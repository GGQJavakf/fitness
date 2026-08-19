package com.aifitness.assistant.progression.infrastructure;

import com.aifitness.assistant.progression.application.RecommendationRepository;
import com.aifitness.assistant.progression.domain.ProgressionDecision;
import com.aifitness.assistant.progression.domain.ProgressionRecommendation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** MySQL adapter preserving immutable calculation evidence and atomic lifecycle outbox events. */
public final class JdbcRecommendationRepository implements RecommendationRepository {
    private static final TypeReference<Map<String, Object>> JSON_MAP = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;

    public JdbcRecommendationRepository(DataSource dataSource, ObjectMapper json) {
        DataSource required = Objects.requireNonNull(dataSource, "dataSource must not be null");
        this.jdbc = new JdbcTemplate(required);
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(required));
        this.json = Objects.requireNonNull(json, "json must not be null");
    }

    @Override
    public SaveResult saveIfAbsent(ProgressionRecommendation recommendation) {
        Optional<ProgressionRecommendation> existing = findBySource(
                recommendation.userId(), recommendation.sourceSessionId(), recommendation.exerciseId(),
                recommendation.algorithmVersion());
        if (existing.isPresent()) return new SaveResult(existing.orElseThrow(), false);
        Map<String, Object> current = prescription(recommendation.exerciseCode(), recommendation.currentPrescription());
        Map<String, Object> suggested = prescription(
                recommendation.exerciseCode(), recommendation.recommendedPrescription());
        recommendation.roundingEvidence().ifPresent(evidence -> {
            suggested.put("rawRecommendedWeight", evidence.rawRecommendedWeight());
            suggested.put("roundedWeight", evidence.roundedWeight());
            suggested.put("roundingRule", evidence.roundingRule());
            suggested.put("availableEquipmentSteps", evidence.availableEquipmentSteps());
        });
        jdbc.update("""
                INSERT INTO progression_recommendation
                    (id, user_id, exercise_id, source_session_id, decision, current_json, recommended_json,
                     reason_code, input_snapshot_json, algorithm_version, user_decision, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?)
                ON DUPLICATE KEY UPDATE id = id
                """, bytes(recommendation.id()), bytes(recommendation.userId()), bytes(recommendation.exerciseId()),
                bytes(recommendation.sourceSessionId()), recommendation.decision().name(), writeJson(current),
                writeJson(suggested), recommendation.reasonCode(), validJson(recommendation.inputSnapshotJson()),
                recommendation.algorithmVersion(), Timestamp.from(recommendation.createdAt()));
        ProgressionRecommendation saved = findBySource(
                recommendation.userId(), recommendation.sourceSessionId(), recommendation.exerciseId(),
                recommendation.algorithmVersion()).orElseThrow();
        return new SaveResult(saved, saved.id().equals(recommendation.id()));
    }

    @Override
    public Optional<ProgressionRecommendation> findBySource(
            UUID userId, UUID sourceSessionId, UUID exerciseId, String algorithmVersion) {
        return query("WHERE user_id = ? AND source_session_id = ? AND exercise_id = ? AND algorithm_version = ?",
                bytes(userId), bytes(sourceSessionId), bytes(exerciseId), algorithmVersion).stream().findFirst();
    }

    @Override
    public Optional<ProgressionRecommendation> findByIdAndUser(UUID id, UUID userId) {
        return query("WHERE id = ? AND user_id = ?", bytes(id), bytes(userId)).stream().findFirst();
    }

    @Override
    public List<ProgressionRecommendation> listByUser(
            UUID userId, Optional<ProgressionRecommendation.Status> status) {
        if (status.isPresent()) {
            return query("WHERE user_id = ? AND user_decision = ? ORDER BY created_at DESC, id DESC",
                    bytes(userId), status.orElseThrow().name());
        }
        return query("WHERE user_id = ? ORDER BY created_at DESC, id DESC", bytes(userId));
    }

    @Override
    public Page pageByUser(
            UUID userId,
            Optional<ProgressionRecommendation.Status> status,
            Optional<Cursor> cursor,
            int limit) {
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
        List<Object> arguments = new java.util.ArrayList<>();
        StringBuilder clause = new StringBuilder("WHERE user_id = ?");
        arguments.add(bytes(userId));
        status.ifPresent(value -> {
            clause.append(" AND user_decision = ?");
            arguments.add(value.name());
        });
        cursor.ifPresent(value -> {
            clause.append(" AND (created_at < ? OR (created_at = ? AND id < ?))");
            arguments.add(Timestamp.from(value.createdAt()));
            arguments.add(Timestamp.from(value.createdAt()));
            arguments.add(bytes(value.id()));
        });
        clause.append(" ORDER BY created_at DESC, id DESC LIMIT ?");
        arguments.add(limit + 1);
        List<ProgressionRecommendation> matches = query(clause.toString(), arguments.toArray());
        boolean hasMore = matches.size() > limit;
        List<ProgressionRecommendation> items = hasMore ? matches.subList(0, limit) : matches;
        Optional<Cursor> next = hasMore
                ? Optional.of(new Cursor(items.getLast().createdAt(), items.getLast().id()))
                : Optional.empty();
        return new Page(items, hasMore, next);
    }

    @Override
    public ProgressionRecommendation updatePending(
            ProgressionRecommendation recommendation, Optional<String> decisionIdempotencyKey) {
        int updated;
        if (recommendation.status() == ProgressionRecommendation.Status.DISMISSED) {
            updated = jdbc.update("""
                    UPDATE progression_recommendation
                    SET user_decision = 'DISMISSED', dismissal_reason = ?, decided_at = UTC_TIMESTAMP(6)
                    WHERE id = ? AND user_id = ? AND user_decision = 'PENDING'
                    """, recommendation.dismissalReason().orElseThrow(), bytes(recommendation.id()),
                    bytes(recommendation.userId()));
        } else {
            updated = jdbc.update("""
                    UPDATE progression_recommendation
                    SET user_decision = ?, accepted_weight = ?, decision_idempotency_key = ?,
                        decided_at = UTC_TIMESTAMP(6), applied_plan_id = ?, applied_plan_version_id = ?
                    WHERE id = ? AND user_id = ? AND user_decision = 'PENDING'
                    """, recommendation.status().name(), recommendation.acceptedWeightKg().orElseThrow(),
                    decisionIdempotencyKey.orElseThrow(), bytes(recommendation.appliedPlanId().orElseThrow()),
                    bytes(recommendation.appliedPlanVersionId().orElseThrow()), bytes(recommendation.id()),
                    bytes(recommendation.userId()));
        }
        if (updated != 1) {
            throw new IllegalStateException("recommendation is already decided");
        }
        return findByIdAndUser(recommendation.id(), recommendation.userId()).orElseThrow();
    }

    @Override
    public DecisionClaim claimDecision(
            UUID userId, String operation, String idempotencyKey, String payloadFingerprint) {
        jdbc.update("""
                INSERT INTO progression_decision_idempotency
                    (id, user_id, operation, idempotency_key, payload_fingerprint, created_at)
                VALUES (?, ?, ?, ?, ?, UTC_TIMESTAMP(6))
                ON DUPLICATE KEY UPDATE id = id
                """, bytes(UUID.randomUUID()), bytes(userId), operation, idempotencyKey, payloadFingerprint);
        DecisionRow row = jdbc.query("""
                SELECT payload_fingerprint, result_recommendation_id
                FROM progression_decision_idempotency
                WHERE user_id = ? AND operation = ? AND idempotency_key = ?
                FOR UPDATE
                """, (result, ignored) -> new DecisionRow(
                        result.getString("payload_fingerprint"),
                        optionalUuid(result, "result_recommendation_id")),
                bytes(userId), operation, idempotencyKey).stream().findFirst().orElseThrow();
        if (!row.payloadFingerprint().equals(payloadFingerprint)) {
            throw new com.aifitness.assistant.progression.application.RecommendationService
                    .IdempotencyKeyReusedException();
        }
        return new DecisionClaim(row.recommendationId().flatMap(id -> findByIdAndUser(id, userId)));
    }

    @Override
    public void completeDecision(
            UUID userId, String operation, String idempotencyKey, String payloadFingerprint, UUID recommendationId) {
        int updated = jdbc.update("""
                UPDATE progression_decision_idempotency
                SET result_recommendation_id = ?, completed_at = UTC_TIMESTAMP(6)
                WHERE user_id = ? AND operation = ? AND idempotency_key = ?
                  AND payload_fingerprint = ? AND result_recommendation_id IS NULL
                """, bytes(recommendationId), bytes(userId), operation, idempotencyKey, payloadFingerprint);
        if (updated != 1) throw new IllegalStateException("progression decision was not claimed");
    }

    @Override
    public void appendOutbox(UUID aggregateId, String eventType) {
        jdbc.update("""
                INSERT INTO outbox_event (id, event_type, aggregate_id, payload_json, status, next_attempt_at)
                VALUES (?, ?, ?, JSON_OBJECT('recommendationId', ?), 'PENDING', UTC_TIMESTAMP(6))
                """, bytes(UUID.randomUUID()), eventType, bytes(aggregateId), aggregateId.toString());
    }

    @Override
    public <T> T inTransaction(Supplier<T> operation) {
        return Objects.requireNonNull(transactions.execute(ignored -> operation.get()));
    }

    private List<ProgressionRecommendation> query(String clause, Object... arguments) {
        return jdbc.query("""
                SELECT id, user_id, exercise_id, source_session_id, decision, current_json, recommended_json,
                       reason_code, input_snapshot_json, algorithm_version, user_decision, accepted_weight,
                       applied_plan_id, applied_plan_version_id, dismissal_reason, created_at
                FROM progression_recommendation
                """ + clause, (row, ignored) -> read(row), arguments);
    }

    private ProgressionRecommendation read(ResultSet row) throws SQLException {
        Map<String, Object> current = readJson(row.getString("current_json"));
        Map<String, Object> suggested = readJson(row.getString("recommended_json"));
        return new ProgressionRecommendation(
                uuid(row.getBytes("id")), uuid(row.getBytes("user_id")), uuid(row.getBytes("exercise_id")),
                text(current, "exerciseCode"), uuid(row.getBytes("source_session_id")),
                ProgressionDecision.Decision.valueOf(row.getString("decision")), prescription(current),
                prescription(suggested), row.getString("reason_code"), row.getString("input_snapshot_json"),
                row.getString("algorithm_version"), roundingEvidence(suggested),
                ProgressionRecommendation.Status.valueOf(row.getString("user_decision")),
                Optional.ofNullable(row.getBigDecimal("accepted_weight")), optionalUuid(row, "applied_plan_id"),
                optionalUuid(row, "applied_plan_version_id"), Optional.ofNullable(row.getString("dismissal_reason")),
                row.getTimestamp("created_at").toInstant());
    }

    private static Map<String, Object> prescription(
            String exerciseCode, ProgressionDecision.Prescription prescription) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("exerciseCode", exerciseCode);
        value.put("weightKg", prescription.weightKg());
        value.put("repMin", prescription.repMin());
        value.put("repMax", prescription.repMax());
        return value;
    }

    private static ProgressionDecision.Prescription prescription(Map<String, Object> value) {
        return new ProgressionDecision.Prescription(
                new BigDecimal(String.valueOf(value.get("weightKg"))), number(value, "repMin"),
                number(value, "repMax"));
    }

    private static Optional<ProgressionRecommendation.RoundingEvidence> roundingEvidence(
            Map<String, Object> value) {
        if (!value.containsKey("roundingRule")) return Optional.empty();
        Object steps = value.get("availableEquipmentSteps");
        if (!(steps instanceof List<?> values)) {
            throw new IllegalStateException("recommendation equipment steps are missing");
        }
        return Optional.of(new ProgressionRecommendation.RoundingEvidence(
                new BigDecimal(String.valueOf(value.get("rawRecommendedWeight"))),
                new BigDecimal(String.valueOf(value.get("roundedWeight"))),
                text(value, "roundingRule"),
                values.stream().map(item -> new BigDecimal(String.valueOf(item))).toList()));
    }

    private String validJson(String value) {
        readJson(value);
        return value;
    }

    private String writeJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("recommendation evidence cannot be serialized", exception);
        }
    }

    private Map<String, Object> readJson(String value) {
        try {
            return json.readValue(value, JSON_MAP);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("recommendation evidence is invalid", exception);
        }
    }

    private static String text(Map<String, Object> value, String field) {
        Object found = value.get(field);
        if (found == null) throw new IllegalStateException("recommendation field is missing: " + field);
        return String.valueOf(found);
    }

    private static int number(Map<String, Object> value, String field) {
        Object found = value.get(field);
        if (!(found instanceof Number number)) throw new IllegalStateException("recommendation number is missing");
        return number.intValue();
    }

    private static Optional<UUID> optionalUuid(ResultSet row, String field) throws SQLException {
        byte[] value = row.getBytes(field);
        return value == null ? Optional.empty() : Optional.of(uuid(value));
    }

    private static byte[] bytes(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits())
                .array();
    }

    private static UUID uuid(byte[] value) {
        ByteBuffer buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    private record DecisionRow(String payloadFingerprint, Optional<UUID> recommendationId) {}

}
