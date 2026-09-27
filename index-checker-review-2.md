# Review 2: column-index checker (2f8968c8..2a8852df)

Reviewed at `2a8852df` in `.claude/worktrees/review-index-checker`. JDK 25, `./mvnw` with `timeout 180`. Every experiment was reverted, and `git status --short` is clean.

The probe sources named below (`Read.java`, `Box.java`, `bad/Bad.java`, `stub/`) were scratch files outside the tree, compiled with javac 25, `-proc:only` and `ColumnIndexChecker`.

## Must fix

- [x] **The #903 mapping itself is unchecked. Putting #903's mistake back where the mapping is built compiles cleanly.** Every `FileColumnOrdinals` for a non-reference file is built from `validateSchemaCompatibility` (`RowGroupIterator.java:1551-1561`).
  - Its local `int[] fileOrdinals` has no `@IndexedBy`.
  - `validateColumn` (`:1567`) returns a plain `int`.
  - `FileColumnOrdinals.of(int[] fileOrdinals, …)` (`FileColumnOrdinals.java:65`) takes an unannotated array.
  - Canary: changing `RowGroupIterator.java:1643` from `return fileColumn.columnIndex();` to `return refColumn.columnIndex();` and running `-Pcolumn-index-check -Dquick compile` gives **0 errors**. That change maps every reference ordinal to itself, which is exactly the #903 behaviour.
  - Fix:
    - `validateColumn` returns `@FileOrdinal int`, through a conversion point `fileOrdinal(ColumnSchema)` for a leaf of *this file's* schema, the counterpart of `ProjectedSchema.originalIndex`.
    - The local and `FileColumnOrdinals.of`'s parameter get `@IndexedBy(OriginalIndex.class)`.
    - The `fileOrdinals` array's elements become `@FileOrdinal` (still needs the `-1` absent case, see the nit on `fileOrdinal`).
  - Without this, the checker proves only that the mapping is *applied*, not that it is *right*.
  - **Resolved:** `fileOrdinalOf` looks the column up by path in the file's own schema and returns a `@FileOrdinal`; `validateColumn`, `validateSchemaCompatibility`, `FileColumnOrdinals.of` and its field carry `@IndexedBy(OriginalIndex.class) @FileOrdinal int[]`. The canary now fails with `[return]`; returning `originalIndex` also fails, and looking up in `referenceSchema` fails with `[indexedby.handover]`.

- [x] **A loop counter bounded by one space and used in another compiles in the real code, not only in the pinned test.** Canary: in `masksApplicableForRowGroup`, `RowGroupIterator.java:1132` `rowGroup.columns().get(fileOrdinal)` → `get(p)`, where `p < projectedSchema.getProjectedColumnCount()`, a projected index. `-Pcolumn-index-check -Dquick compile` gives **0 errors**.
  - This is the "literals fit every space" limit (`_designs/INDEX_CHECKER.md:102`), but the design undersells it. Both #903 sites are `for (int x = 0; x < count; x++)` loops, so "subscript a file-ordinal list with the loop counter" is the most likely shape of the next #903.
  - Fix: declare the counters in the checked set with their space:
    - `for (@ProjectedIndex int projCol = 0; …)` (`:753`);
    - `for (@ProjectedIndex int p = 0; …)` (`:1129`).
    - With literals at bottom, `@ProjectedIndex int p = 0; p++` type-checks. `ColumnIndexCheckerTest` already shows that `i++` works on a `@ProjectedIndex` counter.
  - State in the design that every loop counter in the checked set must carry its space, and make it a review rule.
  - **Resolved:** Annotating the counter alone did not help: flow refines a local to the literal's bottom type (`moreSpecificValue`), so `@ProjectedIndex int p = 0` still read as bottom. Verified with `ColumnIndexCheckerTest.markedCounterPicksChunk` before the fix. The checker now reads a local that holds only a literal as its declared type, and as `@ColumnIndexUnknown` when it has none, so an unmarked counter is rejected wherever a space is required. The design states the rule; the compiler enforces it. All counters in the checked set are marked. The `get(p)` canary now fails with `[subscript.space]`.

## Should fix

