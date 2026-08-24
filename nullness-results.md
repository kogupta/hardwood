# NullAway exploration results

Base: main at 9a3a8588b03396227af30a173303d207567e4f19.

## Tooling

- `org.jspecify:jspecify:1.0.1` resolved from Maven Central.
- `com.uber.nullaway:nullaway:0.14.0` resolved from Maven Central.
- Error Prone 2.49.0 and JDK 25.
- NullAway ran through the existing Error Prone compiler path.
- A temporary `@Nullable String` dereference failed compilation with one NullAway diagnostic.
- Tests required an Error Prone excluded-path rule in this exploratory setup; test code is not annotated yet.

## Surface

- IntelliJ Structural Search: 129 production `return null` statements in core.
- IntelliJ Structural Search: 71 null-initialized local states in `dev.hardwood.internal.thrift`.
- Other production modules had 38 explicit `return null` statements across CLI and parquet-java-compat. Avro, S3, and AWS auth had no explicit `return null` in the searched production paths, but they contained nullable state and optional configuration checks.

## Representative core package

`dev.hardwood.internal.thrift` was marked `@NullMarked`.

Initial production diagnostics: 11.

All 11 were explainable contracts or lazy state:

- 2 lazy `pathCache` initialization diagnostics.
- 1 optional i64 array return.
- 1 optional bounding-box coordinate helper return.
- 4 optional ColumnIndex arrays passed to validation.
- 1 LogicalType union that can be absent.
- 1 optional PageEncodingStats entry.
- 1 unknown ConvertedType sentinel return.

After seven narrow `@Nullable`/type-use annotations, production compilation passed with zero NullAway diagnostics. IntelliJ then found that `ThriftEnumLookup.ENCODINGS` intentionally contains a null hole but was declared as a non-null element array. Marking the array elements nullable removed both the `null stored in non-null array` and `condition always true` warnings. No runtime checks were removed.

Focused parser tests: 44 passed.
Full core verification: 2,650 tests, 0 failures, 0 errors, 1 skipped.

## Other modules

These were temporary representative-package probes and were removed after measurement:

| Module/package | Diagnostics | Observation |
|---|---:|---|
| `s3/dev.hardwood.s3` | 3 | Lazy tail cache and builder/config fields; one nullable constructor argument |
| `avro/dev.hardwood.avro` | 2 | Lazy filter field and one nullable reader argument |
| `aws-auth/dev.hardwood.aws.auth` | 0 | No diagnostics in the package probe when compiled against the installed S3 artifact |
| `cli/dev.hardwood.cli.command` | 68 | Uninitialized CLI option fields, nullable command results, optional index values, and generated Aesh sources |
| `parquet-java-compat` | Not annotated | Compatibility code contains nullable returns but is a separate copied API surface and needs a separate policy |

The CLI result is noisier than the others because annotation processing generated additional diagnostics. It is not a clean adoption estimate.

## Java 22 overlay

A temporary nullable dereference in `src/main/java22` failed with NullAway. The Java 21 package annotation propagated. A matching Java 22 package-info file was also tested; no production probe remains.

## Build cost

Clean `core -am compile` baseline: 20.19 seconds.
Clean `core -am compile` with NullAway and the representative annotations: 21.13 seconds.
Observed difference: +0.94 seconds, approximately +4.7 percent. This is one measurement per state, not a stable benchmark.

## Decision

NullAway provides measurable value as a contract inventory and catches accidental nullable dereferences. Core Thrift produced useful explicit contracts and one real annotation contradiction. S3 and Avro also show small, tractable surfaces. CLI requires generated-code handling and a larger migration. The first package produced no confirmed runtime defect and no safe null-check deletions. Wider adoption should start with core metadata/reader contracts and S3/Avro, not with a whole-repository `@NullMarked` switch.
