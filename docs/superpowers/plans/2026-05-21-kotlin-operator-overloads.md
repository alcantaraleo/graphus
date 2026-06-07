# Kotlin Operator Overloads, Property Accessors, and Lambda Invocations Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Detect Kotlin operator overload calls (`a + b`, `a[i]`, `++x`), property accessor invocations, and lambda parameter invocations in `KotlinCallGraphBuilder` and emit `CallEdge`s where a unique indexed method exists, or well-typed `UnresolvedNode` placeholders otherwise.

**Architecture:** All changes are confined to `KotlinCallGraphBuilder`. Three new PSI node types are walked in `buildEdges`: `KtBinaryExpression` (binary operators → named `operator fun`), `KtUnaryExpression` (prefix/postfix operators), and `KtArrayAccessExpression` (→ `get`/`set`). Each is mapped to a Kotlin operator method name by convention, then resolved against the call graph using the existing name+arity index. Lambda invocations (calling a function-type parameter as `handler(arg)`) are detected by checking whether the callee name matches an enclosing function's parameter that has a function type; when detected, a well-typed `UnresolvedNode` is emitted instead of a plain unresolved call, tagged with `UNRESOLVED:LAMBDA:`. Property accessor calls within the same file (reading a `val`/`var` that maps to an indexed `FieldNode`) are left for a follow-up; this scope covers operator and lambda forms which are fully tractable within the structural PSI.

**Tech Stack:** Java 21, Kotlin PSI (`KtBinaryExpression`, `KtPrefixExpression`, `KtPostfixExpression`, `KtArrayAccessExpression`, `KtNameReferenceExpression`), `KotlinCallGraphBuilder`, `UnresolvedCallRecord`, `UnresolvedNode`, JUnit 5.

---

## File Map

| File | Action | Purpose |
|------|--------|---------|
| `graphus-parser/src/main/java/io/graphus/parser/KotlinCallGraphBuilder.java` | Modify | Add operator/array/lambda detection; operator→method name maps; helpers to walk new PSI node types |
| `graphus-parser/src/test/java/io/graphus/parser/KotlinCallGraphBuilderTest.java` | Modify | Add test cases for each new call form |

---

### Kotlin operator-to-method-name convention (reference)

| Token `toString()` | Kotlin operator fun | Arity |
|---|---|---|
| `PLUS` (binary) | `plus` | 1 |
| `MINUS` (binary) | `minus` | 1 |
| `MUL` | `times` | 1 |
| `DIV` | `div` | 1 |
| `PERC` | `rem` | 1 |
| `PLUSEQ` | `plusAssign` | 1 |
| `MINUSEQ` | `minusAssign` | 1 |
| `MULTEQ` | `timesAssign` | 1 |
| `DIVEQ` | `divAssign` | 1 |
| `PERCEQ` | `remAssign` | 1 |
| `EQEQ` | `equals` | 1 |
| `LT`, `GT`, `LTEQ`, `GTEQ` | `compareTo` | 1 |
| `RANGE` | `rangeTo` | 1 |
| `PLUS` (prefix) | `unaryPlus` | 0 |
| `MINUS` (prefix) | `unaryMinus` | 0 |
| `EXCL` | `not` | 0 |
| `PLUSPLUS` (prefix/postfix) | `inc` | 0 |
| `MINUSMINUS` (prefix/postfix) | `dec` | 0 |
| Array access (read) | `get` | = # index expressions |
| Array access (write) | `set` | = # index expressions + 1 |

---

### Task 1: Binary operator call edges

**Files:**
- Modify: `graphus-parser/src/main/java/io/graphus/parser/KotlinCallGraphBuilder.java`
- Test: `graphus-parser/src/test/java/io/graphus/parser/KotlinCallGraphBuilderTest.java`

- [ ] **Step 1: Write the failing test**

Add to `KotlinCallGraphBuilderTest`:

```java
@Test
void resolvesBinaryOperatorOverloadAsCallEdge(@TempDir Path tempDir) throws IOException {
    Path source = writeFile(
            tempDir,
            "Vector.kt",
            """
            package demo

            data class Vector(val x: Int, val y: Int) {
                operator fun plus(other: Vector): Vector = Vector(x + other.x, y + other.y)
                fun combine(other: Vector): Vector = this + other
            }
            """);

    BuildOutput output = parseAndBuild(tempDir, source);

    assertTrue(
            output.graph().getEdges().contains(
                    new CallEdge("demo.Vector.combine(Vector)", "demo.Vector.plus(Vector)")),
            "Binary '+' on Vector must resolve to operator fun plus(Vector)");
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
./gradlew :graphus-parser:test --tests "io.graphus.parser.KotlinCallGraphBuilderTest.resolvesBinaryOperatorOverloadAsCallEdge" --no-daemon 2>&1 | tail -20
```

Expected: FAIL — no edge from `combine` to `plus`.

- [ ] **Step 3: Add the binary operator map and walker**

In `KotlinCallGraphBuilder.java`, add after the class-level `CallSiteSignature` record:

```java
import java.util.Map;
import org.jetbrains.kotlin.psi.KtBinaryExpression;

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
```

Add a private static helper to walk binary expressions:

```java
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
```

In `buildEdges`, after the existing `for (KtCallExpression call : findAllCallExpressions(body))` loop, add:

```java
for (KtBinaryExpression binary : findAllBinaryExpressions(body)) {
    String tokenName = binary.getOperationToken().toString();
    String methodName = BINARY_OPERATOR_TO_METHOD.get(tokenName);
    if (methodName == null) {
        continue; // not an operator overload convention (e.g. assignment =)
    }
    // Binary operator: one argument (the right-hand side).
    CallSiteSignature signature = new CallSiteSignature(methodName, 1);
    List<MethodNode> candidates = kotlinMethodsByName.getOrDefault(signature.name(), List.of()).stream()
            .filter(node -> arityOf(node.getSignature()) == signature.arity())
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
```

Note: the variable `binary` shadows the outer `call` variable in a separate for-loop — no conflict.

The `lineOf(KtBinaryExpression)` call needs the existing `lineOf(KtCallExpression)` helper to be generalised. Update `lineOf` to accept any `KtElement`:

Rename the existing:
```java
private static int lineOf(KtCallExpression call) { ... }
```

To:
```java
private static int lineOf(org.jetbrains.kotlin.psi.KtElement element) {
    org.jetbrains.kotlin.com.intellij.openapi.editor.Document document =
            org.jetbrains.kotlin.com.intellij.psi.PsiDocumentManager
                    .getInstance(element.getProject())
                    .getDocument(element.getContainingKtFile());
    if (document == null || element.getTextRange() == null) {
        return -1;
    }
    return document.getLineNumber(element.getTextRange().getStartOffset()) + 1;
}
```

All existing call sites (`lineOf(call)`) continue to compile since `KtCallExpression` is a `KtElement`.

- [ ] **Step 4: Run the test to verify it passes**

```bash
./gradlew :graphus-parser:test --tests "io.graphus.parser.KotlinCallGraphBuilderTest.resolvesBinaryOperatorOverloadAsCallEdge" --no-daemon 2>&1 | tail -20
```

Expected: PASS.

- [ ] **Step 5: Run all existing tests to check for regressions**

```bash
./gradlew :graphus-parser:test --no-daemon 2>&1 | tail -20
```

Expected: all tests PASS.

- [ ] **Step 6: Commit**

```bash
git add graphus-parser/src/main/java/io/graphus/parser/KotlinCallGraphBuilder.java \
        graphus-parser/src/test/java/io/graphus/parser/KotlinCallGraphBuilderTest.java
git commit -m "feat(parser): resolve Kotlin binary operator overloads as call edges"
```

---

### Task 2: Unary operator call edges (prefix and postfix)

**Files:**
- Modify: `graphus-parser/src/main/java/io/graphus/parser/KotlinCallGraphBuilder.java`
- Test: `graphus-parser/src/test/java/io/graphus/parser/KotlinCallGraphBuilderTest.java`

- [ ] **Step 1: Write the failing test**

Add to `KotlinCallGraphBuilderTest`:

