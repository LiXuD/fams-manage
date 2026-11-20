package com.fams.masterdata;

import java.time.Instant;

final class NewNotificationChannelRow {
    private Long id;
    private final long version;
    private final String smsTemplate;
    private final String wechatTemplate;
    private final long actorAccountId;
    private final String actorUsername;
    private final Instant createdAt;

    NewNotificationChannelRow(long version, String smsTemplate, String wechatTemplate,
            long actorAccountId, String actorUsername, Instant createdAt) {
        this.version = version;
        this.smsTemplate = smsTemplate;
        this.wechatTemplate = wechatTemplate;
        this.actorAccountId = actorAccountId;
        this.actorUsername = actorUsername;
        this.createdAt = createdAt;
    }

    Long getId() { return id; }
    void setId(Long id) { this.id = id; }
    long getVersion() { return version; }
    String getSmsTemplate() { return smsTemplate; }
    String getWechatTemplate() { return wechatTemplate; }
    long getActorAccountId() { return actorAccountId; }
    String getActorUsername() { return actorUsername; }
    Instant getCreatedAt() { return createdAt; }
}
