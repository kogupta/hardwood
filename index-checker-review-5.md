# Review 5: column-index checker (f2bb0d50..3f9a8a1d)

Reviewed at `3f9a8a1d` in `.claude/worktrees/review-index-checker`. JDK 25, `timeout 180 ./mvnw`. Every experiment was reverted, and `git status --short` is clean.

Probe harness: `scratchpad/probe/run.sh`, with the checker jar rebuilt at `3f9a8a1d`. This round's probe sources are in `probe/r5/` and are cited as `<File>:<line>`:

- `W.java`: laundering paths that remain.
- `S.java`: switch-expression results.
- `probe/K.java`: `class.unchecked`. Run with `-AonlyDefs='^probe\.K(\..*)?$'`.

The round-4 probes (`r4/L.java`, `r4/P.java`, `r4/V.java`) were re-run. The CF 4.2.3 sources used for the `defsPattern` comparison are unpacked in `scratchpad/cfsrc/`.

## Must fix

None.

## Should fix

- [x] **A type pattern hands a container to a binding variable without a check.** `instanceof` and `switch` patterns declare a variable with no initializer, so `visitVariable` sees nothing to hand over, and the binding variable carries no space:
  - `W:12`: `if (chunks instanceof ArrayList<String> al) { return al.get(o); }` compiles clean.
  - `W:13`: `switch ((Object) chunks) { case List<?> l -> l.get(o); … }` compiles clean.
  - Assigning the same value to a local is rejected (`L:9–11`, `P:29–33`), so this is a gap in the handover rule itself, not a Limit.
  - Fix:
    - In `visitInstanceOf`, when the pattern is a `BindingPatternTree`, check a handover from `tree.getExpression()` to the binding variable (`checkHandover(expression, bindingElement, space(bindingElement), tree)`).
    - Do the same for the type-pattern labels of a `switch` statement or expression, against its selector.
    - Record patterns can be left to the Limits.
  - **Resolved:** `visitInstanceOf` checks a handover from the tested value to the variable of a binding pattern; `visitSwitch` and `visitSwitchExpression` do the same for each `case` label with a type pattern, against the selector. Record pattern components are listed in the Limits. Test `patternBindingKeepsSpace` failed before the change.

- [x] **Array initializers skip the new array-element rule.** The design now says: "An array element has no `@IndexedBy` …; handing a container of a space to one is an error." `visitAssignment` enforces this for `a[i] = chunks` (`L:16`, `P:16/17` now report). The initializer form does not go through it:
  - `W:15`: `List<String>[] a = new List[] { chunks }; a[0].get(o)` compiles clean.
  - `W:24`: `Object[] a = { chunks }; ((List<String>) a[0]).get(o)` compiles clean.
  - Fix: in `visitNewArray`, check each initializer element as a handover to a target with no `@IndexedBy`. The array created implicitly by a varargs call has no `NEW_ARRAY` tree, so varargs are unaffected.
  - **Resolved:** `visitNewArray` checks each initializer as a handover to an element with no `@IndexedBy`. Test `arrayInitializerHoldsNoSpace` failed before the change.

