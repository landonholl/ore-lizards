package com.orelizards.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.orelizards.entity.OreLizardEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib3.renderers.geo.GeoEntityRenderer;

public class OreLizardRenderer extends GeoEntityRenderer<OreLizardEntity> {
	public OreLizardRenderer(EntityRendererProvider.Context context) {
		super(context, new OreLizardModel());
		this.shadowRadius = 0.5F;
		this.addLayer(new OreTintLayer(this));
	}

	/**
	 * Skips the whole render for a lizard that is invisible to the player looking at it - which,
	 * while dormant, is every lizard. GeckoLib does this itself on every other version this mod is
	 * built for (3.0.80 and up wrap the body pass in {@code if (!isInvisibleTo(player))}, and the
	 * 4.x line returns a null render type), but 3.0.32 - the GeckoLib 3 build for 1.17.1 - does
	 * not: it renders the model unconditionally and merely passes a vertex alpha of 0.
	 *
	 * <p>Vertex alpha is not enough on 1.17. Its {@code rendertype_entity_cutout} core shader - the
	 * render type GeckoLib 3 uses - tests only the <em>texture's</em> alpha before discarding
	 * ({@code color = texture(...); if (color.a < 0.1) discard; color *= vertexColor;}), and the
	 * render type does not blend, so a vertex alpha of 0 is multiplied in after the test and then
	 * written to a framebuffer that ignores it. A dormant lizard therefore drew fully opaque - and
	 * untinted and unlit into the bargain, because {@link OreTintLayer} correctly refuses to give an
	 * invisible lizard its variant color and glow, which is what made it read as a plain white mob.
	 * (1.16.5 escapes this because it predates core shaders: its alpha test is a GL one against the
	 * final fragment, vertex alpha included.)
	 *
	 * <p>Skipping the render also freezes the animation controller while buried, exactly as on the
	 * other versions - see the GeckoLib timing notes in CLAUDE.md. That is the intended behaviour:
	 * dormancy is deliberately not represented as a held animation for precisely that reason.
	 */
	@Override
	public void render(OreLizardEntity entity, float entityYaw, float partialTick, PoseStack poseStack,
			MultiBufferSource bufferSource, int packedLight) {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player != null && entity.isInvisibleTo(player)) {
			return;
		}
		super.render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight);
	}
}
