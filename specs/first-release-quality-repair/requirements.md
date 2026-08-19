# First-release quality repair requirements

## Scope

Repair the first-release plan, authenticated navigation, workout execution, plan editing,
exercise guidance, local durability, and regression gates. The work is local-only and must
not deploy, upload, or create production resources.

## User stories and acceptance criteria

### R1 — Duration-aware, explainable plans

> Superseded for generation ownership by `../ai-primary-plan-generation/requirements.md`.
> The requirements below retain only the fallback and validation behavior from this repair.

- When a user generates a 45-minute plan, the complete session budget shall contain four or five
  safe exercises when at least four eligible exercises exist; other duration bands remain
  versioned policy rather than a linear count mapping.
- When AI is unavailable, the deterministic fallback shall use the saved profile, eligible
  exercises, goal prescriptions, and remaining duration without claiming AI personalization.
- When a configured template exercise is unavailable or excluded, the generator shall prefer
  a deterministic eligible movement-compatible replacement before rejecting the whole plan.
- While building a plan, the validator shall reject sessions that exceed the configured time and
  shall reject underfilled 45-minute sessions with a typed issue.

### R2 — Plan-first authenticated navigation

- When an authenticated user has an active plan and no active workout, the application shall
  land on the plan page without rendering or navigating through the onboarding home page.
- While the product has no real authenticated dashboard, the main navigation shall expose
  Plan, Progress, and My only.

### R3 — One-tap local workout recording

- When a valid set is submitted, the application shall persist it locally and advance to the
  next workout state without waiting for remote synchronization.
- While remote synchronization is pending or unavailable, the application shall show an
  explicit local-save/sync status and shall not discard repeated taps silently.
- When input is invalid, the application shall show the error next to the affected input or
  primary action.
- When a CloudBase container request exceeds the configured timeout, the request shall fail
  predictably so the offline workflow can continue.

### R4 — One-set-per-screen execution

- While entering a work set on a narrow phone viewport, weight, reps, optional effort, and the
  primary completion action shall be usable without scrolling through guidance and secondary
  actions.
- The primary completion action shall remain reachable above the bottom safe area.
- Secondary actions shall remain available without competing visually with the primary action.
- When a rest timer reaches zero in the foreground, the application shall leave the running
  rest state automatically.

### R5 — Editable and rotating plans

- When a user views a candidate or active plan, the application shall provide a visible edit
  entry.
- When edits are saved, the application shall preserve immutable versions and user locks.
- When an active plan was created by an older rule snapshot, editing shall validate it against
  the current rules, require explicit upgrade confirmation, and preserve the older version.
- When preparing the next workout, the application shall select the day after the latest
  completed day in the active plan while still allowing manual day selection.

### R6 — Exercise motion guidance

- During a live workout, the application shall embed the corresponding project-original
  cat-coach static motion breakdown rather than opening a separate detail page.
- Every active catalog exercise shall provide two to four compact local JPEG stages according
  to movement complexity. Stage labels shall switch with one tap inside a fixed-height region.
- When a breakdown is unavailable or an image fails to load, the application shall fall back
  to an approved local static cover and textual guidance without a blank state or dead control.
- The guide shall preserve textual steps and safety cues as the authoritative guidance; it
  shall not copy a third-party character or import unverified third-party media.

### R7 — Bounded local storage and meaningful tests

- After a workout draft revision is atomically replaced, the store shall best-effort remove
  the prior revision without risking the active pointer.
- Tests shall exercise routing, timeout, local-first recording, rest expiry, day rotation,
  editor reachability, compact layout markers, motion fallback, and duration-aware generation
  rather than relying only on source-string presence.

## Non-goals

- No public release, CloudBase deployment, schema migration, new credentials, or permission
  changes.
- No medical, rehabilitation, or professional-coaching claims.
- No unlicensed external image or video assets, and no runtime GIF, animated WebP, MP4, 3D
  character, or AI-generated continuous-frame motion media.
