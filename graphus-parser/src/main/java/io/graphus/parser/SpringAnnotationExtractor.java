package io.graphus.parser;

import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import io.graphus.model.HttpMapping;
import io.graphus.model.InjectionType;
import io.graphus.model.SpringMetadata;
import io.graphus.model.SpringStereotype;
import java.util.Locale;
import java.util.Optional;

public final class SpringAnnotationExtractor {

    public SpringMetadata extract(NodeList<AnnotationExpr> annotations) {
        SpringMetadata metadata = new SpringMetadata();
        for (AnnotationExpr annotation : annotations) {
            String simpleName = annotation.getName().getIdentifier();
            switch (simpleName) {
                case "RestController", "Controller" -> metadata.setStereotype(SpringStereotype.CONTROLLER);
                case "Service" -> metadata.setStereotype(SpringStereotype.SERVICE);
                case "Repository" -> metadata.setStereotype(SpringStereotype.REPOSITORY);
                case "Configuration", "Bean" -> metadata.setStereotype(SpringStereotype.CONFIGURATION);
                case "Entity", "Table" -> metadata.setStereotype(SpringStereotype.JPA_ENTITY);
                case "Component" -> metadata.setStereotype(SpringStereotype.COMPONENT);
                case "Autowired", "Inject" -> metadata.setInjectionType(InjectionType.FIELD);
                case "Transactional" -> metadata.setTransactional(true);
                case "Scheduled" -> metadata.setScheduled(true);
                case "Async" -> metadata.setAsync(true);
                default -> {
                    // non-Spring annotation
                }
            }

            HttpMapping mapping = parseMapping(annotation);
            if (mapping != null) {
                metadata.addHttpMapping(mapping);
            }
        }
        return metadata;
    }

    public SpringMetadata extractForMethod(MethodDeclaration methodDeclaration) {
        return extract(methodDeclaration.getAnnotations());
    }

    private HttpMapping parseMapping(AnnotationExpr annotationExpr) {
        String annotation = annotationExpr.getName().getIdentifier();
        return switch (annotation) {
            case "GetMapping" -> new HttpMapping("GET", findPath(annotationExpr).orElse(""));
            case "PostMapping" -> new HttpMapping("POST", findPath(annotationExpr).orElse(""));
            case "PutMapping" -> new HttpMapping("PUT", findPath(annotationExpr).orElse(""));
            case "DeleteMapping" -> new HttpMapping("DELETE", findPath(annotationExpr).orElse(""));
            case "PatchMapping" -> new HttpMapping("PATCH", findPath(annotationExpr).orElse(""));
            case "RequestMapping" -> new HttpMapping(findMethod(annotationExpr).orElse("ANY"), findPath(annotationExpr).orElse(""));
            default -> null;
        };
    }

    private Optional<String> findPath(AnnotationExpr annotationExpr) {
        if (annotationExpr.isSingleMemberAnnotationExpr()) {
            return Optional.of(annotationExpr.asSingleMemberAnnotationExpr().getMemberValue().toString().replace("\"", ""));
        }
        if (annotationExpr.isNormalAnnotationExpr()) {
            return annotationExpr.asNormalAnnotationExpr()
                    .getPairs()
                    .stream()
                    .filter(pair -> pair.getName().getIdentifier().equals("value") || pair.getName().getIdentifier().equals("path"))
                    .findFirst()
                    .map(pair -> pair.getValue().toString().replace("\"", ""));
        }
        return Optional.empty();
    }

    private Optional<String> findMethod(AnnotationExpr annotationExpr) {
        if (!annotationExpr.isNormalAnnotationExpr()) {
            return Optional.empty();
        }
        return annotationExpr.asNormalAnnotationExpr()
                .getPairs()
                .stream()
                .filter(pair -> pair.getName().getIdentifier().equals("method"))
                .findFirst()
                .map(pair -> pair.getValue().toString().replace("RequestMethod.", "").toUpperCase(Locale.ROOT));
    }
}
