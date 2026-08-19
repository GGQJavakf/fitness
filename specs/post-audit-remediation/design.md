# Post-audit remediation design

## Delivery strategy

The repair is split into independently verifiable lanes, but all lanes share the OpenAPI contract,
versioned rule policy, immutable fact model, and a single final verification entry. Behavior changes
are introduced through failing regression tests before implementation.

## Plan composition

Add a versioned session-composition policy. The 45-minute band has `minExercises=4`,
`targetExercises=5`, and `maxExercises=5`. The duration estimator consumes general warm-up,
ramp-set, work-set, rest, and transition parameters from the same rule snapshot.

Current-release onboarding uses the deterministic composer only. `PLAN_GENERATION` is not in the
approved online-model purpose allowlist, so the mini program rejects that purpose before accessing
the CloudBase AI SDK. The backend retains one common validator for compatibility with already
persisted AI candidates and any separately approved future proposal source; such a proposal cannot
relax the minimum composition or numeric prescription policy. If the deterministic composer cannot
safely reach the minimum, the API returns `INSUFFICIENT_ELIGIBLE_EXERCISES` rather than padding with
unsafe duplicates.

## AI safety and observability

Online AI activation for the three currently approved purposes (`PLAN_EXPLANATION`,
`WORKOUT_SUMMARY`, and `ALTERNATIVE_RANKING`) requires an explicit build/release flag plus recorded
user consent. Provider errors are classified into configuration, eligibility, SDK, contract, unsafe
output, transient, timeout, and throttling categories. Only the last three use routine fallback.

Each approved purpose has a dedicated allowlist projection. It excludes identity, tokens, contact
fields, raw profile objects, and unrestricted additional-requirements text. Enabling online plan
generation requires a new explicit purpose/data projection, consent UX, release approval, and
acceptance tests; this remediation does not infer that authorization.

## Workout integrity

Session start is a server-authoritative transactional facade. It serializes starts per user and
reads the exact replay plus any non-terminal session from the same locked snapshot. Only a pristine
`CREATED` exact replay returns the original create result; `IN_PROGRESS`/`PAUSED` replays and
different-key starts return the owned session with effective non-void, non-planned set facts. A
terminal exact replay returns another active session when one exists, otherwise a typed terminal
result so the client can retire the durable key. It then recomputes recovery facts. A recovery warning issues a digest-only,
five-minute, single-use confirmation token and creates no session. Token consumption and session
creation share a serializable transaction and bind the user, plan/version, training day, exact
client session key, and recovery-fact fingerprint.

Client-writable status transitions use a restricted command enum. Completion remains a dedicated
transactional use case. Completion writes an outbox row in the same transaction; a worker claims
and records delivery idempotently.

The mini program exposes `startOrResume`. Per-session commands run through a serial executor and
draft writes use monotonically increasing revisions. Conflict resolution returns the authoritative
facts, server version, and operation identity so the queue can atomically acknowledge, rebuild, or
abandon the operation. Cross-device or response-loss recovery rebuilds a synced draft from the
server snapshot, reactivates `CREATED`/`PAUSED` sessions, and never skips the general warmup when no
set fact exists. Recovery responses omit absent set fields in accordance with OpenAPI; the client
also accepts legacy `null` optionals, validates them, and normalizes them before rebuilding the draft.
Corrupt drafts and corrupt durable start intents are quarantined or replaced through an explicit
recovery path rather than silently creating another session.

Workout-set deletion is implemented as an append-only void record, not physical deletion. All
read models and progression calculations filter voided facts.

## API and persistence

Spring route metadata and OpenAPI paths are normalized and compared bidirectionally in a context
test. Critical routes additionally run MockMvc response-schema assertions. Error handling separates
authentication header failures from business-header and binding errors and always emits the common
error envelope.

Progression decisions use a durable idempotency record keyed by user, operation, and key, containing
a payload fingerprint and result reference. Workout completion uses an outbox. Privacy proofs and
rate limits use shared database-backed atomic operations for the current MySQL deployment.

Workout history uses list projections and batched aggregates. Recommendation listing uses a stable
`(created_at,id)` cursor. Candidate cache maintenance runs on writes and reads, enforces TTL and a
hard capacity, and remains clock-testable.

## Release and verification

Release preflight requires an explicit supported profile and rejects `local`/`test`. A packaged-jar
smoke verifies health plus application beans/routes without deploying. The mini-program gate verifies
the required base-library and AI approval flags. Critical UI tests render and interact with components
instead of searching source strings.

Remote deployment is not part of this implementation. Database migrations run locally by default;
an external disposable MySQL schema may be used only after separate explicit authorization, with the
strict remote-host, schema-name, TLS trust, secret-injection, empty-schema, and zero-skip gates defined
by the release verification harness. The harness never clears a non-empty external schema.

## Security redlines

The pre-proposal redline route was manually narrowed to the attack surfaces actually changed:

- `HR-AUTH` and `HR-AUTHZ`: trusted identity headers remain valid only behind verified mini-program
  ingress; client-writable status, proof, token, and account ownership checks stay server-side.
- `HR-INPUT`: request headers, UUIDs, enums, cursors, AI JSON, and conflict decisions have closed
  validation before any state change; SQL parameters remain bound values.
- `HR-SENSITIVE-LOG`: API and AI errors expose stable codes/request ids only; tokens, model/provider
  configuration, profile/free text, SQL details, and stack traces never enter client responses.
- `HR-WEB-CONFIG` and `HR-PROTOCOL-OSS-BUILD`: release gates reject local/test profiles and unverified
  ingress/artifacts; this change does not enable public ingress or run deployment commands.
- `HR-PRIVACY`: model use is purpose-gated and consent-gated, data is allowlisted, and logout,
  revocation, deletion, and account switch purge user-scoped local data.

Negative tests cover forged trusted headers, prohibited status transitions, missing or malformed
inputs, idempotency-key reuse, stale revisions, unapproved AI calls, sensitive error leakage, and
cross-instance replay. Positive tests retain genuine mini-program login, valid completion, safe
fallback generation, same-payload replay, and explicit recovery flows.
