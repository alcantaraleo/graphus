package io.graphus.parser;

import io.graphus.model.CallGraph;
import io.graphus.model.MethodNode;
import io.graphus.model.SymbolNode;
import io.graphus.model.UnresolvedNode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jetbrains.kotlin.psi.KtArrayAccessExpression;
import org.jetbrains.kotlin.psi.KtBinaryExpression;
import org.jetbrains.kotlin.psi.KtCallExpression;
import org.jetbrains.kotlin.psi.KtDeclarationWithBody;
import org.jetbrains.kotlin.psi.KtExpression;
import org.jetbrains.kotlin.psi.KtFunctionType;
import org.jetbrains.kotlin.psi.KtNameReferenceExpression;
import org.jetbrains.kotlin.psi.KtNamedFunction;
import org.jetbrains.kotlin.psi.KtParameter;
import org.jetbrains.kotlin.psi.KtPostfixExpression;
import org.jetbrains.kotlin.psi.KtPrefixExpression;
import org.jetbrains.kotlin.psi.KtUnaryExpression;
import org.jetbrains.kotlin.psi.KtValueArgument;

/**
 * Walks the function bodies registered by {@link KotlinSymbolVisitor} and adds Kotlin→Kotlin
 * call edges to the {@link CallGraph}.
 *
 * <p>Resolution is best-effort and structural: each call expression contributes a {@code (simple
 * callee name, argument count)} key. The builder looks up the matching Kotlin {@link MethodNode}
 * with that key and adds a direct edge when exactly one candidate exists. Ambiguous or unmatched
 * calls produce an {@link UnresolvedNode} and a structured {@link UnresolvedCallRecord} so
 * {@link CrossLanguageCallResolver} can attempt Kotlin→Java resolution in a later pass.
 */
public final class KotlinCallGraphBuilder {

    private static final Map<String, String> UNARY_OPERATOR_TO_METHOD = Map.of(
            "PLUS", "unaryPlus",
            "MINUS", "unaryMinus",
            "EXCL", "not",
            "PLUSPLUS", "inc",
            "MINUSMINUS", "dec"
    );

    private static final Map<String, String> BINARY_OPERATOR_TO_METHOD = Map.ofEntries(
            Map.entry("PLUS", "plus"),
            Map.entry("MINUS", "minus"),
            Map.entry("MUL", "times"),
            Map.entry("DIV", "div"),
            Map.entry("PERC", "rem"),
            Map.entry("PLUSEQ", "plusAssign"),
            Map.entry("MINUSEQ", "minusAssign"),
            Map.entry("MULTEQ", "timesAssign"),
            Map.entry("DIVEQ", "divAssign"),
            Map.entry("PERCEQ", "remAssign"),
            Map.entry("EQEQ", "equals"),
            Map.entry("LT", "compareTo"),
            Map.entry("GT", "compareTo"),
            Map.entry("LTEQ", "compareTo"),
            Map.entry("GTEQ", "compareTo"),
            Map.entry("RANGE", "rangeTo")
    );

