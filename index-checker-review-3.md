# Review 3: column-index checker (2a8852df..12dc11be)

Reviewed at `12dc11be` in `.claude/worktrees/review-index-checker`. JDK 25, `timeout 180 ./mvnw`. Every experiment was reverted, and `git status --short` is clean.

Probe harness: `scratchpad/probe/run.sh`. It runs javac 25 with `-proc:only` and the checker jar freshly built from `tools/column-index-checker` at `12dc11be`. This round's probe sources are `probe/r3/R3.java` and `probe/r3/R4.java`, cited below as `R3:<line>` / `R4:<line>`.

## Must fix

- [x] **The #903 line still compiles when spelled with `getColumns()`.** `checkSubscript` sees the container `workItem.fileSchema().getColumns()`. Its element is `FileSchema.getColumns()`, which has no `@IndexedBy`, so the subscript is not checked, even though the receiver `workItem.fileSchema()` is `@IndexedBy(FileOrdinal.class)`.
  - Canary: in `computeFetchPlans` (`RowGroupIterator.java:~770`), change `workItem.fileSchema().getColumn(fileOrdinal)` to `workItem.fileSchema().getColumns().get(originalIndex)`. Result with `-pl core -am -Pcolumn-index-check -Dquick compile`: **0 errors**. This is the #903 mistake, spelled differently.
  - The checked set already uses this form: `ProjectedSchema.java:106` (`originalColumns = schema.getColumns()`, later `originalColumns.get(origIdx)`) and `:217` (`schema.getColumns().get(projectedToOriginal[i])`). Both are unchecked today.
  - Fix, either:
    - treat `FileSchema.getColumns()` as a view carrying the receiver's space. In `checkSubscript` and `checkHandover`, a container that is `x.getColumns()` on a `FileSchema` takes `space(x)`.
    - or mark `getColumns()` `@IndexedBy` per call site the same way. A single stub cannot say which space, for the reason the design gives for `getColumn`.
  - Pin the fix with a `ColumnIndexCheckerTest` case.
  - **Resolved:** `checkSubscript` and `checkHandover` resolve a container through its copies and views; `schema.getColumns()` takes the space of `schema`. `ProjectedSchema`'s schema parameters, field, `originalColumns` local and `getOriginalSchema()` now carry `@IndexedBy(OriginalIndex.class)`. Canaries: your `getColumns().get(originalIndex)` line, and a projected counter at `ProjectedSchema` `:163` and `:219`, each give `[subscript.space]`. Test `columnsOfSchemaKeepSpace`.

## Should fix

- [x] **The bottom-to-declared rewrite covers only `IDENTIFIER` trees of `LOCAL_VARIABLE`s, so three literal-derived shapes still fit every space.** The design (`INDEX_CHECKER.md`, "Qualifiers") says an unannotated counter "belongs to no space and is rejected wherever one is required". The following compile clean against `@IndexedBy(FileOrdinal.class) List<String> chunks`:
  - `R3:16`: a parameter reset to a literal, `p = 0; p++; chunks.get(p)`. `ElementKind.PARAMETER` is not rewritten.
  - `R3:17`: a field counter, `for (cursor = 0; cursor < n; cursor++) chunks.get(cursor)`. `ElementKind.FIELD` is not rewritten, and flow refines the field to bottom inside the method.
  - `R4:6`: a postfix increment used as the index, `int i = 0; chunks.get(i++)`. The `UNARY` tree's type comes from flow (bottom), not from the rewritten identifier. The prefix and `+i` forms are rejected (`R4:7`).
  - None of these occurs in the checked set today. I searched for `[x++]` / `(x++)` subscripts and found only `ProjectedSchema:230/235` `partitioned[next++]`, where `partitioned` has no `@IndexedBy`.
  - Fix: key the rule on the value rather than the tree. A bottom type is legal only for a literal or a compile-time constant expression. Anything else that computes to bottom reads as its variable's declared type, or as top. Alternatively, cover `PARAMETER`/`FIELD` and the increment/decrement kinds explicitly.
  - Either way, add the three shapes to `ColumnIndexCheckerTest`.
  - **Resolved:** Keyed on the value. Bottom is kept only for an expression of literals and operators. A variable (local, parameter, field, named constant) reads as its declared type, `++`/`--` as its operand's, a conditional as the least upper bound of its branches, anything else as top. Tests `parameterResetToLiteral`, `fieldCounter`, `postfixIncrementAsIndex`, `namedConstantHasDeclaredSpace`.

