package io.graphus.parser;

import io.graphus.model.CallGraph;
import io.graphus.model.ClassNode;
import io.graphus.model.FieldNode;
import io.graphus.model.InjectionType;
import io.graphus.model.MethodNode;
import io.graphus.model.SymbolNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Post-processes a {@link CallGraph} to add synthetic dependency edges for Guice-injected
 * constructor parameters and fields. Because Guice resolves bindings at runtime, injected
 * dependencies leave no explicit call expression in the AST and therefore no edge in the graph.
 *
 * <p>Strategy: for each {@code @Inject} constructor parameter or field whose declared type is an
 * interface, look up all non-test {@link ClassNode}s that implement that interface. If exactly one
 * implementor exists, add a synthetic edge from the dependent class to the implementor. Ambiguous
 * cases (zero or more than one implementor) are skipped — no false positives are introduced.
 */
public final class GuiceInjectionResolver {

    private static final String TEST_PATH_SEGMENT_UNIX = "/test/";
    private static final String TEST_SOURCE_ROOT = "src/test";

    public Result resolve(CallGraph callGraph) {
        Map<String, List<ClassNode>> implementorsByInterface = buildImplementorIndex(callGraph);

        int edgesAdded = 0;
        int skippedAmbiguous = 0;
        int skippedNoMatch = 0;

        for (SymbolNode node : callGraph.getNodes()) {
            if (isTestSource(node.getFilePath())) {
                continue;
            }

            if (node instanceof MethodNode methodNode
                    && methodNode.getGuiceMetadata().getInjectionType() == InjectionType.CONSTRUCTOR) {
                for (var param : methodNode.getParams()) {
                    List<ClassNode> candidates = implementorsByInterface.getOrDefault(param.type(), List.of());
                    if (candidates.size() == 1) {
                        callGraph.addEdge(methodNode.getDeclaringClassId(), candidates.get(0).getId());
                        edgesAdded++;
                    } else if (candidates.isEmpty()) {
                        skippedNoMatch++;
                    } else {
                        skippedAmbiguous++;
                    }
                }
            }

            if (node instanceof FieldNode fieldNode
                    && fieldNode.getGuiceMetadata().getInjectionType() == InjectionType.FIELD) {
                List<ClassNode> candidates = implementorsByInterface.getOrDefault(fieldNode.getType(), List.of());
                if (candidates.size() == 1) {
                    callGraph.addEdge(fieldNode.getDeclaringClassId(), candidates.get(0).getId());
                    edgesAdded++;
                } else if (candidates.isEmpty()) {
                    skippedNoMatch++;
                } else {
                    skippedAmbiguous++;
                }
            }
        }

        return new Result(edgesAdded, skippedAmbiguous, skippedNoMatch);
    }

    private static Map<String, List<ClassNode>> buildImplementorIndex(CallGraph callGraph) {
        Map<String, List<ClassNode>> index = new HashMap<>();
        for (SymbolNode node : callGraph.getNodes()) {
            if (!(node instanceof ClassNode classNode)) {
                continue;
            }
            if (isTestSource(classNode.getFilePath())) {
                continue;
            }
            for (String iface : classNode.getInterfaces()) {
                index.computeIfAbsent(iface, key -> new ArrayList<>()).add(classNode);
            }
        }
        return index;
    }

    private static boolean isTestSource(String filePath) {
        if (filePath == null) {
            return false;
        }
        return filePath.contains(TEST_PATH_SEGMENT_UNIX) || filePath.contains(TEST_SOURCE_ROOT);
    }

    /**
     * @param edgesAdded       synthetic injection edges added
     * @param skippedAmbiguous injected types with more than one implementor (not resolved)
     * @param skippedNoMatch   injected types with no implementor in the index (external or concrete)
     */
    public record Result(int edgesAdded, int skippedAmbiguous, int skippedNoMatch) {}
}
