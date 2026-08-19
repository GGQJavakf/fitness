# Post-audit remediation requirements

## Scope

Repair every confirmed P0/P1 and high/medium finding from the first-release audit across plan
composition, AI orchestration, workout durability, API contracts, persistence, privacy, release
configuration, and quality gates. Changes remain local: no production deployment, credential,
permission, or destructive data operation is authorized.

This specification supersedes the exercise-count statements in
`../ai-primary-plan-generation/requirements.md` and the fallback duration statements in
`../first-release-quality-repair/requirements.md`.

## Acceptance requirements

### R1 — Complete 45-minute training sessions

- When a user's session budget is 45 minutes, the deterministic plan composer shall produce
  four or five safe exercises for every training day when at least four eligible exercises
  exist.
- While estimating a session, the composer shall include general warm-up, ramp warm-up,
  work-set execution, inter-set rest, and exercise transitions.
- When safety, equipment, recovery, or catalog constraints leave fewer than four eligible
  exercises, the system shall return a typed underfilled diagnostic instead of silently
  presenting a three-exercise plan as a complete 45-minute session.
- While composing any duration band, the server-side versioned policy shall remain authoritative
  for exercise count, prescription, recovery, duration, and rounding.

### R2 — AI proposals obey the same composition contract

- For the current release, `PLAN_GENERATION` is not an approved online-model purpose. Onboarding
  shall generate plans with the deterministic composer and shall not call the CloudBase AI SDK.
- When AI proposes fewer than the required exercises for a 45-minute day, the backend shall
  reject the proposal with actionable issue paths and shall not register it as a candidate. This
  remains a compatibility boundary for already persisted candidates and any separately approved
  future proposal source; it does not authorize an online call in this release.
- When a plan is presented, the UI and persisted version shall expose whether the accepted
  source is `AI_PERSONALIZED` or `FALLBACK_RULE_PLAN`; newly generated current-release plans shall
  be presented truthfully as rule-generated rather than as an AI failure fallback.
- When AI initialization, configuration, eligibility, SDK, or response-contract faults occur,
  the system shall preserve their typed category; only transient timeout, throttling, and
  temporary provider failures may use ordinary fallback.

### R3 — Approved AI data boundary

- `PLAN_GENERATION` shall remain outside the approved purpose allowlist until a separate product,
  privacy, consent, and release decision explicitly approves it.
- Unless an explicit release flag and user consent are both present, the mini program shall not
  call an online model.
- When online AI is enabled, the request shall contain only approved structured training facts;
  it shall not contain user identifiers, authentication data, contact data, raw profile objects,
  or unrestricted free text.
- When AI approval or model readiness is absent, release preflight shall fail closed instead of
  relying on runtime fallback to hide the configuration gap.

### R4 — Workout lifecycle and local durability

- When a training day is still inside the server-calculated recovery window, starting a session
  shall return a typed confirmation challenge without creating a session. Continuing shall require
  a short-lived, single-use server token bound to the authenticated user, plan/version, training
  day, exact client session key, and current recovery-fact fingerprint.
- When a client requests a generic workout status transition, it shall not be able to write
  `COMPLETING` or `COMPLETED`; completion shall pass through the authoritative completion use
  case and its completeness/progression checks.
- When an unfinished local draft exists, starting from Plan or History shall resume it or ask
  for an explicit abandon decision before creating another server session.
- While resume, record-set, timer, replacement, and synchronization commands target the same
  workout, their local writes shall be serialized or revision-checked so a late operation
  cannot overwrite a newer fact.
- When a synchronization operation is acknowledged, rejected, or conflict-resolved, the local
  queue shall converge to an explicit terminal or rebuilt operation state and shall not block
  completion forever.
- When a local draft is corrupt, startup shall preserve authentication and expose a dedicated
  recovery state instead of presenting the failure as a login or network error.
- When any device starts a workout while the same user already owns a non-terminal server session,
  the server shall return that authoritative session and its effective recorded sets without
  creating a second session. A same-key replay may return `201` only while the original session is
  still pristine `CREATED`; an ended same-key replay shall return a typed terminal result that lets
  the client clear the durable intent and require one fresh user action.

### R5 — API truth, errors, and idempotency

- When the OpenAPI document contains a route, the Spring application shall expose the same
  method and normalized path; when Spring exposes an API route, the document shall contain it.
- When a business request header, UUID, enum, or body field is missing or invalid, the API shall
  return the documented validation envelope; only authentication failures shall return 401.
- When an unexpected server fault occurs, the API shall return a redacted `INTERNAL_ERROR`
  envelope correlated by request id.
- When a progression decision is retried with the same idempotency key and payload, the API
  shall replay the original result without creating another plan version; a different payload
  with the same key shall return `IDEMPOTENCY_KEY_REUSED`.
- When the workout-set delete contract is invoked, the implementation shall use an auditable
  logical void operation compatible with immutable workout facts; summaries and progression
  shall ignore voided facts.
- When an exercise action is not implemented, OpenAPI shall not advertise it. Compatibility
  field `planDayId` shall be retained while the truthful `trainingDayCode` is introduced.

### R6 — Durable asynchronous and shared operational state

- When workout completion commits, a durable outbox record shall be committed atomically so
  progression processing eventually occurs exactly once after restart.
- While multiple instances serve privacy and authentication flows, one-time proofs, warning
  tokens, rate limits, and login throttles shall use shared atomic state rather than process
  memory.
- When an account is deleted, revoked, explicitly logged out, or switched, the mini program
  shall purge all user-scoped `fitness.*` session, draft, revision, queue, and temporary-export
  data.

### R7 — Bounded queries and caches

- When workout history is queried with any supported page size, SQL round trips shall remain
  constant rather than growing per session or exercise.
- When progression recommendations are listed, cursor pagination shall bound response size and
  avoid duplicate or missing records across pages.
- When plan-candidate cache entries expire or capacity is reached, maintenance shall remove old
  entries and keep memory below a configured upper bound.

### R8 — Release and quality gates

- When public/staging preflight runs without an explicitly supported non-local Spring profile,
  it shall fail; local or test profiles shall never pass a release gate.
- When a release artifact is checked, the gate shall start the packaged application with the
  target profile and verify health, datasource, Flyway, authenticated business routes, and
  mini-program-only ingress expectations.
- When critical UI behavior is disconnected, routed with the wrong identifier, or loses its
  retry/recovery action, interaction tests shall fail without relying on source-string checks.
- When backend or frontend contract shapes drift at runtime, consumer-driven samples and real
  HTTP smoke tests shall fail.
- A single repository verification entry shall run API generation/drift checks, type checking,
  tests, coverage thresholds, builds, release preflight, Spring verification, and route parity.

## Non-goals and safety boundary

- No production deploy, mini-program upload, credential rotation, permission change, public ingress
  change, or remote schema execution without separate explicit authorization. An authorized external
  database run is limited to the exact disposable schema and verified-TLS/secret-handling gates in
  the release harness; it never authorizes destructive cleanup of an existing schema.
- No medical diagnosis, rehabilitation prescription, or unsafe exercise filling to satisfy a
  count target.
- Existing immutable plan versions and workout facts remain readable; corrections are new
  versions or append-only void/outbox/idempotency records.
