package com.aifitness.assistant.progression;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

import com.aifitness.assistant.common.domain.RuleReference;
import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.plan.application.PlanVersionService;
import com.aifitness.assistant.plan.domain.PlanDraft;
import com.aifitness.assistant.plan.domain.TrainingPlanVersion;
import com.aifitness.assistant.plan.infrastructure.InMemoryPlanRepository;
import com.aifitness.assistant.progression.api.RecommendationController;
import com.aifitness.assistant.progression.api.RecommendationExceptionHandler;
import com.aifitness.assistant.progression.application.RecommendationService;
import com.aifitness.assistant.progression.domain.ProgressionRecommendation;
import com.aifitness.assistant.progression.infrastructure.InMemoryRecommendationRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class RecommendationEndpointIntegrationTest {
    private static final AuthenticatedUserId USER = new AuthenticatedUserId(UUID.fromString(
            "30000000-0000-0000-0000-000000000001"));
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-24T12:00:00Z"), ZoneOffset.UTC);

    private MockMvc mvc;
    private RecommendationService service;

    @BeforeEach
    void setUp() {
        InMemoryPlanRepository planRepository = new InMemoryPlanRepository();
        UUID planId = UUID.randomUUID();
        PlanDraft plan = new PlanDraft("FULL_BODY", "基础计划", List.of(new PlanDraft.Day(
                "DAY_A", "训练 A", List.of(new PlanDraft.Exercise(
                        "GOBLET_SQUAT", 3, 8, 12, 90, PlanDraft.WeightStatus.KNOWN)))), Map.of());
        planRepository.create(USER.value(), new TrainingPlanVersion(
                UUID.randomUUID(), planId, 1, TrainingPlanVersion.SourceType.INITIAL, plan,
                new RuleReference("1.0.0", "1.0.0", "1.0.0"), Set.of(), CLOCK.instant()));
        PlanVersionService plans = new PlanVersionService(planRepository, policy(), CLOCK);
        service = new RecommendationService(
                new InMemoryRecommendationRepository(), plans, CLOCK, UUID::randomUUID);
        RecommendationController controller = new RecommendationController(service, CLOCK);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new RecommendationExceptionHandler())
                .setCustomArgumentResolvers(authenticatedUserResolver())
                .build();
    }

    @Test
    void listAndIdempotentApplyUseTheUnifiedApiEnvelope() throws Exception {
        ProgressionRecommendation recommendation = service.save(
                USER, UUID.randomUUID(), "GOBLET_SQUAT", UUID.randomUUID(),
                RecommendationLifecycleTest.increaseDecision(), "{\"schemaVersion\":\"1.0.0\"}");

        mvc.perform(get("/api/v1/progression-recommendations").param("status", "PENDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(recommendation.id().toString()))
                .andExpect(jsonPath("$.data[0].reasonCode")
                        .value("ALL_SETS_AT_MAX_WITH_ACCEPTABLE_RIR"));

        mvc.perform(post("/api/v1/progression-recommendations/{id}/apply", recommendation.id())
                        .header("Idempotency-Key", "recommendation-apply-once")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPLIED"))
                .andExpect(jsonPath("$.data.recommendedWeightKg").value(42.5))
                .andExpect(jsonPath("$.data.changeSummary.previousWeightKg").value(40))
                .andExpect(jsonPath("$.data.changeSummary.appliedWeightKg").value(42.5))
                .andExpect(jsonPath("$.data.appliedPlanVersionId").isNotEmpty());

        mvc.perform(post("/api/v1/progression-recommendations/{id}/apply", recommendation.id())
                        .header("Idempotency-Key", "recommendation-apply-once")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPLIED"));

        mvc.perform(post("/api/v1/progression-recommendations/{id}/apply", recommendation.id())
                        .header("Idempotency-Key", "recommendation-apply-once")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":1,\"acceptedWeight\":{\"value\":45,\"unit\":\"KG\"}}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void recommendationListingUsesAnOpaqueStableCursorWithoutDuplicates() throws Exception {
        Set<String> expected = new HashSet<>();
        for (int index = 0; index < 3; index++) {
            expected.add(service.save(
                    USER, UUID.randomUUID(), "GOBLET_SQUAT", UUID.randomUUID(),
                    RecommendationLifecycleTest.increaseDecision(),
                    "{\"schemaVersion\":\"1.0.0\",\"index\":" + index + "}").id().toString());
        }

        var firstResponse = mvc.perform(get("/api/v1/progression-recommendations").param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Has-More", "true"))
                .andExpect(header().exists("X-Next-Cursor"))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andReturn().getResponse();
        String cursor = firstResponse.getHeader("X-Next-Cursor");

        var secondResponse = mvc.perform(get("/api/v1/progression-recommendations")
                        .param("limit", "2")
                        .param("cursor", cursor))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Has-More", "false"))
                .andExpect(header().doesNotExist("X-Next-Cursor"))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andReturn().getResponse();

        ObjectMapper json = new ObjectMapper();
        Set<String> actual = new HashSet<>();
        for (JsonNode item : json.readTree(firstResponse.getContentAsString()).path("data")) {
            actual.add(item.path("id").asText());
        }
        for (JsonNode item : json.readTree(secondResponse.getContentAsString()).path("data")) {
            actual.add(item.path("id").asText());
        }
        assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void recommendationListingRejectsMalformedOrOversizedPageInputs() throws Exception {
        mvc.perform(get("/api/v1/progression-recommendations").param("cursor", "not-a-cursor"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        mvc.perform(get("/api/v1/progression-recommendations").param("limit", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    private static PlanVersionService.PlanPolicy policy() {
        return new PlanVersionService.PlanPolicy() {
            @Override
            public PlanVersionService.CandidatePlan candidate(AuthenticatedUserId user, String candidateId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<PlanVersionService.ValidationIssue> validate(
                    AuthenticatedUserId user, PlanDraft plan, RuleReference reference) {
                return List.of();
            }
        };
    }

    private static HandlerMethodArgumentResolver authenticatedUserResolver() {
        return new HandlerMethodArgumentResolver() {
            @Override
            public boolean supportsParameter(MethodParameter parameter) {
                return parameter.getParameterType() == AuthenticatedUserId.class;
            }

            @Override
            public Object resolveArgument(
                    MethodParameter parameter,
                    ModelAndViewContainer container,
                    NativeWebRequest request,
                    org.springframework.web.bind.support.WebDataBinderFactory binderFactory) {
                return USER;
            }
        };
    }
}
