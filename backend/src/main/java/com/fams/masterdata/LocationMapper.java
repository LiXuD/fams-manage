package com.fams.masterdata;

import java.time.Instant;
import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
interface LocationMapper {

    @Select("""
            SELECT l.id, l.code, l.name, l.location_type, l.parent_id,
                   p.version AS parent_version, p.status AS parent_status,
                   l.warehouse_id, w.version AS warehouse_version,
                   w.status AS warehouse_status, l.description, l.status, l.version
              FROM fams_location l
              LEFT JOIN fams_location p ON p.id = l.parent_id
              LEFT JOIN fams_warehouse w ON w.id = l.warehouse_id
             WHERE l.id = #{id}
            """)
    LocationRow find(@Param("id") long id);

    @Select("""
            SELECT l.id, l.code, l.name, l.location_type, l.parent_id,
                   p.version AS parent_version, p.status AS parent_status,
                   l.warehouse_id, w.version AS warehouse_version,
                   w.status AS warehouse_status, l.description, l.status, l.version
              FROM fams_location l
              LEFT JOIN fams_location p ON p.id = l.parent_id
              LEFT JOIN fams_warehouse w ON w.id = l.warehouse_id
             WHERE l.id = #{id} FOR UPDATE
            """)
    LocationRow findForUpdate(@Param("id") long id);

    @Select("""
            SELECT l.id, l.code, l.name, l.location_type, l.parent_id,
                   p.version AS parent_version, p.status AS parent_status,
                   l.warehouse_id, w.version AS warehouse_version,
                   w.status AS warehouse_status, l.description, l.status, l.version
              FROM fams_location l
              LEFT JOIN fams_location p ON p.id = l.parent_id
              LEFT JOIN fams_warehouse w ON w.id = l.warehouse_id
             WHERE l.code_normalized = #{code}
            """)
    LocationRow findByCode(@Param("code") String code);

    @Select("SELECT COUNT(*) FROM fams_location")
    long count();

    @Select("""
            SELECT COUNT(*) FROM fams_location l
              LEFT JOIN fams_warehouse w ON w.id = l.warehouse_id
             WHERE l.status = 'ACTIVE'
               AND (l.warehouse_id IS NULL OR w.status = 'ACTIVE')
            """)
    long countActive();

    @Select("SELECT id FROM fams_location WHERE parent_id = #{id} AND status = 'ACTIVE' LIMIT 1 FOR UPDATE")
    Long findActiveChildIdForUpdate(@Param("id") long id);

    @Select("""
            SELECT id FROM fams_asset
             WHERE card_stage = 'ACTIVE' AND current_location_id = #{id}
             LIMIT 1 FOR UPDATE
            """)
    Long findActiveAssetReferenceIdForUpdate(@Param("id") long id);

    @Select("""
            SELECT id FROM fams_asset
             WHERE card_stage IN ('DRAFT', 'PENDING_INBOUND')
               AND planned_inbound_location_id = #{id}
             LIMIT 1 FOR UPDATE
            """)
    Long findPlannedAssetReferenceIdForUpdate(@Param("id") long id);

    @Select("""
            SELECT i.id FROM fams_inbound_receipt_item i
              JOIN fams_inbound_receipt r ON r.id = i.receipt_id
             WHERE i.target_location_id = #{id}
               AND r.receipt_status IN ('DRAFT', 'PENDING_ADMIN_DECISION',
                   'WAITING_WAREHOUSE_CLAIM', 'WAREHOUSE_PROCESSING')
               AND i.execution_status = 'PENDING'
             LIMIT 1 FOR UPDATE
            """)
    Long findOpenInboundReferenceIdForUpdate(@Param("id") long id);

    @Select("SELECT COUNT(*) FROM fams_location WHERE parent_id = #{id}")
    long countChildren(@Param("id") long id);

    @Select("""
            SELECT l.id, l.code, l.name, l.location_type, l.parent_id,
                   p.version AS parent_version, p.status AS parent_status,
                   l.warehouse_id, w.version AS warehouse_version,
                   w.status AS warehouse_status, l.description, l.status, l.version
              FROM fams_location l
              LEFT JOIN fams_location p ON p.id = l.parent_id
              LEFT JOIN fams_warehouse w ON w.id = l.warehouse_id
             ORDER BY l.id LIMIT #{limit} OFFSET #{offset}
            """)
    List<LocationRow> list(@Param("offset") int offset, @Param("limit") int limit);

    @Select("""
            SELECT l.id, l.code, l.name, l.location_type, l.parent_id,
                   p.version AS parent_version, p.status AS parent_status,
                   l.warehouse_id, w.version AS warehouse_version,
                   w.status AS warehouse_status, l.description, l.status, l.version
              FROM fams_location l
              LEFT JOIN fams_location p ON p.id = l.parent_id
              LEFT JOIN fams_warehouse w ON w.id = l.warehouse_id
             WHERE l.status = 'ACTIVE'
               AND (l.warehouse_id IS NULL OR w.status = 'ACTIVE')
             ORDER BY l.id LIMIT #{limit} OFFSET #{offset}
            """)
    List<LocationRow> listActive(@Param("offset") int offset, @Param("limit") int limit);

    @Insert("""
            INSERT INTO fams_location
                (code, code_normalized, name, location_type, parent_id, warehouse_id,
                 description, status, version, created_at, updated_at, created_by, updated_by)
            VALUES (#{code}, #{normalizedCode}, #{name}, #{locationType}, #{parentId},
                    #{warehouseId}, #{description}, 'ACTIVE', 0,
                    #{now}, #{now}, #{actorId}, #{actorId})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(NewLocationRow row);

    @Update("""
            UPDATE fams_location SET code = #{code}, code_normalized = #{normalizedCode},
                   name = #{name}, location_type = #{locationType}, parent_id = #{parentId},
                   warehouse_id = #{warehouseId}, description = #{description},
                   version = version + 1, updated_at = #{now}, updated_by = #{actorId}
             WHERE id = #{id} AND version = #{version}
            """)
    int update(@Param("id") long id, @Param("version") long version,
            @Param("code") String code, @Param("normalizedCode") String normalizedCode,
            @Param("name") String name, @Param("locationType") String locationType,
            @Param("parentId") Long parentId, @Param("warehouseId") Long warehouseId,
            @Param("description") String description, @Param("actorId") long actorId,
            @Param("now") Instant now);

    @Update("""
            UPDATE fams_location SET status = #{status}, version = version + 1,
                   updated_at = #{now}, updated_by = #{actorId}
             WHERE id = #{id} AND version = #{version}
            """)
    int changeStatus(@Param("id") long id, @Param("version") long version,
            @Param("status") String status, @Param("actorId") long actorId,
            @Param("now") Instant now);
}
