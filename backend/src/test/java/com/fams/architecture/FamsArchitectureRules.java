package com.fams.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

final class FamsArchitectureRules {

    static final ArchRule TOP_LEVEL_MODULES_ARE_ACYCLIC = slices()
            .matching("com.fams.(*)..")
            .should()
            .beFreeOfCycles()
            .because("top-level product modules must remain independently changeable");

    static final ArchRule NO_CROSS_MODULE_MAPPER_ACCESS = classes()
            .that()
            .resideInAPackage("com.fams..")
            .should(notAccessMapperInAnotherModule())
            .because("database access is owned by the Mapper's top-level module");

    static final ArchRule CONTROLLERS_ARE_NOT_TRANSACTIONAL = classes()
            .that()
            .haveSimpleNameEndingWith("Controller")
            .should()
            .notBeAnnotatedWith(Transactional.class)
            .because("transactions belong to the application service boundary");

    static final ArchRule CONTROLLER_METHODS_ARE_NOT_TRANSACTIONAL = methods()
            .that()
            .areDeclaredInClassesThat()
            .haveSimpleNameEndingWith("Controller")
            .should()
            .notBeAnnotatedWith(Transactional.class)
            .because("transactions belong to the application service boundary");

    private FamsArchitectureRules() {
    }

    private static ArchCondition<JavaClass> notAccessMapperInAnotherModule() {
        return new ArchCondition<>("not access a Mapper in another top-level module") {
            @Override
            public void check(JavaClass source, ConditionEvents events) {
                Optional<String> sourceModule = topLevelModule(source);
                if (sourceModule.isEmpty()) {
                    return;
                }
                for (Dependency dependency : source.getDirectDependenciesFromSelf()) {
                    JavaClass target = dependency.getTargetClass();
                    Optional<String> targetModule = topLevelModule(target);
                    if (
                            target.getSimpleName().endsWith("Mapper")
                                    && targetModule.isPresent()
                                    && !sourceModule.equals(targetModule)
                    ) {
                        events.add(SimpleConditionEvent.violated(
                                source,
                                dependency.getDescription()
                        ));
                    }
                }
            }
        };
    }

    private static Optional<String> topLevelModule(JavaClass type) {
        String packageName = type.getPackageName();
        String prefix = "com.fams.";
        if (!packageName.startsWith(prefix)) {
            return Optional.empty();
        }
        String remainder = packageName.substring(prefix.length());
        int separator = remainder.indexOf('.');
        String module = separator < 0 ? remainder : remainder.substring(0, separator);
        return module.isBlank() ? Optional.empty() : Optional.of(module);
    }
}
