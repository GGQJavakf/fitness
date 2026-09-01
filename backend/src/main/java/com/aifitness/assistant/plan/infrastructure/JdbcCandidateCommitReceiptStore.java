package com.aifitness.assistant.plan.infrastructure;

import com.aifitness.assistant.plan.application.CandidateCommitReceiptStore;
import com.aifitness.assistant.plan.application.CandidateCommitService;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;

/** MySQL receipt adapter that never persists the reusable idempotency key or request payload. */
final class JdbcCandidateCommitReceiptStore implements CandidateCommitReceiptStore {
    private final JdbcOperations jdbc;

    JdbcCandidateCommitReceiptStore(DataSource dataSource) {
        this(new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource must not be null")));
    }

    JdbcCandidateCommitReceiptStore(JdbcOperations jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
    }

    @Override
    public Optional<Receipt> find(UUID userId, String keyDigest, String payloadDigest) {
        return read(userId, keyDigest, false)
                .flatMap(row -> receiptFor(row, payloadDigest));
    }

    @Override
    public Claim claim(UUID userId, String keyDigest, String payloadDigest) {
        jdbc.update("""
                INSERT IGNORE INTO plan_candidate_commit_receipt
                    (user_id, key_digest, payload_digest, created_at)
                VALUES (?, ?, ?, UTC_TIMESTAMP(6))
                """, bytes(userId), digestBytes(keyDigest), digestBytes(payloadDigest));
        Stored row = read(userId, keyDigest, true).orElseThrow();
        return new Claim(receiptFor(row, payloadDigest));
    }

    @Override
    public void complete(
            UUID userId,
            String keyDigest,
            String payloadDigest,
            UUID planId,
            int versionNumber,
            UUID versionId) {
        int updated = jdbc.update("""
                UPDATE plan_candidate_commit_receipt
                SET plan_id = ?, version_no = ?, version_id = ?, completed_at = UTC_TIMESTAMP(6)
                WHERE user_id = ? AND key_digest = ? AND payload_digest = ?
                  AND plan_id IS NULL AND version_no IS NULL AND version_id IS NULL
                """, bytes(planId), versionNumber, bytes(versionId), bytes(userId),
                digestBytes(keyDigest), digestBytes(payloadDigest));
        if (updated != 1) {
            throw new IllegalStateException("candidate commit was not claimed");
        }
    }

    private Optional<Stored> read(UUID userId, String keyDigest, boolean lock) {
        InMemoryCandidateCommitReceiptStore.requireDigest(keyDigest);
        List<Stored> rows = jdbc.query("""
                SELECT payload_digest, plan_id, version_no, version_id
                FROM plan_candidate_commit_receipt
                WHERE user_id = ? AND key_digest = ?
                """ + (lock ? " FOR UPDATE" : ""), (row, ignored) -> new Stored(
                row.getBytes("payload_digest"),
                optionalUuid(row.getBytes("plan_id")),
                row.getObject("version_no", Integer.class),
                optionalUuid(row.getBytes("version_id"))),
                bytes(userId), digestBytes(keyDigest));
        return rows.stream().findFirst();
    }

    private static Optional<Receipt> receiptFor(Stored row, String payloadDigest) {
        byte[] expected = digestBytes(payloadDigest);
        if (!Arrays.equals(row.payloadDigest(), expected)) {
            throw new CandidateCommitService.IdempotencyKeyReusedException();
        }
        boolean complete = row.planId().isPresent() && row.versionNumber() != null && row.versionId().isPresent();
        boolean empty = row.planId().isEmpty() && row.versionNumber() == null && row.versionId().isEmpty();
        if (!complete && !empty) {
            throw new IllegalStateException("candidate commit receipt is partially completed");
        }
        return complete
                ? Optional.of(new Receipt(
                        row.planId().orElseThrow(), row.versionNumber(), row.versionId().orElseThrow()))
                : Optional.empty();
    }

    private static byte[] digestBytes(String digest) {
        InMemoryCandidateCommitReceiptStore.requireDigest(digest);
        return java.util.HexFormat.of().parseHex(digest);
    }

    private static byte[] bytes(UUID value) {
        return ByteBuffer.allocate(16)
                .putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits())
                .array();
    }

    private static Optional<UUID> optionalUuid(byte[] value) {
        if (value == null) {
            return Optional.empty();
        }
        ByteBuffer buffer = ByteBuffer.wrap(value);
        return Optional.of(new UUID(buffer.getLong(), buffer.getLong()));
    }

    private record Stored(
            byte[] payloadDigest,
            Optional<UUID> planId,
            Integer versionNumber,
            Optional<UUID> versionId) {}
}
