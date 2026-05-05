package io.graphus.parser;

import io.graphus.model.CallGraph;
import io.graphus.model.MethodNode;
import io.graphus.model.SymbolNode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Post-processes a {@link CallGraph} that was populated by both the Java and Kotlin pipelines.
 * For every unresolved Java call, attempts to match it against a Kotlin {@link MethodNode}; for
 * every unresolved Kotlin call, attempts to match against a Java {@link MethodNode}. Matching is
 * done by simple callee name and argument count (BA-5: arity comes from structured records, not
 * from string parsing).
 *
 * <p>The strategy is intentionally conservative: a single name+arity candidate becomes a direct
 * call edge and the originating {@link io.graphus.model.UnresolvedNode} placeholder is removed;
 * any ambiguity (multiple candidates) leaves the {@link io.graphus.model.UnresolvedNode} in place.
 */
public final class CrossLanguageCallResolver {

    public Result resolve(
            CallGraph callGraph,
            Set<String> javaMethodIds,
            Set<String> kotlinMethodIds,
            Collection<UnresolvedCallRecord> javaUnresolvedRecords,
            Collection<UnresolvedCallRecord> kotlinUnresolvedRecords) {

        Map<String, List<MethodNode>> javaIndex = indexMethodNodes(callGraph, javaMethodIds);
        Map<String, List<MethodNode>> kotlinIndex = indexMethodNodes(callGraph, kotlinMethodIds);

        int javaToKotlin = resolveAgainst(callGraph, javaUnresolvedRecords, kotlinIndex);
        int kotlinToJava = resolveAgainst(callGraph, kotlinUnresolvedRecords, javaIndex);

        return new Result(javaToKotlin, kotlinToJava);
    }

    private static int resolveAgainst(
            CallGraph callGraph,
            Collection<UnresolvedCallRecord> records,
            Map<String, List<MethodNode>> targetIndex) {
        int resolved = 0;
        for (UnresolvedCallRecord record : records) {
            List<MethodNode> candidates = targetIndex.getOrDefault(record.calleeName(), List.of()).stream()
                    .filter(node -> KotlinCallGraphBuilder.arityOf(node.getSignature()) == record.arity())
                    .toList();
            if (candidates.size() != 1) {
                continue;
            }
            String calleeId = candidates.get(0).getId();
            callGraph.removeEdge(record.callerId(), record.unresolvedNodeId());
            callGraph.removeNode(record.unresolvedNodeId());
            callGraph.addEdge(record.callerId(), calleeId);
            resolved++;
        }
        return resolved;
    }

    private static Map<String, List<MethodNode>> indexMethodNodes(CallGraph callGraph, Set<String> ids) {
        Map<String, List<MethodNode>> bySimpleName = new HashMap<>();
        for (String id : ids) {
            SymbolNode node = callGraph.getNode(id);
            if (node instanceof MethodNode methodNode) {
                bySimpleName.computeIfAbsent(methodNode.getName(), key -> new ArrayList<>()).add(methodNode);
            }
        }
        return bySimpleName;
    }

    /**
     * @param javaToKotlinResolved Java unresolved calls that became Java→Kotlin direct edges
     * @param kotlinToJavaResolved Kotlin unresolved calls that became Kotlin→Java direct edges
     */
    public record Result(int javaToKotlinResolved, int kotlinToJavaResolved) {

        public int total() {
            return javaToKotlinResolved + kotlinToJavaResolved;
        }
    }
}
