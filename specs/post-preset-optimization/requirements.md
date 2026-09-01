# Post-preset optimization requirements

## Scope

Complete the follow-up items discovered after the four persona preset rollout: persona-aware
preset selection, truthful equipment capability, four-day bodyweight generation, atomic candidate
editing, package-budget hardening, per-preset provenance/safety governance, and executable device
acceptance. Changes stay local and reversible. They do not authorize production activation,
mini-program upload, deployment, credential changes, or public content approval.

## Acceptance requirements

### R1 — Persona-aware preset recommendation

- When an authenticated user with a profile lists system presets, the backend shall evaluate the
  same experience, goal, frequency, duration, and location fields used by preset activation.
- Every result shall be `EXACT` or `PARTIAL`, expose every mismatching field, and a non-empty result
  shall contain exactly one deterministic `recommended` item.
- Exact matches shall sort first. Remaining ties shall prefer goal, location, experience distance,
  frequency distance, duration distance, and finally stable catalog order/code.
- The mini program shall initially highlight the server-recommended item. A partial match shall
  explain the profile fields to adjust and shall not send a candidate request that is guaranteed to
  fail profile matching.
- Recommendation never proves equipment compatibility; existing server-side exercise eligibility
  validation remains authoritative.

### R2 — Four-day bodyweight capability without weakening gym plans

- When onboarding has no load equipment, the client shall not pre-filter the server to a loaded
  training split before template eligibility is known.
- For the reviewed four-day bodyweight template only, an upper-body day may use pushing, posterior
  shoulder/back-control, and trunk-stability patterns without requiring direct elbow-flexion and
  elbow-extension isolation.
- Loaded/gym upper-body templates shall retain their current movement-pattern requirements.
- Beginner + HOME + no equipment + four days shall produce exactly four eligible bodyweight days;
  five- and six-day bodyweight capacity shall remain unavailable unless separately reviewed.

### R3 — Atomic candidate-edit commit

- Opening or cancelling candidate editing shall create no plan version.
- Saving an edited candidate shall validate and commit only the final edited plan in one server
  transaction. A first save shall create v1, not an unedited v1 followed by edited v2.
- Validation error, unconfirmed warning, stale active version, expired/foreign candidate, storage
  failure, or response-loss retry shall not create an intermediate active plan.
- The command shall be idempotent by a non-secret request key and semantic payload digest. Same-key
  same-payload retries return the same version; different payloads return a typed reuse conflict.
- Existing direct activation remains compatible for users who accept a candidate without editing.

### R4 — Preset provenance and safety truth

- Every preset shall reference its own structured source identifiers; an unrelated personal-plan
  source shall never be inherited by the four persona presets.
- Source title, URL, usage boundary, content status, and professional-review status shall survive
  loading and be available to the preset preview. A completed review shall additionally require its
  record identifier and review date; pending review shall not fabricate either value. UI shall not
  claim traceability without evidence.
- `AI_VALIDATED` content shall remain unavailable to public activation. Only separately signed
  `PUBLIC_RELEASE_APPROVED` preset versions may pass a future public gate.
- A low-impact claim shall be machine-enforced. Missing or above-limit impact classification shall
  fail closed across catalog validation, candidate validation, editing, and replacements.
- Per-side duration semantics shall be versioned and shared by all estimators. Training dosage shall
  not be silently reduced or relabelled merely to hide a duration overrun; affected plans require a
  recorded professional decision before content changes.

### R5 — Package budgets with useful headroom

- The build shall keep every effective WeChat package below the 2 MiB hard limit, including shared
  top-level JavaScript attributed to non-independent subpackages.
- `subpackages/exercise-guide` shall have a fail-closed release budget with at least 128 KiB of
  effective headroom, and
  generated guide assets shall have deterministic per-file and aggregate budgets.
- Large asynchronous business chunks shall have measured warning thresholds and ownership output;
  a warning must identify the largest contributors without changing Android startup boundaries.
- Asset optimization shall preserve manifest identity, dimensions sufficient for mobile guidance,
  image readability, and deterministic rebuild output.

### R6 — Truthful equipment inventory

- Equipment availability and load profile shall be separate concepts. Non-loaded equipment such as
  a bench shall not require a fabricated KG level.
- Loaded equipment shall declare KG interpretation (`PER_HAND`, `TOTAL`, or `STACK_DISPLAY`),
  increment, and available levels; unknown types or load modes fail closed.
- A plan/workout shall resolve the concrete equipment profile used when multiple items share a type.
- The mini program shall provide a dedicated, round-trippable inventory editor only after the new
  contract and migration are verified; a temporary checkbox-only UI is not completion evidence.

### R7 — Simulator, device, review, and release evidence

- Local backend plus WeChat DevTools shall cover the four persona presets and the generic four-day
  bodyweight journey from fresh identity through candidate preview, activation, and preparation.
- The release candidate shall provide an Android/iOS matrix for cold start, cache refresh, process
  kill, background/foreground, lock/unlock, and weak/offline recovery with device, OS, WeChat, base
  library, build fingerprint, and observed result.
- Simulator evidence shall never be reported as physical-device evidence.
- After implementation, every reviewable file shall be covered by OCR preview/rule review; material
  findings shall be fixed and one fresh review pass run, with a maximum of two independent passes.

### R8 — Reviewed four-persona preset prescriptions

- Persona gender is test data only. User-facing plan names and dosage shall be gender-neutral and
  shall not infer lower-body emphasis, lower effort, or different progression from sex.
- Beginner presets shall expose a structured two-week introductory phase: two work sets per
  exercise, target RIR 3–4, then transition to the recorded target sets only when technique and
  recovery are ready. The phase shall be visible in preview and retained by the preset plan rules.
- The beginner three-day gym hypertrophy preset shall contain exactly four exercises per day using
  the reviewed A/B/C prescriptions: squat/push/pull/core, hinge/vertical push/pull/glute bridge,
  and squat/floor press/row/bird dog. Its conservative per-side duration shall fit 45 minutes.
- The intermediate four-day gym hypertrophy preset shall keep upper/lower structure, contain five
  or six exercises per day, preserve normal compound-movement rest, and fit 60 minutes under the
  shared conservative duration estimator, including unilateral work.
- The four-day equipment-free home preset shall be named truthfully as no-jump general conditioning,
  contain three or four actions per day, contain no duplicate push-up variations within one day,
  and carry an immutable `NO_JUMP` plan constraint. Unknown or jumping impact data shall fail closed
  during catalog load, candidate validation, edit validation, additions, and replacements.
- The requested three-day home band push/pull/legs preset requires both resistance-band availability
  and a confirmed safe fixed anchor. Until Equipment Inventory V2 can represent both capabilities,
  it shall remain in the catalog as `BLOCKED_CAPABILITY`, shall not be recommended or generated, and
  shall explain the exact missing capability. The existing bodyweight prescription shall not be
  presented as an equivalent substitute.
- All four persona presets shall remain professional-review `PENDING`; public activation stays off.

## Non-goals and safety boundary

- No medical, rehabilitation, pregnancy/postpartum, chronic-disease, medication, supplement, or
  individualized calorie prescription.
- No automatic professional sign-off. Human credentials, review date, preset code/version, and
  content digest are external evidence and cannot be fabricated by code.
- No reset, checkout, clean, broad staging, commit, push, upload, deployment, or public activation.
