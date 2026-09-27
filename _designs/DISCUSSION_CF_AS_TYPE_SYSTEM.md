# Proposal: the Checker Framework as a narrow type system for index arithmetic

> Status: proposal for discussion, not yet adopted. Evidence: spike #1283,
> branch `1283-cf-index-value-spike`, findings in
> `_designs/CHECKER_FRAMEWORK_SPIKE.md`.

## Premise

Runtime checks stay where untrusted bytes enter the library: non-internal
classes, the Thrift decoder, page-header parsing, anything whose input is the
file rather than another internal component. Those checks are boundary
validation and must survive.

Checks *inside* internal classes should go away. In their place, the Checker
Framework acts as a type system with one narrow job — index arithmetic
validation — and proves those internal accesses at compile time. An internal
access the compiler accepts is proven safe; the runtime exception class it
replaces stops being expressible on paths the type system covers.

Scope discipline: index arithmetic only. Not Nullness, not Optional, not
regex, not i18n. The moment the scope widens, the annotation cost and the
compile cost stop being worth it.

## What the spike showed — what works

- **Guards become proofs.** After an inline `d < 0 || d >= dict.length` check,
  the Index Checker proves the `dict[d]` access with **zero annotations**
 (all five `applyDictionary` overloads). The check we already wanted for
  correctness doubles as the thing the dataflow refines on.
- **The annotated JDK comes free.** `Math.min`/`max` (`@PolyUpperBound`),
  `Math.toIntExact`, `floorDiv`, and `Buffer.position()/limit()/remaining()`
  (`@NonNegative`) carry annotations inside checker.jar. Internal code gets
  proven without us annotating the JDK.
- **The subchecker bundle is the right default.** `IndexChecker` brings Upper
  Bound, Lower Bound, SameLen, Value, LessThan and friends as one unit;
  MinLen-style facts arrive via `@ArrayLenRange` from the Value checker.
- **It coexists with Error Prone.** One javac invocation, `-Xplugin` for EP and
  a named processor for CF, identical wall clock, both diagnostics present.
  No conflict, no wiring changes needed. One caveat: when EP reports an
  *error* in the same javac run, the Checker Framework's warnings are not
  printed at all, so a combined CI job shows no CF findings on any build that
  also fails an EP check.
- **Warnings-only mode makes adoption incremental.** `-Awarns` turns every
  finding into a warning, so the profile can run in CI as an instrument long
  before anyone gates on it.

## What the spike showed — what doesn't work

- **Dataflow is intra-method.** Refinement dies at method boundaries. Anything
  cross-method needs the contract on the signature — which is the point of the
  premise, but it means adoption is per-class and per-signature, never free.
- **Element-wise array validity is inexpressible.** "Every element of this
  `int[]` is an index into `dict`" cannot be stated in the current qualifier
  set. The null-definition-levels path needs a batch check before the SIMD
  dispatch instead of a pure annotation. This is the main expressiveness gap
  for the dictionary path.
- **Sequences are arrays and Strings only.** ByteBuffer and MemorySegment are
  outside the type system; no annotated `ByteBuffer.java` exists in the
  embedded JDK. Thrift/ByteBuffer paths keep runtime validation — which the
  premise already accepts.
- **Known crash:** `BugInCF` on array-typed pattern binding variables
  (`values instanceof int[] a`), no handler for `ElementKind.BINDING_VARIABLE`
  in `ElementAnnotationApplier`. Method-level `-AskipDefs` does not gate it;
  class-level does. Three classes skipped today; needs an upstream report.
- **Cost.** Core module compile goes 24 s → 188–193 s (8–14×), forked javac,
  nine `--add-exports` flags. Opt-in profile only; no one pays it who does not
  ask for it.
- **Baseline is large and front-loaded elsewhere.** 2,812 warnings on core;
  the reader/batch layer outnumbers the decode layer 2:1. The decode layer
  (small, self-contained) is the easy win; the reader layer is where most of
  the remaining value — and most of the annotation work — lives.

## Which class of problems gets eliminated

