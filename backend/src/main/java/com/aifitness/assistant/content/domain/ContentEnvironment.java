package com.aifitness.assistant.content.domain;

public enum ContentEnvironment {
    LOCAL("local"),
    TEST("test"),
    STAGING_EXPERIENCE("staging-experience"),
    PUBLIC("public");

    private final String externalName;

    ContentEnvironment(String externalName) {
        this.externalName = externalName;
    }

    public String externalName() {
        return externalName;
    }

    public static ContentEnvironment fromExternalName(String value) {
        for (ContentEnvironment environment : values()) {
            if (environment.externalName.equals(value)) {
                return environment;
            }
        }
        throw new IllegalArgumentException("unsupported content environment");
    }
}
