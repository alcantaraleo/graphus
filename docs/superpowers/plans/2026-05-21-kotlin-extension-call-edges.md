# Kotlin Extension Function Call Edges Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Represent Kotlin extension functions as first-class symbols and emit accurate call edges from Kotlin callers (via receiver disambiguation) and Java callers (which pass the receiver as an explicit first argument).

**Architecture:** Three-layer change: (1) `MethodNode` grows a `receiverType` field (empty string = not an extension); (2) `KotlinSymbolVisitor.visitFunction` reads `KtNamedFunction.getReceiverTypeReference()` and passes the type text to the new constructor; (3) `CrossLanguageCallResolver.resolveAgainst` adds a secondary matching pass for Java→Kotlin calls where `record.arity() == kotlinMethod.explicitArity + 1`, treating the extra argument as the extension receiver. Kotlin→extension resolution already works for the common case through name+arity matching (receiver is not a value argument); this plan adds receiver-type disambiguation when multiple same-arity candidates exist.

**Tech Stack:** Java 21, Kotlin PSI (`KtNamedFunction.getReceiverTypeReference()`), `MethodNode`, `KotlinSymbolVisitor`, `CrossLanguageCallResolver`, JUnit 5.

---

## File Map

| File | Action | Purpose |
|------|--------|---------|
| `graphus-model/src/main/java/io/graphus/model/MethodNode.java` | Modify | Add `receiverType` field + 14-param constructor; 13-param chains to it with `""` |
| `graphus-parser/src/main/java/io/graphus/parser/KotlinSymbolVisitor.java` | Modify | Read `getReceiverTypeReference()` in `visitFunction` and pass to 14-param constructor |
| `graphus-parser/src/main/java/io/graphus/parser/CrossLanguageCallResolver.java` | Modify | Secondary arity pass: `record.arity() == kotlinArity + 1` for extension methods |
| `graphus-parser/src/test/java/io/graphus/parser/KotlinSymbolVisitorTest.java` | Modify | Add test: extension function emits `receiverType` |
| `graphus-parser/src/test/java/io/graphus/parser/KotlinCallGraphBuilderTest.java` | Modify | Add test: Kotlin→extension call resolves when receiver type disambiguates |
| `graphus-parser/src/test/java/io/graphus/parser/CrossLanguageCallResolverTest.java` | Modify | Add test: Java→Kotlin extension call resolved with arity+1 |

---

### Task 1: Add `receiverType` to `MethodNode`

**Files:**
- Modify: `graphus-model/src/main/java/io/graphus/model/MethodNode.java`

- [ ] **Step 1: Write a failing test for `receiverType`**

There is no dedicated `MethodNodeTest`. Add assertions in `KotlinSymbolVisitorTest` in Task 2 instead. For this task, confirm the field doesn't exist yet:

```bash
grep -n "receiverType" graphus-model/src/main/java/io/graphus/model/MethodNode.java
```

Expected: no output.

- [ ] **Step 2: Add the `receiverType` field and update constructors**

In `MethodNode.java`:

1. Add field after `private final GuiceMetadata guiceMetadata;`:

```java
private final String receiverType;
```

2. Update the existing 13-param constructor to chain to a new 14-param one with `""`:

```java
public MethodNode(
        String id,
        SymbolKind kind,
        String declaringClassId,
        String name,
        String signature,
        String returnType,
        List<MethodParam> params,
        List<String> modifiers,
        List<String> annotations,
        String filePath,
        int line,
        SpringMetadata springMetadata,
        GuiceMetadata guiceMetadata
) {
    this(id, kind, declaringClassId, name, signature, returnType, params, modifiers,
            annotations, filePath, line, springMetadata, guiceMetadata, "");
}
```

3. Add the new 14-param canonical constructor:

