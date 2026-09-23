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
interface ManufacturerMapper {

    @Select("SELECT id, code, name, description, status, version FROM fams_manufacturer WHERE id = #{id}")
    ManufacturerRow find(@Param("id") long id);

    @Select("SELECT id, code, name, description, status, version FROM fams_manufacturer WHERE code_normalized = #{code}")
    ManufacturerRow findByCode(@Param("code") String normalizedCode);

    @Select("SELECT COUNT(*) FROM fams_manufacturer")
    long count();

    @Select("SELECT id, code, name, description, status, version FROM fams_manufacturer ORDER BY id LIMIT #{limit} OFFSET #{offset}")
    List<ManufacturerRow> list(@Param("offset") int offset, @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM fams_manufacturer WHERE status = 'ACTIVE'")
    long countActive();

    @Select("SELECT id, code, name, description, status, version FROM fams_manufacturer WHERE status = 'ACTIVE' ORDER BY id LIMIT #{limit} OFFSET #{offset}")
    List<ManufacturerRow> listActive(@Param("offset") int offset, @Param("limit") int limit);

    @Insert("""
            INSERT INTO fams_manufacturer
                (code, code_normalized, name, description, status, version,
                 created_at, updated_at, created_by, updated_by)
            VALUES (#{code}, #{normalizedCode}, #{name}, #{description}, 'ACTIVE', 0,
                    #{now}, #{now}, #{actorId}, #{actorId})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(NewManufacturerRow row);

    @Update("""
            UPDATE fams_manufacturer
               SET code = #{code}, code_normalized = #{normalizedCode},
                   name = #{name}, description = #{description},
                   version = version + 1, updated_at = #{now}, updated_by = #{actorId}
             WHERE id = #{id} AND version = #{version}
            """)
    int update(
            @Param("id") long id,
            @Param("version") long version,
            @Param("code") String code,
            @Param("normalizedCode") String normalizedCode,
            @Param("name") String name,
            @Param("description") String description,
            @Param("actorId") long actorId,
            @Param("now") Instant now);

    @Update("""
            UPDATE fams_manufacturer
               SET status = #{status}, version = version + 1,
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
