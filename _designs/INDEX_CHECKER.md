<!--

     SPDX-License-Identifier: CC-BY-SA-4.0

     Copyright The original authors

     Licensed under the Creative Commons Attribution-ShareAlike 4.0 International License;
     you may not use this file except in compliance with the License.
     You may obtain a copy of the License at https://creativecommons.org/licenses/by-sa/4.0/

-->
# Index types for internal code

**Status:** In progress

## Principle

A value that enters Hardwood is checked once, where it enters. From there on its type carries the result of that check, and the compiler proves every internal use is in range. Internal code does not re-check it.

Values enter at two kinds of boundary:

- **Public API.** Arguments passed to the non-`internal` packages, and to `internal` classes that implement a public interface (`PqList`, `PqStruct`, `StructBuilder`, `InputFile`, ...).
- **File bytes.** Thrift fields of the footer and page headers (`internal/thrift`), and page payloads decoded in `internal/encoding`, `internal/compression` and `internal/variant`.

A boundary check stays a runtime check. An internal check whose only job is to protect internal code from internal callers is replaced by a type.

## Tooling

The types come from the Checker Framework Index Checker (`org.checkerframework:checker`), which also runs the Constant Value Checker. The qualifiers come from `org.checkerframework:checker-qual`, a `provided` dependency of `hardwood-core`: the annotations have no run-time role.

The `index-check` Maven profile of `hardwood-core` runs the Index Checker during main compilation. It checks the classes named by the `index-check.classes` property (a regular expression passed as `-AonlyDefs`). The profile compiles into `target/index-check`, apart from the plain build's output: the compiler plugin skips sources it considers up to date, so sharing `target/classes` would let an unchecked compilation stand in for a checked one. Every class in that set compiles with zero index errors. A class joins the set when all of its index errors are resolved; it never joins with errors outstanding.

The Index Checker runs in the same `javac` invocation as Error Prone. An Error Prone error stops the compilation before the Index Checker reports, so Error Prone findings are fixed first.

## Qualifiers in use

| Qualifier | Meaning |
|---|---|
| `@NonNegative`, `@Positive` | Lower bound: `>= 0`, `>= 1`. |
| `@IndexFor("a")` | Valid index into sequence `a`: `0 <= i < a.length`. |
| `@IndexOrHigh("a")` | `0 <= i <= a.length`; valid as an exclusive end. |
| `@LengthOf("this")` | On `size()` of a fixed-size class, which makes the class a sequence the checker understands. |
| `@HasSubsequence` | On a backing-array field: the object is the slice `[start, end)` of that array. |
| `@LessThan("e")` | Value is less than expression `e`. |
| `@SameLen("a")` | Array has the same length as `a`. |
| `@IntRange(from, to)` | Value lies in a fixed range, such as a bit width in `[0, 32]`. |

## Rules

1. A boundary keeps its runtime check. The check is written as an `if … throw` on the value itself; the checker narrows the value's type from that condition. `Objects.checkIndex` and a separate `checkBounds(index)` helper do not narrow the caller's value.
2. The type of every parameter, field, record component and return value between the boundary and the use carries the fact. An unannotated link breaks the proof and the checker reports it.
3. An internal re-check is deleted once the checker proves it can never fire. A test that exists only to build the impossible state (for example a record constructed with `-1` by hand) is deleted with it.
4. Where the checker cannot prove a fact, the code either keeps a runtime check or carries `@SuppressWarnings("index")` on the narrowest element, with a comment that states the fact being asserted.
5. Code in other modules that calls an `internal` method whose re-check was removed is compiled with the Index Checker too, or the method keeps its check.

## Limits

The Index Checker does not prove:

- Products. `i * 2` and `pageIndex * stride` have no upper bound; division, `%`, `&` and right shift by a constant keep bounds.
- Facts about array elements, such as "every entry of `dictIndices` is a valid dictionary id". Those are checked where the values are decoded.
- Relations between `ByteBuffer` position, limit and backing-array length.
- Integer overflow.

## Index spaces

Hardwood addresses columns in several index spaces: original (file) column index, projected column index, top-level field index and leaf-column index. They are all `int`, and `ProjectedSchema.toOriginalIndex(int)` / `toProjectedIndex(int)` convert between them. Passing a number from one space where another is expected compiles and can return wrong rows without an error: #525 pins one such case (a top-level primitive after a struct, whose field index differs from its leaf-column index) with a guard test, and #1242 fixed a predicate column that had no index in the projected space. Distinct qualifiers per space, checked by the Checker Framework Subtyping Checker, make such a mix-up a compile error.
