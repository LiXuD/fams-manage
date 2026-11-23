package com.fams.masterdata;

import java.time.Instant;
import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/** Repository boundary for the ManufacturerMapper aggregate; pass-through persistence calls only. */
@Repository
@Profile("fams-product")
class ManufacturerRepository {

    private final ManufacturerMapper mapper;

    ManufacturerRepository(ManufacturerMapper mapper) {
        this.mapper = mapper;
    }

    ManufacturerRow find(long id) {
        return mapper.find(id);
    }

    ManufacturerRow findByCode(String normalizedCode) {
        return mapper.findByCode(normalizedCode);
    }

    long count() {
        return mapper.count();
    }

    long countActive() {
        return mapper.countActive();
    }

    List<ManufacturerRow> list(int offset, int limit) {
        return mapper.list(offset, limit);
    }

    List<ManufacturerRow> listActive(int offset, int limit) {
        return mapper.listActive(offset, limit);
    }

    int insert(NewManufacturerRow row) {
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
