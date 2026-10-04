package com.fams.runtime.compatibility;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("runtime-compatibility")
@Service
public class RuntimeCompatibilityService {

    private final RuntimeProbeMapper probeMapper;

    public RuntimeCompatibilityService(RuntimeProbeMapper probeMapper) {
        this.probeMapper = probeMapper;
    }

    @Transactional
    public void writeCommitted(String probeKey, String probeValue) {
        probeMapper.insert(probeKey, probeValue);
    }

    public String read(String probeKey) {
        try {
            return probeMapper.findValue(probeKey);
        } catch (EmptyResultDataAccessException ignored) {
            return null;
        }
    }

    @Transactional
    public void writeAndReject(String probeKey) {
        probeMapper.insert(probeKey + "-first", "partial-first");
        probeMapper.insert(probeKey + "-second", "partial-second");
        throw new RuntimeCompatibilityRejectedException(probeKey);
    }
}
