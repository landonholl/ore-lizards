package com.orelizards.client;

import com.geckolib.cache.model.GeoBone;
import com.geckolib.cache.model.cuboid.CuboidGeoBone;
import com.geckolib.cache.model.cuboid.GeoCube;
import com.geckolib.constant.dataticket.DataTicket;
import com.geckolib.renderer.base.GeoRenderer;
import com.geckolib.renderer.base.PerBoneRender;
import com.geckolib.renderer.base.RenderPassInfo;
import com.geckolib.renderer.layer.GeoRenderLayer;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.orelizards.entity.OreLizardEntity;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;

import java.util.List;
import java.util.function.BiConsumer;

/**
 * Re-draws the "shards" and "eyes" bones with a color multiply matching the lizard's ore variant,
 * on top of their normal white/grey pass, so one texture can represent every ore type.
 *
 * <p>Both bones then get a further emissive pass, so they read as glowing crystal rather than
 * painted rock - the mob was very hard to pick out against cave stone otherwise.
 *
 * <p><b>26.2 / GeckoLib 5.5 shape.</b> Entity renderers do not draw. They <em>submit</em> geometry to
 * a {@link SubmitNodeCollector}, which records each submission's render type and a copy of its pose
 * under the collector order it was submitted at; once every entity is in, vanilla sorts each order's
 * submissions into phases (solid, translucent custom geometry, outline, ...), batches each phase by
 * render type into one shared {@code StagedVertexBuffer}, and executes those draws in sequence.
 * GeckoLib follows suit: its model pass is one {@code submitCustomGeometry} call, and a layer's
 * per-bone tasks ({@link #addPerBoneRender}) run at submission time with the pose stack placed at the
 * bone, so they too submit rather than draw, and the collector captures the bone's pose for us at the
 * moment the task runs. What is left of the three-pass structure is therefore small: the base model
 * (GeckoLib's own submission), the tint re-draw of the two bones (submitted from their per-bone task
 * into the model's own render type), and the emissive re-draw (submitted from the same task into
 * vanilla's {@code eyes} render type) - both extra passes one collector order after the body, see
 * {@link #submitBonePasses} for why. Everything the passes need from the entity is copied into the
 * render state in {@link #addRenderData} or already there on the vanilla state.
 *
 * <p>The render state is named outright as {@link LivingEntityRenderState}: GeckoLib 5.5 injects its
 * {@code GeoRenderState} interface into vanilla's {@code EntityRenderState} through a transitive class
 * tweaker, which Loom applies to the dev jar, so the intersection-bounded type parameter earlier ports
 * needed is gone (see {@link OreLizardRenderer}).
 */
public class OreTintLayer extends GeoRenderLayer<OreLizardEntity, Void, LivingEntityRenderState> {
	private static final String SHARDS_BONE = "shards";
	private static final String EYES_BONE = "eyes";

	/** Bones that get the variant tint and the emissive pass. */
	private static final List<String> GLOWING_BONES = List.of(SHARDS_BONE, EYES_BONE);

	/**
	 * Scales the additive glow pass. The emissive pass adds the variant color on top of the
	 * already-tinted bones, so 1.0 clips the brighter ores (diamond/emerald/gold) to near-white
	 * and loses their hue; this keeps the ore recognisable while still reading as a light source.
	 * Coal, being nearly black, barely glows at all - which is the behaviour we want for the
	 * shards, and means a coal lizard's eyes stay dark too.
	 */
	private static final float GLOW_STRENGTH = 0.7F;

	/**
	 * The collector order both extra passes are submitted at, one after the body's default order 0.
	 * Vanilla's own {@code EyesLayer} (spider and enderman eyes) submits at exactly this order for the
	 * reason given in {@link #submitBonePasses}.
	 */
	private static final int EXTRA_PASS_SUBMIT_ORDER = 1;

	/**
	 * The variant's tint colour, carried in the render state. GeckoLib 5 draws from a snapshot of the
	 * entity (the render state) rather than the entity itself, so the layer hooks never receive the
	 * entity: the colour is copied in by {@link #addRenderData} during extraction and read back through
	 * this ticket in the per-bone task. The id is namespaced because {@code DataTicket.create} dedupes
	 * on (type, id), so a bare "tint_color" would silently share a ticket with any other mod that picked
	 * the same name. Invisibility needs no ticket of its own: vanilla's
	 * {@code EntityRenderState.isInvisible} is {@code Entity.isInvisible()} at extraction time, which is
	 * what the 1.20.1 code checked directly.
	 */
	private static final DataTicket<Integer> TINT_COLOR = DataTicket.create("orelizards:tint_color", Integer.class);

	public OreTintLayer(GeoRenderer<OreLizardEntity, Void, LivingEntityRenderState> renderer) {
		super(renderer);
	}

	@Override
	public void addRenderData(OreLizardEntity animatable, Void relatedObject, LivingEntityRenderState renderState, float partialTick) {
		renderState.addGeckolibData(TINT_COLOR, animatable.getOreVariant().getTintColor());
	}

