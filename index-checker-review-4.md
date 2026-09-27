# Review 4: column-index checker (12dc11be..f2bb0d50)

Reviewed at `f2bb0d50` in `.claude/worktrees/review-index-checker`. JDK 25, `timeout 180 ./mvnw`. Every experiment was reverted, and `git status --short` is clean.

Probe harness: `scratchpad/probe/run.sh`. It runs javac 25 with `-proc:only` and the checker jar built from `tools/column-index-checker` at `f2bb0d50`. By default no `-AonlyDefs` is passed, so every class is checked. This round's probe sources are in `probe/r4/` and are cited as `<File>:<line>`:

- `P.java`: handovers into annotated targets.
- `L.java`: a container laundered into an unannotated local, then subscripted.
- `probe/Checked.java`: false positives and `isChecked`. Run with `-AonlyDefs='^probe\.(Checked|Impl)(\..*|\$.*)?$'`.
- `B.java`, `B2.java`, `B3.java`, `B4.java`, `T.java`: `BugInCF`.
- `V.java`: `var`, `reversed()`, `toArray()`.
- `FS.java`: `FileSchema` views. Run with `EXTRA_CP=core/target/classes`.
- `Inv.java`: an invalid `@IndexedBy` outside the set.

The round-3 probes (`r3/R3.java`) were re-run against the new jar. Every round-3 hole that f2bb0d50 claims to close now reports: `R3:16,17,19–25,29,32,34–40`. The lambda, stream, `ListIterator` and method-reference cases are still silent, and the design's Limits list them.

## Must fix

- [x] **A switch expression bypasses the new container rules.** `collectOrigins` handles `CONDITIONAL_EXPRESSION` branch by branch. `SWITCH_EXPRESSION` falls through to `default -> origins.add(value)`. `space(switchTree)` then finds no element and returns `NONE`, so:
  - `L:9`: `List<String> c = switch (x) { case 1 -> chunks; default -> chunks; }; c.get(o)` compiles clean.
  - `L:25`: `(switch (x) { default -> chunks; }).get(o)` compiles clean. The subscript's origin is `NONE`, so it is skipped.
  - `P:14` is the same as `L:9`.
  - For comparison, the conditional forms are caught: `L:27` (two errors) and `P:15`.
  - Real-code canary: at `RowGroupIterator.java:770`, replace `workItem.fileSchema().getColumn(fileOrdinal)` with `(switch (projCol) { default -> workItem.fileSchema().getColumns(); }).get(originalIndex)`. `-pl core -am -Pcolumn-index-check -Dquick compile` gives **0 errors**. This is #903 again, through the construct that the conditional fix was meant to cover.
  - Fix:
    - In `collectOrigins`, collect the value of each `case`: the expression body of a `->` case, and each `yield` expression. A `TreeScanner` over the switch that stops at nested lambdas, classes and switches does this.
    - In the factory, have `valueQualifier` take the LUB of the yielded values, as `branchesQualifier` does. Today `byOriginal[switch (x) { case 1 -> 0; default -> 1; }]` is rejected (`probe/Checked.java:35`), while `byOriginal[c ? 0 : 1]` passes (`:36`).
    - Add a `ColumnIndexCheckerTest` case for each.
  - **Resolved:** `collectOrigins` collects every result of a `switch` expression through CF's `FunctionalSwitchExpressionScanner`, which covers the body of a `->` case and each `yield`. The factory's `valueQualifier` takes the least upper bound of the same results. Tests: `switchOfContainers`, `switchOfLiterals`. The canary at `RowGroupIterator:770` reports `[770,115] [subscript.space]`.

## Should fix

