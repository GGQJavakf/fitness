package com.aifitness.assistant.privacy.infrastructure;

import com.aifitness.assistant.identity.application.UserAccessRevocation;
import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.privacy.application.PrivacyDataPort;
import com.aifitness.assistant.privacy.application.PrivacyDeletionWorker;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Isolated local/test data only; it never reads production or shared business repositories. */
final class LocalPrivacyDataFixture implements PrivacyDataPort, PrivacyDeletionWorker.DataLifecyclePort {

    private final UserAccessRevocation accessRevocation;

    private final Map<UUID, EnumMap<Category, List<ExportRecord>>> records = new ConcurrentHashMap<>();
    private final Set<CommandKey> executed = new HashSet<>();
    private final Set<UUID> accessRevoked = new HashSet<>();
    private final Set<UUID> retentionSeparated = new HashSet<>();

    LocalPrivacyDataFixture(UserAccessRevocation accessRevocation) {
        this.accessRevocation = java.util.Objects.requireNonNull(accessRevocation);
    }

    @Override
    public synchronized List<ResourceExport> export(UUID userId) {
        EnumMap<Category, List<ExportRecord>> userRecords = records.computeIfAbsent(
                userId, LocalPrivacyDataFixture::defaults);
        return java.util.Arrays.stream(Category.values())
                .map(category -> new ResourceExport(category, userRecords.get(category)))
                .toList();
    }

    @Override
    public synchronized void execute(PrivacyDeletionWorker.LifecycleCommand command) {
        CommandKey key = new CommandKey(command.requestId(), command.step());
        if (executed.contains(key)) {
            return;
        }
        switch (command.step()) {
            case REVOKE_ACCESS -> {
                accessRevocation.revokeAllSessionsAndBlockLogin(
                        new AuthenticatedUserId(command.userId()), command.requestId());
                accessRevoked.add(command.userId());
            }
            case ANONYMIZE_BUSINESS_DATA -> {
                EnumMap<Category, List<ExportRecord>> userRecords = records.computeIfAbsent(
                        command.userId(), LocalPrivacyDataFixture::defaults);
                userRecords.replaceAll((category, values) -> List.of());
            }
            case SEPARATE_REQUIRED_RETENTION -> retentionSeparated.add(command.userId());
        }
        executed.add(key);
    }

    synchronized boolean accessRevoked(UUID userId) {
        return accessRevoked.contains(userId);
    }

    synchronized boolean retentionSeparated(UUID userId) {
        return retentionSeparated.contains(userId);
    }

    private static EnumMap<Category, List<ExportRecord>> defaults(UUID ignoredUserId) {
        EnumMap<Category, List<ExportRecord>> result = new EnumMap<>(Category.class);
        result.put(Category.PROFILE, List.of(new ExportRecord(opaqueId(), "成年用户训练档案")));
        result.put(Category.EQUIPMENT, List.of(
                new ExportRecord(opaqueId(), "哑铃"),
                new ExportRecord(opaqueId(), "训练凳")));
        result.put(Category.PREFERENCES, List.of(new ExportRecord(opaqueId(), "动作偏好")));
        result.put(Category.PLANS, List.of(new ExportRecord(opaqueId(), "当前训练计划")));
        result.put(Category.WORKOUTS, List.of());
        return result;
    }

    private static String opaqueId() {
        return UUID.randomUUID().toString();
    }

    private record CommandKey(UUID requestId, PrivacyDeletionWorker.LifecycleStep step) {}
}
