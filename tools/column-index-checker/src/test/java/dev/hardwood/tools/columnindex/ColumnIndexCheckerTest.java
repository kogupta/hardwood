/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.tools.columnindex;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/// Compiles small sources that mix up column indices and checks the errors the checker reports.
/// The sources follow the shape of the multi-file read path: a reference-schema ordinal has to
/// pass through a per-file mapping before it may pick a column chunk of that file.
class ColumnIndexCheckerTest {

    @TempDir
    static Path classes;

    /// `FileSchema` as the checker sees it: a container of columns picked by position.
    private static final String FILE_SCHEMA = """
            package dev.hardwood.schema;
            import java.util.List;
            public class FileSchema {
                public native List<String> getColumns();
                public native String getColumn(int index);
            }
            """;

    private static final String HEADER = """
            import java.util.ArrayList;
            import java.util.Arrays;
            import java.util.Collections;
            import java.util.List;
            import java.util.Objects;
            import dev.hardwood.schema.FileSchema;
            import dev.hardwood.tools.columnindex.qual.FileOrdinal;
            import dev.hardwood.tools.columnindex.qual.IndexedBy;
            import dev.hardwood.tools.columnindex.qual.OriginalIndex;
            import dev.hardwood.tools.columnindex.qual.ProjectedIndex;
            class Read {
                @IndexedBy(FileOrdinal.class) List<String> chunks;
                @IndexedBy(OriginalIndex.class) int[] projectedToOriginal;
                native @FileOrdinal int fileOrdinal(@OriginalIndex int referenceOrdinal);
                native @OriginalIndex int toOriginal(@ProjectedIndex int projected);
                native void readChunks(@IndexedBy(FileOrdinal.class) List<String> fileChunks);
            """;

    @Test
    void mappedOrdinalPicksChunk() {
        assertThat(check("""
                String chunk(@OriginalIndex int original) {
                    return chunks.get(fileOrdinal(original));
                }
                """)).isEmpty();
    }

    @Test
    void referenceOrdinalPicksChunkOfOtherFile() {
        assertThat(check("""
                String chunk(@OriginalIndex int original) {
                    return chunks.get(original);
                }
                """)).containsExactly("""
                18:23 [subscript.space] index of the wrong column-index space for chunks.
                  found   : @OriginalIndex
                  required: @FileOrdinal""");
    }

    @Test
    void projectedIndexSubscriptsOriginalArray() {
        assertThat(check("""
                int column(@ProjectedIndex int projected) {
                    return projectedToOriginal[projected];
                }
                """)).containsExactly("""
                18:32 [subscript.space] index of the wrong column-index space for projectedToOriginal.
                  found   : @ProjectedIndex
                  required: @OriginalIndex""");
    }

    @Test
    void projectedIndexPassedAsOriginal() {
        assertThat(check("""
                int file(@ProjectedIndex int projected) {
                    return fileOrdinal(projected);
                }
                """)).containsExactly("""
                18:24 [argument] incompatible argument for parameter referenceOrdinal of Read.fileOrdinal.
                  found   : @ProjectedIndex int
                  required: @OriginalIndex int""");
    }

    @Test
    void literalFitsEverySpace() {
        assertThat(check("""
                String first() {
                    return chunks.get(0) + projectedToOriginal[0] + fileOrdinal(-1);
                }
                """)).isEmpty();
    }

    @Test
    void aliasKeepsSpace() {
        assertThat(check("""
                String chunk(@OriginalIndex int original) {
                    @IndexedBy(FileOrdinal.class) List<String> alias = chunks;
                    readChunks(alias);
                    readChunks(new ArrayList<>());
                    return alias.get(fileOrdinal(original));
                }
                """)).isEmpty();
    }

    @Test
    void aliasWithoutSpace() {
        assertThat(check("""
                String chunk(@OriginalIndex int original) {
                    List<String> alias = chunks;
                    return alias.get(original);
                }
                """)).containsExactly("""
                18:18 [indexedby.handover] alias is indexed by another column-index space than the value handed to it.
                  found   : @IndexedBy(FileOrdinal.class)
                  required: no @IndexedBy""");
    }

