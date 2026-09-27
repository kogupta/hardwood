# Index Checker spike: progress

Base: `main` at 9f84f5bbc2bd83956df19e6192c48f592115b01f. Branch: `claude/fervent-ramanujan-ucff0m`.
Design: [_designs/INDEX_CHECKER.md](_designs/INDEX_CHECKER.md).

## Open questions

- [ ] Issue key for commits. Issues are disabled on `kogupta/hardwood` (API returns 410), and CLAUDE.md requires every commit to start with an issue key. Commits use the `spike:` prefix of the earlier `spike/nullness` and `spike/pbt` branches. Work stays on this branch; no PR planned.

## Tooling (verified)

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

- **Dictionary `numValues`.** `@NonNegative` on `ThriftCompactReader.readNonNegativeI32()`, `DictionaryPageHeader.numValues`, `DictionaryParser.decompress` and `Dictionary.parse` clears the "array size could be negative" errors in `Dictionary.parse`. The re-check at `DictionaryParser.java:150` becomes dead.
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
  - `@NonNegative` on `readNonNegativeI32()`, the `DictionaryPageHeaderReader` local, the `DictionaryPageHeader.numValues` component, `DictionaryParser.decompress` and `Dictionary.parse`.
  - Re-check and `DictionaryParserTest.rejectsANegativeValueCount` deleted. The rejection is now pinned where it happens: `MalformedMetadataValidationTest.negativeDictionaryNumValuesRejected` asserts `DictionaryPageHeader.num_values — must be non-negative but was -1`. `BadDataHandlingTest.rejectDictheader` / `rejectArrowGH41321` still pass.
  - Canary: dropping `@NonNegative` from the record component fails the build at `DictionaryParser.java:157` (`[argument] found: int, required: @NonNegative int`).
  - Checked set: `DictionaryPageHeader`, `DictionaryPageHeaderReader`, `DictionaryParser` (0 errors). `ThriftCompactReader` and `Dictionary` carry annotations but are not in the set yet; their signatures are still checked at every call from a checked class.
- [x] `RowGroupIterator.java:249, 252, 519` `tailSkip`/`physicalSkip < 0`. Boundary: `RowReaderBuilder.skip(long)` and `tail(long)` in `ParquetFileReader`; `setTailSkip` is only called under `skip > 0`. Test constructors pass `0`.
  - `@NonNegative long` on the builder's `skip` field, `buildRowReader(…, skip)`, the two private `buildRowReader` overloads, `trackedIterator`, the `RowGroupIterator` constructors, its two fields and `setTailSkip`. Three re-checks deleted; the `tailSkip`/`physicalSkip` mutual-exclusion check stays (not an index fact). No test exercised the deleted checks.
  - The annotations add 0 errors. `ParquetFileReader` joins the checked set; its one error was a Value Checker false positive on the `switch` in `matches` (`found @BoolVal(false), required @BoolVal(true)`), suppressed with `@SuppressWarnings("value")` and a comment.
  - Canaries: deleting the public `if (skip < 0) throw` in `skip(long)` fails the build (`[assignment] found: long, required: @NonNegative long`), so the boundary check is now load-bearing for compilation. Dropping `@NonNegative` from the field fails at the `buildRowReader` call.
  - `RowGroupIterator` stays out of the checked set. Sound anyway: its parameter annotations are enforced at every call from a checked class, and `ParquetFileReader` is the only production caller. Its 15 errors are projected-column indexing (`plans[projectedColumnIndex]`, `touched.set(toOriginalIndex(…))`, arrays sized by `getProjectedColumnCount()` / `getColumnCount()`, `nextSetBit` indexes into `fileOrdinals`). Clearing them pulls in `ProjectedSchema` (15 errors) and the public `FileSchema` (4): the index-space work below, not this item.
- [ ] Trace remaining candidates: `BatchSizing:100`, `PageInfo:74`, `ByteArrayBuilder:35, 91`, `MergePlan:32`, `BoundsReadability:93`, `RowRanges:46`, `SequentialFetchPlan:152`, `ResolvedPredicate:307`, `RleBitPackingHybridEncoder:56`, `BitPacker:31`, `FileMetadataCache:140`.
- [ ] Classes on the paths above not yet in the checked set: `ThriftCompactReader` (12 errors), `Dictionary` (17), `RowGroupIterator` (15), `BatchSizing` (1). In the set: `DictionaryPageHeader`, `DictionaryPageHeaderReader`, `DictionaryParser`, `ParquetFileReader`.
- [x] `index-check` profile in `core/pom.xml`; `checker-framework.version` in the parent POM; `checker-qual` as `provided`. Run: `./mvnw -pl core -am -Pindex-check install -DskipITs` (52 s with Error Prone and unit tests, vs 63 s baseline run earlier; within noise). `.mvn/jvm.config` flags suffice; nothing extra needed for Maven.
- [ ] Full `./mvnw verify` on JDK 25 before pushing. numValues change: all modules pass except the Docker-based S3 ITs (`Could not find a valid Docker environment`; no Docker in this container). `-rf :hardwood-s3 -DskipITs` passes; core unit tests 13,890 run, 0 failures. Keep open: the S3 ITs have not run.
  Skip change: same result. Unit tests pass in every module; every failing IT is a Docker-bound S3 test (`hardwood-s3`, the `*S3CommandIT` classes in `cli`, `ParquetReaderS3CompatIT`).

## Next: index spaces

Findings so far (history read, no spike yet):

- `0625f55b` (#525) is a guard test, not a shipped bug: it pins the field-index vs leaf-column-index case before a refactor could break it.
- `6cff8f99` (#1242) fixed a real bug: a predicate on an unprojected column had no projected index and threw at row-level evaluation. Missing mapping, not an int passed across spaces.
- No other instance found in commit messages.
- Plan: qualifiers `@ProjectedIndex`, `@OriginalIndex`, `@FieldIndex`, `@LeafIndex` on plain `int`, first on `ProjectedSchema.toOriginalIndex` / `toProjectedIndex`.
- Unknowns to settle in the spike: array subscripts are not checked by the Subtyping Checker, so raw `array[idx]` mix-ups slip through; the qualifier of `idx + 1` and of loop counters; the `-1` "not projected" sentinel needs its own qualifier. Measure annotations and casts needed in `ProjectedSchema` plus one consumer (`TopLevelFieldMap` or `FlatRowReader`).

- [ ] Subtyping Checker qualifiers for original vs projected column index, field index vs leaf-column index. Evidence: `0625f55b` (#525) "confusing the two spaces there returns wrong rows (or throws) without any other signal"; #1242. Surface: `toOriginalIndex` 14 refs, `toProjectedIndex` 15, `projectedCol` 127, `projectedIndex` 89, `originalIndex` 32, `fieldIndex` 168.
