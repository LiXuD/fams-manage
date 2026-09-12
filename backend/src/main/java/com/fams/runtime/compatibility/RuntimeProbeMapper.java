package com.fams.runtime.compatibility;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.springframework.context.annotation.Profile;

@Profile("runtime-compatibility")
@Mapper
interface RuntimeProbeMapper {

    @Insert("""
            INSERT INTO d6_02b_runtime_probe (probe_key, probe_value)
            VALUES (#{probeKey}, #{probeValue})
            """)
    int insert(@Param("probeKey") String probeKey, @Param("probeValue") String probeValue);

    @Select("""
            SELECT probe_value
            FROM d6_02b_runtime_probe
            WHERE probe_key = #{probeKey}
            """)
    String findValue(@Param("probeKey") String probeKey);
}
