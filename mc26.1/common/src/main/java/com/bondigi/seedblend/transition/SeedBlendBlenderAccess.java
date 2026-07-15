package com.bondigi.seedblend.transition;

import org.jetbrains.annotations.Nullable;

/**
 * Mixin-backed duck on {@code Blender}: carries the per-chunk transition context.
 * Null on vanilla blenders (including EMPTY, whose overridden methods bypass the
 * injected blending entirely).
 */
public interface SeedBlendBlenderAccess {
    @Nullable
    TransitionContext seedblend$getTransition();

    void seedblend$setTransition(TransitionContext context);
}
