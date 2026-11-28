package org.apvgakasinay;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

/** Module boundaries and safety rules, checked on the compiled classes. */
@AnalyzeClasses(packages = "org.apvgakasinay", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule casesInternalsArePrivate = internalsOf("cases");

    @ArchTest
    static final ArchRule ledgerInternalsArePrivate = internalsOf("ledger");

    @ArchTest
    static final ArchRule mediaInternalsArePrivate = internalsOf("media");

    @ArchTest
    static final ArchRule siteInternalsArePrivate = internalsOf("site");

    @ArchTest
    static final ArchRule identityInternalsArePrivate = internalsOf("identity");

    @ArchTest
    static final ArchRule auditInternalsArePrivate = internalsOf("audit");

    @ArchTest
    static final ArchRule donationsInternalsArePrivate = internalsOf("donations");

    @ArchTest
    static final ArchRule publicModulesDoNotKnowDonations = noClasses()
            .that().resideInAnyPackage("org.apvgakasinay.site..", "org.apvgakasinay.cases..", "org.apvgakasinay.ledger..",
                    "org.apvgakasinay.media..", "org.apvgakasinay.identity..", "org.apvgakasinay.audit..")
            .should().dependOnClassesThat().resideInAPackage("org.apvgakasinay.donations..")
            .because("the donations module builds on the others; none of them knows it exists");

    @ArchTest
    static final ArchRule publicModulesDoNotKnowIdentity = noClasses()
            .that().resideInAnyPackage("org.apvgakasinay.site..", "org.apvgakasinay.cases..", "org.apvgakasinay.ledger..", "org.apvgakasinay.media..")
            .should().dependOnClassesThat().resideInAnyPackage("org.apvgakasinay.identity..", "org.apvgakasinay.audit..")
            .because("public read responses never depend on who is asking");

    @ArchTest
    static final ArchRule auditDoesNotKnowIdentity = noClasses()
            .that().resideInAPackage("org.apvgakasinay.audit..")
            .should().dependOnClassesThat().resideInAPackage("org.apvgakasinay.identity..")
            .because("the audit log records events for any module and depends on none");

    @ArchTest
    static final ArchRule sharedDependsOnNoModule = noClasses()
            .that().resideInAPackage("org.apvgakasinay.shared..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.apvgakasinay.cases..", "org.apvgakasinay.site..", "org.apvgakasinay.identity..", "org.apvgakasinay.audit..", "org.apvgakasinay.ledger..", "org.apvgakasinay.media..", "org.apvgakasinay.fixtures..",
                    "org.apvgakasinay.donations..")
            .because("shared is plumbing used by every module");

    @ArchTest
    static final ArchRule ledgerDoesNotKnowCases = noClasses()
            .that().resideInAPackage("org.apvgakasinay.ledger..")
            .should().dependOnClassesThat().resideInAnyPackage("org.apvgakasinay.cases..", "org.apvgakasinay.site..")
            .because("the ledger is the foundation; cases and site build on it, not the reverse");

    @ArchTest
    static final ArchRule nothingDependsOnFixtures = noClasses()
            .that().resideOutsideOfPackage("org.apvgakasinay.fixtures..")
            .should().dependOnClassesThat().resideInAPackage("org.apvgakasinay.fixtures..")
            .because("fixture loading is a development tool the application must work without");

    @ArchTest
    static final ArchRule fixtureBeansExistOnlyInDevAndTest = classes()
            .that().resideInAPackage("org.apvgakasinay.fixtures..")
            .and().areAnnotatedWith(org.springframework.stereotype.Component.class)
            .should().beAnnotatedWith(Profile.class)
            .because("production must never load fictional data");

    @ArchTest
    static final ArchRule noMutationEndpoints = noMethods()
            .that().areDeclaredInClassesThat().areAnnotatedWith(RestController.class)
            .and().areDeclaredInClassesThat().resideOutsideOfPackages("org.apvgakasinay.identity..", "org.apvgakasinay.donations..")
            .should().beAnnotatedWith(PostMapping.class)
            .orShould().beAnnotatedWith(PutMapping.class)
            .orShould().beAnnotatedWith(PatchMapping.class)
            .orShould().beAnnotatedWith(DeleteMapping.class)
            .because("only the identity and donations modules have operations that change something; every other module is read-only");

    @ArchTest
    static final ArchRule everyDonationOperationRunsThroughTheOrderOfChecks = com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods()
            .that().areDeclaredInClassesThat().resideInAPackage("org.apvgakasinay.donations..")
            .and().areDeclaredInClassesThat().areAnnotatedWith(RestController.class)
            .and().areAnnotatedWith(org.springframework.web.bind.annotation.GetMapping.class)
            .or().areAnnotatedWith(PostMapping.class)
            .and().areDeclaredInClassesThat().resideInAPackage("org.apvgakasinay.donations..")
            .should().beAnnotatedWith(org.apvgakasinay.shared.ApiOperation.class)
            .because("an operation outside the pipeline of identity-and-security.md 4.3 would skip its checks");

    @ArchTest
    static final ArchRule readServicesRunInOneSnapshot = classes()
            .that().areAnnotatedWith(Service.class)
            .should().beAnnotatedWith(Transactional.class)
            .because("every number in a response must come from one database snapshot");

    @ArchTest
    static final ArchRule controllersDoNotTouchTheDatabase = noClasses()
            .that().areAnnotatedWith(RestController.class)
            .and().resideOutsideOfPackage("org.apvgakasinay.media.internal..")
            .should().dependOnClassesThat().resideInAPackage("org.springframework.jdbc..")
            .because("controllers return public response types built by services");

    private static ArchRule internalsOf(String module) {
        return noClasses()
                .that().resideOutsideOfPackage("org.apvgakasinay." + module + "..")
                .should().dependOnClassesThat().resideInAPackage("org.apvgakasinay." + module + ".internal..")
                .because("a module's internal package is private to that module");
    }
}
