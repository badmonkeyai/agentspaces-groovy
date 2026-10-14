/*
 * Copyright 2026 Bad Monkey, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.badmonkey.agentspaces.groovy

import ai.badmonkey.agentspaces.agent.annotation.Ballot
import ai.badmonkey.agentspaces.agent.annotation.BidFunction
import ai.badmonkey.agentspaces.agent.annotation.Durations
import ai.badmonkey.agentspaces.agent.annotation.OnDecision
import ai.badmonkey.agentspaces.agent.annotation.OnEstimate
import ai.badmonkey.agentspaces.agent.annotation.OrderedTake
import ai.badmonkey.agentspaces.agent.annotation.Propose
import ai.badmonkey.agentspaces.agent.annotation.SpaceJoin
import ai.badmonkey.agentspaces.agent.annotation.SpaceNotify
import ai.badmonkey.agentspaces.agent.annotation.SpaceReduce
import ai.badmonkey.agentspaces.agent.annotation.SpaceTake
import groovy.transform.CompileStatic
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.AnnotationNode
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.RecordComponentNode
import org.codehaus.groovy.ast.expr.ArgumentListExpression
import org.codehaus.groovy.ast.expr.ClassExpression
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.ListExpression
import org.codehaus.groovy.ast.expr.MapEntryExpression
import org.codehaus.groovy.ast.expr.MapExpression
import org.codehaus.groovy.ast.expr.MethodCall
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.PropertyExpression
import org.codehaus.groovy.ast.expr.TupleExpression
import org.codehaus.groovy.transform.stc.StaticTypeCheckingVisitor
import org.codehaus.groovy.transform.stc.AbstractTypeCheckingExtension

/**
 * The type-checking extension (SRAD section 3.7, GR-T-01): under
 * {@code @TypeChecked(extensions = 'ai.badmonkey.agentspaces.groovy.FleetChecker')}
 * or {@code @CompileStatic(extensions = ...)} it turns the binder's bind-time
 * refusals into compile errors, for the Java annotations on a Groovy class
 * and for the agent DSL's clauses: a {@code where} entry must name a
 * component of the cue record; a duration attribute must parse; a
 * {@code @Ballot} must return {@code String}; {@code mode} must be one of
 * the verb's constants; each {@code parts} entry must name a class; a
 * clause's closure parameter must agree with the cue; {@code @Memoized} and
 * {@code @ActiveMethod} may not sit on a verb method. It also lets the fleet
 * DSL compile statically: an unknown method on a {@code FleetBuilder} is a
 * keyword the builder resolves at run time. Space and group names are not
 * checked, because only the runtime knows them.
 */
@CompileStatic
class FleetChecker extends AbstractTypeCheckingExtension {

    static final Set<String> VERBS = [SpaceTake, SpaceNotify, BidFunction, Ballot, OnDecision, OrderedTake,
                                      OnEstimate, Propose, SpaceJoin, SpaceReduce]*.name as Set<String>
    static final Set<String> DURATIONS = ['lease', 'pollTimeout', 'resultLease', 'takeLease', 'ticketLease', 'within',
                                          'accumulatorLease', 'subscriptionLease'] as Set<String>
    static final Set<String> REFUSED_BESIDE_A_VERB = ['groovy.transform.Memoized', 'groovy.transform.ActiveMethod'] as Set<String>
    static final Set<String> DSL_VERBS = ['take', 'notify', 'bid', 'ballot', 'onDecision', 'orderedTake', 'onEstimate',
                                          'propose', 'join', 'reduce'] as Set<String>

    FleetChecker(StaticTypeCheckingVisitor visitor) {
        super(visitor)
    }

    // ---- the Java annotations on a Groovy class ----------------------------------------------

    @Override
    boolean beforeVisitMethod(MethodNode node) {
        List<AnnotationNode> verbs = node.annotations.findAll { AnnotationNode a -> VERBS.contains(a.classNode.name) }
        if (verbs.isEmpty()) {
            return false
        }
        node.annotations.each { AnnotationNode a ->
            if (REFUSED_BESIDE_A_VERB.contains(a.classNode.name)) {
                addStaticTypeError("@${a.classNode.nameWithoutPackage} cannot sit on a verb method: the binder calls ${node.name} once per cue and writes what it returns, and ${a.classNode.nameWithoutPackage} ${a.classNode.name.endsWith('Memoized') ? 'would answer a repeated cue from its cache' : 'returns before the work runs'}".toString(), a)
            }
        }
        verbs.each { AnnotationNode verb ->
            String simple = verb.classNode.nameWithoutPackage
            ClassNode cue = cueOf(node, simple)
            checkWhere(verb.getMember('where'), cue, "@${simple}.where on ${node.name}", verb)
            DURATIONS.each { String attr -> checkDuration(verb.getMember(attr), "@${simple}.${attr} on ${node.name}", verb) }
            if (simple == 'Ballot' && node.returnType != ClassHelper.STRING_TYPE) {
                addStaticTypeError("a @Ballot method returns the chosen option as a String (or null to abstain); ${node.name} returns ${node.returnType.nameWithoutPackage}".toString(), node)
            }
        }
        false
    }