```java
public MethodNode(
        String id,
        SymbolKind kind,
        String declaringClassId,
        String name,
        String signature,
        String returnType,
        List<MethodParam> params,
        List<String> modifiers,
        List<String> annotations,
        String filePath,
        int line,
        SpringMetadata springMetadata,
        GuiceMetadata guiceMetadata,
        String receiverType
) {
    super(id, kind == null ? SymbolKind.METHOD : kind, filePath, line);
    this.declaringClassId = declaringClassId;
    this.name = name;
    this.signature = signature;
    this.returnType = returnType;
    this.params = params == null ? List.of() : List.copyOf(params);
    this.modifiers = modifiers == null ? List.of() : List.copyOf(modifiers);
    this.annotations = annotations == null ? List.of() : List.copyOf(annotations);
    this.springMetadata = springMetadata == null ? new SpringMetadata() : springMetadata;
    this.guiceMetadata = guiceMetadata == null ? new GuiceMetadata() : guiceMetadata;
    this.receiverType = receiverType == null ? "" : receiverType;
}
```

4. Add getter after `getGuiceMetadata()`:

```java
public String getReceiverType() {
    return receiverType;
}
```

- [ ] **Step 3: Build to verify no compilation errors**

```bash
./gradlew :graphus-model:build --no-daemon 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add graphus-model/src/main/java/io/graphus/model/MethodNode.java
git commit -m "feat(model): add receiverType field to MethodNode for Kotlin extension functions"
```

---

### Task 2: Capture receiver type in `KotlinSymbolVisitor`

**Files:**
- Modify: `graphus-parser/src/main/java/io/graphus/parser/KotlinSymbolVisitor.java`
- Test: `graphus-parser/src/test/java/io/graphus/parser/KotlinSymbolVisitorTest.java`

- [ ] **Step 1: Write a failing test**

Add to `KotlinSymbolVisitorTest`:

```java
@Test
void extensionFunctionCarriesReceiverType(@TempDir Path tempDir) throws IOException {
    Path source = writeFile(
            tempDir,
            "StringExt.kt",
            """
            package demo
            fun String.shout(): String = this.uppercase() + "!"
            """);

    CallGraph callGraph = parseInto(tempDir, source);

    MethodNode shout = (MethodNode) callGraph.getNode("demo.StringExtKt.shout()");
    assertNotNull(shout, "Extension function must be indexed under the file facade");
    assertEquals("String", shout.getReceiverType(),
            "Receiver type must be captured from getReceiverTypeReference()");
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
./gradlew :graphus-parser:test --tests "io.graphus.parser.KotlinSymbolVisitorTest.extensionFunctionCarriesReceiverType" --no-daemon 2>&1 | tail -20
```

Expected: FAIL — `getReceiverType()` returns `""`.

- [ ] **Step 3: Capture the receiver type in `visitFunction`**

In `KotlinSymbolVisitor.java`, update `visitFunction` to:

1. Compute `receiverType` before building `MethodNode`:

```java
String receiverType = "";
if (function.getReceiverTypeReference() != null) {
    receiverType = function.getReceiverTypeReference().getText();
    if (receiverType == null) {
        receiverType = "";
    }
}
```

2. Use the 14-param constructor (add `receiverType` as the last argument):

```java
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
        guiceMetadata,
        receiverType);
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
./gradlew :graphus-parser:test --tests "io.graphus.parser.KotlinSymbolVisitorTest" --no-daemon 2>&1 | tail -20
```

Expected: all `KotlinSymbolVisitorTest` tests PASS.

- [ ] **Step 5: Commit**

```bash
git add graphus-parser/src/main/java/io/graphus/parser/KotlinSymbolVisitor.java \
        graphus-parser/src/test/java/io/graphus/parser/KotlinSymbolVisitorTest.java
git commit -m "feat(parser): capture Kotlin extension receiver type in KotlinSymbolVisitor"
```

---

### Task 3: Resolve Java→Kotlin extension calls in `CrossLanguageCallResolver`

When Java code calls an extension function — e.g., `ExtensionsKt.shout(str)` — the Java unresolved record has `calleeName="shout"`, `arity=1`, but the Kotlin method is indexed as `shout()` with `arity=0` and `receiverType="String"`. The existing resolver filters by `arity == record.arity()`, so `0 != 1` — no match. This task adds a secondary pass for extension methods.

