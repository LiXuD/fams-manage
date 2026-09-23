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

    @Select("SELECT COUNT(*) FROM fams_location WHERE parent_id = #{id} AND status = 'ACTIVE'")
    long countActiveChildren(@Param("id") long id);

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