- [x] **A copy or a derived container silently leaves its space.** The handover rule compares `@IndexedBy` by *source element*. A source with no element, or whose element is a JDK method, counts as "no `@IndexedBy`". The rule therefore passes when the target has none, and the subscripts on the result are then unchecked.
  - These compile clean, and each then indexes the copy with the wrong space:
    - `R3:29` `int[] c = byProjected.clone(); c[o]`
    - `R3:40` `Arrays.copyOf(byProjected, 3)`
    - `R3:39` `List<String> c = f ? chunks : other`
    - `R3:27` a lambda `() -> chunks`, via `Supplier.get()`
    - `R3:28` a method reference `this::chunks`
  - "A freshly allocated array or object fits any target" also lets a copy constructor relabel the space: `R3:38` `@IndexedBy(OriginalIndex.class) List<String> l = new ArrayList<>(chunks);` and `R3:33` `new Rec(new ArrayList<>(chunks))` both give **no error**.
  - Fix:
    - exempt only a `NEW_CLASS` with no container argument, and give a copy constructor its argument's space;
    - report a handover from a container-typed expression without an element (conditional, lambda result) into any target, as for `var`;
    - or list these in "Limits".
  - Note that `clone()` into an annotated target is already an error (`R3:30`). That is safe but forces a suppression.
  - **Resolved:** `clone()`, `Arrays.copyOf`, `Arrays.copyOfRange`, `List.copyOf` and a constructor given a container carry their source's space. A conditional is checked branch by branch. Lambdas and method references are listed in the design's Limits. `toOriginalIndices()` now carries `@IndexedBy(ProjectedIndex.class)` with no suppression. Tests `copiesKeepSpace`, `copyConstructorKeepsSpace`, `conditionalOfContainers`. This surfaced `new ArrayList<>(schema.getColumns())` in `createAllColumnsProjection`, now a suppressed conversion point (9 in total).

- [x] **A parameter without `@IndexedBy` accepts any container, including in methods of the checked set.** `R3:37` `help(chunks, o)`, where `static String help(List<String> l, int i) { return l.get(i); }`, gives no error, and the subscript inside `help` is unchecked.
  - The design states this rule ("A parameter without `@IndexedBy` accepts any array, list or schema"), so it is documented, not hidden. But it is the handover hole of round 2 moved one call away.
  - Fix: apply the exemption only to methods whose declaration is outside the checked set (not from source, or not matched by `-AonlyDefs`). In the set, a missing `@IndexedBy` on a container parameter then counts as its own space, as it already does for locals.
  - **Resolved:** Only a method declared outside the checked set (no source tree, or a class `shouldSkipDefs` skips) accepts any container. This surfaced three unmarked parameters in the set, now annotated: `ProjectedSchema.create(…)`, `RowGroupDictionaryFilterSource`'s constructor, and `coalesceAcrossColumns(plans, …)` (with its counters, `Entry.planIndex` and `regionsByPlan`). Test `unmarkedParameterOfCheckedMethod`.

- [x] **"Every conversion point is visible as a suppression" is not true: a counter's declared space is trusted, not checked.** `R3:18` `for (@FileOrdinal int p = 0; p < projectedCount; p++) chunks.get(p)` compiles, although `p` is bounded by the projected count.
  - A declared counter is an unsuppressed conversion point from a literal into a space. The real code has several:
    - `ProjectedSchema.java:141` `for (@OriginalIndex int i = 0; i < originalCount; i++) … includedOriginalIndices.add(i)`;
    - `RowGroupIterator` `:766` and `:1143`;
    - `RowGroupIndexBuffers:103`;
    - `requireSameFile`.
  - Mitigation, verified in the real loops: where the body also uses the counter in its true space (`plans[projCol]`, `toOriginalIndex(p)`), a wrong declaration fails there.
  - Fix: say in "Conversion points" / "Limits" that a counter's declared space is asserted by its declaration and bounded by its loop condition, which the checker does not relate. Reword the "visible as a suppression" sentence accordingly.
  - **Resolved:** Design "Conversion points" now says a counter or constant qualifier is an unsuppressed conversion point, trusted and not related to the loop bound, and names the mitigation. The "visible as a suppression" sentence excludes it. Notes have a matching item.

