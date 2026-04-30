package io.graphus.parser;

import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import io.graphus.model.GuiceMetadata;
import io.graphus.model.GuiceStereotype;
import io.graphus.model.InjectionType;
import java.util.Optional;

public final class GuiceAnnotationExtractor {

    public GuiceMetadata extractForClass(ClassOrInterfaceDeclaration declaration) {
        GuiceMetadata metadata = extract(declaration.getAnnotations(), InjectionType.NONE);
        boolean isModuleType = declaration.getExtendedTypes().stream()
                .map(type -> type.getNameAsString())
                .anyMatch(name -> name.equals("AbstractModule") || name.equals("PrivateModule"));
        if (isModuleType) {
            metadata.setStereotype(GuiceStereotype.MODULE);
        }
        return metadata;
    }

    public GuiceMetadata extractForMethod(MethodDeclaration declaration) {
        return extract(declaration.getAnnotations(), InjectionType.METHOD);
    }

    public GuiceMetadata extractForField(FieldDeclaration declaration) {
        return extract(declaration.getAnnotations(), InjectionType.FIELD);
    }

    public GuiceMetadata extractForConstructor(ConstructorDeclaration declaration) {
        return extract(declaration.getAnnotations(), InjectionType.CONSTRUCTOR);
    }

    private GuiceMetadata extract(NodeList<AnnotationExpr> annotations, InjectionType injectTarget) {
        GuiceMetadata metadata = new GuiceMetadata();
        for (AnnotationExpr annotation : annotations) {
            String simpleName = annotation.getName().getIdentifier();
            switch (simpleName) {
                case "Inject" -> metadata.setInjectionType(injectTarget);
                case "Provides" -> metadata.setStereotype(GuiceStereotype.PROVIDES);
                case "Singleton" -> metadata.setSingleton(true);
                case "RequestScoped" -> metadata.setRequestScoped(true);
                case "SessionScoped" -> metadata.setSessionScoped(true);
                case "Named" -> extractNamedValue(annotation).ifPresent(metadata::setNamedValue);
                default -> {
                    // non-Guice annotation
                }
            }
        }
        return metadata;
    }

    private Optional<String> extractNamedValue(AnnotationExpr annotationExpr) {
        if (annotationExpr.isSingleMemberAnnotationExpr()) {
            return Optional.of(annotationExpr.asSingleMemberAnnotationExpr()
                    .getMemberValue()
                    .toString()
                    .replace("\"", ""));
        }
        if (annotationExpr.isNormalAnnotationExpr()) {
            return annotationExpr.asNormalAnnotationExpr()
                    .getPairs()
                    .stream()
                    .filter(pair -> pair.getName().getIdentifier().equals("value"))
                    .findFirst()
                    .map(pair -> pair.getValue().toString().replace("\"", ""));
        }
        return Optional.empty();
    }
}
