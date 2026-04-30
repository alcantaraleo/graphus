package io.graphus.parser;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import java.nio.file.Path;
import java.util.stream.Collectors;

final class SymbolIdResolver {

    private SymbolIdResolver() {
    }

    static String classId(ClassOrInterfaceDeclaration declaration) {
        String packageName = declaration.findCompilationUnit()
                .flatMap(compilationUnit -> compilationUnit.getPackageDeclaration().map(packageDecl -> packageDecl.getNameAsString()))
                .orElse("");
        if (packageName.isBlank()) {
            return declaration.getNameAsString();
        }
        return packageName + "." + declaration.getNameAsString();
    }

    static String methodId(MethodDeclaration declaration) {
        try {
            return declaration.resolve().getQualifiedSignature();
        } catch (RuntimeException ignored) {
            return declaration.findAncestor(ClassOrInterfaceDeclaration.class)
                    .map(SymbolIdResolver::classId)
                    .orElse("unknown-class")
                    + "#"
                    + signature(declaration);
        }
    }

    static String methodId(ResolvedMethodDeclaration declaration) {
        return declaration.getQualifiedSignature();
    }

    static String methodId(MethodCallExpr methodCallExpr) {
        return methodId(methodCallExpr.resolve());
    }

    static String signature(MethodDeclaration declaration) {
        String params = declaration.getParameters().stream()
                .map(parameter -> parameter.getType().asString())
                .collect(Collectors.joining(", "));
        return declaration.getNameAsString() + "(" + params + ")";
    }

    static String filePath(Node node, Path repositoryRoot) {
        return node.findCompilationUnit()
                .flatMap(compilationUnit -> compilationUnit.getStorage().map(storage -> {
                    Path absolutePath = storage.getPath().toAbsolutePath().normalize();
                    if (repositoryRoot != null && absolutePath.startsWith(repositoryRoot.toAbsolutePath().normalize())) {
                        return repositoryRoot.toAbsolutePath().normalize().relativize(absolutePath).toString();
                    }
                    return absolutePath.toString();
                }))
                .orElse("");
    }
}