    @Test
    void argumentOfOtherSpace() {
        assertThat(check("""
                void read(@IndexedBy(OriginalIndex.class) List<String> referenceChunks) {
                    readChunks(referenceChunks);
                }
                """)).containsExactly("""
                18:16 [indexedby.handover] fileChunks is indexed by another column-index space than the value handed to it.
                  found   : @IndexedBy(OriginalIndex.class)
                  required: @IndexedBy(FileOrdinal.class)""");
    }

    @Test
    void fieldAssignedFromUnmarkedList() {
        assertThat(check("""
                void replace(List<String> other) {
                    chunks = other;
                }
                """)).containsExactly("""
                18:12 [indexedby.handover] chunks is indexed by another column-index space than the value handed to it.
                  found   : no @IndexedBy
                  required: @IndexedBy(FileOrdinal.class)""");
    }

    @Test
    void returnOfOtherSpace() {
        assertThat(check("""
                @IndexedBy(OriginalIndex.class) List<String> referenceChunks() {
                    return chunks;
                }
                """)).containsExactly("""
                18:5 [indexedby.handover] referenceChunks() is indexed by another column-index space than the value handed to it.
                  found   : @IndexedBy(FileOrdinal.class)
                  required: @IndexedBy(OriginalIndex.class)""");
    }

    @Test
    void setWithWrongSpace() {
        assertThat(check("""
                void replace(@OriginalIndex int original) {
                    chunks.set(original, "x");
                }
                """)).containsExactly("""
                18:16 [subscript.space] index of the wrong column-index space for chunks.
                  found   : @OriginalIndex
                  required: @FileOrdinal""");
    }

    @Test
    void castIntoSpace() {
        assertThat(check("""
                @FileOrdinal int file(int ordinal) {
                    return (@FileOrdinal int) ordinal;
                }
                """)).containsExactly("""
                18:12 [cast.unsafe] cast from "@ColumnIndexUnknown int" to "@FileOrdinal int" cannot be statically verified""");
    }

    @Test
    void indexedByNamesOtherAnnotation() {
        assertThat(check("""
                @IndexedBy(Deprecated.class) List<String> other;
                void replace() {
                    other = new ArrayList<>();
                }
                """)).containsExactly("""
                17:43 [indexedby.invalid] @IndexedBy names java.lang.Deprecated, which is not a column-index space""");
    }

    @Test
    void counterOfChunkSpace() {
        assertThat(check("""
                String all() {
                    String all = "";
                    for (@FileOrdinal int f = 0; f < chunks.size(); f++) {
                        all += chunks.get(f);
                    }
                    return all;
                }
                """)).isEmpty();
    }

    @Test
    void markedCounterPicksChunk() {
        assertThat(check("""
                String chunk(int count) {
                    String all = "";
                    for (@ProjectedIndex int p = 0; p < count; p++) {
                        all += chunks.get(p) + fileOrdinal(p);
                    }
                    return all;
                }
                """)).containsExactly("""
                20:27 [subscript.space] index of the wrong column-index space for chunks.
                  found   : @ProjectedIndex
                  required: @FileOrdinal""",
                """
                20:44 [argument] incompatible argument for parameter referenceOrdinal of Read.fileOrdinal.
                  found   : @ProjectedIndex int
                  required: @OriginalIndex int""");
    }

    @Test
    void unmarkedCounterPicksChunk() {
        assertThat(check("""
                String chunk(int count) {
                    String all = "";
                    for (int p = 0; p < count; p++) {
                        all += chunks.get(p) + fileOrdinal(p);
                    }
                    return all;
                }
                """)).containsExactly("""
                20:27 [subscript.space] index of the wrong column-index space for chunks.
                  found   : @ColumnIndexUnknown
                  required: @FileOrdinal""",
                """
                20:44 [argument] incompatible argument for parameter referenceOrdinal of Read.fileOrdinal.
                  found   : @ColumnIndexUnknown int
                  required: @OriginalIndex int""");
    }

    @Test
    void columnsOfSchemaKeepSpace() {
        assertThat(check("""
                String column(@IndexedBy(FileOrdinal.class) FileSchema schema, @OriginalIndex int original) {
                    @IndexedBy(OriginalIndex.class) List<String> columns = schema.getColumns();
                    return schema.getColumns().get(original);
                }
                """)).containsExactly("""
                18:50 [indexedby.handover] columns is indexed by another column-index space than the value handed to it.
                  found   : @IndexedBy(FileOrdinal.class)
                  required: @IndexedBy(OriginalIndex.class)""",
                """
                19:36 [subscript.space] index of the wrong column-index space for schema.
                  found   : @OriginalIndex
                  required: @FileOrdinal""");
    }