With the premise in place, this class disappears from internal code:

> "An internal component throws `ArrayIndexOutOfBoundsException`,
> `NegativeArraySizeException`, or an off-by-one silent misread because index
> arithmetic on already-validated values was wrong."

Every internal array access on a typed path is compiler-proven. The
`ParquetReadException` family survives only at the boundary, where it belongs.
Today's guards in `RleBitPackingHybridDecoder` and the DELTA decoders are the
seed of this: under the premise, the *internal* half of each guard (the part
re-checking values an upstream component produced) would be replaced by typed
signatures, while the boundary parse that reads the value out of the page
buffer keeps its check and produces a typed value.

## Compiler-enforced docs

`@Positive int valueCount` is better documentation than "valueCount should be
positive", because it cannot lie:

- Prose drifts; annotations are re-checked at every call site, on every
  compile, forever.
- Call sites become self-evident: a caller passing an untyped `int` to a
  `@NonNegative` parameter is a visible, warnable event — the reviewer does
  not have to reconstruct the invariant from javadoc.
- The same property helps LLM agents: a qualifier on a signature is
  machine-readable context that cannot be hallucinated away or skimmed past.
  "What can I pass here?" has a checkable answer.

This is arguably the biggest near-term win: it costs annotations only on
signatures we would have documented anyway, and it makes drift impossible
rather than unlikely.

## Open question: would custom types eliminate more checks?

Open question — needs an experiment, though the parallel spike
(`claude/fervent-ramanujan-ucff0m`) already answered part of it: a custom
column-index checker with 42 unit tests and canary files reproducing the
#903 bug class builds and runs under the same wiring (see
`_designs/INDEX_CHECKER.md` on that branch). Candidates from the codebase:

- `@WithinPage` — an offset/length proven to lie inside the current page
  buffer. Would replace the manual `pos + length` EOF checks with a typed
  value produced once at the boundary.
- `@DictionaryIndex("dict")`-style pairing — today inexpressible for whole
  arrays (see gap above), but usable on scalar values.
- `@ValidatedPrefix` / typed DELTA prefix lengths.

The hypothesis: each custom type converts one family of runtime checks into
boundary-only checks and makes signatures shorter to read for humans and
agents. The risk: custom qualifiers multiply annotation vocabulary, and every
new type needs its own transfer rules and doc. The spike's verdict names the
pilot classes to test this on (`ProjectedSchema`, `ColumnBatch`,
`RecordShredder`, `NestedBatchIndex`) — that experiment is the follow-up this
discussion should gate.

## Proposed follow-ups

Two work items are already scoped by the spike verdict and feed this
discussion directly:

1. **Error Prone baseline + a custom `NoUnsafeIntegralNarrowing` BugChecker**
   (pure EP, cheapest win). Establishes the EP warning baseline on core and
   adds one syntactic check for unsafe integral narrowing (the `long` → `int`
   truncations that feed index arithmetic). No Checker Framework involved;
   runs in the existing qa profile; a same-week deliverable. It also cleans
   the noise floor before any CF adoption, so the CF pilot's warnings are
   readable.
2. **CF pilots on the reader/batch layer: `ProjectedSchema`, `ColumnBatch`,
   `RecordShredder`, `NestedBatchIndex`.** These four are where the 2:1
   warning concentration sits and where the custom-qualifier hypothesis
   (section above) gets tested. The experiment: annotate the four classes,
   count annotations added, count runtime checks that become provable, and
   record which checks could then be deleted. Output is the data this
   discussion needs to answer questions 1 and 4.

Sequencing: (1) first — it is independent, cheap, and shrinks the baseline;
(2) second — its result decides whether the premise graduates from decode
layer to reader layer.

## Experiment 1 — `NoUnsafeIntegralNarrowing` + Error Prone baseline (done, 2026-09-27)

