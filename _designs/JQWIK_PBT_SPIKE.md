<!--

     SPDX-License-Identifier: CC-BY-SA-4.0

     Copyright The original authors

     Licensed under the Creative Commons Attribution-ShareAlike 4.0 International License;
     you may not use this file except in compliance with the License.
     You may obtain a copy of the License at https://creativecommons.org/licenses/by-sa/4.0/

-->
# jqwik property-based testing spike

**Status:** Evidence incomplete; do not adopt whole-reader PBT yet

## Question

Should Hardwood incorporate jqwik property-based testing into its correctness workflow?

## Scope tested

Branch: `spike/pbt`.

The spike added jqwik `1.9.3` through `test-bom` and added properties for two production areas. Four properties generate arbitrary 32-bit integer ordinals outside the valid domains for physical type, page type, encoding and converted type. One property generates RLE/bit-packing streams across bit widths 0 through 32 and variable array lengths, then compares encoder output decoded by the independent decoder with the generated values.

The enum properties run 100 tries each. The RLE property runs 200 tries.

## Evidence

| Production areas | 2 |
| Property methods | 5 |
| Generated cases | 600 |
| Property failures | 0 |
| Full core tests | 2,655 |
| Full core failures | 0 |
| Full core skipped | 1 |
| Production defects found | 0 |
| Generated Parquet files | 0 |
| Reader round-trip properties | 0 |
| Metamorphic relations | 0 |
| Observed shrinking of a reader failure | 0 |

The RLE property is stronger than the original enum-only test. It exercises a specification-sensitive byte encoding across every legal bit width, variable lengths, zero-width values, full-width values, and generated RLE or bit-packed output. Existing example tests cover many hand-selected RLE cases; this property adds randomized combinations and jqwik shrinking. No seeded defect has yet produced a minimized counterexample.

## Tradeoffs

### Benefits demonstrated
- Test dependency integration is straightforward.
- A compact generator covers negative and above-range integer boundaries better than a few hand-written examples.
- A generated codec property covers all legal bit widths and variable stream lengths.
- jqwik owns generated-array shrinking and reports deterministic property failures.

### Benefits not demonstrated

- Shrinking a failing schema, file, record or page to a diagnostic case.
- Finding a defect that existing example tests miss.
- Generating nested LIST, MAP and repeated schemas.
- Generating null patterns, encodings, codecs, row groups or page boundaries.
- Using round-trip or metamorphic relations as an oracle.

### Costs not yet measured

- Generator implementation and maintenance cost.
- Runtime and case-count tradeoffs for file-level properties.
- Failure reproduction and artifact retention.
- Whether generated inputs reach enough reader paths to justify the cost.

## Test redundancy

No existing test was removed. The spike adds one enum property class and one RLE property class. The existing example tests remain because they document named edge cases and provide readable failure messages. This spike did not compute kill-set subsumption, so it cannot claim that any existing test is redundant.

## Decision

Do not incorporate whole-reader PBT based on this spike. The spike provides useful codec-property evidence, but it does not measure generated-file reader coverage. Adopt jqwik selectively for pure codec or finite-domain properties and run a separate file-generation experiment before adopting whole-reader PBT.

The next experiment must generate a valid, shrinkable schema and record domain, write a real Parquet file, and assert a specified oracle. It must report:

- generated cases and execution time;
- shrunk counterexample size when a seeded defect is present;
- distinct reader paths reached;
- failures found beyond existing tests;
- retained reproducible fixtures;
- build and maintenance cost.

Adoption requires at least one property that exercises a specification-sensitive reader or writer path and produces a useful minimized counterexample, or strong coverage of a declared finite domain that existing tests do not cover.
