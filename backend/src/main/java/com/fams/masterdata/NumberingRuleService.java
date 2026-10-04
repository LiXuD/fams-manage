package com.fams.masterdata;

import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.fams.product.contract.model.NumberingDatePart;
import com.fams.product.contract.model.NumberingMode;
import com.fams.product.contract.model.NumberingRule;
import com.fams.product.contract.model.NumberingRuleCreateRequest;
import com.fams.product.contract.model.NumberingRuleUpdateRequest;
import com.fams.product.contract.model.NumberingTarget;
import com.fams.product.contract.model.PageNumberingRule;
import com.fams.product.contract.model.ReferenceStatus;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Profile("fams-product")
@Service
class NumberingRuleService {

    private static final Duration IDEMPOTENCY_RETENTION = Duration.ofHours(24);
    private final NumberingRuleMapper mapper;
    private final IdentityMasterDataMapper records;
    private final MasterDataFingerprint fingerprint;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    NumberingRuleService(NumberingRuleMapper mapper, IdentityMasterDataMapper records,
            MasterDataFingerprint fingerprint, ObjectMapper objectMapper, Clock authClock) {
        this.mapper = mapper;
        this.records = records;
        this.fingerprint = fingerprint;
        this.objectMapper = objectMapper;
        this.clock = authClock;
    }

    PageNumberingRule list(int page, int pageSize) {
        List<NumberingRule> items = mapper.list((page - 1) * pageSize, pageSize).stream()
                .map(this::toRule).toList();
        return new PageNumberingRule(page, pageSize, mapper.count(), items);
    }

    NumberingRule get(long id) {
        return toRule(require(id));
    }

    @Transactional
    NumberingRule create(long actorId, NumberingRuleCreateRequest request,
            String key, UUID requestId) {
        String operation = "createNumberingRule";
        String digest = fingerprint.of(operation, objectMapper.writeValueAsString(request));
        Optional<NumberingRule> replay = replay(actorId, operation, key, digest);
        if (replay.isPresent()) {
            return replay.orElseThrow();
        }
        lockConfiguration();
        String code = required(request.getCode(), 64, "Rule code");
        if (mapper.findByCode(normalize(code)) != null) {
            throw codeConflict();
        }
        NumberingConfig config = validated(request.getTarget(), request.getMode(),
                request.getFixedPrefix(), request.getDatePart(), request.getSequenceLength());
        assertTargetAvailable(config.target(), null);
        Instant now = clock.instant();
        NewNumberingRuleRow row = new NewNumberingRuleRow(code, normalize(code),
                config.target(), config.mode(), config.fixedPrefix(), config.datePart(),
                config.sequenceLength(), actorId, now);
        try {
            requireOne(mapper.insert(row));
        } catch (DataIntegrityViolationException exception) {
            throw targetConflict();
        }
        NumberingRule result = get(row.getId());
        audit(actorId, requestId, "NUMBERING_RULE_CREATED", row.getId(), now);
        record(actorId, operation, key, digest, 201, result);
        return result;
    }

    @Transactional
    NumberingRule update(long actorId, long id, long expectedVersion,
            NumberingRuleUpdateRequest request, String key, UUID requestId) {
        String operation = "updateNumberingRule";
        String digest = fingerprint.of(operation, Long.toString(id), Long.toString(expectedVersion),
                objectMapper.writeValueAsString(request));
        Optional<NumberingRule> replay = replay(actorId, operation, key, digest);
        if (replay.isPresent()) {
            return replay.orElseThrow();
        }
        lockConfiguration();
        NumberingRuleRow current = requireVersion(requireForUpdate(id), expectedVersion);
        NumberingTarget target = request.getTarget() == null
                ? NumberingTarget.valueOf(current.target()) : request.getTarget();
        NumberingMode mode = request.getMode() == null
                ? NumberingMode.valueOf(current.mode()) : request.getMode();
        String fixedPrefix = mode == NumberingMode.CATEGORY_PREFIX
                ? request.getFixedPrefix()
                : request.getFixedPrefix() == null ? current.fixedPrefix() : request.getFixedPrefix();
        NumberingDatePart datePart = request.getDatePart() == null
                ? NumberingDatePart.valueOf(current.datePart()) : request.getDatePart();
        Integer requestedLength = request.getSequenceLength();
        int sequenceLength = requestedLength == null
                ? current.sequenceLength() : requestedLength.intValue();
        NumberingConfig config = validated(target, mode, fixedPrefix, datePart, sequenceLength);
        if ("ACTIVE".equals(current.status())) {
            assertTargetAvailable(config.target(), id);
        }
        Instant now = clock.instant();
        try {
            requireOne(mapper.update(id, expectedVersion, config.target(), config.mode(),
                    config.fixedPrefix(), config.datePart(), config.sequenceLength(), actorId, now));
        } catch (DataIntegrityViolationException exception) {
            throw targetConflict();
        }
        NumberingRule result = get(id);
        audit(actorId, requestId, "NUMBERING_RULE_UPDATED", id, now);
        record(actorId, operation, key, digest, 200, result);
        return result;
    }