The check is implemented in `error-prone-checks`
(`NoUnsafeIntegralNarrowing.java`, 13 unit tests): a cast out of `long` into
`int`/`short`/`char`/`byte` is rejected unless the operand is a compile-time
constant that fits (literals, constant fields, and folded expressions), with
`@SuppressWarnings` as the documented escape. It measured 99 hits over a clean
`core` compile; the checks run in the default build at WARN only while their
escapes are missing — the eleven WARN flags live in the opt-in
`error-prone-warnings` profile (qa keeps `NoVar`/`NoLegacyJavadoc` at ERROR),
and a check promotes once its escape hatches exist:

| Check | Hits |
| --- | --- |
| `NoUnsafeIntegralNarrowing` | 99 |
| `FutureReturnValueIgnored` | 6 |
| `IntLongMath` | 1 |
| `ConstantOverflow` | 1 |
| `InterruptedExceptionSwallowed` | 1 |
| `BadShiftAmount`, `ArrayEquals`, `CollectionIncompatibleType`, `MissingCasesInEnumSwitch`, `ComparisonOutOfRange`, `ReturnValueIgnored` | 0 |

The six zero-hit checks confirm the note's claim that the codebase is already
disciplined on those classes. The 99 narrowing hits were classified by reading
every hit site:

- **~60 — masked bit-slicing** (`(int) (bits & mask)` in the unpack loops,
  `(byte) accumulator` byte-stream slicing): truncation *is* the intent and the
  mask bounds the value. Safe today, but they are the noise floor: v1 flags
  them because a cast alone proves nothing.
- **~8 — `Math.min`/`Math.max` clamps** (`(int) Math.min(intBound, longValue)`,
  buffer-growth clamped to `Integer.MAX_VALUE`): bounded by an int argument.
  **The Checker Framework's Value checker proves these for free** — `Math.min`
  carries `@PolyUpperBound` in the annotated JDK. This is the one place where
  EP flags the risk and CF discharges the proof; the strongest single argument
  in this experiment for running both tools.
- **~10 — file-controlled headers** (`(int) (header >> 1)` RLE repeat counts in
  `RleBitPackingHybridDecoder`, `PageRecordCounter`, `FixedSizeListDetector`;
  `(int) getValueCount(header)`; thrift zigzag and field ids): a corrupt file
  controls these longs, and truncation can turn a huge count into a negative
  one downstream. **Genuine review findings, not noise** — queued for
  `Math.toIntExact` / range-check fixes.
- **~8 — bounded by construction** (`floorMod(...)` × unit, CRC32 low word,
  hash mixing low-32): safe, but the proof is cross-term arithmetic no
  syntactic check can express; these are the documented-suppression cases.
- **~5 — unproven domain conversions**: `(int) (bitPos >>> 3)` byte offsets in
  `DeltaBinaryPackedDecoder`, whose own comment says page cursors reach 2^34,
  making the truncation-to-int cursor a real edge case. A sixth site, the
  `(int) epochDay` in the INT32 date conversion, is a false positive: the
  conversion already range-checks and throws before the cast, and EP cannot
  see the check. It is now the documented suppression example
  (`PhysicalValueConverter.dateToInt`, suppressed with the bound stated).

Three conclusions:

1. The check is viable but v1 must gain two escapes before ERROR severity: a
   mask-fit escape (`x & mask` where the mask fits the target) and a
   clamp escape (`Math.min`/`Math.max` with an int operand). That removes
   roughly 70% of the noise while keeping every file-controlled and
   unproven-conversion finding.
2. Even at v1 the check paid for itself: two genuine defect classes found
   (file-controlled header truncations, the 2^34 cursor edge), plus one
   documented false positive that became the suppression example.
3. The clamp class is the concrete EP↔CF bridge: EP finds the cast, CF's
   Value checker proves the bound once the value is typed — which is exactly
   the boundary-annotation model this doc proposes.

## Experiment 2 — pilot classes annotated (done, 2026-09-27)

The four pilot classes are annotated (~58
qualifier annotations, no checker logic changed):