- [x] **Widening a container to a non-container type drops its space silently.** `checkHandover` returns early when the *target* type is not an array, `List` or `FileSchema`. A container assigned to `Object`, `Collection` or `Iterable` and then cast back therefore carries no space:
  - `L:21`: `Object c = chunks; ((List<String>) c).get(o)`.
  - `L:28`: `Iterable<String> c = chunks; ((List<String>) c).get(o)`.
  - Real-code canary: at `RowGroupIterator.java:770`, use `((List<ColumnSchema>) (Collection<ColumnSchema>) workItem.fileSchema().getColumns()).get(originalIndex)`. Result: **0 errors**.
  - A single cast is resolved: `L:24` and `L:26` report. `TreeUtils.elementFromTree` sees through one `TYPE_CAST`.
  - Fix: in `checkHandover`, check the source when it is a container (`isContainer(typeOf(source))`), not only when the target is. That makes the target's type irrelevant: a source with a space may flow only into a target of the same space. Alternatively, count `Collection` and `Iterable` as containers too.
  - **Resolved:** `checkHandover` returns early only when neither the target type nor the source type is a container, so `Object c = chunks` and `Iterable<String> c = chunks` are errors (test `widenedContainerKeepsSpace`). The `Collection` canary still gave 0 errors after that: a cast over `getColumns()` fell to the `default` case of `collectOrigins`, and the view rule never ran. `collectOrigins` now looks through a `TYPE_CAST` (test `castKeepsSpace`, which failed before the change). The canary reports `[770,140] [subscript.space]` with and without `-Dquick`.

- [x] **Other paths also lose the space, and neither the design nor the notes list them.** The design's Limits (`INDEX_CHECKER.md:137`) name only positions reached without an `int` argument, `BitSet`/`Map`, and lambdas and method references. The notes' new open item (`index-checker-results.md`, last `[ ]`) says the same. Each case below compiles clean and then subscripts the result with the wrong space:
  - **An array element.** `visitAssignment` skips `ARRAY_ACCESS` targets, and `space(ARRAY_ACCESS)` is `NONE`.
    - `L:16`: `int[][] a = …; a[0] = ords; a[0][o]`.
    - `P:16`: the same with `List<String>[]`.
  - **JDK views and copies that `copiedFrom` does not know.**
    - `L:12`: `subList`.
    - `V:7`: `reversed()`.
    - `L:15`: `Collections.unmodifiableList`.
    - `V:8`: `toArray()`.
    - `P:23`: `stream().toList()`.
    - These fit an unannotated target and are rejected by an annotated one (`P:22–28`), so the space is lost only when the target is unmarked.
  - **Identity helpers.**
    - `L:14`: `Objects.requireNonNull(chunks)`.
    - `L:13`: a generic `static <T> T id(T t)` inside the checked set. Its parameter is a type variable, not a container, so `checkArguments` skips it.
  - **Holders.**
    - `L:17`: `List<List<String>>`.
    - `L:18`: `Optional.of(chunks)`.
    - `P:42`: `Map.put`.
    - `V:9`: `for (List<String> c : List.of(chunks))`.
  - None of these occurs in the checked set today. I searched for `subList`, `unmodifiable`, `requireNonNull`, `toArray`, `= switch`, and for `Collection<`/`Iterable<`/`Optional<` holding containers. The only hits are `ProjectedSchema:150/166` (`stream().toList()`/`toArray()` on `List<Integer>`, which is not indexed).
  - Fix:
    - Add `subList`, `reversed`, `Collections.unmodifiableList` and `Objects.requireNonNull` to `copiedFrom`. They are views or identity, and the cost is one `case` each.
    - Add one sentence to the Limits and the notes' open item, e.g.: "A container carries its space only through variables, fields, parameters and methods marked `@IndexedBy` and the copies and views listed above. Reached any other way (an array element, a JDK method not listed, a generic helper, a holder such as `Optional` or `List<List<…>>`), it carries none, and subscripts on it are not checked."
  - **Resolved:** An array element is now a handover target with no `@IndexedBy` (test `arrayElementHoldsNoSpace`). `copiedFrom` knows `toArray` on a `List`, `Collections.unmodifiableList` and `Objects.requireNonNull` (test `viewsAndIdentityKeepSpace`). `subList` and `reversed` are left out on purpose: their positions differ from those of the source, so the source's space would be wrong for them. The holders, generic helpers and unlisted JDK methods are in the design's Limits and the notes' open item.

