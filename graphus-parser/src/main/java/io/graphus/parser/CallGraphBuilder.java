package io.graphus.parser;

import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import io.graphus.model.CallGraph;
import io.graphus.model.UnresolvedNode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public final class CallGraphBuilder {

    private final Path repositoryRoot;

    public CallGraphBuilder(Path repositoryRoot) {
        this.repositoryRoot = repositoryRoot;
    }

    /**
     * Backwards-compatible entry point that returns only the unresolved-call count. Existing
     * callers (and tests) keep the same shape; callers needing the structured records call
     * {@link #buildEdgesWithRecords(CallGraph, Map)} instead.
     */
    public int buildEdges(CallGraph callGraph, Map<CallableDeclaration<?>, String> callableIdsByDeclaration) {
        return buildEdgesWithRecords(callGraph, callableIdsByDeclaration).unresolvedCalls();
    }

    /**
     * Builds Java→Java edges and emits structured {@link UnresolvedCallRecord} entries for every
     * call site that JavaParser could not resolve. Capturing the records while the AST is still
     * available lets {@link CrossLanguageCallResolver} match calls by name and arity without
     * re-parsing {@link MethodCallExpr#toString()} (BA-5).
     */
    public BuildResult buildEdgesWithRecords(
            CallGraph callGraph, Map<CallableDeclaration<?>, String> callableIdsByDeclaration) {
        AtomicInteger unresolvedCalls = new AtomicInteger();
        List<UnresolvedCallRecord> unresolvedRecords = new ArrayList<>();

        for (Map.Entry<CallableDeclaration<?>, String> entry : callableIdsByDeclaration.entrySet()) {
            CallableDeclaration<?> declaration = entry.getKey();
            String callerId = entry.getValue();

            for (MethodCallExpr methodCallExpr : declaration.findAll(MethodCallExpr.class)) {
                try {
                    String calleeId = SymbolIdResolver.methodId(methodCallExpr);
                    callGraph.addEdge(callerId, calleeId);
                } catch (UnsolvedSymbolException | UnsupportedOperationException unresolvedException) {
                    unresolvedCalls.incrementAndGet();
                    String unresolvedId = "UNRESOLVED:" + methodCallExpr;
                    String filePath = SymbolIdResolver.filePath(methodCallExpr, repositoryRoot);
                    int line = methodCallExpr.getBegin().map(position -> position.line).orElse(-1);
                    callGraph.addNode(new UnresolvedNode(unresolvedId, methodCallExpr.toString(), filePath, line));
                    callGraph.addEdge(callerId, unresolvedId);
                    unresolvedRecords.add(new UnresolvedCallRecord(
                            callerId,
                            methodCallExpr.getNameAsString(),
                            methodCallExpr.getArguments().size(),
                            unresolvedId,
                            UnresolvedCallRecord.Origin.JAVA));
                } catch (RuntimeException ignored) {
                    unresolvedCalls.incrementAndGet();
                }
            }
        }
        return new BuildResult(unresolvedCalls.get(), List.copyOf(unresolvedRecords));
    }

    public record BuildResult(int unresolvedCalls, List<UnresolvedCallRecord> records) {
    }
}
