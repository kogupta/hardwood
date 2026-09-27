/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.build.errorprone;

import java.util.Set;

import javax.lang.model.type.TypeKind;

import com.google.auto.service.AutoService;
import com.google.errorprone.BugPattern;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeCastTree;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.tree.JCTree.JCPrimitiveTypeTree;

/// Enforces Hardwood's integral-narrowing rule: a `long` may not be cast to a
/// narrower integral type unless the value is statically known to fit. The
/// preferred rewrite is `Math.toIntExact(longValue)`. This version recognizes
/// only compile-time constants (literals, constant fields, and expressions the
/// compiler folds) as statically fitting; a runtime range check followed by a
/// cast is still flagged — suppress it with
/// `@SuppressWarnings("NoUnsafeIntegralNarrowing")` and a comment stating the
/// bound.
///
/// Scope is deliberately narrow: only casts out of `long` (the file-size /
/// offset domain in this reader) into `int`, `short`, `char`, or `byte`.
/// Narrowing between smaller types (for example `int` to `short`) is not
/// checked, and boxed `Long` operands are skipped.
@AutoService(BugChecker.class)
@BugPattern(
        name = "NoUnsafeIntegralNarrowing",
        summary = "Do not cast a long to a narrower integral type; use Math.toIntExact or make the fit compile-time visible.",
        severity = BugPattern.SeverityLevel.ERROR)
public final class NoUnsafeIntegralNarrowing extends BugChecker implements BugChecker.TypeCastTreeMatcher {

    private static final Set<TypeKind> TARGET_KINDS = Set.of(TypeKind.INT, TypeKind.SHORT, TypeKind.CHAR, TypeKind.BYTE);

    @Override
    public Description matchTypeCast(TypeCastTree tree, VisitorState state) {
        if (!JavaSourceFiles.isConventionalJavaSource(state)) {
            return Description.NO_MATCH;
        }
        TypeKind targetKind = targetKind(tree);
        if (targetKind == null || !narrowingFromLong(tree.getExpression())) {
            return Description.NO_MATCH;
        }
        Object constant = ASTHelpers.constValue(tree.getExpression());
        if (constant instanceof Number number && fits(number.longValue(), targetKind)) {
            return Description.NO_MATCH;
        }
        return describeMatch(tree);
    }

    private static TypeKind targetKind(TypeCastTree tree) {
        if (tree.getType().getKind() != Tree.Kind.PRIMITIVE_TYPE) {
            return null;
        }
        TypeKind kind = ((JCPrimitiveTypeTree) tree.getType()).getPrimitiveTypeKind();
        return TARGET_KINDS.contains(kind) ? kind : null;
    }

    private static boolean narrowingFromLong(Tree expression) {
        Type expressionType = ASTHelpers.getType(expression);
        return expressionType != null && expressionType.getKind() == TypeKind.LONG;
    }

    private static boolean fits(long value, TypeKind targetKind) {
        return switch (targetKind) {
            case INT -> value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE;
            case SHORT -> value >= Short.MIN_VALUE && value <= Short.MAX_VALUE;
            case CHAR -> value >= Character.MIN_VALUE && value <= Character.MAX_VALUE;
            case BYTE -> value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE;
            default -> false;
        };
    }
}
