package com.fams.moduleb.fixture;

import com.fams.modulea.fixture.ModuleAMapper;

public final class CrossModuleMapperClient {

    private final ModuleAMapper mapper;

    public CrossModuleMapperClient(ModuleAMapper mapper) {
        this.mapper = mapper;
    }

    public ModuleAMapper mapper() {
        return mapper;
    }
}
