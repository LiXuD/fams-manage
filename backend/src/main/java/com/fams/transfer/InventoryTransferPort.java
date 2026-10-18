package com.fams.transfer;

import com.fams.asset.AssetHandoverPort;
import com.fams.asset.AssetTransferPort;
import com.fams.auth.RequisitionActorContext.Actor;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Inventory owns difference competition; formal approval, custody and locks stay transfer-owned.
 */
@Component
@Profile("fams-product")
@Transactional(propagation = Propagation.MANDATORY)
public class InventoryTransferPort {
    private final TransferService transfers;

    public InventoryTransferPort(TransferService transfers) {
        this.transfers = transfers;
    }

    public record Link(String requestId, String itemId, long version, String status) {}

    public static final class Failure extends RuntimeException {
        private final int status;

        Failure(String code, int status) {
            super(code);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    public Link createAndSubmit(
            Actor actor,
            String operationKey,
            long assetId,
            long assetVersion,
            AssetTransferPort.Target target,
            String reason) {
        try {
            var draft =
                    transfers.create(
                            actor,
                            operationKey + ":create",
                            new TransferController.Draft(
                                    reason,
                                    List.of(
                                            new TransferController.Item(
                                                    assetId, assetVersion, target))));
            long request = Long.parseLong(draft.get("id").toString());
            return link(
                    transfers.action(
                            actor,
                            request,
                            ((Number) draft.get("version")).longValue(),
                            operationKey + ":submit",
                            "submit"));
        } catch (TransferFailure failure) {
            throw new Failure(failure.getMessage(), failure.status());
        } catch (AssetHandoverPort.Failure failure) {
            throw new Failure(failure.getMessage(), failure.status());
        }
    }

    public Link progress(Actor actor, long requestId) {
        try {
            return link(transfers.get(actor, requestId));
        } catch (TransferFailure failure) {
            throw new Failure(failure.getMessage(), failure.status());
        }
    }

    private Link link(Map<String, Object> row) {
        Object value = row.get("items");
        if (!(value instanceof List<?> items)
                || items.size() != 1
                || !(items.getFirst() instanceof Map<?, ?> item)) {
            throw new IllegalStateException("Inventory transfer must have exactly one asset");
        }
        return new Link(
                row.get("id").toString(),
                item.get("id").toString(),
                ((Number) row.get("version")).longValue(),
                "REJECTED".equals(row.get("requestStatus"))
                        ? "REJECTED"
                        : item.get("itemStatus").toString());
    }
}
