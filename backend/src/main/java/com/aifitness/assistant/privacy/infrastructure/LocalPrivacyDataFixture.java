package com.aifitness.assistant.privacy.infrastructure;

import com.aifitness.assistant.privacy.application.PrivacyDataPort;
import com.aifitness.assistant.privacy.application.PrivacyDeletionWorker;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Isolated local/test data only; it never reads production or shared business repositories. */
final class LocalPrivacyDataFixture implements PrivacyDataPort, PrivacyDeletionWorker.DataLifecyclePort {

    private final Map<UUID, EnumMap<Category, Integer>> records = new ConcurrentHashMap<>();
    private final Set<CommandKey> executed = new HashSet<>();
    private final Set<UUID> accessRevoked = new HashSet<>();
    private final Set<UUID> retentionSeparated = new HashSet<>();

    @Override
    public synchronized List<ResourceSummary> summarize(UUID userId) {
        EnumMap<Category, Integer> userRecords = records.computeIfAbsent(userId, ignored -> defaults());
        return Arrays.stream(Category.values())
                .map(category -> new ResourceSummary(category, userRecords.get(category)))
                .toList();
    }

    @Override
    public synchronized void execute(PrivacyDeletionWorker.LifecycleCommand command) {
        CommandKey key = new CommandKey(command.requestId(), command.step());
        if (!executed.add(key)) {
            return;
        }
        switch (command.step()) {
            case REVOKE_ACCESS -> accessRevoked.add(command.userId());
            case ANONYMIZE_BUSINESS_DATA -> {
                EnumMap<Category, Integer> userRecords = records.computeIfAbsent(
                        command.userId(), ignored -> defaults());
                userRecords.replaceAll((category, count) -> 0);
            }
            case SEPARATE_REQUIRED_RETENTION -> retentionSeparated.add(command.userId());
        }
    }

    synchronized boolean accessRevoked(UUID userId) {
        return accessRevoked.contains(userId);
    }

    synchronized boolean retentionSeparated(UUID userId) {
        return retentionSeparated.contains(userId);
    }

    private static EnumMap<Category, Integer> defaults() {
        EnumMap<Category, Integer> result = new EnumMap<>(Category.class);
        result.put(Category.PROFILE, 1);
        result.put(Category.EQUIPMENT, 2);
        result.put(Category.PREFERENCES, 1);
        result.put(Category.PLANS, 1);
        result.put(Category.WORKOUTS, 0);
        return result;
    }

    private record CommandKey(UUID requestId, PrivacyDeletionWorker.LifecycleStep step) {}
}
