# Review 6: column-index checker (3f9a8a1d..bc39d2fb)

Reviewed at `bc39d2fb` in `.claude/worktrees/review-index-checker`. JDK 25, `timeout 180 ./mvnw`. Every experiment was reverted, and `git status --short` is clean.

Probe harness: `scratchpad/probe/run.sh`, with the checker jar rebuilt at `bc39d2fb`. This round's probe sources are in `probe/r6/`:

- `Q.java`: the origin-based early return, type patterns, and `asList`/`List.of`.
- `probe/E.java`: local enums. Run with `-AonlyDefs='^probe\.E(\..*)?$'`.

The r4 and r5 probes were re-run.

## Must fix

None.

## Should fix

None.

## Nits

- [x] **The literal-argument clause of `isPlainEnumConstant` never changes the outcome, so the docs promise an allowance that cannot occur.**
  - An enum constant with arguments needs an explicit constructor, and `hasCode` already counts that constructor's body as code, even an empty `E(int v) {}`.
  - `E:4` (`P(1, "a")` with the constructor `A(int i, String s) {}`), `E:7` and `E:8` are all reported. Only the argument-free `enum C { P, Q; }` (`E:6`) passes.
  - The design says: "an enum constant is code only if it has a body or an argument that is not a literal". The notes say: "A local enum whose constants have no body and only literal arguments is allowed". Both describe a case that is always reported.
  - Fix, either:
    - drop the argument loop and say "a local enum whose constants have neither arguments nor bodies";
    - or also exempt the constructor that only stores its parameters, which is probably not worth it.
  - **Resolved:** `isPlainEnumConstant` now allows only a constant with neither arguments nor a body, and `isLiteralValue` is private again. The design and the notes say the same.
- [x] **A misplaced "so do" in the design's handover paragraph.** The sentence reads: "An array or object created with `new` fits any target, and a new object that is not an array, list or schema carries no space even when its constructor is given one; so do the elements of a varargs call, which form a new array." "So do" now attaches to "carries no space" instead of "fits any target". Move the varargs clause before the new clause, or split the sentence.
  - **Resolved:** The varargs clause follows "fits any target" again; the sentence about new non-container objects stands on its own.
- [x] **The Limits cover a method-reference parameter only by analogy.** `W:21` (`BiFunction<…> f = this::first; f.apply(chunks, o)`) still compiles clean. The Limits name "a lambda parameter". Make it "a lambda or method-reference parameter".
  - **Resolved:** The Limits and the notes say "a parameter of a lambda or method reference".
- [x] **The array-element message names the array, not the element.** An initializer reports `new Object[]{names} is indexed by another column-index space than the value handed to it` (`Q:21`). `a[0] = chunks` reports `a is indexed by …`. Both read as if the array's own space were wrong. Consider a separate key, e.g. `indexedby.element`: "an element of %s holds no column-index space; the value handed to it is %s".
  - **Resolved:** New key `indexedby.element`: "an element of X has no @IndexedBy, so a container handed to it loses its column-index space." Used for assignments to an element and for initializers; `arrayElementHoldsNoSpace` and `arrayInitializerHoldsNoSpace` pin it.

## Verified correct

- [x] Builds and checks:
  - `-pl tools/column-index-checker verify`: 42 tests, 0 failures.
  - `-pl core -am -Pcolumn-index-check compile`: 0 errors, with and without `-Dquick`.
  - `-Pindex-check -Dquick compile`: rc 0.
  - `docs-prose-check.py`: rc 0.
  - No core code changed.
- [x] Real-code canary: at `RowGroupIterator:770`, `(workItem.fileSchema().getColumns() instanceof ArrayList<ColumnSchema> al ? al.get(originalIndex) : null)` gives `[770,88] [indexedby.handover] al …`.
- [x] Round-5 items resolved:
  - `W:12` (`instanceof` binding), `W:13` (`case` binding on a cast selector) and `W:15` (array initializer) now report.
  - So do `W:24` (`Object[] a = { chunks }`), `W:16` (`Arrays.asList(names)`), `W:17` (`List.of(names)`), `W:19` (`ArrayList.clone()` typed `Object`) and `W:22` (`synchronizedList`).
  - `probe/K.java:9`, a plain local enum, is no longer reported.
  - Still silent, and now listed in the Limits: `W:10/11/27` (`addAll`, `arraycopy`, `Collections.addAll`), `W:14` (a lambda parameter), and `W:20/26` (stream copies: "a method not listed").
- [x] Focus 1, the origin-based early return and `NEW_CLASS`. There are no false positives:
  - `chunks.toString()`, `String.join(",", chunks)`, `chunks.size()` and `Object o = chunks.get(0)` are silent (`Q:12/13/19/25`).
  - A non-container `new` given a container is silent (`Q:17`, `StringBuilder.append(chunks)`).
  - A record constructor in the set is still checked through `checkArguments`: `Q:16` reports the unmarked `Plain.l`, and `Q:15` accepts the marked `Rec`.
  - The expected rejections all report:
    - a widened view (`Q:14` `requireNonNull`);
    - a mixed conditional (`Q:18`);
    - an array initializer (`Q:21`);
    - a widened copy constructor (`Q:22`, into `Iterable`);
    - `names.clone()` into `Object` (`Q:23`).
  - `Q:20`: `new Box<>(chunks)` for a generic record in the set reports on `v`. This follows the documented rule for generic helpers.
  - The only new silence the `NEW_CLASS` change adds is a non-container JDK holder (`Q:24`, `AtomicReference`), which the Limits cover as a holder.
- [x] Focus 2, patterns:
  - Reported:
    - `instanceof final` (`Q:29`);
    - an old-style `case List<?> l when …:` in a switch statement (`Q:31`);
    - a switch-expression type pattern over a container selector (`Q:35`);
    - a conditional selector for `instanceof` (`Q:36`).
  - Accepted:
    - `@IndexedBy(FileOrdinal.class)` on the binding with the right index (`Q:30`);
    - an unannotated binding given a `NONE` value (`Q:28`, `x instanceof List<?> l` for an `Object` parameter);
    - a pattern-free `instanceof` (`Q:37`).
  - Generic record patterns (`Q:32/33`) do not follow the components, as the Limits say. The errors on those lines are for `new Box<>(chunks)`.
- [x] Focus 3, `Arrays.asList`/`List.of`:
  - The whole-array rule fires for `List.of(names)`, `Arrays.asList(names)`, `Arrays.asList((String[]) names)`, `List.<Object>of(names)` and `Arrays.<Object>asList(names)` (`Q:39/43/47/41/45`). The last two resolve to the varargs method with the array passed whole, so positions are kept and the error is right.
  - It does not fire where the array is one element:
    - `List.<String[]>of(names)` (`Q:40`, resolves to `of(E)`);
    - `List.of(ords)` for an `int[]` (`Q:42`, `of(E)` with `E = int[]`);
    - `Arrays.<String[]>asList(names)` (`Q:44`, a varargs call);
    - `List.of(names[0])` (`Q:46`).
- [x] Switch-expression results (`S.java`) and the r4 probes (`L.java`, `P.java`, `V.java`) give the same results as in round 5.
- [x] Docs: the design's Subscripts, Limits and handover paragraph, and the notes, match the code, except for the enum-argument wording and "so do" above.
