# jqwik property-testing spike results

Base: main at 9a3a8588b03396227af30a173303d207567e4f19.
Branch: `spike/pbt`.

## Configuration

- jqwik `1.9.3`.
- Managed through `test-bom`.
- Core test dependency is test scope.
- Surefire remains the existing `3.5.4` configuration.

## Properties tested

The spike now covers two production areas:

### Thrift enum lookup

Four properties generate arbitrary 32-bit integer ordinals outside the valid domains for physical type, page type, encoding and converted type. Each property runs 100 tries.

### RLE/bit-packing encoder and decoder

One property generates arbitrary stream lengths and bit widths from 0 through 32. It masks generated values into the selected width, encodes them with `RleBitPackingHybridEncoder`, decodes them with `RleBitPackingHybridDecoder`, and compares the complete value stream. It runs 200 tries. jqwik owns the generated array and bit-width shrinking.

## Evidence

| Measurement | Result |
|---|---:|
| Production areas | 2 |
| Property methods | 5 |
| Generated cases | 600 |
| Property failures | 0 |
| Full core tests | 2,655 |
| Full core failures | 0 |
| Full core skipped | 1 |
| Generated Parquet files | 0 |
| Reader round-trip properties | 0 |
| Metamorphic relations | 0 |
| Production defects found | 0 |

The RLE property is materially stronger than the original enum-only test. It exercises a specification-sensitive byte encoding across every legal bit width, variable input lengths, zero-width values, full-width values, and both RLE and bit-packed output depending on generated repetition patterns. Existing example tests already cover many hand-selected RLE cases; the property adds randomized combinations and shrinking, but no seeded defect has yet demonstrated a minimized counterexample.

## Decision

jqwik is technically viable and now has evidence beyond dependency integration. Do not adopt whole-reader PBT yet. The spike still generates no Parquet files, nested schemas, records, null patterns, codecs or page layouts, and it found no defect beyond existing example coverage.

Adopt jqwik selectively for pure codec/level properties where an exact oracle is cheap. Run a separate file-generation experiment before adopting PBT for the reader/writer correctness strategy. That experiment must measure generated files, paths reached, shrinking of a seeded failure, retained fixtures, runtime and maintenance cost.
