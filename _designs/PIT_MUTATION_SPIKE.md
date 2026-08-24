<!--

     SPDX-License-Identifier: CC-BY-SA-4.0

     Copyright The original authors

     Licensed under the Creative Commons Attribution-ShareAlike 4.0 International License;
     you may not use this file except in compliance with the License.
     You may obtain a copy of the License at https://creativecommons.org/licenses/by-sa/4.0/

-->
# PIT mutation testing spike

**Status:** Evidence complete; staged adoption recommended

## Question

Should Hardwood incorporate mutation testing into its correctness workflow?

## Scope tested

Branch: `spike/mutation`.

PIT was run against all production classes and tests in `dev.hardwood.internal.thrift`. The run used PIT `1.25.9`, the PIT JUnit 5 plugin `1.2.2`, JDK 25, and the existing Java 21/Java 22 core build. The profile is opt-in and is not part of normal `verify`.

## Evidence

| Measurement | Result |
|---|---:|
| Mutations generated | 867 |
| Killed | 584 |
| Survived | 110 |
| No coverage | 171 |
| Timed out | 2 |
| Test strength | 84% |
| Mutated-class line coverage | 78% |
| Tests examined | 60 classes |
| Test executions | 1,912 |
| Mutation runtime | 101 seconds |

PIT `1.19.1` was also tested. It reported no usable coverage because its ASM could not process class-file major version 69 produced under JDK 25. PIT `1.25.9` processed the same classes and ran JUnit 5 tests correctly.

The XML report identifies every survivor by source file, method, line, mutator and test coverage. The 110 survivors are actionable candidates, not a score to optimize blindly. The 171 no-coverage mutants identify code that the selected Thrift tests do not reach. The two timeouts require separate triage.

## Tradeoffs

### Benefits

- Finds weak assertions without requiring an external reference implementation.
- Measures whether malformed-input and boundary tests detect changed behavior.
- Produces source-level candidates for missing tests.
- Runs independently of production behavior and does not change the shipped artifact.

### Costs

- A scoped Thrift run takes about 101 seconds; a repository-wide run will cost more.
- Survivors require human classification. Some are equivalent or irrelevant.
- No-coverage mutants must remain distinct from survived mutants.
- PIT compatibility is version-sensitive on JDK 25.
- Mutation runs are too expensive and noisy for every normal verification.

## Decision

Incorporate PIT as an opt-in, package-scoped adequacy check. Do not add it to normal `verify`. Start with parser and metadata packages, where boundary mutants have a clear robustness contract. Record survivors and dispositions in a checked-in or CI artifact. Keep no-coverage and timed-out results separate from survivors.

PIT is justified by this spike because it found 110 survivors and 171 unreachable mutation sites in a package whose malformed-input tests already pass. That is evidence of a reviewable test gap, not merely tool integration.

## Required follow-up

- Classify the 110 survivors.
- Add tests only for survivors that represent observable missing contracts.
- Investigate the 171 no-coverage mutants as reachability gaps.
- Resolve the two timed-out mutants.
- Establish a package allowlist and a scheduled CI job.
