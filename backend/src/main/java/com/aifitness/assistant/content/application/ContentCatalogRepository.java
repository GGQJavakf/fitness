package com.aifitness.assistant.content.application;

import com.aifitness.assistant.content.domain.ExerciseCatalog;
import com.aifitness.assistant.content.domain.PlanTemplateCatalog;

public interface ContentCatalogRepository {

    ExerciseCatalog exercises();

    PlanTemplateCatalog templates();
}
