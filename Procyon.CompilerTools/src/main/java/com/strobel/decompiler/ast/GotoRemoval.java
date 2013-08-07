/*
 * GotoRemoval.java
 *
 * Copyright (c) 2013 Mike Strobel
 *
 * This source code is based on Mono.Cecil from Jb Evain, Copyright (c) Jb Evain;
 * and ILSpy/ICSharpCode from SharpDevelop, Copyright (c) AlphaSierraPapa.
 *
 * This source code is subject to terms and conditions of the Apache License, Version 2.0.
 * A copy of the license can be found in the License.html file at the root of this distribution.
 * By using this source code in any fashion, you are agreeing to be bound by the terms of the
 * Apache License, Version 2.0.
 *
 * You must not remove this notice, or any other, from this software.
 */

package com.strobel.decompiler.ast;

import com.strobel.annotations.NotNull;
import com.strobel.core.StrongBox;
import com.strobel.core.VerifyArgument;
import com.strobel.decompiler.ITextOutput;
import com.strobel.util.ContractUtils;

import java.util.*;

import static com.strobel.core.CollectionUtilities.*;
import static com.strobel.decompiler.ast.PatternMatching.*;

@SuppressWarnings("ConstantConditions")
final class GotoRemoval {
    private final static Node NULL_NODE = new Node() {
        @Override
        public void writeTo(final ITextOutput output) {
            throw ContractUtils.unreachable();
        }
    };

    final Map<Node, Label> labels = new IdentityHashMap<>();
    final Map<Label, Node> labelLookup = new IdentityHashMap<>();
    final Map<Node, Node> parentLookup = new IdentityHashMap<>();
    final Map<Node, Node> nextSibling = new IdentityHashMap<>();

    public final void removeGotos(final Block method) {
        parentLookup.put(method, NULL_NODE);

        for (final Node node : method.getSelfAndChildrenRecursive(Node.class)) {
            Node previousChild = null;

            for (final Node child : node.getChildren()) {
                if (parentLookup.containsKey(child)) {
                    throw Error.expressionLinkedFromMultipleLocations(child);
                }

                parentLookup.put(child, node);

                if (previousChild != null) {
                    if (previousChild instanceof Label) {
                        labels.put(child, (Label) previousChild);
                        labelLookup.put((Label) previousChild, child);
                    }
                    nextSibling.put(previousChild, child);
                }

                previousChild = child;
            }

            if (previousChild != null) {
                nextSibling.put(previousChild, NULL_NODE);
            }
        }

        transformLeaveStatements(method);

        boolean modified;

        do {
            modified = false;

            for (final Expression e : method.getSelfAndChildrenRecursive(Expression.class)) {
                if (e.getCode() == AstCode.Goto) {
                    modified |= trySimplifyGoto(e);
                }
            }
        }
        while (modified);

        removeRedundantCode(method);
    }

