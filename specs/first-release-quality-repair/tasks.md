# Implementation plan

- [x] 1. Add failing regression coverage
  - Duration-aware generation and variation
  - Plan-first routing and editor reachability
  - CloudBase timeout, local-first recording, foreground rest expiry
  - Day rotation, draft revision cleanup, compact layout, motion guide
  - _Requirements: R1-R7_

- [x] 2. Repair deterministic plan generation
  - Historical implementation added duration/experience targets; the fixed-count portion was
    later removed by `ai-primary-plan-generation`
  - Retain goal inputs, movement-compatible replacement, and duration-bounded fallback expansion
  - Preserve locks and validation invariants
  - _Requirement: R1_

- [x] 3. Repair navigation, editing, and next-day selection
  - Make Plan the authenticated landing destination
  - Remove the obsolete authenticated Home fallback
  - Register and expose Plan Editor
  - Upgrade edited legacy plans through a confirmed immutable version
  - Select the next completed-plan day deterministically
  - _Requirements: R2, R5_

- [x] 4. Repair workout reliability and durability
  - Bound CloudBase requests
  - Decouple local state transitions from remote flush
  - Complete expired rests and surface field-local errors
  - Remove superseded draft revisions
  - _Requirements: R3, R4, R7_

- [x] 5. Refine workout UI and exercise guidance
  - Deliver a one-set-per-screen compact execution surface
  - Keep primary action above the safe area
  - Replace the generic stick figure with per-exercise original cat static breakdowns and safe fallback
  - _Requirements: R4, R6_

- [x] 6. Complete regression, build, and review gates
  - Run backend and mini-program full checks
  - Run CloudBase semantic review and independent reviews
  - Fix material findings and rerun affected checks
  - _Requirements: R1-R7_
