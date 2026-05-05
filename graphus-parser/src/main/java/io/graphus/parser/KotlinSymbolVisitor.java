package io.graphus.parser;

import io.graphus.model.ClassNode;
import io.graphus.model.FieldNode;
import io.graphus.model.GuiceMetadata;
import io.graphus.model.InjectionType;
import io.graphus.model.MethodNode;
import io.graphus.model.MethodParam;
import io.graphus.model.SpringMetadata;
import io.graphus.model.SymbolKind;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.kotlin.psi.KtAnnotationEntry;
import org.jetbrains.kotlin.psi.KtClass;
import org.jetbrains.kotlin.psi.KtClassOrObject;
import org.jetbrains.kotlin.psi.KtConstructor;
import org.jetbrains.kotlin.psi.KtDeclaration;
import org.jetbrains.kotlin.psi.KtFile;
import org.jetbrains.kotlin.psi.KtNamedFunction;
import org.jetbrains.kotlin.psi.KtObjectDeclaration;
import org.jetbrains.kotlin.psi.KtParameter;
import org.jetbrains.kotlin.psi.KtPrimaryConstructor;
import org.jetbrains.kotlin.psi.KtProperty;
import org.jetbrains.kotlin.psi.KtSecondaryConstructor;
import org.jetbrains.kotlin.psi.KtSuperTypeListEntry;
import org.jetbrains.kotlin.psi.KtTypeReference;

/**
 * Visits a {@link KtFile} PSI tree and populates a {@link io.graphus.model.CallGraph} with
 * {@link ClassNode}, {@link MethodNode}, and {@link FieldNode} entries.
 *
 * <p>Symbol IDs follow the same {@code package.ClassName.method(ParamType)} convention as
 * {@link SymbolVisitor} for Java, sharing one namespace in the {@code CallGraph}. Top-level Kotlin
 * functions are attached to a synthetic class id derived from the file name (e.g. {@code FooKt}),
 * mirroring how the Kotlin compiler emits JVM facade classes.
 *
 * <p>Each {@link org.jetbrains.kotlin.psi.KtDeclarationWithBody} encountered (named function,
 * primary/secondary constructor) is registered in {@link KotlinParserContext} so
 * {@link KotlinCallGraphBuilder} can walk the body for call expressions in a later pass.
 */
public final class KotlinSymbolVisitor {

    private final KotlinAnnotationExtractor annotationExtractor;
    private final Path repositoryRoot;

    public KotlinSymbolVisitor(KotlinAnnotationExtractor annotationExtractor, Path repositoryRoot) {
        this.annotationExtractor = annotationExtractor;
        this.repositoryRoot = repositoryRoot == null ? null : repositoryRoot.toAbsolutePath().normalize();
    }

    public void visit(KtFile file, KotlinParserContext context) {
        String packageName = file.getPackageFqName().asString();
        String filePath = relativizeFilePath(file);

        // Class/interface/object declarations.
        for (KtClassOrObject declaration : file.getDeclarations().stream()
                .filter(KtClassOrObject.class::isInstance)
                .map(KtClassOrObject.class::cast)
                .toList()) {
            visitClassOrObject(declaration, packageName, filePath, context);
        }

        // Top-level functions and properties get a synthetic file-facade class.
        List<KtNamedFunction> topLevelFunctions = file.getDeclarations().stream()
                .filter(KtNamedFunction.class::isInstance)
                .map(KtNamedFunction.class::cast)
                .toList();
        List<KtProperty> topLevelProperties = file.getDeclarations().stream()
                .filter(KtProperty.class::isInstance)
                .map(KtProperty.class::cast)
                .toList();

        if (topLevelFunctions.isEmpty() && topLevelProperties.isEmpty()) {
            return;
        }

        String facadeSimpleName = facadeSimpleName(file);
        String facadeId = qualify(packageName, facadeSimpleName);
        ClassNode facadeNode = new ClassNode(
                facadeId,
                facadeSimpleName,
                filePath,
                lineOf(file),
                List.of("@FileFacade"),
                "",
                List.of(),
                new SpringMetadata(),
                new GuiceMetadata());
        context.callGraph().addNode(facadeNode);

        for (KtNamedFunction function : topLevelFunctions) {
            visitFunction(function, facadeId, filePath, context, facadeNode);
        }
        for (KtProperty property : topLevelProperties) {
            visitProperty(property, facadeId, filePath, context, facadeNode);
        }
    }