    @Transactional
    NumberingRule changeStatus(long actorId, long id, long expectedVersion, boolean enabled,
            String reason, String key, UUID requestId) {
        String operation = enabled ? "enableNumberingRule" : "disableNumberingRule";
        String digest = fingerprint.of(operation, Long.toString(id), Long.toString(expectedVersion), reason);
        Optional<NumberingRule> replay = replay(actorId, operation, key, digest);
        if (replay.isPresent()) {
            return replay.orElseThrow();
        }
        lockConfiguration();
        required(reason, 500, "Reason");
        NumberingRuleRow current = requireVersion(requireForUpdate(id), expectedVersion);
        String status = enabled ? "ACTIVE" : "DISABLED";
        if (status.equals(current.status())) {
            throw new MasterDataOperationException(enabled
                    ? "NUMBERING_RULE_ALREADY_ENABLED" : "NUMBERING_RULE_ALREADY_DISABLED",
                    "The rule already has the requested status.", 409);
        }
        if (enabled) {
            validated(NumberingTarget.valueOf(current.target()), NumberingMode.valueOf(current.mode()),
                    current.fixedPrefix(), NumberingDatePart.valueOf(current.datePart()),
                    current.sequenceLength());
            assertTargetAvailable(current.target(), id);
        }
        Instant now = clock.instant();
        try {
            requireOne(mapper.changeStatus(id, expectedVersion, status, actorId, now));
        } catch (DataIntegrityViolationException exception) {
            throw targetConflict();
        }
        NumberingRule result = get(id);
        audit(actorId, requestId, enabled ? "NUMBERING_RULE_ENABLED" : "NUMBERING_RULE_DISABLED",
                id, now);
        record(actorId, operation, key, digest, 200, result);
        return result;
    }

    private NumberingConfig validated(NumberingTarget target, NumberingMode mode,
            String fixedPrefix, NumberingDatePart datePart, Integer sequenceLength) {
        if (target == null || mode == null || datePart == null || sequenceLength == null
                || sequenceLength < 1 || sequenceLength > 16) {
            throw MasterDataOperationException.invalid("The numbering configuration is incomplete.");
        }
        if (target == NumberingTarget.INBOUND_RECEIPT && mode == NumberingMode.CATEGORY_PREFIX) {
            throw new MasterDataOperationException("NUMBERING_MODE_NOT_ALLOWED_FOR_TARGET",
                    "Inbound receipts require enterprise numbering.", 400);
        }
        if (mode == NumberingMode.CATEGORY_PREFIX) {
            if (fixedPrefix != null) {
                throw new MasterDataOperationException("NUMBERING_RULE_FIXED_PREFIX_NOT_ALLOWED",
                        "Category-prefix mode cannot use a fixed prefix.", 400);
            }
            validateCategoryPrefixes();
        } else if (fixedPrefix != null && !fixedPrefix.isEmpty()
                && !fixedPrefix.matches("[A-Za-z0-9_-]{1,32}")) {
            throw MasterDataOperationException.invalid("The fixed prefix is invalid.");
        }
        return new NumberingConfig(target.name(), mode.name(), fixedPrefix,
                datePart.name(), sequenceLength);
    }

    private void lockConfiguration() {
        if (mapper.lockConfiguration() == null) {
            throw MasterDataOperationException.writeConflict("The numbering configuration is unavailable.");
        }
    }

