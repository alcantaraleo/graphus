package io.graphus.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ClassNode extends SymbolNode {

    private final String simpleName;
    private final List<String> annotations;
    private final String superClass;
    private final List<String> interfaces;
    private final List<String> fields = new ArrayList<>();
    private final List<String> methods = new ArrayList<>();
    private final SpringMetadata springMetadata;

    public ClassNode(
            String id,
            String simpleName,
            String filePath,
            int line,
            List<String> annotations,
            String superClass,
            List<String> interfaces,
            SpringMetadata springMetadata
    ) {
        super(id, SymbolKind.CLASS, filePath, line);
        this.simpleName = simpleName;
        this.annotations = annotations == null ? List.of() : List.copyOf(annotations);
        this.superClass = superClass == null ? "" : superClass;
        this.interfaces = interfaces == null ? List.of() : List.copyOf(interfaces);
        this.springMetadata = springMetadata == null ? new SpringMetadata() : springMetadata;
    }

    public String getSimpleName() {
        return simpleName;
    }

    public List<String> getAnnotations() {
        return annotations;
    }

    public String getSuperClass() {
        return superClass;
    }

    public List<String> getInterfaces() {
        return interfaces;
    }

    public List<String> getFields() {
        return Collections.unmodifiableList(fields);
    }

    public void addField(String fieldId) {
        fields.add(fieldId);
    }

    public List<String> getMethods() {
        return Collections.unmodifiableList(methods);
    }

    public void addMethod(String methodId) {
        methods.add(methodId);
    }

    public SpringMetadata getSpringMetadata() {
        return springMetadata;
    }
}
