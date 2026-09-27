# Review 1: Index Checker spike, `e43d08dd..2f8968c8`

Reviewed in worktree `/home/user/hardwood/.claude/worktrees/review-index-checker` (detached at `2f8968c8`, clean), JDK 25, `./mvnw` under `timeout 180`. All experiments below were reverted.

## Must fix

- [x] **The row-skip removal is sound only by inspection, not by the checker. It swaps fail-fast for silent wrong results for any caller outside the checked set.** `RowGroupIterator` is not checked, so its `@NonNegative` annotations only constrain callers that happen to be in the checked set. Nothing stops a new unchecked caller (core class, test, benchmark) from passing a negative value.
  - Canary: I removed `@NonNegative` from `RowGroupIterator.tailSkip` (field), from `setTailSkip(long)` and from `Dictionary.parse(..., int numValues, ...)`, then ran `./mvnw -pl core -Pindex-check -Dquick compile`. Result: `BUILD SUCCESS`. So design Rule 2 ("An unannotated link breaks the proof and the checker reports it") and the `fdb3e7d1` message ("dropping any link fails the build") are false for links inside unchecked classes.
  - Behaviour if a negative value arrives now (traced in `RowGroupIterator.java`):
    - `tailSkip < 0`: every use is gated by `tailSkip > 0` (`:701`), so the value is ignored and the read starts at row 0.
    - `physicalSkip < 0`: `planSkipRemaining = physicalSkip` (`:367`), and `planSkipRemaining >= rgRows` is false (`:1266`), so `firstRowGroupSkip = -N` (`:1274`). `ParquetFileReader.java:538-542` then computes `readerMaxRows = maxRows + (-N)` and `discardLeadingRows(reader, -N)` does nothing. The reader silently returns N fewer rows, starting at row 0.
    - Before this change, both cases threw `IllegalArgumentException`. CLAUDE.md: "Silent failures are never an option."
  - Current callers are all fine:
    - Production: only `ParquetFileReader` (`:470`, `:694`), which is checked.
    - Tests: `RowGroupIteratorCloseTest:76,88,110`, `PageScannerTest:179` and `ColumnWorkerTest:652` use the 3-arg constructor, which passes a literal `0`.
    - No other module references `RowGroupIterator`.
  - Options:
    - (a) Restore the three checks until `RowGroupIterator` is in the checked set. They run once per reader, so the removal saves nothing measurable.
    - (b) Reduce the unchecked surface: make the `List<InputFile>` 4-arg and 5-arg constructors private or delete them (they are reachable only with literal `0` from the 3-arg one). That leaves the `FileMetadataCache` constructor and `setTailSkip`, both called only from `ParquetFileReader`. Then state in the design that callers of annotated signatures in unchecked classes must be in the set.
    - (c) Bring `RowGroupIterator` into the set with narrow, commented suppressions for its 15 unrelated errors.
  - Whichever option is chosen, change the notes' "Sound anyway" to what actually holds: true for today's callers, not enforced for new ones.
  - Resolved: option (a). The three `RowGroupIterator` checks are back, and `RowGroupIteratorSkipTest` pins their messages. Design Rule 5 and the notes state what holds.

