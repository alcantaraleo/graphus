package io.graphus.parser;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import io.graphus.model.ClassNode;
import io.graphus.model.FieldNode;
import io.graphus.model.MethodNode;
import io.graphus.model.MethodParam;
import io.graphus.model.SpringMetadata;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public final class SymbolVisitor extends VoidVisitorAdapter<ParserContext> {

    private final SpringAnnotationExtractor springAnnotationExtractor;
    private final Path repositoryRoot;

    public SymbolVisitor(SpringAnnotationExtractor springAnnotationExtractor, Path repositoryRoot) {
        this.springAnnotationExtractor = springAnnotationExtractor;
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

        ClassNode classNode = new ClassNode(
                classId,
                declaration.getNameAsString(),
                filePath,
                line,
                annotations,
                superClass,
                interfaces,
                springMetadata
        );
        context.callGraph().addNode(classNode);
    }

    @Override
    public void visit(FieldDeclaration declaration, ParserContext context) {
        super.visit(declaration, context);

        String classId = declaration.findAncestor(ClassOrInterfaceDeclaration.class)
                .map(SymbolIdResolver::classId)
                .orElse("unknown-class");
        String filePath = SymbolIdResolver.filePath(declaration, repositoryRoot);
        int line = declaration.getBegin().map(position -> position.line).orElse(-1);
        List<String> annotations = declaration.getAnnotations().stream()
                .map(annotation -> "@" + annotation.getName().asString())
                .toList();
        SpringMetadata springMetadata = springAnnotationExtractor.extract(declaration.getAnnotations());

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
                    springMetadata
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

        String classId = declaration.findAncestor(ClassOrInterfaceDeclaration.class)
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

        MethodNode methodNode = new MethodNode(
                methodId,
                classId,
                declaration.getNameAsString(),
                SymbolIdResolver.signature(declaration),
                declaration.getTypeAsString(),
                params,
                modifiers,
                annotations,
                filePath,
                line,
                springMetadata
        );
        context.callGraph().addNode(methodNode);
        context.registerMethod(declaration, methodId);

        if (context.callGraph().getNode(classId) instanceof ClassNode classNode) {
            classNode.addMethod(methodId);
        }
    }
}