    private boolean trySimplifyGoto(final Expression gotoExpression) {
        assert gotoExpression.getCode() == AstCode.Goto;
        assert gotoExpression.getOperand() instanceof Label;

        final Node target = enter(gotoExpression, new LinkedHashSet<Node>());

        if (target == null) {
            return false;
        }

        //
        // The goto expression is marked as visited because we do not want to iterate over
        // nodes which we plan to modify.
        //
        // The simulated path always has to start in the same try block in order for the
        // same finally blocks to be executed.
        //

        final Set<Node> visitedNodes = new LinkedHashSet<>();

        visitedNodes.add(gotoExpression);

        if (target == exit(gotoExpression, visitedNodes)) {
            gotoExpression.setCode(AstCode.Nop);
            gotoExpression.setOperand(null);

            if (target instanceof Expression) {
                ((Expression) target).getRanges().addAll(gotoExpression.getRanges());
            }

            gotoExpression.getRanges().clear();
            return true;
        }

        visitedNodes.clear();
        visitedNodes.add(gotoExpression);

        for (final TryCatchBlock tryCatchBlock : getParents(gotoExpression, TryCatchBlock.class)) {
            final Block finallyBlock = tryCatchBlock.getFinallyBlock();

            if (finallyBlock == null) {
                continue;
            }

            if (target == enter(finallyBlock, visitedNodes)) {
                gotoExpression.setCode(AstCode.Nop);
                gotoExpression.setOperand(null);
                gotoExpression.getRanges().clear();
                return true;
            }
        }

        visitedNodes.clear();
        visitedNodes.add(gotoExpression);

        int loopDepth = 0;
        int switchDepth = 0;
        Node breakBlock = null;

        for (final Node parent : getParents(gotoExpression)) {
            if (parent instanceof Loop) {
                ++loopDepth;

                final Node exit = exit(parent, visitedNodes);

                if (target == exit) {
                    breakBlock = parent;
                    break;
                }

                if (exit instanceof TryCatchBlock) {
                    final Node firstChild = firstOrDefault(exit.getChildren());

                    if (firstChild != null) {
                        visitedNodes.clear();
                        if (enter(firstChild, visitedNodes) == target) {
                            breakBlock = parent;
                            break;
                        }
                    }
                }
            }
            else if (parent instanceof Switch) {
                ++switchDepth;

                final Node nextNode = nextSibling.get(parent);

                if (nextNode != null &&
                    nextNode != NULL_NODE &&
                    nextNode == gotoExpression.getOperand()) {

                    breakBlock = parent;
                    break;
                }
            }
        }

        visitedNodes.clear();
        visitedNodes.add(gotoExpression);

        if (breakBlock != null) {
            gotoExpression.setCode(AstCode.LoopOrSwitchBreak);
            gotoExpression.setOperand((loopDepth + switchDepth) > 1 ? gotoExpression.getOperand() : null);
            return true;
        }

        loopDepth = 0;
        Loop continueBlock = null;

        for (final Node parent : getParents(gotoExpression)) {
            if (parent instanceof Loop) {
                ++loopDepth;

                final Node enter = enter(parent, visitedNodes);

                if (target == enter) {
                    continueBlock = (Loop) parent;
                    break;
                }

                if (enter instanceof TryCatchBlock) {
                    final Node firstChild = firstOrDefault(enter.getChildren());

                    if (firstChild != null) {
                        visitedNodes.clear();
                        if (enter(firstChild, visitedNodes) == target) {
                            continueBlock = (Loop) parent;
                            break;
                        }
                    }
                }
            }
        }

        visitedNodes.clear();
        visitedNodes.add(gotoExpression);

        if (continueBlock != null) {
            gotoExpression.setCode(AstCode.LoopContinue);
            gotoExpression.setOperand(loopDepth > 1 ? gotoExpression.getOperand() : null);
            return true;
        }

        if (tryInlineReturn(gotoExpression, target, AstCode.Return) ||
            tryInlineReturn(gotoExpression, target, AstCode.AThrow)) {

            return true;
        }

        return false;
    }

    private boolean tryInlineReturn(final Expression gotoExpression, final Node target, final AstCode code) {
        final List<Expression> expressions = new ArrayList<>();

        if (matchGetArguments(target, code, expressions) &&
            (expressions.isEmpty() ||
             expressions.size() == 1/* && Inlining.hasNoSideEffect(expressions.get(0))*/)) {

            gotoExpression.setCode(code);
            gotoExpression.setOperand(null);
            gotoExpression.getArguments().clear();

            if (!expressions.isEmpty()) {
                gotoExpression.getArguments().add(expressions.get(0).clone());
            }

            return true;
        }

        final StrongBox<Variable> v = new StrongBox<>();
        final StrongBox<Variable> v2 = new StrongBox<>();

        Node next = nextSibling.get(target);

        while (next instanceof Label) {
            next = nextSibling.get(next);
        }

        if (matchGetArguments(target, AstCode.Store, v, expressions) &&
            expressions.size() == 1 &&
            /*Inlining.hasNoSideEffect(expressions.get(0)) &&*/
            matchGetArguments(next, code, expressions) &&
            expressions.size() == 1 &&
            matchGetOperand(expressions.get(0), AstCode.Load, v2) &&
            v2.get() == v.get()) {

            gotoExpression.setCode(code);
            gotoExpression.setOperand(null);
            gotoExpression.getArguments().clear();
            gotoExpression.getArguments().add(((Expression) target).getArguments().get(0).clone());

            return true;
        }

        return false;
    }

