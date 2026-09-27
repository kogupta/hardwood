# Checker Framework Spike

**Status: Completed**

Feasibility spike for the Checker Framework's Index Checker (Constant Value
Checker included as its subchecker) over `hardwood-core`, per
[harness issue #1283](https://github.com/hardwood-hq/hardwood/issues/1283).
This document records the measured facts; the adopt/stop verdict lands here
when the spike completes.

## Profile shape

Opt-in Maven profile `checkerframework` in `core/pom.xml`, manual activation
via `-Pcheckerframework`. Bound to the `default-compile` execution only; test
sources and the java22 multi-release sources are never checked. The Index
Checker runs with `-Awarns` (warnings, not errors) and `-Xmaxwarns 100000`.

Three wiring facts deviate from or sharpen the plan, each probed in isolation
with a standalone javac before being committed:

1. **Forked compiler.** The manual's requirement: an unforked javac silently
   ignores the `-J` VM-access flags. `<fork>true</fork>` is the primary
   design; a forked javac is also per-execution, which is what keeps the
   profile off the other executions.
2. **checker-qual on the compile classpath.** The processor resolves its
   annotated-JDK annotations through `Elements.getTypeElement`, which reads
   the compilation classpath — the processor path alone does not feed that
   lookup on current JDKs. Without `org.checkerframework:checker-qual`
   (`provided`, profile-scoped) on the classpath, the run fails with
   `AnnotationBuilder: fromClass can't load class
   org.checkerframework.dataflow.qual.Deterministic`. Both the processor path
   and the classpath carry `checker`, `checker-qual` and `checker-util`
   (`annotationProcessorPaths` does not resolve transitives).
3. **Three skipped classes.** The framework crashes
   (`BugInCF` in `ElementAnnotationApplier`: no handler for
   `ElementKind.BINDING_VARIABLE`) when applying annotations to array-typed
   pattern binding variables, e.g. `values instanceof int[] a` in
   `ColumnReader.getInts()`. Probed: skipping the individual methods does not
   gate the crashing phase (annotation application is not per-method-gated);
   skipping the classes does. `-AskipDefs` covers the three classes with that
   construct — `dev.hardwood.reader.ColumnReader`,
   `dev.hardwood.reader.SelectionEngine`,
   `dev.hardwood.internal.reader.FlyweightFormatter` — and nothing else.
   Upstream fix required; until then these classes stay runtime-checked.

`-AskipUses` excludes the four optional codec dependencies (snappy, zstd-jni,
lz4, brotli4j), which are unannotated third-party jars.

## Measurements

Wall clock for `clean compile` of `core` (`-pl core`, tests skipped where
permitted), two repetitions each on a warm build cache, plus one cold-cache
profile run from the first wiring attempt:

| Configuration | Rep 1 | Rep 2 |
| --- | --- | --- |
| default (`qa` profile: Error Prone active) | 24 s | 24 s |
| `-Dquick` (no static analysis) | 15 s | 12 s |
| `-Dquick -Pcheckerframework` | 188 s | 193 s |
| `-Pcheckerframework` (Error Prone + Checker Framework) | 193 s | 187 s |

Cold-cache profile run: 296 s. Overhead against `-Dquick` is roughly **14x**
warm; against the default build roughly **8x**. Error Prone coexistence costs
nothing measurable: the combined run is indistinguishable from the
Checker-Framework-only run, and the warning count is identical (2,825), so
neither tool interferes with the other.

## Warning inventory

2,825 diagnostics under `-Awarns` across `core` main sources:

| Key | Count |
| --- | --- |
| `array.access.unsafe.high` | 1,058 |
| `array.access.unsafe.low` | 925 |
| `argument` | 339 |
| `array.access.unsafe.high.range` | 262 |
| `array.length.negative` | 187 |
| `array.access.unsafe.high.constant` | 49 |
| `override.return` | 3 |
| `switch.expression` | 2 |

Sample messages, verbatim:

- `RowGroupIterator.java:[569,25] [array.access.unsafe.high] Potentially
  unsafe array access: the index could be larger than the array's bound`
- `RowGroupIterator.java:[569,25] [array.access.unsafe.low] Potentially unsafe
  array access: the index could be negative.`
- `FileMetaDataReader.java:[45,41] [argument] incompatible argument for
  parameter arg0 of BitSet.get.`

Highest-count files: `internal/reader/FlatRowReader` (179),
`internal/reader/NestedBatchDataView` (161),
`internal/encoding/simd/ScalarOperations` (126),
`internal/reader/NestedColumnWorker` (108),
`internal/encoding/RleBitPackingHybridDecoder` (100 — 51 `high`, 38 `low`,
6 `argument`, 4 `high.range`, 1 `length.negative`),
`internal/reader/NestedLevelComputer` (76), `internal/reader/BinaryBatchValues`
(75), `internal/reader/VariantShredReassembler` (68).

The reader/batch layer (`internal/reader/*`, the prior note's pilot classes)
outnumbers the decode layer (`internal/encoding/*`) roughly 2:1 in raw
warning count — consistent with the prior note's identification of
`ProjectedSchema`/`ColumnBatch`/`RecordShredder`/`NestedBatchIndex` as the
highest-value adoption targets.

## Known limits

- **Element-wise array validity is inexpressible.** No qualifier states "every
  element of `int[] indices` is a valid index for `dict`". The dictionary-index
  guard therefore lives at runtime, with the comparison inline at each access
  site (intra-method dataflow refinement), not in a batch-level helper.
- **ByteBuffer and MemorySegment are unchecked.** Neither appears in the
  annotated JDK embedded in `checker-4.2.3.jar`; the Thrift reader, the
  ByteBuffer-viewed RLE reads, and the FFM code stay runtime-validated.
- **Array-typed binding variables crash the framework** (see profile shape,
  item 3).
- **No arithmetic-overflow checking in the Index type system** (manual's own
  caveat); the Value Checker's range arithmetic covers what it can infer.

## Guard stage results

### Dictionary indices (RleBitPackingHybridDecoder)

Out-of-range dictionary indices now throw `ParquetReadException`
("Invalid dictionary index N at position P: dictionary has L entries") at the
point of use, pinned by `RleBitPackingHybridDecoderDictionaryBoundsTest`
(six cases: null and non-null definition levels, the `index == length`
boundary, byte-array dictionaries, first-failing-position reporting, and a
happy-path control). All dictionary accesses in the decoder are checker-proven
after the change: zero `[...dict...]`-related warnings remain in the file
(100 → 89 warnings, and every one of the 89 is a different invariant — see
below).

Two deviations from the reviewed plan, both discovered during implementation:

1. **Guard placement.** The plan put comparisons inside the
   `simd/ScalarOperations` apply loops. `VectorOperations` — the java22
   multi-release twin with its own scalar fallbacks and vectorized gathers —
   feeds from the same dispatch and was not covered by that wording; guarding
   inside it would duplicate the check into vector API code. Instead the
   non-null branches check inline at each access (intra-method, so the
   checker proves them), and the `defLevels == null` branches validate the
   batch once before the SIMD dispatch — one choke point covering every
   implementation behind `SimdOperations`, with no branches added to the hot
   vector loops.
2. **No annotations, no checker-qual dependency.** The guards alone drove
   every dictionary-access warning out, so the target of zero annotations was
   met without any qualifier, and the planned `checker-qual` `provided`
   dependency was not needed.

Residual warnings in `RleBitPackingHybridDecoder.java` (89) are unrelated
invariants: parallel-array length relations (`@LTLengthOf("output")` does not
imply `@LTLengthOf("defLevels")` without `@SameLen` plumbing through every
caller), count non-negativity, and the `sourceFor` padding arithmetic. Proving
those requires annotations propagating through caller contracts — the
"difficult static proof" the prior adoption note says to decline in favor of
the existing runtime invariant.

### DELTA byte-array lengths and prefixes (DeltaLengthByteArrayDecoder, DeltaByteArrayDecoder)

DELTA_LENGTH_BYTE_ARRAY now rejects negative lengths ("Negative byte array
length: N") before the zero-length fast path, and DELTA_BYTE_ARRAY rejects
prefix lengths below zero or beyond the previous value's length ("Invalid
prefix length P at value index V: previous value has L bytes") before the
suffix is consumed — closing the gap where a malformed prefix produced
`NegativeArraySizeException` or silently wrong reconstruction. Both guards
live in the single consumption points (`readValue()`), so the batched
`readByteArrays` paths inherit them. Pinned by
`DeltaByteArrayDecoderBoundsTest` (five cases: negative length, over-long
prefix, negative prefix, prefix against the empty first value, and a
well-formed control; streams composed from the real encoders, which cannot
produce these values — the decoders must).

The two guard comparisons also proved two previously-warned accesses (total
diagnostics 2,814 → 2,812). The delta decoders' 8 residual warnings predate
the spike: `prefixLengths[currentIndex]` bounds against `totalValues`,
`definitionLevels[i]` vs `output.length`, and count negativity in
`initialize` — again parallel-array relations outside the guarded invariants.
No annotations were needed in either file.

## Full-build verification

`mvn verify` on the default build (Error Prone active, no Checker Framework)
passes every JVM unit test (1,042, 0 failures) and the parquet-java
compatibility runner. The only failures are the S3 integration tests, which
error before running: Testcontainers reports "Could not find a valid Docker
environment" and this machine has no Docker daemon at all
(`docker.service` not found). The failure is environmental and predates the
spike's changes; the touched code (`internal/encoding`) is covered by the
unit suites and the compatibility runner above. A environment with Docker
should re-run `mvn verify` before merge.

## Verdict

**Adopt the Checker Framework as an opt-in CI instrument, and proceed with
the prior adoption note's pilot classes before any wholesale gating.** The
spike's evidence:

- The guard stages delivered three controlled-exception fixes (dictionary
  indices, DELTA lengths, DELTA prefixes) for crash classes reachable from
  malformed files, and the checker then proved every guarded access — with
  **zero annotations** in all three decoder classes. That is exactly the
  annotation-locality and idiomatic-simplicity character the prior note's
  exit criteria (§18C/D, §20) demand.
- Coexistence with Error Prone is free (identical wall clock and warning
  count in the combined run; both visible in one javac invocation).
- The cost profile is acceptable only CI-side: 8-14x compile overhead, three
  classes skipped over the upstream `BINDING_VARIABLE` crash, and a
  diagnostics inventory where the largest mass (parallel-array relations in
  the reader/batch layer, ~2:1 over the decode layer) is inexpressible
  without `@SameLen`/`@IndexFor` propagation through caller contracts — the
  shape §20 says to decline.

Accordingly: gate nothing wholesale yet. The follow-up sequence the verdict
names (each its own issue, out of scope here):

1. Prior note's Error Prone baseline + `NoUnsafeIntegralNarrowing` check
   (its §14-15) — cheap, immediate.
2. Prior note's CF pilots on `ProjectedSchema`/`ColumnBatch`/
   `RecordShredder`/`NestedBatchIndex` (its §5-8), with method-level
   `@IndexFor`/`@IndexOrLow` contracts at access sites — the element-wise
   array annotation its §5 sketches is inexpressible and should not be
   planned around.
3. Upstream the `ElementAnnotationApplier` binding-variable crash
   (typetools/checker-framework), then lift this profile's `-AskipDefs`.
4. A CI job running `-Pcheckerframework` warnings-only on `core`, trended
   against the 2,812 baseline recorded here.