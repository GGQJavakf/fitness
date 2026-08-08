# AI-primary plan generation requirements

## Scope

Replace duration-to-exercise-count rule generation with an AI-primary workflow. The AI uses
the authenticated user's training profile, eligible exercise catalog, structured preferences,
and optional additional requirements. The backend remains authoritative for eligibility,
time, prescription bounds, recovery, locks, and persistence. When AI is unavailable or its
proposal remains invalid after one repair attempt, the product returns a clearly labelled
fallback rule plan.

## User stories and acceptance criteria

### R1 — AI uses complete user context

- When the user requests a plan, the system shall provide the AI only the normalized training
  profile, eligible exercise facts, rule constraints, structured preferences, and the user's
  bounded additional requirements.
- When additional requirements are supplied, the system shall treat them as untrusted
  training preferences and shall not interpret medical, injury, rehabilitation, or
  prompt-control text as plan instructions.
- When the same duration is used with different goals, experience, or additional
  requirements, the AI shall be allowed to produce meaningfully different valid structures.

### R2 — Duration is a budget, not an exercise-count mapping

- While generating or validating a plan, the system shall treat session minutes as a maximum
  time budget and shall not require a fixed exercise count for 30/45/60/75/90 minutes.
- When a 45-minute AI proposal contains any valid count from one through the configured
  maximum, the backend shall accept or reject it based on time, eligibility, volume,
  recovery, and prescription constraints rather than a four-exercise target.
- When a fallback plan is required, its exercise count shall emerge from template content,
  goal prescriptions, safety constraints, and remaining time rather than a duration lookup.

### R3 — Structured AI proposal and authoritative validation

- When AI returns a proposal, the client shall accept only the closed JSON plan schema and
  exercise codes present in the server-provided eligible catalog.
- When the proposal contains unknown exercises, duplicate exercises, invalid numeric bounds,
  an incorrect training-day count, or an estimated duration over budget, the backend shall
  reject it and return actionable issue codes without storing a candidate.
- When user locks are supplied, the backend shall preserve them before validation and shall
  reject a proposal that cannot satisfy their paths or values.
- The AI shall never provide an absolute starting weight; bodyweight/calibration state shall
  be derived from authoritative exercise facts.

### R4 — One bounded repair, then explicit fallback

- When the first AI proposal is structurally or semantically invalid, the client shall request
  at most one repair using only the backend validation issue codes and paths.
- When the configured AI provider is unavailable or times out, its output is malformed or
  unsafe, or its proposal is still invalid after repair, the client shall request a
  deterministic fallback plan without resubmitting that invalid proposal.
- Authentication, profile-version, generation-context, HTTP, and API-contract failures shall
  remain visible failures and shall not be disguised as AI unavailability.
- When a fallback plan is returned, the candidate and active-plan UI shall identify it as a
  fallback plan and shall not present it as AI-personalized.
- When an AI proposal passes backend validation, the UI shall identify it as AI-personalized
  and rule-validated.

### R5 — User-entered additional requirements

- While completing onboarding, the user shall be able to enter an optional additional
  training requirement of at most 300 characters.
- The field shall explain that it is for non-medical preferences such as focus areas,
  preferred training style, or scheduling emphasis.
- The draft shall survive in-session onboarding navigation and shall be included in every AI
  generation or repair request for that submission.
- Existing structured preferences shall be loaded into onboarding and shall not be overwritten
  unless the user actually changes them.

### R6 — Compatibility and durability

- Existing clients that omit an AI proposal shall continue to receive a valid fallback plan.
- Candidate activation, immutable plan versions, user editing, workout snapshots, and
  idempotency behavior shall remain unchanged.
- A single onboarding submission shall be single-flight so repeated taps cannot create
  concurrent profile writes or candidate-generation requests.
- No production deployment, credential change, permission change, or database migration is
  part of this change.

## Non-goals

- Medical diagnosis, injury adaptation, rehabilitation prescriptions, or health-risk
  inference from free text.
- AI-generated absolute weight values.
- Public release or production CloudBase resource changes.
