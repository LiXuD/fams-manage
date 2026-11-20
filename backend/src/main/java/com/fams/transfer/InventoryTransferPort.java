package com.fams.transfer;

import com.fams.asset.AssetHandoverPort;
import com.fams.asset.AssetTransferPort;
import com.fams.auth.RequisitionActorContext.Actor;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

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
            return link(
                    transfers.action(
                            actor,
                            Long.parseLong(draft.id()),
                            draft.version(),
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

    private Link link(TransferViews.Detail detail) {
        if (detail.items().size() != 1) {
            throw new IllegalStateException("Inventory transfer must have exactly one asset");
        }
        var item = detail.items().getFirst();
        return new Link(
                detail.id(),
                item.id(),
                detail.version(),
                "REJECTED".equals(detail.requestStatus()) ? "REJECTED" : item.itemStatus());
    }
}
