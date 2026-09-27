/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.build.errorprone;

import org.junit.jupiter.api.Test;

import com.google.errorprone.CompilationTestHelper;

final class NoUnsafeIntegralNarrowingTest {

    private final CompilationTestHelper compilationHelper = CompilationTestHelper.newInstance(NoUnsafeIntegralNarrowing.class, getClass());

    @Test
    void rejectsLongToIntCast() {
        compilationHelper
                .addSourceLines(
                        "src/main/java/dev/hardwood/Test.java",
                        """
                        package dev.hardwood;
                        final class Test {
                          int test(long value) {
                            // BUG: Diagnostic contains: Do not cast a long to a narrower integral type
                            return (int) value;
                          }
                        }
                        """)
                .doTest();
    }

    @Test
    void rejectsNarrowerThanIntTargets() {
        compilationHelper
                .addSourceLines(
                        "src/main/java/dev/hardwood/Test.java",
                        """
                        package dev.hardwood;
                        final class Test {
                          char test(long value) {
                            // BUG: Diagnostic contains: Do not cast a long to a narrower integral type
                            return (char) value;
                          }
                        }
                        """)
                .doTest();
    }

    @Test
    void rejectsCastOfLongExpression() {
        compilationHelper
                .addSourceLines(
                        "src/main/java/dev/hardwood/Test.java",
                        """
                        package dev.hardwood;
                        final class Test {
                          int test(long header) {
                            // BUG: Diagnostic contains: Do not cast a long to a narrower integral type
                            return (int) (header >> 1);
                          }
                        }
                        """)
                .doTest();
    }

    @Test
    void rejectsCastInAssignmentToOtherNarrowerType() {
        compilationHelper
                .addSourceLines(
                        "src/main/java/dev/hardwood/Test.java",
                        """
                        package dev.hardwood;
                        final class Test {
                          void test(long value) {
                            // BUG: Diagnostic contains: Do not cast a long to a narrower integral type
                            byte first = (byte) value;
                          }
                        }
                        """)
                .doTest();
    }

    @Test
    void rejectsConstantThatDoesNotFit() {
        compilationHelper
                .addSourceLines(
                        "src/main/java/dev/hardwood/Test.java",
                        """
                        package dev.hardwood;
                        final class Test {
                          int test() {
                            // BUG: Diagnostic contains: Do not cast a long to a narrower integral type
                            return (int) 4_000_000_000L;
                          }
                        }
                        """)
                .doTest();
    }

    @Test
    void allowsConstantThatFits() {
        compilationHelper
                .addSourceLines(
                        "src/main/java/dev/hardwood/Test.java",
                        """
                        package dev.hardwood;
                        final class Test {
                          int test() {
                            return (int) 5L;
                          }
                        }
                        """)
                .doTest();
    }

    @Test
    void allowsWideningCasts() {
        compilationHelper
                .addSourceLines(
                        "src/main/java/dev/hardwood/Test.java",
                        """
                        package dev.hardwood;
                        final class Test {
                          long test(int value) {
                            return (long) value;
                          }
                        }
                        """)
                .doTest();
    }

    @Test
    void allowsNarrowingFromSmallerTypes() {
        compilationHelper
                .addSourceLines(
                        "src/main/java/dev/hardwood/Test.java",
                        """
                        package dev.hardwood;
                        final class Test {
                          short test(int value) {
                            return (short) value;
                          }
                        }
                        """)
                .doTest();
    }

    @Test
    void allowsSuppressedCast() {
        compilationHelper
                .addSourceLines(
                        "src/main/java/dev/hardwood/Test.java",
                        """
                        package dev.hardwood;
                        final class Test {
                          @SuppressWarnings("NoUnsafeIntegralNarrowing")
                          int test(long value) {
                            return (int) value;
                          }
                        }
                        """)
                .doTest();
    }

    @Test
    void flagsUnboxedChain() {
        compilationHelper
                .addSourceLines(
                        "src/main/java/dev/hardwood/Test.java",
                        """
                        package dev.hardwood;
                        final class Test {
                          int test(Long value) {
                            // BUG: Diagnostic contains: Do not cast a long to a narrower integral type
                            return (int) (long) value;
                          }
                        }
                        """)
                .doTest();
    }

    @Test
    void allowsConstantFieldThatFits() {
        compilationHelper
                .addSourceLines(
                        "src/main/java/dev/hardwood/Test.java",
                        """
                        package dev.hardwood;
                        final class Test {
                          private static final long LIMIT = 1024L;

                          int test() {
                            return (int) LIMIT;
                          }
                        }
                        """)
                .doTest();
    }

    @Test
    void allowsFoldedArithmeticConstantThatFits() {
        compilationHelper
                .addSourceLines(
                        "src/main/java/dev/hardwood/Test.java",
                        """
                        package dev.hardwood;
                        final class Test {
                          int test() {
                            return (int) (1024L * 2);
                          }
                        }
                        """)
                .doTest();
    }

    @Test
    void flagsMaskedOperandWithoutFittingConstant() {
        compilationHelper
                .addSourceLines(
                        "src/main/java/dev/hardwood/Test.java",
                        """
                        package dev.hardwood;
                        final class Test {
                          int test(long base) {
                            // BUG: Diagnostic contains: Do not cast a long to a narrower integral type
                            return (int) (base & 0xFFFF_FFFFL);
                          }
                        }
                        """)
                .doTest();
    }
}
