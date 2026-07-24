package com.aifitness.assistant.privacy.infrastructure;

import com.aifitness.assistant.privacy.application.PrivacyDataPort;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Owner-scoped, summary-only export reader for the experience database. */
public final class JdbcPrivacyDataReader implements PrivacyDataPort {

    private final JdbcTemplate jdbc;

    public JdbcPrivacyDataReader(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource must not be null"));
    }

    @Override
    public List<ResourceExport> export(UUID userId) {
        byte[] owner = bytes(userId);
        return List.of(
                new ResourceExport(Category.PROFILE, profile(userId, owner)),
                new ResourceExport(Category.EQUIPMENT, equipment(owner)),
                new ResourceExport(Category.PREFERENCES, preferences(owner)),
                new ResourceExport(Category.PLANS, plans(owner)),
                new ResourceExport(Category.WORKOUTS, workouts(owner)));
    }

    private List<ExportRecord> profile(UUID userId, byte[] owner) {
        return jdbc.query("SELECT experience, goal FROM user_profile WHERE user_id = ?",
                (row, ignored) -> new ExportRecord(
                        userId.toString(), "训练档案：" + row.getString(1) + "/" + row.getString(2)), owner);
    }

    private List<ExportRecord> equipment(byte[] owner) {
        return jdbc.query("""
                SELECT client_equipment_key, equipment_type
                FROM user_equipment WHERE user_id = ? ORDER BY item_order, id
                """, (row, ignored) -> new ExportRecord(
                        uuid(row.getBytes(1)).toString(), "器械：" + row.getString(2)), owner);
    }

    private List<ExportRecord> preferences(byte[] owner) {
        return jdbc.query("""
                SELECT exercise_id, preference_type
                FROM user_exercise_preference WHERE user_id = ? ORDER BY preference_order, exercise_id
                """, (row, ignored) -> new ExportRecord(
                        uuid(row.getBytes(1)).toString(), "动作偏好：" + row.getString(2)), owner);
    }

    private List<ExportRecord> plans(byte[] owner) {
        return jdbc.query("""
                SELECT id, status FROM training_plan WHERE user_id = ? ORDER BY created_at, id
                """, (row, ignored) -> new ExportRecord(
                        uuid(row.getBytes(1)).toString(), "训练计划：" + row.getString(2)), owner);
    }

    private List<ExportRecord> workouts(byte[] owner) {
        return jdbc.query("""
                SELECT id, status FROM workout_session WHERE user_id = ? ORDER BY started_at, id
                """, (row, ignored) -> new ExportRecord(
                        uuid(row.getBytes(1)).toString(), "训练记录：" + row.getString(2)), owner);
    }

    private static byte[] bytes(UUID value) {
        return ByteBuffer.allocate(16)
                .putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits()).array();
    }

    private static UUID uuid(byte[] value) {
        ByteBuffer bytes = ByteBuffer.wrap(value);
        return new UUID(bytes.getLong(), bytes.getLong());
    }
}
