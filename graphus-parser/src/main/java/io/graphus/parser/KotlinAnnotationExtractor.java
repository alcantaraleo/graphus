package io.graphus.parser;

import io.graphus.model.GuiceMetadata;
import io.graphus.model.GuiceStereotype;
import io.graphus.model.HttpMapping;
import io.graphus.model.InjectionType;
import io.graphus.model.SpringMetadata;
import io.graphus.model.SpringStereotype;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.jetbrains.kotlin.psi.KtAnnotationEntry;
import org.jetbrains.kotlin.psi.KtConstantExpression;
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression;
import org.jetbrains.kotlin.psi.KtExpression;
import org.jetbrains.kotlin.psi.KtStringTemplateExpression;
import org.jetbrains.kotlin.psi.ValueArgument;

/**
 * Extracts Spring and Guice metadata from Kotlin {@link KtAnnotationEntry} nodes.
 *
 * <p>Mirrors the behaviour of {@link SpringAnnotationExtractor} and {@link GuiceAnnotationExtractor}
 * for Kotlin sources. Annotation simple names are matched against the same set of known stereotype
 * names so Kotlin and Java code share a single {@code SpringStereotype}/{@code GuiceStereotype}
 * vocabulary in the {@code CallGraph}.
 *
 * <p>Guice {@code MODULE} stereotype detection uses the supertype list passed by
 * {@link KotlinSymbolVisitor} since annotations alone do not signal "extends AbstractModule".
 */
public final class KotlinAnnotationExtractor {

    public SpringMetadata extractSpring(List<KtAnnotationEntry> annotations) {
        SpringMetadata metadata = new SpringMetadata();
        for (KtAnnotationEntry annotation : annotations) {
            String simpleName = simpleNameOf(annotation);
            applySpringStereotype(metadata, simpleName);
            HttpMapping mapping = parseMapping(annotation, simpleName);
            if (mapping != null) {
                metadata.addHttpMapping(mapping);
            }
        }
        return metadata;
    }

    public GuiceMetadata extractGuice(List<KtAnnotationEntry> annotations, InjectionType injectTarget) {
        GuiceMetadata metadata = new GuiceMetadata();
        for (KtAnnotationEntry annotation : annotations) {
            String simpleName = simpleNameOf(annotation);
            switch (simpleName) {
                case "Inject" -> metadata.setInjectionType(injectTarget);
                case "Provides" -> metadata.setStereotype(GuiceStereotype.PROVIDES);
                case "Singleton" -> metadata.setSingleton(true);
                case "RequestScoped" -> metadata.setRequestScoped(true);
                case "SessionScoped" -> metadata.setSessionScoped(true);
                case "Named" -> singleStringArgument(annotation).ifPresent(metadata::setNamedValue);
                default -> {
                    // non-Guice annotation
                }
            }
        }
        return metadata;
    }

    /**
     * Adds the Guice {@code MODULE} stereotype when the Kotlin class extends a recognized Guice
     * module supertype. Kotlin uses single supertype list (no separate {@code extends}/{@code implements})
     * so callers pass the simple names of every {@code SuperTypeListEntry}.
     */
    public GuiceMetadata extractGuiceForClass(List<KtAnnotationEntry> annotations, List<String> supertypeSimpleNames) {
        GuiceMetadata metadata = extractGuice(annotations, InjectionType.NONE);
        for (String supertype : supertypeSimpleNames) {
            if ("AbstractModule".equals(supertype) || "PrivateModule".equals(supertype)) {
                metadata.setStereotype(GuiceStereotype.MODULE);
                break;
            }
        }
        return metadata;
    }

    private void applySpringStereotype(SpringMetadata metadata, String simpleName) {
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
    }

    private HttpMapping parseMapping(KtAnnotationEntry annotation, String simpleName) {
        return switch (simpleName) {
            case "GetMapping" -> new HttpMapping("GET", firstStringArgument(annotation).orElse(""));
            case "PostMapping" -> new HttpMapping("POST", firstStringArgument(annotation).orElse(""));
            case "PutMapping" -> new HttpMapping("PUT", firstStringArgument(annotation).orElse(""));
            case "DeleteMapping" -> new HttpMapping("DELETE", firstStringArgument(annotation).orElse(""));
            case "PatchMapping" -> new HttpMapping("PATCH", firstStringArgument(annotation).orElse(""));
            case "RequestMapping" -> new HttpMapping(
                    namedArgument(annotation, "method").orElse("ANY"),
                    namedArgument(annotation, "path").or(() -> namedArgument(annotation, "value"))
                            .or(() -> firstStringArgument(annotation))
                            .orElse(""));
            default -> null;
        };
    }

    static String simpleNameOf(KtAnnotationEntry annotation) {
        if (annotation.getTypeReference() == null || annotation.getTypeReference().getText() == null) {
            return "";
        }
        String text = annotation.getTypeReference().getText().trim();
        int genericStart = text.indexOf('<');
        if (genericStart >= 0) {
            text = text.substring(0, genericStart);
        }
        int dot = text.lastIndexOf('.');
        return dot >= 0 ? text.substring(dot + 1) : text;
    }

    /** Returns the first positional value argument as a plain string, stripping any wrapping quotes. */
    private static Optional<String> firstStringArgument(KtAnnotationEntry annotation) {
        for (ValueArgument argument : annotation.getValueArguments()) {
            if (argument.getArgumentName() != null) {
                continue;
            }
            String literal = literalText(argument.getArgumentExpression());
            if (literal != null) {
                return Optional.of(literal);
            }
        }
        return Optional.empty();
    }

    private static Optional<String> singleStringArgument(KtAnnotationEntry annotation) {
        for (ValueArgument argument : annotation.getValueArguments()) {
            String literal = literalText(argument.getArgumentExpression());
            if (literal != null) {
                return Optional.of(literal);
            }
        }
        return Optional.empty();
    }

    private static Optional<String> namedArgument(KtAnnotationEntry annotation, String parameterName) {
        for (ValueArgument argument : annotation.getValueArguments()) {
            if (argument.getArgumentName() == null) {
                continue;
            }
            String name = argument.getArgumentName().getAsName().asString();
            if (!parameterName.equals(name)) {
                continue;
            }
            KtExpression expression = argument.getArgumentExpression();
            String literal = literalText(expression);
            if (literal != null) {
                return Optional.of(literal);
            }
            if (expression instanceof KtDotQualifiedExpression dotQualified) {
                String selector = dotQualified.getSelectorExpression() == null
                        ? ""
                        : dotQualified.getSelectorExpression().getText();
                return Optional.of(selector.toUpperCase(Locale.ROOT));
            }
        }
        return Optional.empty();
    }

    private static String literalText(KtExpression expression) {
        if (expression instanceof KtStringTemplateExpression template) {
            String raw = template.getText();
            if (raw == null) {
                return "";
            }
            if (raw.length() >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
                return raw.substring(1, raw.length() - 1);
            }
            return raw;
        }
        if (expression instanceof KtConstantExpression constant) {
            return constant.getText();
        }
        return null;
    }
}
