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

    private static final String HEADER = """
            import java.util.ArrayList;
            import java.util.List;
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
                14:23 [subscript.space] index of the wrong column-index space for chunks.
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
                14:32 [subscript.space] index of the wrong column-index space for projectedToOriginal.
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
                14:24 [argument] incompatible argument for parameter referenceOrdinal of Read.fileOrdinal.
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
                14:18 [indexedby.handover] alias is indexed by another column-index space than the value handed to it.
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
                14:16 [indexedby.handover] fileChunks is indexed by another column-index space than the value handed to it.
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
                14:12 [indexedby.handover] chunks is indexed by another column-index space than the value handed to it.
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
                14:5 [indexedby.handover] referenceChunks() is indexed by another column-index space than the value handed to it.
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
                14:16 [subscript.space] index of the wrong column-index space for chunks.
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
                14:12 [cast.unsafe] cast from "@ColumnIndexUnknown int" to "@FileOrdinal int" cannot be statically verified""");
    }

    @Test
    void indexedByNamesOtherAnnotation() {
        assertThat(check("""
                @IndexedBy(Deprecated.class) List<String> other;
                String first() {
                    return other.get(0);
                }
                """)).containsExactly("""
                15:12 [indexedby.invalid] @IndexedBy names java.lang.Deprecated, which is not a column-index space""");
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
                16:27 [subscript.space] index of the wrong column-index space for chunks.
                  found   : @ProjectedIndex
                  required: @FileOrdinal""",
                """
                16:44 [argument] incompatible argument for parameter referenceOrdinal of Read.fileOrdinal.
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
                16:27 [subscript.space] index of the wrong column-index space for chunks.
                  found   : @ColumnIndexUnknown
                  required: @FileOrdinal""",
                """
                16:44 [argument] incompatible argument for parameter referenceOrdinal of Read.fileOrdinal.
                  found   : @ColumnIndexUnknown int
                  required: @OriginalIndex int""");
    }

    private static List<String> check(String members) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        JavaFileObject source = new SimpleJavaFileObject(URI.create("string:///Read.java"),
                JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return HEADER + members + "}\n";
            }
        };
        List<String> options = List.of("-proc:only",
                "-classpath", System.getProperty("java.class.path"),
                "-processor", ColumnIndexChecker.class.getName());
        compiler.getTask(null, null, diagnostics, options, null, List.of(source)).call();

        List<String> errors = new ArrayList<>();
        for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
            if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
                errors.add(diagnostic.getLineNumber() + ":" + diagnostic.getColumnNumber() + " "
                        + diagnostic.getMessage(Locale.ROOT));
            }
        }
        return errors;
    }
}
