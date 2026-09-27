# Branch 1283-cf-index-value-spike: Checker Framework Index Checker spike: Review actions

Branch `1283-cf-index-value-spike` at `3d9512eb`, 12 commits on `main` (merge base `9f84f5bb`). No PR, so no CI run to lean on.
Session: 94611bca-b03f-5524-a823-bb0689943d2c

## Summary

**What:** Adds an opt-in `checkerframework` profile that runs the Index Checker over `core`, and adds runtime guards for three malformed-file crashes (out-of-range dictionary indices, negative DELTA lengths, bad DELTA prefixes). It then annotates four pilot classes (`NestedBatchIndex`, `ColumnBatch`, `RecordShredder`, `ProjectedSchema`) plus their cursor callers, and adds a `NoUnsafeIntegralNarrowing` Error Prone check wired at WARN into the default `qa` build. Two documents under `_designs/` record the measurements.

**Why:** Issue #1283: find out whether the Checker Framework can replace internal index re-checks with compile-time proofs while boundary checks stay.

**Assessment:** The three decoder guards and their tests are sound and could land on their own. The annotation half needs work first. It adds a runtime check (`refineProjCol`) at more than 100 call sites on the nested read path with no benchmark, which runs against the stated premise of removing internal checks. One call site is ordered so that its own `-1` guard can never run. The design documents also contradict each other and the pom on several load-bearing facts.

## Decisions

- **Q:** `refineProjCol` adds a compare-and-branch before every nested accessor call, only to mint `@IndexFor("valueCounts")`. The doc says it "never fires". Keep it as a runtime check, or mint the type another way?
  - [ ] **A.** Keep `refineProjCol` as a runtime check, and add JMH or end-to-end numbers for the nested read path to show the cost.
  - [ ] **B.** Make `refineProjCol` a trusted conversion: no branch, `@SuppressWarnings("index")` with a comment naming the invariant (descriptor and batch built from the same `ProjectedSchema`). The checker still sees the type, and the hot path gains nothing.
  - [ ] **C.** Type the descriptor columns at the source (`TopLevelFieldMap.FieldDesc` components) so no bridge is needed. This is a bigger change, because the descriptor and the batch are different objects and `@IndexFor` cannot name a field of another object's array.
  - **Rec:** B. It keeps the spike's result (the cursor layer is typed) without adding the internal checks the premise says should go away. The JVM's own bounds check already catches a real mismatch, and the doc already calls this invariant unbreakable. C is worth a follow-up issue, not this branch.

- **Q:** The `qa` profile now turns on `NoUnsafeIntegralNarrowing` and ten other checks at WARN for every module, which adds about 99 warnings to every default `core` build. Keep them in the default build?
  - [ ] **A.** Keep them in `qa` at WARN, as now.
  - [ ] **B.** Move the eleven `-Xep:*:WARN` flags to an opt-in profile until the mask and clamp escapes the discussion doc lists exist, then promote to ERROR in `qa`.
  - [ ] **C.** Drop the check from this branch and land it in its own PR with the escapes.
  - **Rec:** B. Warnings that nobody acts on train people to ignore the build output. The doc's own conclusion says v1 needs two escapes before it is useful as a gate.

- **Q:** Branch `claude/fervent-ramanujan-ucff0m` (the parallel spike) adds its own `index-check` and `column-index-check` profiles to `core/pom.xml`, with an enforcer rule that makes them exclusive, plus its own copy of the checker-qual dependency. Both branches reconfigure `default-compile`, so running `checkerframework` together with either of those profiles gives an undefined mix of processors and arguments. How should the two land?
  - [ ] **A.** One profile: land `checkerframework` from this branch and fold the other branch's checked-class list and custom column-index checker into it later.
  - [ ] **B.** Land the other branch's profiles and port this branch's four pilot classes into them; drop `checkerframework`.
  - [ ] **C.** Keep both, and extend the enforcer rule so all three profiles are mutually exclusive.
  - **Rec:** A, with the `checker-framework.version` property moved to the parent `pom.xml` so both checkers share one version and one checker-qual declaration. Two profiles that check the same module with the same tool double the setup to maintain.

## Implementation semantics

