# Release candidate accessibility and validation design

## Delivery sequence

Work proceeds test-first in four lanes: candidate hygiene and dependency remediation, accessibility
baseline, progressive plan-editor presentation, and environment evidence. Existing deterministic
training policy, OpenAPI contracts, immutable plan versions, and workout durability semantics remain
unchanged.

## Candidate hygiene and dependency policy

Pin the vulnerable transitive build dependency through `package.json` overrides and regenerate the
lockfile with the existing package manager. A regression test asserts both the declared override and
the resolved lockfile version. Dependency audits use the official npm registry and run before the
rest of frontend verification. Local screenshots, network traces, and temporary inspection output
belong under ignored `artifacts/`; original source images and committed runtime assets remain
versioned inputs.

## Professional content review boundary

The public preflight remains the authoritative fail-closed gate. A deterministic review-pack
generator inventories every active exercise and alternative relationship, includes content digests,
and leaves explicit reviewer identity, qualification, decision, and date fields unfilled. A separate
human review can convert those decisions into schema-valid approval metadata. Automated code in this
change prepares and validates evidence but never self-approves biomechanics or public suitability.

Static cat artwork is treated as branded step illustration, not anatomical evidence. Plain-language
instructions, breathing, common mistakes, and safety cues remain authoritative until professionally
reviewed visual guidance exists.

## Accessibility baseline

Global styles define the shared body, helper, focus/selected, and button baselines. Page-specific
styles may be larger but cannot fall below the 22 px supporting-copy floor. Key navigation, workout,
exercise-guide, and editor controls receive at least an 88 px touch target. Tests scan committed SCSS
and exercise key component markup so regressions fail before visual review.

## Progressive plan editor

The editor adds presentation-only state for the expanded day, expanded exercise, and advanced-tools
panel. The first available day and exercise are selected on initial render. Day and exercise summary
buttons change presentation state only; all mutations continue through the existing application
facade. Numeric inputs, locks, replacement, ordering, deletion confirmation, validation, rebalance,
warning confirmation, save coalescing, telemetry, and navigation retain their current contracts.

The page hierarchy is:

1. plan/version context;
2. training-day summaries with one expanded day;
3. exercise summaries with one expanded prescription;
4. optional advanced validation and rebalance tools;
5. one sticky primary confirmation/save action.

## Environment evidence

Weak-network checks use the existing WeChat IDE/device tooling without upload or public release.
Results are recorded in a matrix and distinguish simulator observations from physical-device facts.
The MySQL lane reuses the packaged release harness, generates one unique empty schema, injects secrets
without logging them, verifies zero skips, and refuses cleanup of any pre-existing data.

## Rollback

The UI lane is isolated to presentation state, SCSS, and tests and can be reverted without changing
stored plans. The dependency pin is a lockfile/override change. Review-pack generation is additive.
No remote or production state is changed by this specification.