```java
@Test
void resolvesUnaryOperatorOverloadAsCallEdge(@TempDir Path tempDir) throws IOException {
    Path source = writeFile(
            tempDir,
            "Counter.kt",
            """
            package demo

            class Counter(var value: Int) {
                operator fun inc(): Counter = Counter(value + 1)
                fun step(): Counter { var c = this; return ++c }
            }
            """);

    BuildOutput output = parseAndBuild(tempDir, source);

    assertTrue(
            output.graph().getEdges().contains(
                    new CallEdge("demo.Counter.step()", "demo.Counter.inc()")),
            "Prefix '++' must resolve to operator fun inc()");
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
./gradlew :graphus-parser:test --tests "io.graphus.parser.KotlinCallGraphBuilderTest.resolvesUnaryOperatorOverloadAsCallEdge" --no-daemon 2>&1 | tail -20
```

Expected: FAIL.

- [ ] **Step 3: Add unary operator map and walkers**

Add the constant and imports to `KotlinCallGraphBuilder.java`:

```java
import org.jetbrains.kotlin.psi.KtPrefixExpression;
import org.jetbrains.kotlin.psi.KtPostfixExpression;

private static final Map<String, String> UNARY_OPERATOR_TO_METHOD = Map.of(
        "PLUS", "unaryPlus",
        "MINUS", "unaryMinus",
        "EXCL", "not",
        "PLUSPLUS", "inc",
        "MINUSMINUS", "dec"
);
```

Add helpers:

```java
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
```

In `buildEdges`, after the binary operator loop, add:

```java
for (KtPrefixExpression prefix : findAllPrefixExpressions(body)) {
    String tokenName = prefix.getOperationToken().toString();
    String methodName = UNARY_OPERATOR_TO_METHOD.get(tokenName);
    if (methodName == null) continue;
    resolveUnaryOperator(callGraph, callerId, declaration, prefix, methodName,
            kotlinMethodsByName, records);
    if (!callGraph.getEdges().stream()
            .anyMatch(e -> e.fromId().equals(callerId)
                    && !e.toId().startsWith("UNRESOLVED:"))) {
        unresolvedCalls++;
    }
}
for (KtPostfixExpression postfix : findAllPostfixExpressions(body)) {
    String tokenName = postfix.getOperationToken().toString();
    String methodName = UNARY_OPERATOR_TO_METHOD.get(tokenName);
    if (methodName == null) continue;
    resolveUnaryOperator(callGraph, callerId, declaration, postfix, methodName,
            kotlinMethodsByName, records);
}
```

Add the helper (unary operators have arity 0):

```java
private static void resolveUnaryOperator(
        CallGraph callGraph,
        String callerId,
        KtDeclarationWithBody declaration,
        org.jetbrains.kotlin.psi.KtUnaryExpression unary,
        String methodName,
        Map<String, List<MethodNode>> kotlinMethodsByName,
        List<UnresolvedCallRecord> records) {
    List<MethodNode> candidates = kotlinMethodsByName.getOrDefault(methodName, List.of()).stream()
            .filter(node -> arityOf(node.getSignature()) == 0)
            .toList();
    if (candidates.size() == 1) {
        callGraph.addEdge(callerId, candidates.get(0).getId());
    } else if (!candidates.isEmpty()) {
        String unresolvedId = "UNRESOLVED:OP:" + methodName + "/0@" + callerId
                + "#" + System.identityHashCode(unary);
        callGraph.addNode(new UnresolvedNode(
                unresolvedId, unary.getText(), relativeFilePathOf(declaration), lineOf(unary)));
        callGraph.addEdge(callerId, unresolvedId);
        records.add(new UnresolvedCallRecord(callerId, methodName, 0, unresolvedId,
                UnresolvedCallRecord.Origin.KOTLIN));
    }
}
```

Note: `KtPrefixExpression` and `KtPostfixExpression` both extend `KtUnaryExpression`, so the helper parameter type is `KtUnaryExpression`.

