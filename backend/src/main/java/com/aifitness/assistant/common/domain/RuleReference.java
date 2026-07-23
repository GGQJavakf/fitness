package com.aifitness.assistant.common.domain;

public record RuleReference(String ruleVersion, String templateVersion, String contentVersion) {

    public RuleReference {
        ruleVersion = requireText(ruleVersion, "ruleVersion");
        templateVersion = requireText(templateVersion, "templateVersion");
        contentVersion = requireText(contentVersion, "contentVersion");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
