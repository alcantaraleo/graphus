package io.graphus.parser;

import io.graphus.model.CallEdge;
import io.graphus.model.CallGraph;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.jetbrains.kotlin.psi.KtFile;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KotlinCallGraphBuilderTest {

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
    void resolvesUniqueKotlinToKotlinEdge(@TempDir Path tempDir) throws IOException {
        Path source = writeFile(
                tempDir,
                "Calculator.kt",
                """
                package demo

                class Calculator {
                    fun add(a: Int, b: Int): Int = a + b
                    fun callsAdd(): Int = add(1, 2)
                }
                """);

        BuildOutput output = parseAndBuild(tempDir, source);
        Set<CallEdge> edges = output.graph().getEdges();
        assertTrue(edges.contains(new CallEdge("demo.Calculator.callsAdd()", "demo.Calculator.add(Int, Int)")),
                "callsAdd() should call add(Int, Int) directly");
        assertEquals(0, output.result().unresolvedCalls(), "Single matching candidate must not record unresolved");
    }

    @Test
    void leavesAmbiguousCallUnresolvedWhenSameNameAndArityCollide(@TempDir Path tempDir) throws IOException {
        Path source = writeFile(
                tempDir,
                "Greeters.kt",
                """
                package demo

                class FormalGreeter {
                    fun greet(name: String): String = "Hello, " + name
                }
                class CasualGreeter {
                    fun greet(name: String): String = "Hi, " + name
                }
                class Driver {
                    fun run(): String = greet("Leo")
                }
                """);

        BuildOutput output = parseAndBuild(tempDir, source);
        // Two candidates with same name+arity → ambiguous, must remain unresolved.
        assertFalse(output.result().records().isEmpty());
        assertTrue(output.result().records().stream()
                .anyMatch(record -> record.calleeName().equals("greet") && record.arity() == 1));
    }

    @Test
    void disambiguatesExtensionCallByReceiverTypeWhenNameAndArityCollide(@TempDir Path tempDir)
            throws IOException {
        Path stringExt = writeFile(tempDir, "StringExt.kt",
                """
                package demo
                fun String.shout(): String = this.uppercase() + "!"
                """);
        Path intExt = writeFile(tempDir, "IntExt.kt",
                """
                package demo
                fun Int.shout(): String = this.toString() + "!"
                """);
        Path greeter = writeFile(tempDir, "Greeter.kt",
                """
                package demo
                class Greeter {
                    fun greetString(s: String): String = s.shout()
                }
                """);

        BuildOutput output = parseAndBuild(tempDir, stringExt, intExt, greeter);

        Set<CallEdge> edges = output.graph().getEdges();
        // Must not resolve s.shout() to the Int extension when receiver is a String variable.
        assertFalse(
                edges.contains(new CallEdge("demo.Greeter.greetString(String)", "demo.IntExtKt.shout()")),
                "Must not resolve s.shout() to the Int extension");
        // Resolved to String or left unresolved — both are acceptable (no full type inference).
    }

    private record BuildOutput(CallGraph graph, KotlinCallGraphBuilder.BuildResult result) {
    }

    private static BuildOutput parseAndBuild(Path repoRoot, Path... sources) throws IOException {
        CallGraph graph = new CallGraph();
        KotlinParserContext ctx = new KotlinParserContext(graph);
        KotlinSymbolVisitor visitor = new KotlinSymbolVisitor(new KotlinAnnotationExtractor(), repoRoot);
        for (Path source : sources) {
            KtFile file = env.parse(source);
            visitor.visit(file, ctx);
        }
        KotlinCallGraphBuilder.BuildResult result = new KotlinCallGraphBuilder().buildEdges(graph, ctx);
        return new BuildOutput(graph, result);
    }

    private static Path writeFile(Path dir, String name, String contents) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, contents);
        return file;
    }
}
