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
                12:23 [subscript.space] index of the wrong column-index space for chunks.
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
                12:32 [subscript.space] index of the wrong column-index space for projectedToOriginal.
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
                12:24 [argument] incompatible argument for parameter referenceOrdinal of Read.fileOrdinal.
                  found   : @ProjectedIndex int
                  required: @OriginalIndex int""");
    }

    /// Integer literals belong to every space, so a counter that starts at `0` is accepted
    /// wherever an index is required. The checker catches mix-ups of indices that came from a
    /// typed source, not of ones computed from a literal.
    @Test
    void literalFitsEverySpace() {
        assertThat(check("""
                String first() {
                    int i = 0;
                    return chunks.get(i) + projectedToOriginal[i] + fileOrdinal(i);
                }
                """)).isEmpty();
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
