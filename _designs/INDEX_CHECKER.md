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
2. The type of every parameter, field, record component and return value between the boundary and the use carries the fact. Within the checked set, an unannotated link breaks the proof and the checker reports it.
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

Run it as `./mvnw -pl core -am -Pcolumn-index-check -Dquick compile`. Without `-am`, Maven takes the checker from the local repository, which lags behind any edit to `tools/column-index-checker`.

The two profiles configure the same compilation, so they are exclusive: an enforcer rule stops a build that activates both.

### Qualifiers

| Qualifier | Meaning |
|---|---|
| `@OriginalIndex` | A leaf ordinal of the reference schema: `FileSchema.getColumn(int)` of the schema the projection was resolved against. |
| `@ProjectedIndex` | A leaf position in the projection: `ProjectedSchema.getProjectedColumn(int)`. |
| `@ProjectedIndexOrAbsent` | A `@ProjectedIndex`, or `-1` for a column the projection leaves out. `toProjectedIndex` returns it. |
| `@FileOrdinal` | A leaf position in one specific file, as its row groups list their column chunks. Equal to the `@OriginalIndex` only in the reference file. `FileColumnOrdinals.fileOrdinal` converts one to the other. |
| `@ColumnIndexUnknown` | Every unannotated `int`. The top of the hierarchy. |
| `@ColumnIndexBottom` | A literal, or an expression of literals and operators such as `-1`. The bottom of the hierarchy: it fits every space. |

A qualifier on a parameter, field or return value states its space. A local variable takes the space of the value assigned to it. A variable that holds only a literal reads as its declared type, whether it is a local, a parameter, a field or a named constant; so do `i++` and `--i`. A loop counter therefore states its space in its declaration, as in `for (@ProjectedIndex int p = 0; p < count; p++)`. An unannotated counter or constant belongs to no space and is rejected wherever one is required. A conditional `c ? a : b` or a `switch` expression reads as the least upper bound of its results.

### Subscripts

Which space indexes a `FileSchema` or a `RowGroup.columns()` list depends on which file it came from, so no qualifier on `FileSchema.getColumn(int)` itself can state it. The declaration annotation `@IndexedBy(X.class)` states it on the variable, field, parameter or method that holds the array, list or schema. The checker then requires an index of space `X` for:

- `array[i]`;
- every `int` argument of the `java.util.List` methods `get`, `set`, `add`, `remove`, `listIterator` and `subList`;
- `schema.getColumn(i)`.

`column-index.astub`, bundled with the checker, marks `RowGroup.columns()` as `@IndexedBy(FileOrdinal.class)`. An `@IndexedBy` naming a class that is not one of the four spaces is an error at its declaration.

An array, list or schema keeps its `@IndexedBy` when it is assigned to a variable, field or array element, passed to a parameter, returned, or bound to the variable of a type pattern (`x instanceof List<?> l`, or a `case` label of a `switch`): source and target must name the same space, and a missing `@IndexedBy` on either side counts as a space of its own. An array element, including one written in an array initializer, has no `@IndexedBy`, and neither has a target whose type is not an array, list or schema, such as `Object` or `Iterable`; handing a container of a space to one is an error. A parameter of a method declared outside the checked set accepts any array, list or schema, since the checker does not check that method's body. An array or object created with `new` fits any target; so do the elements of a varargs call, which form a new array. A new object that is not an array, list or schema carries no space, even when its constructor is given one. A value returned by a factory method, such as `List.of()`, has no `@IndexedBy`.

A copy or a view carries the space of its source:

- `clone()` of an array or list, `Arrays.copyOf(a, n)`, `Arrays.copyOfRange(a, from, to)` and `List.copyOf(l)`;
- a constructor given an array or list, such as `new ArrayList<>(chunks)`;
- `schema.getColumns()`, whose positions are those of `schema.getColumn(int)`;
- `l.toArray()`, `Collections.unmodifiableList(l)`, `Collections.synchronizedList(l)` and `Objects.requireNonNull(x)`;
- `Arrays.asList(a)` and `List.of(a)` given a whole array;
- a cast, such as `(List<String>) iterable`.

