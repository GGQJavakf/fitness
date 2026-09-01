package com.aifitness.assistant.plan.infrastructure;

import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.plan.application.PlanCandidateService;
import com.aifitness.assistant.plan.application.PlanCandidateStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

/** MySQL-backed candidate store shared by every application instance. */
public final class JdbcPlanCandidateStore implements PlanCandidateStore {
    private static final String LOCK_USER = "SELECT id FROM user_account WHERE id = ? FOR UPDATE";

    private final JdbcOperations jdbc;
    private final ObjectMapper json;
    private final int maximumCandidatesPerUser;
    private final TransactionOperations transactions;

    public JdbcPlanCandidateStore(
            DataSource dataSource,
            ObjectMapper json,
            int maximumCandidatesPerUser) {
        DataSource required = Objects.requireNonNull(dataSource, "dataSource must not be null");
        this.jdbc = new JdbcTemplate(required);
        this.json = stableCandidateJson(json);
        this.maximumCandidatesPerUser = requirePositiveCapacity(maximumCandidatesPerUser);
        TransactionTemplate transactionTemplate =
                new TransactionTemplate(new DataSourceTransactionManager(required));
        transactionTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        this.transactions = transactionTemplate;
    }

    JdbcPlanCandidateStore(
            JdbcOperations jdbc,
            ObjectMapper json,
            int maximumCandidatesPerUser) {
        this(jdbc, json, maximumCandidatesPerUser, TransactionOperations.withoutTransaction());
    }

    JdbcPlanCandidateStore(
            JdbcOperations jdbc,
            ObjectMapper json,
            int maximumCandidatesPerUser,
            TransactionOperations transactions) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
        this.json = stableCandidateJson(json);
        this.maximumCandidatesPerUser = requirePositiveCapacity(maximumCandidatesPerUser);
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
    }

    @Override
    public void save(
            AuthenticatedUserId user,
            PlanCandidateService.CandidateEnvelope candidate) {
        Objects.requireNonNull(user, "authenticated user must not be null");
        Objects.requireNonNull(candidate, "candidate must not be null");
        UUID candidateId = requireCandidateId(candidate.candidateId());
        var normalizedExpiry = candidate.expiresAt().truncatedTo(ChronoUnit.MICROS);
        PlanCandidateService.CandidateEnvelope persisted = new PlanCandidateService.CandidateEnvelope(
                candidate.candidateId(),
                candidate.generationSource(),
                candidate.plan(),
                candidate.ruleReference(),
                candidate.explanationStatus(),
                candidate.explanation(),
                normalizedExpiry);
        byte[] userId = bytes(user.value());
        String candidateJson = write(persisted);

        transactions.executeWithoutResult(ignored -> {
            lockUser(userId);
            jdbc.update("""
                    INSERT INTO plan_candidate
                        (user_id, candidate_id, candidate_json, expires_at, created_at, updated_at)
                    VALUES (?, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                    AS incoming
                    ON DUPLICATE KEY UPDATE
                        candidate_json = incoming.candidate_json,
                        expires_at = incoming.expires_at,
                        updated_at = UTC_TIMESTAMP(6)
                    """, userId, bytes(candidateId), candidateJson, Timestamp.from(normalizedExpiry));
            trimToCapacity(userId);
        });
    }

    @Override
    public Optional<PlanCandidateService.CandidateEnvelope> find(
            AuthenticatedUserId user, String candidateId) {
        Objects.requireNonNull(user, "authenticated user must not be null");
        Optional<UUID> parsedId = parseCandidateId(candidateId);
        if (parsedId.isEmpty()) {
            return Optional.empty();
        }
        List<PlanCandidateService.CandidateEnvelope> candidates = jdbc.query("""
                SELECT candidate_json
                FROM plan_candidate
                WHERE user_id = ? AND candidate_id = ?
                  AND expires_at > UTC_TIMESTAMP(6)
                """, (row, ignored) -> read(row.getString("candidate_json")),
                bytes(user.value()), bytes(parsedId.orElseThrow()));
        return candidates.stream().findFirst().map(candidate -> {
            if (!candidate.candidateId().equals(candidateId)) {
                throw new IllegalStateException("persisted plan candidate identity is invalid");
            }
            return candidate;
        });
    }

    @Scheduled(fixedDelayString = "${fitness.plan.candidate-cleanup-delay-ms:60000}")
    public void purgeExpired() {
        jdbc.update("DELETE FROM plan_candidate WHERE expires_at <= UTC_TIMESTAMP(6)");
    }

    private void lockUser(byte[] userId) {
        if (jdbc.query(LOCK_USER, (row, ignored) -> row.getBytes(1), userId).isEmpty()) {
            throw new IllegalStateException("authenticated user disappeared while saving plan candidate");
        }
    }

    private void trimToCapacity(byte[] userId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM plan_candidate WHERE user_id = ?",
                Long.class,
                userId);
        long overflow = Math.max(0L, Objects.requireNonNullElse(count, 0L) - maximumCandidatesPerUser);
        if (overflow == 0L) {
            return;
        }
        jdbc.update("""
                DELETE FROM plan_candidate
                WHERE user_id = ?
                ORDER BY expires_at ASC, updated_at ASC, candidate_id ASC
                LIMIT ?
                """, userId, Math.toIntExact(Math.min(overflow, Integer.MAX_VALUE)));
    }

    private static int requirePositiveCapacity(int maximumCandidatesPerUser) {
        if (maximumCandidatesPerUser < 1) {
            throw new IllegalArgumentException("maximumCandidatesPerUser must be positive");
        }
        return maximumCandidatesPerUser;
    }

    private static ObjectMapper stableCandidateJson(ObjectMapper source) {
        return Objects.requireNonNull(source, "objectMapper must not be null")
                .copy()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    private String write(PlanCandidateService.CandidateEnvelope candidate) {
        try {
            return json.writeValueAsString(candidate);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("plan candidate cannot be serialized", exception);
        }
    }

    private PlanCandidateService.CandidateEnvelope read(String value) {
        try {
            return json.readValue(value, PlanCandidateService.CandidateEnvelope.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("persisted plan candidate is invalid", exception);
        }
    }

    private static UUID requireCandidateId(String candidateId) {
        return parseCandidateId(candidateId)
                .orElseThrow(() -> new IllegalArgumentException("candidateId must be a UUID"));
    }

    private static Optional<UUID> parseCandidateId(String candidateId) {
        if (candidateId == null || candidateId.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(candidateId));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    private static byte[] bytes(UUID value) {
        return ByteBuffer.allocate(16)
                .putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits())
                .array();
    }
}