	/**
	 * Registers the two bones' extra passes. GeckoLib runs per-bone tasks right after it has submitted
	 * the model, with the pose stack placed at each bone's pivot in its animated pose for this frame.
	 *
	 * <p>Nothing is registered when the model isn't being drawn. {@code willRender()} is false when
	 * GeckoLib resolved no render type - an entity invisible to the viewer - and a dormant lizard is
	 * meant to draw nothing at all, tint included. (On GeckoLib 5.1 this guard was also load-bearing
	 * against a null-pose NPE inside GeckoLib; 5.4+ tasks no longer depend on a captured pose, so it is
	 * now purely the correct behaviour.)
	 */
	@Override
	public void addPerBoneRender(RenderPassInfo<LivingEntityRenderState> renderPassInfo,
			BiConsumer<GeoBone, PerBoneRender<LivingEntityRenderState>> consumer) {
		if (!renderPassInfo.willRender()) {
			return;
		}
		for (String boneName : GLOWING_BONES) {
			// Only cuboid bones have cubes to redraw; the geo has nothing else, this just keeps the cast
			// in submitBonePasses honest.
			renderPassInfo.model().getBone(boneName)
					.filter(CuboidGeoBone.class::isInstance)
					.ifPresent(bone -> consumer.accept(bone, this::submitBonePasses));
		}
	}

	/**
	 * The per-bone task: submits the tint pass and, for a visible lizard, the emissive pass for one
	 * bone. Runs at submission time, so nothing is drawn here - each {@code submitCustomGeometry} records
	 * a copy of the current pose (the bone's, courtesy of GeckoLib) and a callback vanilla invokes later
	 * with the buffer for that render type.
	 *
	 * <p><b>Tint.</b> Goes into the very render type the model was submitted with, so it is drawn with
	 * the same pipeline, texture and lighting as the body and differs only in colour. The renderer's own
	 * {@code renderColor} is deliberately not folded in: the variant colour is meant to multiply the
	 * texture as written, and the 1.20.1 original never modulated it by the base colour either.
	 *
	 * <p><b>Glow.</b> Goes into {@link RenderTypes#eyes}, the render type vanilla uses for enderman and
	 * spider eye overlays (coincidental name clash with our own "eyes" bone - the shards go through it
	 * too), for two reasons:
	 * <ul>
	 *   <li>Vanilla: its pipeline ({@code RenderPipelines.EYES}) never samples the lightmap, so the pass
	 *       is fullbright regardless of the light level in the cave, and it blends additively - the
	 *       bone visibly glows in the dark instead of just being brightly lit.</li>
	 *   <li>Shader packs: Iris/OptiFine route this render type through the {@code gbuffers_spidereyes}
	 *       program, which packs treat as emissive. GeckoLib's own {@code AutoGlowingGeoLayer} was the
	 *       obvious alternative, but it builds its own {@code geckolib_emissive} pipeline that packs
	 *       have no convention for, and it needs a separate {@code _glowmask} texture per skin - which
	 *       would also cost us the per-variant tint.</li>
	 * </ul>
	 * Depth testing still applies (only the depth <em>write</em> mask is off), so this doesn't shine
	 * through walls.
	 *
	 * <p><b>Why both passes are submitted one order later.</b> Each pass has to be drawn <em>after</em>
	 * the body: the tint at the same depth as the body's quads, where the later draw wins, and the glow
	 * writing no depth, so the body would simply paint over it. The old rule - never ask the buffer
	 * source for a different render type while the model's batch is being written - was one way of
	 * guaranteeing that; the 1.21.11 collector's per-render-type submission lists were another. 26.2 has
	 * neither. A submission goes into a phase of the collection for its order, chosen by its render
	 * type ({@code SubmitNodeCollection.submitCustomGeometry}: outline types to the outline phase,
	 * blending types like {@code eyes} to translucent custom geometry, everything else - the body and
	 * the tint - to solid), a phase collects every feature's submissions in a single list, and
	 * {@code SimpleFeatureRenderPhase.maybeShuffle} shuffles that list whenever
	 * {@code SharedConstants.DEBUG_SHUFFLE_MODELS} is on - Mojang's way of saying that the order of
	 * submissions <em>within</em> a phase is not something to rely on. What the collector does promise
	 * is the order between orders (ascending; {@code SubmitNodeStorage} keys its collections in an
	 * {@code Int2ObjectAVLTreeMap}) and between phases (a fixed list, solid before translucent custom
	 * geometry): {@code FeatureRenderDispatcher.renderAllFeatures} executes every solid phase first,
	 * order by order ascending, and only then the translucent ones, again order by order. So the body
	 * stays at order 0 and both extra passes go to order 1: the tint lands in order 1's solid phase,
	 * after every order-0 solid draw (the body's among them), and the glow in order 1's translucent
	 * custom geometry phase, which executes after every solid phase - so after both the body and the
	 * tint. Vanilla's own {@code EyesLayer} submits at order 1 for the same reason. Relative to the
	 * 1.21.11 port only the tint moved (from order 0 to 1); nothing visible changes, it just rests on an
	 * ordering the collector actually guarantees.
	 *
	 * <p>The glow is skipped entirely for an invisible lizard. A dormant one is meant to be undetectable,
	 * and a glow is exactly the thing that would give it away. GeckoLib already submits nothing for an
	 * entity invisible to the viewer, so this is belt-and-braces - it also covers a spectator, to whom
	 * vanilla shows invisible mobs as translucent ghosts: they get the tint but no glow - but it is the
	 * one case where getting it wrong breaks the core mechanic.
	 */
	private void submitBonePasses(RenderPassInfo<LivingEntityRenderState> renderPassInfo, GeoBone bone,
			SubmitNodeCollector renderTasks) {
		LivingEntityRenderState renderState = renderPassInfo.renderState();
		Identifier texture = getTextureResource(renderState);
		// GeckoLib 5 takes colours as one packed ARGB int - the form VertexConsumer.setColor(int) consumes.
		// Alpha forced opaque: a straight multiply over the base pass. White if the ticket were somehow
		// missing, which leaves the base texture as drawn rather than tinting it black.
		int tintRgb = renderState.getOrDefaultGeckolibData(TINT_COLOR, 0xFFFFFF);
		int packedLight = renderPassInfo.packedLight();
		int packedOverlay = renderPassInfo.packedOverlay();
		CuboidGeoBone cuboidBone = (CuboidGeoBone) bone;
		OrderedSubmitNodeCollector afterBody = renderTasks.order(EXTRA_PASS_SUBMIT_ORDER);

		RenderType bodyType = getRenderer().getRenderType(renderState, texture);
		if (bodyType != null) {
			int tint = opaque(tintRgb);
			afterBody.submitCustomGeometry(renderPassInfo.poseStack(), bodyType,
					(pose, buffer) -> drawBoneCubes(cuboidBone, pose, buffer, packedLight, packedOverlay, tint));
		}

		if (!renderState.isInvisible) {
			int glow = opaque(scaleRgb(tintRgb, GLOW_STRENGTH));
			// LightCoordsUtil.FULL_BRIGHT is 26.2's home for what LightTexture.FULL_BRIGHT used to be; the
			// eyes pipeline ignores the lightmap anyway, this just keeps the intent legible.
			afterBody.submitCustomGeometry(renderPassInfo.poseStack(), RenderTypes.eyes(texture),
					(pose, buffer) -> drawBoneCubes(cuboidBone, pose, buffer, LightCoordsUtil.FULL_BRIGHT, packedOverlay, glow));
		}
	}

