package com.fams.masterdata;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.fams.product.contract.model.BusinessParameterSnapshot;
import com.fams.product.contract.model.BusinessParameterVersion;
import com.fams.product.contract.model.BusinessParameterVersionActor;
import com.fams.product.contract.model.BusinessParameterVersionCreateRequest;
import com.fams.product.contract.model.BusinessParameterVersionStatus;
import com.fams.product.contract.model.NotificationEscalationThresholds;
import com.fams.product.contract.model.PageBusinessParameterVersion;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Profile("fams-product")
@Service
class BusinessParameterService {

    private final BusinessParameterMapper mapper;
    private final IdentityMasterDataMapper records;
    private final MasterDataFingerprint fingerprint;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    BusinessParameterService(BusinessParameterMapper mapper, IdentityMasterDataMapper records,
            MasterDataFingerprint fingerprint, ObjectMapper objectMapper, Clock authClock) {
        this.mapper = mapper;
        this.records = records;
        this.fingerprint = fingerprint;
        this.objectMapper = objectMapper;
        this.clock = authClock;
    }

    PageBusinessParameterVersion list(int page, int pageSize) {
        List<BusinessParameterVersion> items = mapper.list((page - 1) * pageSize, pageSize)
                .stream().map(this::toVersion).toList();
        return new PageBusinessParameterVersion(page, pageSize, mapper.count(), items);
    }

    BusinessParameterVersion get(long id) {
        BusinessParameterRow row = mapper.find(id);
        if (row == null) {
            throw MasterDataOperationException.notFound("The business-parameter version");
        }
        return toVersion(row);
    }

    BusinessParameterVersion current() {
        BusinessParameterState state = mapper.state();
        if (state == null || state.currentVersionId() == null) {
            throw MasterDataOperationException.notFound("The current business-parameter version");
        }
        return get(state.currentVersionId());
    }

    ZoneId currentZoneOrDefault() {
        BusinessParameterState state = mapper.state();
        if (state == null || state.currentVersionId() == null) {
            return ZoneId.of("Asia/Shanghai");
        }
        return ZoneId.of(get(state.currentVersionId()).getSnapshot().getTimezone());
    }

    @Transactional
    BusinessParameterVersion create(long actorId, long expectedVersion, JsonNode raw,
            String key, UUID requestId) {
        String operation = "createBusinessParameterVersion";
        String digest = fingerprint.of(operation, Long.toString(expectedVersion),
                objectMapper.writeValueAsString(raw));
        Optional<BusinessParameterVersion> replay = replay(actorId, operation, key, digest);
        if (replay.isPresent()) {
            return replay.orElseThrow();
        }
        BusinessParameterVersionCreateRequest request = parse(raw);
        validate(raw, request.getSnapshot());
        BusinessParameterState state = mapper.lockState();
        if (state == null || state.currentVersion() != expectedVersion) {
            throw versionConflict();
        }
        String actorUsername = mapper.accountUsername(actorId);
        if (actorUsername == null) {
            throw MasterDataOperationException.notFound("The administrator account");
        }
        BusinessParameterVersion before = state.currentVersionId() == null
                ? null : get(state.currentVersionId());
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        if (before != null && !now.isAfter(before.getEffectiveAt().toInstant())) {
            throw new MasterDataOperationException("BUSINESS_PARAMETER_VERSION_CONFLICT",
                    "The next version must have a later effective instant.", 409);
        }
        if (before != null && mapper.supersede(state.currentVersionId()) != 1) {
            throw versionConflict();
        }
        NewBusinessParameterRow row = new NewBusinessParameterRow(expectedVersion + 1,
                now, actorId, actorUsername, objectMapper.writeValueAsString(request.getSnapshot()));
        requireOne(mapper.insert(row));
        requireOne(mapper.activate(expectedVersion, row.getVersion(), row.getId()));
        BusinessParameterVersion result = get(row.getId());
        audit(actorId, requestId, before, result, now);
        record(actorId, operation, key, digest, result, now);
        return result;
    }

    private BusinessParameterVersionCreateRequest parse(JsonNode raw) {
        if (raw == null || !raw.isObject() || raw.size() != 1 || raw.has("effectiveAt")
                || !raw.has("snapshot") || !raw.path("snapshot").isObject()) {
            throw MasterDataOperationException.invalid("A complete immediate snapshot is required.");
        }
        JsonNode snapshot = raw.path("snapshot");
        for (String field : List.of("timezone", "workWeek", "holidays", "adjustedWorkdays",
                "adminDecisionDeadlineWorkdays", "warehouseClaimDeadlineWorkdays",
                "warehouseFirstExecutionDeadlineWorkdays", "handoverAcceptanceDeadlineNaturalDays",
                "notificationEscalationThresholds")) {
            if (!snapshot.hasNonNull(field)) {
                throw MasterDataOperationException.invalid("The complete snapshot is required.");
            }
        }
        if (snapshot.size() != 9) {
            throw MasterDataOperationException.invalid("Unexpected snapshot fields are not allowed.");
        }
        JsonNode thresholds = snapshot.path("notificationEscalationThresholds");
        if (!thresholds.isObject()) {
            throw MasterDataOperationException.invalid("The notification thresholds are required.");
        }
        for (String field : List.of("administratorUnreadHours",
                "onDutyWarehouseOperatorUnreadHours", "firstExecutionOverdueNaturalDays",
                "receiptDueUnreadHours")) {
            if (!thresholds.hasNonNull(field)) {
                throw MasterDataOperationException.invalid("The complete notification thresholds are required.");
            }
        }
        if (thresholds.size() != 4) {
            throw MasterDataOperationException.invalid("Unexpected notification fields are not allowed.");
        }
        try {
            return objectMapper.treeToValue(raw, BusinessParameterVersionCreateRequest.class);
        } catch (RuntimeException exception) {
            throw MasterDataOperationException.invalid("The parameter snapshot is invalid.");
        }
    }

