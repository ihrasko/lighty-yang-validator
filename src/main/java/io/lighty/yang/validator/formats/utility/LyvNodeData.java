/*
 * Copyright (c) 2021 PANTHEON.tech s.r.o. All Rights Reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-v10.html
 */
package io.lighty.yang.validator.formats.utility;

import java.util.List;
import java.util.Optional;
import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.Nullable;
import org.opendaylight.yangtools.yang.common.QName;
import org.opendaylight.yangtools.yang.model.api.ActionDefinition;
import org.opendaylight.yangtools.yang.model.api.CaseSchemaNode;
import org.opendaylight.yangtools.yang.model.api.ContainerLike;
import org.opendaylight.yangtools.yang.model.api.EffectiveModelContext;
import org.opendaylight.yangtools.yang.model.api.MandatoryAware;
import org.opendaylight.yangtools.yang.model.api.NotificationDefinition;
import org.opendaylight.yangtools.yang.model.api.RpcDefinition;
import org.opendaylight.yangtools.yang.model.api.SchemaNode;
import org.opendaylight.yangtools.yang.model.api.stmt.SchemaNodeIdentifier.Absolute;

public class LyvNodeData {

    private final boolean isKey;
    private final EffectiveModelContext context;
    private final SchemaNode node;
    private final Absolute absolutePath;
    private final Boolean resolvedConfig;

    public LyvNodeData(final @NonNull EffectiveModelContext context, final @NonNull SchemaNode node,
            final @NonNull LyvStack stack) {
        this(context, node, stack.toSchemaNodeIdentifier());
    }

    public LyvNodeData(final @NonNull EffectiveModelContext context, final @NonNull SchemaNode node,
            final @NonNull LyvStack stack, final @Nullable List<QName> keys) {
        this(context, node, stack.toSchemaNodeIdentifier(), keys);
    }

    /**
     * Same as {@link #LyvNodeData(EffectiveModelContext, SchemaNode, LyvStack, List)}, but with an explicitly
     * resolved config value - see {@link #LyvNodeData(EffectiveModelContext, SchemaNode, Absolute, List, Boolean)}.
     */
    public LyvNodeData(final @NonNull EffectiveModelContext context, final @NonNull SchemaNode node,
            final @NonNull LyvStack stack, final @Nullable List<QName> keys, final @Nullable Boolean resolvedConfig) {
        this(context, node, stack.toSchemaNodeIdentifier(), keys, resolvedConfig);
    }

    public LyvNodeData(final @NonNull EffectiveModelContext context, final @NonNull SchemaNode node,
            final @NonNull Absolute absolutePath) {
        this(context, node, absolutePath, null);
    }

    public LyvNodeData(final @NonNull EffectiveModelContext context, final @NonNull SchemaNode node,
            final @NonNull Absolute absolutePath, final @Nullable List<QName> keys) {
        this(context, node, absolutePath, keys, null);
    }

    /**
     * Same as {@link #LyvNodeData(EffectiveModelContext, SchemaNode, Absolute, List)}, but with an explicitly
     * resolved config value instead of leaving it to be derived from {@code node} itself - needed when
     * {@code node} was reached through an augmentation's own child tree, whose own effectiveConfig() is not
     * applicable (same as inside a grouping); see {@link io.lighty.yang.validator.simplify.SchemaTree#isConfig()}.
     */
    public LyvNodeData(final @NonNull EffectiveModelContext context, final @NonNull SchemaNode node,
            final @NonNull Absolute absolutePath, final @Nullable List<QName> keys,
            final @Nullable Boolean resolvedConfig) {
        this.context = context;
        this.absolutePath = absolutePath;
        this.node = node;
        this.resolvedConfig = resolvedConfig;
        isKey = keys != null && keys.contains(node.getQName());
    }

    public EffectiveModelContext getContext() {
        return context;
    }

    public SchemaNode getNode() {
        return node;
    }

    public Absolute getAbsolutePath() {
        return absolutePath;
    }

    /**
     * The resolved config value, if explicitly provided at construction time; otherwise empty, meaning callers
     * should derive it from {@link #getNode()} themselves (safe as long as the node is already at its real,
     * correctly-positioned location).
     */
    public Optional<Boolean> getResolvedConfig() {
        return Optional.ofNullable(resolvedConfig);
    }

    public boolean isNodeMandatory() {
        return node instanceof MandatoryAware && ((MandatoryAware) node).isMandatory()
                || node instanceof ContainerLike || node instanceof CaseSchemaNode
                || node instanceof NotificationDefinition || node instanceof ActionDefinition
                || node instanceof RpcDefinition || isKey;
    }
}
