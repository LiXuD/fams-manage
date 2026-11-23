package com.fams.masterdata;

import java.time.Instant;
import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/** Repository boundary for the AssetCategoryMapper aggregate; pass-through persistence calls only. */
@Repository
@Profile("fams-product")
class AssetCategoryRepository {

    private final AssetCategoryMapper mapper;

    AssetCategoryRepository(AssetCategoryMapper mapper) {
        this.mapper = mapper;
    }

    AssetCategoryRow find(long id) {
        return mapper.find(id);
    }

    AssetCategoryRow findForUpdate(long id) {
        return mapper.findForUpdate(id);
    }

    AssetCategoryRow findByCode(String normalizedCode) {
        return mapper.findByCode(normalizedCode);
    }

    long count() {
        return mapper.count();
    }

    long countActive() {
        return mapper.countActive();
    }

    List<AssetCategoryRow> list(int offset, int limit) {
        return mapper.list(offset, limit);
    }

    List<AssetCategoryRow> listActive(int offset, int limit) {
        return mapper.listActive(offset, limit);
    }

    int insert(NewAssetCategoryRow row) {
        return mapper.insert(row);
    }

    int update(long id, long version, String code, String normalizedCode, String name,
            Long parentId, String description, String suggestedUnit, String numberingPrefix,
            String extensionTemplateJson, long actorId, Instant now) {
        return mapper.update(id, version, code, normalizedCode, name, parentId, description,
                suggestedUnit, numberingPrefix, extensionTemplateJson, actorId, now);
    }

    int changeStatus(long id, long version, String status, long actorId, Instant now) {
        return mapper.changeStatus(id, version, status, actorId, now);
    }
}