- [x] **The profile's separate directory does not fix staleness when the checked set changes. A warm `target/index-check` reports green without checking anything.** Evidence:
  1. Ran `./mvnw -pl core -Pindex-check -Dquick compile` twice. The second run printed `Nothing to compile - all classes are up to date.`
  2. Then ran `... '-Dindex-check.classes=^dev\.hardwood\.internal\.reader\.RowGroupIterator(\..*)?$'`, which should report 15 errors. It printed `Nothing to compile` and `BUILD SUCCESS`.
  - The same happens after editing the regex in `core/pom.xml`, bumping `checker-framework.version`, or any other change that touches no source. It undermines the canary workflow and the "joins the set with zero errors" claim whenever a class is added without a source edit.
  - Fix: force recompilation in the profile, for example a `maven-clean-plugin` execution in `initialize` that deletes `${project.build.outputDirectory}` (the profile's `target/index-check/classes`), or document `clean compile` as the only valid invocation.
  - Also correct `core/pom.xml:236-238`, design "Tooling" and the notes "Tooling (verified)", which say the separate directory "keeps a plain build from ever standing in for a checked one". That is true, but a previous checked build with a different set can still stand in.
  - Resolved: a `maven-clean-plugin` execution in `initialize` empties the profile's directory. Two runs in a row both compile 405 files.

## Should fix

- [x] **`ParquetFileReader.java:766-768`: the suppression can be removed entirely.**
  - The real error key is `switch.expression`, not a generic `value` error. With the suppression removed: `ParquetFileReader.java:[778,31] [switch.expression] incompatible types in switch expression. found: @BoolVal(false) boolean, required: @BoolVal(true) boolean`. It is the only error in the class.
  - Moving the `And` loop into a helper (`case RowGroupPredicate.And a -> matchesAll(rg, a.children());` with a plain `for`/`return false`/`return true` method) compiles with `BUILD SUCCESS` and no suppression under `-Pindex-check -Dquick`. I verified this.
  - If the suppression stays, narrow it to `@SuppressWarnings("value:switch.expression")`, which I verified also passes.
  - `"value"` is the correct checker key: `@SuppressWarnings("index")` does not suppress this error (verified: build fails). So design Rule 4, which only mentions `@SuppressWarnings("index")`, should also cover Value Checker findings (`"value:<key>"`).
  - Resolved: `matchesAll` helper, no suppression. Rule 4 names the `"value:<key>"` form.

- [x] **The "stays" table has three factually wrong reasons** (`index-checker-results.md`). The outcomes are right; the reasons are not.
  - `BoundsReadability:93`: "arrives through a JDK functional interface". Wrong: `BoundsReadability` is Hardwood's own `@FunctionalInterface` (`internal/predicate/BoundsReadability.java:40-49`, `boolean readable(int columnIndex)`), so its parameter can carry a qualifier. The actual blockers:
    - The upper half (`columnIndex >= readable.length`) relates the index to an array captured inside a lambda (`:84`), which no qualifier can name.
    - That array is sized per file, while the index comes from `ResolvedPredicate.leafColumnIndex` or `fileOrdinal` (`MinMaxStats:160`, `RowGroupIterator:751`).
  - `FileMetadataCache:140`: "'Not found' branch, not validation". Wrong for `required == true`: `Objects.checkIndex(fileIndex, inputFiles.size())` is the only range check behind the public `ParquetFileReader.getFileMetaData(int)` (`ParquetFileReader.java:297-306`, whose JavaDoc promises `IndexOutOfBoundsException`). It is a public-API boundary check that lives in internal code. It stays for that reason.
  - `BatchSizing:100`: "Derived from `RowGroup.numRows`, a component of a public record any caller can construct. The public record is the boundary." Misleading:
    - No public API accepts a `RowGroup` or `FileMetaData`. Every `RowGroup` on the read path comes from `RowGroupReader:57` via `readNonNegativeI64()`, a file-byte boundary.
    - The real blockers: `availableRows` carries the `ROWS_UNKNOWN = -1` sentinel (`BatchSizing.java:30`, `ParquetFileReader.java:711-715`), so the parameter cannot be `@NonNegative`. The fact would also have to sit on the public record component `RowGroup.numRows`, which puts `checker-qual` into the public API.
  - Spot-checked and accurate:
    - `PageInfo:74`: the only caller is `SequentialFetchPlan:642`, and `placeholderRecords` can be `numValues` from a header, which can be 0.
    - `MergePlan:32`: `int c = bucket.getKey()` from `Map<Integer, …>` at `BatchFilterCompiler:239`. Both callers also index `matchers[c]` before constructing, so the check cannot fire in practice.
    - `RowRanges:46`: the only caller is `RowGroupIterator:702`. Its `start` is the spike's own `@NonNegative tailSkip`, but `RowGroupIterator` is unchecked.
    - `SequentialFetchPlan:152`: a conditional fact.
    - `ByteArrayBuilder:35`: callers pass `32` or `ValueEncoder.startingCapacity`, which clamps to `[512, 65536]` (`ValueEncoder:96-98,166-167`).
  - Resolved: all three reasons rewritten as given.

- [x] **Design Rule 5 (`_designs/INDEX_CHECKER.md`) is both too broad and too narrow.**
  - Too broad: read literally, it is violated today. `cli` (`InspectDictionaryCommand:182`, `dive/ParquetModel:343`) calls `DictionaryParser.parse`, whose path lost a check, and `cli` is not checked. That is harmless, because `parse(ByteBuffer, …)` reads the header from bytes and takes no `numValues`.
  - Too narrow: it says nothing about unchecked classes in the same module, or about tests, which is where the real gap is (see Must fix 1).
  - Suggested rule: "Every call site that passes a value into an annotated parameter or record component is either in the checked set or passes a literal; otherwise the callee keeps its runtime check."
  - Resolved: the suggested rule, plus a sentence on annotations in unchecked classes.

- [x] **Pin the only remaining skip check in full.** `ParquetReaderTest.java:591-598` `skipRejectsNegative` asserts only `isInstanceOf(IllegalArgumentException.class)`. Now that `RowReaderBuilder.skip` (`ParquetFileReader.java:908-910`) is the only check, add `.hasMessage("skip must be non-negative: -1")`, as CLAUDE.md requires. Also check that `tail(0)` / `tail(-1)` are pinned with `hasMessage`: I found no test asserting `"tail row count must be positive: "`.
  - Resolved: `hasMessage` added; new `tailRejectsNonPositive`.

- [x] **The index-spaces paragraph in the design overstates #1242** (`_designs/INDEX_CHECKER.md`, "Index spaces").
  - The sentence cites #1242 as a case where "passing a number from one space where another is expected … can return wrong rows without an error". The notes' own finding: #1242 was a missing mapping that threw, not a cross-space mix-up and not wrong rows.
  - Either drop #1242 from that sentence, or state that it is the `-1` "not projected" sentinel case, which qualifiers catch only if the sentinel gets its own qualifier.
  - Resolved: #1242 dropped; #903 cited as the shipped mix-up.

## Nit

- [x] `DictionaryPageHeaderReader.java:30`: the `@NonNegative` on the local `numValues` is redundant. Locals are flow-inferred. I removed it (and its import) and ran `-Pindex-check compile`: `BUILD SUCCESS`. Dropping the return annotation on `readNonNegativeI32()` still fails at `DictionaryPageHeaderReader.java:[54,41] [argument]`. The Checker Framework manual advises against annotating locals. Drop it, and drop "the `DictionaryPageHeaderReader` local" from the list of chain links in the notes.
  - Resolved.
- [x] Error counts in the notes are stale. `Dictionary` has 12 errors, not 17: the 5 `array.length.negative` at the `numValues` allocations went away with the annotation. `ThriftCompactReader` has 10, not 12. Measured with `-Dindex-check.classes` set to each class alone (javac summary `12 errors`). `RowGroupIterator` does have 15.
  - Resolved.
- [x] Design "Tooling": "The Index Checker runs in the same `javac` invocation as Error Prone" holds only when the `qa` profile is active. `qa` is activated by `!quick` (`pom.xml:378-383`), so the documented `-Dquick` invocation runs the checker without Error Prone. Say so. Otherwise a reader expects `--should-stop=ifError=FLOW` and Error Prone ordering in the quick run.
  - Resolved.
- [x] Design and notes do not say that `src/main/java22` (the `compile-java22` execution) and test sources are never checked. Only `default-compile` gets the processor. That is correct behaviour, but worth one sentence.
  - Resolved.
- [x] Notes "Scratch spikes": "The re-check at `DictionaryParser.java:150` becomes dead." It was already dead since `d116cc32` (#604), which introduced `readNonNegativeI32()` for `num_values`. The checker documents the fact; it did not make the check dead. The commit message says it correctly ("could only fire on a header built by hand").
  - Resolved.
- [x] `checker-qual` is `provided`, so consumers compile without it. That is harmless for `@NonNegative`, which has no elements. Once element-bearing qualifiers (`@IndexFor("a")`, `@LessThan`, `@IntRange`) land on signatures other modules or users compile against, `javac` warns "Cannot find annotation method 'value()' in type …", which fails `-Werror` builds. Consider this before annotating widely used internal signatures. The version is declared inline in `core/pom.xml:69` rather than in a BOM; putting it in the user-facing `bom/pom.xml` would advertise it, so inline is defensible.
  - Considered: no element-bearing Index Checker qualifier is on a signature yet. `@IndexedBy` (from the new `provided` checker module) has an element; `./mvnw verify -DskipITs` shows no "Cannot find annotation method" warning in any downstream module.
- [x] Pre-existing, found while tracing (not caused by this branch):
  - `DictionaryParser.java:201-205`: the failure message reports `compressedSize=` + `compressedData.remaining()` after the decompressor consumed the buffer, so it always prints `0`. Observed: `Failed to parse dictionary (type=INT64, numValues=-1, uncompressedSize=80000, compressedSize=0, codec=UNCOMPRESSED)`. Capture the size before decompressing.
  - `SequentialFetchPlan.java:590`: `(int) getValueCount(header)` is an unchecked narrowing cast (CLAUDE.md: prefer `Math.toIntExact`).
  - `PageInfo.nullPlaceholder` throws `IllegalArgumentException` for a value that comes from file bytes (a page with `num_values = 0`, inline stats and drop-by-stats), where `ParquetReadException` would be expected. The table's "guards file bytes" reason makes this worth a look.
  - `compressedSize=0` fixed with a failing-first test; the `SequentialFetchPlan` cast removed (`getValueCount` returns `int`). `PageInfo.nullPlaceholder` left open in the notes: it needs a fixture.

## Verified correct

- Dictionary chain soundness:
  - The only `new DictionaryPageHeader(` in main code is `DictionaryPageHeaderReader:54` (checked).
  - The only `new PageHeader(` in main code is `PageHeaderReader:101`. Test helpers in `DictionaryParserTest:171,176` only copy existing headers or pass `null`.
  - The benchmark (`DictionaryPageParseBenchmark:113,131,160`) and `cli` get headers from `PageHeaderReader`.
  - `PagesScreen` in `cli` only reads `DictionaryPageHeader`.
  - No reflection or `reflect-config` references.
  - There is no `module-info.java`, so internal packages are reachable by users, but that matches the project's public/internal convention.
- If a negative `numValues` still arrives (a hand-built header), the failure is **not silent**. I ran the deleted test body: `Dictionary.parse` → `NegativeArraySizeException` → caught in `DictionaryParser.decompress` → `ParquetReadException("Failed to parse dictionary (type=INT64, numValues=-1, …)")`. Removing that check is safe.
- Canaries, all run by me:
  - Dropping `@NonNegative` from the `DictionaryPageHeader.numValues` component fails at `DictionaryParser.java:[157,43] [argument]`.
  - Deleting `if (skip < 0) throw` in `RowReaderBuilder.skip` fails at `ParquetFileReader.java:[908,25] [assignment]`, both with `-Dquick` and without it (Error Prone active).
  - Dropping the return annotation on `readNonNegativeI32()` fails at `DictionaryPageHeaderReader.java:[54,41]`.
- Nested classes are checked (the `RowReaderBuilder` canary fires). The regex does not accidentally match `DictionaryPageHeaderReader` through the `DictionaryPageHeader` alternative.
- `ThriftCompactReader`, `Dictionary` and `RowGroupIterator` report no errors on the annotated chain lines when checked alone (their errors are elsewhere). So the chain would hold if they joined the set.
- `setTailSkip` is only called under `fastSkip && skip > 0` (`ParquetFileReader:469-470`). `skip = Math.max(0, …)` (`:455`) is accepted by the checker. `buildRowReader(..., skip)` and `buildTailRowReader` have no callers outside `ParquetFileReader` and its nested builder; the core test classes in `dev.hardwood.reader` don't call them.
- No test exercised the deleted `RowGroupIterator` checks (no match for `tailSkip must be` / `physicalSkip must be`).
- Profile side effects:
  - Output goes to `target/index-check/classes`, the `compile-java22` output to `target/index-check/classes/META-INF/versions/22`, and the jar to `target/index-check/hardwood-core-1.1.0-SNAPSHOT.jar`.
  - Failsafe `classesDirectory` (`core/pom.xml:219`) uses `${project.build.directory}` and follows.
  - The profile exists only in `core`, so other modules are unaffected. License, impsort and formatter work on sources.
  - A no-profile `clean` removes `target/index-check` too.
  - The explicit `-processor` does not disable any other processor in core (AutoService is used only in `error-prone-checks`).
- `./mvnw -pl core -Pindex-check verify -DskipITs`: 13,978 tests, 0 failures, `BUILD SUCCESS` (58 s).
- `./mvnw -pl parquet-testing-runner -am verify -Dquick -DskipTests=false -DskipITs -Dtest=BadDataHandlingTest`: 13 tests, 0 failures.
- `MalformedMetadataValidationTest.negativeDictionaryNumValuesRejected` asserts the full message with `hasMessage`. `DictionaryParserTest` has no dead imports or helpers after the deletion.
- Plugin versions: the profile references `maven-compiler-plugin` without a version (CLAUDE.md rule respected). No `var`. No legacy JavaDoc. Commit bodies explain why.
