package com.fams.asset;

import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.fams.auth.AssetActorContext.Actor;
import com.fams.product.contract.model.AssetFilterCriteria;
import com.fams.product.contract.model.AssetSavedFilter;
import com.fams.product.contract.model.AssetSavedFilterCreateRequest;
import com.fams.product.contract.model.AssetSavedFilterUpdateRequest;
import com.fams.product.contract.model.PageAssetSavedFilter;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Profile("fams-product")
@Service
class AssetPreferenceService {
    private final AssetMapper mapper;
    private final AssetSecretCodec codec;
    private final ObjectMapper json;
    private final Clock clock;

    AssetPreferenceService(AssetMapper mapper, AssetSecretCodec codec,
            ObjectMapper json, Clock authClock) {
        this.mapper = mapper;
        this.codec = codec;
        this.json = json;
        this.clock = authClock;
    }

    PageAssetSavedFilter list(Actor actor, int page, int pageSize) {
        return new PageAssetSavedFilter(page, pageSize, mapper.savedFilterCount(actor.id()),
                mapper.savedFilters(actor.id(), pageSize, (page - 1) * pageSize)
                        .stream().map(this::toFilter).toList());
    }

    @Transactional
    AssetSavedFilter create(Actor actor, AssetSavedFilterCreateRequest request,
            String key, UUID requestId) {
        String operation = "createAssetSavedFilter";
        String digest = codec.hash(operation + json.writeValueAsString(request));
        AssetSavedFilter replay = replay(actor, operation, key, digest);
        if (replay != null) { return replay; }
        if (mapper.savedFilterCount(actor.id()) >= 20) {
            throw AssetOperationException.conflict("ASSET_SAVED_FILTER_LIMIT_REACHED",
                    "At most 20 personal filters are allowed.");
        }
        Map<String, Object> values = values(actor, request.getName(), request.getCriteria());
        mapper.insertSavedFilter(values);
        AssetSavedFilter result = toFilter(mapper.savedFilter(
                ((Number) values.get("id")).longValue(), actor.id()));
        audit(actor, "ASSET_SAVED_FILTER_CREATED", requestId, result.getId());
        record(actor, operation, key, digest, result);
        return result;
    }

    @Transactional
    AssetSavedFilter update(Actor actor, long id, long version,
            AssetSavedFilterUpdateRequest request, String key, UUID requestId) {
        String operation = "updateAssetSavedFilter";
        String digest = codec.hash(operation + id + ":" + version + json.writeValueAsString(request));
        AssetSavedFilter replay = replay(actor, operation, key, digest);
        if (replay != null) { return replay; }
        Map<String, Object> existing = require(actor, id);
        if (((Number) existing.get("version")).longValue() != version) { throw versionConflict(); }
        String name = request.getName() == null ? existing.get("name").toString() : request.getName();
        AssetFilterCriteria criteria = request.getCriteria() == null
                ? json.readValue(existing.get("criteria_json").toString(), AssetFilterCriteria.class)
                : request.getCriteria();
        Map<String, Object> values = values(actor, name, criteria);
        values.put("id", id);
        values.put("version", version);
        if (mapper.updateSavedFilter(values) != 1) { throw versionConflict(); }
        AssetSavedFilter result = toFilter(mapper.savedFilter(id, actor.id()));
        audit(actor, "ASSET_SAVED_FILTER_UPDATED", requestId, result.getId());
        record(actor, operation, key, digest, result);
        return result;
    }

    @Transactional
    void delete(Actor actor, long id, long version, String key, UUID requestId) {
        String operation = "deleteAssetSavedFilter";
        String digest = codec.hash(operation + id + ":" + version);
        Map<String, Object> prior = mapper.replay(actor.id(), operation, key, now());
        if (prior != null) {
            if (!digest.equals(prior.get("payload_sha256"))) { throw idempotencyConflict(); }
            return;
        }
        Map<String, Object> existing = require(actor, id);
        if (((Number) existing.get("version")).longValue() != version) { throw versionConflict(); }
        if (mapper.deleteSavedFilter(id, actor.id(), version) != 1) { throw versionConflict(); }
        audit(actor, "ASSET_SAVED_FILTER_DELETED", requestId, Long.toString(id));
        Instant timestamp = now();
        mapper.record(actor.id(), operation, key, digest, 204, "{}", null, null,
                timestamp, timestamp.plus(24, ChronoUnit.HOURS));
    }

    private Map<String, Object> values(Actor actor, String name, AssetFilterCriteria criteria) {
        if (name == null || name.isBlank() || name.strip().length() > 64 || criteria == null
                || json.readTree(json.writeValueAsString(criteria)).size() == 0) {
            throw AssetOperationException.invalid("ASSET_SAVED_FILTER_INVALID",
                    "A name and typed criteria are required.");
        }
        Map<String, Object> values = new HashMap<>();
        values.put("ownerId", actor.id());
        values.put("name", name.strip());
        values.put("normalized", Normalizer.normalize(name.strip(), Normalizer.Form.NFKC)
                .toUpperCase(Locale.ROOT));
        values.put("criteria", json.writeValueAsString(criteria));
        values.put("now", now());
        return values;
    }

    private Map<String, Object> require(Actor actor, long id) {
        Map<String, Object> result = mapper.savedFilter(id, actor.id());
        if (result == null) { throw AssetOperationException.notFound(); }
        return result;
    }

    private AssetSavedFilter toFilter(Map<String, Object> row) {
        return new AssetSavedFilter(row.get("id").toString(), row.get("owner_account_id").toString(),
                row.get("name").toString(),
                json.readValue(row.get("criteria_json").toString(), AssetFilterCriteria.class),
                ((Number) row.get("version")).longValue(),
                instant(row.get("created_at")).atOffset(ZoneOffset.UTC),
                instant(row.get("updated_at")).atOffset(ZoneOffset.UTC));
    }

    private Instant instant(Object value) {
        if (value instanceof Instant instant) { return instant; }
        if (value instanceof java.sql.Timestamp timestamp) { return timestamp.toInstant(); }
        if (value instanceof java.time.LocalDateTime dateTime) { return dateTime.toInstant(ZoneOffset.UTC); }
        throw new IllegalStateException("Unsupported database timestamp type.");
    }

    private AssetSavedFilter replay(Actor actor, String operation, String key, String digest) {
        Map<String, Object> prior = mapper.replay(actor.id(), operation, key, now());
        if (prior == null) { return null; }
        if (!digest.equals(prior.get("payload_sha256"))) { throw idempotencyConflict(); }
        return json.readValue(prior.get("response_body_json").toString(), AssetSavedFilter.class);
    }

    private void record(Actor actor, String operation, String key, String digest,
            AssetSavedFilter result) {
        Instant timestamp = now();
        mapper.record(actor.id(), operation, key, digest, 200,
                json.writeValueAsString(result), Long.parseLong(result.getId()), result.getVersion(),
                timestamp, timestamp.plus(24, ChronoUnit.HOURS));
    }

    private void audit(Actor actor, String event, UUID requestId, String id) {
        mapper.audit(actor.id(), event, requestId.toString(), now(),
                json.writeValueAsString(Map.of("filterId", id)));
    }

    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MILLIS); }

    private AssetOperationException versionConflict() {
        return AssetOperationException.conflict("ASSET_SAVED_FILTER_VERSION_CONFLICT",
                "The saved filter version changed.");
    }

    private AssetOperationException idempotencyConflict() {
        return AssetOperationException.conflict("ASSET_SAVED_FILTER_IDEMPOTENCY_CONFLICT",
                "The key has another payload.");
    }
}
