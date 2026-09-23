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
interface BrandMapper {

    @Select("""
            SELECT b.id, b.code, b.name, b.description, b.default_manufacturer_id,
                   m.version AS default_manufacturer_version,
                   m.status AS default_manufacturer_status, b.status, b.version
              FROM fams_brand b
              LEFT JOIN fams_manufacturer m ON m.id = b.default_manufacturer_id
             WHERE b.id = #{id}
            """)
    BrandRow find(@Param("id") long id);

    @Select("""
            SELECT b.id, b.code, b.name, b.description, b.default_manufacturer_id,
                   m.version AS default_manufacturer_version,
                   m.status AS default_manufacturer_status, b.status, b.version
              FROM fams_brand b
              LEFT JOIN fams_manufacturer m ON m.id = b.default_manufacturer_id
             WHERE b.code_normalized = #{code}
            """)
    BrandRow findByCode(@Param("code") String normalizedCode);

    @Select("SELECT COUNT(*) FROM fams_brand")
    long count();

    @Select("SELECT COUNT(*) FROM fams_brand WHERE status = 'ACTIVE'")
    long countActive();

    @Select("""
            SELECT b.id, b.code, b.name, b.description, b.default_manufacturer_id,
                   m.version AS default_manufacturer_version,
                   m.status AS default_manufacturer_status, b.status, b.version
              FROM fams_brand b
              LEFT JOIN fams_manufacturer m ON m.id = b.default_manufacturer_id
             ORDER BY b.id LIMIT #{limit} OFFSET #{offset}
            """)
    List<BrandRow> list(@Param("offset") int offset, @Param("limit") int limit);

    @Select("""
            SELECT b.id, b.code, b.name, b.description, b.default_manufacturer_id,
                   m.version AS default_manufacturer_version,
                   m.status AS default_manufacturer_status, b.status, b.version
              FROM fams_brand b
              LEFT JOIN fams_manufacturer m ON m.id = b.default_manufacturer_id
             WHERE b.status = 'ACTIVE'
             ORDER BY b.id LIMIT #{limit} OFFSET #{offset}
            """)
    List<BrandRow> listActive(@Param("offset") int offset, @Param("limit") int limit);

    @Insert("""
            INSERT INTO fams_brand
                (code, code_normalized, name, default_manufacturer_id, description,
                 status, version, created_at, updated_at, created_by, updated_by)
            VALUES (#{code}, #{normalizedCode}, #{name}, #{defaultManufacturerId},
                    #{description}, 'ACTIVE', 0, #{now}, #{now}, #{actorId}, #{actorId})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(NewBrandRow row);

    @Update("""
            UPDATE fams_brand
               SET code = #{code}, code_normalized = #{normalizedCode}, name = #{name},
                   default_manufacturer_id = #{defaultManufacturerId}, description = #{description},
                   version = version + 1, updated_at = #{now}, updated_by = #{actorId}
             WHERE id = #{id} AND version = #{version}
            """)
    int update(
            @Param("id") long id,
            @Param("version") long version,
            @Param("code") String code,
            @Param("normalizedCode") String normalizedCode,
            @Param("name") String name,
            @Param("defaultManufacturerId") Long defaultManufacturerId,
            @Param("description") String description,
            @Param("actorId") long actorId,
            @Param("now") Instant now);

    @Update("""
            UPDATE fams_brand SET status = #{status}, version = version + 1,
                   updated_at = #{now}, updated_by = #{actorId}
             WHERE id = #{id} AND version = #{version}
            """)
    int changeStatus(
            @Param("id") long id,
            @Param("version") long version,
            @Param("status") String status,
            @Param("actorId") long actorId,
            @Param("now") Instant now);
}
