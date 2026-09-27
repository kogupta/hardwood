/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.tools.columnindex;

import java.net.URI;
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

import static org.assertj.core.api.Assertions.assertThat;

/// Compiles small sources that mix up column indices and checks the errors the checker reports.
/// The sources follow the shape of the multi-file read path: a reference-schema ordinal has to
/// pass through a per-file mapping before it may pick a column chunk of that file.
class ColumnIndexCheckerTest {

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
            import java.util.List;
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
                16:23 [subscript.space] index of the wrong column-index space for chunks.
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
                16:32 [subscript.space] index of the wrong column-index space for projectedToOriginal.
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
                16:24 [argument] incompatible argument for parameter referenceOrdinal of Read.fileOrdinal.
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
                16:18 [indexedby.handover] alias is indexed by another column-index space than the value handed to it.
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
                16:16 [indexedby.handover] fileChunks is indexed by another column-index space than the value handed to it.
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
                16:12 [indexedby.handover] chunks is indexed by another column-index space than the value handed to it.
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
                16:5 [indexedby.handover] referenceChunks() is indexed by another column-index space than the value handed to it.
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
                16:16 [subscript.space] index of the wrong column-index space for chunks.
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
                16:12 [cast.unsafe] cast from "@ColumnIndexUnknown int" to "@FileOrdinal int" cannot be statically verified""");
    }

    @Test
    void indexedByNamesOtherAnnotation() {
        assertThat(check("""
                @IndexedBy(Deprecated.class) List<String> other;
                void replace() {
                    other = new ArrayList<>();
                }
                """)).containsExactly("""
                15:43 [indexedby.invalid] @IndexedBy names java.lang.Deprecated, which is not a column-index space""");
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
                18:27 [subscript.space] index of the wrong column-index space for chunks.
                  found   : @ProjectedIndex
                  required: @FileOrdinal""",
                """
                18:44 [argument] incompatible argument for parameter referenceOrdinal of Read.fileOrdinal.
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
                18:27 [subscript.space] index of the wrong column-index space for chunks.
                  found   : @ColumnIndexUnknown
                  required: @FileOrdinal""",
                """
                18:44 [argument] incompatible argument for parameter referenceOrdinal of Read.fileOrdinal.
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
                16:50 [indexedby.handover] columns is indexed by another column-index space than the value handed to it.
                  found   : @IndexedBy(FileOrdinal.class)
                  required: @IndexedBy(OriginalIndex.class)""",
                """
                17:36 [subscript.space] index of the wrong column-index space for schema.
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
                18:23 [subscript.space] index of the wrong column-index space for chunks.
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
                19:27 [subscript.space] index of the wrong column-index space for chunks.
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
                17:24 [subscript.space] index of the wrong column-index space for chunks.
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
                18:23 [subscript.space] index of the wrong column-index space for chunks.
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
                16:11 [indexedby.handover] copy is indexed by another column-index space than the value handed to it.
                  found   : @IndexedBy(OriginalIndex.class)
                  required: no @IndexedBy""",
                """
                17:11 [indexedby.handover] prefix is indexed by another column-index space than the value handed to it.
                  found   : @IndexedBy(OriginalIndex.class)
                  required: no @IndexedBy""",
                """
                19:17 [subscript.space] index of the wrong column-index space for kept.
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
                16:50 [indexedby.handover] relabelled is indexed by another column-index space than the value handed to it.
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
                16:18 [indexedby.handover] either is indexed by another column-index space than the value handed to it.
                  found   : @IndexedBy(FileOrdinal.class)
                  required: no @IndexedBy""",
                """
                17:41 [subscript.space] index of the wrong column-index space for chunks.
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
                19:17 [indexedby.handover] list is indexed by another column-index space than the value handed to it.
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

    private static List<String> check(String members) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        List<JavaFileObject> sources = List.of(source("Read", HEADER + members + "}\n"),
                source("dev/hardwood/schema/FileSchema", FILE_SCHEMA));
        List<String> options = List.of("-proc:only",
                "-classpath", System.getProperty("java.class.path"),
                "-processor", ColumnIndexChecker.class.getName());
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
        return new SimpleJavaFileObject(URI.create("string:///" + path + ".java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return content;
            }
        };
    }
}
