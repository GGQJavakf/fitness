package com.aifitness.assistant.profile;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

@SpringBootTest(classes = FitnessAssistantApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProfileEndpointIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void authenticatedUserCanCreateReadAndVersionProfileEquipmentAndPreferences() throws Exception {
        String token = login();

        mvc.perform(put("/api/v1/profile")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"experience":"BEGINNER","goal":"GENERAL_FITNESS","weeklyFrequency":3,
                                 "sessionMinutes":60,"location":"GYM","expectedVersion":0}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(1));

        mvc.perform(get("/api/v1/profile").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.weeklyFrequency").value(3));

        mvc.perform(put("/api/v1/profile")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"experience":"BEGINNER","goal":"GENERAL_FITNESS","weeklyFrequency":4,
                                 "sessionMinutes":60,"location":"GYM","expectedVersion":0}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"))
                .andExpect(jsonPath("$.error.details.currentVersion").value(1));

        UUID clientEquipmentKey = UUID.randomUUID();
        mvc.perform(put("/api/v1/profile/equipment")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"clientEquipmentKey":"%s","equipmentType":"DUMBBELL",
                                  "minIncrement":{"value":2.5,"unit":"KG"},
                                  "availableLevels":[{"value":5,"unit":"KG"},{"value":7.5,"unit":"KG"}]}],
                                 "expectedVersion":0}
                                """.formatted(clientEquipmentKey)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].clientEquipmentKey").value(clientEquipmentKey.toString()))
                .andExpect(jsonPath("$.data.items[0].minIncrement.equipmentProfileId").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].minIncrement.unit").value("KG"));

        UUID exerciseId = UUID.randomUUID();
        mvc.perform(put("/api/v1/profile/preferences")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"exerciseId":"%s","preferenceType":"EXCLUDED"}],"expectedVersion":0}
                                """.formatted(exerciseId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].exerciseId").value(exerciseId.toString()));
    }

    @Test
    void equipmentRejectsNonKgAndUnauthenticatedProfileIsDenied() throws Exception {
        mvc.perform(get("/api/v1/profile")).andExpect(status().isUnauthorized());
        String token = login();
        UUID clientEquipmentKey = UUID.randomUUID();

        mvc.perform(put("/api/v1/profile/equipment")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"clientEquipmentKey":"%s","equipmentType":"DUMBBELL",
                                  "minIncrement":{"value":5,"unit":"LB"},
                                  "availableLevels":[{"value":5,"unit":"LB"}]}],"expectedVersion":0}
                                """.formatted(clientEquipmentKey)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        mvc.perform(put("/api/v1/profile/equipment")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"clientEquipmentKey":"%s","equipmentType":"DUMBBELL",
                                  "minIncrement":{"value":2.5,"unit":"KG","equipmentProfileId":"server-id"},
                                  "availableLevels":[{"value":5,"unit":"KG"}]}],"expectedVersion":0}
                                """.formatted(clientEquipmentKey)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        mvc.perform(put("/api/v1/profile/equipment")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[null],\"expectedVersion\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        mvc.perform(put("/api/v1/profile/equipment")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"clientEquipmentKey":"%s","equipmentType":"DUMBBELL",
                                  "minIncrement":{"value":2.5,"unit":"KG"},
                                  "availableLevels":[null]}],"expectedVersion":0}
                                """.formatted(clientEquipmentKey)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        mvc.perform(put("/api/v1/profile/preferences")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[null],\"expectedVersion\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        mvc.perform(put("/api/v1/profile/equipment")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    private String login() throws Exception {
        String response = mvc.perform(post("/api/v1/auth/wechat/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"profile-test-code\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode body = objectMapper.readTree(response);
        return body.at("/data/accessToken").asText();
    }
}
