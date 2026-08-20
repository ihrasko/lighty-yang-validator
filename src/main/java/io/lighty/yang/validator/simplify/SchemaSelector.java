/*
 * Copyright (c) 2021 PANTHEON.tech s.r.o. All Rights Reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-v10.html
 */
package io.lighty.yang.validator.simplify;

import io.lighty.yang.validator.formats.utility.LyvStack;
import io.lighty.yang.validator.simplify.stream.TrackingXmlParserStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.opendaylight.yangtools.yang.common.QName;
import org.opendaylight.yangtools.yang.data.api.schema.stream.NormalizedNodeStreamWriter;
import org.opendaylight.yangtools.yang.data.codec.xml.XmlCodecFactory;
import org.opendaylight.yangtools.yang.data.impl.schema.ImmutableNormalizedNodeStreamWriter;
import org.opendaylight.yangtools.yang.data.impl.schema.NormalizationResultHolder;
import org.opendaylight.yangtools.yang.model.api.ActionDefinition;
import org.opendaylight.yangtools.yang.model.api.ActionNodeContainer;
import org.opendaylight.yangtools.yang.model.api.AugmentationSchemaNode;
import org.opendaylight.yangtools.yang.model.api.CaseSchemaNode;
import org.opendaylight.yangtools.yang.model.api.ChoiceSchemaNode;
import org.opendaylight.yangtools.yang.model.api.DataNodeContainer;
import org.opendaylight.yangtools.yang.model.api.DataSchemaNode;
import org.opendaylight.yangtools.yang.model.api.EffectiveModelContext;
import org.opendaylight.yangtools.yang.model.api.Module;

public class SchemaSelector {

    private static final String OUTPUT_TEXT = "output";
    private static final XMLInputFactory FACTORY = XMLInputFactory.newInstance();
    private final EffectiveModelContext effectiveModelContext;
    private final SchemaTree tree;
    @SuppressWarnings("UnstableApiUsage")
    private final XmlCodecFactory codecs;

    @SuppressWarnings("UnstableApiUsage")
    public SchemaSelector(final EffectiveModelContext effectiveModelContext) {
        this.effectiveModelContext = effectiveModelContext;
        codecs = XmlCodecFactory.create(effectiveModelContext);
        tree = new SchemaTree(SchemaTree.ROOT, null,
                false, false, null);
    }

    public void addXml(final InputStream xml) throws XMLStreamException, IOException, URISyntaxException {
        fillUsedSchema(xml, tree);
    }

    public SchemaTree getSchemaTree() {
        return tree;
    }

    private void fillUsedSchema(final InputStream input, final SchemaTree st)
            throws XMLStreamException, IOException, URISyntaxException {
        final XMLStreamReader reader = FACTORY.createXMLStreamReader(input);
        final NormalizationResultHolder result = new NormalizationResultHolder();
        final NormalizedNodeStreamWriter streamWriter = ImmutableNormalizedNodeStreamWriter.from(result);
        try (var xmlParser = new TrackingXmlParserStream(streamWriter, codecs, effectiveModelContext, true, st)) {
            xmlParser.parse(reader);
        }
    }

    public void noXml() {
        final var stack = new LyvStack();

        for (final Module module : effectiveModelContext.getModules()) {
            for (final DataSchemaNode node : module.getChildNodes()) {
                resolveChildNodes(tree, node, true, false, stack, true);
                stack.clear();
            }

            for (final AugmentationSchemaNode aug : module.getAugmentations()) {
                stack.enter(aug.getTargetPath());
                // The nodes returned by aug.getChildNodes() are not grafted onto the augment's target, so their
                // own effectiveConfig() is not applicable (same as inside a grouping); resolveChildNodes looks
                // each node's own position up via effectiveModelContext.findSchemaTreeNode() instead.
                final boolean augmentConfig = isAugmentConfig(aug);
                for (final DataSchemaNode node : aug.getChildNodes()) {
                    resolveChildNodes(tree, node, true, true, stack, augmentConfig);
                }
                stack.clear();
            }
        }
    }