    private void visitClassOrObject(
            KtClassOrObject declaration, String packageName, String filePath, KotlinParserContext context) {
        String simpleName = declaration.getName();
        if (simpleName == null || simpleName.isBlank()) {
            return;
        }
        String classId = qualify(packageName, simpleName);
        List<KtAnnotationEntry> annotations = declaration.getAnnotationEntries();
        List<String> annotationLabels = annotationLabels(annotations);
        List<String> supertypeNames = new ArrayList<>();
        for (KtSuperTypeListEntry entry : declaration.getSuperTypeListEntries()) {
            KtTypeReference typeReference = entry.getTypeReference();
            if (typeReference != null) {
                supertypeNames.add(simpleNameOfType(typeReference));
            }
        }

        SpringMetadata springMetadata = annotationExtractor.extractSpring(annotations);
        GuiceMetadata guiceMetadata = annotationExtractor.extractGuiceForClass(annotations, supertypeNames);

        String superClass = supertypeNames.isEmpty() ? "" : supertypeNames.get(0);
        List<String> interfaces = supertypeNames.size() > 1
                ? supertypeNames.subList(1, supertypeNames.size())
                : List.of();

        ClassNode classNode = new ClassNode(
                classId,
                simpleName,
                filePath,
                lineOf(declaration),
                annotationLabels,
                superClass,
                interfaces,
                springMetadata,
                guiceMetadata);
        context.callGraph().addNode(classNode);

        // Primary constructor (declared inline on the class header).
        if (declaration instanceof KtClass ktClass) {
            KtPrimaryConstructor primary = ktClass.getPrimaryConstructor();
            if (primary != null) {
                visitConstructor(primary, classId, simpleName, filePath, context, classNode);
            }
            for (KtParameter parameter : ktClass.getPrimaryConstructorParameters()) {
                if (parameter.hasValOrVar()) {
                    visitProperty(parameter, classId, filePath, context, classNode);
                }
            }
            for (KtSecondaryConstructor secondary : ktClass.getSecondaryConstructors()) {
                visitConstructor(secondary, classId, simpleName, filePath, context, classNode);
            }
        } else if (declaration instanceof KtObjectDeclaration objectDeclaration) {
            for (KtSecondaryConstructor secondary : objectDeclaration.getSecondaryConstructors()) {
                visitConstructor(secondary, classId, simpleName, filePath, context, classNode);
            }
        }

        // Body declarations: properties, member functions, nested classes/objects.
        if (declaration.getBody() != null) {
            for (KtDeclaration nested : declaration.getBody().getDeclarations()) {
                if (nested instanceof KtNamedFunction function) {
                    visitFunction(function, classId, filePath, context, classNode);
                } else if (nested instanceof KtProperty property) {
                    visitProperty(property, classId, filePath, context, classNode);
                } else if (nested instanceof KtClassOrObject nestedClass) {
                    visitClassOrObject(nestedClass, packageName + "." + simpleName, filePath, context);
                }
            }
        }
    }

    private void visitFunction(
            KtNamedFunction function,
            String classId,
            String filePath,
            KotlinParserContext context,
            ClassNode owner) {
        String name = function.getName();
        if (name == null || name.isBlank()) {
            return;
        }
        List<KtAnnotationEntry> annotations = function.getAnnotationEntries();
        List<MethodParam> params = parametersFrom(function.getValueParameters());
        String signature = signature(name, params);
        String methodId = classId + "." + signature;
        SpringMetadata springMetadata = annotationExtractor.extractSpring(annotations);
        GuiceMetadata guiceMetadata = annotationExtractor.extractGuice(annotations, InjectionType.METHOD);

        MethodNode methodNode = new MethodNode(
                methodId,
                SymbolKind.METHOD,
                classId,
                name,
                signature,
                returnTypeOf(function),
                params,
                modifiersOf(function),
                annotationLabels(annotations),
                filePath,
                lineOf(function),
                springMetadata,
                guiceMetadata);
        context.callGraph().addNode(methodNode);
        context.registerCallable(function, methodId);
        owner.addMethod(methodId);
    }

