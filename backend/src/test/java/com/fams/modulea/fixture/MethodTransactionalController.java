package com.fams.modulea.fixture;

import org.springframework.transaction.annotation.Transactional;

public final class MethodTransactionalController {

    @Transactional
    public void execute() {
    }
}