- [x] **The notes' claim that "running both in one compilation with checker-prefixed options does not work" is overstated.** Each subchecker honours an option prefixed with *its own* name.
  - Probe `probe/ic/Arr.java` (`a[i]`, `-processor IndexChecker`):
    - no option → `array.access.unsafe.low` and `.high`;
    - `-AIndexChecker_onlyDefs=^Nothing$` → only `.low` remains (the `LowerBoundChecker` subchecker ignores the prefix; the coordinator's observation is right that far);
    - `-AIndexChecker_onlyDefs=^Nothing$ -ALowerBoundChecker_onlyDefs=^Nothing$` → **no errors**.
  - So a combined run needs one prefixed `onlyDefs` per subchecker (`LowerBoundChecker`, and whichever of `SameLen`/`SearchIndex`/`Value`/`SubstringIndex` report). The enforcer (option b) remains a fine choice.
  - Fix: correct `index-checker-results.md` (the "exclusive" item) and the Resolved line in `index-checker-review-2.md`.
  - **Resolved:** Reproduced on a one-line probe (`LowerBoundChecker` needs its own prefix). `index-checker-results.md` and the Resolved line in `index-checker-review-2.md` corrected. The enforcer stays.

## Nits

- [x] **False positive:** a conditional of two same-space locals, `@FileOrdinal int a = 0; @FileOrdinal int b = 1; chunks.get(c ? a : b)` (`R4:9`), fails with two `[conditional] incompatible types in conditional expression` errors. The conditional's type comes from flow (bottom), while its branches are rewritten to `@FileOrdinal`. Unannotated locals (`R3:20`) get `[conditional]` rather than `subscript.space`, which is correct in outcome but confusing. Not present in the checked set. Another reason to key the rewrite on the value (Should item above).
  - **Resolved:** Fixed by the value-keyed rule. Test `conditionalOfSameSpace`.
- [x] **False positive:** varargs. `vararg(1, 2)` against `@IndexedBy(OriginalIndex.class) int... a` (`R3:31`) reports `indexedby.handover`, because each literal element is compared as if it were the array. `checkArguments` should skip a varargs call whose arguments are the elements.
  - **Resolved:** `checkArguments` skips the elements of a varargs call (`TreeUtils.isVarargsCall`). Test `varargsElementsAreIndices`.
- [x] `indexedby.invalid` is reported only when a subscript uses the container (`bad/Bad.java:6`). A field declared `@IndexedBy(NonNegative.class)` and only handed over, never subscripted, is accepted. Validate in `visitVariable`/`visitMethod` on the declaration instead.
  - **Resolved:** Reported at the declaration (`visitVariable`, `processMethodTree`, since `visitMethod` is final). A subscript reports it only for a declaration outside the checked set. Test `indexedByNamesOtherAnnotation` now has a field that is only assigned.
- [x] Still unchecked, with no mention in "Limits":
  - positions reached without an `int` argument to a listed method: `chunks.stream().skip(o)` (`R3:41`), `ListIterator.nextIndex()` (`R3:42`), the method reference `chunks::get` (`R3:43`);
  - containers other than arrays, `List` and `FileSchema`, such as `BitSet touchedColumns` and the `Map<Integer, …> dropLeavesByColumn` keyed by original index.
  - A sentence in "Limits" covers all of it.
  - **Resolved:** Added to the design's Limits and the notes.
- [x] A named constant fits every space: `static final int FIRST = 3; chunks.get(FIRST)` (`R3:22`) is accepted, like a literal. Say "a literal or a compile-time constant" in "Limits".
  - **Resolved:** A named constant now reads as its declared type, so an unmarked one is rejected (`namedConstantHasDeclaredSpace`). Design says a qualifier on a constant is a trusted conversion point.
- [x] `FileColumnOrdinals.identity` and `asReference` trust that the caller holds file 0. Canary: `fileIndex == 0` → `fileIndex >= 0` at `RowGroupIterator.java:1269` makes every file use the identity mapping, which is #903 again. It compiles clean. `CrossFileColumnOrderTest` catches it at run time: 10 run, 8 failures, 1 error. Name this in the conversion-point bullet ("sound only for the file at index 0").
  - **Resolved:** Named in the design's conversion-point section and the notes. Reproduced: with `fileIndex >= 0` at `:1270`, 0 checker errors; `CrossFileColumnOrderTest` 10 run, 8 failures, 1 error.
- [x] `ProjectedSchema.toOriginalIndices()` returns `@OriginalIndex int[]` with no `@IndexedBy(ProjectedIndex.class)`. Adding it would make `projectedToOriginal.clone()` a handover error (`R3:30` pattern), so it needs either the copy rule above or a suppression.
  - **Resolved:** Annotated; `clone()` now carries the space, so no suppression is needed.

## Verified correct

- **MUST 1 from round 2 (mapping) holds for mistakes inside the typed chain.** I re-ran the coordinator's three canaries:
  - `validateColumn` returning `refColumn.columnIndex()` → `[1653,37] [return]`;
  - returning `originalIndex` → `[1653,16] [return]`;
  - `fileOrdinalOf(inputFile, referenceSchema, …)` → `[1587,65] [indexedby.handover]`.
  - `fileOrdinal` no longer needs a suppression: the local takes `@FileOrdinal` from the array element and is returned only after the `< 0` check.
- The chain `validateSchemaCompatibility` → `FileColumnOrdinals.of` → constructor → field is `@IndexedBy(OriginalIndex.class) @FileOrdinal int[]` on every hop.
- **Round-2 probes now fail as intended** (`probe/Read.java`):
  - alias `:15`, argument `:22` and field `:24` → `indexedby.handover`;
  - cast `:17` → `cast.unsafe` is now an **error**;
  - `List.set` `:32` and `subList` `:34` → `subscript.space`.
  - `probe/bad/Bad.java` → `indexedby.invalid` for `NonNegative` and `ColumnIndexUnknown`, with no crash.
- Handover checks that work (`R3`):
  - `var c = chunks` (`:36`) → error;
  - record canonical constructor `new Rec(origList)` (`:35`) → error;
  - record accessor `r.l().get(o)` (`:34`) → `subscript.space`, so a record component's `@IndexedBy` reaches both the constructor and the accessor;
  - lambda parameter as index (`:19`) → rejected;
  - compound `i += 1` (`R3:21`), switch-expression local (`:23`), `final` local constant (`:24`), `(i)`, `(int) i`, `i + 0`, `Math.max(i, 0)` and `j = i + 1` (`R4`) → all rejected as unknown.
- The loop-counter fix in the real code: `get(fileOrdinal)` → `get(p)` in `masksApplicableForRowGroup` is now rejected: `[1145,62] [subscript.space] … for rowGroup.columns()`.
- **Enforcer `one-checker-profile`** (`-pl core validate`):
  - no profile, `-Pindex-check` alone and `-Pcolumn-index-check` alone → pass;
  - `-Pindex-check,column-index-check`, `-Pcolumn-index-check,index-check` and `-Pindex-check -Pcolumn-index-check` → fail with the rule's message.
  - `requireProperty` matches the whole value, so `[^,]+,` means exactly one marker. The rule is bound to `validate`, so it fires before the clean step.
- Suppressions: 8, matching the notes. Each has a comment stating the fact asserted. The `ProjectedSchema:201-205` suppression also covers the `partition` handover; the comment says so.
- Baseline:
  - `-pl core -am -Pcolumn-index-check -Dquick compile` → 0 errors, BUILD SUCCESS;
  - `tools/column-index-checker verify` → 16/16 tests;
  - `-pl core verify -DskipITs` → 13,983 tests, 0 failures, 2 skipped.
- Other claims checked:
  - `ParquetFileReader` fully qualified names replaced with imports.
  - `originalIndex(...)` overloads are private.
  - The design's `-am` note and the stub-limit wording (nested records vs nested classes) match round-2 probes.
  - The filter-path limit is now stated.
