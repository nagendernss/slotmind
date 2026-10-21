# ADR — SlotMind AI Re-Scheduling Engine

Format: **Context → Options → Decision → Tradeoffs**. Full decision log with every task traced: [`decision.md`](decision.md).

---

## ADR-1 · Where does scheduling logic live?

**Context.** Two different concerns: *booking invariants* (a slot holds one appointment; only legal status transitions)
and *slot selection* (which open slot is best for this patient).

**Options.** (a) Everything in services with anemic entities. (b) Everything in the domain. (c) Invariants in entities,
selection in a domain service.

**Decision — (c).** Entities own their state machines through intent methods (`Slot.book()`, `Slot.free()`,
`Appointment.markReschedulePending()`, `Appointment.moveTo(slot)`, `RescheduleSuggestion.accept()`), which throw on an
illegal transition (→ HTTP 409). Selection lives in `ScheduleEngine` plus pluggable strategies.
Booking conflicts are stopped at the domain boundary by three layers: entity guard → `@Version` optimistic lock →
DB unique constraint on `appointment.slot_id`.

**Tradeoffs.** Slightly more code than a fat service. In return no caller (HTTP, async loop, waitlist, patient portal)
can create an illegal state, because they all go through the same entity methods.

## ADR-2 · Runtime strategy switching

**Context.** Rule-based and AI strategies must be switchable at runtime and used by both the HTTP endpoint and the async
loop. Sprint 2's `RoomConstraintStrategy` should be "implement the contract and register — nothing more".

**Options.** `if/else` on a flag · factory class · Spring bean-map registry.

**Decision.** `SchedulingStrategy { key(); recommend(SchedulingContext) }`. Spring injects every implementation into
`StrategyRegistry`. The active key comes from `slotmind.scheduling.strategy`, can be changed live with
`PUT /config/strategy`, or overridden per request with `?strategy=`. Both callers go through `ScheduleEngine.recommend()`.

The engine runs a **two-stage pipeline**:
1. `CandidateFilter` beans apply the hard constraints: open & future, provider online, duration fits, room equipment,
   provider daily cap, clinic scope.
2. The strategy only **ranks** legal candidates.

`RoomConstraintStrategy` was added in Sprint 2 as one new `@Component`, with no other code changed.

**Tradeoffs.** A registry is less dynamic than a plugin system, but it is simple and type-safe. Every strategy,
including the LLM, is bound by the hard constraints. The cost is that no strategy can bend a constraint, which we never
want anyway.

## ADR-3 · Transactional accept

**Context.** Accepting a suggestion must update the appointment, free the old slot and book the new one atomically.
Two staff members can accept suggestions that target the same slot at the same moment (AGT-3).

**Options.** Pessimistic row locks (`SELECT … FOR UPDATE`) · optimistic locking · app-level `synchronized`.

**Decision.** One `@Transactional` accept method:
1. The suggestion must be PENDING and the appointment RESCHEDULE_PENDING.
2. The new slot must be OPEN, its provider online, and the filters must still pass.
3. Then: `oldSlot.free()`, `newSlot.book()`, `appointment.moveTo()`, `suggestion.accept()`, and older pending
   suggestions for that appointment become SUPERSEDED.

Slots carry `@Version`. In a race, the second commit throws an optimistic-lock exception, which is mapped to
**409 SLOT_UNAVAILABLE** and rolls back everything. The unique `slot_id` constraint is a backstop. Follow-up effects
(waitlist auto-fill, notifications) run **after commit**, so they can never undo or half-apply a booking.

**Tradeoffs.** Optimistic locking takes no lock while the user decides, and conflicts at a front desk are rare.
The loser gets a clear 409, and the UI asks them to fetch a new suggestion. Pessimistic locking was rejected because of
lock contention, and `synchronized` because it breaks with more than one instance.

## ADR-4 · Agentic loop decoupling

**Context.** Provider OFFLINE affects N appointments; slot CANCELLED affects at most one. Both endpoints must return
immediately, the loop must not create duplicate suggestions, and an AI failure must not drop work silently.

**Options.** One generic handler with branching · two events with separate handlers · a scheduled poller.

**Decision.** The PATCH services publish domain events (`ProviderWentOffline`, `SlotCancelled`). Two small
`@Async @TransactionalEventListener(AFTER_COMMIT)` handlers each only work out *which appointments are affected*, then
call one shared `RescheduleOrchestrator.process(appointmentId, trigger)`. It runs in its own transaction per appointment,
so one failure doesn't block the others.

- **Idempotency:** a service-level check plus a unique `pendingKey = appointmentId:trigger`, which is set only while the
  suggestion is PENDING.
- **AI failure:** the engine falls back to rule-based (`fallbackUsed=true`, WARN in the activity feed).
- **No slot found:** a suggestion without a slot is stored, so staff still see the appointment and can use the waitlist.
- **Human checkpoint:** the loop only proposes. Bookings change only when staff accept, after confirming with the patient.

**Tradeoffs.** Selection differs between the two triggers, so the handlers are separate; processing is identical, so
the orchestrator is shared. `AFTER_COMMIT` means the loop never reacts to a rolled-back change. In-process events are
lost if the JVM crashes mid-loop; production would use a transactional outbox or Kafka (deferred).

## ADR-5 · Extensibility and exclusions

**Room and equipment constraints (Sprint 2)** plug in as a `CandidateFilter`, which every strategy respects, plus
`RoomConstraintStrategy` as a third ranking option.

Other extension points used in Sprint 2 and 3:
- Per-type durations and requirements: `AppointmentTypeRule`.
- Provider daily cap: a filter, re-checked inside the accept transaction.
- Patient preferences: explicit, or inferred from history.
- Waitlist auto-fill: a listener on `SlotFreed`.
- Notifications: a listener on `SuggestionAccepted`, written through an outbox table.
- Multi-clinic: a clinic-scope filter that widens to other clinics when the home clinic has nothing.
- Queue pressure: provider load added to the context and the prompt.
- Patient portal: reuses the same accept transaction with `actor=PATIENT`.

**AI design.** One OpenAI-compatible gateway serves the provided LiteLLM proxy, Groq, Gemini and Ollama. Without an API
key the AI is treated as unavailable, so suggestions use the rule-based fallback and are labelled that way. A
deterministic mock gateway exists only for automated tests. There are **two prompts**:
- **Provider offline:** expect an alternate provider with the same specialty; weigh continuity of care and urgency.
- **Slot cancelled:** prefer the same provider's next opening.

The model only sees pre-filtered candidates. Its output is validated: the slot must be one of the candidates, the
provider must match that slot, and the reasoning must not contain IDs. The reasoning is written in the second person so
staff can read it aloud. SSE `/suggest/stream` streams the reasoning *after* validation.

**Deferred.**
- Authentication and roles.
- Durable outbox worker or Kafka.
- Real SMS and email providers (a logging sender is used).
- Multi-day calendar and provider working hours.
- Flyway migrations for Postgres.
- True token-level LLM streaming.
