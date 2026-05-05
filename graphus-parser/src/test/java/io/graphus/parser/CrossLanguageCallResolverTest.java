package io.graphus.parser;

import io.graphus.model.CallEdge;
import io.graphus.model.CallGraph;
import io.graphus.model.GuiceMetadata;
import io.graphus.model.MethodNode;
import io.graphus.model.MethodParam;
import io.graphus.model.SpringMetadata;
import io.graphus.model.SymbolKind;
import io.graphus.model.UnresolvedNode;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrossLanguageCallResolverTest {

    @Test
    void replacesJavaUnresolvedWithDirectKotlinEdgeOnUniqueMatch() {
        CallGraph graph = new CallGraph();
        // Java caller method.
        MethodNode caller = method("com.demo.JavaClient.use()", "use", "use()");
        graph.addNode(caller);

        // Kotlin target method.
        MethodNode target = method("com.demo.KotlinService.greet(String)", "greet", "greet(String)");
        graph.addNode(target);

        // Java unresolved placeholder + edge.
        UnresolvedNode unresolved =
                new UnresolvedNode("UNRESOLVED:greet(name)", "greet(name)", "Java.java", 7);
        graph.addNode(unresolved);
        graph.addEdge(caller.getId(), unresolved.getId());

        UnresolvedCallRecord record =
                new UnresolvedCallRecord(caller.getId(), "greet", 1, unresolved.getId(),
                        UnresolvedCallRecord.Origin.JAVA);

        CrossLanguageCallResolver.Result result =
                new CrossLanguageCallResolver().resolve(
                        graph,
                        Set.of(caller.getId()),
                        Set.of(target.getId()),
                        List.of(record),
                        List.of());

        assertEquals(1, result.javaToKotlinResolved());
        assertEquals(0, result.kotlinToJavaResolved());

        Set<CallEdge> edges = graph.getEdges();
        assertTrue(edges.contains(new CallEdge(caller.getId(), target.getId())),
                "Expected direct Java→Kotlin edge after resolution");
        assertFalse(edges.contains(new CallEdge(caller.getId(), unresolved.getId())),
                "Old placeholder edge must be removed");
        assertNull(graph.getNode(unresolved.getId()),
                "Resolved unresolved-node placeholder must be deleted");
    }

    @Test
    void replacesKotlinUnresolvedWithDirectJavaEdgeOnUniqueMatch() {
        CallGraph graph = new CallGraph();
        MethodNode kotlinCaller = method("com.demo.KotlinClient.run()", "run", "run()");
        MethodNode javaTarget = method("com.demo.JavaService.compute(int)", "compute", "compute(int)");
        graph.addNode(kotlinCaller);
        graph.addNode(javaTarget);

        UnresolvedNode unresolved =
                new UnresolvedNode("UNRESOLVED:compute/1@kotlinClient", "compute(x)", "Kotlin.kt", 9);
        graph.addNode(unresolved);
        graph.addEdge(kotlinCaller.getId(), unresolved.getId());

        UnresolvedCallRecord record =
                new UnresolvedCallRecord(kotlinCaller.getId(), "compute", 1, unresolved.getId(),
                        UnresolvedCallRecord.Origin.KOTLIN);

        CrossLanguageCallResolver.Result result =
                new CrossLanguageCallResolver().resolve(
                        graph,
                        Set.of(javaTarget.getId()),
                        Set.of(kotlinCaller.getId()),
                        List.of(),
                        List.of(record));

        assertEquals(1, result.kotlinToJavaResolved());
        assertEquals(0, result.javaToKotlinResolved());
        assertTrue(graph.getEdges().contains(new CallEdge(kotlinCaller.getId(), javaTarget.getId())));
        assertNull(graph.getNode(unresolved.getId()));
    }

    @Test
    void preservesUnresolvedWhenMultipleSameNameSameArityCandidates() {
        CallGraph graph = new CallGraph();
        MethodNode caller = method("com.demo.JavaClient.use()", "use", "use()");
        MethodNode kotlinA = method("com.demo.AKt.greet(String)", "greet", "greet(String)");
        MethodNode kotlinB = method("com.demo.BKt.greet(String)", "greet", "greet(String)");
        graph.addNode(caller);
        graph.addNode(kotlinA);
        graph.addNode(kotlinB);

        UnresolvedNode unresolved =
                new UnresolvedNode("UNRESOLVED:greet(name)", "greet(name)", "Java.java", 7);
        graph.addNode(unresolved);
        graph.addEdge(caller.getId(), unresolved.getId());

        UnresolvedCallRecord record =
                new UnresolvedCallRecord(caller.getId(), "greet", 1, unresolved.getId(),
                        UnresolvedCallRecord.Origin.JAVA);

        CrossLanguageCallResolver.Result result =
                new CrossLanguageCallResolver().resolve(
                        graph,
                        Set.of(caller.getId()),
                        Set.of(kotlinA.getId(), kotlinB.getId()),
                        List.of(record),
                        List.of());

        assertEquals(0, result.javaToKotlinResolved());
        assertNotNull(graph.getNode(unresolved.getId()),
                "Ambiguous match must leave the placeholder in place");
    }

    private static MethodNode method(String id, String name, String signature) {
        return new MethodNode(
                id,
                SymbolKind.METHOD,
                "owner",
                name,
                signature,
                "void",
                List.<MethodParam>of(),
                List.<String>of(),
                List.<String>of(),
                "src/Demo.java",
                1,
                new SpringMetadata(),
                new GuiceMetadata());
    }
}