    @Test
    void parameterResetToLiteral() {
        assertThat(check("""
                String chunk(int p) {
                    p = 0;
                    p++;
                    return chunks.get(p);
                }
                """)).containsExactly("""
                20:23 [subscript.space] index of the wrong column-index space for chunks.
                  found   : @ColumnIndexUnknown
                  required: @FileOrdinal""");
    }

    @Test
    void fieldCounter() {
        assertThat(check("""
                int cursor;
                String all(int n) {
                    String all = "";
                    for (cursor = 0; cursor < n; cursor++) {
                        all += chunks.get(cursor);
                    }
                    return all;
                }
                """)).containsExactly("""
                21:27 [subscript.space] index of the wrong column-index space for chunks.
                  found   : @ColumnIndexUnknown
                  required: @FileOrdinal""");
    }

    @Test
    void postfixIncrementAsIndex() {
        assertThat(check("""
                String next() {
                    int i = 0;
                    return chunks.get(i++);
                }
                """)).containsExactly("""
                19:24 [subscript.space] index of the wrong column-index space for chunks.
                  found   : @ColumnIndexUnknown
                  required: @FileOrdinal""");
    }

    @Test
    void namedConstantHasDeclaredSpace() {
        assertThat(check("""
                static final int FIRST = 3;
                static final @FileOrdinal int FIRST_FILE_COLUMN = 3;
                String first() {
                    return chunks.get(FIRST) + chunks.get(FIRST_FILE_COLUMN);
                }
                """)).containsExactly("""
                20:23 [subscript.space] index of the wrong column-index space for chunks.
                  found   : @ColumnIndexUnknown
                  required: @FileOrdinal""");
    }

    @Test
    void conditionalOfSameSpace() {
        assertThat(check("""
                String pick(boolean first) {
                    @FileOrdinal int a = 0;
                    @FileOrdinal int b = 1;
                    return chunks.get(first ? a : b);
                }
                """)).isEmpty();
    }

    @Test
    void copiesKeepSpace() {
        assertThat(check("""
                int copies(@ProjectedIndex int projected) {
                    int[] copy = projectedToOriginal.clone();
                    int[] prefix = Arrays.copyOf(projectedToOriginal, 3);
                    @IndexedBy(OriginalIndex.class) int[] kept = projectedToOriginal.clone();
                    return kept[projected];
                }
                """)).containsExactly("""
                18:11 [indexedby.handover] copy is indexed by another column-index space than the value handed to it.
                  found   : @IndexedBy(OriginalIndex.class)
                  required: no @IndexedBy""",
                """
                19:11 [indexedby.handover] prefix is indexed by another column-index space than the value handed to it.
                  found   : @IndexedBy(OriginalIndex.class)
                  required: no @IndexedBy""",
                """
                21:17 [subscript.space] index of the wrong column-index space for kept.
                  found   : @ProjectedIndex
                  required: @OriginalIndex""");
    }

    @Test
    void copyConstructorKeepsSpace() {
        assertThat(check("""
                void copy() {
                    @IndexedBy(OriginalIndex.class) List<String> relabelled = new ArrayList<>(chunks);
                    readChunks(new ArrayList<>(chunks));
                    readChunks(new ArrayList<>(3));
                }
                """)).containsExactly("""
                18:50 [indexedby.handover] relabelled is indexed by another column-index space than the value handed to it.
                  found   : @IndexedBy(FileOrdinal.class)
                  required: @IndexedBy(OriginalIndex.class)""");
    }

    @Test
    void conditionalOfContainers() {
        assertThat(check("""
                String pick(boolean first, List<String> other, @OriginalIndex int original) {
                    List<String> either = first ? chunks : other;
                    return (first ? chunks : other).get(original);
                }
                """)).containsExactly("""
                18:18 [indexedby.handover] either is indexed by another column-index space than the value handed to it.
                  found   : @IndexedBy(FileOrdinal.class)
                  required: no @IndexedBy""",
                """
                19:41 [subscript.space] index of the wrong column-index space for chunks.
                  found   : @OriginalIndex
                  required: @FileOrdinal""");
    }

