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
interface AssetCategoryMapper {

    @Select("""
            SELECT c.id, c.code, c.name, c.parent_id, p.version AS parent_version,
                   p.status AS parent_status, c.description, c.suggested_unit,
                   c.numbering_prefix, c.extension_template_json, c.status, c.version
              FROM fams_asset_category c
              LEFT JOIN fams_asset_category p ON p.id = c.parent_id
             WHERE c.id = #{id}
            """)
    AssetCategoryRow find(@Param("id") long id);

    @Select("""
            SELECT c.id, c.code, c.name, c.parent_id, p.version AS parent_version,
                   p.status AS parent_status, c.description, c.suggested_unit,
                   c.numbering_prefix, c.extension_template_json, c.status, c.version
              FROM fams_asset_category c
              LEFT JOIN fams_asset_category p ON p.id = c.parent_id
             WHERE c.id = #{id} FOR UPDATE
            """)
    AssetCategoryRow findForUpdate(@Param("id") long id);

    @Select("""
            SELECT c.id, c.code, c.name, c.parent_id, p.version AS parent_version,
                   p.status AS parent_status, c.description, c.suggested_unit,
                   c.numbering_prefix, c.extension_template_json, c.status, c.version
              FROM fams_asset_category c
              LEFT JOIN fams_asset_category p ON p.id = c.parent_id
             WHERE c.code_normalized = #{code}
            """)
    AssetCategoryRow findByCode(@Param("code") String normalizedCode);

    @Select("SELECT COUNT(*) FROM fams_asset_category")
    long count();

    @Select("SELECT COUNT(*) FROM fams_asset_category WHERE status = 'ACTIVE'")
    long countActive();

    @Select("""
            SELECT c.id, c.code, c.name, c.parent_id, p.version AS parent_version,
                   p.status AS parent_status, c.description, c.suggested_unit,
                   c.numbering_prefix, c.extension_template_json, c.status, c.version
              FROM fams_asset_category c
              LEFT JOIN fams_asset_category p ON p.id = c.parent_id
             ORDER BY c.id LIMIT #{limit} OFFSET #{offset}
            """)
    List<AssetCategoryRow> list(@Param("offset") int offset, @Param("limit") int limit);

    @Select("""
            SELECT c.id, c.code, c.name, c.parent_id, p.version AS parent_version,
                   p.status AS parent_status, c.description, c.suggested_unit,
                   c.numbering_prefix, c.extension_template_json, c.status, c.version
              FROM fams_asset_category c
              LEFT JOIN fams_asset_category p ON p.id = c.parent_id
             WHERE c.status = 'ACTIVE'
             ORDER BY c.id LIMIT #{limit} OFFSET #{offset}
            """)
    List<AssetCategoryRow> listActive(@Param("offset") int offset, @Param("limit") int limit);

    @Insert("""
            INSERT INTO fams_asset_category
                (code, code_normalized, name, parent_id, description, suggested_unit,
                 numbering_prefix, extension_template_json, status, version,
                 created_at, updated_at, created_by, updated_by)
            VALUES (#{code}, #{normalizedCode}, #{name}, #{parentId}, #{description},
                    #{suggestedUnit}, #{numberingPrefix}, #{extensionTemplateJson},
                    'ACTIVE', 0, #{now}, #{now}, #{actorId}, #{actorId})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(NewAssetCategoryRow row);

    @Update("""
            UPDATE fams_asset_category
               SET code = #{code}, code_normalized = #{normalizedCode}, name = #{name},
                   parent_id = #{parentId}, description = #{description},
                   suggested_unit = #{suggestedUnit}, numbering_prefix = #{numberingPrefix},
                   extension_template_json = #{extensionTemplateJson}, version = version + 1,
                   updated_at = #{now}, updated_by = #{actorId}
             WHERE id = #{id} AND version = #{version}
            """)
    int update(@Param("id") long id, @Param("version") long version,
            @Param("code") String code, @Param("normalizedCode") String normalizedCode,
            @Param("name") String name, @Param("parentId") Long parentId,
            @Param("description") String description, @Param("suggestedUnit") String suggestedUnit,
            @Param("numberingPrefix") String numberingPrefix,
            @Param("extensionTemplateJson") String extensionTemplateJson,
            @Param("actorId") long actorId, @Param("now") Instant now);

    @Update("""
            UPDATE fams_asset_category SET status = #{status}, version = version + 1,
                   updated_at = #{now}, updated_by = #{actorId}
             WHERE id = #{id} AND version = #{version}
            """)
    int changeStatus(@Param("id") long id, @Param("version") long version,
            @Param("status") String status, @Param("actorId") long actorId,
            @Param("now") Instant now);
}
