package com.fams.runtime.compatibility;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/** Repository boundary for the RuntimeProbeMapper aggregate; pass-through persistence calls only. */
@Repository
@Profile("runtime-compatibility")
class RuntimeProbeRepository {

    private final RuntimeProbeMapper mapper;

    RuntimeProbeRepository(RuntimeProbeMapper mapper) {
        this.mapper = mapper;
    }

    int insert(String probeKey, String probeValue) {
        return mapper.insert(probeKey, probeValue);
    }

    String findValue(String probeKey) {
        return mapper.findValue(probeKey);
    }
}