- [x] **The Limits still miss some ways to lose a space.** The new Limits sentence covers holders and "a method not listed". The cases below compile clean (`W.java`) and fall under neither reading:
  - **A JDK method that copies into another container:**
    - `W:10`: `c.addAll(chunks)` into `new ArrayList<>()`.
    - `W:11`: `System.arraycopy(ords, 0, d, 0, n)`.
    - `W:26`: `Collections.addAll(c, names)`.

    The container is passed to an unchecked method, which is allowed. The positions then reach a fresh container, which carries no space. This is the likeliest of these to occur in real code.
  - **A lambda parameter:** `W:14`, `Function<List<String>, String> f = l -> l.get(o); f.apply(chunks)`. The same holds for a method reference to a checked method, `W:20` (`this::first`). The Limits mention only a container *returned* from a lambda.
  - **Views over arrays, and same-position copies not in `copiedFrom`:**
    - `W:16`: `Arrays.asList(names)`.
    - `W:17`: `List.of(names)`.
    - `W:25`: `Arrays.stream(names).toList()`.
    - `W:18`: `((ArrayList<String>) chunks).clone()`, where `clone` is recognised only on arrays.
    - `W:19`: `chunks.stream().map(…).toList()`.
    - `W:21`: `Collections.synchronizedList(chunks)`.

    "A method not listed above" covers these, but `Arrays.asList` and `synchronizedList` are views in the same sense as `unmodifiableList`.
  - Fix:
    - Add `Arrays.asList`, `Collections.synchronizedList`, and `clone()` on a `List` receiver to `copiedFrom`.
    - Extend the Limits sentence: "…, a lambda parameter, or a container filled from another by a method such as `addAll` or `System.arraycopy`."
    - Mirror the change in the notes' open item.
  - **Resolved:** `copiedFrom` adds `Arrays.asList(a)` and `List.of(a)` when the whole array goes to the varargs parameter (not a varargs call), `Collections.synchronizedList`, and `clone()` on a list. `ArrayList.clone()` returns `Object`, so `checkHandover` now decides whether a value is a container by its origins' types rather than its own; a `new` object that is not a container therefore contributes no origins. Test `viewsOverArraysKeepSpace` failed before the change. The Limits and the notes now name lambda parameters, record pattern components, and containers filled by `addAll` or `System.arraycopy`.

## Nits

- [x] **`defsPattern` / `isChecked` versus `SourceChecker`** (focus 1). Compared against `SourceChecker.getPattern` (4.2.3, `cfsrc/…/SourceChecker.java:968`):
  - **Matches CF:**
    - the defaults (`"\\]'\"\\]"` for `skipDefs` and `"."` for `onlyDefs`);
    - the lookup order (option, then `checkers.<name>`, then the environment);
    - an empty value falling back to the default;
    - prefixed options, since `hasOption`/`getOption` go through the same `getOptions()`, including subcheckers;
    - the matched name: `type.asType().toString()` equals `TreeUtils.typeOf(classTree).toString()` for a named class.
  - **Two differences, both minor:**
    - An option given with no value (`-AonlyDefs`): CF throws `UserError("The onlyDefs property is empty …")`. `defsPattern` falls through to the system property and the environment. CF still throws on its first `shouldSkipDefs`, so the build fails either way.
    - `BaseTypeVisitor.visitClass` (`:573`) skips a class when `shouldSkipDefs(tree) || shouldSkipFiles(tree)`. `isChecked` ignores `-AskipFiles`, `-AonlyFiles` and `-AskipDirs`. A class in a skipped file therefore counts as checked, and its unmarked container parameters reject a marked argument. The core profile does not use these options. Either honour them, or note in the `isChecked` comment that only `skipDefs`/`onlyDefs` are replicated.
  - **`isElementFromSourceCode` needs a `file:` URI.** This is not a problem for Maven, which passes files on disk. The profile's clean at `initialize` also rules out the other risk: an incremental build that recompiles only stale sources, where callees loaded from `target/` would count as unchecked.
  - **Resolved:** The `isChecked` comment states that the file options are not applied and that the profile does not set them. An option given with no value fails the build either way, so it is left as is.
- [x] **A local enum is always `class.unchecked`.** `K:9` (`void k03() { enum Color { RED, GREEN } }`) is reported. Its constants are `VariableTree`s with a `NEW_CLASS` initializer, so `hasCode` sees them as field initializers. The design says "A local record or interface without code is allowed", which reads as if a plain local enum were too. Fix: skip enum-constant `VariableTree`s whose initializer has no class body, or name enums in the design sentence.
  - **Resolved:** An enum constant with no body and only literal arguments is not code. The design names local enums. Covered by `localTypesArePartOfTheirClass` (`Color` passes; `Size`, with a constructor body, is reported).