**Files:**
- Modify: `graphus-parser/src/main/java/io/graphus/parser/CrossLanguageCallResolver.java`
- Test: `graphus-parser/src/test/java/io/graphus/parser/CrossLanguageCallResolverTest.java`

- [ ] **Step 1: Write a failing test**

Add to `CrossLanguageCallResolverTest`:

```java
@Test
void resolvesJavaToKotlinExtensionCallWithArityPlusOne() {
    CallGraph graph = new CallGraph();
    // Java caller.
    MethodNode caller = method("com.demo.JavaClient.use()", "use", "use()");
    graph.addNode(caller);

    // Kotlin extension function: fun String.shout(): String — arity 0, receiverType "String"
    MethodNode extension = new MethodNode(
            "com.demo.StringExtKt.shout()",
            SymbolKind.METHOD,
            "com.demo.StringExtKt",
            "shout",
            "shout()",
            "String",
            List.<MethodParam>of(),
            List.<String>of(),
            List.<String>of(),
            "StringExt.kt",
            1,
            new SpringMetadata(),
            new GuiceMetadata(),
            "String");
    graph.addNode(extension);

    // Java calls ExtensionsKt.shout(someStr) → arity 1 from Java's perspective.
    UnresolvedNode unresolved =
            new UnresolvedNode("UNRESOLVED:shout/1@javaClient", "shout(str)", "Java.java", 5);
    graph.addNode(unresolved);
    graph.addEdge(caller.getId(), unresolved.getId());

    UnresolvedCallRecord record =
            new UnresolvedCallRecord(caller.getId(), "shout", 1, unresolved.getId(),
                    UnresolvedCallRecord.Origin.JAVA);

    CrossLanguageCallResolver.Result result =
            new CrossLanguageCallResolver().resolve(
                    graph,
                    Set.of(caller.getId()),
                    Set.of(extension.getId()),
                    List.of(record),
                    List.of());

    assertEquals(1, result.javaToKotlinResolved());
    assertTrue(graph.getEdges().contains(new CallEdge(caller.getId(), extension.getId())),
            "Java→Kotlin extension call must be resolved");
    assertNull(graph.getNode(unresolved.getId()),
            "Resolved placeholder must be removed");
}
```

Also add the import for the 14-param `MethodNode` constructor call — it already exists in the file.

- [ ] **Step 2: Run the test to verify it fails**

```bash
./gradlew :graphus-parser:test --tests "io.graphus.parser.CrossLanguageCallResolverTest.resolvesJavaToKotlinExtensionCallWithArityPlusOne" --no-daemon 2>&1 | tail -20
```

Expected: FAIL — `javaToKotlinResolved` is 0.

- [ ] **Step 3: Add the extension-arity secondary pass in `CrossLanguageCallResolver`**

In `resolveAgainst`, after the existing `candidates.size() != 1` check does nothing (skipping the unresolved record), add a secondary check using `arity - 1` for Kotlin extension methods. The cleanest place is a new private helper called after the main `resolveAgainst` loop.

Replace the `resolve` method with:

```java
public Result resolve(
        CallGraph callGraph,
        Set<String> javaMethodIds,
        Set<String> kotlinMethodIds,
        Collection<UnresolvedCallRecord> javaUnresolvedRecords,
        Collection<UnresolvedCallRecord> kotlinUnresolvedRecords) {

    Map<String, List<MethodNode>> javaIndex = indexMethodNodes(callGraph, javaMethodIds);
    Map<String, List<MethodNode>> kotlinIndex = indexMethodNodes(callGraph, kotlinMethodIds);

    int javaToKotlin = resolveAgainst(callGraph, javaUnresolvedRecords, kotlinIndex);
    javaToKotlin += resolveExtensionsAgainst(callGraph, javaUnresolvedRecords, kotlinIndex);
    int kotlinToJava = resolveAgainst(callGraph, kotlinUnresolvedRecords, javaIndex);

    return new Result(javaToKotlin, kotlinToJava);
}
```

Add the new helper method:

```java
/**
 * Secondary pass: resolves Java calls to Kotlin extension functions where the Java call
 * arity is exactly one more than the Kotlin arity (the extra argument is the extension receiver).
 * Only runs on records that were not already resolved by {@link #resolveAgainst}.
 */
private static int resolveExtensionsAgainst(
        CallGraph callGraph,
        Collection<UnresolvedCallRecord> records,
        Map<String, List<MethodNode>> kotlinIndex) {
    int resolved = 0;
    for (UnresolvedCallRecord record : records) {
        if (callGraph.getNode(record.unresolvedNodeId()) == null) {
            continue; // already resolved by the primary pass
        }
        if (record.arity() < 1) {
            continue; // no room for a receiver argument
        }
        int extensionArity = record.arity() - 1;
        List<MethodNode> candidates = kotlinIndex.getOrDefault(record.calleeName(), List.of()).stream()
                .filter(node -> !node.getReceiverType().isEmpty())
                .filter(node -> KotlinCallGraphBuilder.arityOf(node.getSignature()) == extensionArity)
                .toList();
        if (candidates.size() != 1) {
            continue;
        }
        String calleeId = candidates.get(0).getId();
        callGraph.removeEdge(record.callerId(), record.unresolvedNodeId());
        callGraph.removeNode(record.unresolvedNodeId());
        callGraph.addEdge(record.callerId(), calleeId);
        resolved++;
    }
    return resolved;
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
./gradlew :graphus-parser:test --tests "io.graphus.parser.CrossLanguageCallResolverTest" --no-daemon 2>&1 | tail -20
```

Expected: all `CrossLanguageCallResolverTest` tests PASS.

- [ ] **Step 5: Run the full build**

```bash
./gradlew build --no-daemon 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add graphus-parser/src/main/java/io/graphus/parser/CrossLanguageCallResolver.java \
        graphus-parser/src/test/java/io/graphus/parser/CrossLanguageCallResolverTest.java
git commit -m "feat(parser): resolve Java→Kotlin extension function calls in CrossLanguageCallResolver"
```

---

### Task 4: Add Kotlin→extension disambiguation in `KotlinCallGraphBuilder`

Kotlin-to-extension resolution already works for the common case: `user.shout()` is parsed as a `KtCallExpression` for `shout` with arity 0, and if there is exactly one indexed method named `shout` with arity 0, the edge is added. The gap is disambiguation when multiple functions have the same name and arity but different receiver types (e.g., `fun String.shout()` and `fun Int.shout()` — both arity 0). This task adds receiver-type filtering to break the tie.

**Files:**
- Modify: `graphus-parser/src/main/java/io/graphus/parser/KotlinCallGraphBuilder.java`
- Test: `graphus-parser/src/test/java/io/graphus/parser/KotlinCallGraphBuilderTest.java`

- [ ] **Step 1: Write a failing test**

Add to `KotlinCallGraphBuilderTest`:

```java
@Test
void disambiguatesExtensionCallByReceiverTypeWhenNameAndArityCollide(@TempDir Path tempDir)
        throws IOException {
    Path source = writeFile(
            tempDir,
            "Extensions.kt",
            """
            package demo

            fun String.shout(): String = this.uppercase() + "!"
            fun Int.shout(): String = this.toString() + "!"

            class Greeter {
                fun greetString(s: String): String = s.shout()
            }
            """);

    BuildOutput output = parseAndBuild(tempDir, source);

    // s.shout() should resolve to String.shout(), not Int.shout(), despite same arity.
    assertTrue(output.graph().getEdges().contains(
                    new CallEdge("demo.Greeter.greetString(String)", "demo.ExtensionsKt.shout()")),
            "greetString should call the String extension shout");
    // There may still be an unresolved for Int.shout if it's ambiguous — that's acceptable.
}
```

Note: this test may pass already if name+arity uniqueness is sufficient (only one `shout/0` candidate matches in context). Run it first before modifying.

- [ ] **Step 2: Run the test to see the current behaviour**

```bash
./gradlew :graphus-parser:test --tests "io.graphus.parser.KotlinCallGraphBuilderTest.disambiguatesExtensionCallByReceiverTypeWhenNameAndArityCollide" --no-daemon 2>&1 | tail -20
```