- [x] **Local and anonymous classes inside a checked class are outside the set.** Their qualified name is empty (anonymous) or bare (local), so the core regex `…(\..*)?$` does not match. CF's `shouldSkipDefs(ClassTree)` then skips their bodies, and `isChecked` exempts their constructors and methods.
  - `probe/Checked.java:16`: `new Src() { public List<String> list() { chunks.get(o); … } }` gives no error with `-AonlyDefs`. Without it, it gives two.
  - `probe/Checked.java:20`: a local class `LocC.g()` calling `chunks.get(o)`, with the same result.
  - `probe/Checked.java:21`: `new Loc(chunks)` for a local `record Loc(List<String> l)` is accepted, because `Loc` is "outside the set". Without `-AonlyDefs` it is rejected.
  - The set holds only the local records `Entry` and `Extent` in `coalesceAcrossColumns`. Neither has a body or a container component, so nothing is lost today.
  - This does not affect lambdas. `probe/Checked.java:25` reports.
  - Fix: override `shouldSkipDefs(ClassTree)` in `ColumnIndexChecker` to decide an anonymous or local class by its outermost named enclosing class (`ElementUtils.enclosingTypeElement` up to a class with a non-empty qualified name). `isChecked` inherits the change. Otherwise, add a sentence to the Limits.
  - **Resolved:** `SourceChecker.shouldSkipDefs(ClassTree)` is `final` in CF 4.2.3, so it cannot be overridden. Instead the visitor reports `class.unchecked` on a local or anonymous class in a checked class when it has code (a method body, a field initializer or an initializer block); a local record without a body is allowed. `isChecked` walks from a local or anonymous class to the named class around it. Test: `localAndAnonymousClassesWithCode`. The design's Limits describe the rule.

- [x] **The `shouldSkipDefs` half of `isChecked` is untested.** `ColumnIndexCheckerTest.check` (`:430`) passes no `-AonlyDefs`, so every test class is "checked". Nothing therefore pins the exemption the core build relies on: an unmarked container parameter of a source class the profile skips accepts any container. `unmarkedParameterOfCheckedMethod` covers only the other side.
  - Fix: let `check` take extra options. Add a test with `-AonlyDefs=^Read$` and two source files: a helper class outside the pattern whose unmarked `List` parameter accepts `chunks`, and a helper inside it that rejects it.
  - **Resolved:** `check` takes checker options and extra sources. Tests: `uncheckedClassAcceptsAnySpace` (`-AonlyDefs=^Read$`) and `skippedClassAcceptsAnySpace` (`-AskipDefs=^Helper$`). Testing this also showed that `isChecked` depended on the callee's class tree: under `-Dquick`, where the `qa` profile and its `-XDcompilePolicy=simple` are off, the checker crashed with a `NullPointerException` in `shouldSkipDefs` on a class javac had not attributed yet, and it treated a class javac had already finished as unchecked. `isChecked` now uses `ElementUtils.isElementFromSourceCode` and matches `skipDefs`/`onlyDefs` against the class symbol's name. Test `calleeCompiledFirstIsChecked` (helper compiled first, `-XDcompilePolicy=byTodo`) failed before the change; the harness now compiles to a temporary directory, because `-proc:only` hides the problem.

## Nits

