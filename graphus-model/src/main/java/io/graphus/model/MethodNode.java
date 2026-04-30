package io.graphus.model;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class MethodNode extends SymbolNode {

    private final String declaringClassId;
    private final String name;
    private final String signature;
    private final String returnType;
    private final List<MethodParam> params;
    private final List<String> modifiers;
    private final List<String> annotations;
    private final Set<String> callers = new LinkedHashSet<>();
    private final Set<String> callees = new LinkedHashSet<>();
    private final SpringMetadata springMetadata;

    public MethodNode(
            String id,
            String declaringClassId,
            String name,
            String signature,
            String returnType,
            List<MethodParam> params,
            List<String> modifiers,
            List<String> annotations,
            String filePath,
            int line,
            SpringMetadata springMetadata
    ) {
        super(id, SymbolKind.METHOD, filePath, line);
        this.declaringClassId = declaringClassId;
        this.name = name;
        this.signature = signature;
        this.returnType = returnType;
        this.params = params == null ? List.of() : List.copyOf(params);
        this.modifiers = modifiers == null ? List.of() : List.copyOf(modifiers);
        this.annotations = annotations == null ? List.of() : List.copyOf(annotations);
        this.springMetadata = springMetadata == null ? new SpringMetadata() : springMetadata;
    }

    public String getDeclaringClassId() {
        return declaringClassId;
    }

    public String getName() {
        return name;
    }

    public String getSignature() {
        return signature;
    }

    public String getReturnType() {
        return returnType;
    }

    public List<MethodParam> getParams() {
        return params;
    }

    public List<String> getModifiers() {
        return modifiers;
    }

    public List<String> getAnnotations() {
        return annotations;
    }

    public Set<String> getCallers() {
        return Set.copyOf(callers);
    }

    public void addCaller(String callerId) {
        if (callerId != null && !callerId.isBlank()) {
            callers.add(callerId);
        }
    }

    public Set<String> getCallees() {
        return Set.copyOf(callees);
    }

    public void addCallee(String calleeId) {
        if (calleeId != null && !calleeId.isBlank()) {
            callees.add(calleeId);
        }
    }

    public SpringMetadata getSpringMetadata() {
        return springMetadata;
    }
}