    /**
     * Resolves {@code node}'s config, and adds it to {@code schemaTree}, then recurses into its children.
     * {@code node} itself is used for structure (type/name/description), while config is looked up fresh through
     * the effective model context via the node's own schema-tree position (which includes {@code node} itself,
     * since {@code stack.enter(node)} happens first) - a node reached only through an augmentation's own child
     * tree does not have its own effectiveConfig() applicable (same as inside a grouping), so looking it up this
     * way instead of calling {@code node.effectiveConfig()} directly is what makes an explicit {@code config
     * false;} on an augmented descendant visible. {@code ambientConfig} is the fallback used when that lookup is
     * absent (e.g. deviated away) or does not resolve a config value of its own.
     */
    private void resolveChildNodes(final SchemaTree schemaTree, final DataSchemaNode node, final boolean rootNode,
            final boolean augNode, final LyvStack stack, final boolean ambientConfig) {
        stack.enter(node);
        final boolean isConfig = resolveEffectiveConfig(stack).orElse(ambientConfig);
        SchemaTree childSchemaTree = schemaTree.addChild(node, rootNode, augNode, stack, isConfig);
        if (node instanceof DataNodeContainer) {
            for (final DataSchemaNode schemaNode : ((DataNodeContainer) node).getChildNodes()) {
                resolveChildNodes(childSchemaTree, schemaNode, false, false, stack, isConfig);
            }
        } else if (node instanceof ChoiceSchemaNode) {
            for (final DataSchemaNode singleCase : ((ChoiceSchemaNode) node).getCases()) {
                resolveChildNodes(childSchemaTree, singleCase, false, false, stack, isConfig);
            }
        }

        if (node instanceof ActionNodeContainer) {
            for (final ActionDefinition action : ((ActionNodeContainer) node).getActions()) {
                stack.enter(action);
                childSchemaTree = childSchemaTree.addChild(action, false, false, stack);
                resolveChildNodes(childSchemaTree, action.getInput(), false, false, stack, true);
                resolveChildNodes(childSchemaTree, action.getOutput(), false, false, stack, true);
                stack.exit();
            }
        }
        stack.exit();
    }

    /**
     * Looks up {@code stack}'s current position through the effective model context's schema tree (which,
     * unlike {@link EffectiveModelContext#findDataTreeChild}, addresses choice/case directly instead of skipping
     * over them), returning its effectiveConfig() if resolved to a real, correctly-positioned DataSchemaNode.
     */
    private Optional<Boolean> resolveEffectiveConfig(final LyvStack stack) {
        return effectiveModelContext.findSchemaTreeNode(stack.toSchemaNodeIdentifier())
                .filter(DataSchemaNode.class::isInstance)
                .map(DataSchemaNode.class::cast)
                .flatMap(DataSchemaNode::effectiveConfig);
    }

    private boolean isAugmentConfig(final AugmentationSchemaNode augmentation) {
        final List<QName> qNames = new ArrayList<>();
        Collection<? extends ActionDefinition> actions = new HashSet<>();
        boolean isAction = false;
        for (final QName path : augmentation.getTargetPath().getNodeIdentifiers()) {
            if (isAction) {
                return !OUTPUT_TEXT.equals(path.getLocalName());
            }
            if (shouldSkipThisIteration(actions, path)) {
                isAction = true;
                continue;
            }

            qNames.add(path);
            final Optional<DataSchemaNode> optDataTreeChild = effectiveModelContext.findDataTreeChild(qNames);

            if (optDataTreeChild.isPresent()) {
                final DataSchemaNode dataTreeChild = optDataTreeChild.orElseThrow();
                final Optional<Boolean> isConfig = dataTreeChild.effectiveConfig();
                if (isConfig.isPresent() && !isConfig.orElseThrow()) {
                    return false;
                }
                if (dataTreeChild instanceof ActionNodeContainer) {
                    actions = ((ActionNodeContainer) dataTreeChild).getActions();
                }
            } else {
                qNames.remove(path);
            }
        }
        return true;
    }

    private static boolean shouldSkipThisIteration(final Collection<? extends ActionDefinition> actions,
            final QName path) {
        for (final ActionDefinition action : actions) {
            if (action.getQName().getLocalName().equals(path.getLocalName())) {
                return true;
            }
        }
        return false;
    }
}

