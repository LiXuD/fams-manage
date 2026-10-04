package com.fams.masterdata;

import java.time.ZoneId;
import java.util.List;

import com.fams.product.contract.model.NumberingTarget;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Narrow cross-domain entry point for asset card creation. */
@Profile("fams-product")
@Component
public final class AssetMasterDataPort {
    private final NumberAllocationService numbers;
    private final BusinessParameterService parameters;

    AssetMasterDataPort(NumberAllocationService numbers, BusinessParameterService parameters) {
        this.numbers = numbers;
        this.parameters = parameters;
    }

    public List<AllocatedAssetCode> reserveAssetCodes(Long categoryId, int count) {
        return numbers.reserve(NumberingTarget.ASSET, categoryId, count).stream()
                .map(item -> new AllocatedAssetCode(item.id(), item.number())).toList();
    }

    public void markUsed(long reservationId) {
        numbers.markUsed(reservationId);
    }

    public long currentParameterVersionId() {
        return Long.parseLong(parameters.current().getId());
    }

    public ZoneId currentZone() {
        return parameters.currentZoneOrDefault();
    }

    public record AllocatedAssetCode(long reservationId, String code) { }
}
