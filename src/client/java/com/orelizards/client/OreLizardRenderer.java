package com.orelizards.client;

import com.orelizards.entity.OreLizardEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib3.renderers.geo.GeoEntityRenderer;

public class OreLizardRenderer extends GeoEntityRenderer<OreLizardEntity> {
	public OreLizardRenderer(EntityRendererProvider.Context context) {
		super(context, new OreLizardModel());
		this.shadowRadius = 0.5F;
		this.addLayer(new OreTintLayer(this));
	}
}
