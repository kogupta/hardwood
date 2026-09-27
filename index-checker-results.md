# Index Checker spike: progress

Base: `main` at 9f84f5bbc2bd83956df19e6192c48f592115b01f. Branch: `claude/fervent-ramanujan-ucff0m`.
Design: [_designs/INDEX_CHECKER.md](_designs/INDEX_CHECKER.md).

## Open questions

- [ ] Issue key for commits. Issues are disabled on `kogupta/hardwood` (API returns 410), and CLAUDE.md requires every commit to start with an issue key. Commits use the `spike:` prefix of the earlier `spike/nullness` and `spike/pbt` branches. Work stays on this branch; no PR planned.

## Tooling (verified)

- The profile compiles into `target/index-check` and empties it in `initialize`. Sharing `target/classes` let a plain build mark the classes up to date, and `-Pindex-check` then reported "Nothing to compile" and checked nothing. A separate directory alone was not enough: a second checked run, or one with a different `-Dindex-check.classes`, also printed "Nothing to compile". With the clean step, two runs in a row both print `Compiling 405 source files`.
- Error Prone runs in the same `javac` call only without `-Dquick` (the `qa` profile). Only `default-compile` gets the checker: `src/main/java22` and test sources are never checked.

- Checker Framework 4.2.3 (`checker`, `checker-qual`) from Maven Central.
- Runs on JDK 21 and JDK 25 `javac` (container JDK 25: Ubuntu `openjdk-25-jdk-headless` 25.0.4.1).
- Needs the `--add-exports`/`--add-opens` flags for `jdk.compiler` that `.mvn/jvm.config` already sets for Error Prone.
- Runs in the same `javac` call as Error Prone 2.49.0 with `NoVar` and `NoLegacyJavadoc`. Error Prone requires `--should-stop=ifError=FLOW`. An Error Prone error suppresses all Index Checker output in that run, even with `-Awarns`.
- `NoVar` only matches files under `/src/(main|test)/java*/`; a scratch copy outside that layout never triggers it.
- Manual gap: the manual writes `@HasSubsequence(value = …)`; the element is `subsequence`.
- `Objects.checkIndex` carries no index annotations in the 4.2.3 annotated JDK, so it narrows nothing.

## Baseline measurements (core main, no annotations, JDK 21)

| Measure | Value |
|---|---:|
| Source files | 405 |
| Index errors | 2,888 (2,781 in `internal`) |
| Files with errors | 181 |
| Plain `javac` | 3.4 s |
| With Index Checker, whole module | 2 m 45 s |
| With Index Checker, `internal/thrift` + `internal/metadata` only | 19 s |

Errors by kind: `array.access.unsafe.high` 1,073, `array.access.unsafe.low` 945, `argument` 352, `array.access.unsafe.high.range` 263, `array.length.negative` 201, other 54.

Baseline build on JDK 25: `./mvnw -pl core -am install -DskipITs` passes in 63 s.

## Scratch spikes (not in the tree)

