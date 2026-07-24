package com.aifitness.assistant.privacy.infrastructure;

import com.aifitness.assistant.identity.application.WechatIdentityResolver;
import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.privacy.application.PrivacyRequestService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;

/**
 * Local/test adapter. The production adapter must use a non-creating identity lookup and the
 * configured WeChat code exchange service before public release.
 */
final class WechatCodeReauthenticationAdapter implements PrivacyRequestService.ReauthenticationPort {

    private final WechatIdentityResolver identities;
    private final Clock clock;
    private final Duration timeToLive;
    private final Map<String, Instant> consumedProofs = new HashMap<>();

    WechatCodeReauthenticationAdapter(
            WechatIdentityResolver identities, Clock clock, Duration timeToLive) {
        this.identities = Objects.requireNonNull(identities);
        this.clock = Objects.requireNonNull(clock);
        this.timeToLive = Objects.requireNonNull(timeToLive);
    }

    @Override
    public synchronized boolean verify(AuthenticatedUserId user, String oneTimeProof) {
        try {
            var resolved = identities.resolveExisting(oneTimeProof).orElse(null);
            Instant now = clock.instant();
            if (resolved == null
                    || resolved.verifiedAt().isAfter(now)
                    || resolved.verifiedAt().isBefore(now.minus(timeToLive))) {
                return false;
            }
            consumedProofs.entrySet().removeIf(entry -> entry.getValue().isBefore(now.minus(timeToLive)));
            if (consumedProofs.putIfAbsent(digest(oneTimeProof), now) != null) {
                return false;
            }
            return resolved.userId().equals(user);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static String digest(String proof) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(proof.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
