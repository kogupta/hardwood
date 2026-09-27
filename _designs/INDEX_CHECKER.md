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

The `index-check` Maven profile of `hardwood-core` runs the Index Checker during main compilation. It checks the classes named by the `index-check.classes` property (a regular expression passed as `-AonlyDefs`). Every class in that set compiles with zero index errors. A class joins the set when all of its index errors are resolved; it never joins with errors outstanding.

The profile compiles into `target/index-check`, apart from the plain build's output, and empties that directory in the `initialize` phase. The compiler plugin skips sources it considers up to date, so without both steps an earlier compilation, unchecked or checked with a different class set or checker version, would stand in for this one.

Only the `default-compile` execution runs the checker. The Java 22 sources (`src/main/java22`) and the test sources are never checked.

Error Prone runs in the same `javac` invocation only when the `qa` profile is active, which it is unless `-Dquick` is set. An Error Prone error then stops the compilation before the Index Checker reports, so Error Prone findings are fixed first.

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
4. Where the checker cannot prove a fact, the code either keeps a runtime check or carries a suppression on the narrowest element, with a comment that states the fact being asserted. The key is `"index"` for an Index Checker finding and `"value:<message key>"` for a Constant Value Checker finding, such as `"value:switch.expression"`; `"index"` does not suppress the latter.
5. Every call site that passes a value into an annotated parameter or record component is in the checked set, or passes a literal. Otherwise the callee keeps its runtime check. An annotation on a class outside the set constrains only the callers inside it.

## Limits

The Index Checker does not prove:

- Products. `i * 2` and `pageIndex * stride` have no upper bound; division, `%`, `&` and right shift by a constant keep bounds.
- Facts about array elements, such as "every entry of `dictIndices` is a valid dictionary id". Those are checked where the values are decoded.
- Relations between `ByteBuffer` position, limit and backing-array length.
- Integer overflow.

## Index spaces

Hardwood addresses columns in several index spaces. They are all `int`, so passing a number from one space where another is expected compiles and can read the wrong column without an error. #903 fixed such a case: the multi-file reader looked up column chunks, column schemas and page indexes of every file with the reference file's leaf ordinals, and decoded one column's pages into another column's slot whenever a later file ordered its columns differently. #525 pins another with a guard test (a top-level primitive after a struct, whose field index differs from its leaf-column index).

The `column-index-check` profile of `hardwood-core` runs a Hardwood checker for three of these spaces. It is a Checker Framework subtyping checker, built by the `hardwood-column-index-checker` module (`tools/column-index-checker`), which also holds the qualifiers. Core depends on that module with `provided` scope. The profile checks the classes named by `column-index-check.classes`, compiles into `target/column-index-check` and empties it first, as `index-check` does.

### Qualifiers

| Qualifier | Meaning |
|---|---|
| `@OriginalIndex` | A leaf ordinal of the reference schema: `FileSchema.getColumn(int)` of the schema the projection was resolved against. |
| `@ProjectedIndex` | A leaf position in the projection: `ProjectedSchema.getProjectedColumn(int)`. |
| `@ProjectedIndexOrAbsent` | A `@ProjectedIndex`, or `-1` for a column the projection leaves out. `toProjectedIndex` returns it. |
| `@FileOrdinal` | A leaf position in one specific file, as its row groups list their column chunks. Equal to the `@OriginalIndex` only in the reference file. `FileColumnOrdinals.fileOrdinal` converts one to the other. |
| `@ColumnIndexUnknown` | Every unannotated `int`. The top of the hierarchy. |
| `@ColumnIndexBottom` | Integer literals. The bottom of the hierarchy: a literal fits every space. |

A qualifier on a parameter, field or return value states its space; locals take the space of the value assigned to them.

### Subscripts

Which space indexes a `FileSchema` or a `RowGroup.columns()` list depends on which file it came from, so no qualifier on `FileSchema.getColumn(int)` itself can state it. The declaration annotation `@IndexedBy(X.class)` states it on the variable, field, parameter or method that holds the array, list or schema. The checker then requires an index of space `X` for `array[i]`, `list.get(i)` and `schema.getColumn(i)` on it. `column-index.astub`, bundled with the checker, marks `RowGroup.columns()` as `@IndexedBy(FileOrdinal.class)`.

### Conversion points

A value enters a space at a conversion point: a method that returns the qualified type and carries `@SuppressWarnings("columnindex")` with a comment stating why the value belongs to the space. `ProjectedSchema.originalIndex(ColumnSchema)` and `originalIndex(PrimitiveNode)` turn the `columnIndex()` of a reference-schema leaf into an `@OriginalIndex`. The qualifier is also lost, and needs a conversion point, where the value passes through:

- a boxed `Integer`, such as a `List<Integer>` element;
- a JDK functional interface, such as `IntPredicate` or `IntUnaryOperator`;
- a JDK method that returns a plain `int`, such as `BitSet.nextSetBit`.

### Limits

Integer literals fit every space, so an index computed from a literal (a loop counter starting at `0`, a `-1` sentinel) is accepted wherever an index is required. The checker catches a mix-up of indices that came from typed sources: a qualified parameter, field or return value.

Stub annotations do not apply to members of nested records such as `SchemaNode.PrimitiveNode`; the conversion points above cover them instead.