- [x] **`@IndexedBy` is not part of the type, so it is not checked on assignment, argument or return.** The design (`INDEX_CHECKER.md:90`) and the notes present it as a rule the checker enforces. Probe `Read.java` gives no error on any of these:
  - `:15` `List<String> c = chunks; c.get(original)`: aliasing into a local without `@IndexedBy` drops the check.
  - `:22` `use(byProjected, o)`, where `use` takes `@IndexedBy(OriginalIndex.class) int[]`: an array of the wrong space is accepted.
  - `:24` `byOriginal = byProjected;`
  - Real-code instance: `validateSchemaCompatibility` (`RowGroupIterator.java:1553`) returns an unannotated `int[]` that becomes `FileColumnOrdinals.fileOrdinals`, which is `@IndexedBy(OriginalIndex.class)`. Nothing checks the handover.
  - Fix, either:
    - (a) the visitor also compares `@IndexedBy` on `visitAssignment`, `visitVariable` initialisers, method arguments and returns, treating a missing `@IndexedBy` as "unknown" (an error when the other side has one);
    - (b) state the limit in "Limits" and in the notes.
  - **Resolved:** Option (a). The visitor checks `@IndexedBy` on assignment, variable initializer, argument to an `@IndexedBy` parameter (method and constructor) and return. Pinned: `aliasWithoutSpace`, `argumentOfOtherSpace`, `fieldAssignedFromUnmarkedList`, `returnOfOtherSpace`, `aliasKeepsSpace`. Core needed 12 more annotations; `asReference` is the one new conversion point it brought.

- [x] **A cast into a space is only a warning, so `(@FileOrdinal int) original` passes the build.** Probe `Read.java:17`: `warning: [cast.unsafe] cast from "@OriginalIndex int" to "@FileOrdinal int" cannot be statically verified`, and the compile succeeds. The profile has no `-Werror` (no `Werror`/`Xlint` anywhere in the POMs).
  - This gives an unmarked conversion point that bypasses the `@SuppressWarnings("columnindex")` + comment convention of design "Conversion points".
  - Fix, either:
    - override `BaseTypeVisitor.checkTypecastSafety` in `ColumnIndexVisitor` so the unsafe cast is reported with `reportError`;
    - or add `-Werror` to the profile's `compilerArgs`, after checking that core compiles without other javac warnings.
  - Pin it in `ColumnIndexCheckerTest`.
  - **Resolved:** `checkTypecastSafety` is overridden to `reportError`. Pinned in `castIntoSpace`.

- [x] **Combining `-Pindex-check,column-index-check` silently drops the Index Checker.** Both profiles append `-processor X` and `-AonlyDefs=…` to `default-compile`. javac keeps the last `-processor`, and the same goes for `-AonlyDefs`.
  - Canary: add `static int probeCanary(int[] a, int i) { return a[i]; }` to `DictionaryParser`.
    - `-Pindex-check` alone: 2 errors (`array.access.unsafe.low/high`).
    - `-Pindex-check,column-index-check` and `-Pcolumn-index-check,index-check`: **0 errors**, BUILD SUCCESS.
  - That is a false green for anyone (or any CI job) who turns on both.
  - Fix, either:
    - (a) run both processors in one invocation: `<annotationProcessors>` with `combine.children="append"`, plus checker-prefixed options. CF 4.2.3 `SourceChecker.createActiveOptions` supports `-A<CheckerSimpleName>_<option>`, e.g. `-AColumnIndexChecker_onlyDefs=…`. Verify that the Index Checker's subcheckers honour the prefix before relying on it.
    - (b) fail fast when both are active, e.g. an enforcer rule, and say in the design that they are exclusive.
  - **Resolved:** Option (b). An enforcer rule (`one-checker-profile`) fails the build when both are active. Option (a) does not work: with `-AIndexChecker_onlyDefs` the Index Checker's subcheckers ignored the prefix and checked all of core (200+ errors). Documented in the design and notes.