    public BuildResult buildEdges(CallGraph callGraph, KotlinParserContext context) {
        Map<String, List<MethodNode>> kotlinMethodsByName = indexKotlinMethods(callGraph, context);
        List<UnresolvedCallRecord> records = new ArrayList<>();
        int unresolvedCalls = 0;

        for (Map.Entry<KtDeclarationWithBody, String> entry : context.callableIdsByDeclaration().entrySet()) {
            KtDeclarationWithBody declaration = entry.getKey();
            String callerId = entry.getValue();
            KtExpression body = declaration.getBodyExpression();
            if (body == null) {
                continue;
            }
            Set<String> lambdaParams = functionTypeParameterNames(declaration);
            for (KtCallExpression call : findAllCallExpressions(body)) {
                CallSiteSignature signature = signatureOf(call);
                if (signature == null) {
                    continue;
                }
                List<MethodNode> candidates = kotlinMethodsByName.getOrDefault(signature.name(), List.of()).stream()
                        .filter(node -> arityOf(node.getSignature()) == signature.arity())
                        .toList();

                if (candidates.size() == 1) {
                    callGraph.addEdge(callerId, candidates.get(0).getId());
                    continue;
                }
                unresolvedCalls++;
                boolean isLambdaInvocation = lambdaParams.contains(signature.name());
                String tag = isLambdaInvocation ? "UNRESOLVED:LAMBDA:" : "UNRESOLVED:";
                String unresolvedId =
                        tag + signature.name() + "/" + signature.arity() + "@" + callerId
                                + "#" + System.identityHashCode(call);
                String filePath = relativeFilePathOf(declaration);
                int line = lineOf(call);
                callGraph.addNode(new UnresolvedNode(unresolvedId, call.getText(), filePath, line));
                callGraph.addEdge(callerId, unresolvedId);
                records.add(new UnresolvedCallRecord(
                        callerId,
                        signature.name(),
                        signature.arity(),
                        unresolvedId,
                        UnresolvedCallRecord.Origin.KOTLIN));
            }
            for (KtBinaryExpression binary : findAllBinaryExpressions(body)) {
                String tokenName = binary.getOperationToken().toString();
                String methodName = BINARY_OPERATOR_TO_METHOD.get(tokenName);
                if (methodName == null) {
                    continue;
                }
                // Binary operators always take one argument (the right-hand side).
                List<MethodNode> candidates = kotlinMethodsByName.getOrDefault(methodName, List.of()).stream()
                        .filter(node -> arityOf(node.getSignature()) == 1)
                        .toList();
                if (candidates.size() == 1) {
                    callGraph.addEdge(callerId, candidates.get(0).getId());
                } else if (!candidates.isEmpty()) {
                    unresolvedCalls++;
                    String unresolvedId = "UNRESOLVED:OP:" + methodName + "/1@" + callerId
                            + "#" + System.identityHashCode(binary);
                    String filePath = relativeFilePathOf(declaration);
                    int line = lineOf(binary);
                    callGraph.addNode(new UnresolvedNode(unresolvedId, binary.getText(), filePath, line));
                    callGraph.addEdge(callerId, unresolvedId);
                    records.add(new UnresolvedCallRecord(callerId, methodName, 1, unresolvedId,
                            UnresolvedCallRecord.Origin.KOTLIN));
                }
            }
            for (KtPrefixExpression prefix : findAllPrefixExpressions(body)) {
                String tokenName = prefix.getOperationToken().toString();
                String methodName = UNARY_OPERATOR_TO_METHOD.get(tokenName);
                if (methodName == null) continue;
                unresolvedCalls += resolveUnaryOperator(callGraph, callerId, declaration, prefix,
                        methodName, kotlinMethodsByName, records);
            }
            for (KtPostfixExpression postfix : findAllPostfixExpressions(body)) {
                String tokenName = postfix.getOperationToken().toString();
                String methodName = UNARY_OPERATOR_TO_METHOD.get(tokenName);
                if (methodName == null) continue;
                unresolvedCalls += resolveUnaryOperator(callGraph, callerId, declaration, postfix,
                        methodName, kotlinMethodsByName, records);
            }
            for (KtArrayAccessExpression arrayAccess : findAllArrayAccesses(body)) {
                int indexCount = arrayAccess.getIndexExpressions().size();
                boolean isWrite = isLhsOfAssignment(arrayAccess);
                String methodName = isWrite ? "set" : "get";
                int arity = isWrite ? indexCount + 1 : indexCount;
                List<MethodNode> candidates = kotlinMethodsByName.getOrDefault(methodName, List.of()).stream()
                        .filter(node -> arityOf(node.getSignature()) == arity)
                        .toList();
                if (candidates.size() == 1) {
                    callGraph.addEdge(callerId, candidates.get(0).getId());
                } else if (!candidates.isEmpty()) {
                    unresolvedCalls++;
                    String unresolvedId = "UNRESOLVED:OP:" + methodName + "/" + arity + "@" + callerId
                            + "#" + System.identityHashCode(arrayAccess);
                    callGraph.addNode(new UnresolvedNode(
                            unresolvedId, arrayAccess.getText(),
                            relativeFilePathOf(declaration), lineOf(arrayAccess)));
                    callGraph.addEdge(callerId, unresolvedId);
                    records.add(new UnresolvedCallRecord(callerId, methodName, arity, unresolvedId,
                            UnresolvedCallRecord.Origin.KOTLIN));
                }
            }
        }

        return new BuildResult(unresolvedCalls, List.copyOf(records));
    }