	/**
	 * The draw half, invoked by vanilla during playback with the pose captured at submission and the
	 * buffer for the render type it was filed under. Draws just this bone's cubes - no children, and
	 * without re-applying the bone's own transform: GeckoLib hands a per-bone task the pose stack
	 * <em>at the bone's pivot</em> with the whole parent chain and this frame's animation already
	 * applied (so that things can be attached at the pivot), where its own model pass draws cubes from
	 * the bone's origin. Translating away from the pivot gets back to that origin; the cubes then
	 * position and rotate themselves exactly as in the model pass, one push/pop each because
	 * {@code GeoCube.render} does not restore the stack itself. This is GeckoLib's own recipe, from its
	 * {@code CustomBoneTextureGeoLayer}.
	 *
	 * <p>A fresh pose stack rather than the render pass's: playback happens long after the pass, when
	 * that stack belongs to whatever is being submitted now.
	 */
	private static void drawBoneCubes(CuboidGeoBone bone, PoseStack.Pose pose, VertexConsumer buffer,
			int packedLight, int packedOverlay, int color) {
		PoseStack poseStack = new PoseStack();
		poseStack.last().set(pose);
		bone.translateAwayFromPivotPoint(poseStack);
		for (GeoCube cube : bone.cubes) {
			poseStack.pushPose();
			cube.render(poseStack, buffer, packedLight, packedOverlay, color);
			poseStack.popPose();
		}
	}

	/** An 0xRRGGBB colour with the alpha byte set to fully opaque, as GeckoLib's packed-int colour expects. */
	private static int opaque(int rgb) {
		return 0xFF000000 | (rgb & 0xFFFFFF);
	}

	/**
	 * Scales each channel of an 0xRRGGBB colour. Truncates rather than rounds, which is what the
	 * float-channel path this replaced did when it quantised {@code channel / 255 * factor} back to a
	 * byte, so the glow lands on exactly the same values as before.
	 */
	private static int scaleRgb(int rgb, float factor) {
		int red = (int) (((rgb >> 16) & 0xFF) * factor);
		int green = (int) (((rgb >> 8) & 0xFF) * factor);
		int blue = (int) ((rgb & 0xFF) * factor);
		return (red << 16) | (green << 8) | blue;
	}
}
