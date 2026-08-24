<!--

     SPDX-License-Identifier: CC-BY-SA-4.0

     Copyright The original authors

     Licensed under the Creative Commons Attribution-ShareAlike 4.0 International License;
     you may not use this file except in compliance with the License.
     You may obtain a copy of the License at https://creativecommons.org/licenses/by-sa/4.0/

-->
# Nullness strategy

**Status:** Proposed

## Verdict

Hardwood should adopt JSpecify annotations and NullAway for production code, with a staged package rollout. The evidence supports a useful static contract checker and a small build cost. It does not support a blanket annotation migration or removing runtime validation.

The first annotated package found eleven contract diagnostics and one incorrect array declaration. Seven narrow nullable annotations made the package pass. IntelliJ then found that `ThriftEnumLookup.ENCODINGS` contains a deliberate `null` hole while its element type was non-null; correcting that contract removed an always-true null check warning. No runtime null check was removed, and no confirmed runtime defect was found in this first package.

The initial measurements were:

| Area | Result |
|---|---:|
| Core Thrift diagnostics before annotations | 11 |
| Core Thrift diagnostics after annotations | 0 |
| Core Thrift verification | 2,650 tests, 0 failures, 1 skipped |
| Clean core compile without NullAway | 20.19 seconds |
| Clean core compile with NullAway | 21.13 seconds |
| Observed compile increase | 0.94 seconds, 4.7% |
| S3 representative package diagnostics | 3 |
| Avro representative package diagnostics | 2 |
| AWS auth representative package diagnostics | 0 |
| CLI representative package diagnostics | 68 |

The CLI result includes generated Aesh sources and is not an adoption-cost estimate until generated-code handling is defined. A broad probe over core public metadata, reader, schema, row and writer packages exceeded the compiler diagnostic limit, so those packages require separate rollout measurements.

## Contract

### JSpecify describes the nullness contract

JSpecify is the source-level annotation vocabulary. `@NullMarked` is the default for a package. `@Nullable` marks a value, field, parameter, return, or type argument that may be absent.

Package annotations are added through `package-info.java` with the repository SPDX header and Markdown `///` package documentation. Type-use placement must distinguish a nullable array from an array with nullable elements.

The first permanent rollout covers internal production packages. Public API packages are annotated only when the nullness behavior is documented under `docs/content/` and the API compatibility report is reviewed.

### NullAway enforces marked code

NullAway runs through the existing Error Prone invocation. Existing `NoVar` and `NoLegacyJavadoc` checks remain enabled in the same compiler execution. The initial configuration uses `OnlyNullMarked=true` so unmarked packages remain outside the checking boundary.

The nullness profile may be opt-in during rollout. A package becomes a permanent build gate only after its diagnostics are classified and its focused tests pass. The final supported state enables the check for all completed production packages while leaving tests, generated sources and third-party stubs outside the initial boundary.

JSpecify is a provided dependency. It must not become a runtime requirement of `hardwood-core` or another published module.

## Runtime validation remains authoritative

Static nullness and runtime input validation solve different problems.

- NullAway proves properties of source-level control flow.
- Parquet metadata is untrusted input and must still be validated at runtime.
- Required Thrift fields fail at the parser boundary with Hardwood-owned exceptions and file or column context.
- Optional metadata retains its current representation and behavior.
- Assertions are not a replacement for malformed-input checks.
- A null check that validates input, supplies error context, or protects a dynamically typed boundary is not redundant merely because a static annotation exists.

No null check is removed solely because NullAway passes. Removal requires proof that the value is non-null by construction and that the check does not enforce an input or error contract.

## Rollout order

### Core Thrift metadata

Annotate `dev.hardwood.internal.thrift` first. This package has a clear parser boundary and explicit required and optional fields. Nullable contracts include optional arrays, absent logical types, absent page encoding statistics, unknown enum values, and lazy path-cache state.

The `ThriftEnumLookup` tables use nullable elements where the format maps an unknown or unsupported ordinal to absence. The table declaration and lookup helper must express that element-level nullability.

### S3 and Avro

Annotate the S3 and Avro production packages after their provided JSpecify dependencies and package contracts are established. Their representative probes found three and two diagnostics respectively. These are small enough to provide a second validation of the approach without the noise of the CLI generated-source path.

### Core reader, metadata, schema, row and writer packages

Roll out package-by-package. The broad probe exceeded the diagnostic limit, so each package must have a bounded diagnostic inventory and focused contract review before it becomes a build gate.

### AWS auth

The representative AWS auth package produced no diagnostics. It may be annotated with a normal package review, but it is not evidence that its transitive S3 contracts are checked. Dependency boundaries must be tested separately.

### CLI

Defer the CLI build gate until generated Aesh sources have an explicit exclusion or annotation policy. The representative probe produced 68 diagnostics, mixing application state with generated-code noise.

### parquet-java-compat

Treat the compatibility module as a separate nullness surface. Its copied or compatibility-oriented API must not inherit annotations accidentally from core. Define its public contract and compatibility policy before marking packages.

## Diagnostics policy

Every diagnostic has one of these dispositions:

1. Add a precise nullable annotation because absence is part of the contract.
2. Add or retain fail-early validation because the value is required input.
3. Model an external or unannotated dependency boundary narrowly.
4. Record a local checker limitation with the smallest justified suppression.
5. Report a checker defect.

Package-wide suppressions and blanket `@SuppressWarnings("NullAway")` are prohibited. Each suppression must identify the boundary or checker limitation it covers.

## Verification

Every rollout package requires:

- NullAway at `ERROR` under `OnlyNullMarked=true`.
- Existing Error Prone checks in the same compilation.
- Focused tests for changed required or optional behavior.
- Existing module tests.
- Java 22 multi-release compilation where the package has an overlay.
- No unintended public API change.
- No runtime-scoped JSpecify dependency.
- IntelliJ inspection of nullable contracts and redundant conditions.

The core Java 22 overlay is covered by a temporary nullable-dereference probe. The probe failed as required, proving that the overlay compilation sees the nullness contract.

## Adoption gate

The project proceeds with staged nullness annotation. A package is complete when it has:

- zero unexplained diagnostics;
- no package-wide suppression;
- explicit required and optional contracts;
- preserved malformed-input behavior;
- focused and module tests passing;
- no unintended API or runtime dependency change;
- recorded compile-time cost.

The project does not require a target number of deleted null checks. A package that adds precise contracts and removes no code still provides preventive value.
