package io.graphus.model;

import java.util.List;

public final class FieldNode extends SymbolNode {

    private final String declaringClassId;
    private final String name;
    private final String type;
    private final List<String> annotations;
    private final SpringMetadata springMetadata;

    public FieldNode(
            String id,
            String declaringClassId,
            String name,
            String type,
            String filePath,
            int line,
            List<String> annotations,
            SpringMetadata springMetadata
    ) {
        super(id, SymbolKind.FIELD, filePath, line);
        this.declaringClassId = declaringClassId;
        this.name = name;
        this.type = type;
        this.annotations = annotations == null ? List.of() : List.copyOf(annotations);
        this.springMetadata = springMetadata == null ? new SpringMetadata() : springMetadata;
    }

    public String getDeclaringClassId() {
        return declaringClassId;
    }

    public String getName() {
        return name;
    }

    public String getType() {
        return type;
    }

    public List<String> getAnnotations() {
        return annotations;
    }

    public SpringMetadata getSpringMetadata() {
        return springMetadata;
    }
}
