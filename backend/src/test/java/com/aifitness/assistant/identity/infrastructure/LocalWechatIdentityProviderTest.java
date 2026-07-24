package com.aifitness.assistant.identity.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class LocalWechatIdentityProviderTest {

    @Test
    void sameOneTimeCodeCanBeConsumedAtomicallyOnlyOnce() throws Exception {
        var provider = new LocalWechatIdentityProvider();
        var start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(8)) {
            for (int attempt = 0; attempt < 8; attempt++) {
                results.add(executor.submit(() -> {
                    start.await();
                    try {
                        provider.exchange("alice|same-nonce");
                        return true;
                    } catch (IllegalArgumentException consumed) {
                        return false;
                    }
                }));
            }
            start.countDown();
            List<Boolean> accepted = new ArrayList<>();
            for (Future<Boolean> result : results) {
                accepted.add(result.get());
            }
            assertThat(accepted).filteredOn(Boolean::booleanValue).hasSize(1);
        }
    }

    @Test
    void differentCodesForTheSameSubjectResolveToTheSameProviderSubject() {
        var provider = new LocalWechatIdentityProvider();

        var first = provider.exchange("alice|nonce-1");
        var second = provider.exchange("alice|nonce-2");

        assertThat(second).isEqualTo(first);
    }
}
