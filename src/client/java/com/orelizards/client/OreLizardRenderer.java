package com.orelizards.client;

import com.orelizards.entity.OreLizardEntity;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import software.bernie.geckolib3.renderer.geo.GeoEntityRenderer;

public class OreLizardRenderer extends GeoEntityRenderer<OreLizardEntity> {
	public OreLizardRenderer(EntityRenderDispatcher dispatcher) {
		super(dispatcher, new OreLizardModel());
		this.shadowRadius = 0.5F;
		this.addLayer(new OreTintLayer(this));
	}
}
