package com.aifitness.assistant.workout;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

import com.aifitness.assistant.FitnessAssistantApplication;
import com.aifitness.assistant.workout.api.WorkoutSessionController;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes = FitnessAssistantApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WorkoutSessionEndpointIntegrationTest {

    @Test
    void serializesAbsentOptionalSetRuleDescriptionAsOmitted() {
        JsonNode serialized = objectMapper.valueToTree(new WorkoutSessionController.OptionalSetRuleData(
                "TUESDAY_UNDER_42_GOOD_STATE", "TUESDAY_BONUS", 1, Optional.empty()));

        assertThat(serialized.has("description")).isFalse();
    }

    @Test
    void serializesRecoverableActiveSetsWithoutNullOptionalFields() throws Exception {
        String token = loginAndConfigure();
        JsonNode candidate = candidate(token);
        JsonNode plan = createPlan(token, candidate.at("/candidateId").asText());
        String planId = plan.at("/data/planId").asText();
        String dayCode = plan.at("/data/activeVersion/plan/days/0/code").asText();
        String clientKey = "recoverable-session-" + UUID.randomUUID();
        String body = objectMapper.writeValueAsString(java.util.Map.of(
                "clientSessionKey", clientKey,
                "planId", planId,
                "planVersionNo", 1,
                "planDayId", dayCode,
                "trainingDayCode", dayCode));
        JsonNode created = json(mvc.perform(post("/api/v1/workout-sessions")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", clientKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        String sessionId = created.at("/data/id").asText();
        String sessionExerciseId = created.at("/data/exercises/0/id").asText();
        mvc.perform(put("/api/v1/workout-sessions/{id}/status", sessionId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"IN_PROGRESS\",\"expectedVersion\":0}"))
                .andExpect(status().isOk());

        saveRecoverySet(token, sessionId, sessionExerciseId, 1, 1, "COMPLETED", true);
        saveRecoverySet(token, sessionId, sessionExerciseId, 2, 2, "FAILED", false);
        saveRecoverySet(token, sessionId, sessionExerciseId, 3, 3, "SKIPPED", false);

        String competingKey = "recoverable-session-" + UUID.randomUUID();
        String competingBody = objectMapper.writeValueAsString(java.util.Map.of(
                "clientSessionKey", competingKey,
                "planId", planId,
                "planVersionNo", 1,
                "planDayId", dayCode,
                "trainingDayCode", dayCode));
        mvc.perform(post("/api/v1/workout-sessions")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", competingKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(competingBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ACTIVE_WORKOUT_EXISTS"))
                .andExpect(jsonPath("$.error.details.sets[0].completionStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.error.details.sets[0].completedAt").isNotEmpty())
                .andExpect(jsonPath("$.error.details.sets[0].safetyFlag").doesNotExist())
                .andExpect(jsonPath("$.error.details.sets[0].anomalyStatus").doesNotExist())
                .andExpect(jsonPath("$.error.details.sets[1].completionStatus").value("FAILED"))
                .andExpect(jsonPath("$.error.details.sets[1].remainingReps").doesNotExist())
                .andExpect(jsonPath("$.error.details.sets[1].completedAt").doesNotExist())
                .andExpect(jsonPath("$.error.details.sets[1].safetyFlag").doesNotExist())
                .andExpect(jsonPath("$.error.details.sets[1].anomalyStatus").doesNotExist())
                .andExpect(jsonPath("$.error.details.sets[2].completionStatus").value("SKIPPED"))
                .andExpect(jsonPath("$.error.details.sets[2].remainingReps").doesNotExist())
                .andExpect(jsonPath("$.error.details.sets[2].completedAt").doesNotExist())
                .andExpect(jsonPath("$.error.details.sets[2].safetyFlag").doesNotExist())
                .andExpect(jsonPath("$.error.details.sets[2].anomalyStatus").doesNotExist());
    }

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void preparesTheLowImpactBeginnerWorkoutFromTheIntroductoryPrescriptionSnapshot()
            throws Exception {
        String token = login();
        mvc.perform(put("/api/v1/profile")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"experience":"BEGINNER","goal":"FAT_LOSS",
                                 "weeklyFrequency":4,"sessionMinutes":30,"location":"HOME",
                                 "expectedVersion":0}
                                """))
                .andExpect(status().isOk());
        JsonNode presetCandidate = json(mvc.perform(post("/api/v1/plans/candidates")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"profileVersion":1,"lockedFields":{},
                                 "presetCode":"BEGINNER_4_DAY_FAT_LOSS_HOME_LOW_IMPACT_V1"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANDIDATE_READY"))
                .andReturn().getResponse().getContentAsString()).at("/data/candidate");
        JsonNode plan = createPlan(token, presetCandidate.path("candidateId").asText());
        String planId = plan.at("/data/planId").asText();
        String dayCode = plan.at("/data/activeVersion/plan/days/0/code").asText();
        assertThat(plan.at("/data/activeVersion/plan/movementImpactConstraint").asText())
                .isEqualTo("NO_JUMP");

        String clientKey = "introductory-low-impact-" + UUID.randomUUID();
        mvc.perform(post("/api/v1/workout-sessions")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", clientKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "clientSessionKey", clientKey,
                                "planId", planId,
                                "planVersionNo", 1,
                                "planDayId", dayCode,
                                "trainingDayCode", dayCode))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.exercises[*].prescription.workSets")
                        .value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is(2))))
                .andExpect(jsonPath("$.data.exercises[*].prescription.targetRirMin")
                        .value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is(3))))
                .andExpect(jsonPath("$.data.exercises[*].prescription.targetRirMax")
                        .value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is(4))));
    }

    @Test
    void startsOwnedImmutableSnapshotIdempotentlyAndEnforcesStatusVersion() throws Exception {
        String token = loginAndConfigure();
        JsonNode candidate = candidate(token);
        JsonNode plan = createPlan(token, candidate.at("/candidateId").asText());
        String planId = plan.at("/data/planId").asText();
        String dayCode = plan.at("/data/activeVersion/plan/days/0/code").asText();
        String clientKey = "workout-session-" + UUID.randomUUID();
        String body = objectMapper.writeValueAsString(java.util.Map.of(
                "clientSessionKey", clientKey,
                "planId", planId,
                "planVersionNo", 1,
                "planDayId", dayCode,
                "trainingDayCode", dayCode));

        JsonNode created = json(mvc.perform(post("/api/v1/workout-sessions")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", clientKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("CREATED"))
                .andExpect(jsonPath("$.data.planDayId").value(dayCode))
                .andExpect(jsonPath("$.data.trainingDayCode").value(dayCode))
                .andExpect(jsonPath("$.data.version").value(0))
                .andExpect(jsonPath("$.data.exercises[0].exerciseName").isNotEmpty())
                .andExpect(jsonPath("$.data.exercises[0].prescription.unit").value("KG"))
                .andExpect(jsonPath("$.data.warmupPrescription.schemaVersion")
                        .value("workout-warmup-prescription-v1"))
                .andExpect(jsonPath("$.data.warmupPrescription.ruleVersion").value("1.6.0"))
                .andExpect(jsonPath("$.data.warmupPrescription.generalWarmup.occurrences").value(1))
                .andExpect(jsonPath("$.data.warmupPrescription.countsTowardTrainingVolume").value(false))
                .andExpect(jsonPath("$.data.warmupPrescription.countsTowardProgression").value(false))
                .andReturn().getResponse().getContentAsString());
        String sessionId = created.at("/data/id").asText();
        String sessionExerciseId = created.at("/data/exercises/0/id").asText();

        mvc.perform(post("/api/v1/workout-sessions")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", clientKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(sessionId));

        mvc.perform(put("/api/v1/workout-sessions/{id}/status", sessionId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"IN_PROGRESS\",\"expectedVersion\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data.version").value(1));

        for (String forbiddenStatus : java.util.List.of("COMPLETING", "COMPLETED")) {
            mvc.perform(put("/api/v1/workout-sessions/{id}/status", sessionId)
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"" + forbiddenStatus + "\",\"expectedVersion\":1}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        }
        mvc.perform(get("/api/v1/workout-sessions/{id}", sessionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data.version").value(1));

        mvc.perform(put("/api/v1/workout-sessions/{id}/status", sessionId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PAUSED\",\"expectedVersion\":0}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"))
                .andExpect(jsonPath("$.error.details.currentVersion").value(1));

        String setKey = "workout-set-" + UUID.randomUUID();
        String setBody = """
                {"sessionExerciseId":"%s","clientOperationSeq":1,"setType":"WORK","setOrder":1,
                 "target":{"weight":{"value":20,"unit":"KG"},"reps":10},
                 "actual":{"weight":{"value":20,"unit":"KG"},"reps":9},
                 "remainingReps":2,"completionStatus":"COMPLETED",
                 "completedAt":"2026-07-24T08:00:00Z","expectedSessionVersion":1}
                """.formatted(sessionExerciseId);
        JsonNode savedSet = json(mvc.perform(put(
                        "/api/v1/workout-sessions/{id}/sets/{clientSetKey}", sessionId, setKey)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", setKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(setBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sessionVersion").value(2))
                .andExpect(jsonPath("$.data.actual.weight.unit").value("KG"))
                .andReturn().getResponse().getContentAsString());
        String setId = savedSet.at("/data/setId").asText();

        mvc.perform(put("/api/v1/workout-sessions/{id}/sets/{clientSetKey}", sessionId, setKey)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", setKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(setBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.setId").value(setId))
                .andExpect(jsonPath("$.data.sessionVersion").value(2));

        mvc.perform(put("/api/v1/workout-sessions/{id}/sets/{clientSetKey}", sessionId, setKey)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", setKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(setBody.replace("\"reps\":9", "\"reps\":8")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));

        ObjectNode syncPayload = (ObjectNode) objectMapper.readTree(setBody);
        syncPayload.remove("clientOperationSeq");
        syncPayload.put("sessionId", sessionId);
        JsonNode conflictingPayload = syncPayload.deepCopy();
        ((ObjectNode) conflictingPayload.path("actual")).put("reps", 8);
        String syncBody = objectMapper.writeValueAsString(java.util.Map.of("operations", java.util.List.of(
                java.util.Map.of("clientOperationSeq", 1, "operationType", "UPSERT_SET",
                        "clientKey", setKey, "payload", syncPayload),
                java.util.Map.of("clientOperationSeq", 2, "operationType", "UPSERT_SET",
                        "clientKey", setKey, "payload", conflictingPayload),
                java.util.Map.of("clientOperationSeq", 3, "operationType", "UNSUPPORTED",
                        "clientKey", "unsupported-key", "payload", java.util.Map.of()))));
        mvc.perform(post("/api/v1/sync/workout-operations")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(syncBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results[0].status").value("DUPLICATE"))
                .andExpect(jsonPath("$.data.results[1].status").value("CONFLICT"))
                .andExpect(jsonPath("$.data.results[2].status").value("REJECTED"));
        JsonNode conflicts = json(mvc.perform(get("/api/v1/sync/conflicts")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].serverEvidence.payloadDigest").isNotEmpty())
                .andReturn().getResponse().getContentAsString());
        String conflictId = conflicts.at("/data/items/0/id").asText();
        mvc.perform(post("/api/v1/sync/conflicts/{id}/resolve", conflictId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resolution\":\"KEEP_SERVER\",\"expectedVersion\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.conflictId").value(conflictId))
                .andExpect(jsonPath("$.data.clientOperationSeq").value(2))
                .andExpect(jsonPath("$.data.clientKey").value(setKey))
                .andExpect(jsonPath("$.data.resolution").value("KEEP_SERVER"))
                .andExpect(jsonPath("$.data.outcome").value("ABANDONED"))
                .andExpect(jsonPath("$.data.authoritativeSessionVersion").value(2))
                .andExpect(jsonPath("$.data.authoritativePayload.kind").value("WORKOUT_SET"))
                .andExpect(jsonPath("$.data.authoritativePayload.actual.actualReps").value(9));
        mvc.perform(post("/api/v1/sync/conflicts/{id}/resolve", conflictId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resolution\":\"KEEP_SERVER\",\"expectedVersion\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.clientOperationSeq").value(2))
                .andExpect(jsonPath("$.data.authoritativeSessionVersion").value(2));
        mvc.perform(post("/api/v1/sync/conflicts/{id}/resolve", conflictId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resolution\":\"KEEP_LOCAL\",\"expectedVersion\":0}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));

        String completionKey = "workout-complete-" + UUID.randomUUID();
        mvc.perform(post("/api/v1/workout-sessions/{id}/complete", sessionId)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", completionKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":2,\"completionType\":\"EARLY_END\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.session.status").value("ABORTED"))
                .andExpect(jsonPath("$.data.completedWorkSets").value(1))
                .andExpect(jsonPath("$.data.complete").value(false))
                .andExpect(jsonPath("$.data.automaticProgressionEligible").value(false));
        mvc.perform(get("/api/v1/workout-sessions")
                        .header("Authorization", "Bearer " + token)
                        .queryParam("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].sessionId").value(sessionId))
                .andExpect(jsonPath("$.data.items[0].trainingDayName").value(dayCode))
                .andExpect(jsonPath("$.data.items[0].status").value("ABORTED"))
                .andExpect(jsonPath("$.data.items[0].completedWorkSets").value(1))
                .andExpect(jsonPath("$.data.items[0].completedReps").value(9))
                .andExpect(jsonPath("$.data.items[0].usesExternalLoad").value(true));

        String otherToken = login();
        mvc.perform(get("/api/v1/workout-sessions/{id}", sessionId)
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        mvc.perform(get("/api/v1/sync/conflicts")
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty());
        mvc.perform(post("/api/v1/sync/conflicts/{id}/resolve", conflictId)
                        .header("Authorization", "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resolution\":\"KEEP_LOCAL\",\"expectedVersion\":0}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void replacesTheActiveWorkoutAtomicallyAndReplaysTheStartedReplacement() throws Exception {
        String token = loginAndConfigure();
        JsonNode plan = createPlan(token, candidate(token).at("/candidateId").asText());
        String planId = plan.at("/data/planId").asText();
        String dayCode = plan.at("/data/activeVersion/plan/days/0/code").asText();
        String activeKey = "active-workout-" + UUID.randomUUID();
        String activeBody = objectMapper.writeValueAsString(java.util.Map.of(
                "clientSessionKey", activeKey,
                "planId", planId,
                "planVersionNo", 1,
                "planDayId", dayCode));
        JsonNode active = json(mvc.perform(post("/api/v1/workout-sessions")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", activeKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(activeBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        String activeId = active.at("/data/id").asText();
        mvc.perform(put("/api/v1/workout-sessions/{id}/status", activeId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"IN_PROGRESS\",\"expectedVersion\":0}"))
                .andExpect(status().isOk());

        String replacementKey = "replacement-workout-" + UUID.randomUUID();
        String incompleteReplacement = objectMapper.writeValueAsString(java.util.Map.of(
                "clientSessionKey", replacementKey,
                "planId", planId,
                "planVersionNo", 1,
                "planDayId", dayCode,
                "activeWorkoutReplacement", java.util.Map.of("sessionId", activeId)));
        mvc.perform(post("/api/v1/workout-sessions")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", replacementKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(incompleteReplacement))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        mvc.perform(get("/api/v1/workout-sessions/{id}", activeId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"));

        String staleReplacement = objectMapper.writeValueAsString(java.util.Map.of(
                "clientSessionKey", replacementKey,
                "planId", planId,
                "planVersionNo", 1,
                "planDayId", dayCode,
                "activeWorkoutReplacement", java.util.Map.of(
                        "sessionId", activeId,
                        "expectedVersion", 0)));
        mvc.perform(post("/api/v1/workout-sessions")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", replacementKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(staleReplacement))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));
        mvc.perform(get("/api/v1/workout-sessions/{id}", activeId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"));

        String replacementBody = objectMapper.writeValueAsString(java.util.Map.of(
                "clientSessionKey", replacementKey,
                "planId", planId,
                "planVersionNo", 1,
                "planDayId", dayCode,
                "activeWorkoutReplacement", java.util.Map.of(
                        "sessionId", activeId,
                        "expectedVersion", 1)));
        JsonNode replaced = json(mvc.perform(post("/api/v1/workout-sessions")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", replacementKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(replacementBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data.version").value(1))
                .andReturn().getResponse().getContentAsString());
        String replacementId = replaced.at("/data/id").asText();
        assertThat(replacementId).isNotEqualTo(activeId);
        mvc.perform(get("/api/v1/workout-sessions/{id}", activeId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ABORTED"));

        mvc.perform(post("/api/v1/workout-sessions")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", replacementKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(replacementBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(replacementId))
                .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"));
    }

    @Test
    void appliesOnlyAnEligibleReplacementToTheCurrentWorkoutSnapshot() throws Exception {
        String token = loginAndConfigure();
        JsonNode plan = createPlan(token, candidate(token).at("/candidateId").asText());
        String clientKey = "replacement-session-" + UUID.randomUUID();
        JsonNode created = json(mvc.perform(post("/api/v1/workout-sessions")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", clientKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "clientSessionKey", clientKey,
                                "planId", plan.at("/data/planId").asText(),
                                "planVersionNo", 1,
                                "planDayId", plan.at("/data/activeVersion/plan/days/0/code").asText()))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String sessionId = created.at("/data/id").asText();
        String snapshotId = created.at("/data/exercises/0/id").asText();
        String sourceCode = created.at("/data/exercises/0/exerciseCode").asText();
        mvc.perform(put("/api/v1/workout-sessions/{id}/status", sessionId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"IN_PROGRESS\",\"expectedVersion\":0}"))
                .andExpect(status().isOk());
        JsonNode candidates = json(mvc.perform(get(
                        "/api/v1/workout-sessions/{sessionId}/exercises/{snapshotId}/replacements",
                        sessionId, snapshotId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andReturn().getResponse().getContentAsString());
        String replacementCode = candidates.at("/data/items/0/code").asText();

        mvc.perform(put("/api/v1/workout-sessions/{id}/exercises/{exerciseId}", sessionId, snapshotId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"REPLACE\",\"replacementExerciseId\":\""
                                + replacementCode + "\",\"expectedVersion\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(2))
                .andExpect(jsonPath("$.data.exercises[0].exerciseCode").value(replacementCode))
                .andExpect(jsonPath("$.data.exercises[0].prescription.targetWeightKg").doesNotExist())
                .andExpect(jsonPath("$.data.exercises[0].status").value("REPLACED"));
        mvc.perform(put("/api/v1/workout-sessions/{id}/exercises/{exerciseId}", sessionId, snapshotId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"REPLACE\",\"replacementExerciseId\":\"UNKNOWN\",\"expectedVersion\":2}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void logicallyVoidsAnOwnedSetIdempotentlyAndExcludesItFromSummariesAndHistory() throws Exception {
        String token = loginAndConfigure();
        JsonNode plan = createPlan(token, candidate(token).at("/candidateId").asText());
        String dayCode = plan.at("/data/activeVersion/plan/days/0/code").asText();
        String sessionKey = "void-session-" + UUID.randomUUID();
        JsonNode created = json(mvc.perform(post("/api/v1/workout-sessions")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", sessionKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "clientSessionKey", sessionKey,
                                "planId", plan.at("/data/planId").asText(),
                                "planVersionNo", 1,
                                "planDayId", dayCode,
                                "trainingDayCode", dayCode))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        String sessionId = created.at("/data/id").asText();
        String sessionExerciseId = created.at("/data/exercises/0/id").asText();
        mvc.perform(put("/api/v1/workout-sessions/{id}/status", sessionId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"IN_PROGRESS\",\"expectedVersion\":0}"))
                .andExpect(status().isOk());

        String setKey = "void-source-" + UUID.randomUUID();
        String setBody = """
                {"sessionExerciseId":"%s","clientOperationSeq":1,"setType":"WORK","setOrder":1,
                 "target":{"weight":{"value":20,"unit":"KG"},"reps":10},
                 "actual":{"weight":{"value":20,"unit":"KG"},"reps":9},
                 "remainingReps":2,"completionStatus":"COMPLETED",
                 "completedAt":"2026-07-24T08:00:00Z","expectedSessionVersion":1}
                """.formatted(sessionExerciseId);
        JsonNode saved = json(mvc.perform(put(
                        "/api/v1/workout-sessions/{sessionId}/sets/{clientSetKey}", sessionId, setKey)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", setKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(setBody))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        String setId = saved.at("/data/setId").asText();
        String voidKey = "void-operation-" + UUID.randomUUID();

        mvc.perform(delete("/api/v1/workout-sessions/{sessionId}/sets/{setId}", sessionId, setId)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", voidKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedSessionVersion\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"))
                .andExpect(jsonPath("$.error.details.currentVersion").value(2));

        JsonNode voided = json(mvc.perform(delete(
                        "/api/v1/workout-sessions/{sessionId}/sets/{setId}", sessionId, setId)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", voidKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedSessionVersion\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.setId").value(setId))
                .andExpect(jsonPath("$.data.reason").value("USER_REQUESTED"))
                .andExpect(jsonPath("$.data.sessionVersion").value(3))
                .andExpect(jsonPath("$.data.duplicate").value(false))
                .andReturn().getResponse().getContentAsString());
        String voidId = voided.at("/data/voidId").asText();

        mvc.perform(delete("/api/v1/workout-sessions/{sessionId}/sets/{setId}", sessionId, setId)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", voidKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedSessionVersion\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.voidId").value(voidId))
                .andExpect(jsonPath("$.data.sessionVersion").value(3))
                .andExpect(jsonPath("$.data.duplicate").value(true));

        mvc.perform(get("/api/v1/workout-sessions/{id}/summary", sessionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("WORKOUT_NOT_TERMINAL"));

        String otherToken = login();
        mvc.perform(get("/api/v1/workout-sessions/{id}/summary", sessionId)
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        mvc.perform(delete("/api/v1/workout-sessions/{sessionId}/sets/{setId}", sessionId, setId)
                        .header("Authorization", "Bearer " + otherToken)
                        .header("Idempotency-Key", "other-user-void-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedSessionVersion\":3}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));

        mvc.perform(post("/api/v1/workout-sessions/{id}/complete", sessionId)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "void-complete-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":3,\"completionType\":\"EARLY_END\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.completedWorkSets").value(0));
        mvc.perform(get("/api/v1/workout-sessions/{id}/summary", sessionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ABORTED"))
                .andExpect(jsonPath("$.data.completedWorkSets").value(0))
                .andExpect(jsonPath("$.data.completedReps").value(0));
        mvc.perform(get("/api/v1/workout-sessions")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].sessionId").value(sessionId))
                .andExpect(jsonPath("$.data.items[0].completedWorkSets").value(0))
                .andExpect(jsonPath("$.data.items[0].completedReps").value(0));
    }

    private JsonNode candidate(String token) throws Exception {
        return json(mvc.perform(post("/api/v1/plans/candidates")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"profileVersion\":1,\"lockedFields\":{}}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).at("/data/candidate");
    }

    private void saveRecoverySet(
            String token,
            String sessionId,
            String sessionExerciseId,
            int setOrder,
            int expectedSessionVersion,
            String completionStatus,
            boolean completed) throws Exception {
        String completedAt = completed ? ",\"completedAt\":\"2026-08-11T08:00:00Z\"" : "";
        String setBody = """
                {"sessionExerciseId":"%s","clientOperationSeq":%d,"setType":"WORK","setOrder":%d,
                 "target":{"weight":{"value":20,"unit":"KG"},"reps":10},
                 "actual":{"weight":{"value":20,"unit":"KG"},"reps":%d},
                 "completionStatus":"%s","expectedSessionVersion":%d%s}
                """.formatted(
                        sessionExerciseId, setOrder, setOrder, completed ? 10 : 8,
                        completionStatus, expectedSessionVersion, completedAt);
        String setKey = "recoverable-set-" + UUID.randomUUID();
        mvc.perform(put("/api/v1/workout-sessions/{sessionId}/sets/{setKey}", sessionId, setKey)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", setKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(setBody))
                .andExpect(status().isOk());
    }

    private JsonNode createPlan(String token, String candidateId) throws Exception {
        return json(mvc.perform(post("/api/v1/plans")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"candidateId\":\"" + candidateId + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
    }

    private String loginAndConfigure() throws Exception {
        String token = login();
        mvc.perform(put("/api/v1/profile")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"experience":"BEGINNER","goal":"GENERAL_FITNESS",
                                 "weeklyFrequency":3,"sessionMinutes":60,"location":"GYM",
                                 "expectedVersion":0}
                                """))
                .andExpect(status().isOk());
        String items = java.util.Arrays.stream(new String[] {"DUMBBELL", "BENCH", "CABLE", "MACHINE"})
                .map(type -> """
                        {"clientEquipmentKey":"%s","equipmentType":"%s",
                         "minIncrement":{"value":1,"unit":"KG"},
                         "availableLevels":[{"value":1,"unit":"KG"}]}
                        """.formatted(UUID.randomUUID(), type))
                .collect(java.util.stream.Collectors.joining(","));
        mvc.perform(put("/api/v1/profile/equipment")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[" + items + "],\"expectedVersion\":0}"))
                .andExpect(status().isOk());
        return token;
    }

    private String login() throws Exception {
        String login = mvc.perform(post("/api/v1/auth/wechat/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"workout-" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json(login).at("/data/accessToken").asText();
    }

    private JsonNode json(String value) throws Exception {
        return objectMapper.readTree(value);
    }
}
