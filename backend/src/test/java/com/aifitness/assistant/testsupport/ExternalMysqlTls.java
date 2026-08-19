package com.aifitness.assistant.testsupport;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mysql.cj.jdbc.MysqlDataSource;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Map;

public final class ExternalMysqlTls {
    private static final ObjectMapper JSON = new ObjectMapper();

    private ExternalMysqlTls() {}

    public static Configuration verifiedIdentity(String jdbcUrl) {
        return new Configuration(
                jdbcUrl + "?sslMode=VERIFY_IDENTITY",
                "VERIFY_IDENTITY",
                null,
                null,
                null);
    }

    public static Configuration encryptedWithoutIdentityVerification(String jdbcUrl) {
        return new Configuration(
                jdbcUrl + "?sslMode=REQUIRED",
                "REQUIRED",
                null,
                null,
                null);
    }

    public static Configuration pinnedCa(
            String jdbcUrl,
            String trustStorePath,
            String trustStoreType,
            String trustStorePassword) {
        if (trustStorePath == null || trustStorePath.isBlank()) {
            throw new IllegalArgumentException("Pinned-CA validation requires a trust store file");
        }
        if (trustStorePassword == null || trustStorePassword.isBlank()) {
            throw new IllegalArgumentException("Pinned-CA validation requires a trust store password");
        }

        Path path;
        try {
            path = Path.of(trustStorePath).toAbsolutePath().normalize();
        } catch (RuntimeException ignored) {
            throw new IllegalArgumentException("Pinned-CA trust store path is invalid");
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            throw new IllegalArgumentException("Pinned-CA trust store must be a regular non-symlink file");
        }

        String normalizedType = trustStoreType == null || trustStoreType.isBlank()
                ? "PKCS12"
                : trustStoreType.trim().toUpperCase(Locale.ROOT);
        if (!normalizedType.equals("PKCS12") && !normalizedType.equals("JKS")) {
            throw new IllegalArgumentException("Pinned-CA trust store type must be PKCS12 or JKS");
        }

        return new Configuration(
                jdbcUrl + "?sslMode=VERIFY_CA",
                "VERIFY_CA",
                path.toUri().toASCIIString(),
                normalizedType,
                trustStorePassword);
    }

    public record Configuration(
            String jdbcUrl,
            String sslMode,
            String trustStoreUrl,
            String trustStoreType,
            String trustStorePassword) {

        public boolean usesPinnedCa() {
            return trustStoreUrl != null;
        }

        public void configure(MysqlDataSource dataSource) {
            dataSource.setURL(jdbcUrl);
            if (!usesPinnedCa()) return;
            try {
                dataSource.setSslMode(sslMode);
                dataSource.setTrustCertificateKeyStoreUrl(trustStoreUrl);
                dataSource.setTrustCertificateKeyStoreType(trustStoreType);
                dataSource.setTrustCertificateKeyStorePassword(trustStorePassword);
                dataSource.setFallbackToSystemTrustStore(false);
            } catch (SQLException ignored) {
                throw new IllegalArgumentException("Unable to configure pinned-CA validation");
            }
        }

        public void applyToSpringEnvironment(Map<String, String> environment) {
            if (!usesPinnedCa()) return;
            Map<String, Object> properties = Map.of(
                    "spring", Map.of(
                            "datasource", Map.of(
                                    "hikari", Map.of(
                                            "data-source-properties", Map.of(
                                                    "sslMode", sslMode,
                                                    "trustCertificateKeyStoreUrl", trustStoreUrl,
                                                    "trustCertificateKeyStoreType", trustStoreType,
                                                    "trustCertificateKeyStorePassword", trustStorePassword,
                                                    "fallbackToSystemTrustStore", false)))));
            try {
                environment.put("SPRING_APPLICATION_JSON", JSON.writeValueAsString(properties));
            } catch (JsonProcessingException ignored) {
                throw new IllegalArgumentException("Unable to configure pinned-CA validation");
            }
        }

        public String redactedDescription() {
            return "ExternalMysqlTls[mode=" + sslMode + ", trustStore=redacted]";
        }

        @Override
        public String toString() {
            return redactedDescription();
        }
    }
}
