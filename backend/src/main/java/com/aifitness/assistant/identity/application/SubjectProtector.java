package com.aifitness.assistant.identity.application;

@FunctionalInterface
public interface SubjectProtector {

    byte[] protect(String providerSubject);
}
