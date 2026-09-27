# Discussion: the Checker Framework as a narrow type system for index arithmetic

> Draft issue — not yet filed to a tracker (fork has no issue permission).
> Evidence: spike #1283, branch `1283-cf-index-value-spike`, findings in
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
  No conflict, no wiring changes needed.
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

Not yet answered — needs an experiment. Candidates from the codebase:

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

## Costs, honestly

- 8–14× compile overhead on `core` under the profile (opt-in; default builds
  unaffected).
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
2. Adopt the profile as an opt-in CI instrument now (trend the 2,812 baseline),
   and let the pilot classes drive when internal checks start coming out?
3. Is the scope fence (index arithmetic only) right, or should the Value
   checker's constant/length facts be in scope from day one?
4. Do we want the custom-qualifier experiment (`@WithinPage` et al.) run on
   the pilot classes before deciding, or decide the direction first?