    private static Map<String, List<MethodNode>> indexKotlinMethods(CallGraph callGraph, KotlinParserContext context) {
        Collection<String> kotlinMethodIds = context.callableIdsByDeclaration().values();
        Map<String, List<MethodNode>> bySimpleName = new HashMap<>();
        for (String methodId : kotlinMethodIds) {
            SymbolNode node = callGraph.getNode(methodId);
            if (node instanceof MethodNode methodNode) {
                bySimpleName.computeIfAbsent(methodNode.getName(), key -> new ArrayList<>()).add(methodNode);
            }
        }
        return bySimpleName;
    }

    private static List<KtCallExpression> findAllCallExpressions(KtExpression root) {
        List<KtCallExpression> calls = new ArrayList<>();
        if (root instanceof KtCallExpression rootCall) {
            calls.add(rootCall);
        }
        org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
                .findChildrenOfType(root, KtCallExpression.class)
                .forEach(calls::add);
        return calls;
    }

    private static List<KtBinaryExpression> findAllBinaryExpressions(KtExpression root) {
        List<KtBinaryExpression> result = new ArrayList<>();
        if (root instanceof KtBinaryExpression rootBin) {
            result.add(rootBin);
        }
        org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
                .findChildrenOfType(root, KtBinaryExpression.class)
                .forEach(result::add);
        return result;
    }

    private static List<KtPrefixExpression> findAllPrefixExpressions(KtExpression root) {
        List<KtPrefixExpression> result = new ArrayList<>();
        if (root instanceof KtPrefixExpression r) {
            result.add(r);
        }
        org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
                .findChildrenOfType(root, KtPrefixExpression.class)
                .forEach(result::add);
        return result;
    }

    private static List<KtPostfixExpression> findAllPostfixExpressions(KtExpression root) {
        List<KtPostfixExpression> result = new ArrayList<>();
        if (root instanceof KtPostfixExpression r) {
            result.add(r);
        }
        org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
                .findChildrenOfType(root, KtPostfixExpression.class)
                .forEach(result::add);
        return result;
    }

    private static List<KtArrayAccessExpression> findAllArrayAccesses(KtExpression root) {
        List<KtArrayAccessExpression> result = new ArrayList<>();
        if (root instanceof KtArrayAccessExpression r) {
            result.add(r);
        }
        org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
                .findChildrenOfType(root, KtArrayAccessExpression.class)
                .forEach(result::add);
        return result;
    }

    private static boolean isLhsOfAssignment(KtArrayAccessExpression arrayAccess) {
        org.jetbrains.kotlin.com.intellij.psi.PsiElement parent = arrayAccess.getParent();
        if (!(parent instanceof KtBinaryExpression binary)) {
            return false;
        }
        return "EQ".equals(binary.getOperationToken().toString())
                && binary.getLeft() == arrayAccess;
    }

