# First-release quality repair design

## Boundaries

- This repair's original deterministic-generation boundary is superseded by
  `../ai-primary-plan-generation/design.md`: AI now proposes plan structure and bounded
  prescriptions; backend rules remain authoritative for eligibility, time, safety, locks,
  validation, weight state, and persistence.
- The mini program remains local-first: a set fact is persisted and queued before remote sync.
- Existing immutable plan versions, workout snapshots, and idempotency keys remain unchanged.
- Presentation changes use the existing forest-green refined design system and safe-area
  primitives.

## Plan generation

The deterministic generator is retained only as an availability and old-client fallback. It:

1. filter templates by frequency;
2. score eligible base templates using equipment fit and saved profile constraints;
3. replace unavailable slots with eligible exercises of the same movement pattern;
4. add eligible, non-duplicate accessory movements only while they fit the remaining duration;
5. validate time, movement duplication, primary-muscle volume, locks, and equipment.

Any generated addition reuses a conservative, versioned accessory prescription from the rule
policy. The post-audit policy now requires four or five safe exercises for a 45-minute day and
uses a complete warm-up/work/rest/transition estimate. AI proposal repair and explicit fallback
provenance remain defined in the superseding designs.

## Workout execution

`recordSet` remains the atomic local write. The page immediately renders its returned state,
releases the submit gate, and starts remote `flush` in the background. Synchronization reads
the latest draft before applying acknowledgements, preserving the existing concurrency fix.
CloudBase container calls use the same bounded timeout as ordinary requests.

Foreground rest expiry is reconciled through the workout service so the persisted timer moves
from `RUNNING` to `FINISHED`; a manual skip remains `SKIPPED`.

## Navigation and plan editing

Authenticated navigation has three destinations: Plan, Progress, My. Home remains the
unauthenticated login/onboarding surface only. `PLAN_EDITOR` is registered as a page
destination and is reachable from both candidate preview and active plan.

The next-day selector uses the latest completed history item whose day code exists in the
active plan. It selects the following plan day cyclically; aborted sessions do not advance.

## Exercise motion

The live workout page renders a reusable Taro component keyed by catalog image reference with an
exercise-code compatibility fallback. The local runtime pack contains two to four static JPEG
stages for every active exercise, all using the same project-original golden-shaded cat. Stage
labels switch the image with one tap inside a fixed-height region without navigating away from the
workout. Missing mappings and image-load failures fall back to an approved local static cover and
textual guidance without blank states or dead controls. Textual instructions, breathing cues,
common errors, and safety cues remain authoritative until a fitness professional approves the
visuals for public release. Runtime GIF, animated WebP, MP4, 3D character, and AI continuous-frame
media are not used.

## Verification

- Backend: focused generation tests, configuration/schema tests, then `mvnw verify`.
- Mini program: focused interaction/domain tests, API type check, TypeScript, full Vitest,
  WeChat build, architecture scan, visual-rule audit, and `git diff --check`.
- Review: CloudBase semantic review plus independent high-risk/concurrency and UI review.
