# jqwik property-testing spike results

Base: main at 9a3a8588b03396227af30a173303d207567e4f19.
Branch: `spike/pbt`.

## Configuration

- jqwik `1.9.3`.
- Managed through `test-bom`.
- Core test dependency is test scope.
- Surefire remains the existing `3.5.4` configuration.

## Property

`ThriftEnumLookupPropertyTest` uses jqwik generators for arbitrary invalid integer ordinals. Four properties cover the parser enum boundary:

- physical type rejects every out-of-range ordinal;
- page type returns `UNKNOWN` for every out-of-range ordinal;
- encoding returns `UNKNOWN` for every out-of-range ordinal;
- converted type returns absence for every out-of-range ordinal.

Each property runs 100 tries. The generator filters arbitrary 32-bit integers to values outside the valid ordinal domain, so it exercises both negative and above-range values rather than only hand-picked boundaries.

## Result

- Property methods: 4.
- Generated tries: 400.
- Property test result: 4 methods passed.
- Full core verification: 2,654 tests, 0 failures, 1 skipped.

## Decision

jqwik integrates cleanly through the existing test BOM and Surefire setup. The property is a useful boundary test, but it is not yet the file/schema generator described by the correctness strategy. The next PBT stage should generate valid schemas, records, and files with shrinking, then use round-trip or metamorphic relations as the oracle. Do not treat this four-property spike as evidence for whole-reader PBT coverage.
