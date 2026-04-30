package io.graphus.indexer;

import dev.langchain4j.data.document.Metadata;
import io.graphus.model.CallGraph;
import io.graphus.model.ClassNode;
import io.graphus.model.FieldNode;
import io.graphus.model.MethodNode;
import io.graphus.model.SymbolKind;
import io.graphus.model.SymbolNode;
import io.graphus.model.UnresolvedNode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public final class SymbolChunkBuilder {

    public List<SymbolChunk> build(CallGraph callGraph) {
        return buildChunks(callGraph.getNodes(), callGraph);
    }

    public List<SymbolChunk> build(CallGraph callGraph, Set<String> filePaths) {
        return buildChunks(
                callGraph.getNodes().stream()
                        .filter(node -> filePaths.contains(node.getFilePath()))
                        .toList(),
                callGraph
        );
    }

    private List<SymbolChunk> buildChunks(Collection<SymbolNode> nodes, CallGraph callGraph) {
        List<SymbolChunk> chunks = new ArrayList<>();

        for (SymbolNode node : nodes) {
            Metadata metadata = metadataFor(node);
            String text = chunkText(node, callGraph);
            chunks.add(new SymbolChunk(node.getId(), text, metadata));
        }

        return chunks;
    }

    private Metadata metadataFor(SymbolNode node) {
        Metadata metadata = new Metadata()
                .put("fqn", node.getId())
                .put("kind", node.getKind().name())
                .put("file", node.getFilePath())
                .put("line", node.getLine());

        if (node instanceof ClassNode classNode) {
            metadata = metadata
                    .put("spring_stereotype", classNode.getSpringMetadata().getStereotype().name())
                    .put("guice_stereotype", classNode.getGuiceMetadata().getStereotype().name());
        } else if (node instanceof MethodNode methodNode) {
            metadata = metadata
                    .put("spring_stereotype", methodNode.getSpringMetadata().getStereotype().name())
                    .put("guice_stereotype", methodNode.getGuiceMetadata().getStereotype().name())
                    .put("guice_injection", methodNode.getGuiceMetadata().getInjectionType().name())
                    .put("guice_singleton", String.valueOf(methodNode.getGuiceMetadata().isSingleton()))
                    .put("guice_named", methodNode.getGuiceMetadata().getNamedValue())
                    .put("callers", String.join(",", methodNode.getCallers()))
                    .put("callees", String.join(",", methodNode.getCallees()))
                    .put("signature", methodNode.getSignature());
            if (!methodNode.getSpringMetadata().getHttpMappings().isEmpty()) {
                String mappings = methodNode.getSpringMetadata().getHttpMappings().stream()
                        .map(mapping -> mapping.method() + " " + mapping.path())
                        .collect(Collectors.joining("; "));
                metadata = metadata.put("http_path", mappings);
            }
        }

        return metadata;
    }

    private String chunkText(SymbolNode node, CallGraph callGraph) {
        if (node instanceof MethodNode methodNode) {
            String mappings = methodNode.getSpringMetadata().getHttpMappings().stream()
                    .map(mapping -> mapping.method() + " " + mapping.path())
                    .collect(Collectors.joining(", "));
            String kindLabel = methodNode.getKind() == SymbolKind.CONSTRUCTOR ? "CONSTRUCTOR" : "METHOD";
            return "[" + kindLabel + "] " + methodNode.getId() + "\n"
                    + "Class: " + methodNode.getDeclaringClassId() + "\n"
                    + "Signature: " + methodNode.getSignature() + "\n"
                    + "Return: " + methodNode.getReturnType() + "\n"
                    + "Parameters: " + methodNode.getParams() + "\n"
                    + "Annotations: " + String.join(", ", methodNode.getAnnotations()) + "\n"
                    + "Spring Stereotype: " + methodNode.getSpringMetadata().getStereotype() + "\n"
                    + "Guice Stereotype: " + methodNode.getGuiceMetadata().getStereotype() + "\n"
                    + "Guice Injection: " + methodNode.getGuiceMetadata().getInjectionType() + "\n"
                    + "Singleton: " + methodNode.getGuiceMetadata().isSingleton() + "\n"
                    + "Named: " + methodNode.getGuiceMetadata().getNamedValue() + "\n"
                    + "HTTP Mappings: " + mappings + "\n"
                    + "Transactional: " + methodNode.getSpringMetadata().isTransactional() + "\n"
                    + "Callers: " + String.join(", ", methodNode.getCallers()) + "\n"
                    + "Callees: " + String.join(", ", methodNode.getCallees()) + "\n"
                    + "File: " + methodNode.getFilePath() + ":" + methodNode.getLine();
        }
        if (node instanceof ClassNode classNode) {
            return "[CLASS] " + classNode.getId() + "\n"
                    + "Simple Name: " + classNode.getSimpleName() + "\n"
                    + "Annotations: " + String.join(", ", classNode.getAnnotations()) + "\n"
                    + "Superclass: " + classNode.getSuperClass() + "\n"
                    + "Interfaces: " + String.join(", ", classNode.getInterfaces()) + "\n"
                    + "Spring Stereotype: " + classNode.getSpringMetadata().getStereotype() + "\n"
                    + "Guice Stereotype: " + classNode.getGuiceMetadata().getStereotype() + "\n"
                    + "Fields: " + String.join(", ", classNode.getFields()) + "\n"
                    + "Methods: " + String.join(", ", classNode.getMethods()) + "\n"
                    + "Outgoing Symbols: " + String.join(", ", callGraph.outgoingNeighbors(classNode.getId())) + "\n"
                    + "File: " + classNode.getFilePath() + ":" + classNode.getLine();
        }
        if (node instanceof FieldNode fieldNode) {
            return "[FIELD] " + fieldNode.getId() + "\n"
                    + "Class: " + fieldNode.getDeclaringClassId() + "\n"
                    + "Name: " + fieldNode.getName() + "\n"
                    + "Type: " + fieldNode.getType() + "\n"
                    + "Annotations: " + String.join(", ", fieldNode.getAnnotations()) + "\n"
                    + "Guice Injection: " + fieldNode.getGuiceMetadata().getInjectionType() + "\n"
                    + "File: " + fieldNode.getFilePath() + ":" + fieldNode.getLine();
        }
        if (node instanceof UnresolvedNode unresolvedNode) {
            return "[UNRESOLVED] " + unresolvedNode.getId() + "\n"
                    + "Expression: " + unresolvedNode.getExpression() + "\n"
                    + "File: " + unresolvedNode.getFilePath() + ":" + unresolvedNode.getLine();
        }
        return "[SYMBOL] " + node.getId();
    }
}
