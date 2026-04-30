package io.graphus.parser;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import io.graphus.model.CallGraph;
import io.graphus.model.UnresolvedNode;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public final class CallGraphBuilder {

    private final Path repositoryRoot;

    public CallGraphBuilder(Path repositoryRoot) {
        this.repositoryRoot = repositoryRoot;
    }

    public int buildEdges(CallGraph callGraph, Map<MethodDeclaration, String> methodIdsByDeclaration) {
        AtomicInteger unresolvedCalls = new AtomicInteger();

        for (Map.Entry<MethodDeclaration, String> entry : methodIdsByDeclaration.entrySet()) {
            MethodDeclaration methodDeclaration = entry.getKey();
            String callerId = entry.getValue();

            for (MethodCallExpr methodCallExpr : methodDeclaration.findAll(MethodCallExpr.class)) {
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
                } catch (RuntimeException ignored) {
                    unresolvedCalls.incrementAndGet();
                }
            }
        }
        return unresolvedCalls.get();
    }
}
