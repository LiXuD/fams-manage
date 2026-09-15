package com.fams.modulea.fixture;

import com.fams.moduleb.fixture.ModuleBCycle;

public final class ModuleACycle {

    private final ModuleBCycle collaborator;

    public ModuleACycle(ModuleBCycle collaborator) {
        this.collaborator = collaborator;
    }

    public ModuleBCycle collaborator() {
        return collaborator;
    }
}
