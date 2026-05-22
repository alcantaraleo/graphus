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
    void resolvesBinaryOperatorOverloadAsCallEdge(@TempDir Path tempDir) throws IOException {
        Path source = writeFile(
                tempDir,
                "Vector.kt",
                """
                package demo

                data class Vector(val x: Int, val y: Int) {
                    operator fun plus(other: Vector): Vector = Vector(x + other.x, y + other.y)
                    fun combine(other: Vector): Vector = this + other
                }
                """);

        BuildOutput output = parseAndBuild(tempDir, source);

        assertTrue(
                output.graph().getEdges().contains(
                        new CallEdge("demo.Vector.combine(Vector)", "demo.Vector.plus(Vector)")),
                "Binary '+' on Vector must resolve to operator fun plus(Vector)");
    }

    @Test
    void resolvesUnaryOperatorOverloadAsCallEdge(@TempDir Path tempDir) throws IOException {
        Path source = writeFile(
                tempDir,
                "Counter.kt",
                """
                package demo

                class Counter(var value: Int) {
                    operator fun inc(): Counter = Counter(value + 1)
                    fun step(): Counter { var c = this; return ++c }
                }
                """);

        BuildOutput output = parseAndBuild(tempDir, source);

        assertTrue(
                output.graph().getEdges().contains(
                        new CallEdge("demo.Counter.step()", "demo.Counter.inc()")),
                "Prefix '++' must resolve to operator fun inc()");
    }

    @Test
    void resolvesArrayAccessGetOperatorAsCallEdge(@TempDir Path tempDir) throws IOException {
        Path source = writeFile(
                tempDir,
                "Grid.kt",
                """
                package demo

                class Grid {
                    operator fun get(row: Int, col: Int): Int = row * 10 + col
                    fun diagonal(i: Int): Int = this[i, i]
                }
                """);

        BuildOutput output = parseAndBuild(tempDir, source);

        assertTrue(
                output.graph().getEdges().contains(
                        new CallEdge("demo.Grid.diagonal(Int)", "demo.Grid.get(Int, Int)")),
                "Array access this[i, i] must resolve to operator fun get(Int, Int)");
    }

    @Test
    void resolvesArrayAccessSetOperatorAsCallEdge(@TempDir Path tempDir) throws IOException {
        Path source = writeFile(
                tempDir,
                "Bag.kt",
                """
                package demo

                class Bag {
                    private val data = mutableMapOf<Int, Int>()
                    operator fun get(i: Int): Int = data[i] ?: 0
                    operator fun set(i: Int, v: Int) { data[i] = v }
                    fun fill(i: Int) { this[i] = 42 }
                }
                """);

        BuildOutput output = parseAndBuild(tempDir, source);

        assertTrue(
                output.graph().getEdges().contains(
                        new CallEdge("demo.Bag.fill(Int)", "demo.Bag.set(Int, Int)")),
                "Array write this[i] = 42 must resolve to operator fun set(Int, Int)");
    }

    @Test
    void lambdaParameterInvocationProducesTaggedUnresolvedNode(@TempDir Path tempDir) throws IOException {
        Path source = writeFile(
                tempDir,
                "Processor.kt",
                """
                package demo

                fun process(handler: (String) -> String, input: String): String = handler(input)
                """);

        BuildOutput output = parseAndBuild(tempDir, source);

        // The call handler(input) should produce a tagged UNRESOLVED:LAMBDA: node.
        boolean hasLambdaUnresolved = output.graph().getNodes().stream()
                .anyMatch(node -> node.getId().startsWith("UNRESOLVED:LAMBDA:handler"));
        assertTrue(hasLambdaUnresolved,
                "Lambda parameter invocation must produce an UNRESOLVED:LAMBDA: tagged node");
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

    @Test
    void resolvesExtensionCallWhenReceiverTextMatchesTypeSuffix(@TempDir Path tempDir)
            throws IOException {
        // Parameter named identically to the type: receiver text == type name → endsWith fires.
        Path meterExt = writeFile(tempDir, "MeterExt.kt",
                """
                package demo
                class Meter(val value: Double)
                fun Meter.fmt(): String = "${value}m"
                """);
        Path doubleExt = writeFile(tempDir, "DoubleExt.kt",
                """
                package demo
                fun Double.fmt(): String = "${this}d"
                """);
        Path converter = writeFile(tempDir, "Converter.kt",
                """
                package demo
                class Converter {
                    fun convert(Meter: Meter): String = Meter.fmt()
                }
                """);

        BuildOutput output = parseAndBuild(tempDir, meterExt, doubleExt, converter);

        // Meter.fmt() — receiver text "Meter" endsWith receiverType "Meter" → String ext wins.
        assertTrue(
                output.graph().getEdges().contains(
                        new CallEdge("demo.Converter.convert(Meter)", "demo.MeterExtKt.fmt()")),
                "When receiver text equals the type name, must resolve to the Meter extension");
        assertFalse(
                output.graph().getEdges().contains(
                        new CallEdge("demo.Converter.convert(Meter)", "demo.DoubleExtKt.fmt()")),
                "Must not resolve to the Double extension");
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
