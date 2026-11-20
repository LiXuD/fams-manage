package com.fams.masterdata;

/** Current channel templates shared by delivery workers and the configuration anomaly scan. */
public interface NotificationChannelConfigPort {

    Templates currentTemplates();

    record Templates(String smsTemplate, String wechatTemplate) {
        public static Templates unconfigured() {
            return new Templates("", "");
        }
    }
}
