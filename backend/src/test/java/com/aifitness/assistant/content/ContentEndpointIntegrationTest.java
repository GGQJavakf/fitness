package com.aifitness.assistant.content;

import com.aifitness.assistant.FitnessAssistantApplication;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.startsWith;

@SpringBootTest(classes = FitnessAssistantApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ContentEndpointIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void exposesOnlyAuthenticatedUsersEligibleVersionedCatalog() throws Exception {
        mvc.perform(get("/api/v1/exercises")).andExpect(status().isUnauthorized());
        String token = login();
        configureEquipment(token);

        mvc.perform(get("/api/v1/exercises")
                        .header("Authorization", "Bearer " + token)
                        .queryParam("equipmentType", "dumbbell"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contentVersion").value("1.8.0"))
                .andExpect(jsonPath("$.data.items.length()").value(21))
                .andExpect(jsonPath("$.data.items[*].code", containsInAnyOrder(
                        "DUMBBELL_BENCH_PRESS",
                        "DUMBBELL_BICEPS_CURL",
                        "DUMBBELL_DEADLIFT",
                        "DUMBBELL_FLOOR_PRESS",
                        "DUMBBELL_FRONT_SQUAT",
                        "DUMBBELL_HAMMER_CURL",
                        "DUMBBELL_LATERAL_RAISE",
                        "DUMBBELL_LYING_TRICEPS_EXTENSION",
                        "DUMBBELL_OVERHEAD_PRESS",
                        "DUMBBELL_OVERHEAD_TRICEPS_EXTENSION",
                        "DUMBBELL_REVERSE_FLY",
                        "DUMBBELL_REVERSE_LUNGE",
                        "DUMBBELL_ROMANIAN_DEADLIFT",
                        "DUMBBELL_SHRUG",
                        "GOBLET_SQUAT",
                        "INCLINE_DUMBBELL_BENCH_PRESS_30",
                        "INCLINE_DUMBBELL_FLY",
                        "ONE_ARM_DUMBBELL_ROW",
                        "SEATED_DUMBBELL_PRESS",
                        "SINGLE_ARM_DUMBBELL_LATERAL_RAISE",
                        "SINGLE_ARM_DUMBBELL_PRESS")))
                .andExpect(jsonPath("$.data.items[0].plainLanguage").isNotEmpty())
                .andExpect(jsonPath("$.data.items[0].difficulty").value("BEGINNER"))
                .andExpect(jsonPath("$.data.items[*].image.fallbackRef",
                        everyItem(startsWith("asset://exercise-guides/"))))
                .andExpect(jsonPath("$.data.items[*].image.fallbackRef",
                        everyItem(endsWith(".jpg"))));

        mvc.perform(get("/api/v1/exercises/GOBLET_SQUAT")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").isNotEmpty())
                .andExpect(jsonPath("$.data.code").value("GOBLET_SQUAT"))
                .andExpect(jsonPath("$.data.image.primaryRef")
                        .value("asset://exercise-guides/goblet-squat-01-setup.jpg"))
                .andExpect(jsonPath("$.data.image.fallbackRef")
                        .value("asset://exercise-guides/goblet-squat-01-setup.jpg"))
                .andExpect(jsonPath("$.data.contentVersion").value("1.8.0"));

        mvc.perform(get("/api/v1/exercises/GOBLET_SQUAT/replacements")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sourceCode").value("GOBLET_SQUAT"))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].movementPattern").value("SQUAT"))
                .andExpect(jsonPath("$.data.items[0].difficulty").value("BEGINNER"));

        mvc.perform(get("/api/v1/exercises/LAT_PULLDOWN/replacements")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].code").value("NEUTRAL_GRIP_PULLDOWN"));

        mvc.perform(get("/api/v1/exercises/UNKNOWN")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));

        mvc.perform(get("/api/v1/plan-templates")
                        .header("Authorization", "Bearer " + token)
                        .queryParam("weeklyFrequency", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.templateVersion").value("1.8.0"))
                .andExpect(jsonPath("$.data.contentVersion").value("1.8.0"))
                .andExpect(jsonPath("$.data.items.length()").value(3))
                .andExpect(jsonPath("$.data.items[*].code")
                        .value(containsInAnyOrder(
                                "BODYWEIGHT_3_DAY_V1",
                                "FULL_BODY_3_DAY_V1",
                                "PUSH_PULL_LEGS_3_DAY_V1")));
    }

    @Test
    void rejectsOutOfRangeTemplateFrequency() throws Exception {
        String token = login();
        mvc.perform(get("/api/v1/plan-templates")
                        .header("Authorization", "Bearer " + token)
                        .queryParam("weeklyFrequency", "7"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    private void configureEquipment(String token) throws Exception {
        StringBuilder items = new StringBuilder();
        for (String type : new String[] {"DUMBBELL", "BENCH", "CABLE", "MACHINE"}) {
            if (!items.isEmpty()) {
                items.append(',');
            }
            items.append("""
                    {"clientEquipmentKey":"%s","equipmentType":"%s",
                     "minIncrement":{"value":1,"unit":"KG"},
                     "availableLevels":[{"value":1,"unit":"KG"}]}
                    """.formatted(UUID.randomUUID(), type));
        }
        mvc.perform(put("/api/v1/profile/equipment")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[" + items + "],\"expectedVersion\":0}"))
                .andExpect(status().isOk());
    }

    private String login() throws Exception {
        String response = mvc.perform(post("/api/v1/auth/wechat/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"content-test-" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode body = objectMapper.readTree(response);
        return body.at("/data/accessToken").asText();
    }
}
