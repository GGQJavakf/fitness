package com.aifitness.assistant.identity.infrastructure;

import com.aifitness.assistant.identity.application.IdentityRepository;
import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.identity.domain.UserIdentity;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** MySQL identity adapter. Provider subjects must already be irreversibly protected. */
public final class JdbcIdentityRepository implements IdentityRepository {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public JdbcIdentityRepository(DataSource dataSource) {
        DataSource required = Objects.requireNonNull(dataSource, "dataSource must not be null");
        this.jdbc = new JdbcTemplate(required);
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(required));
    }

    @Override
    public AuthenticatedUserId findOrCreate(
            UserIdentity.Provider provider, byte[] protectedSubject, Instant now) {
        Objects.requireNonNull(provider, "provider must not be null");
        byte[] subject = requireProtectedSubject(protectedSubject);
        Objects.requireNonNull(now, "now must not be null");

        Optional<AuthenticatedUserId> existing = findExisting(provider, subject);
        if (existing.isPresent()) {
            return existing.get();
        }

        try {
            return Objects.requireNonNull(transactions.execute(status -> {
                Optional<AuthenticatedUserId> locked = find(provider, subject, true);
                if (locked.isPresent()) {
                    return locked.get();
                }
                UUID userId = UUID.randomUUID();
                jdbc.update("""
                        INSERT INTO user_account (id, status, created_at)
                        VALUES (?, 'ACTIVE', ?)
                        """, JdbcBinaryUuid.bytes(userId), Timestamp.from(now));
                jdbc.update("""
                        INSERT INTO user_identity
                            (id, user_id, provider, subject_cipher, status, created_at)
                        VALUES (?, ?, ?, ?, 'ACTIVE', ?)
                        """, JdbcBinaryUuid.bytes(UUID.randomUUID()), JdbcBinaryUuid.bytes(userId),
                        provider.name(), subject, Timestamp.from(now));
                return new AuthenticatedUserId(userId);
            }));
        } catch (DuplicateKeyException race) {
            return findExisting(provider, subject).orElseThrow(() -> race);
        }
    }

    @Override
    public Optional<AuthenticatedUserId> findExisting(
            UserIdentity.Provider provider, byte[] protectedSubject) {
        Objects.requireNonNull(provider, "provider must not be null");
        return find(provider, requireProtectedSubject(protectedSubject), false);
    }

    private Optional<AuthenticatedUserId> find(
            UserIdentity.Provider provider, byte[] subject, boolean lock) {
        String sql = "SELECT user_id FROM user_identity WHERE provider = ? AND subject_cipher = ?"
                + (lock ? " FOR UPDATE" : "");
        List<AuthenticatedUserId> users = jdbc.query(sql,
                (row, ignored) -> new AuthenticatedUserId(JdbcBinaryUuid.uuid(row.getBytes(1))),
                provider.name(), subject);
        return users.stream().findFirst();
    }

    private static byte[] requireProtectedSubject(byte[] protectedSubject) {
        byte[] subject = Objects.requireNonNull(
                protectedSubject, "protectedSubject must not be null").clone();
        if (subject.length == 0 || subject.length > 512) {
            throw new IllegalArgumentException("protectedSubject length is invalid");
        }
        return subject;
    }
}
