package com.fams.masterdata;

import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
interface BusinessParameterMapper {

    @Select("SELECT current_version, current_version_id FROM fams_business_parameter_state WHERE singleton_id = 1 FOR UPDATE")
    BusinessParameterState lockState();

    @Select("SELECT current_version, current_version_id FROM fams_business_parameter_state WHERE singleton_id = 1")
    BusinessParameterState state();

    @Select("SELECT id, version, status, effective_at, actor_account_id, actor_username, snapshot_json FROM fams_business_parameter_version WHERE id = #{id}")
    BusinessParameterRow find(@Param("id") long id);

    @Select("SELECT COUNT(*) FROM fams_business_parameter_version")
    long count();

    @Select("SELECT id, version, status, effective_at, actor_account_id, actor_username, snapshot_json FROM fams_business_parameter_version ORDER BY version DESC LIMIT #{limit} OFFSET #{offset}")
    List<BusinessParameterRow> list(@Param("offset") int offset, @Param("limit") int limit);

    @Select("SELECT username FROM fams_system_account WHERE id = #{id}")
    String accountUsername(@Param("id") long id);

    @Update("UPDATE fams_business_parameter_version SET status = 'SUPERSEDED' WHERE id = #{id} AND status = 'ACTIVE'")
    int supersede(@Param("id") long id);

    @Insert("""
            INSERT INTO fams_business_parameter_version
                (version, status, effective_at, actor_account_id, actor_username,
                 snapshot_json, created_at)
            VALUES (#{version}, 'ACTIVE', #{effectiveAt}, #{actorAccountId},
                    #{actorUsername}, #{snapshotJson}, #{effectiveAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(NewBusinessParameterRow row);

    @Update("""
            UPDATE fams_business_parameter_state
               SET current_version = #{version}, current_version_id = #{id}
             WHERE singleton_id = 1 AND current_version = #{expectedVersion}
            """)
    int activate(@Param("expectedVersion") long expectedVersion,
            @Param("version") long version, @Param("id") long id);
}
