package io.graphus.parser;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import io.graphus.model.CallGraph;
import java.util.LinkedHashMap;
import java.util.Map;

final class ParserContext {

    private final CallGraph callGraph;
    private final Map<CallableDeclaration<?>, String> callableIdsByDeclaration = new LinkedHashMap<>();

    ParserContext(CallGraph callGraph) {
        this.callGraph = callGraph;
    }

    CallGraph callGraph() {
        return callGraph;
    }

    void registerMethod(MethodDeclaration declaration, String methodId) {
        registerCallable(declaration, methodId);
    }

    void registerCallable(CallableDeclaration<?> declaration, String callableId) {
        callableIdsByDeclaration.put(declaration, callableId);
    }

    Map<CallableDeclaration<?>, String> callableIdsByDeclaration() {
        return callableIdsByDeclaration;
    }
}