- [x] **The unboxing claim is wrong, and the suppression at `ProjectedSchema.java:149-150` is unnecessary.**
  - Canary: replacing all six `@SuppressWarnings("columnindex")` with `@SuppressWarnings("none")` gives errors at `RowGroupIterator:1526`, `ProjectedSchema:195/196`, `:394`, `:401` and `FileColumnOrdinals:80`, and **none at `ProjectedSchema:150`**.
  - Probe `Box.java:5`: `@OriginalIndex int a(List<@OriginalIndex Integer> l) { return l.get(0); }` is clean. Unboxing an element of a qualified `Integer` keeps the qualifier.
  - What loses it is *boxing* into an unannotated `Integer` (`Box.java:6`, `Integer x = o; return x;` → `@ColumnIndexUnknown Integer`) and an unqualified `List<Integer>` (`Box.java:7`).
  - Fix:
    - drop the suppression and its comment at `:148-149`;
    - change the design's first "Conversion points" bullet (`INDEX_CHECKER.md:96`) to "an `int` boxed into an unqualified `Integer`, such as an element of a `List<Integer>`";
    - update the count "6 in total" and the "one `Integer` unboxing" line in `index-checker-results.md` to 5.
  - **Resolved:** Suppression dropped; `ProjectedSchema` compiles cleanly without it. Design bullet and notes count updated (8 conversion points now, with the new ones from the items above).

- [x] **`ProjectedSchema`'s own mapping arrays and list carry no `@IndexedBy`, so the subscripts that define the projection are unchecked.**
  - Affected: `projectedToOriginal`, `originalToProjected` and `projectedColumns` (`ProjectedSchema.java:37-39`); the locals at `:105/144/200/241/242`; the helper parameters at `:262/302/320`.
  - Canary 1: adding `@IndexedBy(ProjectedIndex.class)` / `@IndexedBy(OriginalIndex.class)` to the three fields compiles with 0 errors. That puts `toOriginalIndex`, `toProjectedIndex` and `getProjectedColumn` under the check at no cost.
  - Canary 2: adding it to every `projectedToOriginal`/`originalToProjected` local and parameter gives exactly 2 errors: `:271` `originalToProjected[col.columnIndex()]` and `:324`, both `found: @ColumnIndexUnknown, required: @OriginalIndex`. Each is a raw `columnIndex()` one token away from the `originalIndex(col)` conversion that the next line already uses.
  - Fix: add the annotations and use `originalIndex(…)` at `:271` and `:324`.
  - **Resolved:** Done, including `projectedColumns` and `getProjectedColumns()`, and `originalIndex(…)` at both sites.

- [x] **The filter path, which is the other half of #903, is outside the checked set, and the notes' "Not in the set yet" list omits it.** These unchecked classes pass ints into `@FileOrdinal` parameters:
  - `PageFilterEvaluator.java:94` → `indexBuffers.forColumn(columnIndex)`;
  - `RowGroupFilterEvaluator.java:296/303` → bloom-filter/dictionary `forColumn(columnIndex)`;
  - `MinMaxStats.java:160` → `readability.readable(ResolvedPredicate.leafColumnIndex(leaf))`.
  - Their indices come from `ResolvedPredicate` leaves, which carry no qualifier. Whether a caller hands in `filterPredicate` (reference ordinals) or `workItem.columnOrdinals().filter()` (file ordinals) — the substitution #903 also fixed at `RowGroupIterator.java:440/457` — is invisible to the checker.
  - Per design Rule 5, `@FileOrdinal` on `forColumn`/`readable` constrains only callers inside the set.
  - Fix:
    - add these three classes, plus `ResolvedPredicate`'s column index, to the "Not in the set yet" list with this reason;
    - reword "Limits" (`INDEX_CHECKER.md:102`): "The checker catches a mix-up of indices that came from typed sources" holds only for code in the checked set, and not through casts or `@IndexedBy` aliasing (items above).
  - **Resolved:** Added to the notes' "Not in the set yet" list, and the design's "Limits" now scopes the guarantee to the checked set and names the filter path.

