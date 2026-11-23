package com.fams.runtime.compatibility;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("runtime-compatibility")
@Service
public class RuntimeCompatibilityService {

    private final RuntimeProbeRepository probeRepository;

    public RuntimeCompatibilityService(RuntimeProbeRepository probeRepository) {
        this.probeRepository = probeRepository;
    }

    @Transactional
    public void writeCommitted(String probeKey, String probeValue) {
        probeRepository.insert(probeKey, probeValue);
    }

    public String read(String probeKey) {
        try {
            return probeRepository.findValue(probeKey);
        } catch (EmptyResultDataAccessException ignored) {
            return null;
        }
    }

    @Transactional
    public void writeAndReject(String probeKey) {
        probeRepository.insert(probeKey + "-first", "partial-first");
        probeRepository.insert(probeKey + "-second", "partial-second");
        throw new RuntimeCompatibilityRejectedException(probeKey);
    }
}
