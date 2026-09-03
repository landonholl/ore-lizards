package com.orelizards.client;

import com.orelizards.entity.OreLizardEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import software.bernie.geckolib.renderer.GeoEntityRenderer;
import software.bernie.geckolib.renderer.base.GeoRenderState;

/**
 * GeckoLib 5's entity renderer is generic in the render state it draws from, bounded to
 * {@code EntityRenderState & GeoRenderState}. Vanilla's render state classes only pick up
 * {@code GeoRenderState} through a GeckoLib mixin at runtime - there is no compile-time interface
 * injection - so no concrete class satisfies that bound in source. GeckoLib's own subclasses
 * ({@code DirectionalProjectileRenderer}) keep the type parameter and its intersection bound, and so
 * does this one; the actual object at runtime is the {@code LivingEntityRenderState} GeckoLib
 * creates for any living animatable, which is why the bound is narrowed to that.
 */
public class OreLizardRenderer<R extends LivingEntityRenderState & GeoRenderState> extends GeoEntityRenderer<OreLizardEntity, R> {
	public OreLizardRenderer(EntityRendererProvider.Context context) {
		super(context, new OreLizardModel());
		this.shadowRadius = 0.5F;
		// GeckoLib 5.4 renamed addRenderLayer to the chainable withRenderLayer.
		this.withRenderLayer(new OreTintLayer<>(this));
	}
}
