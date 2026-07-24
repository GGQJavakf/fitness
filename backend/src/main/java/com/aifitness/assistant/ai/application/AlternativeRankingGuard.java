package com.aifitness.assistant.ai.application;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

public final class AlternativeRankingGuard {

    public List<String> validatedOrder(List<String> legalCandidateCodes, List<String> providerOrder) {
        List<String> legal = List.copyOf(Objects.requireNonNull(legalCandidateCodes));
        List<String> proposed = List.copyOf(Objects.requireNonNull(providerOrder));
        if (legal.size() < 2 || legal.size() > 4 || proposed.size() != legal.size()
                || new HashSet<>(legal).size() != legal.size()
                || new HashSet<>(proposed).size() != proposed.size()
                || !new HashSet<>(legal).equals(new HashSet<>(proposed))) {
            return legal;
        }
        return proposed;
    }
}
