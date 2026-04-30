package io.graphus.parser;

import com.github.javaparser.ast.body.MethodDeclaration;
import io.graphus.model.CallGraph;
import java.util.LinkedHashMap;
import java.util.Map;

final class ParserContext {

    private final CallGraph callGraph;
    private final Map<MethodDeclaration, String> methodIdsByDeclaration = new LinkedHashMap<>();

    ParserContext(CallGraph callGraph) {
        this.callGraph = callGraph;
    }

    CallGraph callGraph() {
        return callGraph;
    }

    void registerMethod(MethodDeclaration declaration, String methodId) {
        methodIdsByDeclaration.put(declaration, methodId);
    }

    Map<MethodDeclaration, String> methodIdsByDeclaration() {
        return methodIdsByDeclaration;
    }
}
