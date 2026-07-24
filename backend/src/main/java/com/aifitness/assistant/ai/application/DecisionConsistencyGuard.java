package com.aifitness.assistant.ai.application;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

public final class DecisionConsistencyGuard {
    private static final Set<String> DECISIONS = Set.of("INCREASE", "KEEP", "REDUCE", "REVIEW");

    public boolean conflicts(String content, Optional<String> authoritativeDecision) {
        if (authoritativeDecision == null || authoritativeDecision.isEmpty()) {
            return false;
        }
        String authority = authoritativeDecision.orElseThrow().toUpperCase(Locale.ROOT);
        if (!DECISIONS.contains(authority)) {
            throw new IllegalArgumentException("unsupported authoritative decision");
        }
        Set<String> mentioned = decisionsMentioned(content == null ? "" : content);
        return mentioned.stream().anyMatch(decision -> !decision.equals(authority));
    }

    private static Set<String> decisionsMentioned(String content) {
        String normalized = content.toUpperCase(Locale.ROOT);
        Set<String> result = new LinkedHashSet<>();
        DECISIONS.stream().filter(normalized::contains).forEach(result::add);
        if (content.contains("加重") || content.contains("增加重量")) result.add("INCREASE");
        if (content.contains("保持重量") || content.contains("维持重量")) result.add("KEEP");
        if (content.contains("降重") || content.contains("降低重量")) result.add("REDUCE");
        if (content.contains("复核") || content.contains("人工确认")) result.add("REVIEW");
        return result;
    }
}
