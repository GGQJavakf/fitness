# Post-preset optimization design

## Delivery strategy

Work is split into independently testable lanes and merged through existing application/domain
boundaries. Shared hotspots are edited serially. Regression tests precede behavior changes, and the
existing dirty worktree is preserved.

## Preset matching and preview UX

`PlanPresetMatchPolicy` is the single source for list ordering and activation compatibility. It
returns typed mismatch fields and an explicit match status, then applies a stable comparison tuple
instead of an opaque score. `PlanCandidateService` reads the authenticated profile for listing and
uses the same policy again before generation.

The preset page keeps the existing horizontal selector and detail card. It adds compact semantic
badges for recommendation/match state and a profile-adjustment explanation. Existing brand tokens,
typography, 88 px touch targets, async selection lock, and accessible current-selection label remain
unchanged. Partial matches stay browseable but cannot trigger a known-failing request.

## Bodyweight template semantics

An equipment-free onboarding request leaves training split selection to server eligibility. The
four-day bodyweight template declares a bodyweight-specific upper focus rather than deriving safety
requirements from its display name. This focus accepts push plus posterior-control and trunk work,
matching the already-reviewed low-impact preset, while the loaded `UPPER` focus remains strict.

This is not a global relaxation and does not claim five/six-day bodyweight capacity. End-to-end tests
must use the actual onboarding request shape so template-existence tests cannot mask routing gaps.

## Atomic candidate commit

Add a candidate-commit command beside the existing direct-activation endpoint. The request contains
candidate id, expected active version, final edited plan, lock commands, optional warning token, and
an idempotency key header. The backend resolves the candidate and performs validation, concurrency,
warning consumption, final-version insertion/activation, and idempotency receipt in one transaction
port implemented by both JDBC and in-memory adapters.

Only digests of idempotency keys and semantic payloads are persisted. No client fallback to the old
two-request edit path is allowed after an ambiguous timeout. Direct unedited activation remains for
backward compatibility.

## Provenance, impact, and duration

The catalog owns a structured source registry; each preset references an explicit subset and carries
its own content/review status. Loader, domain model, API, and preview preserve these values. Public
eligibility remains fail-closed and is not enabled in this change.

Exercise impact is catalog data, and a plan-level maximum-impact constraint is immutable versioned
data. Validation and replacement resolve the same constraint. Missing classifications fail closed
for a low-impact plan.

Duration moves to one versioned estimator. `perSide` interpretation is explicit and shared by
ordinary and supersetted work. The estimator may expose current overruns, but plan sets, exercise
selection, and advertised duration are changed only with recorded professional review.

## Four-persona prescription refresh

The preset catalog gains explicit availability and introductory-phase metadata. `AVAILABLE` presets
participate in deterministic recommendation and candidate generation. `BLOCKED_CAPABILITY` entries
remain visible for auditability but cannot become the recommended item or a candidate; they expose a
non-empty blocking reason. This lets the requested resistance-band plan remain truthfully recorded
without inventing KG levels, treating a cable machine as a band, or pretending that prone back-control
is equivalent to a row or pulldown.

The three beginner presets use a two-week, two-set, RIR 3–4 familiarization phase followed by their
target prescriptions when technique and recovery are ready. Phase metadata is shown in the catalog;
the same rule is retained in the immutable plan execution/progression rules so it survives activation.
This change does not add calendar-driven automatic progression, which needs a separately reviewed
workout-state model.

The gym prescriptions use the approved neutral A/B/C and upper/lower exercise sets. The home four-day
plan is renamed `新手四日无跳跃居家基础体能`; its copy makes no chest/back completeness or hypertrophy
claim. A plan-level `NO_JUMP` constraint is preserved across candidate edits. Exercise impact is
catalog data; unclassified additions/replacements fail closed for this constrained plan.

`PlanDurationEstimator` owns preset timing semantics. Per-side work time is doubled, while rest is
counted once after both sides; transitions and warm-up remain policy-owned. Catalog validation checks
every available preset against its advertised maximum and profile limit. Durations are documented in
the professional packet with no shortened rest used to make a plan pass.

## Equipment evolution

The equipment redesign is a separate compatibility lane: availability/capability records represent
what exists, while optional load profiles represent how resistance is measured. Plan/workout
snapshots bind a concrete item where ambiguity matters. A migration preserves legacy records by
mapping loaded types to explicit legacy load modes and treating bench availability as non-loaded.

UI work follows the contract, not the reverse: a dedicated editor supports precise GET-edit-PUT-GET
round trips and conflicts. Until that lane is complete, current coarse onboarding remains visibly
coarse and is not called a detailed inventory.

## Package and device gates

Package checks report the effective closure for every subpackage, enforce a headroom budget for the
exercise-guide package, and list large chunks/assets. Asset generation performs deterministic
optimization from source material; startup wrappers and physical async boundaries are unchanged.

DevTools proves only compiled simulator behavior. Physical Android/iOS acceptance runs after code
freeze and records exact device/build fingerprints. Missing device evidence blocks a device-ready
claim but does not erase local contract, test, or simulator evidence.

## Rollback

Each lane is reversible by its owned files. New API/database structures remain additive; client
routing can return to the compatible direct-activation path without deleting audit/idempotency data.
Public activation stays disabled throughout, so rollback never requires production content changes.