- `NestedBatchIndex` — the eight outer arrays are one
  `@SameLen("valueCounts")` family; every accessor takes
  `@IndexFor("valueCounts") int projectedCol`; the `-1` sentinel accessor takes
  `@IndexOrLow` and its `projectedCol < 0` guard refines it. Anchored on
  `valueCounts` because `columnSchemas` is legitimately null in one call path —
  the annotation forced that contract into the open, where the old code
  silently relied on it.
- `ColumnBatch` — `checkedIndex` now returns `@IndexFor("sources") int`; the
  whole private chain (store, requireAllNull, validate*, describe) requires
  that type. Zero new runtime checks: the existing check became the producer
  of the typed value. `ranges`/`validities` joined the SameLen family.
- `RecordShredder` — per-column arrays form a `@SameLen("layers")` family;
  `bind`'s arrays are `@SameLen` parameters with one boundary length check;
  `shred`/`leafRange`/`phantomLayers` take `@IndexFor("layers")`.
- `ProjectedSchema` — the mapping invariant is expressed: elements of
  `projectedToOriginal` are `@IndexFor("originalToProjected")`, elements of
  `originalToProjected` are `@IndexOrLow("projectedToOriginal")`, and the
  accessors carry those types — so using `toProjectedIndex(...)` as an index
  without a `>= 0` refinement is now a compile-time warning.

Three boundary checks were *added* (NestedBatchIndex schema count, ColumnBatch
constructor ranges count, RecordShredder bind lengths) — each closing an
ArrayIndexOutOfBounds path that today surfaces at access time. The premise's
direction held: boundary gains checks, internals gain types. `checker-qual`
moved to a project-level `provided`+`optional` dependency so annotations
compile without the profile.

Clean `core` compile under the profile, before → after (Maven-format
`[WARNING] file:[line,col] [key]` count). Counting note: the first three
experiment runs predate the `error-prone-warnings` profile split, so their
totals include the 116 Error Prone WARN hits the qa profile emitted in the
same compile; the CF-only figures below subtract them.

| File | Before | After |
| --- | --- | --- |
| NestedBatchIndex | 67 | 44 |
| ColumnBatch | 16 | 6 |
| RecordShredder | 53 | 48 |
| ProjectedSchema | 21 | 35 |
| **Pilot total** | **157** | **133** |
| PqMapImpl | 51 | 128 |
| PqListImpl | 15 | 78 |
| PqStructImpl | 24 | 63 |
| NestedBatchDataView | 161 | 192 |
| VariantShredReassembler | 68 | 90 |
| Pq{Int,Long,Double}ListImpl | 21 each | 33 each |
| **Whole module (CF only)** | **2,812** | **3,074** |

Findings:

1. **The SameLen family works and is cheap.** Eight arrays tied to one anchor
   with one constructor check took NestedBatchIndex down 23 warnings; every
   accessor that indexes a projected column is now proven.
2. **Proof obligations are conserved, not destroyed.** The pilots' −24 became
   +~260 in their callers — dominated by the flyweight cursor layer
   (PqListImpl/PqMapImpl/PqStructImpl, +179) that consumes the accessors. This
   is the precise cost of the stop condition this doc records: the outer
   projected-column dimension typed for free, but the inner record/level/item
   dimensions now demand typing in the cursors, which is where the remaining
   work is. The reader/batch layer's 2:1 warning concentration is now
   explained concretely: it is one untyped dependency chain.
3. **A connected subgraph must be typed together.** Annotating boundary
   classes alone raises the total warning count; the count falls only once the
   caller chain is typed too. Any adoption plan must scope by call graph, not
   by file.
4. **ProjectedSchema's mapping invariant is only partially expressible** (+14
   self): the constructor sites build the mapping arrays element-wise and the
   checker cannot prove the array→array element domain there — the same
   element-wise gap experiment 1's dictionary path hit. Declarative array
   mapping qualifiers are the missing feature class.
5. **Compile overhead unchanged** (188–193 s warm under the profile; the
   default build is untouched).

## Experiment 2b — closing the chain (done, 2026-09-27)

Experiment 2 left ~300 obligations in the cursor layer. Closing them taught
the two mechanics that matter for any adoption plan:

