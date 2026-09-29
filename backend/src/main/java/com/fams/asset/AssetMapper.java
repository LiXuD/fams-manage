package com.fams.asset;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.SelectProvider;
import org.apache.ibatis.annotations.Update;

@Mapper
interface AssetMapper {
    @Select("SELECT * FROM fams_asset WHERE id = #{id}")
    Map<String, Object> find(@Param("id") long id);

    @Select("SELECT * FROM fams_asset WHERE id = #{id} FOR UPDATE")
    Map<String, Object> lock(@Param("id") long id);

    @org.apache.ibatis.annotations.SelectProvider(type = AssetSearchSql.class, method = "list")
    List<Map<String, Object>> list(@Param("actorId") long actorId,
            @Param("filters") Map<String, Object> filters,
            @Param("limit") int limit, @Param("offset") int offset);

    @org.apache.ibatis.annotations.SelectProvider(type = AssetSearchSql.class, method = "count")
    long count(@Param("actorId") long actorId,
            @Param("filters") Map<String, Object> filters);

    @SelectProvider(type = AssetReferenceBatchSql.class, method = "select")
    List<Map<String, Object>> referenceViews(@Param("kind") String kind,
            @Param("ids") List<Long> ids);

    @Insert("""
            INSERT INTO fams_asset
              (asset_code, name, category_id, model, unit, owning_organization_id,
               planned_inbound_warehouse_id, planned_inbound_location_id, brand_id,
               manufacturer_id, serial_number, serial_number_normalized,
               acquisition_date, warranty_expiry_date, service_provider, service_contact,
               service_phone_ciphertext,
               source_text, notes, extension_values_json, lifecycle_status,
               exception_status, card_stage, created_business_date, parameter_version_id,
               creator_account_id, creator_username_snapshot, source_asset_id,
               version, created_at, updated_at, created_by, updated_by)
            VALUES
              (#{code}, #{name}, #{categoryId}, #{model}, #{unit}, #{organizationId},
               #{warehouseId}, #{locationId}, #{brandId}, #{manufacturerId},
               #{serial}, #{serialNormalized}, #{acquisitionDate}, #{warrantyDate},
               #{provider}, #{contact}, #{phoneCiphertext}, #{source}, #{notes}, #{extensionJson},
               'WAITING_INBOUND', 'NORMAL', 'DRAFT', #{businessDate}, #{parameterId},
               #{actorId}, #{username}, #{sourceAssetId}, 0, #{now}, #{now},
               #{actorId}, #{actorId})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Map<String, Object> values);

    @Insert("""
            INSERT INTO fams_asset
              (asset_code,name,category_id,model,unit,owning_organization_id,
               serial_number,serial_number_normalized,lifecycle_status,exception_status,
               card_stage,custody_type,storage_warehouse_id,current_location_id,
               using_organization_id,responsible_employee_id,department_responsible_employee_id,
               created_business_date,inbound_business_date,parameter_version_id,
               creator_account_id,creator_username_snapshot,source_text,version,
               created_at,updated_at,created_by,updated_by)
            VALUES
              (#{code},#{name},#{categoryId},#{model},#{unit},#{organizationId},
               #{serial},#{serialNormalized},#{lifecycle},'NORMAL',
               'ACTIVE',#{custody},#{warehouseId},#{locationId},
               #{usingOrganizationId},#{employeeId},#{departmentEmployeeId},
               #{businessDate},#{businessDate},#{parameterId},
               #{actorId},#{username},'INITIAL_IMPORT',0,
               #{now},#{now},#{actorId},#{actorId})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertInitial(Map<String, Object> values);

    @Update("""
            UPDATE fams_asset SET name = #{name}, category_id = #{categoryId},
              model = #{model}, unit = #{unit}, owning_organization_id = #{organizationId},
              planned_inbound_warehouse_id = #{warehouseId},
              planned_inbound_location_id = #{locationId}, brand_id = #{brandId},
              manufacturer_id = #{manufacturerId}, serial_number = #{serial},
              serial_number_normalized = #{serialNormalized},
              acquisition_date = #{acquisitionDate}, warranty_expiry_date = #{warrantyDate},
              service_provider = #{provider}, service_contact = #{contact},
              service_phone_ciphertext = #{phoneCiphertext},
              source_text = #{source}, notes = #{notes},
              extension_values_json = #{extensionJson}, version = version + 1,
              updated_at = #{now}, updated_by = #{actorId}
             WHERE id = #{id} AND version = #{version}
            """)
    int update(Map<String, Object> values);

    @Update("""
            UPDATE fams_asset SET card_stage = 'CANCELLED', cancelled_at = #{now},
              cancellation_reason = #{reason}, version = version + 1,
              updated_at = #{now}, updated_by = #{actorId}
             WHERE id = #{id} AND version = #{version} AND card_stage = 'DRAFT'
            """)
    int cancel(@Param("id") long id, @Param("version") long version,
            @Param("reason") String reason, @Param("actorId") long actorId,
            @Param("now") Instant now);

    @Update("""
            UPDATE fams_asset SET card_stage='CANCELLED',custody_type=NULL,
              storage_warehouse_id=NULL,current_location_id=NULL,using_organization_id=NULL,
              responsible_employee_id=NULL,department_responsible_employee_id=NULL,
              cancelled_at=#{now},cancellation_reason=#{reason},version=version+1,
              updated_at=#{now},updated_by=#{actorId}
             WHERE id=#{id} AND version=0 AND card_stage='ACTIVE'
            """)
    int cancelInitial(@Param("id") long id, @Param("reason") String reason,
            @Param("actorId") long actorId, @Param("now") Instant now);

    @Select("SELECT COUNT(*) FROM fams_asset_business_lock WHERE asset_id = #{id} AND released_at IS NULL")
    int activeLock(@Param("id") long assetId);

    @Select("SELECT id, version, status FROM fams_asset_category WHERE id = #{id} FOR UPDATE")
    Map<String, Object> category(@Param("id") long id);

    @Select("SELECT id, version, status FROM fams_asset_category WHERE id = #{id}")
    Map<String, Object> categoryView(@Param("id") long id);

    @Select("SELECT extension_template_json FROM fams_asset_category WHERE id = #{id}")
    String categoryTemplate(@Param("id") long id);

    @Select("SELECT id, version, status FROM fams_organization WHERE id = #{id} FOR UPDATE")
    Map<String, Object> organization(@Param("id") long id);

    @Select("SELECT id, version, status FROM fams_organization WHERE id = #{id}")
    Map<String, Object> organizationView(@Param("id") long id);

    @Select("SELECT id, version, status FROM fams_warehouse WHERE id = #{id} FOR UPDATE")
    Map<String, Object> warehouse(@Param("id") long id);

    @Select("SELECT id, version, status FROM fams_warehouse WHERE id = #{id}")
    Map<String, Object> warehouseView(@Param("id") long id);

    @Select("SELECT id, version, status, warehouse_id FROM fams_location WHERE id = #{id} FOR UPDATE")
    Map<String, Object> location(@Param("id") long id);

    @Select("SELECT id, version, status, warehouse_id FROM fams_location WHERE id = #{id}")
    Map<String, Object> locationView(@Param("id") long id);

    @Select("SELECT id, version, status FROM fams_brand WHERE id = #{id} FOR UPDATE")
    Map<String, Object> brand(@Param("id") long id);

    @Select("SELECT id, version, status FROM fams_brand WHERE id = #{id}")
    Map<String, Object> brandView(@Param("id") long id);

    @Select("SELECT id, version, status FROM fams_manufacturer WHERE id = #{id} FOR UPDATE")
    Map<String, Object> manufacturer(@Param("id") long id);

    @Select("SELECT id, version, status FROM fams_manufacturer WHERE id = #{id}")
    Map<String, Object> manufacturerView(@Param("id") long id);

    @Select("SELECT id, version, status FROM fams_employee WHERE id = #{id}")
    Map<String, Object> employee(@Param("id") long id);

    @Select("SELECT token_hash, token_ciphertext, tag_version, issued_at FROM fams_asset_tag WHERE asset_id = #{id}")
    Map<String, Object> tag(@Param("id") long assetId);

    @Select("SELECT asset_id FROM fams_asset_tag WHERE token_hash = #{hash}")
    Long assetIdByTokenHash(@Param("hash") String hash);

    @Insert("""
            INSERT INTO fams_asset_tag(asset_id, token_ciphertext, token_hash, tag_version, issued_at)
            VALUES (#{assetId}, #{ciphertext}, #{hash}, 0, #{now})
            """)
    int insertTag(@Param("assetId") long assetId, @Param("ciphertext") byte[] ciphertext,
            @Param("hash") String hash, @Param("now") Instant now);

    @Update("""
            UPDATE fams_asset_tag SET token_ciphertext = #{ciphertext}, token_hash = #{hash},
              tag_version = tag_version + 1, rotated_at = #{now}
             WHERE asset_id = #{assetId} AND tag_version = #{version}
            """)
    int rotateTag(@Param("assetId") long assetId, @Param("version") long version,
            @Param("ciphertext") byte[] ciphertext, @Param("hash") String hash,
            @Param("now") Instant now);

    @Insert("""
            INSERT INTO fams_asset_tag_history(asset_id, token_hash, valid_from, invalidated_at, reason)
            VALUES (#{assetId}, #{hash}, #{validFrom}, #{now}, #{reason})
            """)
    int archiveTag(@Param("assetId") long assetId, @Param("hash") String hash,
            @Param("validFrom") Instant validFrom, @Param("now") Instant now,
            @Param("reason") String reason);

    @org.apache.ibatis.annotations.Delete("DELETE FROM fams_asset_tag WHERE asset_id=#{id}")
    int deleteTag(@Param("id") long assetId);

    @Update("UPDATE fams_asset SET version = version + 1, updated_at = #{now}, updated_by = #{actorId} WHERE id = #{id} AND version = #{version}")
    int touch(@Param("id") long assetId, @Param("version") long version,
            @Param("actorId") long actorId, @Param("now") Instant now);

    @Insert("""
            INSERT INTO fams_asset_timeline_event
              (asset_id, event_type, business_type, business_id, occurred_at, summary_json, actor_account_id)
            VALUES (#{assetId}, #{event}, 'ASSET', #{assetId}, #{now}, #{summary}, #{actorId})
            """)
    int event(@Param("assetId") long assetId, @Param("event") String event,
            @Param("now") Instant now, @Param("summary") String summary,
            @Param("actorId") long actorId);

    @Select("SELECT * FROM fams_asset_timeline_event WHERE asset_id = #{id} ORDER BY occurred_at DESC, id DESC LIMIT #{limit} OFFSET #{offset}")
    List<Map<String, Object>> timeline(@Param("id") long assetId, @Param("limit") int limit,
            @Param("offset") int offset);

    @Select("SELECT COUNT(*) FROM fams_asset_timeline_event WHERE asset_id = #{id}")
    long timelineCount(@Param("id") long assetId);

    @Select("SELECT response_body_json, payload_sha256 FROM fams_idempotency_record WHERE actor_account_id = #{actorId} AND operation_id = #{operation} AND idempotency_key = #{key} AND expires_at > #{now}")
    Map<String, Object> replay(@Param("actorId") long actorId, @Param("operation") String operation,
            @Param("key") String key, @Param("now") Instant now);

    @Insert("""
            INSERT INTO fams_idempotency_record
              (actor_account_id, operation_id, idempotency_key, payload_sha256,
               result_status, http_status, response_body_json, resource_type,
               resource_id, resource_version, created_at, completed_at, expires_at)
            VALUES (#{actorId}, #{operation}, #{key}, #{digest}, 'SUCCEEDED', #{status},
                    #{response}, 'ASSET', #{resourceId}, #{resourceVersion}, #{now}, #{now}, #{expiry})
            """)
    int record(@Param("actorId") long actorId, @Param("operation") String operation,
            @Param("key") String key, @Param("digest") String digest,
            @Param("status") int status, @Param("response") String response,
            @Param("resourceId") Long resourceId, @Param("resourceVersion") Long resourceVersion,
            @Param("now") Instant now, @Param("expiry") Instant expiry);

    @Insert("""
            INSERT INTO fams_asset_tag_print_job
              (requester_account_id, job_type, job_status, template_version, created_at, completed_at)
            VALUES (#{actorId}, #{jobType}, 'COMPLETED', 'v1', #{now}, #{now})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertPrintJob(Map<String, Object> values);

    @Insert("""
            INSERT INTO fams_asset_tag_print_item
              (print_job_id, asset_id, asset_version, tag_version, asset_code_snapshot,
               asset_name_snapshot, token_ciphertext_snapshot, item_status)
            VALUES (#{jobId}, #{assetId}, #{assetVersion}, #{tagVersion}, #{assetCode},
                    #{assetName}, #{tokenCiphertext}, 'COMPLETED')
            """)
    int insertPrintItem(@Param("jobId") long jobId, @Param("assetId") long assetId,
            @Param("assetVersion") long assetVersion, @Param("tagVersion") long tagVersion,
            @Param("assetCode") String assetCode, @Param("assetName") String assetName,
            @Param("tokenCiphertext") byte[] tokenCiphertext);

    @Select("SELECT * FROM fams_asset_tag_print_job WHERE id = #{id} AND requester_account_id = #{actorId}")
    Map<String, Object> printJob(@Param("id") long id, @Param("actorId") long actorId);

    @Select("SELECT * FROM fams_asset_tag_print_item WHERE print_job_id = #{id} ORDER BY id")
    List<Map<String, Object>> printItems(@Param("id") long id);

    @Select("SELECT * FROM fams_asset_saved_filter WHERE owner_account_id = #{ownerId} ORDER BY id DESC LIMIT #{limit} OFFSET #{offset}")
    List<Map<String, Object>> savedFilters(@Param("ownerId") long ownerId,
            @Param("limit") int limit, @Param("offset") int offset);

    @Select("SELECT COUNT(*) FROM fams_asset_saved_filter WHERE owner_account_id = #{ownerId}")
    long savedFilterCount(@Param("ownerId") long ownerId);

    @Select("SELECT * FROM fams_asset_saved_filter WHERE id = #{id} AND owner_account_id = #{ownerId} FOR UPDATE")
    Map<String, Object> savedFilter(@Param("id") long id, @Param("ownerId") long ownerId);

    @Insert("""
            INSERT INTO fams_asset_saved_filter
              (owner_account_id, name, name_normalized, criteria_json, version, created_at, updated_at)
            VALUES (#{ownerId}, #{name}, #{normalized}, #{criteria}, 0, #{now}, #{now})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertSavedFilter(Map<String, Object> values);

    @Update("""
            UPDATE fams_asset_saved_filter SET name = #{name}, name_normalized = #{normalized},
              criteria_json = #{criteria}, version = version + 1, updated_at = #{now}
             WHERE id = #{id} AND owner_account_id = #{ownerId} AND version = #{version}
            """)
    int updateSavedFilter(Map<String, Object> values);

    @org.apache.ibatis.annotations.Delete("DELETE FROM fams_asset_saved_filter WHERE id = #{id} AND owner_account_id = #{ownerId} AND version = #{version}")
    int deleteSavedFilter(@Param("id") long id, @Param("ownerId") long ownerId,
            @Param("version") long version);

    @Insert("""
            INSERT INTO fams_security_event
              (account_id, event_type, request_id, occurred_at, detail_json)
            VALUES (#{actorId}, #{event}, #{requestId}, #{now}, #{details})
            """)
    int audit(@Param("actorId") long actorId, @Param("event") String event,
            @Param("requestId") String requestId, @Param("now") Instant now,
            @Param("details") String details);
}
