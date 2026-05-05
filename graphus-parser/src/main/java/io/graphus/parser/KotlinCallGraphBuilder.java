package io.graphus.parser;

import io.graphus.model.CallGraph;
import io.graphus.model.MethodNode;
import io.graphus.model.SymbolNode;
import io.graphus.model.UnresolvedNode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.kotlin.psi.KtCallExpression;
import org.jetbrains.kotlin.psi.KtDeclarationWithBody;
import org.jetbrains.kotlin.psi.KtExpression;
import org.jetbrains.kotlin.psi.KtNameReferenceExpression;
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
                String unresolvedId =
                        "UNRESOLVED:" + signature.name() + "/" + signature.arity() + "@" + callerId
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

    private static int lineOf(KtCallExpression call) {
        org.jetbrains.kotlin.com.intellij.openapi.editor.Document document =
                org.jetbrains.kotlin.com.intellij.psi.PsiDocumentManager.getInstance(call.getProject())
                        .getDocument(call.getContainingKtFile());
        if (document == null || call.getTextRange() == null) {
            return -1;
        }
        return document.getLineNumber(call.getTextRange().getStartOffset()) + 1;
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

    private record CallSiteSignature(String name, int arity) {
    }

    public record BuildResult(int unresolvedCalls, List<UnresolvedCallRecord> records) {
    }
}
