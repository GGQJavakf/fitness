package com.aifitness.assistant.ai.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AiOutputValidator {
    private static final Set<String> REQUIRED_FIELDS = Set.of(
            "summary", "highlights", "issues", "nextActions", "explanation", "safetyNotice");
    private static final Pattern NUMBER = Pattern.compile("(?<!\\d)[+-]?\\d{1,4}(?:\\.\\d{1,3})?(?!\\d)");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final List<String> UNSAFE_MARKERS = List.of(
            "忽略之前", "忽略以上", "系统提示词", "开发者消息", "ignore previous", "system prompt",
            "developer message", "api key", "access token", "wechatopenid", "openid");

    private final ObjectMapper json;
    private final DecisionConsistencyGuard decisionGuard;

    public AiOutputValidator(ObjectMapper json, DecisionConsistencyGuard decisionGuard) {
        this.json = Objects.requireNonNull(json, "json must not be null");
        this.decisionGuard = Objects.requireNonNull(decisionGuard, "decision guard must not be null");
    }

    public ValidationResult validate(String raw, AuthoritativeFacts facts) {
        Objects.requireNonNull(facts, "authoritative facts must not be null");
        AiSummary summary;
        try {
            summary = parse(raw);
        } catch (RuntimeException exception) {
            return ValidationResult.rejected(ValidationStatus.INVALID_SCHEMA);
        }
        String content = summary.allText();
        if (hasNumericConflict(content, facts.allowedNumbers())) {
            return ValidationResult.rejected(ValidationStatus.NUMERIC_CONFLICT);
        }
        if (isUnsafe(content)) {
            return ValidationResult.rejected(ValidationStatus.UNSAFE);
        }
        if (decisionGuard.conflicts(content, facts.decision())) {
            return ValidationResult.rejected(ValidationStatus.DECISION_CONFLICT);
        }
        return new ValidationResult(ValidationStatus.VALID, Optional.of(summary));
    }

    private AiSummary parse(String raw) {
        if (raw == null || raw.isBlank()) throw new InvalidOutputException();
        try {
            JsonNode root = json.readTree(raw);
            if (root == null || !root.isObject()) throw new InvalidOutputException();
            Set<String> fields = new HashSet<>();
            root.fieldNames().forEachRemaining(fields::add);
            if (!fields.equals(REQUIRED_FIELDS)) throw new InvalidOutputException();
            String summary = text(root, "summary", 300);
            List<String> highlights = textArray(root, "highlights");
            List<String> issues = textArray(root, "issues");
            List<String> nextActions = textArray(root, "nextActions");
            String explanation = text(root, "explanation", 500);
            JsonNode safetyNode = root.get("safetyNotice");
            String safetyNotice = safetyNode.isNull() ? null : boundedText(safetyNode, 240);
            AiSummary result = new AiSummary(
                    summary, highlights, issues, nextActions, explanation, Optional.ofNullable(safetyNotice));
            if (result.allText().length() > 1800) throw new InvalidOutputException();
            return result;
        } catch (InvalidOutputException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new InvalidOutputException();
        }
    }

    private static String text(JsonNode root, String field, int maxLength) {
        return boundedText(root.get(field), maxLength);
    }

    private static String boundedText(JsonNode node, int maxLength) {
        if (node == null || !node.isTextual()) throw new InvalidOutputException();
        String value = node.textValue().strip();
        if (value.isEmpty() || value.length() > maxLength) throw new InvalidOutputException();
        return value;
    }

    private static List<String> textArray(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || !node.isArray() || node.size() > 5) throw new InvalidOutputException();
        List<String> values = new ArrayList<>();
        node.forEach(item -> values.add(boundedText(item, 160)));
        return List.copyOf(values);
    }

    private static boolean hasNumericConflict(String content, Set<BigDecimal> allowedNumbers) {
        Set<BigDecimal> normalizedAllowed = new HashSet<>();
        allowedNumbers.forEach(number -> normalizedAllowed.add(normalize(number)));
        Matcher matcher = NUMBER.matcher(content);
        while (matcher.find()) {
            if (!normalizedAllowed.contains(normalize(new BigDecimal(matcher.group())))) return true;
        }
        return false;
    }

    private static BigDecimal normalize(BigDecimal value) {
        return Objects.requireNonNull(value, "allowed number must not be null").stripTrailingZeros();
    }

    private static boolean isUnsafe(String content) {
        String normalized = content.toLowerCase(Locale.ROOT);
        return PHONE.matcher(content).find() || UNSAFE_MARKERS.stream().anyMatch(normalized::contains);
    }

    public enum ValidationStatus {
        VALID,
        INVALID_SCHEMA,
        NUMERIC_CONFLICT,
        DECISION_CONFLICT,
        UNSAFE
    }

    public record AuthoritativeFacts(Set<BigDecimal> allowedNumbers, Optional<String> decision) {
        public AuthoritativeFacts {
            allowedNumbers = Set.copyOf(Objects.requireNonNull(allowedNumbers, "allowed numbers must not be null"));
            decision = decision == null ? Optional.empty() : decision.map(String::strip);
        }
    }

    public record ValidationResult(ValidationStatus status, Optional<AiSummary> summary) {
        private static ValidationResult rejected(ValidationStatus status) {
            return new ValidationResult(status, Optional.empty());
        }
    }

    public record AiSummary(
            String summary,
            List<String> highlights,
            List<String> issues,
            List<String> nextActions,
            String explanation,
            Optional<String> safetyNotice) {
        public AiSummary {
            highlights = List.copyOf(highlights);
            issues = List.copyOf(issues);
            nextActions = List.copyOf(nextActions);
            safetyNotice = safetyNotice == null ? Optional.empty() : safetyNotice;
        }

        private String allText() {
            return String.join("\n", List.of(
                    summary,
                    String.join("\n", highlights),
                    String.join("\n", issues),
                    String.join("\n", nextActions),
                    explanation,
                    safetyNotice.orElse("")));
        }
    }

    private static final class InvalidOutputException extends RuntimeException {}
}
