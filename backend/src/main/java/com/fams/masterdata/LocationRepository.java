package com.fams.masterdata;

import java.time.Instant;
import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/** Repository boundary for the LocationMapper aggregate; pass-through persistence calls only. */
@Repository
@Profile("fams-product")
class LocationRepository {

    private final LocationMapper mapper;

    LocationRepository(LocationMapper mapper) {
        this.mapper = mapper;
    }

    LocationRow find(long id) {
        return mapper.find(id);
    }

    LocationRow findForUpdate(long id) {
        return mapper.findForUpdate(id);
    }

    LocationRow findByCode(String code) {
        return mapper.findByCode(code);
    }

    long count() {
        return mapper.count();
    }

    long countActive() {
        return mapper.countActive();
    }

    Long findActiveChildIdForUpdate(long id) {
        return mapper.findActiveChildIdForUpdate(id);
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

    long countChildren(long id) {
        return mapper.countChildren(id);
    }

    List<LocationRow> list(int offset, int limit) {
        return mapper.list(offset, limit);
    }

    List<LocationRow> listActive(int offset, int limit) {
        return mapper.listActive(offset, limit);
    }

    int insert(NewLocationRow row) {
        return mapper.insert(row);
    }

    int update(long id, long version, String code, String normalizedCode, String name,
            String locationType, Long parentId, Long warehouseId, String description,
            long actorId, Instant now) {
        return mapper.update(id, version, code, normalizedCode, name, locationType, parentId,
                warehouseId, description, actorId, now);
    }

    int changeStatus(long id, long version, String status, long actorId, Instant now) {
        return mapper.changeStatus(id, version, status, actorId, now);
    }
}