    @Test
    void unmarkedParameterOfCheckedMethod() {
        assertThat(check("""
                static String help(List<String> list, int i) {
                    return list.get(i);
                }
                String chunk(@FileOrdinal int f) {
                    return help(chunks, f) + String.join(",", chunks);
                }
                """)).containsExactly("""
                21:17 [indexedby.handover] list is indexed by another column-index space than the value handed to it.
                  found   : @IndexedBy(FileOrdinal.class)
                  required: no @IndexedBy""");
    }

    @Test
    void varargsElementsAreIndices() {
        assertThat(check("""
                native void columns(@IndexedBy(OriginalIndex.class) int... columns);
                void call() {
                    columns(1, 2);
                    columns(projectedToOriginal);
                }
                """)).isEmpty();
    }

    @Test
    void switchOfContainers() {
        assertThat(check("""
                String pick(int n, @OriginalIndex int original) {
                    List<String> either = switch (n) { case 1 -> chunks; default -> { yield chunks; } };
                    return (switch (n) { default -> chunks; }).get(original);
                }
                """)).containsExactly("""
                18:18 [indexedby.handover] either is indexed by another column-index space than the value handed to it.
                  found   : @IndexedBy(FileOrdinal.class)
                  required: no @IndexedBy""",
                """
                19:52 [subscript.space] index of the wrong column-index space for chunks.
                  found   : @OriginalIndex
                  required: @FileOrdinal""");
    }

    @Test
    void switchOfLiterals() {
        assertThat(check("""
                int pick(int n) {
                    return projectedToOriginal[switch (n) { case 1 -> 0; default -> { yield 1; } }];
                }
                """)).isEmpty();
    }

    @Test
    void widenedContainerKeepsSpace() {
        assertThat(check("""
                void widen() {
                    Object any = chunks;
                    Iterable<String> all = chunks;
                }
                """)).containsExactly("""
                18:12 [indexedby.handover] any is indexed by another column-index space than the value handed to it.
                  found   : @IndexedBy(FileOrdinal.class)
                  required: no @IndexedBy""",
                """
                19:22 [indexedby.handover] all is indexed by another column-index space than the value handed to it.
                  found   : @IndexedBy(FileOrdinal.class)
                  required: no @IndexedBy""");
    }

    @Test
    void castKeepsSpace() {
        assertThat(check("""
                String cast(@IndexedBy(FileOrdinal.class) FileSchema schema, @OriginalIndex int original) {
                    List<String> same = (List<String>) (Iterable<String>) chunks;
                    return ((List<String>) (Iterable<String>) schema.getColumns()).get(original);
                }
                """)).containsExactly("""
                18:18 [indexedby.handover] same is indexed by another column-index space than the value handed to it.
                  found   : @IndexedBy(FileOrdinal.class)
                  required: no @IndexedBy""",
                """
                19:72 [subscript.space] index of the wrong column-index space for schema.
                  found   : @OriginalIndex
                  required: @FileOrdinal""");
    }

    @Test
    void viewsAndIdentityKeepSpace() {
        assertThat(check("""
                void views() {
                    List<String> view = Collections.unmodifiableList(chunks);
                    List<String> same = Objects.requireNonNull(chunks);
                    Object[] array = chunks.toArray();
                    List<String> part = chunks.subList(1, 2);
                }
                """)).containsExactly("""
                18:18 [indexedby.handover] view is indexed by another column-index space than the value handed to it.
                  found   : @IndexedBy(FileOrdinal.class)
                  required: no @IndexedBy""",
                """
                19:18 [indexedby.handover] same is indexed by another column-index space than the value handed to it.
                  found   : @IndexedBy(FileOrdinal.class)
                  required: no @IndexedBy""",
                """
                20:14 [indexedby.handover] array is indexed by another column-index space than the value handed to it.
                  found   : @IndexedBy(FileOrdinal.class)
                  required: no @IndexedBy""");
    }

    @Test
    void arrayElementHoldsNoSpace() {
        assertThat(check("""
                void store(List<String>[] lists) {
                    lists[0] = chunks;
                }
                """)).containsExactly("""
                18:14 [indexedby.handover] lists is indexed by another column-index space than the value handed to it.
                  found   : @IndexedBy(FileOrdinal.class)
                  required: no @IndexedBy""");
    }