    private Iterable<Node> getParents(final Node node) {
        return getParents(node, Node.class);
    }

    private <T extends Node> Iterable<T> getParents(final Node node, final Class<T> parentType) {
        return new Iterable<T>() {
            @NotNull
            @Override
            public final Iterator<T> iterator() {
                return new Iterator<T>() {
                    T current = updateCurrent(node);

                    @SuppressWarnings("unchecked")
                    private T updateCurrent(Node node) {
                        while (node != null && node != NULL_NODE) {
                            node = parentLookup.get(node);

                            if (parentType.isInstance(node)) {
                                return (T) node;
                            }
                        }

                        return null;
                    }

                    @Override
                    public final boolean hasNext() {
                        return current != null;
                    }

                    @Override
                    public final T next() {
                        final T next = current;

                        if (next == null) {
                            throw new NoSuchElementException();
                        }

                        current = updateCurrent(next);
                        return next;
                    }

                    @Override
                    public final void remove() {
                        throw ContractUtils.unsupported();
                    }
                };
            }
        };
    }

    private Node enter(final Node node, final Set<Node> visitedNodes) {
        VerifyArgument.notNull(node, "node");
        VerifyArgument.notNull(visitedNodes, "visitedNodes");

        if (!visitedNodes.add(node)) {
            //
            // Infinite loop.
            //
            return null;
        }

        if (node instanceof Label) {
            return exit(node, visitedNodes);
        }

        if (node instanceof Expression) {
            final Expression e = (Expression) node;

            switch (e.getCode()) {
                case Goto: {
                    final Label target = (Label) e.getOperand();

                    //
                    // Early exit -- same try block.
                    //
                    if (firstOrDefault(getParents(e, TryCatchBlock.class)) ==
                        firstOrDefault(getParents(target, TryCatchBlock.class))) {

                        return enter(target, visitedNodes);
                    }

                    //
                    // Make sure we are not entering a try block.
                    //
                    final List<TryCatchBlock> sourceTryBlocks = toList(getParents(e, TryCatchBlock.class));
                    final List<TryCatchBlock> targetTryBlocks = toList(getParents(target, TryCatchBlock.class));

                    Collections.reverse(sourceTryBlocks);
                    Collections.reverse(targetTryBlocks);

                    //
                    // Skip blocks we are already in.
                    //
                    int i = 0;

                    while (i < sourceTryBlocks.size() &&
                           i < targetTryBlocks.size() &&
                           sourceTryBlocks.get(i) == targetTryBlocks.get(i)) {
                        i++;
                    }

                    if (i == targetTryBlocks.size()) {
                        return enter(target, visitedNodes);
                    }

                    final TryCatchBlock targetTryBlock = targetTryBlocks.get(i);

                    //
                    // Check that the goto points to the start.
                    //
                    TryCatchBlock current = targetTryBlock;

                    while (current != null) {
                        final List<Node> body = current.getTryBlock().getBody();

                        current = null;

                        for (final Node n : body) {
                            if (n instanceof Label) {
                                if (n == target) {
/*
                                    final Node firstChild = firstOrDefault(targetTryBlock.getChildren());

                                    if (firstChild != null) {
                                        final Node result = enter(firstChild, visitedNodes);
                                        return result;
                                    }
*/

                                    return targetTryBlock;
                                }
                            }
                            else if (!match(n, AstCode.Nop)) {
                                current = n instanceof TryCatchBlock ? (TryCatchBlock) n : null;
                                break;
                            }
                        }
                    }

                    return null;
                }

                default: {
                    return e;
                }
            }
        }

        if (node instanceof Block) {
            final Block block = (Block) node;

            if (block.getEntryGoto() != null) {
                return enter(block.getEntryGoto(), visitedNodes);
            }

            if (block.getBody().isEmpty()) {
                return exit(block, visitedNodes);
            }

            return enter(block.getBody().get(0), visitedNodes);
        }

        if (node instanceof Condition) {
            return ((Condition) node).getCondition();
        }

        if (node instanceof Loop) {
            final Loop loop = (Loop) node;

            if (loop.getCondition() != null) {
                return loop.getCondition();
            }

            return enter(loop.getBody(), visitedNodes);
        }

        if (node instanceof TryCatchBlock) {
            return node;
        }

        if (node instanceof Switch) {
            return ((Switch) node).getCondition();
        }

        throw Error.unsupportedNode(node);
    }