- [ ] `PqStructImpl.isStructNull` calls `batch.refineProjCol(structDesc.firstLeafProjCol())` and only then checks `if (leafCol < 0) return false`. `refineProjCol` throws `IllegalStateException` on a negative column, so the documented `-1` ("no leaf is projected", `TopLevelFieldMap.FieldDesc.Struct` JavaDoc) now throws instead of returning `false`, and the `< 0` branch is dead. Move the refine after the guard, as `NestedBatchDataView.isStructNull` already does.
- [ ] `refineProjCol` is evaluated two to four times for the same column within one expression: `PqStructImpl.readValueImpl` and `isFieldNull`, `NestedBatchDataView.readValueImpl` and `isFieldNull`, and the `VariantShredReassembler` def-level/value-index pairs. Refine once into a local, as the `getInt`-style accessors do. This applies whichever option the first Decision picks, because each call is also a separate proof site.
- [ ] No performance numbers for the new work on hot paths: the `refineProjCol` branch on every nested accessor, the extra full pass over `indices` in `checkDictionaryIndices` before each all-present dictionary SIMD dispatch, and the per-element compare in the def-level loops. The first two touch `internal/reader` and `internal/encoding`, which the checklist treats as hot. Run the dictionary and nested-read benchmarks under `performance-testing/`, or state the before/after.
- [ ] `NoUnsafeIntegralNarrowing`'s class JavaDoc says "an explicit range check followed by the cast also qualifies", and its `summary` says "use Math.toIntExact or a proven range check". The matcher only exempts compile-time constants that fit, so a range-checked cast is still flagged. Either fix the JavaDoc and summary, or implement the exemption and add a test for it.

- [ ] `ProjectedSchema` declares `int @IndexFor("originalToProjected") [] projectedToOriginal` and `int @IndexOrLow("projectedToOriginal") [] originalToProjected` (fields and constructor parameters). In that position the annotation applies to the array, not to its elements. Checker Framework 4.2.3 rejects it with `anno.on.irrelevant` (hidden as a warning under `-Awarns`), and a read such as `b[onArray[i]]` then fails with `array.access.unsafe.low` and `.high`. The element form `@IndexFor("originalToProjected") int[]` checks clean on the same probe. Fix the spelling and re-measure: the "+14 element-wise gap" in the spike doc is likely this mistake, not a checker limit.
- [ ] The `-AskipDefs` pattern is not anchored. Checker Framework matches it with `Matcher.find()` (`SourceChecker.shouldSkipDefs`), so `dev\.hardwood\.reader\.ColumnReader` also skips `dev.hardwood.reader.ColumnReaders`. The pom comment and spike doc both say "the rest of the module stays checked" and "nothing else". Anchor it: `^(dev\.hardwood\.reader\.ColumnReader|dev\.hardwood\.reader\.SelectionEngine|dev\.hardwood\.internal\.reader\.FlyweightFormatter)$`.
- [ ] The `checkerframework` profile compiles into the default `target/classes`. After a plain `./mvnw compile`, a `-Pcheckerframework` run with no source change prints "Nothing to compile" and checks nothing, with a green build. Give the profile its own output directory or clean the classes first, so a run always checks the full set.
- [ ] The `BugInCF` claim (`ElementAnnotationApplier`, `ElementKind.BINDING_VARIABLE`) is the only reason three classes are skipped. A minimal probe with `if (o instanceof int[] a) return a.length;` under the Index Checker 4.2.3 and JDK 25 compiles without a crash. Record the exact reproducer (the smallest file that crashes, and the stack trace) in the spike doc, or the skip cannot be re-tested and the upstream report cannot be filed.

## Documentation

- [ ] `_designs/CHECKER_FRAMEWORK_SPIKE.md` no longer matches the branch:
  - wiring item 2 says `checker-qual` is `provided`, profile-scoped;
  - "Guard stage results" item 2 says the dependency "was not needed";
  - `core/pom.xml` now declares `checker-qual` as a project-level `provided` + `optional` dependency, and the `checker-framework.version` property comment still says it is "referenced only by the checkerframework profile".

  Update the doc and the pom comment to the shipped state.
- [ ] The warning and test numbers across the two docs disagree without explanation:
  - the spike doc's baseline is 2,825, then 2,812, and its verdict says to trend CI against 2,812;
  - the discussion doc's Experiment 2 baseline is 2,928, and its Question 2 again says "trend the 2,812 baseline";
  - the spike doc reports 1,042 JVM unit tests, the discussion doc 13,994.

  State which baseline a CI job should trend against, and why 2,928 differs.
- [ ] Both `_designs/` files narrate process instead of describing an end state, which CLAUDE.md forbids for design docs. Examples: "Two deviations from the reviewed plan, both discovered during implementation", "one cold-cache profile run from the first wiring attempt", "revising the spike's recorded deviation", "a test caught the first strict version", and repeated references to a "prior note" and its section numbers that exist nowhere in the repository. The spike doc also says "the adopt/stop verdict lands here when the spike completes" while its status is Completed and it carries a verdict.
- [ ] `_designs/DISCUSSION_CF_AS_TYPE_SYSTEM.md` opens with "Draft issue: not yet filed to a tracker". A draft issue is not a design doc. File it as the issue, or move it out of `_designs/`. CLAUDE.md asks for the issue to exist before the work references it.

