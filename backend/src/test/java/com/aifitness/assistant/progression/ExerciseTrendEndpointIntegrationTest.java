package com.aifitness.assistant.progression;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.progression.api.ExerciseTrendController;
import com.aifitness.assistant.progression.application.ExerciseTrendQuery;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

class ExerciseTrendEndpointIntegrationTest {
    private static final AuthenticatedUserId USER = new AuthenticatedUserId(UUID.fromString(
            "40000000-0000-0000-0000-000000000001"));

    @Test
    void returnsOnlyTheEffectivePointsProvidedByTheServerQuery() throws Exception {
        ExerciseTrendQuery query = (user, code) -> new ExerciseTrendQuery.Trend(
                code, "KG", List.of(new ExerciseTrendQuery.Point(
                        UUID.fromString("41000000-0000-0000-0000-000000000001"),
                        Instant.parse("2026-07-24T09:00:00Z"), new BigDecimal("42.5"), 24, 3)));
        ExerciseTrendController controller = new ExerciseTrendController(
                query, Clock.fixed(Instant.parse("2026-07-24T12:00:00Z"), ZoneOffset.UTC));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(authenticatedUserResolver())
                .build();

        mvc.perform(get("/api/v1/progress/exercises/GOBLET_SQUAT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.exerciseCode").value("GOBLET_SQUAT"))
                .andExpect(jsonPath("$.data.unit").value("KG"))
                .andExpect(jsonPath("$.data.points[0].topWeightKg").value(42.5))
                .andExpect(jsonPath("$.data.points[0].totalReps").value(24))
                .andExpect(jsonPath("$.data.points[0].workSetCount").value(3));
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
