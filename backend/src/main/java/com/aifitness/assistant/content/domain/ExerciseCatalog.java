package com.aifitness.assistant.content.domain;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public record ExerciseCatalog(ReleaseMetadata metadata, List<Exercise> exercises) {

    public ExerciseCatalog {
        Objects.requireNonNull(metadata, "metadata must not be null");
        exercises = List.copyOf(Objects.requireNonNull(exercises, "exercises must not be null"));
    }

    public record Exercise(
            String code,
            String name,
            String plainLanguage,
            String movementPattern,
            String difficulty,
            Set<String> equipment,
            Set<String> primaryMuscles,
            List<String> instructions,
            List<String> safetyCues,
            String rightsStatus,
            boolean active,
            Image image,
            List<Alternative> alternatives) {

        private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

        public Exercise {
            requiredCode(code, "exercise code");
            requiredText(name, "exercise name");
            requiredText(plainLanguage, "plain-language explanation");
            requiredCode(movementPattern, "movement pattern");
            requiredCode(difficulty, "difficulty");
            equipment = Set.copyOf(Objects.requireNonNull(equipment, "equipment must not be null"));
            primaryMuscles = Set.copyOf(
                    Objects.requireNonNull(primaryMuscles, "primary muscles must not be null"));
            instructions = requiredTexts(instructions, "instructions");
            safetyCues = requiredTexts(safetyCues, "safety cues");
            requiredText(rightsStatus, "rights status");
            Objects.requireNonNull(image, "image must not be null");
            alternatives = List.copyOf(Objects.requireNonNull(alternatives, "alternatives must not be null"));
        }

        public boolean hasValidRights() {
            return "ORIGINAL_SUMMARY".equals(rightsStatus);
        }

        public UUID stableId() {
            return UUID.nameUUIDFromBytes(("ai-fitness-exercise:" + code).getBytes(StandardCharsets.UTF_8));
        }

        public boolean supports(Set<String> availableEquipment) {
            return equipment.stream().allMatch(type -> "BODYWEIGHT".equals(type) || availableEquipment.contains(type));
        }

        public Exercise withAlternatives(List<Alternative> eligibleAlternatives) {
            return new Exercise(code, name, plainLanguage, movementPattern, difficulty, equipment, primaryMuscles,
                    instructions, safetyCues, rightsStatus, active, image, eligibleAlternatives);
        }

        private static List<String> requiredTexts(List<String> values, String field) {
            List<String> copy = List.copyOf(Objects.requireNonNull(values, field + " must not be null"));
            if (copy.isEmpty() || copy.stream().anyMatch(String::isBlank)) {
                throw new IllegalArgumentException(field + " must contain text");
            }
            return copy;
        }

        private static void requiredText(String value, String field) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(field + " is required");
            }
        }

        private static void requiredCode(String value, String field) {
            if (value == null || !CODE.matcher(value).matches()) {
                throw new IllegalArgumentException(field + " is invalid");
            }
        }
    }

    public record Image(String primaryRef, String fallbackRef) {
        public Image {
            if (primaryRef == null || primaryRef.isBlank() || fallbackRef == null || fallbackRef.isBlank()) {
                throw new IllegalArgumentException("image and fallback references are required");
            }
        }
    }

    public record Alternative(String exerciseCode, int rank, ReleaseStatus reviewStatus) {
        public Alternative {
            if (exerciseCode == null || exerciseCode.isBlank() || rank < 1) {
                throw new IllegalArgumentException("alternative reference is invalid");
            }
            Objects.requireNonNull(reviewStatus, "alternative review status must not be null");
        }
    }
}