    private Node exit(final Node node, final Set<Node> visitedNodes) {
        VerifyArgument.notNull(node, "node");
        VerifyArgument.notNull(visitedNodes, "visitedNodes");

        final Node parent = parentLookup.get(node);

        if (parent == null || parent == NULL_NODE) {
            //
            // Exited main body.
            //
            return null;
        }

        if (parent instanceof Block) {
            final Node nextNode = nextSibling.get(node);

            if (nextNode != null && nextNode != NULL_NODE) {
                return enter(nextNode, visitedNodes);
            }

            return exit(parent, visitedNodes);
        }

        if (parent instanceof Condition) {
            return exit(parent, visitedNodes);
        }

        if (parent instanceof TryCatchBlock) {
            //
            // Finally blocks are completely ignored.  We rely on the fact that try blocks
            // cannot be entered.
            //
            return exit(parent, visitedNodes);
        }

        if (parent instanceof Switch) {
            //
            // Implicit exit from switch is not allowed.
            //
            return null;
        }

        if (parent instanceof Loop) {
            return enter(parent, visitedNodes);
        }

        throw Error.unsupportedNode(parent);
    }

    @SuppressWarnings("ConstantConditions")
    private void transformLeaveStatements(final Block method) {
        final StrongBox<Label> target = new StrongBox<>();
        final Set<Node> visitedNodes = new LinkedHashSet<>();

    outer:
        for (final Expression e : method.getSelfAndChildrenRecursive(Expression.class)) {
            if (matchGetOperand(e, AstCode.Goto, target)) {
                visitedNodes.clear();

                final Node exit = exit(e, new HashSet<Node>());

                if (exit != null && match(exit, AstCode.Leave)) {
                    final Node parent = parentLookup.get(e);
                    final Node grandParent = parent != null ? parentLookup.get(parent) : null;

                    if (parent instanceof Block &&
                        (grandParent instanceof CatchBlock ||
                         grandParent instanceof TryCatchBlock) &&
                        e == last(((Block) parent).getBody())) {

                        e.setCode(AstCode.Leave);
                        e.setOperand(null);
                    }
                }
            }
        }
    }

