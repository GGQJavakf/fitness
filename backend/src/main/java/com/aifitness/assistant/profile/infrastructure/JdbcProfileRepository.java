package com.aifitness.assistant.profile.infrastructure;

import com.aifitness.assistant.profile.application.ProfileRepository;
import com.aifitness.assistant.profile.application.ProfileService;
import com.aifitness.assistant.profile.domain.EquipmentProfile;
import com.aifitness.assistant.profile.domain.PreferenceProfile;
import com.aifitness.assistant.profile.domain.UserProfile;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** MySQL profile adapter with independent optimistic versions for each editable resource. */
public final class JdbcProfileRepository implements ProfileRepository {

    private static final TypeReference<List<BigDecimal>> DECIMAL_LIST = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;

    public JdbcProfileRepository(DataSource dataSource, ObjectMapper json) {
        DataSource required = Objects.requireNonNull(dataSource, "dataSource must not be null");
        this.jdbc = new JdbcTemplate(required);
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(required));
        this.json = Objects.requireNonNull(json, "json must not be null");
    }

    @Override
    public Optional<UserProfile> findProfile(UUID userId) {
        List<UserProfile> profiles = jdbc.query("""
                SELECT experience, goal, weekly_frequency, session_minutes, location, version
                FROM user_profile
                WHERE user_id = ?
                """, (row, ignored) -> new UserProfile(
                        userId,
                        new UserProfile.Details(
                                UserProfile.ExperienceLevel.valueOf(row.getString(1)),
                                UserProfile.FitnessGoal.valueOf(row.getString(2)),
                                row.getInt(3),
                                row.getInt(4),
                                UserProfile.TrainingLocation.valueOf(row.getString(5))),
                        row.getLong(6)), bytes(userId));
        return profiles.stream().findFirst();
    }

    @Override
    public UserProfile replaceProfile(
            UUID userId, long expectedVersion, UserProfile.Details details) {
        Objects.requireNonNull(details, "details must not be null");
        return Objects.requireNonNull(transactions.execute(status -> {
            requireActiveAccount(userId);
            long currentVersion = profileVersion(userId);
            requireVersion(expectedVersion, currentVersion);
            long nextVersion = currentVersion + 1;
            if (currentVersion == 0) {
                jdbc.update("""
                        INSERT INTO user_profile
                            (user_id, experience, goal, weekly_frequency,
                             session_minutes, location, version)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """, bytes(userId), details.experience().name(), details.goal().name(),
                        details.weeklyFrequency(), details.sessionMinutes(),
                        details.location().name(), nextVersion);
            } else {
                int updated = jdbc.update("""
                        UPDATE user_profile
                        SET experience = ?, goal = ?, weekly_frequency = ?,
                            session_minutes = ?, location = ?, version = ?
                        WHERE user_id = ? AND version = ?
                        """, details.experience().name(), details.goal().name(),
                        details.weeklyFrequency(), details.sessionMinutes(),
                        details.location().name(), nextVersion, bytes(userId), currentVersion);
                requireUpdated(updated, currentVersion);
            }
            return new UserProfile(userId, details, nextVersion);
        }));
    }

    @Override
    public Optional<EquipmentProfile> findEquipment(UUID userId) {
        long version = collectionVersion(userId, "equipment_version", false);
        if (version == 0) {
            return Optional.empty();
        }
        List<EquipmentProfile.Item> items = jdbc.query("""
                SELECT client_equipment_key, equipment_type, min_increment, unit,
                       available_levels_json
                FROM user_equipment
                WHERE user_id = ?
                ORDER BY item_order, id
                """, (row, ignored) -> new EquipmentProfile.Item(
                        uuid(row.getBytes(1)), row.getString(2), canonicalDecimal(row.getBigDecimal(3)),
                        row.getString(4), readLevels(row.getString(5))), bytes(userId));
        return Optional.of(new EquipmentProfile(userId, items, version));
    }

    @Override
    public EquipmentProfile replaceEquipment(
            UUID userId, long expectedVersion, List<EquipmentProfile.Item> items) {
        List<EquipmentProfile.Item> replacement = List.copyOf(items);
        return Objects.requireNonNull(transactions.execute(status -> {
            requireActiveAccount(userId);
            ensureCollectionVersion(userId);
            long currentVersion = collectionVersion(userId, "equipment_version", true);
            requireVersion(expectedVersion, currentVersion);
            jdbc.update("DELETE FROM user_equipment WHERE user_id = ?", bytes(userId));
            int order = 0;
            for (EquipmentProfile.Item item : replacement) {
                jdbc.update("""
                        INSERT INTO user_equipment
                            (id, user_id, client_equipment_key, item_order, equipment_type,
                             min_increment, unit, available_levels_json)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """, bytes(UUID.randomUUID()), bytes(userId),
                        bytes(item.clientEquipmentKey()), order++, item.equipmentType(),
                        item.minIncrement(), item.unit(), writeJson(item.availableLevels()));
            }
            long nextVersion = currentVersion + 1;
            int updated = jdbc.update("""
                    UPDATE user_profile_collection_version
                    SET equipment_version = ?, updated_at = ?
                    WHERE user_id = ? AND equipment_version = ?
                    """, nextVersion, Timestamp.from(Instant.now()), bytes(userId), currentVersion);
            requireUpdated(updated, currentVersion);
            return new EquipmentProfile(userId, replacement, nextVersion);
        }));
    }

    @Override
    public Optional<PreferenceProfile> findPreferences(UUID userId) {
        long version = collectionVersion(userId, "preference_version", false);
        if (version == 0) {
            return Optional.empty();
        }
        List<PreferenceProfile.Preference> preferences = jdbc.query("""
                SELECT exercise_id, preference_type
                FROM user_exercise_preference
                WHERE user_id = ?
                ORDER BY preference_order, exercise_id
                """, (row, ignored) -> new PreferenceProfile.Preference(
                        uuid(row.getBytes(1)),
                        PreferenceProfile.PreferenceType.valueOf(row.getString(2))),
                bytes(userId));
        return Optional.of(new PreferenceProfile(userId, preferences, version));
    }

    @Override
    public PreferenceProfile replacePreferences(
            UUID userId,
            long expectedVersion,
            List<PreferenceProfile.Preference> preferences) {
        List<PreferenceProfile.Preference> replacement = List.copyOf(preferences);
        return Objects.requireNonNull(transactions.execute(status -> {
            requireActiveAccount(userId);
            ensureCollectionVersion(userId);
            long currentVersion = collectionVersion(userId, "preference_version", true);
            requireVersion(expectedVersion, currentVersion);
            jdbc.update("DELETE FROM user_exercise_preference WHERE user_id = ?", bytes(userId));
            int order = 0;
            for (PreferenceProfile.Preference preference : replacement) {
                jdbc.update("""
                        INSERT INTO user_exercise_preference
                            (user_id, exercise_id, preference_type, preference_order)
                        VALUES (?, ?, ?, ?)
                        """, bytes(userId), bytes(preference.exerciseId()),
                        preference.preferenceType().name(), order++);
            }
            long nextVersion = currentVersion + 1;
            int updated = jdbc.update("""
                    UPDATE user_profile_collection_version
                    SET preference_version = ?, updated_at = ?
                    WHERE user_id = ? AND preference_version = ?
                    """, nextVersion, Timestamp.from(Instant.now()), bytes(userId), currentVersion);
            requireUpdated(updated, currentVersion);
            return new PreferenceProfile(userId, replacement, nextVersion);
        }));
    }

    private void requireActiveAccount(UUID userId) {
        List<byte[]> accounts = jdbc.query("""
                SELECT id FROM user_account
                WHERE id = ? AND status = 'ACTIVE'
                FOR UPDATE
                """, (row, ignored) -> row.getBytes(1), bytes(userId));
        if (accounts.isEmpty()) {
            throw new ProfileService.ProfileNotFoundException();
        }
    }

    private long profileVersion(UUID userId) {
        List<Long> versions = jdbc.query(
                "SELECT version FROM user_profile WHERE user_id = ? FOR UPDATE",
                (row, ignored) -> row.getLong(1), bytes(userId));
        return versions.stream().findFirst().orElse(0L);
    }

    private void ensureCollectionVersion(UUID userId) {
        jdbc.update("""
                INSERT IGNORE INTO user_profile_collection_version
                    (user_id, equipment_version, preference_version, updated_at)
                VALUES (?, 0, 0, ?)
                """, bytes(userId), Timestamp.from(Instant.now()));
    }

    private long collectionVersion(UUID userId, String column, boolean lock) {
        if (!column.equals("equipment_version") && !column.equals("preference_version")) {
            throw new IllegalArgumentException("unsupported collection version column");
        }
        String sql = "SELECT " + column
                + " FROM user_profile_collection_version WHERE user_id = ?"
                + (lock ? " FOR UPDATE" : "");
        List<Long> versions = jdbc.query(
                sql, (row, ignored) -> row.getLong(1), bytes(userId));
        return versions.stream().findFirst().orElse(0L);
    }

    private static void requireVersion(long expectedVersion, long currentVersion) {
        if (expectedVersion != currentVersion) {
            throw new ProfileService.VersionConflictException(currentVersion);
        }
    }

    private static void requireUpdated(int updatedRows, long currentVersion) {
        if (updatedRows != 1) {
            throw new ProfileService.VersionConflictException(currentVersion);
        }
    }

    private String writeJson(List<BigDecimal> levels) {
        try {
            return json.writeValueAsString(levels);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("equipment levels cannot be serialized", exception);
        }
    }

    private List<BigDecimal> readLevels(String value) {
        try {
            return json.readValue(value, DECIMAL_LIST);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored equipment levels are invalid", exception);
        }
    }

    private static byte[] bytes(UUID value) {
        return ByteBuffer.allocate(16)
                .putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits())
                .array();
    }

    private static UUID uuid(byte[] value) {
        ByteBuffer buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    private static BigDecimal canonicalDecimal(BigDecimal value) {
        return value.stripTrailingZeros();
    }
}