    private static ClassNode cueOf(MethodNode node, String verb) {
        Parameter[] params = node.parameters
        if (params.length == 0) {
            return null
        }
        verb == 'SpaceReduce' && params.length > 1 ? params[1].type : params[0].type
    }

    // ---- the agent DSL and the fleet DSL ----------------------------------------------------

    /**
     * A DSL clause: a call named for a verb inside a closure whose delegate is
     * the {@code AgentBuilder}. Keyed on the call rather than the selected
     * method because {@code notify} also exists on {@code java.lang.Object},
     * and the compiler may select that one.
     */
    @Override
    void afterMethodCall(MethodCall methodCall) {
        if (!(methodCall instanceof MethodCallExpression)) {
            return
        }
        MethodCallExpression call = (MethodCallExpression) methodCall
        String verb = call.methodAsString
        if (verb == null || !DSL_VERBS.contains(verb) || !call.implicitThis || !insideDelegate(AgentBuilder)) {
            return
        }
        if (call.getNodeMetaData(FleetChecker) != null) {
            return
        }
        call.putNodeMetaData(FleetChecker, Boolean.TRUE)
        checkClause(verb, call)
    }

    private void checkClause(String verb, MethodCallExpression call) {
        MethodNode target = new MethodNode(verb, 1, ClassHelper.OBJECT_TYPE, Parameter.EMPTY_ARRAY, ClassNode.EMPTY_ARRAY, null)
        List<Expression> args = call.arguments instanceof TupleExpression ? ((TupleExpression) call.arguments).expressions : []
        MapExpression options = (MapExpression) args.find { Expression e -> e instanceof MapExpression }
        List<ClassExpression> cues = args.findAll { Expression e -> e instanceof ClassExpression } as List<ClassExpression>
        ClosureExpression body = (ClosureExpression) args.find { Expression e -> e instanceof ClosureExpression }
        ClassNode cue = cues.isEmpty() ? null : (target.name == 'reduce' && cues.size() > 1 ? cues[1].type : cues[0].type)
        String what = "${target.name} clause"
        boolean asMap = false
        if (options != null) {
            options.mapEntryExpressions.each { MapEntryExpression entry ->
                String key = constant(entry.keyExpression)
                if (key == null) {
                    return
                }
                switch (key) {
                    case 'where': checkWhere(entry.valueExpression, cue, "${what} where:", entry); break
                    case 'mode': checkMode(entry.valueExpression, target.name, entry); break
                    case 'parts': checkParts(entry.valueExpression, entry); break
                    case 'asMap': asMap = 'true' == constant(entry.valueExpression); break
                    default:
                        if (DURATIONS.contains(key)) {
                            checkDuration(entry.valueExpression, "${what} ${key}:", entry)
                        }
                }
            }
        }
        if (body != null && cue != null && body.parameters != null && body.parameters.length > 0) {
            ClassNode declared = body.parameters[0].type
            ClassNode expected = asMap ? ClassHelper.MAP_TYPE : (target.name == 'reduce' && cues.size() > 1 ? cues[0].type : cue)
            if (declared != ClassHelper.OBJECT_TYPE && declared != ClassHelper.DYNAMIC_TYPE
                    && !declared.isDerivedFrom(expected) && !expected.isDerivedFrom(declared)
                    && !(asMap && declared.implementsInterface(ClassHelper.MAP_TYPE))) {
                addStaticTypeError("${what}: the body's first parameter is ${declared.nameWithoutPackage} but the cue is ${expected.nameWithoutPackage}".toString(), body.parameters[0])
            }
        }
    }

    @Override
    List<MethodNode> handleMissingMethod(ClassNode receiver, String name, ArgumentListExpression argumentList,
                                         ClassNode[] argumentTypes, MethodCall call) {
        if (insideFleetDsl(receiver)) {
            // A fleet DSL keyword: the builder resolves it against the starter's properties at run time.
            MethodNode dynamic = makeDynamic(call, ClassHelper.make(FleetBuilder))
            return [dynamic] as List<MethodNode>
        }
        [] as List<MethodNode>
    }

