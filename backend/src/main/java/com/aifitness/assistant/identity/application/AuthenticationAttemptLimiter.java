package com.aifitness.assistant.identity.application;

import java.time.Instant;

public interface AuthenticationAttemptLimiter {
    boolean allow(Action action, String credential, Instant now);

    enum Action {
        WECHAT_CODE_LOGIN,
        TRUSTED_SUBJECT_LOGIN,
        REFRESH_TOKEN
    }

    static AuthenticationAttemptLimiter allowAll() {
        return (action, credential, now) -> true;
    }
}
