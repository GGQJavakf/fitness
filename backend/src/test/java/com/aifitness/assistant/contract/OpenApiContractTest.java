package com.aifitness.assistant.contract;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.yaml.snakeyaml.Yaml;

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
            "GET /api/v1/plan-templates",
            "POST /api/v1/plans/candidates",
            "POST /api/v1/plans/validate",
            "POST /api/v1/plans",
            "GET /api/v1/plans/active",
            "GET /api/v1/plans/{planId}/versions/{versionNo}",
            "POST /api/v1/plans/{planId}/versions",
            "POST /api/v1/plans/{planId}/rebalance",
            "GET /api/v1/workouts/today",
            "POST /api/v1/workout-sessions",
            "GET /api/v1/workout-sessions/{id}",
            "PUT /api/v1/workout-sessions/{id}/status",
            "PUT /api/v1/workout-sessions/{id}/exercises/{exerciseId}",
            "PUT /api/v1/workout-sessions/{id}/sets/{clientSetKey}",
            "DELETE /api/v1/workout-sessions/{id}/sets/{clientSetKey}",
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
            "POST /api/v1/privacy/deletion-requests",
            "GET /api/v1/privacy/deletion-requests/{id}");

    private static final Set<String> HTTP_METHODS = Set.of("get", "put", "post", "delete", "patch");
    private static Path contractRoot;
    private static Map<String, Object> openApi;
    private static Map<String, Object> commonSchemas;

    @BeforeAll
    static void loadContract() throws IOException {
        contractRoot = Path.of(System.getProperty("user.dir"), "..", "contract").normalize();
        openApi = loadYaml(contractRoot.resolve("openapi.yaml"));
        commonSchemas = map(loadYaml(contractRoot.resolve("schemas/common.yaml")).get("components"));
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
    void publishesContractDerivedJavaTypes() throws ClassNotFoundException {
        assertThat(Class.forName("com.aifitness.assistant.common.api.ApiResponse")).isNotNull();
        assertThat(Class.forName("com.aifitness.assistant.common.api.ApiError")).isNotNull();
        assertThat(Class.forName("com.aifitness.assistant.common.api.FieldError")).isNotNull();
        assertThat(Class.forName("com.aifitness.assistant.common.domain.Weight")).isNotNull();
        assertThat(Class.forName("com.aifitness.assistant.common.domain.RuleReference")).isNotNull();
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