- **Dictionary `numValues`.** `@NonNegative` on `ThriftCompactReader.readNonNegativeI32()`, `DictionaryPageHeader.numValues`, `DictionaryParser.decompress` and `Dictionary.parse` clears the "array size could be negative" errors in `Dictionary.parse`. The re-check at `DictionaryParser.java:150` was already dead since `d116cc32` (#604), which introduced `readNonNegativeI32()` for `num_values`; the checker documents that fact.
- **`PqIntListImpl`.** 17 errors to 0 with a `final int[]` field under `@HasSubsequence`, `@LengthOf("this")` on `size()`, and the bounds check inlined in `get`. Cost: one constructor suppression (it hides 7 errors: the 4 start/end facts, stated against local names, and the `valueArrays[projectedCol]` lookup) and one range check per list.

## Classification of internal bound checks

160 checks found by a regex pass over `if … throw` bound conditions and `checkIndex`-style calls in `internal`. Rough counts; the worklist below is verified by tracing callers.

| Group | Examples | Outcome |
|---|---|---|
| File-byte boundary | `internal/thrift`, `internal/compression`, decoders in `internal/encoding`, `VariantMetadata`, `VariantValueDecoder` | Stays |
| Public-API boundary implemented in `internal` | `Pq*ListImpl.get`, `PqMapImpl.get`, `PqStructImpl:314`, `FlatRowReader:855`, `NestedBatchDataView:465`, `RowStructNode:459`, predicate literal checks in `FilterPredicateResolver` | Stays |
| "Not found" sentinel branches | `TopLevelFieldMap:116`, `FileColumnOrdinals:75`, `valueCol < 0` in the `Pq*Impl` variant paths | Not validation; stays |
| Shape checks | `children().size() != 1`, `value.length != typeLength` | Out of scope |
| Internal re-validation | see worklist | Replace with types |

## Worklist

Each item: annotate the path from the boundary, delete the re-check, run the checker, run the tests.

- [x] `DictionaryParser.java:150` `numValues < 0`. Boundary: `ThriftCompactReader.readNonNegativeI32()` via `DictionaryPageHeaderReader:40`.
  - `@NonNegative` on `readNonNegativeI32()`, the `DictionaryPageHeader.numValues` component, `DictionaryParser.decompress` and `Dictionary.parse`.
  - Re-check and `DictionaryParserTest.rejectsANegativeValueCount` deleted. The rejection is now pinned where it happens: `MalformedMetadataValidationTest.negativeDictionaryNumValuesRejected` asserts `DictionaryPageHeader.num_values — must be non-negative but was -1`. `BadDataHandlingTest.rejectDictheader` / `rejectArrowGH41321` still pass.
  - Canary: dropping `@NonNegative` from the record component fails the build at `DictionaryParser.java:157` (`[argument] found: int, required: @NonNegative int`).
  - Checked set: `DictionaryPageHeader`, `DictionaryPageHeaderReader`, `DictionaryParser` (0 errors). `ThriftCompactReader` and `Dictionary` carry annotations but are not in the set yet; their signatures are still checked at every call from a checked class.
- [x] `RowGroupIterator` `tailSkip`/`physicalSkip < 0`. Boundary: `RowReaderBuilder.skip(long)` and `tail(long)` in `ParquetFileReader`; `setTailSkip` is only called under `skip > 0`.
  - `@NonNegative long` on the builder's `skip` field, `buildRowReader(…, skip)`, the two private `buildRowReader` overloads, `trackedIterator`, the `RowGroupIterator` constructors, its two fields and `setTailSkip`.
  - The three `RowGroupIterator` checks stay. `RowGroupIterator` is outside the checked set, so its annotations bind only callers inside the set: removing `@NonNegative` from its field and `setTailSkip` still compiles. A negative value from an unchecked caller would be read silently (`tailSkip < 0` is ignored; `physicalSkip < 0` returns N fewer rows). `RowGroupIteratorSkipTest` pins the three messages.
  - What the annotations do prove: deleting the public `if (skip < 0) throw` in `skip(long)` fails the build (`[assignment] found: long, required: @NonNegative long`), so the builder's check is load-bearing for compilation. `ParquetReaderTest.skipRejectsNegative` and `tailRejectsNonPositive` pin its messages.
  - `ParquetFileReader` joins the checked set. Its one error was a Value Checker false positive on the `switch` in `matches` (`[switch.expression] found @BoolVal(false), required @BoolVal(true)`). Moving the `And` loop into `matchesAll` removes it with no suppression.
- [x] Trace remaining candidates. None converted; each stays for the reason given.

  | Check | Outcome | Reason |
  |---|---|---|
  | `BatchSizing:100` `availableRows < 0` | Stays | `availableRows` carries the `ROWS_UNKNOWN = -1` sentinel, so the parameter cannot be `@NonNegative`. Proving the other values would also put `@NonNegative` on the public record component `RowGroup.numRows`. |
  | `PageInfo:74` `numValues <= 0` | Stays | A page header may declare `num_values = 0`; the check guards file bytes. |
  | `ByteArrayBuilder:35` capacity `< 0` | Stays (cost) | Provable: callers pass `32` or a clamp to `[512, 65536]`. Enforcing it needs `ColumnChunkBuffer` (22 errors) in the checked set. |
  | `ByteArrayBuilder:91` `reserve` length `< 0` | Stays | Lengths are products (`Math.multiplyExact`), which the checker does not bound. |
  | `MergePlan:32` `projectedIndex < 0` | Stays | One caller takes the index from an `Integer` map key, which carries no facts. A projected-index fact: see index spaces. |
  | `BoundsReadability:93` | Stays | `BoundsReadability` is Hardwood's own functional interface, so its parameter can carry a qualifier (it now takes `@FileOrdinal`). The upper half, `columnIndex >= readable.length`, relates the index to an array captured in a lambda, which no qualifier can name. |
  | `RowRanges:46` | Stays | Only `start >= 0` is provable; `start < end` relates a skip to a row count. |
  | `SequentialFetchPlan:152` | Stays | Positive only when the mask is non-trivial; a conditional fact has no qualifier. |
  | `ResolvedPredicate:307` | Stays | `definitionLevel <= leafDefinitionLevel` is a schema-tree fact read from public `SchemaNode`s. |
  | `RleBitPackingHybridEncoder:56` bit width | Stays (cost) | Provable as `@IntRange(from = 0, to = 32)` from `LevelEncoder.bitWidth` (`32 - numberOfLeadingZeros`). Enforcing it needs `ColumnChunkBuffer` (22), `RleBitPackingHybridEncoder` (10) and `LevelEncoder` (2) clean. |
  | `BitPacker:31` bit width | Stays (cost) | Same as above plus `DeltaBinaryPackedEncoder` (23). The divisibility half is not an index fact. |
  | `FileMetadataCache:140` | Stays | For required loads, `Objects.checkIndex` is the only range check behind the public `ParquetFileReader.getFileMetaData(int)`: a public-API boundary check in internal code. |

  The costly ones fail on mutable buffer cursors: a growable `byte[]` with a `length` field, and a counter that reaches the array length for one statement before resetting. The checker cannot state "below the length at method entry", so these need suppressions, around 34 of them to delete one three-line check.
- [ ] Classes on the paths above not yet in the checked set: `ThriftCompactReader` (10 errors), `Dictionary` (12), `RowGroupIterator` (15), `BatchSizing` (1). In the set: `DictionaryPageHeader`, `DictionaryPageHeaderReader`, `DictionaryParser`, `ParquetFileReader`.
- [x] `index-check` profile in `core/pom.xml`; `checker-framework.version` in the parent POM; `checker-qual` as `provided`. Run: `./mvnw -pl core -am -Pindex-check install -DskipITs` (52 s with Error Prone and unit tests, vs 63 s baseline run earlier; within noise). `.mvn/jvm.config` flags suffice; nothing extra needed for Maven.
- [ ] Full `./mvnw verify` on JDK 25 before pushing. numValues change: all modules pass except the Docker-based S3 ITs (`Could not find a valid Docker environment`; no Docker in this container). `-rf :hardwood-s3 -DskipITs` passes; core unit tests 13,890 run, 0 failures. Keep open: the S3 ITs have not run.
  Skip change: same result. Unit tests pass in every module; every failing IT is a Docker-bound S3 test (`hardwood-s3`, the `*S3CommandIT` classes in `cli`, `ParquetReaderS3CompatIT`).

## Pre-existing bugs found while tracing

- [x] `DictionaryParser.decompress` reported `compressedSize=0` in its failure message: it read `compressedData.remaining()` after the decompressor had consumed the buffer. The size is now read first. `DictionaryParserTest.aBodyTooShortForItsValuesReportsItsSize` failed before the fix.
- [x] `SequentialFetchPlan`: `(int) getValueCount(header)` narrowed a `long` that only ever held an `int`. `getValueCount` now returns `int`.
- [ ] `PageInfo.nullPlaceholder` throws `IllegalArgumentException` for a value from file bytes (a data page with `num_values = 0`, inline stats and drop-by-stats), where a `ParquetReadException` naming the file is expected. Not changed: needs a fixture with such a page.

## Index spaces

Design: [_designs/INDEX_CHECKER.md, Index spaces](_designs/INDEX_CHECKER.md#index-spaces). Run: `./mvnw -pl core -am -Pcolumn-index-check -Dquick compile`. Without `-am`, the checker comes from `~/.m2`; a changed message in the checker source showed up only with `-am`.

- [x] Bug class confirmed. `e28d8a2d` (#903) fixed a cross-space mix-up that shipped: the multi-file reader used reference-schema ordinals as file ordinals in `rowGroup.columns().get(…)`, `fileSchema().getColumn(…)` and `indexBuffers().forColumn(…)`, and decoded one column's pages into another column's slot when a later file ordered its columns differently. `0625f55b` (#525) is a guard test for field index vs leaf-column index. `6cff8f99` (#1242) is a missing mapping that threw, not a mix-up.
- [x] Custom checker, `tools/column-index-checker` (module `hardwood-column-index-checker`). The stock Subtyping Checker checks arguments, assignments and returns, but not subscripts, and the space of `FileSchema.getColumn(int)` depends on which schema instance it is called on. The checker adds `@IndexedBy(X.class)` on the variable, field, parameter or method that holds an array, list or schema. It checks `array[i]`, the `List` methods that take a position, and `schema.getColumn(i)` against it. Qualifiers: `@OriginalIndex`, `@ProjectedIndex`, `@ProjectedIndexOrAbsent`, `@FileOrdinal`, top `@ColumnIndexUnknown`, bottom `@ColumnIndexBottom`.
- [x] `@IndexedBy` is checked where an array, list or schema changes hands: assignment, variable initializer, argument, and return. Before this, `List<String> c = chunks; c.get(original)` dropped the check. Only a parameter of a method outside the checked set (a JDK method, or a class the profile does not check) accepts any container; inside the set, `help(chunks, i)` with an unmarked `List` parameter is an error.
- [x] Copies and views keep their space: `clone()`, `Arrays.copyOf`, `Arrays.copyOfRange`, `List.copyOf`, a constructor given a container (`new ArrayList<>(chunks)`), and `schema.getColumns()`. A conditional of containers is checked branch by branch. Before this, `workItem.fileSchema().getColumns().get(originalIndex)`, the #903 mistake spelled another way, compiled cleanly, and so did two lines of `ProjectedSchema` that use this form.
- [x] A cast into a space is an error (the stock checker only warns), so every conversion point is a visible suppression.
- [x] Loop counters. Literals are bottom (`@QualifierForLiterals`), and flow refines a local to the more specific type, so `for (int p = 0; …; p++)` and even `for (@ProjectedIndex int p = 0; …)` stayed bottom and fitted every space. Verified in the real code: `get(fileOrdinal)` changed to `get(p)` in `masksApplicableForRowGroup` compiled cleanly. The checker now keeps the literal's bottom type only for a value written with literals and operators. A variable that flow knows holds a literal (local, parameter, field or named constant) reads as its declared type, and so do `i++` and `--i`. A marked counter is its space; an unmarked one is `@ColumnIndexUnknown` and is rejected where a space is required. A conditional reads as the least upper bound of its branches, so `c ? a : b` of two `@FileOrdinal` locals is `@FileOrdinal`. All counters in the checked set now state their space.
- [x] A counter's declared space is trusted: `for (@FileOrdinal int p = 0; p < projectedCount; p++)` compiles. The checker does not relate the declaration to the loop bound. In the real loops the body also uses the counter in its true space (`plans[projCol]`, `toOriginalIndex(p)`), so a wrong declaration fails there.
- [x] Checked set, 0 errors: `ProjectedSchema`, `FileColumnOrdinals`, `RowGroupIterator`, `RowGroupIndexBuffers`, `BoundsReadability`, `RowGroupDictionaryFilterSource`.
- [x] The mapping itself is checked. `validateColumn` returns a `@FileOrdinal` found through `fileOrdinalOf`, which looks the column up by path in that file's schema. The mapping array is `@IndexedBy(OriginalIndex.class) @FileOrdinal int[]` from `validateSchemaCompatibility` through `FileColumnOrdinals`.
- [x] Conversion points (`@SuppressWarnings("columnindex")` with a comment), 9 in total:
  - `ProjectedSchema`: `originalIndex(ColumnSchema)` and `originalIndex(PrimitiveNode)` (now private), where a reference-schema leaf's `columnIndex()` becomes an `@OriginalIndex`; the `IntPredicate` and `partition` result in `createAugmented`; the identity projection in `createAllColumnsProjection`, once for each index and once for the column list.
  - `FileColumnOrdinals.identity`: in the reference file each leaf sits at its reference ordinal. Like `asReference`, it holds only for file 0. Changing `fileIndex == 0` to `fileIndex >= 0` compiles; `CrossFileColumnOrderTest` catches it at run time (8 failures, 1 error of 10).
  - `RowGroupIterator.nextTouched`: `BitSet.nextSetBit` returns a plain `int`.
  - `RowGroupIterator.asReference`: the first file's schema is the reference schema.
  - `RowGroupIterator.fileOrdinalOf`: a leaf found in a file's schema has its ordinal in that file.

  Unboxing an element of a `List<@OriginalIndex Integer>` keeps the qualifier, so that suppression is gone. Boxing into an unqualified `Integer` loses it.
- [x] Stub limits. `-AmergeStubsWithSource` applies stub annotations to classes compiled from source. A stub on the top-level record `RowGroup` works, and so does one on a class nested in an interface. A stub on a record nested in another type, such as `SchemaNode.PrimitiveNode`, does not apply in CF 4.2.3; it is specific to nested records. Hence the `originalIndex(…)` conversion points.
- [x] Canaries, each a planted mix-up that the checker must reject:
  - #903: reference ordinals at `rowGroup.columns().get`, `workItem.fileSchema().getColumn` and `forColumn` in `computeFetchPlans`. 3 errors, `found: @OriginalIndex, required: @FileOrdinal`.
  - Mapping returns `refColumn.columnIndex()`: `[return]` error. Mapping returns `originalIndex`: `[return]` error. Lookup in `referenceSchema` instead of the file's schema: `[indexedby.handover]` error.
  - Counter `p` (projected) picks a chunk in `masksApplicableForRowGroup`: `[subscript.space]` error.
  - `workItem.fileSchema().getColumns().get(originalIndex)` in `computeFetchPlans`: `[subscript.space]` error.
  - `ProjectedSchema`: `schema.getColumns().get(i)` in `createAugmented` and `originalColumns.get(i)` in `create`, each with the projected counter: `[subscript.space]` error.
- [x] `ColumnIndexCheckerTest` compiles small sources in-process and pins the full messages: 27 tests, covering subscripts, `List.set`, arguments, handovers (alias, argument, field, return, unmarked parameter of a checked method), copies and views (`clone`, `Arrays.copyOf`, copy constructor, `getColumns()`), conditionals, casts, an invalid `@IndexedBy` at its declaration, varargs, counters (marked, unmarked, parameter, field, `i++`), named constants, and literals.
- [x] Maven wiring: the `column-index-check` profile of `core`. The checker module holds the qualifiers too, because the Checker Framework loads qualifier classes from the processor path; core depends on it with `provided` scope. `./mvnw verify -DskipITs` passes in every module with no "Cannot find annotation method" warning, under the default lint. A consumer compiling against core with `-Xlint:all` and without the checker jar gets `[classfile]` warnings for `@IndexedBy` on internal members, and `-Werror` then fails.
- [x] `-Pindex-check` and `-Pcolumn-index-check` are exclusive. Both configure `default-compile`, and with both active a planted index error in `DictionaryParser` compiled cleanly: the Index Checker did not run. Running both in one compilation would need one prefixed `onlyDefs` per checker: each subchecker of the Index Checker reads only options prefixed with its own name. On a one-line probe, `-AIndexChecker_onlyDefs=^Nothing$` alone left `array.access.unsafe.low` from the `LowerBoundChecker`; adding `-ALowerBoundChecker_onlyDefs=^Nothing$` removed it. An enforcer rule stops a build that activates both profiles instead.
- [ ] Not in the set yet: `FlatRowReader`, `NestedRowReader`, `TopLevelFieldMap`, `ShredLevel`, `SelectionEngine`, `ColumnReaders`. Blockers: `projectedSchema::toProjectedIndex` passed as an `IntUnaryOperator` (`BatchFilterCompiler`, `RecordFilterCompiler`, `SelectionEngine:111`) needs a Hardwood functional interface or a conversion point; `FlatRowReader.getFieldName(int)` is a public-API boundary that needs a conversion point.
- [ ] Not in the set yet, the filter path, which is the other half of #903: `PageFilterEvaluator:94` and `RowGroupFilterEvaluator:296/303` call `forColumn(columnIndex)`, and `MinMaxStats:160` calls `readable(ResolvedPredicate.leafColumnIndex(leaf))`. Their indices come from `ResolvedPredicate` leaves, which carry no qualifier, so a caller handing in `filterPredicate` (reference ordinals) instead of `columnOrdinals().filter()` (file ordinals) is not caught.
- [ ] Field index vs leaf-column index (#525) has no qualifier yet.
- [ ] Not checked, listed in the design's Limits: positions reached without an `int` argument (`stream().skip`, `ListIterator.nextIndex`, `chunks::get`), `BitSet` and `Map` containers, and a container returned from a lambda or method reference.
