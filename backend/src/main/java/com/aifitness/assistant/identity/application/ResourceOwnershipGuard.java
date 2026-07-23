package com.aifitness.assistant.identity.application;

import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import java.util.Objects;
import java.util.UUID;

public final class ResourceOwnershipGuard {

    public void requireOwnedBy(AuthenticatedUserId authenticatedUser, AuthenticatedUserId resourceOwner) {
        if (!Objects.equals(authenticatedUser, resourceOwner)) {
            throw new ResourceNotFoundException();
        }
    }

    public <T> T requireOwnedResource(
            UUID resourceId, AuthenticatedUserId authenticatedUser, OwnedResourceLookup<T> lookup) {
        Objects.requireNonNull(resourceId, "resourceId must not be null");
        Objects.requireNonNull(authenticatedUser, "authenticatedUser must not be null");
        Objects.requireNonNull(lookup, "lookup must not be null");
        OwnedResource<T> resource = lookup.findById(resourceId);
        if (resource == null || !authenticatedUser.equals(resource.owner())) {
            throw new ResourceNotFoundException();
        }
        return resource.value();
    }

    @FunctionalInterface
    public interface OwnedResourceLookup<T> {
        OwnedResource<T> findById(UUID resourceId);
    }

    public record OwnedResource<T>(AuthenticatedUserId owner, T value) {
        public OwnedResource {
            Objects.requireNonNull(owner, "owner must not be null");
            Objects.requireNonNull(value, "value must not be null");
        }
    }

    public static final class ResourceNotFoundException extends RuntimeException {
        public ResourceNotFoundException() {
            super("resource not found");
        }
    }
}
