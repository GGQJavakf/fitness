package com.aifitness.assistant.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(
        packages = "com.aifitness.assistant",
        importOptions = ImportOption.DoNotIncludeTests.class)
class ModuleBoundaryTest {

    @ArchTest
    static final ArchRule domainDependsOnlyOnDomainAndJavaPackages = classes()
            .that().resideInAnyPackage("..domain..")
            .should().onlyDependOnClassesThat().resideInAnyPackage("java..", "com.aifitness.assistant..domain..")
            .because("domain code has an explicit allowlist and remains independent from outer frameworks and adapters");

    @ArchTest
    static final ArchRule domainDoesNotDependOnOuterApplicationLayers = noClasses()
            .that().resideInAnyPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage("..api..", "..application..", "..infrastructure..")
            .because("domain is the innermost backend layer");

    @ArchTest
    static final ArchRule applicationDoesNotDependOnApiOrInfrastructure = noClasses()
            .that().resideInAnyPackage("..application..")
            .should().dependOnClassesThat().resideInAnyPackage("..api..", "..infrastructure..")
            .because("application coordinates domain ports without depending on adapters")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule apiDoesNotDependOnInfrastructure = noClasses()
            .that().resideInAnyPackage("..api..")
            .should().dependOnClassesThat().resideInAnyPackage("..infrastructure..")
            .because("API adapters call application services rather than infrastructure implementations");

    @ArchTest
    static final ArchRule infrastructureDoesNotDependOnApi = noClasses()
            .that().resideInAnyPackage("..infrastructure..")
            .should().dependOnClassesThat().resideInAnyPackage("..api..")
            .because("infrastructure implements inner ports and is not coupled to transport adapters")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule planApiUsesPlanContractsInsteadOfRulesInternals = noClasses()
            .that().resideInAPackage("com.aifitness.assistant.plan.api..")
            .should().dependOnClassesThat().resideInAPackage("com.aifitness.assistant.rules.domain..")
            .because("the plan transport boundary exposes plan-domain contracts, not rule-engine internals");
}
