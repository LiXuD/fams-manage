package com.fams.masterdata;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import com.fams.product.contract.model.NotificationChannelConfig;
import com.fams.product.contract.model.NotificationChannelConfigReplaceRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Profile("fams-product")
@Service
class NotificationChannelConfigService implements NotificationChannelConfigPort {

    private static final Duration IDEMPOTENCY_RETENTION = Duration.ofHours(24);
    private static final Pattern TEMPLATE = Pattern.compile("[A-Za-z0-9_-]{1,80}");

    private final NotificationChannelRepository notificationChannelRepository;
    private final IdentityMasterDataRepository identityMasterDataRepository;
    private final MasterDataFingerprint fingerprint;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    NotificationChannelConfigService(NotificationChannelRepository notificationChannelRepository,
            IdentityMasterDataRepository identityMasterDataRepository,
            MasterDataFingerprint fingerprint,
            ObjectMapper objectMapper, Clock authClock) {
        this.notificationChannelRepository = notificationChannelRepository;
        this.identityMasterDataRepository = identityMasterDataRepository;
        this.fingerprint = fingerprint;
        this.objectMapper = objectMapper;
        this.clock = authClock;
    }

    @Transactional(propagation = Propagation.REQUIRED, readOnly = true)
    public NotificationChannelConfig current() {
        NotificationChannelState state = notificationChannelRepository.state();
        if (state == null || state.currentVersionId() == null) {
            return new NotificationChannelConfig("", "", 0L);
        }
        return toConfig(require(state.currentVersionId()));
    }

    @Override
    public Templates currentTemplates() {
        NotificationChannelState state = notificationChannelRepository.state();
        if (state == null || state.currentVersionId() == null) {
            return Templates.unconfigured();
        }
        NotificationChannelRow row = require(state.currentVersionId());
        return new Templates(row.smsTemplate(), row.wechatTemplate());
    }

    @Transactional
    public NotificationChannelConfig replace(long actorId, long expectedVersion,
            NotificationChannelConfigReplaceRequest request, String key, UUID requestId) {
        String operation = "replaceNotificationChannels";
        String digest = fingerprint.of(operation, Long.toString(expectedVersion),
                objectMapper.writeValueAsString(request));
        Optional<NotificationChannelConfig> replay = replay(actorId, operation, key, digest);
        if (replay.isPresent()) {
            return replay.orElseThrow();
        }
        String smsTemplate = template(request.getSmsTemplate());
        String wechatTemplate = template(request.getWechatTemplate());
        NotificationChannelState state = notificationChannelRepository.lockState();
        if (state == null || state.currentVersion() != expectedVersion) {
            throw versionConflict();
        }
        String actorUsername = notificationChannelRepository.accountUsername(actorId);
        if (actorUsername == null) {
            throw MasterDataOperationException.notFound("The administrator account");
        }
        NotificationChannelRow before = state.currentVersionId() == null
                ? null : require(state.currentVersionId());
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        if (before != null && notificationChannelRepository.supersede(before.id()) != 1) {
            throw versionConflict();
        }
        NewNotificationChannelRow row = new NewNotificationChannelRow(expectedVersion + 1,
                smsTemplate, wechatTemplate, actorId, actorUsername, now);
        requireOne(notificationChannelRepository.insert(row));
        requireOne(notificationChannelRepository.activate(expectedVersion, row.getVersion(), row.getId()));
        NotificationChannelConfig result = toConfig(require(row.getId()));
        audit(actorId, requestId, before, result, now);
        record(actorId, operation, key, digest, result, row.getId(), now);
        return result;
    }

    private String template(String value) {
        if (value == null) {
            throw MasterDataOperationException.invalid("Both channel templates are required.");
        }
        String stripped = value.strip();
        if (!stripped.isEmpty() && !TEMPLATE.matcher(stripped).matches()) {
            throw new MasterDataOperationException("NOTIFICATION_CHANNEL_TEMPLATE_INVALID",
                    "A channel template must be empty or match [A-Za-z0-9_-]{1,80}.", 400);
        }
        return stripped;
    }

    private NotificationChannelRow require(long id) {
        NotificationChannelRow row = notificationChannelRepository.find(id);
        if (row == null) {
            throw MasterDataOperationException.notFound("The channel-configuration version");
        }
        return row;
    }

    private Optional<NotificationChannelConfig> replay(long actorId, String operation,
            String key, String digest) {
        IdempotencyRow existing = identityMasterDataRepository.findIdempotency(
                actorId, operation, key, clock.instant());
        if (existing == null) {
            return Optional.empty();
        }
        if (!existing.payloadSha256().equals(digest)) {
            throw MasterDataOperationException.idempotencyConflict();
        }
        return Optional.of(objectMapper.readValue(existing.responseBodyJson(),
                NotificationChannelConfig.class));
    }

    private void record(long actorId, String operation, String key, String digest,
            NotificationChannelConfig result, long resourceId, Instant now) {
        requireOne(identityMasterDataRepository.insertIdempotency(actorId, operation, key, digest, 201,
                objectMapper.writeValueAsString(result), "NOTIFICATION_CHANNEL_CONFIG",
                resourceId, result.getVersion(), now, now.plus(IDEMPOTENCY_RETENTION)));
    }

    private void audit(long actorId, UUID requestId, NotificationChannelRow before,
            NotificationChannelConfig after, Instant now) {
        requireOne(identityMasterDataRepository.insertAudit(actorId, requestId.toString(), now,
                objectMapper.writeValueAsString(Map.of(
                        "action", "NOTIFICATION_CHANNEL_CONFIG_REPLACED",
                        "before", before == null ? Map.of()
                                : Map.of("smsTemplate", before.smsTemplate(),
                                        "wechatTemplate", before.wechatTemplate()),
                        "after", Map.of("smsTemplate", after.getSmsTemplate(),
                                "wechatTemplate", after.getWechatTemplate())))));
    }

    private NotificationChannelConfig toConfig(NotificationChannelRow row) {
        NotificationChannelConfig result = new NotificationChannelConfig(
                row.smsTemplate(), row.wechatTemplate(), row.version());
        result.setUpdatedAt(OffsetDateTime.ofInstant(row.createdAt(), ZoneOffset.UTC));
        result.setActorAccountId(Long.toString(row.actorAccountId()));
        result.setActorUsername(row.actorUsername());
        return result;
    }

    private MasterDataOperationException versionConflict() {
        return new MasterDataOperationException("NOTIFICATION_CHANNEL_VERSION_CONFLICT",
                "The current channel-configuration version changed.", 409);
    }

    private void requireOne(int count) {
        if (count != 1) {
            throw versionConflict();
        }
    }
}