- [x] **`BugInCF` on an `int` whose type is a type variable.** A qualifier is missing from both helpers, so the whole compilation aborts:
  - `branchesQualifier`/`qualifier` (new): `B.java` method `b2` (`<T extends @ColumnIndexBottom Integer> … byOriginal[c ? t : u]`, isolated in `B3.java`) and `b11` (`c ? l.get(0) : 0` with `List<T>`, isolated in `B4.java`). The result is `error: no column-index qualifier on t` plus a stack trace from `CFAbstractTransfer.visitTernaryExpression`.
  - `checkSubscript` (from round 3): `T:5` `<X extends Integer> int t1(X x) { return byOriginal[x]; }` gives `no column-index qualifier on subscript x`.
  - Both need an explicit bound or bottom on a type variable, which the core code does not use.
  - Fix: use `getEffectiveAnnotationInHierarchy(top)`, which reads a type variable's upper bound, or fall back to `top` instead of throwing.
  - **Resolved:** `qualifier` and `checkSubscript` use `getAnnotationInHierarchy(top)`, which returns the effective annotation and reads a type variable's bound. (`getEffectiveAnnotationInHierarchy` does not exist in CF 4.2.3.) Test: `typeVariableIndex`.
- [x] **"A freshly allocated array or object fits any target" (`INDEX_CHECKER.md:102`) is true only of `new`.**
  - `probe/Checked.java:57`: `chunks = List.of()`, `chunks = Collections.emptyList()` and `chunks = new ArrayList<>(List.of("a"))` are all rejected (`indexedby.handover`, found "no @IndexedBy").
  - That follows from "a missing `@IndexedBy` counts as a space of its own", but the sentence reads as if factory methods fit too.
  - Fix: say "A `new` array or object".
  - **Resolved:** The design and the visitor's class doc say "an array or object created with `new`", and the design adds that a value returned by a factory method such as `List.of()` has no `@IndexedBy`.
- [x] **A generic helper inside the set cannot take containers of two spaces.** `probe/Checked.java:29`: `static <T> List<T> firstN(List<T> l, int n)` called with a `FileOrdinal` list and an `OriginalIndex` list gives two `indexedby.handover` errors. `@IndexedBy` has one value, so no annotation fixes both. The only escape is a suppression or moving the helper out of the set. Worth one sentence in the Limits.
  - **Resolved:** One sentence in the design's Limits.
- [x] **Bounds derived from `size()` are rejected.** `probe/Checked.java:53`: `chunks.subList(from, chunks.size())`. `:55`: `chunks.add(chunks.size(), "x")`. `size()` is top. Neither occurs in the set. If one does, it needs a conversion point, or `size()` of a container could read as the container's space.
  - **Resolved:** One sentence in the design's Limits. Checked: `chunks.get(chunks.size() - 1)` reports `[subscript.space]`, found `@ColumnIndexUnknown`.
- [x] **Value reads that fall to top where a literal was meant.** `probe/Checked.java:33`: `byOriginal[i = 0]`. `:40`: `-p` with `@OriginalIndex int p = 0`. Each is consistent with "anything else reads as top", and both are harmless (rejections, not holes). The switch-of-literals case is part of the Must item.
  - **Resolved:** No change: both are rejections, consistent with "anything else reads as top".
- [x] **The enhanced-for form of a declared counter is an error, not a conversion point.** `probe/Checked.java:47`: `for (@OriginalIndex int i : raw)` over a plain `int[]` gives the stock `[enhancedfor] incompatible types`. The design's "A qualifier on a loop counter … is a conversion point without a suppression" (`INDEX_CHECKER.md:123`) holds only for a basic `for`. Say "a basic `for` counter", or note that an enhanced `for` needs the array's element type qualified.
  - **Resolved:** The design says "the counter of a basic `for` loop" and adds that an enhanced `for` compiles only over an array whose elements carry the qualifier. Checked: `for (@FileOrdinal int f : positions)` over a plain `int[]` gives `[enhancedfor]`; over a `@FileOrdinal int[]` it passes.

## Verified correct