`subList` and `reversed` return lists whose positions differ from those of their source, so they carry no space. The container a subscript reads from is resolved the same way as a handover, so `schema.getColumns().get(i)` needs the index space of `schema`. A conditional or a `switch` expression carries the spaces of all its results, and each must fit.

### Conversion points

A value enters a space at a conversion point: a method or local variable that has the qualified type and carries `@SuppressWarnings("columnindex")`, with a comment stating why the value belongs to the space. A cast into a space is an error, so every conversion point other than a declared counter or constant (below) is visible as a suppression. The conversion points are:

- `ProjectedSchema.originalIndex(ColumnSchema)` and `originalIndex(PrimitiveNode)`, which turn the `columnIndex()` of a reference-schema leaf into an `@OriginalIndex`;
- `RowGroupIterator.fileOrdinalOf`, which looks a column up by path in one file's schema and returns its `@FileOrdinal`;
- `RowGroupIterator.asReference`, which marks the first file's schema as the reference schema;
- `FileColumnOrdinals.identity` and `ProjectedSchema.createAllColumnsProjection`, where each index is its own counterpart in the other space.

`asReference` and `identity` hold only for the file at index 0. The checker does not see the `fileIndex == 0` test that selects them; `CrossFileColumnOrderTest` covers it at run time.

A qualifier on the counter of a basic `for` loop or on a named constant is a conversion point without a suppression: the checker takes the declaration as given and does not relate it to the loop's bound. The counter of an enhanced `for` takes the type of the elements it iterates, so `for (@ProjectedIndex int p : positions)` compiles only if `positions` is a `@ProjectedIndex int[]`. Where the loop body also uses the counter in its true space, such as `plans[projCol]` or `toOriginalIndex(p)`, a wrong declaration fails there.

The qualifier is also lost, and needs a conversion point, where the value passes through:

- an `int` boxed into an unqualified `Integer`, such as an element of a `List<Integer>`;
- a JDK functional interface, such as `IntPredicate` or `IntUnaryOperator`;
- a JDK method that returns a plain `int`, such as `BitSet.nextSetBit`.

### Limits

The checker catches a mix-up of indices only in the checked set. A qualified parameter outside it binds only the callers inside it, as Rule 5 states. The filter path (`PageFilterEvaluator`, `RowGroupFilterEvaluator`, `MinMaxStats` and the column index of a `ResolvedPredicate` leaf) is outside the set, so passing reference ordinals where the filter needs file ordinals is not caught there.

A literal written in place, such as the `0` in `list.get(0)` or a `-1` sentinel, fits every space. A position computed from the size of a container, such as `list.size() - 1`, belongs to no space and needs a conversion point.

Only an `int` argument of the methods listed under Subscripts is checked. A position reached another way is not: `list.stream().skip(n)`, `ListIterator.nextIndex()`, a method reference such as `chunks::get`. Containers other than arrays, `List` and `FileSchema` carry no space, such as a `BitSet` of columns or a `Map` keyed by column index. A container returned from a lambda or a method reference, such as `() -> chunks` passed as a `Supplier`, loses its space. So does a container kept in another holder or passed through a method not listed above: an `Optional`, a `List<List<…>>`, a `Map` value, `List.of(chunks)`, a generic helper method, a parameter of a lambda or method reference, or the components of a record pattern. A container filled from another by a method such as `addAll` or `System.arraycopy` has no space of its own and does not take the source's.

`@IndexedBy` names one space, so a method that takes a container of any space cannot be declared in the checked set; it belongs outside the set, or has one variant per space.

The checker skips the bodies of local and anonymous classes. In a checked class, such a class is an error (`class.unchecked`) if it has code: a method body, a field initializer or an initializer block. A local record, interface or enum without code is allowed; an enum constant is code if it has arguments or a body, since arguments need a constructor. Code moved into a method or a member class is checked.

Stub annotations do not apply to the accessors of records nested in another type, such as `SchemaNode.PrimitiveNode`; they do apply to top-level records and to nested classes. The conversion points above cover the nested records.
