package com.fams.masterdata;

import java.time.Instant;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
interface NumberAllocationMapper {

    @Select("SELECT numbering_prefix FROM fams_asset_category WHERE id = #{id} AND status = 'ACTIVE' FOR UPDATE")
    String activeCategoryPrefix(@Param("id") long id);

    @Insert("""
            INSERT INTO fams_number_sequence (namespace_key, last_allocated_value, updated_at)
            VALUES (#{namespace}, 0, #{now})
            ON DUPLICATE KEY UPDATE namespace_key = namespace_key
            """)
    int ensureSequence(@Param("namespace") String namespace, @Param("now") Instant now);

    @Select("SELECT last_allocated_value FROM fams_number_sequence WHERE namespace_key = #{namespace} FOR UPDATE")
    Long lockSequence(@Param("namespace") String namespace);

    @Update("""
            UPDATE fams_number_sequence SET last_allocated_value = #{lastValue}, updated_at = #{now}
             WHERE namespace_key = #{namespace} AND last_allocated_value = #{previousValue}
            """)
    int advanceSequence(@Param("namespace") String namespace,
            @Param("previousValue") long previousValue, @Param("lastValue") long lastValue,
            @Param("now") Instant now);

    @Insert("""
            INSERT INTO fams_number_allocation
                (namespace_key, sequence_value, rendered_number, state, created_at)
            VALUES (#{namespace}, #{sequence}, #{rendered}, 'RESERVED', #{now})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(NewNumberAllocationRow row);

    @Update("""
            UPDATE fams_number_allocation SET state = #{state}
             WHERE id = #{id} AND state = 'RESERVED'
            """)
    int finish(@Param("id") long id, @Param("state") String state);
}

final class NewNumberAllocationRow {
    private Long id;
    private final String namespace;
    private final long sequence;
    private final String rendered;
    private final Instant now;

    NewNumberAllocationRow(String namespace, long sequence, String rendered, Instant now) {
        this.namespace = namespace;
        this.sequence = sequence;
        this.rendered = rendered;
        this.now = now;
    }

    Long getId() { return id; }
    void setId(Long id) { this.id = id; }
    String getNamespace() { return namespace; }
    long getSequence() { return sequence; }
    String getRendered() { return rendered; }
    Instant getNow() { return now; }
}