- [ ] The spike doc's "Known limits" says element-wise array validity "is inexpressible", and its next steps say the element-wise annotation "should not be planned around". The probe above shows `@IndexFor("b") int[] onElement` is accepted and makes `b[onElement[i]]` check clean, at least for field-to-field relations. Rewrite that limit after the `ProjectedSchema` fix, stating what was tested.
- [ ] "Coexistence with Error Prone is free" needs a caveat. When Error Prone reports an error in the same javac run, the Index Checker's output is not printed at all (seen with 4.2.3 on the parallel spike). A combined CI job therefore shows no checker findings on any build that also has an Error Prone error.
- [ ] `_designs/DISCUSSION_CF_AS_TYPE_SYSTEM.md`, "Open question: would custom types eliminate more checks?", has data on the parallel spike branch `claude/fervent-ramanujan-ucff0m`: a custom column-index checker with 42 tests and canary files for the #903 bug class (see `_designs/INDEX_CHECKER.md` there). Link to it, or state the answer.

## Tests

- [ ] The three new boundary checks have no test: the `NestedBatchIndex` schema-count `IllegalArgumentException`, the `ColumnBatch` constructor range-count check, and the `RecordShredder.bind` length check. Each one has a full message that can be pinned with `hasMessage(...)`.
- [ ] No test covers a struct whose `firstLeafProjCol` is `-1` reaching `PqStructImpl.isStructNull`. Add one with the ordering fix above, or, if descriptor construction makes `-1` unreachable there, drop the dead branch and the `-1` wording from the record's JavaDoc.

## Nits

- [ ] Commit bodies of `f7e53d19` and `ded154dd` cite "Round-1 implementation review IR-001 … IR-005", IDs that exist in no file in the repository. `93cb80f4` says "established by a minimal probe". Neither helps a later reader of the diff (CLAUDE.md: drop ephemeral minutiae).
- [ ] `refineProjCol`'s `IllegalStateException` message leaves out the file name, although `NestedBatchIndex` holds `fileName` for exactly this purpose (the checklist's "include file name in all exceptions" rule, #90).
- [ ] `checker-qual` is `provided` + `optional`, so the annotations stay in the compiled class files but the jar is not on a consumer's classpath. A consumer building with `-Xlint:all -Werror` may then fail on `[classfile]` warnings for the missing annotation types. The parallel spike saw exactly this for its own qualifier; for `checker-qual` it has not been probed. Compile a small consumer against the packaged jar with `-Xlint:all -Werror` before merging.

## Open items shared with the parallel spike

These are not defects in this branch. Both spikes leave them open, and whichever lands first should carry them as follow-up issues.

- [ ] Neither branch has run the full `./mvnw verify` with Docker, so the S3 and parquet-java integration tests are unverified for both.
- [ ] The filter path is outside both checked sets: `PageFilterEvaluator` (around line 94), `RowGroupFilterEvaluator` (around lines 296 and 303), `MinMaxStats` (around line 160).
- [ ] `FlatRowReader`, `NestedRowReader`, `TopLevelFieldMap`, `ShredLevel`, `SelectionEngine` and `ColumnReaders` are not checked on either branch (two of them only because of the skip above).
- [ ] #525 (field index used where a leaf index is expected) is the bug class a custom qualifier would catch; the Index Checker alone cannot, because both are plain valid `int` indices.
- [ ] `RecordFilterEventTest.multiFileColumnReaderReportsOneEventPerFile` failed once and passed on re-run on the parallel spike; not yet root-caused.

## How this review was checked

- Probes run with the Index Checker 4.2.3 on JDK 25, outside Maven: the `ProjectedSchema` spelling (confirmed as above) and the binding-variable crash (not reproduced).
- The `-AskipDefs` finding is read from the Checker Framework 4.2.3 source, not run on this branch.
- Diff audit: the only new `@SuppressWarnings` is in the Error Prone check's own test; no `var`, no `/** */` JavaDoc, no FQNs, no plugin `<version>` in module POMs, no `hasMessageContaining`. The commits remove no runtime checks.
- The branch changes nothing under `docs/content/`, so the PR build's prose check has nothing to flag. Run by hand on the two `_designs/` files, it passes the spike doc and reports the discussion doc's five reader questions (expected in a draft issue) and 16.9 em dashes per 1,000 words (limit 15).
- Not run: a canary (a planted out-of-range access in `NestedBatchIndex`) under `-Pcheckerframework`, with and without `-Dquick`.