    @Test
    void typeVariableIndex() {
        assertThat(check("""
                <X extends Integer> int column(X x, boolean first) {
                    return projectedToOriginal[x] + projectedToOriginal[first ? x : 0];
                }
                """)).containsExactly("""
                18:32 [subscript.space] index of the wrong column-index space for projectedToOriginal.
                  found   : @ColumnIndexUnknown
                  required: @OriginalIndex""",
                """
                18:63 [subscript.space] index of the wrong column-index space for projectedToOriginal.
                  found   : @ColumnIndexUnknown
                  required: @OriginalIndex""");
    }

    @Test
    void uncheckedClassAcceptsAnySpace() {
        JavaFileObject helper = source("Helper", """
                import java.util.List;
                class Helper {
                    static String help(List<String> list, int i) {
                        return list.get(i);
                    }
                }
                """);
        assertThat(check("""
                String chunk(@FileOrdinal int f) {
                    return Helper.help(chunks, f);
                }
                """, List.of("-AonlyDefs=^Read$"), helper)).isEmpty();
    }

    @Test
    void skippedClassAcceptsAnySpace() {
        JavaFileObject helper = source("Helper", """
                import java.util.List;
                class Helper {
                    static String help(List<String> list, int i) {
                        return list.get(i);
                    }
                }
                """);
        assertThat(check("""
                String chunk(@FileOrdinal int f) {
                    return Helper.help(chunks, f);
                }
                """, List.of("-AskipDefs=^Helper$"), helper)).isEmpty();
    }

    @Test
    void localAndAnonymousClassesWithCode() {
        assertThat(check("""
                Object local(@OriginalIndex int original) {
                    record Pair(int first, int second) {}
                    class Reader {
                        String read() {
                            return chunks.get(original);
                        }
                    }
                    return new Object() {
                        final String chunk = chunks.get(original);
                    };
                }
                """, List.of("-AonlyDefs=^Read$"))).containsExactly("""
                19:5 [class.unchecked] the column-index checker skips the bodies of local and anonymous classes; move this code into a method or a member class""",
                """
                24:25 [class.unchecked] the column-index checker skips the bodies of local and anonymous classes; move this code into a method or a member class""");
    }

    @Test
    void calleeCompiledFirstIsChecked() {
        JavaFileObject helper = source("Helper", """
                import java.util.List;
                class Helper {
                    static String help(List<String> list) {
                        return list.get(0);
                    }
                }
                """);
        assertThat(check("""
                String chunk() {
                    return Helper.help(chunks);
                }
                """, List.of("-XDcompilePolicy=byTodo"), helper)).containsExactly("""
                18:24 [indexedby.handover] list is indexed by another column-index space than the value handed to it.
                  found   : @IndexedBy(FileOrdinal.class)
                  required: no @IndexedBy""");
    }

    private static List<String> check(String members) {
        return check(members, List.of());
    }

    /// Compiles `members` as the body of class `Read`, after `extraSources`, passing
    /// `checkerOptions` to the checker. Returns the errors reported in `Read`.
    private static List<String> check(String members, List<String> checkerOptions,
            JavaFileObject... extraSources) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        List<JavaFileObject> sources = new ArrayList<>(List.of(extraSources));
        sources.add(source("Read", HEADER + members + "}\n"));
        sources.add(source("dev/hardwood/schema/FileSchema", FILE_SCHEMA));
        List<String> options = new ArrayList<>(List.of("-d", classes.toString(),
                "-classpath", System.getProperty("java.class.path"),
                "-processor", ColumnIndexChecker.class.getName()));
        options.addAll(checkerOptions);
        compiler.getTask(null, null, diagnostics, options, null, sources).call();

        List<String> errors = new ArrayList<>();
        for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
            if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
                assertThat(diagnostic.getSource().getName()).isEqualTo("/Read.java");
                errors.add(diagnostic.getLineNumber() + ":" + diagnostic.getColumnNumber() + " "
                        + diagnostic.getMessage(Locale.ROOT));
            }
        }
        return errors;
    }

    private static JavaFileObject source(String path, String content) {
        return new SimpleJavaFileObject(URI.create("file:///" + path + ".java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return content;
            }
        };
    }
}
