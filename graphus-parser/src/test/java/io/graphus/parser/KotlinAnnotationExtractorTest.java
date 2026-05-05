package io.graphus.parser;

import io.graphus.model.GuiceMetadata;
import io.graphus.model.GuiceStereotype;
import io.graphus.model.HttpMapping;
import io.graphus.model.InjectionType;
import io.graphus.model.SpringMetadata;
import io.graphus.model.SpringStereotype;
import java.util.List;
import org.jetbrains.kotlin.psi.KtAnnotationEntry;
import org.jetbrains.kotlin.psi.KtClass;
import org.jetbrains.kotlin.psi.KtFile;
import org.jetbrains.kotlin.psi.KtNamedFunction;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KotlinAnnotationExtractorTest {

    private static KotlinPsiEnvironment env;
    private static final KotlinAnnotationExtractor extractor = new KotlinAnnotationExtractor();

    @BeforeAll
    static void setUp() {
        env = new KotlinPsiEnvironment();
    }

    @AfterAll
    static void tearDown() {
        env.close();
    }

    @Test
    void detectsRestControllerAndGetMapping() {
        KtFile file = parse(
                "demo.kt",
                """
                package demo

                @org.springframework.web.bind.annotation.RestController
                class Greeter {
                    @org.springframework.web.bind.annotation.GetMapping("/hello")
                    fun hello(): String = "hi"
                }
                """);

        KtClass clazz = (KtClass) file.getDeclarations().get(0);
        SpringMetadata classMetadata = extractor.extractSpring(clazz.getAnnotationEntries());
        assertEquals(SpringStereotype.CONTROLLER, classMetadata.getStereotype());

        KtNamedFunction function = (KtNamedFunction) clazz.getBody().getFunctions().get(0);
        SpringMetadata methodMetadata = extractor.extractSpring(function.getAnnotationEntries());
        assertEquals(1, methodMetadata.getHttpMappings().size());
        HttpMapping mapping = methodMetadata.getHttpMappings().get(0);
        assertEquals("GET", mapping.method());
        assertEquals("/hello", mapping.path());
    }

    @Test
    void detectsTransactionalAndAsyncAndScheduled() {
        KtFile file = parse(
                "behavior.kt",
                """
                package demo
                @org.springframework.transaction.annotation.Transactional
                @org.springframework.scheduling.annotation.Async
                @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 1000)
                class Behaviour
                """);
        KtClass clazz = (KtClass) file.getDeclarations().get(0);
        SpringMetadata metadata = extractor.extractSpring(clazz.getAnnotationEntries());
        assertTrue(metadata.isTransactional());
        assertTrue(metadata.isAsync());
        assertTrue(metadata.isScheduled());
    }

    @Test
    void detectsGuiceSingletonInjectAndNamed() {
        KtFile file = parse(
                "guice.kt",
                """
                package demo

                @com.google.inject.Singleton
                class GreetingService {
                    @com.google.inject.Inject
                    constructor(@com.google.inject.name.Named("salutation") salutation: String)
                }
                """);
        KtClass clazz = (KtClass) file.getDeclarations().get(0);
        GuiceMetadata classMetadata =
                extractor.extractGuiceForClass(clazz.getAnnotationEntries(), List.of());
        assertTrue(classMetadata.isSingleton());

        List<KtAnnotationEntry> ctorAnnotations = clazz.getSecondaryConstructors().get(0).getAnnotationEntries();
        GuiceMetadata constructorMetadata = extractor.extractGuice(ctorAnnotations, InjectionType.CONSTRUCTOR);
        assertEquals(InjectionType.CONSTRUCTOR, constructorMetadata.getInjectionType());

        List<KtAnnotationEntry> paramAnnotations =
                clazz.getSecondaryConstructors().get(0).getValueParameters().get(0).getAnnotationEntries();
        GuiceMetadata paramMetadata = extractor.extractGuice(paramAnnotations, InjectionType.NONE);
        assertEquals("salutation", paramMetadata.getNamedValue());
    }

    @Test
    void detectsGuiceModuleViaSupertype() {
        KtFile file = parse(
                "module.kt",
                """
                package demo
                class GreeterModule : com.google.inject.AbstractModule()
                """);
        KtClass clazz = (KtClass) file.getDeclarations().get(0);
        GuiceMetadata withSupertype =
                extractor.extractGuiceForClass(clazz.getAnnotationEntries(), List.of("AbstractModule"));
        assertEquals(GuiceStereotype.MODULE, withSupertype.getStereotype());

        GuiceMetadata withoutSupertype =
                extractor.extractGuiceForClass(clazz.getAnnotationEntries(), List.of("Object"));
        assertFalse(withoutSupertype.getStereotype() == GuiceStereotype.MODULE);
    }

    private static KtFile parse(String fileName, String text) {
        try {
            java.nio.file.Path tempFile = java.nio.file.Files.createTempFile(fileName, ".kt");
            java.nio.file.Files.writeString(tempFile, text);
            return env.parse(tempFile);
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }
}
