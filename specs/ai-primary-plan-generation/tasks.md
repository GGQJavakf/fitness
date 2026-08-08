# Implementation plan

- [x] 1. Replace the fixed exercise-count requirement
  - Remove duration-to-count policy/config/schema and underfilled-count warnings.
  - Keep duration estimation and dynamic fallback filling.
  - _Requirements: R2_

- [x] 2. Extend the contract and backend proposal path
  - Add generation context, closed AI proposal, fallback control, and provenance.
  - Evaluate AI proposals with derived weight state, locks, and existing rule validation.
  - Preserve the old request as an explicit fallback-compatible path.
  - _Requirements: R1-R4, R6_

- [x] 3. Implement client AI orchestration
  - Add `PLAN_GENERATION`, a strict prompt/parser, context whitelist checks, and one repair.
  - Submit AI proposals with fallback disabled, then request fallback only when needed.
  - Keep provider/model configuration and platform boundaries intact.
  - _Requirements: R1-R4_

- [x] 4. Add additional requirements and provenance UI
  - Add the bounded non-medical onboarding input.
  - Show AI-personalized versus fallback status on candidate and active-plan pages.
  - _Requirements: R4-R5_

- [x] 5. Update product and detailed-design boundaries
  - Document AI-primary generation, backend validation, and explicit fallback.
  - Remove statements that make duration determine exercise count.
  - _Requirements: R1-R6_

- [x] 6. Verify and review
  - Run targeted failing tests first, then backend and mini-program full gates.
  - Complete CloudBase semantic checks and one independent read-only review.
  - _Requirements: R1-R6_
