package io.graphus.parser;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.github.javaparser.ast.Node;
import io.graphus.model.ClassNode;
import io.graphus.model.FieldNode;
import io.graphus.model.GuiceMetadata;
import io.graphus.model.MethodNode;
import io.graphus.model.MethodParam;
import io.graphus.model.SpringMetadata;
import io.graphus.model.SymbolKind;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public final class SymbolVisitor extends VoidVisitorAdapter<ParserContext> {

    private final SpringAnnotationExtractor springAnnotationExtractor;
    private final GuiceAnnotationExtractor guiceAnnotationExtractor;
    private final Path repositoryRoot;

    public SymbolVisitor(
            SpringAnnotationExtractor springAnnotationExtractor,
            GuiceAnnotationExtractor guiceAnnotationExtractor,
            Path repositoryRoot
    ) {
        this.springAnnotationExtractor = springAnnotationExtractor;
        this.guiceAnnotationExtractor = guiceAnnotationExtractor;
        this.repositoryRoot = repositoryRoot;
    }

    @Override
    public void visit(ClassOrInterfaceDeclaration declaration, ParserContext context) {
        super.visit(declaration, context);

        String classId = SymbolIdResolver.classId(declaration);
        String filePath = SymbolIdResolver.filePath(declaration, repositoryRoot);
        int line = declaration.getBegin().map(position -> position.line).orElse(-1);
        List<String> annotations = declaration.getAnnotations().stream()
                .map(annotation -> "@" + annotation.getName().asString())
                .toList();
        String superClass = declaration.getExtendedTypes().stream()
                .findFirst()
                .map(type -> type.getNameAsString())
                .orElse("");
        List<String> interfaces = declaration.getImplementedTypes().stream()
                .map(type -> type.getNameAsString())
                .toList();
        SpringMetadata springMetadata = springAnnotationExtractor.extract(declaration.getAnnotations());
        GuiceMetadata guiceMetadata = guiceAnnotationExtractor.extractForClass(declaration);

        ClassNode classNode = new ClassNode(
                classId,
                declaration.getNameAsString(),
                filePath,
                line,
                annotations,
                superClass,
                interfaces,
                springMetadata,
                guiceMetadata
        );
        context.callGraph().addNode(classNode);
    }

    @Override
    public void visit(FieldDeclaration declaration, ParserContext context) {
        super.visit(declaration, context);

        String classId = enclosingClass(declaration)
                .map(SymbolIdResolver::classId)
                .orElse("unknown-class");
        String filePath = SymbolIdResolver.filePath(declaration, repositoryRoot);
        int line = declaration.getBegin().map(position -> position.line).orElse(-1);
        List<String> annotations = declaration.getAnnotations().stream()
                .map(annotation -> "@" + annotation.getName().asString())
                .toList();
        SpringMetadata springMetadata = springAnnotationExtractor.extract(declaration.getAnnotations());
        GuiceMetadata guiceMetadata = guiceAnnotationExtractor.extractForField(declaration);

        for (VariableDeclarator variable : declaration.getVariables()) {
            String fieldId = classId + "#" + variable.getNameAsString();
            FieldNode fieldNode = new FieldNode(
                    fieldId,
                    classId,
                    variable.getNameAsString(),
                    variable.getTypeAsString(),
                    filePath,
                    line,
                    annotations,
                    springMetadata,
                    guiceMetadata
            );
            context.callGraph().addNode(fieldNode);
            if (context.callGraph().getNode(classId) instanceof ClassNode classNode) {
                classNode.addField(fieldId);
            }
        }
    }

    @Override
    public void visit(MethodDeclaration declaration, ParserContext context) {
        super.visit(declaration, context);

        String classId = enclosingClass(declaration)
                .map(SymbolIdResolver::classId)
                .orElse("unknown-class");
        String methodId = SymbolIdResolver.methodId(declaration);
        String filePath = SymbolIdResolver.filePath(declaration, repositoryRoot);
        int line = declaration.getBegin().map(position -> position.line).orElse(-1);

        List<MethodParam> params = declaration.getParameters().stream()
                .map(parameter -> new MethodParam(parameter.getNameAsString(), parameter.getTypeAsString()))
                .toList();
        List<String> modifiers = declaration.getModifiers().stream()
                .map(modifier -> modifier.getKeyword().asString())
                .toList();
        List<String> annotations = declaration.getAnnotations().stream()
                .map(annotation -> "@" + annotation.getName().asString())
                .collect(Collectors.toCollection(ArrayList::new));
        SpringMetadata springMetadata = springAnnotationExtractor.extractForMethod(declaration);
        GuiceMetadata guiceMetadata = guiceAnnotationExtractor.extractForMethod(declaration);

        MethodNode methodNode = new MethodNode(
                methodId,
                SymbolKind.METHOD,
                classId,
                declaration.getNameAsString(),
                SymbolIdResolver.signature(declaration),
                declaration.getTypeAsString(),
                params,
                modifiers,
                annotations,
                filePath,
                line,
                springMetadata,
                guiceMetadata
        );
        context.callGraph().addNode(methodNode);
        context.registerCallable(declaration, methodId);

        if (context.callGraph().getNode(classId) instanceof ClassNode classNode) {
            classNode.addMethod(methodId);
        }
    }

    @Override
    public void visit(ConstructorDeclaration declaration, ParserContext context) {
        super.visit(declaration, context);

        String classId = enclosingClass(declaration)
                .map(SymbolIdResolver::classId)
                .orElse("unknown-class");
        String constructorId = SymbolIdResolver.constructorId(declaration);
        String filePath = SymbolIdResolver.filePath(declaration, repositoryRoot);
        int line = declaration.getBegin().map(position -> position.line).orElse(-1);

        List<MethodParam> params = declaration.getParameters().stream()
                .map(parameter -> new MethodParam(parameter.getNameAsString(), parameter.getTypeAsString()))
                .toList();
        List<String> modifiers = declaration.getModifiers().stream()
                .map(modifier -> modifier.getKeyword().asString())
                .toList();
        List<String> annotations = declaration.getAnnotations().stream()
                .map(annotation -> "@" + annotation.getName().asString())
                .collect(Collectors.toCollection(ArrayList::new));
        SpringMetadata springMetadata = springAnnotationExtractor.extract(declaration.getAnnotations());
        GuiceMetadata guiceMetadata = guiceAnnotationExtractor.extractForConstructor(declaration);

        MethodNode constructorNode = new MethodNode(
                constructorId,
                SymbolKind.CONSTRUCTOR,
                classId,
                declaration.getNameAsString(),
                SymbolIdResolver.signature(declaration),
                declaration.getNameAsString(),
                params,
                modifiers,
                annotations,
                filePath,
                line,
                springMetadata,
                guiceMetadata
        );
        context.callGraph().addNode(constructorNode);
        context.registerCallable(declaration, constructorId);

        if (context.callGraph().getNode(classId) instanceof ClassNode classNode) {
            classNode.addMethod(constructorId);
        }
    }

    private Optional<ClassOrInterfaceDeclaration> enclosingClass(Node node) {
        Node current = node;
        while (current.getParentNode().isPresent()) {
            current = current.getParentNode().orElseThrow();
            if (current instanceof ClassOrInterfaceDeclaration declaration) {
                return Optional.of(declaration);
            }
        }
        return Optional.empty();
    }
}
