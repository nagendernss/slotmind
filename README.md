# SlotMind — AI Re-Scheduling Engine

When a provider goes offline or a slot is cancelled, SlotMind finds every affected appointment, asks an AI scheduling
advisor (with a rule-based fallback) for the best new slot, and queues each suggestion for the front desk to approve.
Accepting a suggestion rebooks the patient in a single transaction, so nothing is ever double-booked.

- Architecture decisions: [`ADR.md`](ADR.md). Full decision log and requirement traceability: [`decision.md`](decision.md)
- Stack: Java 21 · Spring Boot 3 · JPA · H2 · React 18 · Vite · TypeScript

## Run

Prerequisites: **JDK 21+** and **Node 18+**. Maven is not required, because the repo ships the `mvnw` wrapper.

```bash
# 1. Backend  (http://localhost:8080/api)
cd backend
# AI key: env var, or a git-ignored src/main/resources/application-local.properties containing slotmind.ai.api-key=...
export SLOTMIND_AI_API_KEY=sk-...          # PowerShell: $env:SLOTMIND_AI_API_KEY="sk-..."
./mvnw spring-boot:run                      # Windows: mvnw.cmd spring-boot:run
# no key / offline demo: runs automatically with the mock AI when no key is set,
# or force it: ./mvnw spring-boot:run -Dspring-boot.run.arguments=--slotmind.ai.provider=mock
# tests: ./mvnw test   (414 tests; coverage report in target/site/jacoco/index.html)

# 2. Frontend (http://localhost:5173)
cd frontend
npm install
npm run dev
npm test        # 218 tests (Vitest)
```

Patient portal: `http://localhost:5173/#/portal/APT-001` · H2 console: `http://localhost:8080/api/h2-console`

## What's built

| Sprint | Features |
|---|---|
| 1 | Domain model with state machines · REST API · transactional accept with optimistic locking · pluggable strategies (rule-based, AI) switchable at runtime · AI advisor with two prompts, output validation and fallback · async agentic loop that skips duplicate suggestions · front-desk UI · SSE reasoning stream (bonus) |
| 2 | Room and equipment requirements per appointment type · `RoomConstraintStrategy` · patient time preferences (explicit or inferred) · duration per appointment type · provider daily cap |
| 3 | Waitlist auto-fill when a slot is freed · SMS/email notifications on accept (outbox, logging sender) · multi-clinic routing · AI weighs provider queue pressure · patient self-service portal |

## 5-minute demo script

1. **Queue is empty.** Show the providers rail, the day view, and the strategy switch set to *AI advisor*.
2. **Book.** Appointments → New appointment → book a patient into an open slot.
3. **Provider goes offline.** In the rail, click *Mark offline* on **Dr. Sarah Chen** (= `PATCH /api/providers/PRV-001/status`).
   The call returns immediately. Two cards appear, for John Martinez and Emily Watson, each with an AI suggestion,
   a confidence score and reasoning staff can read aloud. The activity feed shows the background loop working.
4. **Accept.** Click *Patient agreed · Accept*. The appointment moves, the old slot is freed and the new one booked.
   The day view updates and a notification is logged.
5. **Concurrency.** Switch to Rule-based, then trigger a case where two suggestions target the same slot. Accept one;
   the other gets **409**, with the message "Slot no longer free".
6. **Slot cancelled.** Day view → click Aisha Patel's slot → *Cancel slot*. A suggestion is created with a different
   prompt, preferring the same doctor and checking for the triage room the urgent visit needs.
7. **Sprints 2 and 3.** Show the room filter, the waitlist offer created when a slot is freed, the patient portal, and
   the SSE stream on *Suggest another*.

```bash
# curl version of the main walkthrough
curl -X PATCH localhost:8080/api/providers/PRV-001/status -H 'Content-Type: application/json' -d '{"status":"OFFLINE"}'
curl 'localhost:8080/api/suggestions?status=PENDING'
curl -X PATCH localhost:8080/api/suggestions/<id> -H 'Content-Type: application/json' -d '{"status":"ACCEPTED"}'
```