1. **A SameLen family must be built in one scope.** A factory that creates
   fresh arrays and passes them through constructor parameters loses the
   grouping — the checker cannot see that `new Object[n]` and `new int[n]`
   share a length. The fix: create every family array from one
   `batches.length` expression and assign straight to the fields (the
   SameLen transfer's own rule: `b = new T[a.length]` implies b is
   a-length). Family construction moved into NestedBatchIndex's
   constructor; RecordShredder and ColumnBatch derive their arrays from the
   anchor field's `.length`.
2. **The descriptor/batch boundary needs one bridge.** Schema-derived column
   indices (FieldDesc) and per-batch state are guaranteed to agree by
   construction, but nothing establishes it where they meet. A single
   `refineProjCol` check on NestedBatchIndex mints the
   `@IndexFor("valueCounts")` type; every cursor call site (PqList/Map/
   StructImpl, the typed list cursors, NestedBatchDataView,
   VariantShredReassembler) goes through it. The key-only-map `-1` sentinel
   keeps a non-throwing path — the annotation surfaced that contract too
   (a test caught the first strict version).

Whole-module profile warnings: **2,812 → 2,755** (CF only), below the
pre-annotation baseline, with the four pilot classes and their cursor layer
typed:

| File | Baseline | Chain closed |
| --- | --- | --- |
| NestedBatchIndex | 67 | 38 |
| ColumnBatch | 16 | 7 |
| RecordShredder | 53 | 40 |
| PqMapImpl | 51 | 39 |
| PqIntListImpl | 21 | 16 |
| NestedBatchDataView | 161 | 145 |
| ProjectedSchema | 21 | 35 |
| PqListImpl | 15 | 19 |
| PqStructImpl | 24 | 27 |

ProjectedSchema, PqListImpl and PqStructImpl sit slightly above baseline:
their remaining warnings are the element-wise mapping gap (constructor sites
that build mapping arrays value by value) and the inner jagged dimensions the
prior stop condition excluded. Those are the honest price of typing the
outer dimension; they are documented residuals, not regressions. Full core
suite green throughout (13,997 tests after the review round; 13,994 at chain
closure).

After the branch-review round (trusted conversion for `refineProjCol` and
`valueColumn`, element-form `ProjectedSchema` qualifiers, refine-once
hoists), the CF-only whole-module count is **2,767**: still below the
2,812 pre-pilot baseline, +12 against chain closure, of which +9 are
pre-existing `argument` warnings in `PqMapImpl` the old throwing bridge
incidentally discharged. That 2,767 is the current trend instrument.

## Costs, honestly

- 8–14× compile overhead on `core` under the profile (opt-in; default builds
  unaffected).
- Runtime cost after the review round: none on the hot path. `refineProjCol`
  is a trusted conversion (no branch); the remaining runtime work is three
  once-per-batch construction checks and one full pass over a column's
  dictionary indices before each all-present SIMD dispatch — O(batch) per
  column, not per value. The nested-read benchmarks under
  `performance-testing/` were not re-run; that is the open cost item.
- Annotation maintenance: signatures become part of the correctness story, so
  changes ripple into call sites visibly — a feature, but a real tax during
  refactors.
- Upstream fragility: the BugInCF crash shows the framework can block a build
  (in warnings-only mode, block the *instrument*, not the build).
- Expressiveness ceiling: the element-wise-array gap means the dictionary
  path keeps one batch guard regardless.

## Questions for the discussion

1. Do we accept the premise — boundary checks stay, internal index checks get
   replaced by typed signatures — as the direction?
2. Adopt the profile as an opt-in CI instrument now (trend the CF-only
   whole-module count: 2,812 before the pilots, 2,755 at chain closure;
   re-baseline each round), and let the pilot classes drive when internal
   checks start coming out?
3. Is the scope fence (index arithmetic only) right, or should the Value
   checker's constant/length facts be in scope from day one?
4. Do we want the custom-qualifier experiment (`@WithinPage` et al.) run on
   the pilot classes before deciding, or decide the direction first?
