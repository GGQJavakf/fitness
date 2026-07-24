package com.aifitness.assistant.workout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.workout.application.WorkoutSetService;
import com.aifitness.assistant.workout.domain.WorkoutSet;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class WorkoutInputAnomalyTest {

    @Test
    void rejectsNegativeWeightMissingRepsAndNonKgUnits() {
        assertThatIllegalArgumentException().isThrownBy(() -> performance("-1", "KG", 8));
        assertThatIllegalArgumentException().isThrownBy(() -> performance("20", "KG", null));
        assertThatIllegalArgumentException().isThrownBy(() -> performance("20", "LB", 8));
    }

    @Test
    void largeWeightChangeRequiresConfirmationAndRemainsExcludedFromProgression() {
        var fixture = WorkoutSetTestFixture.fixture();
        WorkoutSetService.Command unconfirmed = new WorkoutSetService.Command(
                WorkoutSetTestFixture.EXERCISE_ID, 1, WorkoutSet.SetType.WORK, 1,
                performance("20", "KG", 10), performance("100", "KG", 8), 2,
                WorkoutSet.CompletionStatus.COMPLETED,
                Optional.of(Instant.parse("2026-07-24T08:00:00Z")), false);

        assertThatThrownBy(() -> fixture.service().upsert(
                new AuthenticatedUserId(WorkoutSetTestFixture.USER_ID),
                WorkoutSetTestFixture.SESSION_ID, "set-key-0002", 1, unconfirmed))
                .isInstanceOfSatisfying(
                        WorkoutSetService.AnomalyConfirmationRequiredException.class,
                        error -> assertThat(error.reasons()).contains("LARGE_WEIGHT_CHANGE"));

        WorkoutSetService.Command confirmed = unconfirmed.withAnomalyConfirmation();
        var saved = fixture.service().upsert(
                new AuthenticatedUserId(WorkoutSetTestFixture.USER_ID),
                WorkoutSetTestFixture.SESSION_ID, "set-key-0002", 1, confirmed);
        assertThat(saved.set().anomalyStatus())
                .contains(WorkoutSet.AnomalyStatus.CONFIRMED_EXCLUDED);
    }

    @Test
    void largeWeightDecreaseAlsoRequiresConfirmation() {
        var fixture = WorkoutSetTestFixture.fixture();
        WorkoutSetService.Command unconfirmed = new WorkoutSetService.Command(
                WorkoutSetTestFixture.EXERCISE_ID, 1, WorkoutSet.SetType.WORK, 1,
                performance("40", "KG", 10), performance("5", "KG", 8), 2,
                WorkoutSet.CompletionStatus.COMPLETED,
                Optional.of(Instant.parse("2026-07-24T08:00:00Z")), false);

        assertThatThrownBy(() -> fixture.service().upsert(
                new AuthenticatedUserId(WorkoutSetTestFixture.USER_ID),
                WorkoutSetTestFixture.SESSION_ID, "set-key-0003", 1, unconfirmed))
                .isInstanceOf(WorkoutSetService.AnomalyConfirmationRequiredException.class);
    }

    private static WorkoutSet.Performance performance(String weight, String unit, Integer reps) {
        return new WorkoutSet.Performance(new BigDecimal(weight), unit, reps);
    }
}
