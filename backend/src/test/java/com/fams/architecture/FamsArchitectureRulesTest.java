package com.fams.architecture;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

class FamsArchitectureRulesTest {

    private final JavaClasses violatingFixtures = new ClassFileImporter()
            .importPackages("com.fams.modulea.fixture", "com.fams.moduleb.fixture");

    @Test
    void cycleRuleRejectsAnIntentionalModuleCycle() {
        assertThrows(
                AssertionError.class,
                () -> FamsArchitectureRules.TOP_LEVEL_MODULES_ARE_ACYCLIC.check(
                        violatingFixtures
                )
        );
    }

    @Test
    void mapperRuleRejectsIntentionalCrossModuleAccess() {
        assertThrows(
                AssertionError.class,
                () -> FamsArchitectureRules.NO_CROSS_MODULE_MAPPER_ACCESS.check(
                        violatingFixtures
                )
        );
    }

    @Test
    void transactionRulesRejectIntentionalControllerTransactions() {
        assertThrows(
                AssertionError.class,
                () -> FamsArchitectureRules.CONTROLLERS_ARE_NOT_TRANSACTIONAL.check(
                        violatingFixtures
                )
        );
        assertThrows(
                AssertionError.class,
                () -> FamsArchitectureRules.CONTROLLER_METHODS_ARE_NOT_TRANSACTIONAL.check(
                        violatingFixtures
                )
        );
    }
}
