package io.graphus.parser;

import io.graphus.model.CallGraph;
import io.graphus.model.ClassNode;
import io.graphus.model.FieldNode;
import io.graphus.model.GuiceStereotype;
import io.graphus.model.MethodNode;
import io.graphus.model.SpringStereotype;
import io.graphus.model.SymbolKind;
import io.graphus.model.SymbolNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.jetbrains.kotlin.psi.KtFile;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KotlinSymbolVisitorTest {

    private static KotlinPsiEnvironment env;

    @BeforeAll
    static void setUp() {
        env = new KotlinPsiEnvironment();
    }

    @AfterAll
    static void tearDown() {
        env.close();
    }

    @Test
    void emitsClassMethodAndPropertyForRestController(@TempDir Path tempDir) throws IOException {
        Path source = writeFile(
                tempDir,
                "GreetingController.kt",
                """
                package demo

                @org.springframework.web.bind.annotation.RestController
                class GreetingController(val message: String) {
                    @org.springframework.web.bind.annotation.GetMapping("/hello")
                    fun hello(): String = message
                }
                """);

        CallGraph callGraph = parseInto(tempDir, source);

        ClassNode controller = (ClassNode) callGraph.getNode("demo.GreetingController");
        assertNotNull(controller);
        assertEquals(SpringStereotype.CONTROLLER, controller.getSpringMetadata().getStereotype());
        assertTrue(controller.getMethods().stream().anyMatch(id -> id.contains("hello(")));
        assertTrue(controller.getFields().stream().anyMatch(id -> id.endsWith("#message")));

        FieldNode message = (FieldNode) callGraph.getNode("demo.GreetingController#message");
        assertNotNull(message);
        assertEquals("val String", message.getType());

        MethodNode hello = (MethodNode) callGraph.getNode("demo.GreetingController.hello()");
        assertNotNull(hello);
        assertEquals(SymbolKind.METHOD, hello.getKind());
        assertEquals(1, hello.getSpringMetadata().getHttpMappings().size());
        assertEquals("/hello", hello.getSpringMetadata().getHttpMappings().get(0).path());
    }

    @Test
    void emitsDataClassWithProperties(@TempDir Path tempDir) throws IOException {
        Path source = writeFile(
                tempDir,
                "User.kt",
                """
                package demo
                data class User(val name: String, var age: Int)
                """);

        CallGraph callGraph = parseInto(tempDir, source);

        ClassNode user = (ClassNode) callGraph.getNode("demo.User");
        assertNotNull(user);

        FieldNode name = (FieldNode) callGraph.getNode("demo.User#name");
        FieldNode age = (FieldNode) callGraph.getNode("demo.User#age");
        assertEquals("val String", name.getType());
        assertEquals("var Int", age.getType());
    }

    @Test
    void emitsObjectAndCompanionAsClassNode(@TempDir Path tempDir) throws IOException {
        Path source = writeFile(
                tempDir,
                "Singleton.kt",
                """
                package demo
                object Counter {
                    var value: Int = 0
                    fun increment() { value++ }
                }
                """);

        CallGraph callGraph = parseInto(tempDir, source);
        ClassNode counter = (ClassNode) callGraph.getNode("demo.Counter");
        assertNotNull(counter);
        MethodNode increment = (MethodNode) callGraph.getNode("demo.Counter.increment()");
        assertNotNull(increment);
        FieldNode value = (FieldNode) callGraph.getNode("demo.Counter#value");
        assertEquals("var Int", value.getType());
    }

    @Test
    void detectsGuiceSingletonAndModuleSupertype(@TempDir Path tempDir) throws IOException {
        Path service = writeFile(
                tempDir,
                "Service.kt",
                """
                package demo
                @com.google.inject.Singleton
                class Service
                """);
        Path module = writeFile(
                tempDir,
                "Module.kt",
                """
                package demo
                class GreeterModule : com.google.inject.AbstractModule()
                """);

        CallGraph callGraph = parseInto(tempDir, service, module);

        ClassNode svc = (ClassNode) callGraph.getNode("demo.Service");
        assertNotNull(svc);
        // Singleton applies to Guice metadata via class annotation.
        assertTrue(svc.getGuiceMetadata().isSingleton());

        ClassNode mod = (ClassNode) callGraph.getNode("demo.GreeterModule");
        assertNotNull(mod);
        assertEquals(GuiceStereotype.MODULE, mod.getGuiceMetadata().getStereotype());
    }

    @Test
    void emitsTopLevelFunctionUnderFileFacade(@TempDir Path tempDir) throws IOException {
        Path source = writeFile(
                tempDir,
                "MathUtils.kt",
                """
                package demo
                fun square(value: Int): Int = value * value
                """);

        CallGraph callGraph = parseInto(tempDir, source);

        ClassNode facade = (ClassNode) callGraph.getNode("demo.MathUtilsKt");
        assertNotNull(facade);
        assertTrue(facade.getMethods().stream().anyMatch(id -> id.contains("square(")));

        SymbolNode squareNode = callGraph.getNode("demo.MathUtilsKt.square(Int)");
        assertNotNull(squareNode);
        assertTrue(squareNode instanceof MethodNode);
    }

    private static Path writeFile(Path dir, String name, String contents) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, contents);
        return file;
    }

    private static CallGraph parseInto(Path repoRoot, Path... sources) throws IOException {
        CallGraph graph = new CallGraph();
        KotlinParserContext ctx = new KotlinParserContext(graph);
        KotlinSymbolVisitor visitor = new KotlinSymbolVisitor(new KotlinAnnotationExtractor(), repoRoot);
        for (Path source : sources) {
            KtFile file = env.parse(source);
            visitor.visit(file, ctx);
        }
        return graph;
    }

    @SuppressWarnings("unused")
    private static String describeMethods(CallGraph graph) {
        return String.join(",\n", graph.getNodes().stream()
                .map(SymbolNode::getId)
                .toList()
                .toArray(new String[0]));
    }

    @SuppressWarnings("unused")
    private static List<String> ids(CallGraph graph) {
        return graph.getNodes().stream().map(SymbolNode::getId).toList();
    }
}
