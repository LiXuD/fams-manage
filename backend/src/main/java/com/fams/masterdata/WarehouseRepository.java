package com.fams.masterdata;

import java.time.Instant;
import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/** Repository boundary for the WarehouseMapper aggregate; pass-through persistence calls only. */
@Repository
@Profile("fams-product")
class WarehouseRepository {

    private final WarehouseMapper mapper;

    WarehouseRepository(WarehouseMapper mapper) {
        this.mapper = mapper;
    }

    WarehouseRow find(long id) {
        return mapper.find(id);
    }

    WarehouseRow findForUpdate(long id) {
        return mapper.findForUpdate(id);
    }

    WarehouseRow findByCode(String normalizedCode) {
        return mapper.findByCode(normalizedCode);
    }

    Long findActiveLocationIdForUpdate(long id) {
        return mapper.findActiveLocationIdForUpdate(id);
    }

    Long findActiveAssetReferenceIdForUpdate(long id) {
        return mapper.findActiveAssetReferenceIdForUpdate(id);
    }

    Long findPlannedAssetReferenceIdForUpdate(long id) {
        return mapper.findPlannedAssetReferenceIdForUpdate(id);
    }

    Long findOpenInboundReferenceIdForUpdate(long id) {
        return mapper.findOpenInboundReferenceIdForUpdate(id);
    }

    long count() {
        return mapper.count();
    }

    List<WarehouseRow> list(int offset, int limit) {
        return mapper.list(offset, limit);
    }

    long countActive() {
        return mapper.countActive();
    }

    List<WarehouseRow> listActive(int offset, int limit) {
        return mapper.listActive(offset, limit);
    }

    int insert(NewWarehouseRow row) {
        return mapper.insert(row);
    }

    int update(long id, long version, String code, String normalizedCode, String name,
            String description, long actorId, Instant now) {
        return mapper.update(id, version, code, normalizedCode, name, description, actorId, now);
    }

    int changeStatus(long id, long version, String status, long actorId, Instant now) {
        return mapper.changeStatus(id, version, status, actorId, now);
    }
}
