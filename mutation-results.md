# PIT mutation spike results

Base: main at 9a3a8588b03396227af30a173303d207567e4f19.
Branch: `spike/mutation`.

## Configuration

- PIT `1.25.9`.
- PIT JUnit 5 plugin `1.2.2`.
- Opt-in Maven profile: `mutation-test`.
- Target: `dev.hardwood.internal.thrift.*`.
- Tests: `dev.hardwood.internal.thrift.*`.
- The profile is not part of normal `verify`.

PIT 1.19.1 was also tested. It produced 867 `NO_COVERAGE` mutants because its ASM could not process Java class-file major version 69 under JDK 25. PIT 1.25.9 processes the classes successfully.

## Result

The full Thrift package run generated 867 mutations:

| Status | Count |
|---|---:|
| KILLED | 584 |
| SURVIVED | 110 |
| NO_COVERAGE | 171 |
| TIMED_OUT | 2 |

PIT reported 78% line coverage for mutated classes and 84% test strength after excluding no-coverage and timed-out mutants. The run examined 60 test classes, executed 1,912 tests, and took 101 seconds for mutation analysis.

The result is actionable. The 110 survivors are not a coverage percentage; they are candidate missing contracts. The XML report records each source location, mutator, status, and killing test. The 171 no-coverage mutants identify paths that the selected Thrift tests did not reach. The two timed-out mutants need separate triage before interpreting them as survivors.

## Decision

PIT is viable on JDK 25 with PIT 1.25.9 and the JUnit 5 plugin. Keep mutation analysis scoped by package and outside normal verification. Start review with survived mutants in parser boundary checks and enum/metadata handling, then add tests only when a survivor represents a real missing observable contract.
