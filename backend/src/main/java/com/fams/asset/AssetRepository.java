package com.fams.asset;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/** Repository boundary for the AssetMapper aggregate; pass-through persistence calls only. */
@Repository
@Profile("fams-product")
class AssetRepository {

    private final AssetMapper mapper;

    AssetRepository(AssetMapper mapper) {
        this.mapper = mapper;
    }

    long factoryReplacement(long id) {
        return mapper.factoryReplacement(id);
    }

    Map<String, Object> find(long id) {
        return mapper.find(id);
    }

    Map<String, Object> lock(long id) {
        return mapper.lock(id);
    }

    List<Map<String, Object>> list(long actorId, Map<String, Object> filters, int limit, int offset) {
        return mapper.list(actorId, filters, limit, offset);
    }

    long count(long actorId, Map<String, Object> filters) {
        return mapper.count(actorId, filters);
    }

    List<String> candidateLoanTimezones(LocalDate earliest, LocalDate latest) {
        return mapper.candidateLoanTimezones(earliest, latest);
    }

    List<Map<String, Object>> referenceViews(String kind, List<Long> ids) {
        return mapper.referenceViews(kind, ids);
    }

    int insert(Map<String, Object> values) {
        return mapper.insert(values);
    }

    int update(Map<String, Object> values) {
        return mapper.update(values);
    }

    int cancel(long id, long version, String reason, long actorId, Instant now) {
        return mapper.cancel(id, version, reason, actorId, now);
    }

    int activeLock(long assetId) {
        return mapper.activeLock(assetId);
    }

    Map<String, Object> category(long id) {
        return mapper.category(id);
    }

    Map<String, Object> categoryView(long id) {
        return mapper.categoryView(id);
    }

    String categoryTemplate(long id) {
        return mapper.categoryTemplate(id);
    }

    Map<String, Object> organization(long id) {
        return mapper.organization(id);
    }

    Map<String, Object> organizationView(long id) {
        return mapper.organizationView(id);
    }

    Map<String, Object> warehouse(long id) {
        return mapper.warehouse(id);
    }

    Map<String, Object> warehouseView(long id) {
        return mapper.warehouseView(id);
    }

    Map<String, Object> location(long id) {
        return mapper.location(id);
    }

    Map<String, Object> locationView(long id) {
        return mapper.locationView(id);
    }

    Map<String, Object> brand(long id) {
        return mapper.brand(id);
    }

    Map<String, Object> brandView(long id) {
        return mapper.brandView(id);
    }

    Map<String, Object> manufacturer(long id) {
        return mapper.manufacturer(id);
    }

    Map<String, Object> manufacturerView(long id) {
        return mapper.manufacturerView(id);
    }

    Map<String, Object> employee(long id) {
        return mapper.employee(id);
    }

    Map<String, Object> tag(long assetId) {
        return mapper.tag(assetId);
    }

    Long assetIdByTokenHash(String hash) {
        return mapper.assetIdByTokenHash(hash);
    }

    Map<String, Object> tagPlacement(long assetId, long tagVersion, Instant issuedAt) {
        return mapper.tagPlacement(assetId, tagVersion, issuedAt);
    }

    int addTagPlacement(long assetId, long tagVersion, Instant issuedAt, long actorId, Instant confirmedAt) {
        return mapper.addTagPlacement(assetId, tagVersion, issuedAt, actorId, confirmedAt);
    }

    int insertTag(long assetId, byte[] ciphertext, String hash, Instant now) {
        return mapper.insertTag(assetId, ciphertext, hash, now);
    }

    int rotateTag(long assetId, long version, byte[] ciphertext, String hash, Instant now) {
        return mapper.rotateTag(assetId, version, ciphertext, hash, now);
    }

    int archiveTag(long assetId, String hash, Instant validFrom, Instant now, String reason) {
        return mapper.archiveTag(assetId, hash, validFrom, now, reason);
    }

    int touch(long assetId, long version, long actorId, Instant now) {
        return mapper.touch(assetId, version, actorId, now);
    }

    int event(long assetId, String event, Instant now, String summary, long actorId) {
        return mapper.event(assetId, event, now, summary, actorId);
    }

    List<Map<String, Object>> timeline(long assetId, int limit, int offset) {
        return mapper.timeline(assetId, limit, offset);
    }

    long timelineCount(long assetId) {
        return mapper.timelineCount(assetId);
    }

    Map<String, Object> replay(long actorId, String operation, String key, Instant now) {
        return mapper.replay(actorId, operation, key, now);
    }

    int record(long actorId, String operation, String key, String digest, int status, String response,
            Long resourceId, Long resourceVersion, Instant now, Instant expiry) {
        return mapper.record(actorId, operation, key, digest, status, response, resourceId,
                resourceVersion, now, expiry);
    }

    int insertPrintJob(Map<String, Object> values) {
        return mapper.insertPrintJob(values);
    }

    int insertPrintItem(long jobId, long assetId, long assetVersion, long tagVersion, String assetCode,
            String assetName, byte[] tokenCiphertext) {
        return mapper.insertPrintItem(jobId, assetId, assetVersion, tagVersion, assetCode, assetName,
                tokenCiphertext);
    }

    Map<String, Object> printJob(long id, long actorId) {
        return mapper.printJob(id, actorId);
    }

    List<Map<String, Object>> printItems(long id) {
        return mapper.printItems(id);
    }

    List<Map<String, Object>> savedFilters(long ownerId, int limit, int offset) {
        return mapper.savedFilters(ownerId, limit, offset);
    }

    long savedFilterCount(long ownerId) {
        return mapper.savedFilterCount(ownerId);
    }

    Map<String, Object> savedFilter(long id, long ownerId) {
        return mapper.savedFilter(id, ownerId);
    }

    int insertSavedFilter(Map<String, Object> values) {
        return mapper.insertSavedFilter(values);
    }

    int updateSavedFilter(Map<String, Object> values) {
        return mapper.updateSavedFilter(values);
    }

    int deleteSavedFilter(long id, long ownerId, long version) {
        return mapper.deleteSavedFilter(id, ownerId, version);
    }

    int audit(long actorId, String event, String requestId, Instant now, String details) {
        return mapper.audit(actorId, event, requestId, now, details);
    }
}
