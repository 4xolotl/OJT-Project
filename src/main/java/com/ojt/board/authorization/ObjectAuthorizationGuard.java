package com.ojt.board.authorization;

import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class ObjectAuthorizationGuard {

    public void requireOwner(Long actorId, Long ownerId, Long resourceId,
                             ObjectAuthorizationOperation operation) {
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(resourceId, "resourceId");
        Objects.requireNonNull(operation, "operation");
        if (!ownerId.equals(actorId)) {
            throw new ObjectAuthorizationDeniedException(actorId, resourceId, operation);
        }
    }
}
