# Checker Framework Spike

**Status: Completed**

Feasibility spike for the Checker Framework's Index Checker (Constant Value
Checker included as its subchecker) over `hardwood-core`, per
[harness issue #1283](https://github.com/hardwood-hq/hardwood/issues/1283).
This document records the measured facts and the verdict.

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
   on the classpath, the run fails with
   `AnnotationBuilder: fromClass can't load class
   org.checkerframework.dataflow.qual.Deterministic`. Both the processor path
   and the classpath carry `checker`, `checker-qual` and `checker-util`
   (`annotationProcessorPaths` does not resolve transitives). In the shipped
   wiring `checker-qual` is a project-level `provided` + `optional` dependency
   of `core`, not profile-scoped: the pilot annotations must compile in the
   default build too, and `optional` keeps the jar off consumers' classpaths.
3. **Three skipped classes.** The framework crashes
   (`BugInCF` in `ElementAnnotationApplier.applyInternal`: no handler for
   `ElementKind.BINDING_VARIABLE`) when a qualifier whose expression names an
   array resolves against an array-typed pattern binding variable.
   Reproducer: `dev.hardwood.reader.ColumnReader` line 367,
   `if (!(values instanceof int[] a))`, followed by an access where the
   checker resolves a named-expression annotation against `a` — javac reports
   `error: ElementAnnotationUtil.apply: illegal argument: a [BINDING_VARIABLE]
   with type int[]` and the run dies in `BugInCF`. A minimal standalone probe
   (`if (o instanceof int[] a) return a.length;`) does not reproduce it; the
   crash needs the annotated access in the same scope, so it is recorded
   against the full module instead.
   Probed: skipping the individual methods does not gate the crashing phase
   (annotation application is not per-method-gated); skipping the classes
   does. `-AskipDefs` (anchored: `^(dev\.hardwood\.reader\.ColumnReader|...)$`,
   because the checker matches the pattern with `Matcher.find()` and an
   unanchored pattern would also skip longer matching class names) covers the
   three classes with that construct — `dev.hardwood.reader.ColumnReader`,
   `dev.hardwood.reader.SelectionEngine`,
   `dev.hardwood.internal.reader.FlyweightFormatter` — and nothing else.
   Upstream fix required; until then these classes stay runtime-checked.

`-AskipUses` excludes the four optional codec dependencies (snappy, zstd-jni,
lz4, brotli4j), which are unannotated third-party jars.

One operational trap: the profile compiles into the default `target/classes`,
so a `-Pcheckerframework` run after an unchanged plain compile recompiles
nothing and checks nothing (still a green build). Start a measuring run from
`clean`, or change a source first.

## Measurements

Wall clock for `clean compile` of `core` (`-pl core`, tests skipped where
permitted), two repetitions each on a warm build cache, plus one cold-cache
profile run:

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
neither tool interferes with the other. One caveat seen later on the sibling
spike branch: when Error Prone reports an *error* in the same javac run, the
Checker Framework's warnings are not printed at all — a combined CI job shows
no CF findings on any build that also fails an EP check.

## Warning inventory

2,825 diagnostics under `-Awarns` across `core` main sources (2,812 after the
guard stage). Later whole-module counts in
[_designs/DISCUSSION_CF_AS_TYPE_SYSTEM.md](_designs/DISCUSSION_CF_AS_TYPE_SYSTEM.md)
were taken after the Error Prone baseline landed, when the qa profile's eleven
WARN checks emitted 116 warnings in the same compile; those logs read 2,928
baseline / 2,871 chain-closed, of which the CF-only figures are 2,812 and
2,755. The eleven WARN checks now live in the opt-in `error-prone-warnings`
profile, so profile counts from that point on are CF-only.

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

The reader/batch layer (`internal/reader/*`, the discussion doc's pilot
classes) outnumbers the decode layer (`internal/encoding/*`) roughly 2:1 in
raw warning count — consistent with the discussion doc's identification of
`ProjectedSchema`/`ColumnBatch`/`RecordShredder`/`NestedBatchIndex` as the
highest-value adoption targets.

## Known limits

- **Element-wise array qualifiers are position-sensitive.** The element form
  `@IndexFor("dict") int[] indices` is accepted and proves `dict[indices[i]]`
  for field-to-field relations; the array form
  `int @IndexFor("dict") [] indices` annotates the array itself, checks
  nothing, and is silently tolerated under `-Awarns` (`anno.on.irrelevant`).
  `ProjectedSchema`'s mapping invariant uses the element form. What the
  checker still cannot do is lift element-wise writes in a constructor loop
  into the element qualifier, so the mapping-array build sites keep boundary
  checks — this, not an expressiveness wall, is the residual warning mass in
  `ProjectedSchema`.
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

Two placement decisions, each probed before it was committed:

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
   met without any qualifier, and no dependency was needed at this stage.
   (The project-level `checker-qual` dependency arrived later, with the pilot
   annotations.)

Residual warnings in `RleBitPackingHybridDecoder.java` (89) are unrelated
invariants: parallel-array length relations (`@LTLengthOf("output")` does not
imply `@LTLengthOf("defLevels")` without `@SameLen` plumbing through every
caller), count non-negativity, and the `sourceFor` padding arithmetic. Proving
those requires annotations propagating through caller contracts — a proof the
spike declines in favor of the existing runtime invariant.

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

**Adopt the Checker Framework as an opt-in CI instrument, and proceed with the
pilot classes named in the discussion doc before any wholesale gating.** The
spike's evidence:

- The guard stages delivered three controlled-exception fixes (dictionary
  indices, DELTA lengths, DELTA prefixes) for crash classes reachable from
  malformed files, and the checker then proved every guarded access — with
  **zero annotations** in all three decoder classes. Zero annotation at the
  point of proof is the character that makes adoption worth extending.
- Coexistence with Error Prone is free (identical wall clock and warning
  count in the combined run; both visible in one javac invocation).
- The cost profile is acceptable only CI-side: 8-14x compile overhead, three
  classes skipped over the upstream `BINDING_VARIABLE` crash, and a
  diagnostics inventory whose largest mass (parallel-array relations in the
  reader/batch layer, ~2:1 over the decode layer) needs `@SameLen`/
  `@IndexFor` propagation through caller contracts — the shape to decline for
  wholesale gating.

Accordingly: gate nothing wholesale yet. The follow-up sequence the verdict
names:

1. Error Prone baseline + `NoUnsafeIntegralNarrowing` check — done;
   see Experiment 1 in
   [_designs/DISCUSSION_CF_AS_TYPE_SYSTEM.md](_designs/DISCUSSION_CF_AS_TYPE_SYSTEM.md).
2. CF pilots on `ProjectedSchema`/`ColumnBatch`/`RecordShredder`/
   `NestedBatchIndex` plus their cursor layer — done; see Experiments 2 and
   2b in the discussion doc. Element-position array qualifiers work (see
   Known limits); they are part of the shipped pilot annotations.
3. Upstream the `ElementAnnotationApplier` binding-variable crash
   (typetools/checker-framework), then lift this profile's `-AskipDefs`.
   Re-test on the review round: removing the skip list fails the compile with
   the crash against `ColumnReader.java:367` (`values instanceof int[] a`,
   see profile shape item 3), so the skip is still required; the exact
   javac error line and stack are recorded in the discussion ledger.
4. A CI job running `-Pcheckerframework` warnings-only on `core`, trended
   against the CF-only baseline (2,812 here; 2,755 at chain closure —
   the discussion doc's counting note explains the difference).