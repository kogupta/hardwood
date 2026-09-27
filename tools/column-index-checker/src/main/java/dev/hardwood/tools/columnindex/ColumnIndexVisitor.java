/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.tools.columnindex;

import java.util.List;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Name;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

import org.checkerframework.common.basetype.BaseTypeChecker;
import org.checkerframework.common.basetype.BaseTypeVisitor;
import org.checkerframework.framework.type.AnnotatedTypeMirror;
import org.checkerframework.javacutil.AnnotationBuilder;
import org.checkerframework.javacutil.AnnotationUtils;
import org.checkerframework.javacutil.BugInCF;
import org.checkerframework.javacutil.TreePathUtil;
import org.checkerframework.javacutil.TreeUtils;

import com.sun.source.tree.ArrayAccessTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.tree.VariableTree;

import dev.hardwood.tools.columnindex.qual.ColumnIndexUnknown;
import dev.hardwood.tools.columnindex.qual.IndexedBy;

/// Adds two rules to the subtyping rules.
///
/// - **Subscripts.** On a variable, field, parameter or method marked [IndexedBy], every `int`
///   argument of `array[i]`, of a `java.util.List` method taking a position (`get`, `set`,
///   `add`, `remove`, `listIterator`, `subList`) and of `FileSchema.getColumn(int)` must carry
///   the named qualifier.
/// - **Handovers.** An array, list or `FileSchema` assigned, passed or returned keeps its
///   [IndexedBy]: source and target must name the same space. A freshly allocated one fits any
///   target. A value passed to a parameter without [IndexedBy] is not checked.
///
/// A cast into a space is an error rather than a warning, so every conversion point carries a
/// visible suppression.
public final class ColumnIndexVisitor extends BaseTypeVisitor<ColumnIndexAnnotatedTypeFactory> {

