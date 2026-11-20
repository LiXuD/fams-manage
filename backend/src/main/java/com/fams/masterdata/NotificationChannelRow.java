package com.fams.masterdata;

import java.time.Instant;

record NotificationChannelRow(long id, long version, String status, String smsTemplate,
        String wechatTemplate, long actorAccountId, String actorUsername, Instant createdAt) {
}

record NotificationChannelState(long currentVersion, Long currentVersionId) {
}
