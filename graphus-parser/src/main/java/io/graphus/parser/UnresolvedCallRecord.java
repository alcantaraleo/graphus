package io.graphus.parser;

/**
 * Structured record of one unresolved call expression captured while the AST is still in memory.
 *
 * <p>Replaces the fragile pattern of parsing {@code MethodCallExpr.toString()} downstream
 * (BA-5): name and arity come straight from the parser, so nested calls, lambdas, and string
 * literals containing commas no longer corrupt the arity count.
 *
 * @param callerId    fully-qualified id of the caller (Java method/constructor or Kotlin callable)
 * @param calleeName  simple name of the called method or function
 * @param arity       declared argument count at the call site
 * @param unresolvedNodeId id of the {@link io.graphus.model.UnresolvedNode} placeholder added to
 *                         the {@link io.graphus.model.CallGraph}; {@link CrossLanguageCallResolver}
 *                         removes it on a confident match.
 * @param origin      whether the unresolved call came from the Java or Kotlin pipeline
 */
public record UnresolvedCallRecord(
        String callerId,
        String calleeName,
        int arity,
        String unresolvedNodeId,
        Origin origin) {

    public enum Origin {
        JAVA,
        KOTLIN
    }
}
