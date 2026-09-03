package com.orelizards.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.orelizards.entity.OreLizardEntity;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.constant.dataticket.DataTicket;
import software.bernie.geckolib.renderer.base.GeoRenderState;
import software.bernie.geckolib.renderer.base.GeoRenderer;
import software.bernie.geckolib.renderer.layer.GeoRenderLayer;
import software.bernie.geckolib.util.RenderUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * Re-draws the "shards" and "eyes" bones with a color multiply matching the lizard's ore variant,
 * on top of their normal white/grey pass, so one texture can represent every ore type.
 *
 * <p>Both bones then get a further emissive pass, so they read as glowing crystal rather than
 * painted rock - the mob was very hard to pick out against cave stone otherwise.
 *
 * <p><b>1.21.9+ / GeckoLib 5.3-alpha-3 shape.</b> Entity renderers no longer draw. They <em>submit</em>
 * geometry to a {@link SubmitNodeCollector}, which records each submission's render type and a copy
 * of its pose; vanilla then plays every submission back grouped by render type once all entities are
 * in, fetching one buffer per type. GeckoLib follows suit: its model pass is one
 * {@code submitCustomGeometry} call whose playback callback first runs the animation for the frame
 * (posing the shared {@link GeoBone}s) and then draws the bone tree, and a layer's
 * {@link #submitRenderTask} hook runs right after that submission, with the pose stack still at the
 * model's root. The three-pass structure is unchanged from 1.20.1 - base model, the two bones again
 * multiplied by the variant colour, then those two bones once more fullbright through vanilla's
 * {@code eyes} render type - and so is its mechanism: the tint pass walks the bone tree itself and
 * <em>captures each glowing bone's pose</em> as it draws it, and the emissive pass, submitted one
 * collector order later, draws from those captured poses. See {@link #submitRenderTask} for why the
 * capture is back (it had gone on GeckoLib 5.4) and why the passes are placed where they are.
 * Everything the passes need from the entity is copied into the render state in
 * {@link #addRenderData} or already there on the vanilla state.
 */
public class OreTintLayer<R extends LivingEntityRenderState & GeoRenderState> extends GeoRenderLayer<OreLizardEntity, Void, R> {
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
	 * The submission order the emissive pass goes into. Vanilla's own {@code EyesLayer} (spider and
	 * enderman eyes) submits at exactly this order for exactly the reason given in
	 * {@link #submitRenderTask}; the body and the tint stay at the default order 0.
	 */
	private static final int GLOW_SUBMIT_ORDER = 1;

	/**
	 * The variant's tint colour, carried in the render state. GeckoLib 5 draws from a snapshot of the
	 * entity (the render state) rather than the entity itself, so the layer hooks never receive the
	 * entity: the colour is copied in by {@link #addRenderData} during extraction and read back through
	 * this ticket at submission. The id is namespaced because {@code DataTicket.create} dedupes on
	 * (type, id), so a bare "tint_color" would silently share a ticket with any other mod that picked
	 * the same name. Invisibility needs no ticket of its own: vanilla's
	 * {@code EntityRenderState.isInvisible} is {@code Entity.isInvisible()} at extraction time, which is
	 * what the 1.20.1 code checked directly.
	 */
	private static final DataTicket<Integer> TINT_COLOR = DataTicket.create("orelizards:tint_color", Integer.class);

	/**
	 * A glowing bone together with the pose it was drawn at during the tint pass - the bone's own
	 * origin, with the whole parent chain and this frame's animation applied. The emissive pass draws
	 * the same bone from this pose later, so it lands exactly on top of the tinted geometry.
	 */
	private record PosedBone(GeoBone bone, PoseStack.Pose pose) {
	}

	public OreTintLayer(GeoRenderer<OreLizardEntity, Void, R> renderer) {
		super(renderer);
	}

	@Override
	public void addRenderData(OreLizardEntity animatable, Void relatedObject, R renderState, float partialTick) {
		renderState.addGeckolibData(TINT_COLOR, animatable.getOreVariant().getTintColor());
	}

	/**
	 * Submits the tint pass and, for a visible lizard, the emissive pass. Runs at submission time,
	 * immediately after GeckoLib has submitted the model itself, so nothing is drawn here - each
	 * {@code submitCustomGeometry} records a copy of the current pose (the model's root, courtesy of
	 * GeckoLib) and a callback vanilla invokes later with the buffer for that render type.
	 *
	 * <p>Nothing is submitted when the model isn't being drawn. {@code didRenderModel} is false when
	 * GeckoLib resolved no render type - an entity invisible to the viewer - and a dormant lizard is
	 * meant to draw nothing at all, tint included.
	 *
	 * <p><b>Tint.</b> Goes into the very render type the model was submitted with, at the same order.
	 * Vanilla files submissions per render type in submission order and writes each type's list into one
	 * buffer, so this lands in the model's own batch, directly after the model's own quads - the same
	 * "append to the batch in progress, never a swap" that the 1.20.1 layer relied on, now guaranteed by
	 * the collector rather than by us. That adjacency is also what makes the pose right: GeckoLib's
	 * model callback runs the animation for this lizard (posing the {@link GeoBone}s, which are shared
	 * by every lizard using the model) and then draws, and ours runs next, before any other lizard's
	 * callback can re-pose them. The callback walks the bone tree from the root with GeckoLib's own
	 * per-bone transform ({@link RenderUtil#prepMatrixForBone}), draws only the two glowing bones'
	 * cubes tinted, and keeps a copy of each one's pose for the glow. The renderer's own
	 * {@code renderColor} is deliberately not folded in: the variant colour is meant to multiply the
	 * texture as written, and the 1.20.1 original never modulated it by the base colour either.
	 *
	 * <p><b>Glow.</b> Goes into {@link RenderType#eyes}, the render type vanilla uses for enderman and
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
	 * <p><b>Why the glow is submitted one order later.</b> The old rule was "never ask the buffer source
	 * for a different render type while the model's batch is being written", because every render type
	 * without a dedicated buffer shares one builder, and asking for another shared type draws whatever
	 * that builder holds. That is still how {@code MultiBufferSource.BufferSource} works on 1.21.10 -
	 * and the entity cutout types are not among the dedicated buffers (only the glint types and the
	 * water mask are), so the body itself lives in the shared builder. The collector replays custom
	 * geometry per render type from a plain {@code HashMap}, with no ordering between types: had the
	 * glow been filed at order 0 alongside the body, the {@code eyes} list could be replayed first,
	 * drawn to the GPU the moment the body's type was requested next, and then painted over by the body
	 * (which the glow, writing no depth, cannot keep out). Orders are replayed in ascending sequence,
	 * each in full before the next, so submitting the glow at order 1 means the body has already been
	 * written - and its batch is flushed by the first shared-type request order 1 makes - before a
	 * single glow quad is buffered. The same ordering property the 1.20.1 code got from doing the swap
	 * after the whole model, expressed in the collector's terms; vanilla's {@code EyesLayer} does
	 * exactly this.
	 *
	 * <p><b>Why the glow draws from captured poses.</b> By the time order 1 is replayed, every order-0
	 * callback has run, and the shared bones hold whichever lizard was drawn last - so the glow cannot
	 * simply walk the tree again, or with two lizards in view one of them would glow in the other's
	 * pose. Re-running the animation from the glow callback (which is what GeckoLib's own
	 * {@code TextureLayerGeoLayer} does) is not safe either: a second tick in the same frame re-poses a
	 * controller the handler had just told to {@code STOP}. So the tint callback, which is guaranteed
	 * to run with this lizard's pose, records the pose of each glowing bone as it draws it, and the
	 * glow callback draws the same cubes from those records. Orders replay in sequence, so the capture
	 * always precedes the read. This is the 1.20.1 layer's "capture during the bone pass, draw after
	 * the model" again, with the collector supplying the deferral.
	 *
	 * <p>GeckoLib's per-bone render tasks ({@code addPerBoneRender}), which the 5.4 port used for this,
	 * are deliberately not used on 5.3-alpha-3: they run at submission time, before any callback has
	 * animated the bones for this frame, so they see the previous frame's pose (or another lizard's),
	 * and the alpha's {@code GeoBone.transformToBone} applies the bone chain child-first, which places a
	 * nested bone like {@code shards} (body → shards) or {@code eyes} (body → head → eyes) wrongly.
	 *
	 * <p>The glow is skipped entirely for an invisible lizard. A dormant one is meant to be undetectable,
	 * and a glow is exactly the thing that would give it away. GeckoLib already submits nothing for an
	 * entity invisible to the viewer, so this is belt-and-braces - it also covers a spectator, to whom
	 * vanilla shows invisible mobs as translucent ghosts: they get the tint but no glow - but it is the
	 * one case where getting it wrong breaks the core mechanic.
	 */
	@Override
	public void submitRenderTask(R renderState, PoseStack poseStack, BakedGeoModel bakedModel, SubmitNodeCollector renderTasks,
			CameraRenderState cameraState, int packedLight, int packedOverlay, int renderColor, boolean didRenderModel) {
		if (!didRenderModel) {
			return;
		}
		ResourceLocation texture = getTextureResource(renderState);
		RenderType bodyType = getRenderer().getRenderType(renderState, texture);
		if (bodyType == null) {
			return;
		}

		// GeckoLib 5 takes colours as one packed ARGB int - the form VertexConsumer.setColor(int) consumes.
		// Alpha forced opaque: a straight multiply over the base pass. White if the ticket were somehow
		// missing, which leaves the base texture as drawn rather than tinting it black.
		int tintRgb = renderState.getOrDefaultGeckolibData(TINT_COLOR, 0xFFFFFF);
		int tint = opaque(tintRgb);
		// Filled by the tint callback, read by the glow callback. Fresh per submission, so nothing
		// carries over between frames or between lizards.
		List<PosedBone> glowingBones = new ArrayList<>(GLOWING_BONES.size());

		renderTasks.submitCustomGeometry(poseStack, bodyType, (pose, buffer) -> {
			// A fresh pose stack rather than the render pass's: playback happens long after the pass,
			// when that stack belongs to whatever is being submitted now.
			PoseStack stack = new PoseStack();
			stack.last().set(pose);
			for (GeoBone bone : bakedModel.topLevelBones()) {
				drawTintedBones(renderState, stack, bone, buffer, cameraState, packedLight, packedOverlay, tint, glowingBones);
			}
		});

		if (renderState.isInvisible) {
			return;
		}
		int glow = opaque(scaleRgb(tintRgb, GLOW_STRENGTH));
		renderTasks.order(GLOW_SUBMIT_ORDER).submitCustomGeometry(poseStack, RenderType.eyes(texture), (pose, buffer) -> {
			PoseStack stack = new PoseStack();
			for (PosedBone posed : glowingBones) {
				stack.last().set(posed.pose());
				getRenderer().renderCubesOfBone(renderState, posed.bone(), stack, buffer, cameraState,
						LightTexture.FULL_BRIGHT, packedOverlay, glow);
			}
		});
	}

	/**
	 * The tint pass's bone walk. Applies exactly the transform GeckoLib's own model pass applies to
	 * each bone ({@code translate to bone, to pivot, rotate, scale, away from pivot} - one call on
	 * {@link RenderUtil}, so the two can't drift apart), draws the cubes of the glowing bones only,
	 * records their poses, and recurses like {@code renderChildBones} does. Non-glowing bones
	 * contribute their transform and nothing else. {@code renderCubesOfBone} honours a hidden bone
	 * itself.
	 */
	private void drawTintedBones(R renderState, PoseStack stack, GeoBone bone, VertexConsumer buffer, CameraRenderState cameraState,
			int packedLight, int packedOverlay, int tint, List<PosedBone> glowingBones) {
		stack.pushPose();
		RenderUtil.prepMatrixForBone(stack, bone);
		if (GLOWING_BONES.contains(bone.getName())) {
			getRenderer().renderCubesOfBone(renderState, bone, stack, buffer, cameraState, packedLight, packedOverlay, tint);
			glowingBones.add(new PosedBone(bone, stack.last().copy()));
		}
		if (!bone.isHidingChildren()) {
			for (GeoBone child : bone.getChildBones()) {
				drawTintedBones(renderState, stack, child, buffer, cameraState, packedLight, packedOverlay, tint, glowingBones);
			}
		}
		stack.popPose();
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
