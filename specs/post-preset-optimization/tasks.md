# Implementation plan

## 0.1.2 scope decision (2026-09-01)

- The project side accepts the current fitness-plan prescriptions for the first-version scope.
- Equipment Inventory V2, the generic conservative per-side duration model, and the resistance-band
  safe-anchor capability are deferred to `0.2` and do not block the `0.1.2` release candidate.
- The resistance-band push/pull/legs preset remains `BLOCKED_CAPABILITY`; deferral does not make the
  preset selectable or publicly releasable.
- This is a product-scope decision only. Professional review records remain `PENDING`, and public
  content status must not be changed without qualified sign-off bound to the frozen content digests.

- [x] 1. Audit the seven follow-up items and freeze boundaries
  - Confirm live branch/HEAD/dirty scope and classify implementation, product, professional, device,
    and public-release boundaries.
  - Establish current package measurements and distinguish simulator from physical-device evidence.
  - _Requirements: R1-R7_

- [x] 2. Implement deterministic persona-aware preset recommendation
  - Add the shared backend match policy, OpenAPI fields, stable ordering, unique recommendation,
    default UI highlight, partial-match explanation, and regression matrix.
  - _Requirement: R1_

- [x] 3. Repair the reviewed four-day bodyweight route
  - Stop equipment-free onboarding from prematurely excluding bodyweight templates.
  - Add an explicit bodyweight upper focus without weakening loaded upper-body requirements.
  - Cover the real onboarding request and four-day candidate journey; keep five/six-day limits.
  - _Requirement: R2_

- [x] 4. Replace candidate-edit double writes with an atomic commit
  - [x] Add the backend transaction port/adapters, idempotency receipt migration, warning handling,
    fault-injection, and replay tests.
  - [x] Close the reviewed JDBC concurrency/receipt-constraint findings and switch the OpenAPI/client
    to the atomic endpoint with retry and stale-response coverage.
  - Preserve the existing direct unedited activation contract.
  - _Requirement: R3_

- [ ] 5. Make preset provenance and low-impact claims machine-verifiable
  - [x] Add per-preset structured sources/status/review identifiers and truthful preview UI.
  - [x] Add fail-closed no-jump classification and plan constraint across validation/edit/replacement.
  - [ ] **DEFERRED TO 0.2:** Centralize conservative per-side duration estimation and produce a professional review packet for any
    content dosage/duration adjustment; do not fabricate approval. The four-persona preset path now uses the
    shared estimator; generic validation/generation estimators still require a separately tested model change.
  - _Requirement: R4_

- [x] 5a. Refresh and gate the four-persona prescriptions
  - Add gender-neutral names, availability state, structured beginner introductory phase, and UI
    explanations without changing public activation.
  - Replace the two gym prescriptions and the no-jump home prescription with the reviewed action,
    set, RIR, rest, and duration matrix.
  - Keep the band push/pull/legs preset fail-closed until resistance-band and fixed-anchor capability
    are representable by Equipment Inventory V2; do not substitute bodyweight back-control.
  - Cover catalog load, recommendation, candidate generation, action counts, phase dosage, duration,
    no-jump editing/replacements, and blocked capability behavior.
  - _Requirements: R4, R6, R8_

- [x] 6. Restore package headroom without changing startup ownership
  - Add package/asset/chunk budgets, deterministic contribution output, and safe asset optimization.
  - Rebuild and prove at least 128 KiB effective headroom for the exercise-guide package.
  - _Requirement: R5_

- [ ] 7. **DEFERRED TO 0.2:** Design and implement the equipment-inventory compatibility lane
  - [x] Audit the full profile-to-progression chain and define the phased V2 compatibility model.
  - [ ] After product/professional decisions, separate availability from load profile and explicit
    load interpretation.
  - [ ] Add migration, bindings, API/domain validation, dedicated editor, round-trip/conflict tests,
    and candidate/workout ambiguity coverage.
  - _Requirement: R6_

- [ ] 8. Run full regression, simulator journeys, OCR review, and completion audit
  - [x] Run schema/API generation, targeted backend verification, frontend typecheck/tests/build,
    package gates, boundary scans, scoped OCR review, and a fresh post-fix pass.
  - [ ] Run the four-persona and generic four-day bodyweight live journeys after a loopback-capable
    backend is available; retain the completed DevTools startup smoke as a lower-layer result only.
  - [ ] Execute the MySQL/Testcontainers migration and concurrency lane on a Docker-capable host.
  - _Requirements: R1-R8_

- [ ] 9. Execute the external acceptance gates
  - Obtain qualified professional sign-off for content changes using exact versions/digests.
  - Execute the Android/iOS physical-device matrix on the frozen release candidate.
  - Keep public activation disabled until both gates are evidenced and separately authorized.
  - _Requirements: R4, R7_
