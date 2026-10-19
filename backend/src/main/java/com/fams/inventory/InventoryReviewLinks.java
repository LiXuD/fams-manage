package com.fams.inventory;

import com.fams.auth.RequisitionActorContext.Actor;

/** Keep readonly conflict navigation within the credential's existing API channel. */
final class InventoryReviewLinks {
    private InventoryReviewLinks() {}

    static String task(Actor actor, long task) {
        return (actor.miniToken() == null ? "/api/v1" : "/api/v1/mini")
                + "/inventory-reviews/tasks/"
                + task;
    }
}
