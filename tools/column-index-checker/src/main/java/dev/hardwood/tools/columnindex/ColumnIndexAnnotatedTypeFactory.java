/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.tools.columnindex;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;

import org.checkerframework.common.basetype.BaseAnnotatedTypeFactory;
import org.checkerframework.common.basetype.BaseTypeChecker;
import org.checkerframework.framework.type.AnnotatedTypeMirror;
import org.checkerframework.javacutil.AnnotationBuilder;
import org.checkerframework.javacutil.BugInCF;
import org.checkerframework.javacutil.SwitchExpressionScanner.FunctionalSwitchExpressionScanner;
import org.checkerframework.javacutil.TreeUtils;

import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.SwitchExpressionTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.tree.UnaryTree;

import dev.hardwood.tools.columnindex.qual.ColumnIndexBottom;
import dev.hardwood.tools.columnindex.qual.ColumnIndexUnknown;
import dev.hardwood.tools.columnindex.qual.FileOrdinal;
import dev.hardwood.tools.columnindex.qual.OriginalIndex;
import dev.hardwood.tools.columnindex.qual.ProjectedIndex;
import dev.hardwood.tools.columnindex.qual.ProjectedIndexOrAbsent;

/// The column-index qualifier hierarchy.
///
/// Only a value written as a literal, or computed from literals alone (`-1`, `2 * 3`), fits every
/// space. A variable that flow analysis knows holds a literal reads as its declared type instead:
/// a counter such as `for (int i = 0; ...; i++)` belongs to no space unless its declaration names
/// one, and `for (@ProjectedIndex int p = 0; ...; p++)` reads as a projected index throughout the
/// loop. The same holds for parameters, fields, named constants, and for `i++` and `--i`, which
/// read as the declared type of `i`. A conditional or switch expression reads as the least upper
/// bound of its results.
public final class ColumnIndexAnnotatedTypeFactory extends BaseAnnotatedTypeFactory {

    private final AnnotationMirror top;
    private final AnnotationMirror bottom;

    public ColumnIndexAnnotatedTypeFactory(BaseTypeChecker checker) {
        super(checker);
        top = AnnotationBuilder.fromClass(elements, ColumnIndexUnknown.class);
        bottom = AnnotationBuilder.fromClass(elements, ColumnIndexBottom.class);
        postInit();
    }

    @Override
    protected void addComputedTypeAnnotations(Tree tree, AnnotatedTypeMirror type, boolean iUseFlow) {
        super.addComputedTypeAnnotations(tree, type, iUseFlow);
        if (type.getKind().isPrimitive() && type.hasPrimaryAnnotation(bottom)
                && tree instanceof ExpressionTree expression && !isLiteralValue(expression)) {
            type.replaceAnnotation(valueQualifier(expression));
        }
    }

    /// Whether `expression` is written with literals and operators alone.
    private static boolean isLiteralValue(ExpressionTree expression) {
        ExpressionTree value = TreeUtils.withoutParens(expression);
        if (value instanceof LiteralTree) {
            return true;
        }
        if (value instanceof BinaryTree binary) {
            return isLiteralValue(binary.getLeftOperand()) && isLiteralValue(binary.getRightOperand());
        }
        if (value instanceof TypeCastTree cast) {
            return isLiteralValue(cast.getExpression());
        }
        return switch (value.getKind()) {
            case UNARY_MINUS, UNARY_PLUS, BITWISE_COMPLEMENT ->
                    isLiteralValue(((UnaryTree) value).getExpression());
            default -> false;
        };
    }

    /// The qualifier of a value that flow analysis typed as a literal although it is not written
    /// as one.
    private AnnotationMirror valueQualifier(ExpressionTree expression) {
        ExpressionTree value = TreeUtils.withoutParens(expression);
        return switch (value.getKind()) {
            case IDENTIFIER, MEMBER_SELECT -> declaredQualifier(TreeUtils.elementFromTree(value));
            case PREFIX_INCREMENT, PREFIX_DECREMENT, POSTFIX_INCREMENT, POSTFIX_DECREMENT ->
                    declaredQualifier(TreeUtils.elementFromTree(((UnaryTree) value).getExpression()));
            case CONDITIONAL_EXPRESSION -> {
                ConditionalExpressionTree conditional = (ConditionalExpressionTree) value;
                yield resultsQualifier(List.of(conditional.getTrueExpression(), conditional.getFalseExpression()));
            }
            case SWITCH_EXPRESSION -> resultsQualifier(switchResults((SwitchExpressionTree) value));
            default -> top;
        };
    }

    private AnnotationMirror declaredQualifier(Element element) {
        if (element == null || !element.getKind().isVariable()) {
            return top;
        }
        AnnotationMirror declared = fromElement(element).getPrimaryAnnotationInHierarchy(top);
        return declared == null ? top : declared;
    }

    private AnnotationMirror resultsQualifier(List<ExpressionTree> results) {
        AnnotationMirror bound = null;
        for (ExpressionTree result : results) {
            AnnotationMirror qualifier = qualifier(result);
            bound = bound == null ? qualifier
                    : getQualifierHierarchy().leastUpperBoundQualifiersOnly(bound, qualifier);
            if (bound == null) {
                throw new BugInCF("no least upper bound of the results of %s", results);
            }
        }
        return bound == null ? top : bound;
    }

    /// The expressions a switch expression can yield: the body of each `->` case that is an
    /// expression, and the value of each `yield`.
    static List<ExpressionTree> switchResults(SwitchExpressionTree switchExpression) {
        List<ExpressionTree> results = new ArrayList<>();
        new FunctionalSwitchExpressionScanner<Void, Void>((result, p) -> {
            results.add(result);
            return null;
        }, (first, second) -> null).scanSwitchExpression(switchExpression, null);
        return results;
    }

    private AnnotationMirror qualifier(ExpressionTree expression) {
        AnnotationMirror qualifier = getAnnotatedType(expression).getAnnotationInHierarchy(top);
        if (qualifier == null) {
            throw new BugInCF("no column-index qualifier on %s", expression);
        }
        return qualifier;
    }

    @Override
    protected Set<Class<? extends Annotation>> createSupportedTypeQualifiers() {
        return getBundledTypeQualifiers(ColumnIndexUnknown.class, OriginalIndex.class,
                ProjectedIndexOrAbsent.class, ProjectedIndex.class, FileOrdinal.class,
                ColumnIndexBottom.class);
    }
}
