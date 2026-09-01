package com.aifitness.assistant.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aifitness.assistant.FitnessAssistantApplication;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.yaml.snakeyaml.Yaml;

@SpringBootTest(classes = FitnessAssistantApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OpenApiContractTest {

    private static final Set<String> P0_OPERATIONS = Set.of(
            "POST /api/v1/auth/wechat/login",
            "POST /api/v1/auth/refresh",
            "POST /api/v1/auth/logout",
            "GET /api/v1/profile",
            "PUT /api/v1/profile",
            "GET /api/v1/profile/equipment",
            "PUT /api/v1/profile/equipment",
            "GET /api/v1/profile/preferences",
            "PUT /api/v1/profile/preferences",
            "GET /api/v1/exercises",
            "GET /api/v1/exercises/{id}",
            "GET /api/v1/exercises/{sourceCode}/replacements",
            "GET /api/v1/workout-sessions/{sessionId}/exercises/{snapshotId}/replacements",
            "GET /api/v1/plan-templates",
            "POST /api/v1/plans/candidates",
            "POST /api/v1/plans/candidate-commits",
            "GET /api/v1/plans/presets",
            "GET /api/v1/plans/generation-context",
            "GET /api/v1/plans/{planId}/exercise-options",
            "GET /api/v1/plans/{planId}/exercise-replacements",
            "GET /api/v1/plans/{planId}/day-options",
            "POST /api/v1/plans/validate",
            "POST /api/v1/plans",
            "GET /api/v1/plans/active",
            "GET /api/v1/plans/{planId}/versions/{versionNo}",
            "POST /api/v1/plans/{planId}/versions",
            "POST /api/v1/plans/{planId}/rebalance",
            "GET /api/v1/workout-recovery-checks",
            "POST /api/v1/workout-sessions",
            "GET /api/v1/workout-sessions/{id}",
            "PUT /api/v1/workout-sessions/{id}/status",
            "PUT /api/v1/workout-sessions/{id}/exercises/{exerciseId}",
            "PUT /api/v1/workout-sessions/{sessionId}/sets/{setId}",
            "DELETE /api/v1/workout-sessions/{sessionId}/sets/{setId}",
            "POST /api/v1/workout-sessions/{id}/complete",
            "POST /api/v1/sync/workout-operations",
            "GET /api/v1/sync/conflicts",
            "POST /api/v1/sync/conflicts/{id}/resolve",
            "GET /api/v1/workout-sessions",
            "GET /api/v1/workout-sessions/{id}/summary",
            "GET /api/v1/progress/exercises/{exerciseId}",
            "GET /api/v1/progression-recommendations",
            "POST /api/v1/progression-recommendations/{id}/apply",
            "POST /api/v1/progression-recommendations/{id}/dismiss",
            "POST /api/v1/ai/plan-explanations",
            "POST /api/v1/ai/workout-summaries",
            "GET /api/v1/privacy/export",
            "POST /api/v1/privacy/reauthentication-proofs",
            "GET /api/v1/privacy/exports/{id}",
            "POST /api/v1/privacy/deletion-requests",
            "GET /api/v1/privacy/deletion-requests/{id}",
            "POST /api/v1/privacy/deletion-requests/{id}/process");

    private static final Set<String> HTTP_METHODS = Set.of("get", "put", "post", "delete", "patch");
    private static Path contractRoot;
    private static Map<String, Object> openApi;
    private static Map<String, Object> commonSchemas;
    private static Map<String, Object> profileSchemas;
    private static Map<String, Object> planSchemas;
    private static Map<String, Object> workoutSchemas;
    private static Map<String, Object> privacySchemas;

    @Autowired @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;
    @Autowired private MockMvc mvc;

    @BeforeAll
    static void loadContract() throws IOException {
        contractRoot = Path.of(System.getProperty("user.dir"), "..", "contract").normalize();
        openApi = loadYaml(contractRoot.resolve("openapi.yaml"));
        commonSchemas = map(loadYaml(contractRoot.resolve("schemas/common.yaml")).get("components"));
        profileSchemas = map(loadYaml(contractRoot.resolve("schemas/profile.yaml")).get("components"));
        planSchemas = map(loadYaml(contractRoot.resolve("schemas/plan.yaml")).get("components"));
        workoutSchemas = map(loadYaml(contractRoot.resolve("schemas/workout.yaml")).get("components"));
        privacySchemas = map(loadYaml(contractRoot.resolve("schemas/privacy.yaml")).get("components"));
    }

    @Test
    void exposesExactlyTheP0OperationsUnderVersionedBusinessPaths() {
        Set<String> actual = new LinkedHashSet<>();
        map(openApi.get("paths")).forEach((path, value) -> map(value).keySet().stream()
                .filter(HTTP_METHODS::contains)
                .map(String::toUpperCase)
                .map(method -> method + " " + path)
                .forEach(actual::add));

        assertThat(openApi.get("openapi")).isEqualTo("3.1.0");
        assertThat(actual).containsExactlyInAnyOrderElementsOf(P0_OPERATIONS);
    }

    @Test
    void springMappingsAndOpenApiOperationsHaveBidirectionalMethodPathParity() {
        Set<String> documented = openApiOperations();
        Set<String> implemented = new LinkedHashSet<>();
        handlerMapping.getHandlerMethods().forEach((mapping, handler) -> {
            for (String path : mapping.getPatternValues()) {
                if (!path.startsWith("/api/v1/")) {
                    continue;
                }
                for (RequestMethod method : mapping.getMethodsCondition().getMethods()) {
                    implemented.add(method.name() + " " + normalizePath(path));
                }
            }
        });

        assertThat(implemented).containsExactlyInAnyOrderElementsOf(documented);
    }

    @Test
    void criticalMockMvcErrorsMatchTheDocumentedEnvelope() throws Exception {
        assertThat(required(map(commonSchemas.get("schemas")), "ApiErrorResponse"))
                .containsExactlyInAnyOrder("error", "meta");

        mvc.perform(post("/api/v1/auth/wechat/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.fieldErrors").isArray())
                .andExpect(jsonPath("$.error.details").isMap())
                .andExpect(jsonPath("$.error.retryable").isBoolean())
                .andExpect(jsonPath("$.meta.requestId").isNotEmpty());
        mvc.perform(get("/api/v1/profile"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"))
                .andExpect(jsonPath("$.meta.requestId").isNotEmpty());
    }

    @Test
    void definesUniformSuccessAndErrorEnvelopesWithStableMachineCodes() {
        Map<String, Object> schemas = map(commonSchemas.get("schemas"));
        assertThat(schemas).containsKeys("ApiResponse", "ApiError", "FieldError", "ResponseMeta");
        assertThat(required(schemas, "ApiResponse")).containsExactlyInAnyOrder("data", "meta");
        assertThat(required(schemas, "ApiError")).containsExactlyInAnyOrder(
                "code", "message", "fieldErrors", "details", "retryable");
        assertThat(required(schemas, "FieldError")).containsExactlyInAnyOrder("path", "code");
        assertThat(required(schemas, "ResponseMeta")).contains("requestId");

        Map<String, Object> errorCode = map(schemas.get("ErrorCode"));
        assertThat(list(errorCode.get("enum"))).contains(
                "VERSION_CONFLICT", "IDEMPOTENCY_KEY_REUSED", "PLAN_VALIDATION_FAILED");
    }

    @Test
    void keepsTimeWeightAndRuleReferencesDeterministic() {
        Map<String, Object> schemas = map(commonSchemas.get("schemas"));
        Map<String, Object> utc = map(schemas.get("UtcDateTime"));
        assertThat(utc).containsEntry("type", "string").containsEntry("format", "date-time");
        assertThat(utc.get("pattern")).isEqualTo("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?Z$");

        Map<String, Object> weight = map(schemas.get("Weight"));
        Map<String, Object> value = map(map(weight.get("properties")).get("value"));
        assertThat(value).containsEntry("type", "number")
                .containsEntry("minimum", 0).containsEntry("multipleOf", 0.01d);
        assertThat(list(map(schemas.get("WeightUnit")).get("enum"))).containsExactly("KG");
        assertThat(required(schemas, "RuleReference")).containsExactlyInAnyOrder(
                "ruleVersion", "templateVersion", "contentVersion");
    }

    @Test
    void marksIdempotentAndVersionedCommandsAndNeverTrustsUserIdInput() throws IOException {
        String allContractText = Files.readString(contractRoot.resolve("openapi.yaml"))
                + Files.readString(contractRoot.resolve("schemas/common.yaml"))
                + Files.readString(contractRoot.resolve("schemas/profile.yaml"))
                + Files.readString(contractRoot.resolve("schemas/plan.yaml"))
                + Files.readString(contractRoot.resolve("schemas/workout.yaml"))
                + Files.readString(contractRoot.resolve("schemas/progression.yaml"));

        assertThat(allContractText).contains("Idempotency-Key", "expectedVersion", "VERSION_CONFLICT");
        assertThat(allContractText).doesNotContain("userId:");
    }

    @Test
    void workoutStartPublishesRecoveryConfirmationIdempotencyAndActiveWorkoutConflictShapes() {
        Map<String, Object> schemas = map(workoutSchemas.get("schemas"));
        Map<String, Object> startProperties = map(map(schemas.get("StartWorkoutSessionRequest")).get("properties"));
        assertThat(startProperties).containsKey("recoveryConfirmationToken");
        assertThat(required(schemas, "WorkoutRecoveryConfirmationDetails"))
                .containsExactlyInAnyOrder("assessment", "confirmationToken", "confirmationExpiresAt");

        Map<String, Object> post = map(map(map(openApi.get("paths"))
                .get("/api/v1/workout-sessions")).get("post"));
        Map<String, Object> conflict = map(map(post.get("responses")).get("409"));
        String conflictRef = (String) map(map(map(conflict.get("content"))
                .get("application/json")).get("schema")).get("$ref");
        assertThat(conflictRef).endsWith("/WorkoutSessionStartConflictResponse");
        assertThat(list(map(schemas.get("WorkoutSessionStartConflictResponse")).get("oneOf")))
                .extracting(item -> map(item).get("$ref"))
                .containsExactlyInAnyOrder(
                        "#/components/schemas/WorkoutRecoveryConfirmationErrorResponse",
                        "#/components/schemas/WorkoutStartIdempotencyErrorResponse",
                        "#/components/schemas/ActiveWorkoutExistsErrorResponse",
                        "#/components/schemas/WorkoutStartTerminalReplayErrorResponse");
    }

    @Test
    void workoutExerciseUpdatePublishesBothVersionAndReplacementConflictCodes() {
        Map<String, Object> schemas = map(workoutSchemas.get("schemas"));
        Map<String, Object> put = map(map(map(openApi.get("paths"))
                .get("/api/v1/workout-sessions/{id}/exercises/{exerciseId}")).get("put"));
        Map<String, Object> conflict = map(map(put.get("responses")).get("409"));
        String conflictRef = (String) map(map(map(conflict.get("content"))
                .get("application/json")).get("schema")).get("$ref");
        assertThat(conflictRef).endsWith("/WorkoutExerciseUpdateConflictResponse");
        Map<String, Object> errorProperties = map(map(
                schemas.get("WorkoutExerciseUpdateConflictError")).get("properties"));
        assertThat(list(map(errorProperties.get("code")).get("enum")))
                .containsExactlyInAnyOrder("VERSION_CONFLICT", "INSUFFICIENT_REPLACEMENTS");
    }

    @Test
    void publishesContractDerivedJavaTypes() throws ClassNotFoundException {
        assertThat(Class.forName("com.aifitness.assistant.common.api.ApiResponse")).isNotNull();
        assertThat(Class.forName("com.aifitness.assistant.common.api.ApiError")).isNotNull();
        assertThat(Class.forName("com.aifitness.assistant.common.api.FieldError")).isNotNull();
        assertThat(Class.forName("com.aifitness.assistant.common.domain.Weight")).isNotNull();
        assertThat(Class.forName("com.aifitness.assistant.common.domain.RuleReference")).isNotNull();
    }

    @Test
    void profileOperationsExposeTheirTypedSuccessEnvelopes() {
        assertThat(successSchemaRef("/api/v1/profile", "get")).endsWith("/UserProfileResponse");
        assertThat(successSchemaRef("/api/v1/profile", "put")).endsWith("/UserProfileResponse");
        assertThat(successSchemaRef("/api/v1/profile/equipment", "get"))
                .endsWith("/EquipmentProfileResponse");
        assertThat(successSchemaRef("/api/v1/profile/equipment", "put"))
                .endsWith("/EquipmentProfileResponse");
        assertThat(successSchemaRef("/api/v1/profile/preferences", "get"))
                .endsWith("/PreferenceProfileResponse");
        assertThat(successSchemaRef("/api/v1/profile/preferences", "put"))
                .endsWith("/PreferenceProfileResponse");
    }

    @Test
    void contentOperationsExposeVersionedTypedSuccessEnvelopes() {
        assertThat(successSchemaRef("/api/v1/exercises", "get"))
                .endsWith("/ExerciseListResponse");
        assertThat(successSchemaRef("/api/v1/exercises/{id}", "get"))
                .endsWith("/ExerciseDetailResponse");
        assertThat(successSchemaRef("/api/v1/plan-templates", "get"))
                .endsWith("/PlanTemplateListResponse");
    }

    @Test
    void planCandidateOperationsExposeTypedDeterministicContracts() {
        assertThat(successSchemaRef("/api/v1/plans/candidates", "post"))
                .endsWith("/PlanCandidateGenerationResponse");
        assertThat(successSchemaRef("/api/v1/plans/validate", "post"))
                .endsWith("/PlanValidationResponse");
        Map<String, Object> schemas = map(planSchemas.get("schemas"));
        assertThat(required(schemas, "PlanExercise"))
                .contains("workSets", "repMin", "repMax", "restSeconds", "weightStatus");
        assertThat(required(schemas, "PlanCandidate"))
                .contains("ruleReference", "explanationStatus", "explanation");
    }

    @Test
    void aiOperationsExposeTypedValidatedOrDegradedContent() {
        assertThat(successSchemaRef("/api/v1/ai/plan-explanations", "post"))
                .endsWith("/AiGeneratedContentResponse");
        assertThat(successSchemaRef("/api/v1/ai/workout-summaries", "post"))
                .endsWith("/AiGeneratedContentResponse");
    }

    @Test
    void planVersionOperationsExposeTypedImmutableContracts() {
        assertThat(successSchemaRef("/api/v1/plans", "post"))
                .endsWith("/ActivePlanResponse");
        assertThat(successSchemaRef("/api/v1/plans/active", "get"))
                .endsWith("/ActivePlanResponse");
        assertThat(successSchemaRef("/api/v1/plans/{planId}/versions/{versionNo}", "get"))
                .endsWith("/PlanVersionResponse");
        assertThat(successSchemaRef("/api/v1/plans/{planId}/versions", "post"))
                .endsWith("/PlanVersionResultResponse");
        assertThat(successSchemaRef("/api/v1/plans/{planId}/rebalance", "post"))
                .endsWith("/PlanVersionResultResponse");

        Map<String, Object> schemas = map(planSchemas.get("schemas"));
        assertThat(required(schemas, "CreatePlanVersionRequest"))
                .containsExactlyInAnyOrder("plan", "baseVersionNumber", "locks");
        assertThat(map(map(schemas.get("CreatePlanVersionRequest")).get("properties")))
                .containsKey("warningConfirmationToken")
                .doesNotContainKey("expectedVersion");
        Map<String, Object> versionProperties =
                map(map(schemas.get("CreatePlanVersionRequest")).get("properties"));
        assertThat(map(versionProperties.get("plan")))
                .containsEntry("$ref", "#/components/schemas/PlanValidationDraft");
        Map<String, Object> lockCommands = map(schemas.get("LockCommandStatus"));
        assertThat(list(lockCommands.get("enum")))
                .containsExactly("USER_LOCKED", "UNLOCKED")
                .doesNotContain("RULE_LOCKED");
        for (String requestName : List.of("CreatePlanVersionRequest", "RebalancePlanRequest")) {
            Map<String, Object> locks = map(map(map(schemas.get(requestName)).get("properties")).get("locks"));
            assertThat(map(locks.get("propertyNames")).get("pattern"))
                    .as("%s must accept every domain-supported editable lock path", requestName)
                    .isEqualTo("^/days/[^/]+/exercises/[^/]+/(workSets|repMin|repMax|restSeconds|targetWeightKg)$");
        }
        assertThat(required(schemas, "PlanVersionData"))
                .contains("versionNumber", "plan", "ruleReference", "confirmedWarningCodes", "createdAt");
    }

    @Test
    void workoutSessionOperationsExposeTypedImmutableSnapshots() {
        assertThat(successSchemaRef("/api/v1/workout-sessions", "post"))
                .endsWith("/WorkoutSessionResponse");
        assertThat(successSchemaRef("/api/v1/workout-sessions/{id}", "get"))
                .endsWith("/WorkoutSessionResponse");
        assertThat(successSchemaRef("/api/v1/workout-sessions/{id}/status", "put"))
                .endsWith("/WorkoutSessionResponse");
        assertThat(successSchemaRef("/api/v1/workout-sessions/{id}/exercises/{exerciseId}", "put"))
                .endsWith("/WorkoutSessionResponse");
        assertThat(successSchemaRef("/api/v1/workout-sessions/{sessionId}/sets/{setId}", "put"))
                .endsWith("/WorkoutSetResponse");
        assertThat(successSchemaRef("/api/v1/workout-sessions/{sessionId}/sets/{setId}", "delete"))
                .endsWith("/WorkoutSetVoidResponse");
        assertThat(successSchemaRef("/api/v1/workout-sessions", "get"))
                .endsWith("/WorkoutHistoryResponse");
        assertThat(successSchemaRef("/api/v1/workout-sessions/{id}/complete", "post"))
                .endsWith("/WorkoutCompletionResponse");

        Map<String, Object> schemas = map(workoutSchemas.get("schemas"));
        assertThat(required(schemas, "WorkoutSetVoidData"))
                .containsExactlyInAnyOrder(
                        "voidId", "setId", "reason", "voidedAt", "sessionVersion", "duplicate");
        assertThat(required(schemas, "WorkoutSessionData"))
                .contains("planVersionId", "planVersionNo", "planDayId", "trainingDayCode",
                        "status", "version", "exercises");
        assertThat(required(schemas, "WorkoutHistoryItem"))
                .contains("trainingDayCode", "trainingDayName");
        Map<String, Object> statusProperty = map(map(
                map(schemas.get("UpdateSessionStatusRequest")).get("properties")).get("status"));
        assertThat(list(statusProperty.get("enum"))).doesNotContain("COMPLETING", "COMPLETED");
        assertThat(list(map(map(map(schemas.get("UpdateSessionExerciseRequest"))
                .get("properties")).get("action")).get("enum")))
                .containsExactly("REPLACE");
        assertThat(required(schemas, "WorkoutExerciseSnapshot"))
                .contains("exerciseCode", "exerciseName", "contentVersion", "equipment", "prescription");
        assertThat(required(schemas, "WorkoutPrescriptionSnapshot"))
                .containsExactlyInAnyOrder(
                        "workSets", "repMin", "repMax", "restSeconds", "weightStatus", "unit");
        assertThat(map(map(map(schemas.get("WorkoutPrescriptionSnapshot"))
                .get("properties")).get("optionalSetRule")))
                .containsEntry("$ref", "#/components/schemas/WorkoutOptionalSetRule");
        assertThat(map(map(schemas.get("WorkoutOptionalSetRule")).get("properties")))
                .containsKey("description");
        assertThat(required(schemas, "UpsertSetRequest"))
                .contains("sessionExerciseId", "clientOperationSeq", "target", "actual", "expectedSessionVersion");
        assertThat(required(schemas, "WorkoutSetData"))
                .contains("setId", "sessionExerciseId", "clientSetKey", "serverRevision", "sessionVersion", "syncStatus");
        assertThat(list(map(map(commonSchemas.get("schemas")).get("ErrorCode")).get("enum")))
                .contains("ANOMALY_CONFIRMATION_REQUIRED", "ACCESS_REVOKED");
    }

    @Test
    void syncOperationsExposeTypedPerItemResultsAndExplicitConflictResolution() {
        assertThat(successSchemaRef("/api/v1/sync/workout-operations", "post"))
                .endsWith("/SyncWorkoutOperationsResponse");
        assertThat(successSchemaRef("/api/v1/sync/conflicts", "get"))
                .endsWith("/SyncConflictListResponse");
        assertThat(successSchemaRef("/api/v1/sync/conflicts/{id}/resolve", "post"))
                .endsWith("/SyncConflictResolutionResponse");
        Map<String, Object> schemas = map(workoutSchemas.get("schemas"));
        assertThat(required(schemas, "SyncSetPayload"))
                .contains("sessionId", "sessionExerciseId", "expectedSessionVersion");
        assertThat(required(schemas, "SyncOperationResult"))
                .containsExactlyInAnyOrder("clientOperationSeq", "status");
        assertThat(required(schemas, "ResolveSyncConflictRequest"))
                .containsExactlyInAnyOrder("resolution", "expectedVersion");
        assertThat(required(schemas, "SyncConflictResolutionData"))
                .containsExactlyInAnyOrder(
                        "conflictId", "clientOperationSeq", "clientKey", "resolution", "outcome",
                        "authoritativeSessionVersion", "authoritativePayload");
        Map<String, Object> resolutionProperties = map(
                map(schemas.get("SyncConflictResolutionData")).get("properties"));
        assertThat(list(map(resolutionProperties.get("outcome")).get("enum")))
                .containsExactlyInAnyOrder("ACKNOWLEDGED", "REBUILT", "ABANDONED");
    }

    @Test
    void privacyOperationsRequireReauthenticationAndExposeTypedRetentionAwareContracts() {
        assertThat(successSchemaRef("/api/v1/privacy/export", "get"))
                .endsWith("/PrivacyExportResponse");
        assertThat(successSchemaRef("/api/v1/privacy/deletion-requests", "post"))
                .endsWith("/DeletionRequestResponse");
        assertThat(successSchemaRef("/api/v1/privacy/deletion-requests/{id}", "get"))
                .endsWith("/DeletionRequestResponse");

        Map<String, Object> exportOperation = map(
                map(map(openApi.get("paths")).get("/api/v1/privacy/export")).get("get"));
        assertThat(list(exportOperation.get("parameters")).toString())
                .contains("X-Reauthentication-Proof");
        Map<String, Object> schemas = map(privacySchemas.get("schemas"));
        assertThat(required(schemas, "CreateDeletionRequest"))
                .containsExactlyInAnyOrder("reauthenticationProof", "confirmationText");
        assertThat(list(map(map(map(schemas.get("CreateDeletionRequest"))
                .get("properties")).get("confirmationText")).get("enum")))
                .containsExactly("DELETE");
        assertThat(required(schemas, "PrivacyExportData"))
                .containsExactlyInAnyOrder(
                        "id", "status", "generatedAt", "expiresAt", "resources",
                        "scope", "excludedRetentionCategories");
        assertThat(required(schemas, "PrivacyExportResource"))
                .containsExactlyInAnyOrder("category", "recordCount", "records");
        assertThat(required(schemas, "PrivacyExportRecord"))
                .containsExactlyInAnyOrder("id", "summary");
        assertThat(required(schemas, "ReauthenticationProofData"))
                .containsExactlyInAnyOrder("proof", "issuedAt", "expiresAt");

        for (String path : List.of(
                "/api/v1/privacy/reauthentication-proofs",
                "/api/v1/privacy/export",
                "/api/v1/privacy/exports/{id}",
                "/api/v1/privacy/deletion-requests",
                "/api/v1/privacy/deletion-requests/{id}",
                "/api/v1/privacy/deletion-requests/{id}/process")) {
            String method = path.equals("/api/v1/privacy/reauthentication-proofs")
                    || path.equals("/api/v1/privacy/deletion-requests")
                    || path.endsWith("/process") ? "post" : "get";
            Map<String, Object> operation = map(map(map(openApi.get("paths")).get(path)).get(method));
            assertThat(map(operation.get("responses"))).containsKey("429");
        }
    }

    @Test
    void equipmentUpdateInputDoesNotExposeServerManagedProfileId() {
        Map<String, Object> schemas = map(profileSchemas.get("schemas"));
        Map<String, Object> inputWeight = map(schemas.get("EquipmentWeightInput"));
        assertThat(map(inputWeight.get("properties"))).containsOnlyKeys("value", "unit");
        assertThat(required(schemas, "EquipmentWeightInput")).containsExactlyInAnyOrder("value", "unit");
        assertThat(map(map(schemas.get("EquipmentItemRequest")).get("properties")))
                .containsKeys("clientEquipmentKey", "minIncrement", "availableLevels");
        Map<String, Object> requestItems = map(
                map(map(schemas.get("UpdateEquipmentRequest")).get("properties")).get("items"));
        assertThat(map(requestItems.get("items")))
                .containsEntry("$ref", "#/components/schemas/EquipmentItemRequest");
    }

    private static String successSchemaRef(String path, String method) {
        Map<String, Object> operation = map(map(map(openApi.get("paths")).get(path)).get(method));
        Map<String, Object> responses = map(operation.get("responses"));
        Map<String, Object> response = map(responses.containsKey("200") ? responses.get("200") : responses.get("201"));
        Map<String, Object> content = map(response.get("content"));
        return (String) map(map(content.get("application/json")).get("schema")).get("$ref");
    }

    private static Set<String> openApiOperations() {
        Set<String> operations = new LinkedHashSet<>();
        map(openApi.get("paths")).forEach((path, value) -> map(value).keySet().stream()
                .filter(HTTP_METHODS::contains)
                .map(String::toUpperCase)
                .map(method -> method + " " + normalizePath(path))
                .forEach(operations::add));
        return operations;
    }

    private static String normalizePath(String path) {
        return path.replaceAll("\\{[^/}]+}", "{}");
    }

    private static Map<String, Object> loadYaml(Path path) throws IOException {
        assertThat(path).as("contract file %s", path).isRegularFile();
        try (InputStream input = Files.newInputStream(path)) {
            return map(new Yaml().load(input));
        }
    }

    private static List<Object> required(Map<String, Object> schemas, String name) {
        return list(map(schemas.get(name)).get("required"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return (List<Object>) value;
    }
}
