/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.tools.columnindex;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Name;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

import org.checkerframework.common.basetype.BaseTypeChecker;
import org.checkerframework.common.basetype.BaseTypeVisitor;
import org.checkerframework.framework.type.AnnotatedTypeMirror;
import org.checkerframework.javacutil.AnnotationBuilder;
import org.checkerframework.javacutil.AnnotationUtils;
import org.checkerframework.javacutil.TreeUtils;

import com.sun.source.tree.ArrayAccessTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MethodInvocationTree;

import dev.hardwood.tools.columnindex.qual.ColumnIndexUnknown;
import dev.hardwood.tools.columnindex.qual.IndexedBy;

/// Adds the subscript rule to the subtyping rules: `array[i]`, `list.get(i)` and
/// `schema.getColumn(i)` on a variable, field or method marked [IndexedBy] require `i` to carry
/// that qualifier.
public final class ColumnIndexVisitor extends BaseTypeVisitor<ColumnIndexAnnotatedTypeFactory> {

    private final AnnotationMirror top;

    public ColumnIndexVisitor(BaseTypeChecker checker) {
        super(checker);
        top = AnnotationBuilder.fromClass(elements, ColumnIndexUnknown.class);
    }

    @Override
    public Void visitArrayAccess(ArrayAccessTree tree, Void p) {
        checkSubscript(tree.getExpression(), tree.getIndex());
        return super.visitArrayAccess(tree, p);
    }

    @Override
    public Void visitMethodInvocation(MethodInvocationTree tree, Void p) {
        ExecutableElement method = TreeUtils.elementFromUse(tree);
        if (isSubscript(method)) {
            ExpressionTree receiver = TreeUtils.getReceiverTree(tree);
            if (receiver != null) {
                checkSubscript(receiver, tree.getArguments().get(0));
            }
        }
        return super.visitMethodInvocation(tree, p);
    }

    /// `List.get(int)` and `FileSchema.getColumn(int)`, with overrides.
    private boolean isSubscript(ExecutableElement method) {
        if (method.getParameters().size() != 1
                || method.getParameters().get(0).asType().getKind() != TypeKind.INT) {
            return false;
        }
        if (method.getSimpleName().contentEquals("get")) {
            return isSubtypeOf(method, "java.util.List");
        }
        if (method.getSimpleName().contentEquals("getColumn")) {
            return isSubtypeOf(method, "dev.hardwood.schema.FileSchema");
        }
        return false;
    }

    private boolean isSubtypeOf(ExecutableElement method, String typeName) {
        TypeElement type = elements.getTypeElement(typeName);
        if (type == null) {
            return false;
        }
        TypeMirror owner = types.erasure(method.getEnclosingElement().asType());
        return types.isSubtype(owner, types.erasure(type.asType()));
    }

    @SuppressWarnings("deprecation") // getElementValueClassName: the class-valued element is read by name
    private void checkSubscript(ExpressionTree container, ExpressionTree index) {
        Element element = TreeUtils.elementFromTree(TreeUtils.withoutParens(container));
        if (element == null) {
            return;
        }
        AnnotationMirror indexedBy = atypeFactory.getDeclAnnotation(element, IndexedBy.class);
        if (indexedBy == null) {
            return;
        }
        Name qualifierName = AnnotationUtils.getElementValueClassName(indexedBy, "value", false);
        AnnotationMirror required = AnnotationBuilder.fromName(elements, qualifierName);
        AnnotatedTypeMirror indexType = atypeFactory.getAnnotatedType(index);
        AnnotationMirror found = indexType.getPrimaryAnnotationInHierarchy(top);
        if (found != null && !qualHierarchy.isSubtypeQualifiersOnly(found, required)) {
            checker.reportError(index, "subscript.space", found, required, container);
        }
    }
}
