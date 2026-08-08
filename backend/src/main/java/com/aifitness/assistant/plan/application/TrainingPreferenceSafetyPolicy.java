package com.aifitness.assistant.plan.application;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

public final class TrainingPreferenceSafetyPolicy {

    private static final List<String> FORBIDDEN_COMPACT_MARKERS = List.of(
            "忽略之前", "忽略以上", "忽略系统", "系统提示词", "开发者消息",
            "越过系统", "绕过系统", "ignoreprevious", "ignoreallprevious",
            "ignoresystem", "systemprompt", "developermessage", "apikey",
            "accesstoken", "jailbreak", "医疗", "诊断", "治疗", "康复",
            "处方", "疾病", "医生", "手术", "术后", "高血压", "低血压",
            "心脏病", "糖尿病", "哮喘", "关节炎", "半月板", "韧带",
            "骨折", "椎间盘", "疝气", "孕期", "怀孕", "疼痛", "受伤",
            "损伤", "扭伤", "拉伤", "撕裂", "炎症", "眩晕", "头晕",
            "胸闷", "麻木", "肿胀", "膝伤", "肩伤", "腰伤", "背伤", "旧伤",
            "伤后", "伤病", "身体不适", "感到不适", "出现不适", "持续不适",
            "酸痛", "刺痛", "medical", "diagnosis",
            "treatment", "therapy", "rehab", "doctor", "surgery", "injury",
            "injured", "pain", "hypertension", "diabetes", "fracture", "ligament",
            "meniscus");
    private static final String ENGLISH_NUMBER_WORD =
            "(?:zero|one|two|three|four|five|six|seven|eight|nine|ten|eleven"
                    + "|twelve|thirteen|fourteen|fifteen|sixteen|seventeen"
                    + "|eighteen|nineteen|twenty|thirty|forty|fifty|sixty"
                    + "|seventy|eighty|ninety|hundred|thousand)";
    private static final String ENGLISH_DETECTION_GAP =
            "[\\p{Z}\\p{P}\\p{S}\\p{M}\\p{C}_]+";
    private static final Pattern DETECTION_SEPARATOR =
            Pattern.compile("[\\p{Z}\\p{P}\\p{S}\\p{M}\\p{C}_]+");
    private static final Pattern ABSOLUTE_WEIGHT = Pattern.compile(
            "(?:\\d+(?:\\.\\d+)?|[零〇一二两三四五六七八九十百点半]+)"
                    + "(?:kilograms?|kilos?|pounds?|kgs?|lbs?|公斤|千克|市斤|斤|磅)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern ENGLISH_ABSOLUTE_WEIGHT = Pattern.compile(
            "(?:^|[^A-Za-z])" + ENGLISH_NUMBER_WORD
                    + "(?:" + ENGLISH_DETECTION_GAP
                    + "(?:and" + ENGLISH_DETECTION_GAP + ")?"
                    + ENGLISH_NUMBER_WORD + ")*"
                    + ENGLISH_DETECTION_GAP
                    + "(?:kilograms?|kilos?|pounds?|kgs?|lbs?)(?![A-Za-z])",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private TrainingPreferenceSafetyPolicy() {}

    public static Optional<String> normalize(String value) {
        return normalize(value, 300);
    }

    public static Optional<String> normalize(String value, int maximumLength) {
        if (value == null || value.isBlank()) {
            return Optional.of("");
        }
        if (maximumLength < 1
                || value.length() > maximumLength
                || containsUnsafeCodePoint(value)) {
            return Optional.empty();
        }
        String trimmed = value.trim();
        String normalized = Normalizer.normalize(trimmed, Normalizer.Form.NFKC);
        if (trimmed.length() > maximumLength
                || normalized.length() > maximumLength
                || containsUnsafeCodePoint(normalized)) {
            return Optional.empty();
        }
        String compact = compactForDetection(normalized);
        return FORBIDDEN_COMPACT_MARKERS.stream().anyMatch(compact::contains)
                ? Optional.empty()
                : Optional.of(trimmed);
    }

    public static boolean containsAbsoluteWeight(String value) {
        if (value == null) {
            return false;
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        return ABSOLUTE_WEIGHT.matcher(compactForDetection(normalized)).find()
                || ENGLISH_ABSOLUTE_WEIGHT.matcher(normalized).find();
    }

    private static String compactForDetection(String value) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        return DETECTION_SEPARATOR.matcher(normalized).replaceAll("");
    }

    private static boolean containsUnsafeCodePoint(String value) {
        return value.codePoints().anyMatch(codePoint ->
                Character.isISOControl(codePoint)
                        || isDefaultIgnorableCodePoint(codePoint)
                        || switch (Character.getType(codePoint)) {
                            case Character.FORMAT,
                                    Character.SURROGATE,
                                    Character.PRIVATE_USE,
                                    Character.UNASSIGNED -> true;
                            default -> false;
                        });
    }

    private static boolean isDefaultIgnorableCodePoint(int codePoint) {
        return codePoint == 0x00AD
                || codePoint == 0x034F
                || codePoint == 0x061C
                || inRange(codePoint, 0x115F, 0x1160)
                || inRange(codePoint, 0x17B4, 0x17B5)
                || inRange(codePoint, 0x180B, 0x180F)
                || inRange(codePoint, 0x200B, 0x200F)
                || inRange(codePoint, 0x202A, 0x202E)
                || inRange(codePoint, 0x2060, 0x206F)
                || codePoint == 0x3164
                || inRange(codePoint, 0xFE00, 0xFE0F)
                || codePoint == 0xFEFF
                || codePoint == 0xFFA0
                || inRange(codePoint, 0xFFF0, 0xFFF8)
                || inRange(codePoint, 0x1BCA0, 0x1BCA3)
                || inRange(codePoint, 0x1D173, 0x1D17A)
                || inRange(codePoint, 0xE0000, 0xE0FFF);
    }

    private static boolean inRange(int codePoint, int minimum, int maximum) {
        return codePoint >= minimum && codePoint <= maximum;
    }
}
