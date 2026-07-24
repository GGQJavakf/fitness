package com.aifitness.assistant.identity.application;

import java.util.Objects;

@FunctionalInterface
public interface WechatIdentityProvider {

    ProviderSubject exchange(String oneTimeCode);

    record ProviderSubject(String subject) {
        public ProviderSubject {
            if (subject == null || subject.isBlank()) {
                throw new IllegalArgumentException("provider subject must not be blank");
            }
        }
    }

    final class ExchangeRejectedException extends RuntimeException {
        public ExchangeRejectedException() {
            super("wechat credential exchange rejected");
        }
    }
}
