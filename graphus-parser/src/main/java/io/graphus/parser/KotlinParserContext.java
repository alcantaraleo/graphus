package io.graphus.parser;

import io.graphus.model.CallGraph;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jetbrains.kotlin.psi.KtDeclarationWithBody;

/**
 * Carries Kotlin parsing state across visitor and builder passes.
 *
 * <p>{@link KotlinSymbolVisitor} registers each {@link KtDeclarationWithBody} (named function,
 * primary/secondary constructor, accessor) by the symbol id used in the {@code CallGraph};
 * {@link KotlinCallGraphBuilder} consumes this map to resolve call expressions inside the bodies.
 *
 * <p>Analogous to {@link ParserContext} for the Java pipeline.
 */
final class KotlinParserContext {

    private final CallGraph callGraph;
    private final Map<KtDeclarationWithBody, String> callableIdsByDeclaration = new LinkedHashMap<>();

    KotlinParserContext(CallGraph callGraph) {
        this.callGraph = callGraph;
    }

    CallGraph callGraph() {
        return callGraph;
    }

    void registerCallable(KtDeclarationWithBody declaration, String callableId) {
        callableIdsByDeclaration.put(declaration, callableId);
    }

    Map<KtDeclarationWithBody, String> callableIdsByDeclaration() {
        return callableIdsByDeclaration;
    }
}