    /** Whether the receiver is a FleetBuilder, or the call sits in a closure (nested blocks included) whose delegate is one. */
    private boolean insideFleetDsl(ClassNode receiver) {
        receiver.isDerivedFrom(ClassHelper.make(FleetBuilder)) || insideDelegate(FleetBuilder)
    }

    /**
     * Whether an enclosing closure delegates to the given type. Dynamic
     * because the compiler's {@code DelegationMetadata} is package-private.
     */
    @groovy.transform.CompileDynamic
    private boolean insideDelegate(Class<?> type) {
        ClassNode wanted = ClassHelper.make(type)
        for (def enclosing : typeCheckingVisitor.typeCheckingContext.enclosingClosureStack) {
            def metadata = enclosing.closureExpression.getNodeMetaData(org.codehaus.groovy.transform.stc.StaticTypesMarker.DELEGATION_METADATA)
            while (metadata != null) {
                if (metadata.type.isDerivedFrom(wanted)) {
                    return true
                }
                metadata = metadata.parent
            }
        }
        false
    }

    // ---- the checks ------------------------------------------------------------------------

    private void checkWhere(Expression where, ClassNode cue, String what, ASTNode at) {
        if (where == null || cue == null) {
            return
        }
        Set<String> components = componentsOf(cue)
        if (components == null) {
            return
        }
        List<Expression> items = where instanceof ListExpression ? ((ListExpression) where).expressions
                : (where instanceof MapExpression ? ((MapExpression) where).mapEntryExpressions.collect { MapEntryExpression e -> e.keyExpression } : [where])
        items.each { Expression item ->
            String text = constant(item)
            if (text == null) {
                return
            }
            String field = text.split('!=|=')[0].trim()
            if (!components.contains(field)) {
                addStaticTypeError("${what}: ${cue.nameWithoutPackage} has no component '${field}'; components: ${components.sort()}".toString(), item)
            }
        }
    }

    private void checkDuration(Expression value, String what, ASTNode at) {
        String text = constant(value)
        if (text == null) {
            return
        }
        try {
            Durations.parse(text)
        } catch (IllegalArgumentException e) {
            addStaticTypeError("${what}: ${e.message}".toString(), value)
        }
    }

    private void checkMode(Expression value, String verb, ASTNode at) {
        Class<?> modes = verb == 'reduce' ? SpaceReduce.Mode : (verb == 'join' ? SpaceJoin.Mode : null)
        if (modes == null) {
            addStaticTypeError("mode: applies to join and reduce clauses only, not ${verb}".toString(), value)
            return
        }
        String text = constant(value)
        if (text == null && value instanceof PropertyExpression) {
            text = ((PropertyExpression) value).propertyAsString
        }
        if (text == null) {
            return
        }
        List<String> names = modes.enumConstants.collect { Object c -> ((Enum<?>) c).name() }
        if (!names.contains(text.toUpperCase(Locale.ROOT))) {
            addStaticTypeError("mode: wants one of ${names*.toLowerCase()}; got '${text}'".toString(), value)
        }
    }

    private void checkParts(Expression value, ASTNode at) {
        if (!(value instanceof ListExpression)) {
            addStaticTypeError('parts: takes a list of classes or maps with value:', value)
            return
        }
        ((ListExpression) value).expressions.each { Expression part ->
            if (part instanceof ClassExpression) {
                return
            }
            if (part instanceof MapExpression) {
                boolean named = ((MapExpression) part).mapEntryExpressions.any { MapEntryExpression e -> constant(e.keyExpression) == 'value' }
                if (!named) {
                    addStaticTypeError('parts: each map needs value: naming the part\'s class', part)
                }
                return
            }
            addStaticTypeError('parts: each part is a class or a map with value:', part)
        }
    }

    /** The record's component names, or null when the type is no record the compiler can see. */
    static Set<String> componentsOf(ClassNode type) {
        if (type.isResolved()) {
            Class<?> clazz = type.typeClass
            return clazz.isRecord() ? (clazz.recordComponents*.name as Set<String>) : null
        }
        List<RecordComponentNode> components = type.recordComponents
        components ? (components*.name as Set<String>) : null
    }

    private static String constant(Expression e) {
        e instanceof ConstantExpression ? String.valueOf(((ConstantExpression) e).value) : null
    }
}