- [x] **The `class.unchecked` advice does not fit an enum constant body.** `K:23`: `enum E { A { int f() { … } }, B; … }` is correctly reported. "Move this code into a method or a member class" is awkward there; a constructor argument or a `switch` in `f()` is the usual rewrite. Consider "…; move this code into a method or a named class".
  - **Resolved:** No change. The usual rewrite, a `switch` in a method of the enum, is "a method"; a local class is also named, so "a named class" would be wrong advice.
- [x] **No test covers `isChecked` walking out of a local class.** `K:26` (`new R2(chunks)` for a local `record R2(List<String> l)`) and `K:27` (`new C2().g(chunks)`) now report `indexedby.handover` under `-AonlyDefs`. Before this round, the same calls were exempt (`r4/probe/Checked.java:21`). Worth one test beside `localAndAnonymousClassesWithCode`.
  - **Resolved:** Test `localTypesArePartOfTheirClass`: `new Pair(chunks)` for a local record reports `indexedby.handover` under `-AonlyDefs=^Read$`.

## Verified correct

- [x] Baseline:
  - `-pl core -am -Pcolumn-index-check -Dquick compile`: 0 errors, with no `class.unchecked` in the core set (`Entry` and `Extent` have no code).
  - Without `-Dquick`: 0 errors.
  - `-Pindex-check -Dquick compile`: rc 0.
  - `tools/docs-prose-check.py`: rc 0.
  - `-pl tools/column-index-checker verify`: 38 tests, 0 failures.
  - No core code changed, so core tests were not re-run. They passed at `f2bb0d50`.
- [x] Canaries at `RowGroupIterator:770`:
  - The switch form gives `[subscript.space]` at `[770,115]`, with `-Dquick`.
  - The `(List) (Collection) getColumns()` form gives `[subscript.space]` without `-Dquick`. It is at `[771,130]` because the added import shifts the line by one.
- [x] Switch expressions (`S.java`):
  - Old-style `case …: yield` inside a block, `yield` inside `try`, and a nested switch as a result each report once per distinct wrong space (`S:6–8`).
  - A `throw` arm is ignored (`S:9`, no error for the right space).
  - `r4/probe/Checked.java`: a switch of literals as an index now passes, like the conditional.
- [x] Casts (focus 2): looking through a cast is sound, because a reference cast never changes the object, so its positions are its source's. No false positive was found:
  - an `ArrayList` cast of a `List` keeps the space;
  - a cast of an array element or a method result resolves to `NONE` as before;
  - `(Object) chunks` as a switch selector is not a handover.

  The only new rejections are handovers into `Object`/`Iterable`/type-variable targets inside the set, which the design now states (`L:13` `id(chunks)` reports on `t`).
- [x] Round-4 probes:
  - Newly caught: `L:9/10/11/13/14/15/16/20–23/28`, `P:14/16/17/28`, `V:8`.
  - Still silent, and consistent with the design:
    - `subList` (`L:12`) and `reversed` (`V:7`), which carry no space by design;
    - holders (`L:17/18`, `V:9`, `P:42`);
    - the lambda return (`L:19`).
- [x] `class.unchecked` (focus 3):
  - Reported for:
    - a local class with a method, including an empty or explicit constructor (`K:25`);
    - a local interface with a default method (`K:11`);
    - a local record with a compact constructor (`K:13`);
    - an anonymous class with an instance initializer (`K:15`) or a field initializer (`K:17`);
    - an anonymous class with a method, in a field initializer (`K:19`), a static block (`K:21`) or an instance block (`K:22`);
    - a local class inside a lambda (`K:18`);
    - an enum constant body (`K:23`).
  - Not reported for an empty local class, a class with only an uninitialised field, a bodiless record, an empty interface, `new Object() {}`, or an abstract local class (`K:7,8,10,12,14,16,24`).
- [x] `index-checker-results.md` and the design's Subscripts, Conversion points and Limits sections match the code, apart from the items above:
  - array initializers;
  - local enums;
  - the copy-into and lambda-parameter paths.
