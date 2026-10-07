package com.ojt.board.authorization;

import java.util.Objects;
import org.springframework.security.access.AccessDeniedException;

public final class ObjectAuthorizationDeniedException extends AccessDeniedException {

    private final long actorId;
    private final long resourceId;
    private final ObjectAuthorizationOperation operation;

    public ObjectAuthorizationDeniedException(Long actorId, Long resourceId,
                                               ObjectAuthorizationOperation operation) {
        super(Objects.requireNonNull(operation, "operation").deniedMessage());
        this.actorId = Objects.requireNonNull(actorId, "actorId");
        this.resourceId = Objects.requireNonNull(resourceId, "resourceId");
        this.operation = operation;
    }

    public long actorId() {
        return actorId;
    }

    public long resourceId() {
        return resourceId;
    }

    public ObjectAuthorizationOperation operation() {
        return operation;
    }
}