    private void validate(JsonNode raw, BusinessParameterSnapshot snapshot) {
        if (snapshot == null) {
            throw MasterDataOperationException.invalid("The parameter snapshot is required.");
        }
        String timezone = snapshot.getTimezone();
        if (timezone == null) {
            throw new MasterDataOperationException("BUSINESS_PARAMETER_TIMEZONE_INVALID",
                    "The timezone is not a valid IANA zone.", 400);
        }
        try {
            ZoneId.of(timezone);
        } catch (DateTimeException exception) {
            throw new MasterDataOperationException("BUSINESS_PARAMETER_TIMEZONE_INVALID",
                    "The timezone is not a valid IANA zone.", 400);
        }
        if (snapshot.getWorkWeek() == null || snapshot.getWorkWeek().isEmpty()) {
            throw MasterDataOperationException.invalid("The work week must not be empty.");
        }
        JsonNode rawSnapshot = raw.path("snapshot");
        assertUnique(rawSnapshot.path("workWeek"));
        Set<String> holidays = assertUnique(rawSnapshot.path("holidays"));
        Set<String> adjusted = assertUnique(rawSnapshot.path("adjustedWorkdays"));
        for (String date : holidays) {
            if (adjusted.contains(date)) {
                throw new MasterDataOperationException("BUSINESS_PARAMETER_DATE_CONFLICT",
                        "Holidays and adjusted workdays cannot overlap.", 400);
            }
        }
        NotificationEscalationThresholds thresholds = snapshot.getNotificationEscalationThresholds();
        if (!positive(snapshot.getAdminDecisionDeadlineWorkdays())
                || !positive(snapshot.getWarehouseClaimDeadlineWorkdays())
                || !positive(snapshot.getWarehouseFirstExecutionDeadlineWorkdays())
                || !positive(snapshot.getHandoverAcceptanceDeadlineNaturalDays())
                || thresholds == null || !positive(thresholds.getAdministratorUnreadHours())
                || !positive(thresholds.getOnDutyWarehouseOperatorUnreadHours())
                || !positive(thresholds.getFirstExecutionOverdueNaturalDays())
                || !positive(thresholds.getReceiptDueUnreadHours())) {
            throw new MasterDataOperationException("BUSINESS_PARAMETER_DURATION_INVALID",
                    "Every configured duration must be positive.", 400);
        }
    }

    private Set<String> assertUnique(JsonNode values) {
        Set<String> unique = new HashSet<>();
        if (!values.isArray()) {
            throw MasterDataOperationException.invalid("Calendar values must be arrays.");
        }
        for (JsonNode value : values) {
            String text = value.asText();
            if (!unique.add(text)) {
                throw new MasterDataOperationException("BUSINESS_PARAMETER_DATE_DUPLICATE",
                        "Calendar arrays cannot contain duplicate values.", 400);
            }
        }
        return unique;
    }

    private boolean positive(Integer value) {
        return value != null && value > 0;
    }

    private Optional<BusinessParameterVersion> replay(long actorId, String operation,
            String key, String digest) {
        IdempotencyRow existing = records.findIdempotency(actorId, operation, key, clock.instant());
        if (existing == null) {
            return Optional.empty();
        }
        if (!existing.payloadSha256().equals(digest)) {
            throw MasterDataOperationException.idempotencyConflict();
        }
        return Optional.of(objectMapper.readValue(existing.responseBodyJson(),
                BusinessParameterVersion.class));
    }

    private void record(long actorId, String operation, String key, String digest,
            BusinessParameterVersion result, Instant now) {
        requireOne(records.insertIdempotency(actorId, operation, key, digest, 201,
                objectMapper.writeValueAsString(result), "BUSINESS_PARAMETER_VERSION",
                Long.parseLong(result.getId()), result.getVersion(), now,
                now.plus(java.time.Duration.ofHours(24))));
    }

    private void audit(long actorId, UUID requestId, BusinessParameterVersion before,
            BusinessParameterVersion after, Instant now) {
        requireOne(records.insertAudit(actorId, requestId.toString(), now,
                objectMapper.writeValueAsString(Map.of(
                        "action", "BUSINESS_PARAMETER_VERSION_CREATED",
                        "before", before == null ? Map.of() : before.getSnapshot(),
                        "after", after.getSnapshot()))));
    }

    private BusinessParameterVersion toVersion(BusinessParameterRow row) {
        return new BusinessParameterVersion(Long.toString(row.id()), row.version(),
                BusinessParameterVersionStatus.valueOf(row.status()),
                OffsetDateTime.ofInstant(row.effectiveAt(), ZoneOffset.UTC),
                new BusinessParameterVersionActor(Long.toString(row.actorAccountId()),
                        row.actorUsername()),
                objectMapper.readValue(row.snapshotJson(), BusinessParameterSnapshot.class));
    }

    private MasterDataOperationException versionConflict() {
        return new MasterDataOperationException("BUSINESS_PARAMETER_VERSION_CONFLICT",
                "The active parameter version changed.", 409);
    }

    private void requireOne(int count) {
        if (count != 1) {
            throw versionConflict();
        }
    }
}