- [x] **Documented run command resolves the checker from `~/.m2`.**
  - The notes (`index-checker-results.md:100`) say `./mvnw -pl core -Pcolumn-index-check -Dquick compile`.
  - Without `-am`, `hardwood-column-index-checker` (both the `provided` dependency and the `annotationProcessorPaths` entry) comes from the local repository.
  - `~/.m2/repository/dev/hardwood/hardwood-column-index-checker/1.1.0-SNAPSHOT/` currently holds a jar installed at 09:58 by another session. After an edit to the checker, the command silently runs the old checker, and on a fresh clone it fails to resolve.
  - `./mvnw -T4 -pl core -am -Pcolumn-index-check -Dquick compile` builds Parent → Error Prone Checks → Column Index Checker → Test Support → Core and succeeds.
  - Fix: document `-pl core -am`. The design says nothing about `-am` either.
  - **Resolved:** The design and notes give `./mvnw -pl core -am -Pcolumn-index-check -Dquick compile`. Verified: a changed message in the checker source shows up with `-am` and not without.

## Nits

- [x] `ColumnIndexVisitor.checkSubscript` fails open when `found == null` (`ColumnIndexVisitor.java:100`). For `int` indices it is never null, but a null should be a `BugInCF`/error, not a pass (CLAUDE.md fail-early).
  - **Resolved:** Now a `BugInCF`.
- [x] `@IndexedBy` with a class that is not a column-index qualifier crashes the checker instead of reporting an error. Probe `bad/Bad.java`: `@IndexedBy(NonNegative.class) int[] a; a[i]` → `BugInCF: getQualifierKind(...NonNegative) => null ... The Checker Framework crashed`. Validate the value against `qualHierarchy` and report `indexedby.invalid`. `@IndexedBy(ColumnIndexUnknown.class)` is accepted and means "no check"; reject it too.
  - **Resolved:** `indexedby.invalid` for a value that is not a supported qualifier or is the top. Pinned in `indexedByNamesOtherAnnotation`.
- [x] Only `List.get(int)` is a subscript. `set(int, E)`, `remove(int)`, `add(int, E)`, `subList` and `listIterator(int)` on an `@IndexedBy` list are unchecked: probe `Read.java:32` `chunks.set(o, "x")` and `:34` `chunks.subList(o, o + 1)` give no error. Nothing in the checked set uses them today, so either add them to `isSubscript` or name the limit in the design.
  - **Resolved:** `set`, `add`, `remove`, `listIterator` and `subList` are subscripts too. Pinned in `setWithWrongSpace`.
- [x] `nextTouched` (`RowGroupIterator.java:1524-1527`) types the `-1` end marker as `@OriginalIndex`. `FileColumnOrdinals.fileOrdinal` (`:79-80`) types the `-1` absent entry as `@FileOrdinal` for one statement before the check. Both are sound today because of the `>= 0` / `< 0` guards next to them. Still, the comment at `FileColumnOrdinals:78` ("Past the -1 check below") describes a check *after* the suppressed assignment. Move the suppression onto a `return` after the check, e.g. `int ordinal = …; if (ordinal < 0) throw …; return asFileOrdinal(ordinal);`, or reword.
  - **Resolved:** `FileColumnOrdinals.fileOrdinal` has no suppression now: the array elements are `@FileOrdinal`, and `-1` is a literal. Comment reworded. `nextTouched` stays; its `-1` is guarded by `>= 0` at both call sites.
- [x] Conversion points `ProjectedSchema.originalIndex(ColumnSchema)` / `originalIndex(PrimitiveNode)` (`:392-402`) are `public static` and accept a leaf of *any* schema. A caller passing another file's leaf gets an `@OriginalIndex`, which is the #903 shape again. They are effectively unchecked casts. Today their only callers are inside `ProjectedSchema` (next to `:271` and `:324`), so make them `private` (or package-private) until another class in the set needs them.
  - **Resolved:** Both overloads are private.
- [x] Design Rule 2 (`INDEX_CHECKER.md:55`) still says "An unannotated link breaks the proof and the checker reports it". Round 1 showed this is false for links in unchecked classes. Rule 5 now scopes it, but Rule 2 on its own still overstates. Add "within the checked set".
  - **Resolved:** Added "Within the checked set".
