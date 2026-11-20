package com.fams.masterdata;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
interface NotificationChannelMapper {

    @Select("SELECT current_version, current_version_id FROM fams_notification_channel_state WHERE singleton_id = 1 FOR UPDATE")
    NotificationChannelState lockState();

    @Select("SELECT current_version, current_version_id FROM fams_notification_channel_state WHERE singleton_id = 1")
    NotificationChannelState state();

    @Select("SELECT id, version, status, sms_template, wechat_template, actor_account_id, actor_username, created_at FROM fams_notification_channel_version WHERE id = #{id}")
    NotificationChannelRow find(@Param("id") long id);

    @Select("SELECT username FROM fams_system_account WHERE id = #{id}")
    String accountUsername(@Param("id") long id);

    @Update("UPDATE fams_notification_channel_version SET status = 'SUPERSEDED' WHERE id = #{id} AND status = 'ACTIVE'")
    int supersede(@Param("id") long id);

    @Insert("""
            INSERT INTO fams_notification_channel_version
                (version, status, sms_template, wechat_template, actor_account_id,
                 actor_username, created_at)
            VALUES (#{version}, 'ACTIVE', #{smsTemplate}, #{wechatTemplate},
                    #{actorAccountId}, #{actorUsername}, #{createdAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(NewNotificationChannelRow row);

    @Update("""
            UPDATE fams_notification_channel_state
               SET current_version = #{version}, current_version_id = #{id}
             WHERE singleton_id = 1 AND current_version = #{expectedVersion}
            """)
    int activate(@Param("expectedVersion") long expectedVersion,
            @Param("version") long version, @Param("id") long id);
}
