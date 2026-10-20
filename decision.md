# SlotMind — Architecture Decisions

AI re-scheduling engine for CareBridge clinics. This file records every architectural decision, the reasoning behind it,
the tradeoffs we accepted, and a traceability matrix proving every task in the problem statement is covered
(Sprint 1 T-1…T-6 + bonus, Sprint 2, Sprint 3).

Format per decision: **Context → Options → Decision → Tradeoffs**.

---

## 0. Constraints that shaped everything

| Constraint | Consequence |
|---|---|
| 2 hours total, solo, all 3 sprints | Monolith, in-process events, H2, no message broker, no auth. Every sprint-2/3 feature is an *addition* (new filter, new listener, new entity), never a rewrite. |
| Walkthrough: book → provider offline → suggestion appears → accept updates slot atomically | This path must work at every checkpoint. Built and verified first; extensions layered on top. |
| LLM may be slow / down / wrong at demo time | Engine validates every AI answer and falls back to rule-based. A `mock` LLM provider makes the demo deterministic with no API key. |
| Staff read reasoning aloud to patients | Prompt forces second-person, plain language, no IDs/jargon. Rule-based output is labelled "template text" in the UI. |

## 1. Stack

| Layer | Choice | Why | Rejected |
|---|---|---|---|
| Backend | Java 21, Spring Boot 3.3, Spring Data JPA, Bean Validation | Required by brief | — |
| Build toolchain | Standard **JDK 21** (`javac`), Maven via the `mvnw` wrapper | Conventional setup any reviewer can reproduce on Windows/macOS/Linux; Mockito and all test tooling work without tweaks. *History:* the build machine first had only a JRE, so we briefly compiled with the Eclipse compiler (ECJ). We replaced that with a portable Temurin JDK 21 (no admin rights needed) to remove the workaround | Keeping ECJ (non-standard, broke Mockito's inline mocks on a JRE) |
| DB | H2 in-memory (Postgres-compatible mode), schema via JPA | Zero setup for a 2-hour build; Postgres swap = datasource config only | Postgres in Docker (setup time) |
| Frontend | React 18 + Vite + TypeScript, no UI library | Fastest to scaffold; prototype design ported as plain CSS | Angular 17 (more boilerplate) |
| LLM | One `OpenAiCompatibleLlmGateway` → **provided LiteLLM proxy** (`https://litellm-qc.zycus.net/v1`, model `qwen-cursor`, extra header `product: PC1`); Groq/Gemini/Ollama work via base-url; plus `MockLlmGateway` | LiteLLM, Groq, Gemini and Ollama all speak OpenAI-compatible `/chat/completions` → one client, any provider, chosen by config. API key read from env `SLOTMIND_AI_API_KEY` or git-ignored `application-local.properties` — **never committed** (repo is public) | Provider-specific clients (3× code); key in `application.yml` (leaks in public repo) |
| Async | Spring `@Async` + `@TransactionalEventListener(AFTER_COMMIT)` | Decoupled, no infra | Kafka/RabbitMQ (infra cost; noted as future) |
| API base | `server.servlet.context-path=/api` | Controllers keep the brief's exact paths (`/appointments`, `/providers/{id}/status`…); Vite proxies `/api` | — |

### Persistence & transaction rules (JPA / Hibernate)

- Real JPA `@Entity` classes (Hibernate 6 via Spring Data JPA); `@ManyToOne(fetch = LAZY)` relationships,
  `@ElementCollection` for room equipment, `@Enumerated(STRING)`, `@Version` on `Slot` and `Appointment`,
  unique constraints on `appointment.slot_id` and `suggestion.pending_key`.
- One `JpaRepository` per aggregate; list views use `JOIN FETCH` / `@EntityGraph` (no N+1, no lazy-init errors).
- Query services: `@Transactional(readOnly = true)`. Every state change (book, provider/slot status, accept/reject,
  waitlist accept, complete) is a `@Transactional` service method. `RescheduleOrchestrator.process` runs in
  `@Transactional(propagation = REQUIRES_NEW)` so each affected appointment commits or fails on its own.
- Domain events published inside the transaction, consumed by `@Async @TransactionalEventListener(AFTER_COMMIT)`.
- `spring.jpa.open-in-view=false`; controllers return DTO records mapped inside the transaction, never entities.
- `ObjectOptimisticLockingFailureException` / `DataIntegrityViolationException` → HTTP 409.

## 2. Domain model (T-1)

```
Clinic(id, name)                                                         [Sprint 3: multi-clinic]
Provider(id, name, specialty GENERAL|SPECIALIST|PEDIATRIC,
         status AVAILABLE|BUSY|OFFLINE, clinic, maxDailyAppointments)    [Sprint 2: daily cap]
Room(id, name, clinic, equipment: Set<String>)                           [Sprint 2]
AppointmentTypeRule(type, durationMinutes, requiredEquipment: Set)       [Sprint 2: per-type duration + room needs]
Slot(id, provider, room?, startTime, durationMinutes,
     status OPEN|BOOKED|CANCELLED, @Version)
Appointment(id, patientName, contact?, type ROUTINE|URGENT|FOLLOW_UP,
            provider, slot (unique), status, @Version)
RescheduleSuggestion(id, appointment, recommendedSlot?, alternateProvider?, confidence, reasoning,
            status PENDING|ACCEPTED|REJECTED|SUPERSEDED,
            triggerReason INITIAL|PROVIDER_OFFLINE|SLOT_CANCELLED,
            strategy, fallbackUsed, crossClinic, pendingKey (unique, nullable), createdAt, resolvedAt)
PatientPreference(patientName PK, preferredWindow MORNING|AFTERNOON|ANY, source EXPLICIT|INFERRED)  [Sprint 2]
WaitlistEntry(id, patientName, contact, type, specialty, preferredWindow, clinic,
              status WAITING|OFFERED|BOOKED|DECLINED, offeredSlot?, createdAt)                      [Sprint 3]
Notification(id, appointmentId, channel SMS|EMAIL|LOG, recipient, message, status SENT|FAILED, createdAt)  [Sprint 3]
ActivityEvent(id, at, kind, message)   — the "agent activity" feed shown in the UI
```

### State machines (enforced inside entities, not services)

```
Appointment:  CONFIRMED ──► RESCHEDULE_PENDING ──► RESCHEDULED ──► COMPLETED
                  │                ▲      │              │
                  └──► COMPLETED   │      │ (reject: stays pending, staff asks again)
                                   └──────┴── RESCHEDULED can be disrupted again → RESCHEDULE_PENDING
Slot:         OPEN ◄──► BOOKED ;  OPEN|BOOKED ──► CANCELLED (terminal)
Suggestion:   PENDING ──► ACCEPTED | REJECTED | SUPERSEDED   (SUPERSEDED = replaced by a newer suggestion
                                                              for the same appointment; added beyond brief)
Provider:     AVAILABLE ◄──► BUSY ◄──► OFFLINE   (any → OFFLINE fires the agentic loop)
```

Each entity exposes intent methods (`slot.book()`, `slot.free()`, `slot.cancel()`, `appointment.markReschedulePending()`,
`appointment.moveTo(slot)`, `suggestion.accept()`) that throw `IllegalStateTransitionException` (→ HTTP 409) on an
illegal transition.

## 3. API (T-1) — all under `/api`

| Method | Path | Notes |
|---|---|---|
| POST | `/appointments` | `{patientName, type, providerId, slotId, contact?}` → books slot in one transaction; 409 if slot not OPEN |
| GET | `/appointments?status=` | filterable list |
| GET | `/appointments/{id}` | |
| PATCH | `/appointments/{id}/complete` | lifecycle end |
| POST | `/appointments/{id}/suggest?strategy=` | on-demand, synchronous, `triggerReason=INITIAL` (shown as "Manual") |
| POST | `/appointments/{id}/suggest/stream` | **Bonus** SSE: `status` → `token`… → `suggestion` events |
| GET | `/suggestions?status=` | UI polling source |
| PATCH | `/suggestions/{id}` | `{status: ACCEPTED|REJECTED, actor?: STAFF|PATIENT}`; accept is transactional |
| GET | `/providers` | includes `bookedToday` (load) |
| PATCH | `/providers/{id}/status` | `{status}`; `OFFLINE` fires agentic loop, returns immediately |
| GET | `/slots?providerId=&status=` | |
| PATCH | `/slots/{id}/status` | `{status}`; `CANCELLED` fires agentic loop, returns immediately |
| GET | `/schedule` | providers × slots with appointment + "held by suggestion" — feeds day view and load view |
| GET/PUT | `/config/strategy` | runtime strategy switch `{strategy: AI|RULE_BASED|ROOM_CONSTRAINT}` |
| GET | `/rooms`, `/clinics` | |
| GET/PUT | `/preferences/{patientName}` | `{preferredWindow}` |
| GET/POST | `/waitlist` | |
| POST | `/waitlist/{id}/accept` · `/waitlist/{id}/decline` | staff confirms an auto-fill offer → books |
| GET | `/notifications` | outbox / sent log |
| GET | `/activity` | agent activity feed |
| GET | `/portal/appointments/{id}` | patient self-service view (appointment + open suggestions) |
| POST | `/portal/appointments/{id}/reschedule` | patient asks for a new time (INITIAL trigger) |

Errors: `{ "error": "SLOT_UNAVAILABLE" | "INVALID_STATE" | "NOT_FOUND" | "VALIDATION", "message": "..." }`
with 409 / 409 / 404 / 400.

### JSON views (contract shared by backend and frontend)

```jsonc
SlotView        { "id","startTime":"2026-10-20T11:00:00","durationMinutes":30,"status":"OPEN",
                  "providerId","providerName","roomId","roomName","clinicId","clinicName" }
ProviderView    { "id","name","specialty","status","clinicId","clinicName","maxDailyAppointments","bookedToday" }
AppointmentView { "id","patientName","contact","type","status","providerId","providerName","slot":SlotView }
SuggestionView  { "id","appointmentId","patientName","appointmentType","appointmentStatus",
                  "triggerReason","status","strategy","fallbackUsed","crossClinic",
                  "confidence":0.88,"reasoning","createdAt","resolvedAt",
                  "originalSlot":SlotView,"recommendedSlot":SlotView|null,
                  "alternateProviderId":string|null }
ScheduleView    { "providers":[ { "provider":ProviderView,
                  "slots":[ SlotView & { "appointment":{ "id","patientName","type","status" }|null,
                                         "heldBySuggestionId":string|null } ] } ] }
WaitlistView    { "id","patientName","contact","type","specialty","preferredWindow","clinicId",
                  "status","createdAt","offeredSlot":SlotView|null }
NotificationView{ "id","appointmentId","channel","recipient","message","status","createdAt" }
ActivityView    { "id","at","kind":"INFO|AGENT|OK|WARN|ERROR","message" }
StrategyConfig  { "strategy":"AI","available":["AI","RULE_BASED","ROOM_CONSTRAINT"] }
```

## 4. ADR Q1 — Where does scheduling logic live?

- **Context.** Booking rules (no double booking, legal transitions) and slot *selection* logic are different concerns.
- **Options.** (a) all in services (anemic entities); (b) all in domain; (c) invariants in domain, selection in a domain service.
- **Decision (c).** State rules live **on the entities** (`Slot.book()` refuses unless OPEN). Selection lives in
  `ScheduleEngine` (domain service) + pluggable `SchedulingStrategy`. Conflicts are prevented at the domain boundary
  by three layers: entity guard → `@Version` optimistic lock → DB unique constraint on `appointment.slot_id`.
- **Tradeoffs.** Slightly more code than a fat service; in exchange no code path (HTTP, async loop, waitlist, portal)
  can produce an illegal state.

## 5. ADR Q2 — Runtime strategy switching (T-2)

- **Context.** Rule-based and AI strategies must be switchable at runtime, used by both HTTP and async callers;
  Sprint 2 `RoomConstraintStrategy` must be "implement the contract and register — nothing more".
- **Options.** `if/else` on a flag; factory; Spring bean map registry.
- **Decision.** `interface SchedulingStrategy { String key(); List<SlotRecommendation> recommend(SchedulingContext ctx); }`.
  Spring injects all beans into `StrategyRegistry` (`Map<key, strategy>`). Active key comes from
  `slotmind.scheduling.strategy` and can be changed live via `PUT /config/strategy` or per request (`?strategy=`).
  Both the HTTP endpoint and the agentic loop call `ScheduleEngine.recommend(...)` → same contract.
- **Two-stage pipeline.** The engine first builds the **candidate set** through `CandidateFilter`s (hard constraints),
  then the strategy **ranks** candidates. Strategies never see an illegal slot.
  Filters: `OpenFutureSlotFilter`, `ProviderOnlineFilter`, `DurationFitFilter` (S2), `RoomEquipmentFilter` (S2),
  `ProviderDailyCapFilter` (S2), `ClinicScopeFilter` (S3). New constraint = new `@Component` filter.
- **Tradeoffs.** Hard constraints apply to *every* strategy (safer than letting each strategy remember them);
  the cost is that a strategy cannot deliberately break a constraint (we never want that).

## 6. ADR Q3 — Transactional accept, no double-booking (T-1, AGT-3)

- **Decision.** `SuggestionService.accept()` is one `@Transactional` method:
  1. load suggestion → must be PENDING (else 409 INVALID_STATE)
  2. load appointment → must be RESCHEDULE_PENDING
  3. load new slot → must be OPEN, provider not OFFLINE, filters re-checked (else 409 SLOT_UNAVAILABLE)
  4. `oldSlot.free()` (if it was BOOKED), `newSlot.book()`, `appointment.moveTo(newSlot)`, `suggestion.accept()`,
     supersede other pending suggestions of that appointment
  5. publish `SlotFreed(oldSlot)` and `SuggestionAccepted` (handled **after commit**)
- **Race (two staff accept two suggestions pointing at the same slot):** both read slot v1 OPEN; first commit bumps
  `@Version` → second flush throws `ObjectOptimisticLockingFailureException` → mapped to **409 SLOT_UNAVAILABLE**,
  whole transaction rolled back (appointment, old slot untouched). Backstop: unique constraint on
  `appointment.slot_id` makes a double booking impossible even if code is wrong.
- **Options rejected.** Pessimistic `SELECT … FOR UPDATE` (holds row locks across LLM-free but still user-paced
  flows; contention is rare at a front desk so optimistic wins); app-level `synchronized` (breaks with >1 instance).
- **UI consequence.** On 409 the card shows "Slot no longer free — get a new suggestion"; nothing changed server-side.

## 7. ADR Q4 / AGT-1 — Agentic loop decoupling (T-4)

- **Context.** Provider OFFLINE affects N appointments; slot CANCELLED affects ≤1. Endpoints must return immediately.
- **Options.** One generic handler with branching; two events + two handlers; scheduled poller.
- **Decision.** Two **domain events** (`ProviderWentOffline`, `SlotCancelled`) published by the PATCH services,
  two small `@Async @TransactionalEventListener(AFTER_COMMIT)` handlers whose only job is *finding affected
  appointments*, and **one shared** `RescheduleOrchestrator.process(appointmentId, trigger)` that does the work in its
  own transaction per appointment (one failure doesn't block others).
  - Different *selection* (N vs 1) → separate handlers; identical *processing* → shared orchestrator.
  - `AFTER_COMMIT` → the loop never runs for a status change that rolled back.
- **Idempotency.** Skip if a PENDING suggestion exists for the same appointment + triggerReason. Enforced twice:
  service check + DB unique column `pendingKey = appointmentId:trigger` (set only while PENDING, NULL otherwise —
  NULLs don't collide).
- **Failure.** AI failure → engine falls back to rule-based (`fallbackUsed=true`), never a silent drop. If even
  rule-based finds nothing, a suggestion with no slot is stored (`recommendedSlot=null`, reasoning explains, UI offers
  waitlist) — still visible to staff.
- **Human checkpoint.** Loop only *proposes*. Booking changes only on `PATCH /suggestions/{id}` ACCEPTED, after the
  front desk confirms with the patient.
- **Tradeoffs.** In-process events are lost if the JVM dies mid-loop. Accepted for the hackathon; production path =
  transactional outbox table + worker, or Kafka. Recorded as deferred.

## 8. AI scheduling strategy (T-3, AGT-2)

- **Gateway.** `LlmGateway.callLLM(systemPrompt, userPrompt) → String` (matches Addendum B). Implementations:
  `OpenAiCompatibleLlmGateway` (`slotmind.ai.provider=litellm|groq|gemini|ollama`, base-url/model/key/extra-headers
  from config, 15s timeout; default = provided LiteLLM proxy, model `qwen-cursor`, header `product: PC1`)
  and `MockLlmGateway` (`provider=mock`, deterministic JSON, used when no key → demo never depends on wifi).
- **Two prompts.** `PromptFactory` picks by trigger:
  - `PROVIDER_OFFLINE`: "the booked doctor is unavailable today; an alternate provider with the same specialty is
    expected; weigh continuity (follow-ups) and urgency."
  - `SLOT_CANCELLED`: "prefer the same provider's next opening; only change doctor if none."
  - `INITIAL` (manual) reuses the cancelled-style prompt with "staff requested a new time".
- **Context given to the LLM.** appointment (type, urgency, original time, provider), patient preferred window (S2),
  numbered candidate list (slotId, time, duration, provider, specialty, room, clinic, provider load) — already
  filtered, so the model only chooses among legal options — and the trigger context.
- **Output.** `{"slotId","alternateProviderId","confidence","reasoning"}` (JSON mode where supported; else first
  `{…}` block extracted).
- **Validation (engine, applies to every strategy):** slotId ∈ candidates (exists, OPEN, legal) · alternateProviderId
  null or equals the slot's provider · confidence clamped 0..1 · reasoning non-empty and contains no `SLT-`/`PRV-` ids.
  Any failure/timeout/exception → rule-based fallback, `fallbackUsed=true`, activity log WARN.
- **Patient-friendly reasoning.** System prompt: "second person, 2–3 sentences, no IDs, no jargon, it will be read
  aloud". Example included in prompt.
- **Bonus SSE.** `POST /appointments/{id}/suggest/stream` (`SseEmitter`): emits `status` events, then the validated
  reasoning in `token` chunks, then a final `suggestion` event with the saved SuggestionView.
  Tradeoff: we stream *after* validation (pseudo-stream) rather than raw LLM tokens, so the user never sees text for a
  slot that later fails validation. True token streaming is a gateway-level change.

## 9. ADR Q5 — Extensibility & exclusions

- Room/equipment constraints plug in as `CandidateFilter`s (hard) and `RoomConstraintStrategy` (ranking) — done in S2.
- Deferred (documented, not built): authentication/roles, durable outbox/Kafka, real SMS/email provider credentials,
  multi-day calendar, Postgres migrations (Flyway), provider working-hours model, true LLM token streaming.

## 10. Sprint 2 — Constraints & preferences

| Item | Decision |
|---|---|
| Room & equipment per appointment type | `AppointmentTypeRule` table (type → required equipment), `Room.equipment`, `RoomEquipmentFilter` rejects slots whose room lacks it. Seed: URGENT needs `TRIAGE`; PEDIATRIC providers' rooms carry `PEDIATRIC`. |
| `RoomConstraintStrategy` as 3rd option | Implements `SchedulingStrategy`, registered as bean `ROOM_CONSTRAINT`. Ranking: same provider → same room → best-fit room (fewest surplus equipment, keeping specialised rooms free) → earliest. Zero changes elsewhere — proves the extension point. |
| Patient preference history | `PatientPreference` (EXPLICIT via `PUT /preferences/{name}`) else INFERRED from past appointments (majority before/after noon) else ANY. Rule ranking uses it as tie-break within a tier; AI prompt receives it. |
| Duration varies by type | `AppointmentTypeRule.durationMinutes` (ROUTINE 30, FOLLOW_UP 20, URGENT 45); `DurationFitFilter` requires `slot.durationMinutes ≥ required`. Booking also validated. |
| Provider max daily appointments | `Provider.maxDailyAppointments`; `ProviderDailyCapFilter` + re-check inside accept transaction. |

## 11. Sprint 3 — Waitlist & notifications

| Item | Decision |
|---|---|
| Waitlist auto-fill when slot opens | `SlotFreed` event (from accept, or slot set back to OPEN) → `@Async AFTER_COMMIT` `WaitlistAutoFillListener` → best WAITING entry (specialty, duration, clinic, preferred window; ordered URGENT first then longest wait) becomes `OFFERED` with that slot. Staff confirms `POST /waitlist/{id}/accept` → transactional booking (same slot guards). Human checkpoint preserved. |
| SMS/email on accept | `SuggestionAccepted` / waitlist booking → `NotificationService` writes `Notification` row then calls `NotificationSender` (`LoggingNotificationSender` default; channel by contact: `@` → EMAIL, digits → SMS). Runs after commit so a send failure can never roll back a booking; failure stored as FAILED. |
| Multi-clinic routing | `Clinic` entity; `ClinicScopeFilter` restricts to the patient's clinic first; if no candidate, engine widens to all clinics and marks `crossClinic=true`; reasoning names the clinic. |
| AI considers downstream queue pressure | Context carries each provider's `bookedToday / maxDaily`; AI prompt includes it ("avoid overloading"); rule ranking uses lower load as tie-break. Load visible in UI provider view. |
| Patient self-service portal | `/portal/appointments/{id}` (view + pending suggestion) and `/portal/appointments/{id}/reschedule`; accept via the same `PATCH /suggestions/{id}` with `actor=PATIENT` → same transaction & guards. Frontend route `#/portal/APT-001`. |

## 12. Front-desk UI (T-5)

Design reference: prototype artifact (clean clinical SaaS). React app, polling every 5 s.

- **Floor:** queue of `RESCHEDULE_PENDING` appointments with inline suggestion (time, provider, confidence,
  reasoning, AI vs rule/fallback source), Accept/Reject, trigger badge (Provider offline / Slot cancelled / Manual),
  provider list with status + status switch, slot list/day view, loading skeletons, error banner when polling fails,
  409 conflict message on accept.
- **Ceiling:** full day view (providers × time, colour by appointment type, held/cancelled states), waitlist panel with
  offers, appointment-type colour coding, provider load view, strategy switch, activity feed, notifications log,
  booking form, patient portal page.

## 13. Seed data

Addendum A's providers/slots/appointments with **the exact IDs** (PRV-001…005, SLT-001…006, APT-001…004), times
relative to start-up (`now + N minutes`) exactly as in the addendum, plus extra OPEN slots, two rooms per clinic, a
second clinic, type rules, two waitlist entries and one explicit preference, so every feature is demoable.
Demo tip from the brief holds: `PATCH /providers/PRV-001/status OFFLINE` → two suggestions (APT-001, APT-002).

## 14. Testing strategy (time-boxed)

Integration tests on the paths that carry the points: (1) accept is atomic and a second accept of the same slot
gets 409; (2) provider OFFLINE produces exactly one suggestion per affected appointment and is idempotent;
(3) invalid AI output (unknown slot) falls back to rule-based.

## 15. Decisions made during implementation

| # | Decision | Reasoning |
|---|---|---|
| 1 | Extra hard filter `SpecialtyMatchFilter`: an alternate provider must share the original specialty | A pediatric visit must stay pediatric. Enforcing it as a filter means neither the AI nor any strategy can break it; if nothing qualifies, the staff get a "no slot, offer waitlist" suggestion |
| 2 | Engine prefers slots not already held by another patient's PENDING suggestion; a held slot is used only if nothing else is legal | Spreads the N suggestions from one provider going offline across different slots, so accepting one doesn't make the others stale. Optimistic locking remains the guarantee; this just reduces conflicts |
| 3 | Unknown `?strategy=` → 400; unknown configured strategy → rule-based + WARN | Fail loudly on caller error, degrade gracefully on config error |
| 4 | Reject keeps the appointment `RESCHEDULE_PENDING`; the next suggestion excludes slots the patient already rejected; a manual/portal request supersedes older pending suggestions. "Suggest another" on an appointment already being re-scheduled **keeps the original trigger** (e.g. PROVIDER_OFFLINE); only a request on a confirmed appointment is INITIAL/Manual | No ping-pong of the same rejected slot; at most one live suggestion per appointment. Keeping the trigger means the right prompt (offline vs cancelled) is used again and the card keeps its badge and filter (found in manual testing: the card switched to "Manual" and vanished under the "Provider offline" filter) |
| 4b | UI refresh after any action always re-fetches; only background polls may share an in-flight request, and older responses are discarded | Manual testing showed a race: refreshing after Reject reused a poll started *before* the click, briefly showing the rejected time and doctor again |
| 5 | Waitlist decline → entry `DECLINED`, the slot is offered to the next matching entry | The freed slot keeps working for the clinic |
| 6 | Reasoning is phrased as an **offer** ("we can offer you… would that work?"), never "your appointment is now…" | Live testing showed the model wrote "your appointment is now at 5:05 PM" before staff had confirmed it. That misstates a pending proposal (human checkpoint, T-4), so the prompt now says so explicitly. Post-accept notifications do say "has been moved" |
| 7 | Prompt forbids guessing doctors' gender | Live testing showed the model writing "she" for a doctor it knew only by name |
| 8 | IDs: `RM-` rooms, `CLN-` clinics, `SUG-` suggestions, `WL-` waitlist; reasoning containing any internal id is rejected → fallback | IDs are system jargon that staff must not read to patients |
| 9 | Known limitation: when a slot is booked, another patient's pending suggestion for that same slot is not auto-regenerated; accepting it returns 409 and the UI asks for a new suggestion | Rare (decision 2 avoids it whenever another legal slot exists). Auto-regeneration on `SlotBooked` is a small follow-up listener |

## 16. Traceability — every task in the brief

| Brief item | Where |
|---|---|
| T-1 Appointment/Provider/Slot/RescheduleSuggestion + lifecycles | §2, `domain/*` |
| T-1 six endpoints | §3 |
| T-1 transactional accept, no double booking | §6 |
| T-2 contract, rule-based (same provider nearest → same specialty nearest), runtime switch, shared by HTTP+async, RoomConstraint = register only | §5, §10 |
| T-3 LLM inputs, output JSON, validate ids/OPEN, fallback, two prompts | §8 |
| T-3 bonus SSE | §8 |
| T-4 OFFLINE → all CONFIRMED; CANCELLED → one; async; dedupe; fallback; human checkpoint | §7 |
| T-5 floor + ceiling | §12 |
| T-6 ADR (logic placement, switchability, transactional accept, loop decoupling, extensibility) + walkthrough | §4–§9, `ADR.md` |
| AGT-1 / AGT-2 / AGT-3 | §7 / §8 / §6 |
| Sprint 2 (5 items) | §10 |
| Sprint 3 (5 items) | §11 |
| Submission: public repo, 5-min demo, `ADR.md` | README demo script + `ADR.md` |
