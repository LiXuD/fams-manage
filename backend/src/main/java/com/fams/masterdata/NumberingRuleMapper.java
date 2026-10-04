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
interface NumberingRuleMapper {

    // One persistent row serializes category mutations with rule activation and validation.
    @Select("SELECT current_version FROM fams_business_parameter_state WHERE singleton_id = 1 FOR UPDATE")
    Long lockConfiguration();

    @Select("SELECT COUNT(*) FROM fams_asset_category WHERE status = 'ACTIVE' AND id != #{id} AND numbering_prefix = #{prefix}")
    int countActivePrefixOtherThan(@Param("id") long id, @Param("prefix") String prefix);

    @Select("SELECT id, code, target, mode, fixed_prefix, date_part, sequence_length, status, version FROM fams_numbering_rule WHERE id = #{id}")
    NumberingRuleRow find(@Param("id") long id);

    @Select("SELECT id, code, target, mode, fixed_prefix, date_part, sequence_length, status, version FROM fams_numbering_rule WHERE id = #{id} FOR UPDATE")
    NumberingRuleRow findForUpdate(@Param("id") long id);

    @Select("SELECT id, code, target, mode, fixed_prefix, date_part, sequence_length, status, version FROM fams_numbering_rule WHERE code_normalized = #{code}")
    NumberingRuleRow findByCode(@Param("code") String code);

    @Select("SELECT id, code, target, mode, fixed_prefix, date_part, sequence_length, status, version FROM fams_numbering_rule WHERE target = #{target} AND status = 'ACTIVE'")
    NumberingRuleRow findActiveTarget(@Param("target") String target);

    @Select("SELECT COUNT(*) FROM fams_numbering_rule")
    long count();

    @Select("SELECT id, code, target, mode, fixed_prefix, date_part, sequence_length, status, version FROM fams_numbering_rule ORDER BY id LIMIT #{limit} OFFSET #{offset}")
    List<NumberingRuleRow> list(@Param("offset") int offset, @Param("limit") int limit);

    @Select("SELECT numbering_prefix FROM fams_asset_category WHERE status = 'ACTIVE' ORDER BY id")
    List<String> activeCategoryPrefixes();

    @Insert("""
            INSERT INTO fams_numbering_rule
                (code, code_normalized, target, mode, fixed_prefix, date_part,
                 sequence_length, status, version, created_at, updated_at, created_by, updated_by)
            VALUES (#{code}, #{normalizedCode}, #{target}, #{mode}, #{fixedPrefix},
                    #{datePart}, #{sequenceLength}, 'ACTIVE', 0,
                    #{now}, #{now}, #{actorId}, #{actorId})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(NewNumberingRuleRow row);

    @Update("""
            UPDATE fams_numbering_rule SET target = #{target}, mode = #{mode},
                   fixed_prefix = #{fixedPrefix}, date_part = #{datePart},
                   sequence_length = #{sequenceLength}, version = version + 1,
                   updated_at = #{now}, updated_by = #{actorId}
             WHERE id = #{id} AND version = #{version}
            """)
    int update(@Param("id") long id, @Param("version") long version,
            @Param("target") String target, @Param("mode") String mode,
            @Param("fixedPrefix") String fixedPrefix, @Param("datePart") String datePart,
            @Param("sequenceLength") int sequenceLength, @Param("actorId") long actorId,
            @Param("now") Instant now);

    @Update("""
            UPDATE fams_numbering_rule SET status = #{status}, version = version + 1,
                   updated_at = #{now}, updated_by = #{actorId}
             WHERE id = #{id} AND version = #{version}
            """)
    int changeStatus(@Param("id") long id, @Param("version") long version,
            @Param("status") String status, @Param("actorId") long actorId,
            @Param("now") Instant now);
}