- [x] Baseline: `timeout 180 ./mvnw -pl core -am -Pcolumn-index-check -Dquick compile`: 0 errors. `-Pindex-check -Dquick compile`: rc 0, no errors.
- [x] Enforcer: `-Pindex-check,column-index-check` and `-Pcolumn-index-check,index-check` both fail at `validate` with `one-checker-profile` and the "activate one at a time" message.
- [x] `-pl tools/column-index-checker verify`: 27 tests, 0 failures. `-pl core verify -DskipITs`: 13,983 tests, 0 failures, 2 skipped, BUILD SUCCESS.
- [x] Suppressions: 9 `@SuppressWarnings("columnindex")`, matching the notes:
  - `ProjectedSchema:205` (partition), `:261` and `:274` (`createAllColumnsProjection`), `:415` and `:422`;
  - `RowGroupIterator:322` (`asReference`), `:1539` (`nextTouched`) and `:1661` (`fileOrdinalOf`);
  - `FileColumnOrdinals:56`.
  - Every one is covered by the design's conversion-point or "qualifier is lost" lists.
- [x] review-3 Resolved, `getColumns()` (`review-3.md:16`). All three canaries reproduce `[subscript.space]`:
  - `RowGroupIterator:770` `getColumns().get(originalIndex)`;
  - `ProjectedSchema:163` `originalColumns.get(i)`;
  - `ProjectedSchema:219` `schema.getColumns().get(i)`.
  - `FS:5–7` report on a probe, and `FS:9` (the right space) passes.
- [x] review-3 Resolved, value-keyed bottom (`:27`). `R3:16/17/20–25` report. `probe/Checked.java:31` rejects the unmarked constant and accepts `FIRST`. `:37–39,41,42,44,51` accept the declared-space forms `p + 1`, `q = p`, `(p)`, `(int) p`, `p += 1` and `FIRST + 1`. The rewrite only ever replaces bottom by a supertype (declared, LUB or top), so it cannot hide an error during dataflow. It can only add rejections.
- [x] review-3 Resolved, copies (`:42`). `clone`, `Arrays.copyOf`, `List.copyOf`, the copy constructor and conditionals report (`R3:29/40`, `P:22–28`, `L:22`). `toOriginalIndices()` carries `@IndexedBy(ProjectedIndex.class)` with no suppression.
- [x] review-3 Resolved, unmarked parameters (`:47`). Removing `@IndexedBy` from `RowGroupDictionaryFilterSource`'s constructor parameter gives `indexedby.handover` at `:56` and at the caller `RowGroupIterator:445`. Removing it from `ProjectedSchema.create`'s `schema` gives 8 errors (`:103/108/122/126/168/187/195`, `RowGroupIterator:350`). Changing `Entry.planIndex` to `@OriginalIndex` gives `[argument]` at `RowGroupIterator:894` and `[subscript.space]` at `:964`, so the local record's component annotation is live even though `Entry` is outside `-AonlyDefs`.
- [x] review-3 Resolved, varargs (`:73`): `R3:31` accepts literal elements, and `R3:32` rejects an array passed as the varargs array.
- [x] review-3 Resolved, `indexedby.invalid` (`:75`). `bad/Bad.java` reports once per declaration (`:4`, `:5`) and not again at the subscript. `Inv:4` (declaration outside `-AonlyDefs`) reports at each subscript.
- [x] review-3 Resolved, prefixed options (`:66`), and the corrected wording in `index-checker-review-2.md` and the notes: consistent with the round-3 probe.
- [x] Handovers that should be rejected are rejected:
  - an assignment expression as a value (`P:29–30`);
  - casts (`P:31`, `L:10–11`);
  - parentheses (`P:32`);
  - `var` (`P:33`, `V:5–6`);
  - a checked record constructor with an unmarked component (`P:18–19`);
  - the handover side of a lambda's result (`P:36`);
  - an array-element *index* (`P:39–41`: `=`, `+=`, `++`);
  - compound assignment of a projected index into an original counter (`P:38`).
- [x] Lambda bodies are checked (`probe/Checked.java:25`). A record constructor in the set with a compact body (`:27`) passes. An override in the set is checked through its own element (`:66`).
- [x] CLAUDE.md on the diff:
  - no `var`, no `/** */`, no `hasMessageContaining`;
  - no fully-qualified class names in code (the `"java.util.List"`-style strings are type-name lookups);
  - the design reads as end state.