    private static final String NONE = "none";

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
                List<? extends VariableElement> parameters = method.getParameters();
                for (int i = 0; i < parameters.size(); i++) {
                    if (parameters.get(i).asType().getKind() == TypeKind.INT) {
                        checkSubscript(receiver, tree.getArguments().get(i));
                    }
                }
            }
        }
        checkArguments(method, tree.getArguments());
        return super.visitMethodInvocation(tree, p);
    }

    @Override
    public Void visitNewClass(NewClassTree tree, Void p) {
        checkArguments(TreeUtils.elementFromUse(tree), tree.getArguments());
        return super.visitNewClass(tree, p);
    }

    @Override
    public Void visitAssignment(AssignmentTree tree, Void p) {
        Element target = TreeUtils.elementFromTree(tree.getVariable());
        if (target != null && tree.getVariable().getKind() != Tree.Kind.ARRAY_ACCESS) {
            checkHandover(tree.getExpression(), target, space(target), tree);
        }
        return super.visitAssignment(tree, p);
    }

    @Override
    public Void visitVariable(VariableTree tree, Void p) {
        if (tree.getInitializer() != null) {
            VariableElement target = TreeUtils.elementFromDeclaration(tree);
            if (target != null) {
                checkHandover(tree.getInitializer(), target, space(target), tree);
            }
        }
        return super.visitVariable(tree, p);
    }

    @Override
    public Void visitReturn(ReturnTree tree, Void p) {
        Tree enclosing = TreePathUtil.enclosingMethodOrLambda(getCurrentPath());
        if (tree.getExpression() != null && enclosing instanceof MethodTree method) {
            ExecutableElement target = TreeUtils.elementFromDeclaration(method);
            if (target != null) {
                checkHandover(tree.getExpression(), target, space(target), tree);
            }
        }
        return super.visitReturn(tree, p);
    }

    @Override
    protected void checkTypecastSafety(TypeCastTree typeCastTree) {
        AnnotatedTypeMirror castType = atypeFactory.getAnnotatedType(typeCastTree);
        AnnotatedTypeMirror exprType = atypeFactory.getAnnotatedType(typeCastTree.getExpression());
        if (!isTypeCastSafe(castType, exprType)) {
            checker.reportError(typeCastTree, "cast.unsafe", exprType.toString(true),
                    castType.toString(true));
        }
    }

    /// `List` methods and `FileSchema.getColumn`, with overrides, that take a position.
    private boolean isSubscript(ExecutableElement method) {
        String name = method.getSimpleName().toString();
        return switch (name) {
            case "get", "set", "add", "remove", "listIterator", "subList" ->
                    isMemberOf(method, "java.util.List");
            case "getColumn" -> isMemberOf(method, "dev.hardwood.schema.FileSchema");
            default -> false;
        };
    }

    private boolean isMemberOf(ExecutableElement method, String typeName) {
        TypeElement type = elements.getTypeElement(typeName);
        if (type == null) {
            return false;
        }
        TypeMirror owner = types.erasure(method.getEnclosingElement().asType());
        return types.isSubtype(owner, types.erasure(type.asType()));
    }

    /// Arguments handed to parameters marked [IndexedBy].
    private void checkArguments(ExecutableElement method, List<? extends ExpressionTree> arguments) {
        List<? extends VariableElement> parameters = method.getParameters();
        int count = Math.min(parameters.size(), arguments.size());
        for (int i = 0; i < count; i++) {
            String required = space(parameters.get(i));
            if (!required.equals(NONE)) {
                checkHandover(arguments.get(i), parameters.get(i), required, arguments.get(i));
            }
        }
    }

    private void checkHandover(ExpressionTree source, Element target, String required, Tree reportAt) {
        if (!isContainer(target.getKind().isExecutable()
                ? ((ExecutableElement) target).getReturnType() : target.asType())) {
            return;
        }
        ExpressionTree value = TreeUtils.withoutParens(source);
        if (value.getKind() == Tree.Kind.NEW_ARRAY || value.getKind() == Tree.Kind.NEW_CLASS
                || TreeUtils.isNullExpression(value)) {
            return;
        }
        Element sourceElement = value.getKind() == Tree.Kind.ARRAY_ACCESS
                ? null : TreeUtils.elementFromTree(value);
        String found = sourceElement == null ? NONE : space(sourceElement);
        if (!found.equals(required)) {
            checker.reportError(reportAt, "indexedby.handover", display(found), display(required),
                    target);
        }
    }

    /// Arrays, `java.util.List` and `FileSchema`: the types [IndexedBy] applies to.
    private boolean isContainer(TypeMirror type) {
        if (type.getKind() == TypeKind.ARRAY) {
            return true;
        }
        if (type.getKind() != TypeKind.DECLARED) {
            return false;
        }
        for (String name : List.of("java.util.List", "dev.hardwood.schema.FileSchema")) {
            TypeElement container = elements.getTypeElement(name);
            if (container != null
                    && types.isSubtype(types.erasure(type), types.erasure(container.asType()))) {
                return true;
            }
        }
        return false;
    }

    /// The qualifier name in `element`'s [IndexedBy], or [#NONE].
    @SuppressWarnings("deprecation") // getElementValueClassName: the class-valued element is read by name
    private String space(Element element) {
        AnnotationMirror indexedBy = atypeFactory.getDeclAnnotation(element, IndexedBy.class);
        if (indexedBy == null) {
            return NONE;
        }
        Name qualifierName = AnnotationUtils.getElementValueClassName(indexedBy, "value", false);
        return qualifierName.toString();
    }

    private static String display(String space) {
        if (space.equals(NONE)) {
            return "no @IndexedBy";
        }
        return "@IndexedBy(" + space.substring(space.lastIndexOf('.') + 1) + ".class)";
    }

    private void checkSubscript(ExpressionTree container, ExpressionTree index) {
        Element element = TreeUtils.elementFromTree(TreeUtils.withoutParens(container));
        if (element == null) {
            return;
        }
        String space = space(element);
        if (space.equals(NONE)) {
            return;
        }
        AnnotationMirror required = AnnotationBuilder.fromName(elements, space);
        if (required == null || !atypeFactory.isSupportedQualifier(required)
                || AnnotationUtils.areSame(required, top)) {
            checker.reportError(container, "indexedby.invalid", space);
            return;
        }
        AnnotationMirror found = atypeFactory.getAnnotatedType(index).getPrimaryAnnotationInHierarchy(top);
        if (found == null) {
            throw new BugInCF("no column-index qualifier on subscript %s", index);
        }
        if (!qualHierarchy.isSubtypeQualifiersOnly(found, required)) {
            checker.reportError(index, "subscript.space", found, required, container);
        }
    }
}
