# Implementation plan

- [x] 1. Capture the live candidate scope and establish failing regressions
  - Inventory tracked/untracked changes and preserve existing user work.
  - Add failing tests for the vulnerable transitive tool, typography/touch baseline, and progressive
    plan-editor interaction.
  - _Requirements: R1, R3, R4_

- [x] 2. Repair dependency and candidate-hygiene gates
  - Pin the safe transitive build dependency and regenerate the lockfile.
  - Ignore diagnostic artifacts while retaining reproducible source assets.
  - Run packaged/toolchain audits and the frontend verification chain.
  - _Requirement: R1_

- [x] 3. Prepare truthful professional-review evidence
  - Generate a deterministic exercise/alternative review pack with content digests.
  - Clarify branded illustration versus authoritative written instruction.
  - Confirm public preflight stays blocked until real professional approval is supplied.
  - _Requirement: R2_

- [x] 4. Apply and verify the shared usability baseline
  - Raise supporting copy and key touch targets to the defined minimums.
  - Add accessible labels/state to ambiguous primary controls.
  - Run automated checks and visual simulator review on representative compact screens.
  - _Requirement: R3_

- [x] 5. Simplify the plan editor with progressive disclosure
  - Render one expanded day and one expanded exercise while keeping all summaries visible.
  - Move validation and rebalance into an advanced secondary panel.
  - Keep one sticky primary action and retain all edit/save/recovery semantics.
  - Run focused interaction tests, type checking, build, and visual review.
  - _Requirement: R4_

- [ ] 6. Execute environment validation
  - [ ] Run the physical-device weak-network recovery matrix without upload or production access.
  - [x] Run packaged backend verification against one unique empty disposable MySQL schema with zero
    environment skips.
  - [x] Record exact completed evidence and distinguish the remaining physical-device gap.
  - _Requirements: R5, R6_

- [ ] 7. Freeze and read back the candidate
  - Run full frontend/backend gates, diff hygiene, and proportional independent review.
  - Resolve material findings, commit the auditable candidate, and read back branch, HEAD, upstream,
    worktree status, public approval state, and remaining external gates.
  - _Requirement: R7_
