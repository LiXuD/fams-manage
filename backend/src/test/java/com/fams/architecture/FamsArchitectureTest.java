package com.fams.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

class FamsArchitectureTest {

    private final JavaClasses productionClasses = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("com.fams");

    @Test
    void productionModulesAreAcyclic() {
        FamsArchitectureRules.TOP_LEVEL_MODULES_ARE_ACYCLIC.check(productionClasses);
    }

    @Test
    void productionModulesDoNotReachAcrossMapperBoundaries() {
        FamsArchitectureRules.NO_CROSS_MODULE_MAPPER_ACCESS.check(productionClasses);
    }

    @Test
    void controllersDoNotOwnTransactions() {
        FamsArchitectureRules.CONTROLLERS_ARE_NOT_TRANSACTIONAL.check(productionClasses);
        FamsArchitectureRules.CONTROLLER_METHODS_ARE_NOT_TRANSACTIONAL.check(
                productionClasses
        );
    }

    @Test
    void servicesDoNotDeclareClassLevelTransactions() {
        FamsArchitectureRules.SERVICES_DO_NOT_DECLARE_CLASS_LEVEL_TRANSACTIONS.check(
                productionClasses
        );
    }

    @Test
    void servicesDoNotReachMybatisMappers() {
        FamsArchitectureRules.SERVICES_DO_NOT_REACH_MYBATIS_MAPPERS.check(productionClasses);
    }

    @Test
    void transactionalMethodsArePublic() {
        FamsArchitectureRules.TRANSACTIONAL_METHODS_ARE_PUBLIC.check(productionClasses);
    }

    @Test
    void applicationEntryPointIsInTheRootPackage() {
        FamsArchitectureRules.APPLICATION_ENTRY_POINT_IS_IN_THE_ROOT_PACKAGE.check(
                productionClasses
        );
    }
}
