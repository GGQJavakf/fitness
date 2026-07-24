package com.aifitness.assistant.ai.application;

import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class AlternativeRankingService {
    private final LegalAlternativeProvider alternatives;
    private final AiOrchestrator orchestrator;
    private final AlternativeRankingGuard guard;
    private final ObjectMapper json;

    public AlternativeRankingService(
            LegalAlternativeProvider alternatives,
            AiOrchestrator orchestrator,
            AlternativeRankingGuard guard,
            ObjectMapper json) {
        this.alternatives = Objects.requireNonNull(alternatives);
        this.orchestrator = Objects.requireNonNull(orchestrator);
        this.guard = Objects.requireNonNull(guard);
        this.json = Objects.requireNonNull(json);
    }

    public Ranking rank(AuthenticatedUserId user, String sourceExerciseCode) {
        List<Candidate> legal = List.copyOf(alternatives.find(user, sourceExerciseCode));
        if (legal.size() < 2 || legal.size() > 4) return new Ranking(Status.DEGRADED, legal, "RULE_ORDER_ONLY");
        List<String> legalCodes = legal.stream().map(Candidate::exerciseCode).toList();
        List<Map<String, Object>> candidateFacts = legal.stream().map(candidate -> {
            Map<String, Object> facts = new LinkedHashMap<>();
            facts.put("exerciseCode", candidate.exerciseCode());
            facts.put("movementPattern", candidate.movementPattern());
            facts.put("difficulty", candidate.difficulty());
            facts.put("equipment", candidate.equipment());
            facts.put("primaryMuscles", candidate.primaryMuscles());
            return Map.copyOf(facts);
        }).toList();
        AiOrchestrator.Result result = orchestrator.generate(
                AiProvider.Purpose.ALTERNATIVE_RANKING,
                Map.of("candidateSummaries", candidateFacts),
                "{\"orderedCandidateCodes\":[]}");
        if (result.status() == AiOrchestrator.Status.DEGRADED) {
            return new Ranking(Status.DEGRADED, legal, result.validationStatus());
        }
        List<String> proposed = parseOrder(result.content());
        List<String> validated = proposed == null ? legalCodes : guard.validatedOrder(legalCodes, proposed);
        if (proposed == null || validated.equals(legalCodes) && !proposed.equals(legalCodes)) {
            return new Ranking(Status.DEGRADED, legal, "INVALID_RANKING");
        }
        Map<String, Candidate> byCode = legal.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                Candidate::exerciseCode, candidate -> candidate));
        return new Ranking(Status.READY, validated.stream().map(byCode::get).toList(), "VALID");
    }

    private List<String> parseOrder(String raw) {
        try {
            JsonNode root = json.readTree(raw);
            if (root == null || !root.isObject() || root.size() != 1 || !root.path("orderedCandidateCodes").isArray()) {
                return null;
            }
            java.util.ArrayList<String> result = new java.util.ArrayList<>();
            for (JsonNode item : root.path("orderedCandidateCodes")) {
                if (!item.isTextual()) return null;
                result.add(item.textValue());
            }
            return List.copyOf(result);
        } catch (Exception exception) {
            return null;
        }
    }

    public interface LegalAlternativeProvider {
        List<Candidate> find(AuthenticatedUserId user, String sourceExerciseCode);
    }

    public record Candidate(
            String exerciseCode,
            String movementPattern,
            String difficulty,
            List<String> equipment,
            List<String> primaryMuscles) {
        public Candidate {
            exerciseCode = Objects.requireNonNull(exerciseCode);
            movementPattern = Objects.requireNonNull(movementPattern);
            difficulty = Objects.requireNonNull(difficulty);
            equipment = List.copyOf(equipment);
            primaryMuscles = List.copyOf(primaryMuscles);
        }
    }

    public enum Status { READY, DEGRADED }
    public record Ranking(Status status, List<Candidate> candidates, String validationStatus) {
        public Ranking { candidates = List.copyOf(candidates); }
    }
}
