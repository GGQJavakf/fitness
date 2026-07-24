package com.aifitness.assistant.progression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aifitness.assistant.progression.application.EffectiveSetSelector;
import com.aifitness.assistant.progression.application.EffectiveSetSelector.FactStatus;
import com.aifitness.assistant.progression.application.EffectiveSetSelector.RawSetFact;
import com.aifitness.assistant.progression.application.EffectiveSetSelector.SelectionCriteria;
import com.aifitness.assistant.progression.application.EffectiveSetSelector.SessionOutcome;
import com.aifitness.assistant.progression.application.EffectiveSetSelector.SetKind;
import com.aifitness.assistant.progression.domain.ProgressionInput;
import com.aifitness.assistant.progression.domain.ProgressionInput.ExclusionReason;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EffectiveSetSelectorTest {
    private static final UUID USER = new UUID(0, 1);
    private static final UUID OTHER_USER = new UUID(0, 2);
    private static final UUID EXERCISE = new UUID(0, 10);
    private static final UUID OTHER_EXERCISE = new UUID(0, 11);
    private static final UUID SESSION = new UUID(0, 20);
    private static final Instant SELECTED_AT = Instant.parse("2026-07-24T11:00:00Z");
    private static final String DIGEST = "a".repeat(64);

    @Test
    void selectsOnlyCurrentCompletedWorkFactsForTheSameOwnerExerciseVariantAndUnit() {
        RawSetFact eligible = fact(1, USER, EXERCISE, "STANDARD", "KG", SetKind.WORK,
                SessionOutcome.COMPLETED, FactStatus.COMPLETED, new BigDecimal("40"), null, false, true);
        List<RawSetFact> facts = List.of(
                eligible,
                fact(2, USER, EXERCISE, "STANDARD", "KG", SetKind.WARMUP,
                        SessionOutcome.COMPLETED, FactStatus.COMPLETED, new BigDecimal("20"), 3, false, true),
                fact(3, USER, EXERCISE, "STANDARD", "KG", SetKind.EXTRA,
                        SessionOutcome.COMPLETED, FactStatus.COMPLETED, new BigDecimal("40"), 2, false, true),
                fact(4, USER, EXERCISE, "STANDARD", "KG", SetKind.WORK,
                        SessionOutcome.COMPLETED, FactStatus.COMPLETED, null, 2, false, true),
                fact(5, USER, EXERCISE, "STANDARD", "KG", SetKind.WORK,
                        SessionOutcome.COMPLETED, FactStatus.COMPLETED, new BigDecimal("40"), 2, true, true),
                fact(6, USER, EXERCISE, "STANDARD", "LB", SetKind.WORK,
                        SessionOutcome.COMPLETED, FactStatus.COMPLETED, new BigDecimal("90"), 2, false, true),
                fact(7, USER, EXERCISE, "TEMPO", "KG", SetKind.WORK,
                        SessionOutcome.COMPLETED, FactStatus.COMPLETED, new BigDecimal("35"), 2, false, true),
                fact(8, USER, EXERCISE, "STANDARD", "KG", SetKind.WORK,
                        SessionOutcome.ABORTED, FactStatus.COMPLETED, new BigDecimal("40"), 2, false, true),
                fact(9, USER, EXERCISE, "STANDARD", "KG", SetKind.WORK,
                        SessionOutcome.COMPLETED, FactStatus.COMPLETED, new BigDecimal("40"), 2, false, false),
                fact(10, OTHER_USER, EXERCISE, "STANDARD", "KG", SetKind.WORK,
                        SessionOutcome.COMPLETED, FactStatus.COMPLETED, new BigDecimal("40"), 2, false, true),
                fact(11, USER, OTHER_EXERCISE, "STANDARD", "KG", SetKind.WORK,
                        SessionOutcome.COMPLETED, FactStatus.COMPLETED, new BigDecimal("40"), 2, false, true),
                fact(12, USER, EXERCISE, "STANDARD", "KG", SetKind.WORK,
                        SessionOutcome.COMPLETED, FactStatus.FAILED, new BigDecimal("40"), 0, false, true));

        ProgressionInput selected = new EffectiveSetSelector().select(
                new SelectionCriteria(USER, EXERCISE, "STANDARD", "KG"), facts, SELECTED_AT);

        assertThat(selected.effectiveSets()).extracting(ProgressionInput.EffectiveSet::factId)
                .containsExactly(eligible.factId());
        assertThat(selected.excludedSets()).hasSize(11);
        assertThat(selected.excludedSets()).extracting(excluded -> excluded.reasons().getFirst())
                .containsExactly(
                        ExclusionReason.WARMUP_SET,
                        ExclusionReason.EXTRA_SET,
                        ExclusionReason.MISSING_WEIGHT,
                        ExclusionReason.ANOMALOUS_INPUT,
                        ExclusionReason.UNIT_CHANGED,
                        ExclusionReason.VARIANT_CHANGED,
                        ExclusionReason.INCOMPLETE_SESSION,
                        ExclusionReason.SUPERSEDED_REVISION,
                        ExclusionReason.USER_MISMATCH,
                        ExclusionReason.EXERCISE_MISMATCH,
                        ExclusionReason.INCOMPLETE_SET);
        assertThat(selected.schemaVersion()).isEqualTo("progression-input-v1");
        assertThat(selected.selectedAt()).isEqualTo(SELECTED_AT);
    }

    @Test
    void preservesMissingRirAndCopiesCollectionsIntoAnImmutableSnapshot() {
        List<RawSetFact> mutableFacts = new java.util.ArrayList<>();
        mutableFacts.add(fact(1, USER, EXERCISE, "STANDARD", "KG", SetKind.WORK,
                SessionOutcome.COMPLETED, FactStatus.COMPLETED, new BigDecimal("40.0"), null, false, true));

        ProgressionInput selected = new EffectiveSetSelector().select(
                new SelectionCriteria(USER, EXERCISE, "STANDARD", "KG"), mutableFacts, SELECTED_AT);
        mutableFacts.clear();

        assertThat(selected.effectiveSets()).hasSize(1);
        assertThat(selected.effectiveSets().getFirst().remainingReps()).isEmpty();
        assertThat(selected.effectiveSets().getFirst().weightKg()).isEqualByComparingTo("40");
        assertThatThrownBy(() -> selected.effectiveSets().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsNonKgSelectionCriteriaWithoutSilentlyConvertingUnits() {
        assertThatThrownBy(() -> new SelectionCriteria(USER, EXERCISE, "STANDARD", "LB"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("KG");
    }

    private static RawSetFact fact(
            long suffix, UUID userId, UUID exerciseId, String variant, String unit, SetKind kind,
            SessionOutcome sessionOutcome, FactStatus factStatus, BigDecimal weight, Integer rir,
            boolean anomalous, boolean currentRevision) {
        return new RawSetFact(
                new UUID(0, 100 + suffix), SESSION, userId, exerciseId, variant, unit, kind,
                1, sessionOutcome, factStatus, weight, 10, Optional.ofNullable(rir), anomalous,
                currentRevision, Instant.parse("2026-07-24T10:00:00Z").plusSeconds(suffix), suffix, DIGEST);
    }
}
