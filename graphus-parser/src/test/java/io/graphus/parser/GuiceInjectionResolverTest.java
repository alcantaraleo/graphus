package io.graphus.parser;

import io.graphus.model.CallEdge;
import io.graphus.model.CallGraph;
import io.graphus.model.ClassNode;
import io.graphus.model.FieldNode;
import io.graphus.model.GuiceMetadata;
import io.graphus.model.InjectionType;
import io.graphus.model.MethodNode;
import io.graphus.model.MethodParam;
import io.graphus.model.SpringMetadata;
import io.graphus.model.SymbolKind;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuiceInjectionResolverTest {

    // ---- @Inject constructor ----

    @Test
    void addsEdgeWhenInjectConstructorHasSingleImplementorParam() {
        CallGraph graph = new CallGraph();

        ClassNode impl = classNode("com.demo.PaymentServiceImpl", "PaymentServiceImpl",
                List.of("PaymentService"), "src/main/java/com/demo/PaymentServiceImpl.java");
        graph.addNode(impl);

        ClassNode dependent = classNode("com.demo.CheckoutController", "CheckoutController",
                List.of(), "src/main/java/com/demo/CheckoutController.java");
        graph.addNode(dependent);

        MethodNode ctor = injectConstructor("com.demo.CheckoutController.<init>(PaymentService)",
                "CheckoutController", "com.demo.CheckoutController",
                List.of(new MethodParam("service", "PaymentService")),
                "src/main/java/com/demo/CheckoutController.java");
        graph.addNode(ctor);

        GuiceInjectionResolver.Result result = new GuiceInjectionResolver().resolve(graph);

        assertEquals(1, result.edgesAdded());
        assertTrue(graph.getEdges().contains(new CallEdge("com.demo.CheckoutController", impl.getId())));
    }

    @Test
    void skipsWhenInjectConstructorParamHasMultipleImplementors() {
        CallGraph graph = new CallGraph();

        graph.addNode(classNode("com.demo.ImplA", "ImplA", List.of("PaymentService"),
                "src/main/java/com/demo/ImplA.java"));
        graph.addNode(classNode("com.demo.ImplB", "ImplB", List.of("PaymentService"),
                "src/main/java/com/demo/ImplB.java"));
        graph.addNode(classNode("com.demo.CheckoutController", "CheckoutController",
                List.of(), "src/main/java/com/demo/CheckoutController.java"));

        graph.addNode(injectConstructor("com.demo.CheckoutController.<init>(PaymentService)",
                "CheckoutController", "com.demo.CheckoutController",
                List.of(new MethodParam("service", "PaymentService")),
                "src/main/java/com/demo/CheckoutController.java"));

        GuiceInjectionResolver.Result result = new GuiceInjectionResolver().resolve(graph);

        assertEquals(0, result.edgesAdded());
        assertEquals(1, result.skippedAmbiguous());
        assertTrue(graph.getEdges().isEmpty());
    }

    @Test
    void skipsWhenInjectConstructorParamHasNoImplementor() {
        CallGraph graph = new CallGraph();

        graph.addNode(classNode("com.demo.CheckoutController", "CheckoutController",
                List.of(), "src/main/java/com/demo/CheckoutController.java"));
        graph.addNode(injectConstructor("com.demo.CheckoutController.<init>(ExternalService)",
                "CheckoutController", "com.demo.CheckoutController",
                List.of(new MethodParam("svc", "ExternalService")),
                "src/main/java/com/demo/CheckoutController.java"));

        GuiceInjectionResolver.Result result = new GuiceInjectionResolver().resolve(graph);

        assertEquals(0, result.edgesAdded());
        assertEquals(1, result.skippedNoMatch());
    }

    @Test
    void excludesTestSourceImplementorsFromIndex() {
        CallGraph graph = new CallGraph();

        // test-only stub — must not be indexed as an implementor
        graph.addNode(classNode("com.demo.FakePaymentService", "FakePaymentService",
                List.of("PaymentService"), "src/test/java/com/demo/FakePaymentService.java"));

        graph.addNode(classNode("com.demo.CheckoutController", "CheckoutController",
                List.of(), "src/main/java/com/demo/CheckoutController.java"));
        graph.addNode(injectConstructor("com.demo.CheckoutController.<init>(PaymentService)",
                "CheckoutController", "com.demo.CheckoutController",
                List.of(new MethodParam("service", "PaymentService")),
                "src/main/java/com/demo/CheckoutController.java"));

        GuiceInjectionResolver.Result result = new GuiceInjectionResolver().resolve(graph);

        assertEquals(0, result.edgesAdded());
        assertEquals(1, result.skippedNoMatch());
        assertTrue(graph.getEdges().isEmpty());
    }

    @Test
    void doesNotAddEdgeForNonInjectConstructor() {
        CallGraph graph = new CallGraph();

        graph.addNode(classNode("com.demo.PaymentServiceImpl", "PaymentServiceImpl",
                List.of("PaymentService"), "src/main/java/com/demo/PaymentServiceImpl.java"));
        graph.addNode(classNode("com.demo.CheckoutController", "CheckoutController",
                List.of(), "src/main/java/com/demo/CheckoutController.java"));

        // no @Inject
        MethodNode ctor = new MethodNode(
                "com.demo.CheckoutController.<init>(PaymentService)",
                SymbolKind.CONSTRUCTOR,
                "com.demo.CheckoutController",
                "CheckoutController",
                "CheckoutController(PaymentService)",
                "CheckoutController",
                List.of(new MethodParam("service", "PaymentService")),
                List.of(),
                List.of(),
                "src/main/java/com/demo/CheckoutController.java",
                1,
                new SpringMetadata(),
                new GuiceMetadata());
        graph.addNode(ctor);

        GuiceInjectionResolver.Result result = new GuiceInjectionResolver().resolve(graph);

        assertEquals(0, result.edgesAdded());
        assertTrue(graph.getEdges().isEmpty());
    }

    // ---- @Inject field ----

    @Test
    void addsEdgeWhenInjectFieldHasSingleImplementor() {
        CallGraph graph = new CallGraph();

        ClassNode impl = classNode("com.demo.BookingRepositoryImpl", "BookingRepositoryImpl",
                List.of("BookingRepository"), "src/main/java/com/demo/BookingRepositoryImpl.java");
        graph.addNode(impl);

        ClassNode owner = classNode("com.demo.BookingService", "BookingService",
                List.of(), "src/main/java/com/demo/BookingService.java");
        graph.addNode(owner);

        graph.addNode(injectField("com.demo.BookingService#repository", "com.demo.BookingService",
                "repository", "BookingRepository", "src/main/java/com/demo/BookingService.java"));

        GuiceInjectionResolver.Result result = new GuiceInjectionResolver().resolve(graph);

        assertEquals(1, result.edgesAdded());
        assertTrue(graph.getEdges().contains(new CallEdge("com.demo.BookingService", impl.getId())));
    }

    @Test
    void skipsInjectFieldWhenAmbiguous() {
        CallGraph graph = new CallGraph();

        graph.addNode(classNode("com.demo.ImplA", "ImplA", List.of("BookingRepository"),
                "src/main/java/com/demo/ImplA.java"));
        graph.addNode(classNode("com.demo.ImplB", "ImplB", List.of("BookingRepository"),
                "src/main/java/com/demo/ImplB.java"));
        graph.addNode(classNode("com.demo.BookingService", "BookingService",
                List.of(), "src/main/java/com/demo/BookingService.java"));
        graph.addNode(injectField("com.demo.BookingService#repository", "com.demo.BookingService",
                "repository", "BookingRepository", "src/main/java/com/demo/BookingService.java"));

        GuiceInjectionResolver.Result result = new GuiceInjectionResolver().resolve(graph);

        assertEquals(0, result.edgesAdded());
        assertEquals(1, result.skippedAmbiguous());
    }

    // ---- helpers ----

    private static ClassNode classNode(String id, String simpleName, List<String> interfaces, String filePath) {
        return new ClassNode(id, simpleName, filePath, 1, List.of(), null, interfaces,
                new SpringMetadata(), new GuiceMetadata());
    }

    private static MethodNode injectConstructor(String id, String name, String declaringClassId,
            List<MethodParam> params, String filePath) {
        GuiceMetadata guice = new GuiceMetadata();
        guice.setInjectionType(InjectionType.CONSTRUCTOR);
        return new MethodNode(id, SymbolKind.CONSTRUCTOR, declaringClassId, name,
                name + "(" + params.stream().map(MethodParam::type).reduce((a, b) -> a + "," + b).orElse("") + ")",
                name, params, List.of(), List.of(), filePath, 1, new SpringMetadata(), guice);
    }

    private static FieldNode injectField(String id, String declaringClassId, String name,
            String type, String filePath) {
        GuiceMetadata guice = new GuiceMetadata();
        guice.setInjectionType(InjectionType.FIELD);
        return new FieldNode(id, declaringClassId, name, type, filePath, 1, List.of(),
                new SpringMetadata(), guice);
    }
}