    private void visitConstructor(
            KtConstructor<?> constructor,
            String classId,
            String simpleName,
            String filePath,
            KotlinParserContext context,
            ClassNode owner) {
        List<KtAnnotationEntry> annotations = constructor.getAnnotationEntries();
        List<MethodParam> params = parametersFrom(constructor.getValueParameters());
        String signature = signature(simpleName, params);
        String constructorId = classId + "#" + signature;
        SpringMetadata springMetadata = annotationExtractor.extractSpring(annotations);
        GuiceMetadata guiceMetadata = annotationExtractor.extractGuice(annotations, InjectionType.CONSTRUCTOR);

        MethodNode constructorNode = new MethodNode(
                constructorId,
                SymbolKind.CONSTRUCTOR,
                classId,
                simpleName,
                signature,
                simpleName,
                params,
                List.of(),
                annotationLabels(annotations),
                filePath,
                lineOf(constructor),
                springMetadata,
                guiceMetadata);
        context.callGraph().addNode(constructorNode);
        context.registerCallable(constructor, constructorId);
        owner.addMethod(constructorId);
    }

    private void visitProperty(
            KtProperty property, String classId, String filePath, KotlinParserContext context, ClassNode owner) {
        String name = property.getName();
        if (name == null || name.isBlank()) {
            return;
        }
        List<KtAnnotationEntry> annotations = property.getAnnotationEntries();
        SpringMetadata springMetadata = annotationExtractor.extractSpring(annotations);
        GuiceMetadata guiceMetadata = annotationExtractor.extractGuice(annotations, InjectionType.FIELD);
        String fieldId = classId + "#" + name;
        FieldNode fieldNode = new FieldNode(
                fieldId,
                classId,
                name,
                typeOfProperty(property),
                filePath,
                lineOf(property),
                annotationLabels(annotations),
                springMetadata,
                guiceMetadata);
        context.callGraph().addNode(fieldNode);
        owner.addField(fieldId);
    }

    private void visitProperty(
            KtParameter parameter,
            String classId,
            String filePath,
            KotlinParserContext context,
            ClassNode owner) {
        String name = parameter.getName();
        if (name == null || name.isBlank()) {
            return;
        }
        List<KtAnnotationEntry> annotations = parameter.getAnnotationEntries();
        SpringMetadata springMetadata = annotationExtractor.extractSpring(annotations);
        GuiceMetadata guiceMetadata = annotationExtractor.extractGuice(annotations, InjectionType.FIELD);
        String fieldId = classId + "#" + name;
        String prefix = parameter.isMutable() ? "var" : "val";
        String typeText = parameter.getTypeReference() == null ? "" : parameter.getTypeReference().getText();
        String displayType = typeText.isBlank() ? prefix : prefix + " " + typeText;
        FieldNode fieldNode = new FieldNode(
                fieldId,
                classId,
                name,
                displayType,
                filePath,
                lineOf(parameter),
                annotationLabels(annotations),
                springMetadata,
                guiceMetadata);
        context.callGraph().addNode(fieldNode);
        owner.addField(fieldId);
    }

    private List<MethodParam> parametersFrom(List<KtParameter> parameters) {
        List<MethodParam> result = new ArrayList<>();
        for (KtParameter parameter : parameters) {
            String paramName = parameter.getName() == null ? "" : parameter.getName();
            String paramType = parameter.getTypeReference() == null
                    ? "Any"
                    : parameter.getTypeReference().getText();
            result.add(new MethodParam(paramName, paramType));
        }
        return result;
    }

