package com.aifitness.assistant.identity.infrastructure;

import com.aifitness.assistant.identity.application.SubjectProtector;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class Sha256SubjectProtector implements SubjectProtector {

    @Override
    public byte[] protect(String providerSubject) {
        if (providerSubject == null || providerSubject.isBlank()) {
            throw new IllegalArgumentException("provider subject must not be blank");
        }
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(providerSubject.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("required subject protection algorithm is unavailable");
        }
    }
}
