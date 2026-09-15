package com.fams.moduleb.fixture;

import com.fams.modulea.fixture.ModuleACycle;

public final class ModuleBCycle {

    private final ModuleACycle collaborator;

    public ModuleBCycle(ModuleACycle collaborator) {
        this.collaborator = collaborator;
    }

    public ModuleACycle collaborator() {
        return collaborator;
    }
}