    private static String signature(String name, List<MethodParam> params) {
        StringBuilder sb = new StringBuilder(name).append('(');
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(params.get(i).type());
        }
        sb.append(')');
        return sb.toString();
    }

    private static String returnTypeOf(KtNamedFunction function) {
        KtTypeReference typeReference = function.getTypeReference();
        return typeReference == null ? "Unit" : typeReference.getText();
    }

    private static String typeOfProperty(KtProperty property) {
        String prefix = property.isVar() ? "var" : "val";
        KtTypeReference typeReference = property.getTypeReference();
        if (typeReference != null) {
            return prefix + " " + typeReference.getText();
        }
        return prefix;
    }

    private static List<String> modifiersOf(KtNamedFunction function) {
        List<String> modifiers = new ArrayList<>();
        if (function.getModifierList() == null) {
            return modifiers;
        }
        String text = function.getModifierList().getText();
        for (String token : text.split("\\s+")) {
            if (!token.isBlank() && !token.startsWith("@")) {
                modifiers.add(token);
            }
        }
        return modifiers;
    }

    private static List<String> annotationLabels(List<KtAnnotationEntry> annotations) {
        List<String> labels = new ArrayList<>();
        for (KtAnnotationEntry annotation : annotations) {
            labels.add("@" + KotlinAnnotationExtractor.simpleNameOf(annotation));
        }
        return labels;
    }

    private static String simpleNameOfType(KtTypeReference reference) {
        String text = reference.getText() == null ? "" : reference.getText().trim();
        int genericStart = text.indexOf('<');
        if (genericStart >= 0) {
            text = text.substring(0, genericStart);
        }
        int dot = text.lastIndexOf('.');
        return dot >= 0 ? text.substring(dot + 1) : text;
    }

    private static int lineOf(org.jetbrains.kotlin.psi.KtElement element) {
        if (element == null || element.getContainingKtFile() == null) {
            return -1;
        }
        org.jetbrains.kotlin.com.intellij.openapi.editor.Document document =
                org.jetbrains.kotlin.com.intellij.psi.PsiDocumentManager.getInstance(element.getProject())
                        .getDocument(element.getContainingKtFile());
        if (document == null) {
            return -1;
        }
        int offset = element.getTextRange() == null ? -1 : element.getTextRange().getStartOffset();
        return offset < 0 ? -1 : document.getLineNumber(offset) + 1;
    }

    private String relativizeFilePath(KtFile file) {
        String virtual = safeVirtualFilePath(file);
        if (virtual == null || virtual.isBlank()) {
            virtual = file.getName();
        }
        if (virtual == null || virtual.isBlank()) {
            return "";
        }
        Path absolute;
        try {
            absolute = Paths.get(virtual).toAbsolutePath().normalize();
        } catch (RuntimeException invalid) {
            return virtual;
        }
        if (repositoryRoot != null && absolute.startsWith(repositoryRoot)) {
            return repositoryRoot.relativize(absolute).toString();
        }
        return absolute.toString();
    }

    /**
     * {@link KtFile#getVirtualFilePath()} dereferences {@code getVirtualFile()} unconditionally,
     * which is null for files built directly from a PSI factory in tests.
     */
    private static String safeVirtualFilePath(KtFile file) {
        try {
            return file.getVirtualFilePath();
        } catch (NullPointerException missingVirtualFile) {
            return null;
        }
    }

    private static String facadeSimpleName(KtFile file) {
        String fileName = file.getName();
        if (fileName == null || fileName.isBlank()) {
            return "TopLevelKt";
        }
        String stripped = fileName;
        int slash = Math.max(stripped.lastIndexOf('/'), stripped.lastIndexOf('\\'));
        if (slash >= 0) {
            stripped = stripped.substring(slash + 1);
        }
        if (stripped.endsWith(".kt")) {
            stripped = stripped.substring(0, stripped.length() - 3);
        }
        if (stripped.isBlank()) {
            return "TopLevelKt";
        }
        char first = Character.toUpperCase(stripped.charAt(0));
        String rest = stripped.length() > 1 ? stripped.substring(1) : "";
        return first + rest + "Kt";
    }

    private static String qualify(String packageName, String simpleName) {
        if (packageName == null || packageName.isBlank()) {
            return simpleName;
        }
        return packageName + "." + simpleName;
    }
}
