package com.aifitness.assistant.privacy.infrastructure;

import com.aifitness.assistant.privacy.application.PrivacyRequestService;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Append-only privacy audit adapter; lifecycle steps use deterministic ids for retry safety. */
public final class JdbcPrivacyAudit implements PrivacyRequestService.AuditPort {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public JdbcPrivacyAudit(DataSource dataSource, Clock clock) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource must not be null"));
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public void record(UUID userId, String action, UUID requestId) {
        insert(UUID.randomUUID(), userId, action, requestId, false);
    }

    @Override
    public void recordStepOnce(UUID userId, String action, UUID requestId) {
        UUID eventId = UUID.nameUUIDFromBytes(
                ("ai-fitness-privacy-audit:" + userId + ":" + action + ":" + requestId)
                        .getBytes(StandardCharsets.UTF_8));
        insert(eventId, userId, action, requestId, true);
    }

    private void insert(UUID id, UUID userId, String action, UUID requestId, boolean ignoreDuplicate) {
        String verb = ignoreDuplicate ? "INSERT IGNORE" : "INSERT";
        jdbc.update(verb + """
                 INTO domain_audit
                    (id, user_id, action, entity_type, entity_id, metadata_json, created_at)
                VALUES (?, ?, ?, 'PRIVACY_REQUEST', ?, JSON_OBJECT(), ?)
                """, bytes(id), bytes(userId), action,
                bytes(requestId == null ? userId : requestId), Timestamp.from(clock.instant()));
    }

    private static byte[] bytes(UUID value) {
        return ByteBuffer.allocate(16)
                .putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits()).array();
    }
}
