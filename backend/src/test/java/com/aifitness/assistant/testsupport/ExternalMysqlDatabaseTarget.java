package com.aifitness.assistant.testsupport;

import java.net.URI;
import java.util.regex.Pattern;

/** Restricts external verification to the legacy test schema or an unmistakably disposable schema. */
public final class ExternalMysqlDatabaseTarget {
    private static final String LEGACY_DATABASE = "fitness_m0";
    private static final Pattern DISPOSABLE_DATABASE =
            Pattern.compile("fitness_verify_[a-z0-9]{12,32}");

    private ExternalMysqlDatabaseTarget() {}

    public static String requireAllowed(URI uri, String errorMessage) {
        String path = uri == null ? null : uri.getPath();
        if (path == null || path.length() < 2 || path.indexOf('/', 1) >= 0) {
            throw new IllegalArgumentException(errorMessage);
        }
        String database = path.substring(1);
        if (!LEGACY_DATABASE.equals(database) && !DISPOSABLE_DATABASE.matcher(database).matches()) {
            throw new IllegalArgumentException(errorMessage);
        }
        return database;
    }
}
