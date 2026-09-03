package com.orelizards.client;

import com.geckolib.renderer.GeoEntityRenderer;
import com.orelizards.entity.OreLizardEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;

/**
 * GeckoLib 5's entity renderer is generic in the render state it draws from. On GeckoLib 5.5 that
 * state can be named outright: GeckoLib's class tweaker injects {@code GeoRenderState} into vanilla's
 * {@code EntityRenderState} <em>transitively</em>, so Loom applies it to the dev jar and
 * {@link LivingEntityRenderState} - the state GeckoLib creates for any living animatable - satisfies
 * every GeckoLib bound at compile time. (Earlier ports had to keep {@code R} as a type parameter with
 * an {@code EntityRenderState & GeoRenderState} intersection bound because the interface only existed
 * at runtime; GeckoLib's own {@code GeoEntityRenderer} has since dropped the {@code GeoRenderState}
 * bound for the same reason.)
 */
public class OreLizardRenderer extends GeoEntityRenderer<OreLizardEntity, LivingEntityRenderState> {
	public OreLizardRenderer(EntityRendererProvider.Context context) {
		super(context, new OreLizardModel());
		this.shadowRadius = 0.5F;
		this.withRenderLayer(new OreTintLayer(this));
	}
}
