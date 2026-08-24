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

The spike added jqwik `1.9.3` through `test-bom` and added one test class for `ThriftEnumLookup`. Four properties generated arbitrary 32-bit integer ordinals outside the valid domains for physical type, page type, encoding and converted type.

Each property ran 100 tries.

## Evidence

| Measurement | Result |
|---|---:|
| Property methods | 4 |
| Generated cases | 400 |
| Property failures | 0 |
| Full core tests | 2,654 |
| Full core failures | 0 |
| Full core skipped | 1 |
| Production defects found | 0 |
| Generated Parquet files | 0 |
| Reader round-trip properties | 0 |
| Metamorphic relations | 0 |
| Observed shrinking of a reader failure | 0 |

The result proves that jqwik `1.9.3` resolves through the test BOM, runs under the existing Surefire configuration, and can generate boundary ordinals. It does not measure the value of PBT for Hardwood's reader and writer correctness problems.

## Tradeoffs

### Benefits demonstrated

- Test dependency integration is straightforward.
- A compact generator covers negative and above-range integer boundaries better than a few hand-written examples.
- The property source is small and deterministic enough for normal test execution.

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

## Decision

Do not incorporate whole-reader PBT based on this spike. The spike is an integration result, not a correctness result. Keep jqwik available as a candidate dependency and run a second bounded experiment before adoption.

The next experiment must generate a valid, shrinkable schema and record domain, write a real Parquet file, and assert a specified oracle. It must report:

- generated cases and execution time;
- shrunk counterexample size when a seeded defect is present;
- distinct reader paths reached;
- failures found beyond existing tests;
- retained reproducible fixtures;
- build and maintenance cost.

Adoption requires at least one property that exercises a specification-sensitive reader or writer path and produces a useful minimized counterexample, or strong coverage of a declared finite domain that existing tests do not cover.
