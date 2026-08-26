package com.aifitness.assistant.plan.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.aifitness.assistant.content.domain.ContentEnvironment;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ClasspathSystemPlanPresetCatalogLoaderTest {

    @Test
    void exposesAiValidatedPresetsOnlyInApprovedNonPublicEnvironments() {
        ObjectMapper objectMapper = new ObjectMapper();

        assertThat(ClasspathSystemPlanPresetCatalogLoader.load(
                objectMapper, ContentEnvironment.LOCAL).presets()).hasSize(1);
        assertThat(ClasspathSystemPlanPresetCatalogLoader.load(
                objectMapper, ContentEnvironment.TEST).presets()).hasSize(1);
        assertThat(ClasspathSystemPlanPresetCatalogLoader.load(
                objectMapper, ContentEnvironment.STAGING_EXPERIENCE).presets()).hasSize(1);
        assertThat(ClasspathSystemPlanPresetCatalogLoader.load(
                objectMapper, ContentEnvironment.PUBLIC).presets()).isEmpty();
    }
}