- [x] Stub limit wording. Probe `stub/`:
  - a stub on a top-level record accessor applies;
  - a stub on a record nested in an interface does not (both component and accessor form);
  - a stub on a *class* nested in an interface does apply.
  - The design's "members of nested records" (`INDEX_CHECKER.md:104`) is accurate. The notes' "(cause not found)" can say it is record-specific.
  - **Resolved:** Design and notes say the limit is specific to records nested in another type; nested classes and top-level records work.
- [x] Downstream `Cannot find annotation method 'value()' in type 'IndexedBy'`. The notes' "no warning downstream" holds for this repo's builds, which use default lint.
  - A consumer compiled with `-Xlint:all` against core without the checker jar gets 9 `[classfile]` warnings from `RowGroupIterator`, `RowGroupIterator$WorkItem` and `FileColumnOrdinals`.
  - With `-Werror -Xlint:all` the compile fails (scratch `consumer/c/Consumer.java`).
  - Only `internal` members carry it, so this is low risk. Qualify the claim ("with default lint") or note it next to the existing `checker-qual` item.
  - **Resolved:** Notes qualify the claim with "under the default lint" and describe the `-Xlint:all` / `-Werror` case.
- [x] `ParquetFileReader.java:786` `List<dev.hardwood.metadata.ColumnChunk>`: a fully qualified name in code this branch touched nearby. It predates the branch, but CLAUDE.md asks for imports.
  - **Resolved:** Imported.

## Verified correct

- #903 canary. Restoring the pre-fix lines gives 8 errors under `-Pcolumn-index-check -Dquick compile`:
  - in the per-column loop (`RowGroupIterator.java:753-775`): `subscript.space` for `rowGroup.columns()` and `workItem.fileSchema()`, and `argument` for `RowGroupIndexBuffers.forColumn`, `RowGroupDictionaryFilterSource.loaded`, `BoundsReadability.readable` and `loadedPageEnd`;
  - in `masksApplicableForRowGroup` (`:1130` reverted to `toOriginalIndex(p)`): `subscript.space` for `rowGroup.columns()` and `fileSchema`.
  - All messages show `found: @OriginalIndex, required: @FileOrdinal`.
  - `WorkItem`'s record-component `@IndexedBy` reaches the `fileSchema()` accessor, and the `RowGroup.columns()` stub applies.
- Baseline: `-Pcolumn-index-check -Dquick compile` with 0 errors on the checked set, including under `-T4 -am`.
- The five other suppressions each hide exactly one real finding (listed in the unboxing item above), and each comment states the fact asserted.
- Arithmetic across spaces is rejected (LUB is top): `Read.java:19`. `-1`/absent used as projected is rejected: `:26`. Flow re-assignment is tracked: `:30`. `(int)` of a qualified `long` keeps the qualifier: `:38`.
- Reactor order under `-T4`: the checker module is built before core. The `provided` dependency orders it.
- Published POM: core's `provided` dependency on the non-deployed `hardwood-column-index-checker` (`maven.deploy.skip=true`) follows the existing precedent of the `test`-scoped `hardwood-error-prone-checks` (also `maven.deploy.skip=true`). Neither scope is transitive for Maven or Gradle consumers.
- Clean step: `./mvnw -pl core -Pindex-check verify -DskipITs` gives BUILD SUCCESS, 13,983 tests, 0 failures, 2 skipped, 56 s. The jar lands in `target/index-check`.
- `tools/column-index-checker` `verify` (with `qa`): `ColumnIndexCheckerTest` 5/5 pass.
- Review-1 resolutions hold:
  - `RowGroupIteratorSkipTest`: 3 tests pinning full messages for `tailSkip`/`physicalSkip`/`setTailSkip`.
  - `matchesAll` replaces the `value` suppression.
  - `skipRejectsNegative` and the new `tailRejectsNonPositive` use `hasMessage`.
  - `SequentialFetchPlan.getValueCount` returns `int` and the cast is gone.
  - The redundant local `@NonNegative` in `DictionaryPageHeaderReader` is removed.
  - The `DictionaryParser` `compressedSize` fix is real: reverting it makes `DictionaryParserTest.aBodyTooShortForItsValuesReportsItsSize` fail.
- Design "Index spaces" facts on #903 and #525, the qualifier table, and the stub mechanism (`-AmergeStubsWithSource`, `@StubFiles`) match the code.