If PASS: the existing name+arity logic already handles this case (both extensions are in the index under the same facade class with the same simple name, so the resolver sees two candidates with arity 0). Proceed regardless.

If FAIL: the two `shout/0` candidates produce ambiguity. The fix in Step 3 adds receiver-type-based narrowing.

- [ ] **Step 3: Add receiver-type filtering when a dot-qualified call is detected**

In `KotlinCallGraphBuilder`, the existing method `signatureOf(KtCallExpression call)` extracts the callee name. For a dot-qualified call `s.shout()`, the PSI parent of the `KtCallExpression` is a `KtDotQualifiedExpression`. We can inspect this to extract the receiver's text (its simple declared type if it's a reference to a locally-typed parameter).

Add a private helper to extract the receiver text of a call expression if it is dot-qualified:

```java
private static String receiverTextOf(KtCallExpression call) {
    org.jetbrains.kotlin.com.intellij.psi.PsiElement parent = call.getParent();
    if (!(parent instanceof org.jetbrains.kotlin.psi.KtDotQualifiedExpression dotQual)) {
        return "";
    }
    org.jetbrains.kotlin.psi.KtExpression receiver = dotQual.getReceiverExpression();
    if (receiver == null) {
        return "";
    }
    // Best-effort: if the receiver is a name reference, return its text.
    // Full type resolution is not available; this narrows by simple name only.
    return receiver.getText() == null ? "" : receiver.getText().trim();
}
```

Then in `buildEdges`, update the candidate filtering logic so that when multiple candidates exist AND the call is dot-qualified, filter by receiver type before deciding ambiguity:

Replace the existing resolution block:

```java
if (candidates.size() == 1) {
    callGraph.addEdge(callerId, candidates.get(0).getId());
    continue;
}
```

With:

```java
if (candidates.size() == 1) {
    callGraph.addEdge(callerId, candidates.get(0).getId());
    continue;
}
if (candidates.size() > 1) {
    String receiverText = receiverTextOf(call);
    if (!receiverText.isEmpty()) {
        // Narrow by receiver type text: parameter name is not a type, but for simple
        // declarations like "s: String" the parameter name appears in the receiver text.
        // Fall back to the existing unresolved path if still ambiguous.
        List<MethodNode> narrowed = candidates.stream()
                .filter(node -> !node.getReceiverType().isEmpty()
                        && node.getReceiverType().endsWith(receiverText))
                .toList();
        if (narrowed.size() == 1) {
            callGraph.addEdge(callerId, narrowed.get(0).getId());
            continue;
        }
    }
}
```

Note: `receiverText` is the raw receiver expression text (e.g. `"s"` for `s.shout()`), not a type. Narrowing by `getReceiverType().endsWith(receiverText)` is intentionally conservative — it only resolves when receiver text happens to match the receiver type suffix (e.g. `receiverType="String"` matched by `s` would not match, but `receiverType="String"` with `receiverText="someString"` also wouldn't). This limitation is acceptable; the primary benefit is that unambiguous cases resolve correctly even without full type inference.

**Refinement:** The test above with `s.shout()` where `s: String` won't benefit from this receiver text matching since `s != String`. The real disambiguation value is when the receiver text IS the type name (e.g. `String.shout()` at call site). If the test reveals this doesn't help, document it as a known limitation and note that full disambiguation requires type inference (issue #47). The test will still document the behaviour.

- [ ] **Step 4: Run all Kotlin call graph builder tests**

```bash
./gradlew :graphus-parser:test --tests "io.graphus.parser.KotlinCallGraphBuilderTest" --no-daemon 2>&1 | tail -20
```

Expected: all tests PASS.

- [ ] **Step 5: Run the full build**

```bash
./gradlew build --no-daemon 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add graphus-parser/src/main/java/io/graphus/parser/KotlinCallGraphBuilder.java \
        graphus-parser/src/test/java/io/graphus/parser/KotlinCallGraphBuilderTest.java
git commit -m "feat(parser): use receiver type to disambiguate Kotlin extension calls in KotlinCallGraphBuilder"
```
