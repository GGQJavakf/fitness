package com.aifitness.assistant.plan.infrastructure;

import com.aifitness.assistant.plan.application.PlanRepository;
import com.aifitness.assistant.plan.application.PlanVersionService;
import com.aifitness.assistant.plan.domain.TrainingPlan;
import com.aifitness.assistant.plan.domain.TrainingPlanVersion;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class InMemoryPlanRepository implements PlanRepository {
    private final Map<UUID, TrainingPlan> plans = new HashMap<>();
    private final Map<UUID, UUID> activePlanByUser = new HashMap<>();

    @Override
    public synchronized Optional<TrainingPlan> findActiveByUser(UUID userId) {
        return Optional.ofNullable(activePlanByUser.get(userId)).map(plans::get);
    }

    @Override
    public synchronized Optional<TrainingPlan> findByIdAndUser(UUID planId, UUID userId) {
        return Optional.ofNullable(plans.get(planId)).filter(plan -> plan.userId().equals(userId));
    }

    @Override
    public synchronized TrainingPlan create(UUID userId, TrainingPlanVersion firstVersion) {
        UUID activePlanId = activePlanByUser.get(userId);
        if (activePlanId != null) {
            if (activePlanId.equals(firstVersion.planId())) {
                return plans.get(activePlanId);
            }
            throw new PlanVersionService.ActivePlanAlreadyExistsException();
        }
        TrainingPlan plan = new TrainingPlan(
                firstVersion.planId(), userId, java.util.List.of(firstVersion), firstVersion.versionNumber());
        plans.put(plan.id(), plan);
        activePlanByUser.put(userId, plan.id());
        return plan;
    }

    @Override
    public synchronized TrainingPlan append(
            UUID userId, UUID planId, int expectedVersion, TrainingPlanVersion version) {
        TrainingPlan current = findByIdAndUser(planId, userId)
                .orElseThrow(PlanVersionService.PlanNotFoundException::new);
        if (current.activeVersionNumber() != expectedVersion) {
            throw new PlanVersionService.VersionConflictException(current.activeVersionNumber());
        }
        TrainingPlan updated = current.append(version);
        plans.put(planId, updated);
        return updated;
    }

    synchronized Snapshot snapshot() {
        return new Snapshot(plans, activePlanByUser);
    }

    synchronized void restore(Snapshot snapshot) {
        plans.clear();
        plans.putAll(snapshot.plans());
        activePlanByUser.clear();
        activePlanByUser.putAll(snapshot.activePlanByUser());
    }

    record Snapshot(Map<UUID, TrainingPlan> plans, Map<UUID, UUID> activePlanByUser) {
        Snapshot {
            plans = new LinkedHashMap<>(plans);
            activePlanByUser = new LinkedHashMap<>(activePlanByUser);
        }
    }
}