The `unresolvedCalls` counter tracking in the loop above is approximate (the helper doesn't return a boolean). Simplify by having `resolveUnaryOperator` return `int` (1 if unresolved, 0 otherwise) and increment in the caller. Adjust accordingly.

Actually, the cleanest approach: move the `unresolvedCalls++` into the helper itself by passing the counter as an `int[]` wrapper or returning it. Since this is package-private, use a return value:

```java
private static int resolveUnaryOperator(...) {
    // returns 1 if left unresolved, 0 if resolved or no candidates
    ...
    if (candidates.size() == 1) {
        callGraph.addEdge(callerId, candidates.get(0).getId());
        return 0;
    } else if (!candidates.isEmpty()) {
        // add unresolved node + edge + record
        ...
        return 1;
    }
    return 0;
}
```

And in the loop:

```java
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
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
./gradlew :graphus-parser:test --tests "io.graphus.parser.KotlinCallGraphBuilderTest.resolvesUnaryOperatorOverloadAsCallEdge" --no-daemon 2>&1 | tail -20
```

Expected: PASS.

- [ ] **Step 5: Run all parser tests**

```bash
./gradlew :graphus-parser:test --no-daemon 2>&1 | tail -20
```

Expected: all tests PASS.

- [ ] **Step 6: Commit**

```bash
git add graphus-parser/src/main/java/io/graphus/parser/KotlinCallGraphBuilder.java \
        graphus-parser/src/test/java/io/graphus/parser/KotlinCallGraphBuilderTest.java
git commit -m "feat(parser): resolve Kotlin unary operator overloads (prefix and postfix) as call edges"
```

---

### Task 3: Array access operator call edges (`get` / `set`)

**Files:**
- Modify: `graphus-parser/src/main/java/io/graphus/parser/KotlinCallGraphBuilder.java`
- Test: `graphus-parser/src/test/java/io/graphus/parser/KotlinCallGraphBuilderTest.java`

- [ ] **Step 1: Write the failing test**

Add to `KotlinCallGraphBuilderTest`:

```java
@Test
void resolvesArrayAccessGetOperatorAsCallEdge(@TempDir Path tempDir) throws IOException {
    Path source = writeFile(
            tempDir,
            "Grid.kt",
            """
            package demo

            class Grid {
                operator fun get(row: Int, col: Int): Int = row * 10 + col
                fun diagonal(i: Int): Int = this[i, i]
            }
            """);

    BuildOutput output = parseAndBuild(tempDir, source);

    assertTrue(
            output.graph().getEdges().contains(
                    new CallEdge("demo.Grid.diagonal(Int)", "demo.Grid.get(Int, Int)")),
            "Array access this[i, i] must resolve to operator fun get(Int, Int)");
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
./gradlew :graphus-parser:test --tests "io.graphus.parser.KotlinCallGraphBuilderTest.resolvesArrayAccessGetOperatorAsCallEdge" --no-daemon 2>&1 | tail -20
```

Expected: FAIL.

- [ ] **Step 3: Add array access handling**

Add import and helper to `KotlinCallGraphBuilder.java`:

```java
import org.jetbrains.kotlin.psi.KtArrayAccessExpression;
```

```java
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
```

In `buildEdges`, after the unary operator loops, add:

```java
for (KtArrayAccessExpression arrayAccess : findAllArrayAccesses(body)) {
    int indexCount = arrayAccess.getIndexExpressions().size();
    // Determine if this is a read (get) or write (set, as LHS of assignment).
    boolean isWrite = isLhsOfAssignment(arrayAccess);
    String methodName = "get";
    int arity = indexCount;
    if (isWrite) {
        methodName = "set";
        arity = indexCount + 1; // extra argument: the value being assigned
    }
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
```

Add the helper to detect array access as write LHS:

```java
private static boolean isLhsOfAssignment(KtArrayAccessExpression arrayAccess) {
    org.jetbrains.kotlin.com.intellij.psi.PsiElement parent = arrayAccess.getParent();
    if (!(parent instanceof KtBinaryExpression binary)) {
        return false;
    }
    return "EQ".equals(binary.getOperationToken().toString())
            && binary.getLeft() == arrayAccess;
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
./gradlew :graphus-parser:test --tests "io.graphus.parser.KotlinCallGraphBuilderTest.resolvesArrayAccessGetOperatorAsCallEdge" --no-daemon 2>&1 | tail -20
```

Expected: PASS.

- [ ] **Step 5: Run all parser tests**

```bash
./gradlew :graphus-parser:test --no-daemon 2>&1 | tail -20
```

Expected: all tests PASS.

- [ ] **Step 6: Commit**

```bash
git add graphus-parser/src/main/java/io/graphus/parser/KotlinCallGraphBuilder.java \
        graphus-parser/src/test/java/io/graphus/parser/KotlinCallGraphBuilderTest.java
git commit -m "feat(parser): resolve Kotlin array access operator (get/set) as call edges"
```

---

### Task 4: Lambda parameter invocations as well-typed `UnresolvedNode`s

When a function body invokes `handler(input)` where `handler` is a function-type parameter of the enclosing function, the existing code records an `UNRESOLVED:handler/1@<callerId>` node — but it's indistinguishable from a plain failed name lookup. This task tags such nodes with `UNRESOLVED:LAMBDA:` so they don't pollute cross-language resolution attempts in `CrossLanguageCallResolver`.

**Files:**
- Modify: `graphus-parser/src/main/java/io/graphus/parser/KotlinCallGraphBuilder.java`
- Test: `graphus-parser/src/test/java/io/graphus/parser/KotlinCallGraphBuilderTest.java`

- [ ] **Step 1: Write the failing test**

Add to `KotlinCallGraphBuilderTest`:

```java
@Test
void lambdaParameterInvocationProducesTaggedUnresolvedNode(@TempDir Path tempDir) throws IOException {
    Path source = writeFile(
            tempDir,
            "Processor.kt",
            """
            package demo

            fun process(handler: (String) -> String, input: String): String = handler(input)
            """);

    BuildOutput output = parseAndBuild(tempDir, source);

    // The call handler(input) should produce a tagged UNRESOLVED:LAMBDA: node.
    boolean hasLambdaUnresolved = output.graph().getNodes().stream()
            .anyMatch(node -> node.getId().startsWith("UNRESOLVED:LAMBDA:handler"));
    assertTrue(hasLambdaUnresolved,
            "Lambda parameter invocation must produce an UNRESOLVED:LAMBDA: tagged node");
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
./gradlew :graphus-parser:test --tests "io.graphus.parser.KotlinCallGraphBuilderTest.lambdaParameterInvocationProducesTaggedUnresolvedNode" --no-daemon 2>&1 | tail -20
```

Expected: FAIL — the unresolved node ID starts with `UNRESOLVED:` not `UNRESOLVED:LAMBDA:`.

- [ ] **Step 3: Collect function-type parameter names for each declaration**

In `buildEdges`, when iterating over declarations, collect the set of parameter names that have function types. Kotlin PSI: `KtNamedFunction.getValueParameters()` → `KtParameter.getTypeReference()` → `KtFunctionType` is the PSI type for `(A) -> B`.

Add a helper:

```java
import org.jetbrains.kotlin.psi.KtNamedFunction;
import org.jetbrains.kotlin.psi.KtParameter;
import org.jetbrains.kotlin.psi.KtFunctionType;

private static Set<String> functionTypeParameterNames(KtDeclarationWithBody declaration) {
    if (!(declaration instanceof KtNamedFunction function)) {
        return Set.of();
    }
    Set<String> names = new java.util.LinkedHashSet<>();
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
```

- [ ] **Step 4: Tag lambda-invocation unresolved nodes**

In `buildEdges`, inside the existing `KtCallExpression` loop, when building the unresolved node ID, check if the callee name is a function-type parameter of the current declaration and use a `LAMBDA` tag:

```java
// Existing code (after candidates.size() != 1):
unresolvedCalls++;
Set<String> lambdaParams = functionTypeParameterNames(declaration);
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
```

The `functionTypeParameterNames` call is cheap (walks only value parameters of the current declaration).

- [ ] **Step 5: Run the test to verify it passes**

```bash
./gradlew :graphus-parser:test --tests "io.graphus.parser.KotlinCallGraphBuilderTest.lambdaParameterInvocationProducesTaggedUnresolvedNode" --no-daemon 2>&1 | tail -20
```

Expected: PASS.

- [ ] **Step 6: Run all tests**

```bash
./gradlew build --no-daemon 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add graphus-parser/src/main/java/io/graphus/parser/KotlinCallGraphBuilder.java \
        graphus-parser/src/test/java/io/graphus/parser/KotlinCallGraphBuilderTest.java
git commit -m "feat(parser): tag lambda parameter invocations as UNRESOLVED:LAMBDA: in KotlinCallGraphBuilder"
```
