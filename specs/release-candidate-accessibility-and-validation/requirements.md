# Release candidate accessibility and validation requirements

## Scope

Freeze a reproducible local release candidate after repairing the currently confirmed toolchain,
usability, and plan-editor issues. Public content approval, physical-device weak-network evidence,
and a packaged empty-schema MySQL run remain fail-closed evidence gates. This work does not
authorize production access, CloudRun deployment, WeChat upload, public release, credential or
permission changes, or destructive database cleanup.

## Acceptance requirements

### R1 — Reproducible and auditable candidate

- When dependencies are installed from the committed lockfile, the packaged and complete
  mini-program dependency audits shall report no known vulnerability at the configured threshold.
- When the candidate is frozen, generated diagnostics and local inspection artifacts shall not
  pollute the tracked business diff, while reproducible source assets remain tracked.
- When verification is rerun from the candidate commit, one documented command sequence shall
  reproduce API drift checks, type checking, tests, build, package-size checks, backend verification,
  release preflight, and repository hygiene evidence.

### R2 — Truthful public content approval

- When exercise guidance or alternative relationships have not been reviewed by a qualified
  fitness professional, public preflight shall fail closed and shall identify the unapproved items.
- When a reviewer prepares approval, the repository shall generate a deterministic review pack
  containing the current content identity, scope, and per-item decision fields; changing reviewed
  content shall invalidate stale evidence.
- AI validation, automated tests, static illustrations, or developer review shall never be
  represented as professional public-release approval.

### R3 — Readable and operable interaction baseline

- While a user operates the mini program one-handed or with reduced attention, normal body copy
  shall be at least 24 px and supporting copy shall be at least 22 px.
- When a visible control is interactive, its main touch target shall be at least 88 px high unless
  the platform component provides an equivalent larger hit area that is covered by a test.
- When a control has an ambiguous visual label or selected state, it shall expose an accessible
  name or state to the WeChat accessibility tree.
- The existing forest-green and warm-neutral visual language shall remain consistent; destructive
  actions shall remain visually distinct and require confirmation.

### R4 — Progressive plan editing

- When the editor opens, it shall show every training-day summary, expand only one day, and expose
  only one exercise's detailed numeric prescription at a time.
- When a user selects another day or exercise, the corresponding details shall replace the prior
  details without mutating the plan.
- Structural edits, locks, validation, rebalance preview, warnings, and versioned save semantics
  shall remain available, but validation and rebalance shall live behind a secondary advanced area.
- The sticky action region shall contain one primary next action: confirm a server warning when
  required, otherwise save a new version.

### R5 — Weak-network recovery evidence

- When a physical-device request times out, disconnects, or returns after retry, login, plan load,
  workout draft, set recording, and save flows shall preserve the newest durable state and offer a
  truthful retry or recovery action.
- Physical-device evidence shall record device/base-library/app version, scenario, expected result,
  observed result, and timestamp; simulator-only evidence shall not be called a real-device pass.

### R6 — Packaged MySQL release evidence

- When the packaged backend is validated against MySQL, the harness shall use a uniquely named,
  empty, disposable schema on a local or explicitly approved non-production endpoint.
- The harness shall verify TLS/secret handling as applicable, Flyway migration, datasource identity,
  health, authentication, and representative business routes with zero environment-related skips.
- The harness shall fail rather than clear, reuse, or mutate a non-empty or unapproved schema.

### R7 — Final freeze and readback

- When all automatable gates pass, the candidate shall be independently reviewed in proportion to
  risk, committed as a small auditable series, and read back by branch, HEAD, upstream, worktree
  cleanliness, test evidence, and remaining external gates.
- Public release readiness shall remain blocked until both professional content approval and actual
  physical-device/MySQL evidence are present; no status field may be changed merely to make a gate
  green.
