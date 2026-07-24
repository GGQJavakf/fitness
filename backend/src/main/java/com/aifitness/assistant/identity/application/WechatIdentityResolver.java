package com.aifitness.assistant.identity.application;

import com.aifitness.assistant.identity.domain.AuthenticatedUserId;

@FunctionalInterface
public interface WechatIdentityResolver {

    AuthenticatedUserId resolve(String oneTimeCode);
}