    @SuppressWarnings("ConstantConditions")
    public static void removeRedundantCode(final Block method) {
        //
        // Remove dead labels and NOPs.
        //

        final Set<Label> liveLabels = new LinkedHashSet<>();
        final StrongBox<Label> target = new StrongBox<>();

    outer:
        for (final Expression e : method.getSelfAndChildrenRecursive(Expression.class)) {
            if (e.isBranch()) {
                if (matchGetOperand(e, AstCode.Goto, target)) {
                    //
                    // See if the goto is an explicit jump to an outer finally.  If so, remove it.
                    //
                    for (final TryCatchBlock tryCatchBlock : method.getSelfAndChildrenRecursive(TryCatchBlock.class)) {
                        final Block finallyBlock = tryCatchBlock.getFinallyBlock();

/*
                        if (finallyBlock == null) {
                            continue;
                        }

                        final Node firstInBody = firstOrDefault(finallyBlock.getBody());

                        if (firstInBody == target.get()) {
                            continue outer;
                        }
*/
                        if (finallyBlock != null) {
                            final Node firstInBody = firstOrDefault(finallyBlock.getBody());

                            if (firstInBody == target.get()) {
                                e.setCode(AstCode.Leave);
                                e.setOperand(null);
                                continue outer;
                            }
                        }
                        else if (tryCatchBlock.getCatchBlocks().size() == 1) {
                            final Node firstInBody = firstOrDefault(first(tryCatchBlock.getCatchBlocks()).getBody());

                            if (firstInBody == target.get()) {
                                e.setCode(AstCode.Leave);
                                e.setOperand(null);
                                continue outer;
                            }
                        }
                    }
                }
                liveLabels.addAll(e.getBranchTargets());
            }
        }

        for (final Block block : method.getSelfAndChildrenRecursive(Block.class)) {
            final List<Node> body = block.getBody();

            for (int i = 0; i < body.size(); i++) {
                final Node n = body.get(i);

                if (match(n, AstCode.Nop) ||
                    match(n, AstCode.Leave) ||
                    n instanceof Label && !liveLabels.contains(n)) {

                    body.remove(i--);
                }
            }
        }

        //
        // Remove redundant continue statements.
        //

        for (final Loop loop : method.getSelfAndChildrenRecursive(Loop.class)) {
            final Block body = loop.getBody();

            if (matchLast(body, AstCode.LoopContinue)) {
                body.getBody().remove(body.getBody().size() - 1);
            }
        }

        //
        // Remove redundant break at end of case.  Remove empty case blocks.
        //

        for (final Switch switchNode : method.getSelfAndChildrenRecursive(Switch.class)) {
            CaseBlock defaultCase = null;

            final List<CaseBlock> caseBlocks = switchNode.getCaseBlocks();

            for (final CaseBlock caseBlock : caseBlocks) {
                assert caseBlock.getEntryGoto() == null;

                if (caseBlock.getValues().isEmpty()) {
                    defaultCase = caseBlock;
                }

                final List<Node> caseBody = caseBlock.getBody();
                final int size = caseBody.size();

                if (size >= 2) {
                    if (caseBody.get(size - 2).isUnconditionalControlFlow() &&
                        match(caseBody.get(size - 1), AstCode.LoopOrSwitchBreak)) {

                        caseBody.remove(size - 1);
                    }
                }
            }

            if (defaultCase == null ||
                defaultCase.getBody().size() == 1 && match(firstOrDefault(defaultCase.getBody()), AstCode.LoopOrSwitchBreak)) {

                for (int i = 0; i < caseBlocks.size(); i++) {
                    final List<Node> body = caseBlocks.get(i).getBody();

                    if (body.size() == 1 &&
                        matchGetOperand(firstOrDefault(body), AstCode.LoopOrSwitchBreak, target) &&
                        target.get() == null) {

                        caseBlocks.remove(i--);
                    }
                }
            }
        }

        //
        // Remove redundant return at end of method.
        //

        final List<Node> methodBody = method.getBody();
        final Node lastStatement = lastOrDefault(methodBody);

        if (match(lastStatement, AstCode.Return) &&
            ((Expression) lastStatement).getArguments().isEmpty()) {

            methodBody.remove(methodBody.size() - 1);
        }

        //
        // Remove unreachable return/throw statements.
        //

        boolean modified = false;

        for (final Block block : method.getSelfAndChildrenRecursive(Block.class)) {
            final List<Node> blockBody = block.getBody();

            for (int i = 0; i < blockBody.size() - 1; i++) {
                if (blockBody.get(i).isUnconditionalControlFlow() &&
                    (match(blockBody.get(i + 1), AstCode.Return) ||
                     match(blockBody.get(i + 1), AstCode.AThrow))) {

                    modified = true;
                    blockBody.remove(i-- + 1);
                }
            }
        }

        if (modified) {
            //
            // More removals might be possible.
            //
            new GotoRemoval().removeGotos(method);
        }
    }
}