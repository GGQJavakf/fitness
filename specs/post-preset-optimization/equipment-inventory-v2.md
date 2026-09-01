# Equipment inventory V2 decision design

Status: `REVIEW_REQUIRED`. This design is implementation-ready only after the decisions below are
recorded. The existing V1 endpoint remains authoritative until then.

## Confirmed problem

V1 uses one item to mean availability, concrete equipment identity, and load capability. It therefore
forces non-load-bearing equipment such as a bench to carry fake KG data, cannot distinguish multiple
items of the same type, and loses load interpretation in workout and progression facts. The legacy
PUT also replaces the full collection, so adding optional V2 fields to that payload would allow an old
client to erase data it does not understand.

## Recommended staged contract

### Phase 1 - Inventory without consumer cutover

Add a separate versioned endpoint and canonical model:

```text
EquipmentInventoryItem {
  clientEquipmentKey
  equipmentType: DUMBBELL | BARBELL | CABLE | MACHINE | BENCH | BANDS
  displayName?
  loadProfile?: {
    interpretation: PER_HAND | TOTAL | STACK_DISPLAY
    unit: KG
    minIncrementKg
    availableLevelsKg[]
    verification: USER_CONFIRMED | LEGACY_INFERRED
  }
}
```

Item presence means available. `loadProfile` absence means available but not calibrated. BENCH and
BANDS must not carry KG in Phase 1. Unknown types and interpretations fail closed. After the first V2
write, legacy PUT must return `409 EQUIPMENT_SCHEMA_UPGRADE_REQUIRED` instead of replacing V2 data.

### Phase 2 - Content and plan binding

Replace ambiguous type arrays with versioned requirements containing `LOAD_SOURCE` and `SUPPORT`
roles. Candidate generation and commit include the inventory version. A plan snapshot binds one
`clientEquipmentKey`, type, interpretation, and inventory version; multiple matching items require an
explicit user choice and deleted bindings never silently switch equipment.

### Phase 3 - Workout and progression facts

Persist the resolved equipment binding in the workout snapshot and record actual load with the same
key and interpretation. Progression history is partitioned by exercise, concrete equipment, and
interpretation. `PER_HAND`, `TOTAL`, and `STACK_DISPLAY` never merge; legacy facts remain
`LEGACY_UNSCOPED` and cannot drive automatic progression.

### Phase 4 - Dedicated editor and V1 retirement

Onboarding collects availability only. A dedicated inventory editor handles multiple concrete items,
calibration, GET-edit-PUT-GET verification, and version conflicts. V1 retirement requires a measured
minimum-client-version gate and old-client journey regression.

## Decisions required before implementation

Product must define whether availability means owned, normally accessible, or available for this
session; MACHINE capability granularity; old-client 409 policy; plan-time versus workout-time binding;
and whether an increment without explicit levels is merely incomplete calibration.

A qualified reviewer must define dumbbell `PER_HAND`, barbell `TOTAL` including bar weight, the limits
of `STACK_DISPLAY`, per-exercise load-source/support roles, BANDS semantics, and how much new scoped
history is required before progression resumes.

## Minimum acceptance matrix

- BENCH/BANDS round-trip without fake KG.
- Multiple dumbbells retain distinct keys, names, levels, and interpretations.
- V1 migration preserves keys/order/levels and marks inferred semantics.
- V2 activation makes legacy PUT fail without losing data.
- Availability can unlock a compatible exercise while missing load capability forces calibration.
- Ambiguous load source or multiple matching items fails closed.
- Inventory changes between candidate generation and commit cause a version conflict.
- Workout/offline replay preserves immutable bindings.
- Progression uses exact levels for only the same concrete item and interpretation.
