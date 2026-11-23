package com.fams.masterdata;

import java.time.Instant;
import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/** Repository boundary for the BrandMapper aggregate; pass-through persistence calls only. */
@Repository
@Profile("fams-product")
class BrandRepository {

    private final BrandMapper mapper;

    BrandRepository(BrandMapper mapper) {
        this.mapper = mapper;
    }

    BrandRow find(long id) {
        return mapper.find(id);
    }

    BrandRow findByCode(String normalizedCode) {
        return mapper.findByCode(normalizedCode);
    }

    long count() {
        return mapper.count();
    }

    long countActive() {
        return mapper.countActive();
    }

    List<BrandRow> list(int offset, int limit) {
        return mapper.list(offset, limit);
    }

    List<BrandRow> listActive(int offset, int limit) {
        return mapper.listActive(offset, limit);
    }

    int insert(NewBrandRow row) {
        return mapper.insert(row);
    }

    int update(long id, long version, String code, String normalizedCode, String name,
            Long defaultManufacturerId, String description, long actorId, Instant now) {
        return mapper.update(id, version, code, normalizedCode, name, defaultManufacturerId,
                description, actorId, now);
    }

    int changeStatus(long id, long version, String status, long actorId, Instant now) {
        return mapper.changeStatus(id, version, status, actorId, now);
    }
}