    private static int resolveUnaryOperator(
            CallGraph callGraph,
            String callerId,
            KtDeclarationWithBody declaration,
            KtUnaryExpression unary,
            String methodName,
            Map<String, List<MethodNode>> kotlinMethodsByName,
            List<UnresolvedCallRecord> records) {
        List<MethodNode> candidates = kotlinMethodsByName.getOrDefault(methodName, List.of()).stream()
                .filter(node -> arityOf(node.getSignature()) == 0)
                .toList();
        if (candidates.size() == 1) {
            callGraph.addEdge(callerId, candidates.get(0).getId());
            return 0;
        } else if (!candidates.isEmpty()) {
            String unresolvedId = "UNRESOLVED:OP:" + methodName + "/0@" + callerId
                    + "#" + System.identityHashCode(unary);
            callGraph.addNode(new UnresolvedNode(
                    unresolvedId, unary.getText(), relativeFilePathOf(declaration), lineOf(unary)));
            callGraph.addEdge(callerId, unresolvedId);
            records.add(new UnresolvedCallRecord(callerId, methodName, 0, unresolvedId,
                    UnresolvedCallRecord.Origin.KOTLIN));
            return 1;
        }
        return 0;
    }

    private static CallSiteSignature signatureOf(KtCallExpression call) {
        KtExpression callee = call.getCalleeExpression();
        if (callee == null) {
            return null;
        }
        String name;
        if (callee instanceof KtNameReferenceExpression nameReference) {
            name = nameReference.getReferencedName();
        } else {
            String text = callee.getText();
            if (text == null || text.isBlank()) {
                return null;
            }
            int genericStart = text.indexOf('<');
            if (genericStart >= 0) {
                text = text.substring(0, genericStart);
            }
            int dot = text.lastIndexOf('.');
            name = dot >= 0 ? text.substring(dot + 1) : text;
        }
        if (name == null || name.isBlank()) {
            return null;
        }
        int arity = 0;
        for (KtValueArgument ignored : call.getValueArguments()) {
            arity++;
        }
        return new CallSiteSignature(name, arity);
    }

    static int arityOf(String signature) {
        if (signature == null) {
            return 0;
        }
        int open = signature.indexOf('(');
        int close = signature.lastIndexOf(')');
        if (open < 0 || close <= open) {
            return 0;
        }
        String inside = signature.substring(open + 1, close).trim();
        if (inside.isEmpty()) {
            return 0;
        }
        int count = 1;
        int depth = 0;
        for (int i = 0; i < inside.length(); i++) {
            char c = inside.charAt(i);
            if (c == '<' || c == '(') {
                depth++;
            } else if (c == '>' || c == ')') {
                depth = Math.max(0, depth - 1);
            } else if (c == ',' && depth == 0) {
                count++;
            }
        }
        return count;
    }

    private static int lineOf(org.jetbrains.kotlin.psi.KtElement element) {
        org.jetbrains.kotlin.com.intellij.openapi.editor.Document document =
                org.jetbrains.kotlin.com.intellij.psi.PsiDocumentManager.getInstance(element.getProject())
                        .getDocument(element.getContainingKtFile());
        if (document == null || element.getTextRange() == null) {
            return -1;
        }
        return document.getLineNumber(element.getTextRange().getStartOffset()) + 1;
    }

    private static String relativeFilePathOf(KtDeclarationWithBody declaration) {
        if (declaration.getContainingKtFile() == null) {
            return "";
        }
        try {
            String virtual = declaration.getContainingKtFile().getVirtualFilePath();
            if (virtual != null) {
                return virtual;
            }
        } catch (NullPointerException missingVirtualFile) {
            // PsiFiles created in memory (tests) do not back a real VirtualFile.
        }
        return declaration.getContainingKtFile().getName();
    }

    private static Set<String> functionTypeParameterNames(KtDeclarationWithBody declaration) {
        if (!(declaration instanceof KtNamedFunction function)) {
            return Set.of();
        }
        Set<String> names = new HashSet<>();
        for (KtParameter parameter : function.getValueParameters()) {
            if (parameter.getTypeReference() != null
                    && parameter.getTypeReference().getTypeElement() instanceof KtFunctionType) {
                String name = parameter.getName();
                if (name != null && !name.isBlank()) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    private record CallSiteSignature(String name, int arity) {
    }

    public record BuildResult(int unresolvedCalls, List<UnresolvedCallRecord> records) {
    }
}