    private void validateCategoryPrefixes() {
        List<String> prefixes = mapper.activeCategoryPrefixes();
        if (prefixes.isEmpty()) {
            throw prefixMissing();
        }
        Set<String> seen = new HashSet<>();
        for (String prefix : prefixes) {
            if (prefix == null || !prefix.matches("[A-Za-z0-9_-]{1,32}")) {
                throw prefixMissing();
            }
            if (!seen.add(normalize(prefix))) {
                throw new MasterDataOperationException("NUMBERING_CATEGORY_PREFIX_CONFLICT",
                        "Active category numbering prefixes are ambiguous.", 409);
            }
        }
    }

    private MasterDataOperationException prefixMissing() {
        return new MasterDataOperationException("NUMBERING_CATEGORY_PREFIX_MISSING",
                "Every active category requires a legal numbering prefix.", 409);
    }

    private void assertTargetAvailable(String target, Long currentId) {
        NumberingRuleRow existing = mapper.findActiveTarget(target);
        if (existing != null && (currentId == null || existing.id() != currentId)) {
            throw targetConflict();
        }
    }

    private MasterDataOperationException targetConflict() {
        return new MasterDataOperationException("NUMBERING_RULE_TARGET_CONFLICT",
                "An active rule already owns the target namespace.", 409);
    }

    private MasterDataOperationException codeConflict() {
        return new MasterDataOperationException("NUMBERING_RULE_CODE_CONFLICT",
                "The rule code is already in use.", 409);
    }

    private NumberingRuleRow require(long id) {
        NumberingRuleRow row = mapper.find(id);
        if (row == null) {
            throw MasterDataOperationException.notFound("The numbering rule");
        }
        return row;
    }

    private NumberingRuleRow requireForUpdate(long id) {
        NumberingRuleRow row = mapper.findForUpdate(id);
        if (row == null) {
            throw MasterDataOperationException.notFound("The numbering rule");
        }
        return row;
    }

    private NumberingRuleRow requireVersion(NumberingRuleRow row, long expectedVersion) {
        if (row.version() != expectedVersion) {
            throw MasterDataOperationException.versionConflict();
        }
        return row;
    }

    private Optional<NumberingRule> replay(long actorId, String operation, String key, String digest) {
        IdempotencyRow existing = records.findIdempotency(actorId, operation, key, clock.instant());
        if (existing == null) {
            return Optional.empty();
        }
        if (!existing.payloadSha256().equals(digest)) {
            throw MasterDataOperationException.idempotencyConflict();
        }
        return Optional.of(objectMapper.readValue(existing.responseBodyJson(), NumberingRule.class));
    }

    private void record(long actorId, String operation, String key, String digest,
            int status, NumberingRule result) {
        Instant now = clock.instant();
        requireOne(records.insertIdempotency(actorId, operation, key, digest, status,
                objectMapper.writeValueAsString(result), "NUMBERING_RULE",
                Long.parseLong(result.getId()), result.getVersion(), now,
                now.plus(IDEMPOTENCY_RETENTION)));
    }

    private void audit(long actorId, UUID requestId, String action, long targetId, Instant now) {
        requireOne(records.insertAudit(actorId, requestId.toString(), now,
                objectMapper.writeValueAsString(Map.of("action", action, "targetId", targetId))));
    }

    private NumberingRule toRule(NumberingRuleRow row) {
        NumberingRule result = new NumberingRule(Long.toString(row.id()), row.code(),
                NumberingTarget.valueOf(row.target()), NumberingMode.valueOf(row.mode()),
                NumberingDatePart.valueOf(row.datePart()), row.sequenceLength(),
                ReferenceStatus.valueOf(row.status()), row.version());
        result.setFixedPrefix(row.fixedPrefix());
        return result;
    }

    private String normalize(String value) {
        return Normalizer.normalize(value.strip(), Normalizer.Form.NFKC).toUpperCase(Locale.ROOT);
    }

    private String required(String value, int maximumLength, String field) {
        if (value == null || value.isBlank() || value.strip().length() > maximumLength) {
            throw MasterDataOperationException.invalid(field + " must be non-empty and within its limit.");
        }
        return value.strip();
    }

    private void requireOne(int count) {
        if (count != 1) {
            throw MasterDataOperationException.writeConflict("The numbering write was not applied.");
        }
    }

    private record NumberingConfig(String target, String mode, String fixedPrefix,
            String datePart, int sequenceLength) {
    }
}
