package com.orelizards.entity;

import com.orelizards.entity.ai.FleeAndBurrowGoal;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Tier;
import net.minecraft.world.item.Tiers;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib3.core.IAnimatable;
import software.bernie.geckolib3.core.PlayState;
import software.bernie.geckolib3.core.builder.AnimationBuilder;
import software.bernie.geckolib3.core.builder.ILoopType.EDefaultLoopTypes;
import software.bernie.geckolib3.core.controller.AnimationController;
import software.bernie.geckolib3.core.event.predicate.AnimationEvent;
import software.bernie.geckolib3.core.manager.AnimationData;
import software.bernie.geckolib3.core.manager.AnimationFactory;
import software.bernie.geckolib3.util.GeckoLibUtil;

import java.util.Random;
import java.util.UUID;

public class OreLizardEntity extends PathfinderMob implements IAnimatable {
	public enum State {
		BURIED,
		ERUPTING,
		FLEEING,
		DIGGING_DOWN
	}

	private static final EntityDataAccessor<Integer> ORE_VARIANT =
			SynchedEntityData.defineId(OreLizardEntity.class, EntityDataSerializers.INT);
	// 1.16.5 has no deepslate, so nothing on this version ever sets this to true: every lizard is a
	// stone lizard. The tracked data, its NBT key and isDeepslate() are all kept regardless, so the
	// save format and the client-side texture choice stay identical to the versions that do have it.
	private static final EntityDataAccessor<Boolean> DEEPSLATE =
			SynchedEntityData.defineId(OreLizardEntity.class, EntityDataSerializers.BOOLEAN);
	// Synced so the client's own copy of the entity (which is what AnimationController actually
	// reads from, since that runs on the render thread) sees real state transitions - a plain
	// unsynced field only ever got updated in the server-gated half of tick(), so client-side
	// checks like "== State.DIGGING_DOWN" were permanently stuck at the initial default.
	private static final EntityDataAccessor<Integer> STATE =
			SynchedEntityData.defineId(OreLizardEntity.class, EntityDataSerializers.INT);

	// Save keys for the two pieces of tracked data that are decided once, at spawn, and can't be
	// re-derived afterwards. Without these a lizard whose chunk unloaded came back as whatever
	// defineSynchedData defaults to - a stone coal lizard - regardless of what it spawned as, so a
	// deepslate diamond one silently downgraded itself the first time a player walked out of range.
	// The variant is written by name rather than by ordinal so that reordering the enum, or
	// inserting a variant into the middle of it, doesn't rewrite the ore in every saved world.
	// State deliberately isn't saved - see readAdditionalSaveData.
	private static final String TAG_ORE_VARIANT = "OreVariant";
	private static final String TAG_DEEPSLATE = "Deepslate";
	// NBT type id of a string tag. 1.16.5's Tag interface has no TAG_STRING constant (Mojang added
	// the named ids in 1.17), so this is the literal from the NBT spec that later versions name.
	private static final int TAG_TYPE_STRING = 8;

	private static final UUID FLEE_SPEED_MODIFIER_ID = UUID.fromString("6f6a1f0a-6b6a-4e2b-9b8c-6f2e3a9d1a10");
	// Matches the literal top-level key in ore_lizard.animation.json - your real Blockbench
	// export uses bare "scuttle"/"idle" names, not the "animation.orelizard.X" prefix my earlier
	// hand-written conversion used, and GeckoLib does an exact string lookup against that key.
	private static final AnimationBuilder SCUTTLE_ANIM = new AnimationBuilder().addAnimation("scuttle", EDefaultLoopTypes.LOOP);
	// Play once and stay on the final frame. A one-shot that runs out stops its controller, which
	// drops the model back to its bind pose. Burrow's last frame leaves the body 32 units (two
	// blocks) underground, so reverting would pop the lizard back up above ground, in full view,
	// for the tail end of DIGGING_DOWN. Appear ends at the rest pose so it's unaffected either way,
	// but the same treatment removes any chance of a one-tick flicker between it finishing and
	// FLEEING.
	//
	// GeckoLib 3.0.107 declares HOLD_ON_LAST_FRAME but does not implement it: it is constructed with
	// the same looping=false flag as PLAY_ONCE and nothing in AnimationController or
	// AnimationProcessor ever tests for it, so at the end of the clip the controller still goes to
	// Stopped and the processor starts easing every bone back towards its rest pose. The loop type
	// is kept for intent; the actual hold is done with the reset speed - see animate().
	private static final AnimationBuilder BURROW_ANIM = new AnimationBuilder().addAnimation("burrow", EDefaultLoopTypes.HOLD_ON_LAST_FRAME);
	private static final AnimationBuilder APPEAR_ANIM = new AnimationBuilder().addAnimation("appear", EDefaultLoopTypes.HOLD_ON_LAST_FRAME);

	// Blend time when switching animations. The walk cycle wants to ease in; the state animations
	// must not - see animate() for why.
	private static final int MOVEMENT_TRANSITION_TICKS = 5;
	private static final int STATE_TRANSITION_TICKS = 0;

	// How long GeckoLib 3 takes to ease bones back to the rest pose once no animation is driving
	// them. 1 is GeckoLib's own default (AnimationData.resetTickLength) and stays in force in every
	// state but one. While DIGGING_DOWN it is pushed out to the value below, which is what actually
	// keeps the lizard underground after the 20-tick burrow clip ends: the processor still starts
	// easing the bones back the moment the controller stops, but at 1/1200th of the way per tick
	// the body has moved well under a percent of the two blocks by the time DIGGING_DOWN discards
	// the entity ten ticks later. There is no state after DIGGING_DOWN for the slow reset to leak
	// into, and every other state restores the default before anything of its own can stop.
	private static final double DEFAULT_RESET_TICKS = 1.0;
	private static final double HOLD_RESET_TICKS = 1200.0;

	// Spawn band: the whole stone layer, but still required to sit well under the terrain surface
	// so it stays a cave mob. 1.16.5's world floors at Y=0 and is stone all the way down, so unlike
	// 1.18+ there is no deepslate band to attribute a lizard to - every spawn is a stone lizard.
	private static final int MAX_SPAWN_Y = 50;
	private static final int MIN_DEPTH_BELOW_SURFACE = 8;

	// Spawn attempts that pass every other rule still fail 30% of the time, trimming the rate
	// without needing a fractional spawn weight (weights are ints, and ours is already at 1).
	private static final int SPAWN_CHANCE_PERCENT = 70;

	// Despawn tuning. Vanilla re-evaluates despawning every single tick per mob; we only bother
	// every 5 seconds, and even then bail out early on the cheap checks.
	private static final int DESPAWN_CHECK_INTERVAL = 100;
	// How far away the nearest player has to be before a dormant lizard is written off. 128 blocks
	// is this entity's own tracking range (trackRangeChunks(8) in ModEntities), so beyond it the
	// lizard isn't even being sent to a client - nobody can encounter it, and leaving it there only
	// holds a slot in the AMBIENT population cap that would otherwise let one spawn in the cave a
	// player is actually exploring. Inside that radius a dormant lizard never despawns at all.
	private static final double DORMANT_DESPAWN_RADIUS = 128.0;
	private static final double DORMANT_DESPAWN_RADIUS_SQ = DORMANT_DESPAWN_RADIUS * DORMANT_DESPAWN_RADIUS;

	private static final double TRIGGER_RANGE = 5.0;
	// Fallback search radius for something to flee from when the damage that woke the lizard had no
	// attacker behind it - lava, a falling anvil, a cactus. Matched to the pathfinder's FOLLOW_RANGE,
	// since the flee sweep can't meaningfully run away from anything further off than that anyway.
	private static final double PANIC_TARGET_SEARCH_RANGE = 16.0;
	// Matches the 1-second length of the "appear" animation so it isn't cut off mid-play by the
	// transition into FLEEING.
	private static final int ERUPT_DURATION_TICKS = 20;
	private static final int FLEE_DURATION_TICKS = 260;
	// Longer than the 20-tick burrow animation on purpose; the animation holds its last frame two
	// blocks underground, so the extra ticks are out of sight and just leave the lizard hittable
	// for a moment longer while it escapes.
	private static final int DIG_DURATION_TICKS = 30;
	// 2.5x flee speed reduced by 23% (per feedback that it was too fast to react to): 2.5 * 0.77 = 1.925x.
	private static final double FLEE_SPEED_BONUS = 0.925;

	// A light spark trail so a lizard you've startled stays trackable across a dark cave instead of
	// vanishing the moment it rounds a corner. One particle every few ticks is enough to follow -
	// this is meant to read as a glinting ore trail, not a firework display.
	private static final int SPARK_INTERVAL_TICKS = 3;
	// Roughly mid-body on a 0.6-high entity, so sparks come off the ore rather than the floor.
	private static final double SPARK_Y_OFFSET = 0.35;
	private static final double SPARK_SPREAD = 0.2;

	private final AnimationFactory factory = GeckoLibUtil.createFactory(this);

	private int stateTimer;
	@Nullable
	private LivingEntity fleeTarget;

	public OreLizardEntity(EntityType<? extends OreLizardEntity> type, Level level) {
		super(type, level);
		// Step over full blocks instead of jumping them. MoveControl only triggers a jump when the
		// height of the next waypoint exceeds maxUpStep, and the default 0.6 means every one-block
		// rise in a cave floor became a jump - which kills the mob's momentum and made the flee
		// speed boost read as much slower than it is. 1.0 is what vanilla gives horses and ravagers.
		// The same value feeds WalkNodeEvaluator, so paths now route over those rises as steps too.
		this.maxUpStep = 1.0F;
		this.goalSelector.addGoal(0, new FleeAndBurrowGoal(this));
		this.goalSelector.addGoal(1, new FloatGoal(this));
		// Look-flag goals, not move-flag - these run concurrently with fleeing rather than
		// competing for control, they just keep it from looking like a stiff statue during the
		// brief erupting/digging-down windows where it's visible but not actively pathing.
		this.goalSelector.addGoal(2, new LookAtPlayerGoal(this, Player.class, 6.0F));
		this.goalSelector.addGoal(3, new RandomLookAroundGoal(this));
	}

	public static AttributeSupplier.Builder createAttributes() {
		return PathfinderMob.createMobAttributes()
				.add(Attributes.MAX_HEALTH, 10.0)
				// 0.3 cut by a third to 0.199 (tuned by feel). Cut on the base attribute rather than on
				// FLEE_SPEED_BONUS so every speed the mob has scales together - the flee modifier
				// is MULTIPLY_TOTAL, so fleeing drops by the same third and keeps its 1.925x ratio
				// to the walk it is boosting.
				.add(Attributes.MOVEMENT_SPEED, 0.199)
				.add(Attributes.KNOCKBACK_RESISTANCE, 0.5)
				.add(Attributes.ARMOR, 15.0)
				.add(Attributes.ARMOR_TOUGHNESS, 8.0);
	}

	public static boolean canSpawn(EntityType<OreLizardEntity> type, ServerLevelAccessor level, MobSpawnType spawnType,
			BlockPos pos, Random random) {
		// Light level intentionally not checked - it should spawn regardless of a torch-carrying
		// player's light, so it can be found while exploring lit-up caves, not just pitch darkness.
		//
		// The ceiling reaches up into the stone band rather than stopping at Y=0 (on 1.18+ that
		// rule is what keeps the stone variant reachable at all; here it simply means the mob is
		// found throughout the cave layer rather than only near bedrock). Depth-below-surface
		// (rather than a light check) is what keeps it genuinely underground, since it works
		// during worldgen when lighting isn't computed yet and ignores player torches.
		// Cheapest gate first so the majority of rejected attempts never reach the heightmap lookup.
		return random.nextInt(100) < SPAWN_CHANCE_PERCENT
				&& pos.getY() < MAX_SPAWN_Y
				&& isUnderground(level, pos)
				&& level.getBlockState(pos.below()).is(BlockTags.BASE_STONE_OVERWORLD);
	}

	/**
	 * Well below the terrain surface for this column, rather than a sky-light test: this works
	 * during worldgen before lighting is computed, and ignores player-placed torches.
	 */
	private static boolean isUnderground(LevelReader level, BlockPos pos) {
		int surfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE, pos.getX(), pos.getZ());
		return pos.getY() < surfaceY - MIN_DEPTH_BELOW_SURFACE;
	}

	/**
	 * Ore Lizards are meant to be a rare find, so a player who surfaces for a moment shouldn't come
	 * back to an emptied-out cave system. Despawning is therefore split by state, and nothing like
	 * vanilla's roll:
	 *
	 * <ul>
	 *   <li><b>Dormant.</b> Never despawns while any player is within
	 *       {@value #DORMANT_DESPAWN_RADIUS} blocks, and never while the nearest player is still
	 *       underground - a buried lizard is the whole point of the mob, and one vanishing out of
	 *       the floor of a cave someone is exploring is indistinguishable from it never having
	 *       spawned. Past that radius it is outside its own tracking range and cannot be found by
	 *       anyone, so it is removed outright rather than left holding a slot in the shared AMBIENT
	 *       population cap that a lizard nearer the player could be using.</li>
	 *   <li><b>Activated.</b> Never despawns at all. It has already been seen, it is in the middle
	 *       of a scripted eruption/flee/burrow run, and it discards itself at the end of
	 *       {@link State#DIGGING_DOWN} anyway. Deleting it partway through is the one removal that
	 *       reads as the mob glitching out rather than as ordinary mob cleanup.</li>
	 * </ul>
	 *
	 * <p>This also only evaluates every {@value #DESPAWN_CHECK_INTERVAL} ticks instead of vanilla's
	 * every-tick check, with the expensive heightmap lookup behind both a tick gate and a distance
	 * gate, so on the common path it does less work than the implementation it replaces.
	 */
	@Override
	public void checkDespawn() {
		if (this.isPersistenceRequired() || this.requiresCustomPersistence()) {
			return;
		}
		if (this.getLizardState() != State.BURIED) {
			return;
		}
		if (this.tickCount % DESPAWN_CHECK_INTERVAL != 0) {
			return;
		}

		Player player = this.level.getNearestPlayer(this, -1.0);
		// No players in this dimension at all: nobody to preserve it for, but nobody to notice it
		// go either, and in practice its chunk isn't loaded to tick this. Leave it be.
		if (player == null) {
			return;
		}
		if (player.distanceToSqr(this) < DORMANT_DESPAWN_RADIUS_SQ) {
			return;
		}
		if (isUnderground(this.level, player.blockPosition())) {
			return;
		}

		// 1.16.5's Entity.remove() is what later versions split into discard(): the plain
		// "delete this entity" removal, as opposed to a death or a dimension change.
		this.remove();
	}

	@Override
	protected void defineSynchedData() {
		super.defineSynchedData();
		this.entityData.define(ORE_VARIANT, OreVariant.COAL.ordinal());
		this.entityData.define(DEEPSLATE, false);
		this.entityData.define(STATE, State.BURIED.ordinal());
	}

	@Override
	public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty, MobSpawnType spawnType,
			@Nullable SpawnGroupData spawnGroupData, @Nullable CompoundTag dataTag) {
		// On 1.18+ this is where a lizard below Y=-4 becomes a deepslate lizard, with the deepslate
		// texture, sounds and the diamond/emerald-heavy variant table. 1.16.5 has no deepslate and
		// its world floors at Y=0, so there is nothing to attribute: every lizard is a stone one and
		// rolls the uniform table. The flag is still set explicitly so spawn and load land on the
		// same value by the same route.
		this.setDeepslate(false);
		this.setOreVariant(OreVariant.random(this.random));
		this.becomeDormant();
		return super.finalizeSpawn(level, difficulty, spawnType, spawnGroupData, dataTag);
	}

	@Override
	public void addAdditionalSaveData(CompoundTag tag) {
		super.addAdditionalSaveData(tag);
		tag.putString(TAG_ORE_VARIANT, this.getOreVariant().name());
		tag.putBoolean(TAG_DEEPSLATE, this.isDeepslate());
	}

	/**
	 * Restores the two spawn-time properties and puts the lizard back in the ground.
	 *
	 * <p>The state machine is deliberately <em>not</em> saved. Its flee target is a live entity
	 * reference that can't survive a save in the first place, and the alternative - reloading
	 * mid-flee with nothing to run from - is a lizard standing motionless in the open until its
	 * timer expires and then burrowing away. Coming back dormant is both the better failure mode and
	 * the better fiction: it went back into the rock while nobody was loaded to watch. It also means
	 * loading a chunk can never, by itself, put a lizard into the burrow-and-discard path.
	 *
	 * <p>A lizard saved before these keys existed has no variant recorded, and keeps the
	 * {@link #defineSynchedData} default rather than being re-rolled - re-rolling would change the
	 * ore of an already-discovered lizard, which is worse than one legacy lizard reading as coal.
	 */
	@Override
	public void readAdditionalSaveData(CompoundTag tag) {
		super.readAdditionalSaveData(tag);
		if (tag.contains(TAG_ORE_VARIANT, TAG_TYPE_STRING)) {
			OreVariant variant = OreVariant.byName(tag.getString(TAG_ORE_VARIANT));
			if (variant != null) {
				this.setOreVariant(variant);
			}
		}
		this.setDeepslate(tag.getBoolean(TAG_DEEPSLATE));
		this.becomeDormant();
	}

	public OreVariant getOreVariant() {
		return OreVariant.values()[this.entityData.get(ORE_VARIANT)];
	}

	public boolean isDeepslate() {
		return this.entityData.get(DEEPSLATE);
	}

	public void setDeepslate(boolean deepslate) {
		this.entityData.set(DEEPSLATE, deepslate);
	}

	public void setOreVariant(OreVariant variant) {
		this.entityData.set(ORE_VARIANT, variant.ordinal());
	}

	public State getLizardState() {
		return State.values()[this.entityData.get(STATE)];
	}

	private void setLizardState(State state) {
		this.entityData.set(STATE, state.ordinal());
	}

	public boolean isFleeing() {
		return this.getLizardState() == State.FLEEING;
	}

	@Nullable
	public LivingEntity getFleeTarget() {
		return this.fleeTarget;
	}

	@Override
	public void tick() {
		super.tick();
		if (this.level.isClientSide) {
			return;
		}
		this.emitSparkTrail();
		switch (this.getLizardState()) {
			case BURIED:
				this.tickBuried();
				break;
			case ERUPTING:
				this.tickErupting();
				break;
			case FLEEING:
				this.tickFleeing();
				break;
			case DIGGING_DOWN:
				this.tickDiggingDown();
				break;
		}
	}

	/**
	 * Sparks off the lizard whenever it's out of the ground - erupting, fleeing or digging back
	 * down. Gated on the state rather than on movement so it doesn't cut out when the mob is briefly
	 * cornered or pathing round an obstacle, which is exactly when you're most likely to lose track
	 * of it. Never runs while BURIED: a dormant lizard throwing sparks would give the whole thing
	 * away. Runs ahead of the state switch so a lizard that discards itself this tick can't emit
	 * from beyond the grave.
	 */
	private void emitSparkTrail() {
		if (this.getLizardState() == State.BURIED || this.tickCount % SPARK_INTERVAL_TICKS != 0) {
			return;
		}
		if (!(this.level instanceof ServerLevel)) {
			return;
		}
		ServerLevel serverLevel = (ServerLevel) this.level;
		// Zero speed: the sparks are left hanging where the lizard was rather than being thrown, so
		// the trail marks its actual path. FireworkParticles fade and twinkle out on their own.
		serverLevel.sendParticles(ParticleTypes.FIREWORK, this.getX(), this.getY() + SPARK_Y_OFFSET, this.getZ(),
				1, SPARK_SPREAD, SPARK_SPREAD, SPARK_SPREAD, 0.0);
	}

	private void tickBuried() {
		// Uses vanilla's own named predicate rather than the boolean overload - that boolean's
		// polarity is the opposite of what it reads like (false = NO_SPECTATORS, which still
		// detects creative players), which previously let creative players wake dormant lizards.
		Player nearest = this.level.getNearestPlayer(this.getX(), this.getY(), this.getZ(), TRIGGER_RANGE,
				EntitySelector.NO_CREATIVE_OR_SPECTATOR);
		if (nearest != null) {
			this.beginErupting(nearest);
		}
	}

	private void tickErupting() {
		// Activation always supplies a real target, but a second is long enough for it to die or
		// log out before the eruption finishes.
		LivingEntity target = this.fleeTarget;
		if (!this.isValidFleeTarget(target)) {
			this.beginDiggingDown();
			return;
		}
		this.stateTimer--;
		if (this.stateTimer <= 0) {
			this.beginFleeing(target);
		}
	}

	private void tickFleeing() {
		// Nothing left to run from - the target died, disconnected, or changed dimension. The goal
		// has no anchor to path away from at that point, so the lizard would stand in the open for
		// the rest of the timer and only then burrow. Cut it short and go back into the ground.
		if (!this.isValidFleeTarget(this.fleeTarget)) {
			this.beginDiggingDown();
			return;
		}
		this.stateTimer--;
		if (this.stateTimer <= 0) {
			this.beginDiggingDown();
		}
	}

	private void tickDiggingDown() {
		this.stateTimer--;
		if (this.stateTimer <= 0) {
			this.spawnBurstParticles();
			this.remove();
		}
	}

	/**
	 * Every route out of dormancy goes through here, and every one of them has to name something to
	 * run away from. That is the invariant the rest of the state machine leans on: a lizard can only
	 * reach the burrow-and-discard path by having been activated by a specific entity first, so
	 * nothing incidental - a chunk load, a despawn check, a stray tick of environmental damage with
	 * nobody around - can quietly consume one that no player ever saw.
	 */
	private void beginErupting(LivingEntity target) {
		this.fleeTarget = target;
		this.setLizardState(State.ERUPTING);
		this.stateTimer = ERUPT_DURATION_TICKS;
		this.setInvisible(false);
		this.spawnBurstParticles();
	}

	private void beginFleeing(LivingEntity target) {
		this.fleeTarget = target;
		this.setLizardState(State.FLEEING);
		this.stateTimer = FLEE_DURATION_TICKS;
		AttributeInstance speed = this.getAttribute(Attributes.MOVEMENT_SPEED);
		if (speed != null && speed.getModifier(FLEE_SPEED_MODIFIER_ID) == null) {
			speed.addTransientModifier(new AttributeModifier(
					FLEE_SPEED_MODIFIER_ID, "Flee speed boost", FLEE_SPEED_BONUS,
					AttributeModifier.Operation.MULTIPLY_TOTAL));
		}
	}

	private void beginDiggingDown() {
		this.fleeTarget = null;
		this.setLizardState(State.DIGGING_DOWN);
		this.stateTimer = DIG_DURATION_TICKS;
		AttributeInstance speed = this.getAttribute(Attributes.MOVEMENT_SPEED);
		if (speed != null) {
			speed.removeModifier(FLEE_SPEED_MODIFIER_ID);
		}
		this.getNavigation().stop();
	}

	/**
	 * Puts the lizard back into the floor: dormant, invisible, no target, no flee boost. Used at
	 * spawn and on load, so both arrive at exactly the same resting state rather than at whatever
	 * the tracked-data defaults happen to be.
	 */
	private void becomeDormant() {
		this.fleeTarget = null;
		this.setLizardState(State.BURIED);
		this.stateTimer = 0;
		this.setInvisible(true);
		AttributeInstance speed = this.getAttribute(Attributes.MOVEMENT_SPEED);
		if (speed != null) {
			speed.removeModifier(FLEE_SPEED_MODIFIER_ID);
		}
	}

	/**
	 * Whether there is still a live thing in this world to run away from. Deliberately not a
	 * distance check: outrunning the target is the whole point, so it stays valid however far the
	 * lizard gets.
	 */
	private boolean isValidFleeTarget(@Nullable LivingEntity target) {
		return target != null && target.isAlive() && target.level == this.level;
	}

	private void spawnBurstParticles() {
		// Stone only: 1.16.5 has no deepslate and therefore no DEEPSLATE_BREAK sound (nor could
		// isDeepslate() be true here - see DEEPSLATE). The particles still sample the real block
		// below, so a lizard sitting on andesite or tuff-free granite dusts in that block's colour.
		this.playSound(SoundEvents.STONE_BREAK, 1.0F, 1.0F);
		if (!(this.level instanceof ServerLevel)) {
			return;
		}
		ServerLevel serverLevel = (ServerLevel) this.level;
		BlockState blockState = this.level.getBlockState(this.blockPosition().below());
		serverLevel.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, blockState),
				this.getX(), this.getY() + 0.5, this.getZ(), 20, 0.3, 0.3, 0.3, 0.05);
	}

	@Override
	public boolean hurt(DamageSource source, float amount) {
		Entity attacker = source.getEntity();
		if (attacker instanceof Player && (((Player) attacker).isCreative() || attacker.isSpectator())) {
			return false;
		}
		this.panicFromDamageIfDormant(source);
		if (this.isPickaxeHit(source)) {
			AttributeInstance armor = this.getAttribute(Attributes.ARMOR);
			AttributeInstance toughness = this.getAttribute(Attributes.ARMOR_TOUGHNESS);
			double armorBase = armor != null ? armor.getBaseValue() : 0;
			double toughnessBase = toughness != null ? toughness.getBaseValue() : 0;
			if (armor != null) armor.setBaseValue(0);
			if (toughness != null) toughness.setBaseValue(0);
			boolean result = super.hurt(source, amount);
			if (armor != null) armor.setBaseValue(armorBase);
			if (toughness != null) toughness.setBaseValue(toughnessBase);
			return result;
		}
		return super.hurt(source, amount);
	}

	@Override
	protected SoundEvent getHurtSound(DamageSource damageSource) {
		// Stone only - 1.16.5 has no DEEPSLATE_HIT, and no lizard here is a deepslate one anyway.
		return SoundEvents.STONE_HIT;
	}

	/**
	 * The scuttle sound. Vanilla calls this from {@code Entity.move()} paced by distance
	 * travelled, so it automatically speeds up with the 1.925x flee boost rather than needing its
	 * own timer. Stone to match its body (1.16.5 has no deepslate step sound to pick instead),
	 * pitched well up so it reads as a small skittering critter rather than something heavy walking.
	 */
	@Override
	protected void playStepSound(BlockPos pos, BlockState state) {
		this.playSound(SoundEvents.STONE_STEP, 0.18F, 1.6F);
	}

	/**
	 * Vanilla suffocates any living entity whose hitbox overlaps a solid block each tick - fine
	 * for normal mobs, but ours is meant to sit embedded in stone while dormant and sink back into
	 * it while burrowing down, so it needs to be exempt during those phases. Only FLEEING (out in
	 * open cave air) keeps the normal vanilla check as a safety net.
	 */
	@Override
	public boolean isInWall() {
		return this.getLizardState() == State.FLEEING && super.isInWall();
	}

	/**
	 * LivingEntity re-evaluates the invisibility flag EVERY tick as
	 * {@code setInvisible(hasEffect(INVISIBILITY))}, which silently wiped the one-time
	 * {@code setInvisible(true)} from {@link #finalizeSpawn} on the very next tick - that's why
	 * wild lizards were sitting in plain sight instead of hidden. Re-assert it while dormant.
	 */
	@Override
	protected void updateInvisibilityStatus() {
		if (this.getLizardState() == State.BURIED) {
			this.setInvisible(true);
			return;
		}
		super.updateInvisibilityStatus();
	}

	/**
	 * A dormant lizard is invisible and meant to read as "part of the floor" - players shouldn't
	 * bump into an unseen hitbox or be able to shove it around.
	 */
	@Override
	public boolean isPushable() {
		return this.getLizardState() != State.BURIED && super.isPushable();
	}

	/**
	 * A real animal that gets hurt doesn't wait around to notice you're close - it bolts
	 * immediately, skipping the eruption rather than spending a second rising out of the ground
	 * while something hits it. Previously only proximity triggered the flee response, so a lizard
	 * struck by AoE damage (or found and hit while still buried) just sat there.
	 *
	 * <p>Damage with nobody behind it now leaves the lizard dormant. It used to flee from a null
	 * target instead, which the flee goal cannot path away from: the lizard stood visible and
	 * motionless in the open for the full {@value #FLEE_DURATION_TICKS} ticks, then burrowed and
	 * deleted itself. A lizard taking environmental damage with no player in sight stays in the rock
	 * and takes it.
	 */
	private void panicFromDamageIfDormant(DamageSource source) {
		State current = this.getLizardState();
		if (current != State.BURIED && current != State.ERUPTING) {
			return;
		}

		Entity attacker = source.getEntity();
		LivingEntity threat = attacker instanceof LivingEntity ? (LivingEntity) attacker : null;
		if (threat == null) {
			// Environmental damage - lava, a falling block, a hit from something with no owner.
			// There is usually still a player behind it, so run from the nearest one if there is any.
			threat = this.level.getNearestPlayer(this.getX(), this.getY(), this.getZ(),
					PANIC_TARGET_SEARCH_RANGE, EntitySelector.NO_CREATIVE_OR_SPECTATOR);
		}
		if (!this.isValidFleeTarget(threat)) {
			return;
		}

		this.setInvisible(false);
		this.beginFleeing(threat);
		this.spawnBurstParticles();
	}

	private boolean isPickaxeHit(DamageSource source) {
		Entity attacker = source.getEntity();
		if (!(attacker instanceof Player)) {
			return false;
		}
		ItemStack weapon = ((Player) attacker).getMainHandItem();
		Item item = weapon.getItem();
		if (!(item instanceof PickaxeItem)) {
			return false;
		}
		Tier tier = ((PickaxeItem) item).getTier();
		return tier == Tiers.IRON || tier == Tiers.DIAMOND || tier == Tiers.NETHERITE;
	}

	@Override
	protected void dropCustomDeathLoot(DamageSource damageSource, int lootingMultiplier, boolean allowDrops) {
		super.dropCustomDeathLoot(damageSource, lootingMultiplier, allowDrops);
		if (!allowDrops) {
			return;
		}
		OreVariant variant = this.getOreVariant();
		ItemLike dropItem = variant.getDropItem();
		this.spawnAtLocation(new ItemStack(dropItem, variant.rollDropCount(this.random)));
	}

	@Override
	public AnimationFactory getFactory() {
		return this.factory;
	}

	@Override
	public void registerControllers(AnimationData data) {
		// The AnimationData is this entity's own (the factory creates one per entity id and calls
		// this exactly once for it), so it is captured here and handed to the predicate: it owns the
		// bone reset speed, which animate() uses to hold the burrow pose - see HOLD_RESET_TICKS.
		data.addAnimationController(new AnimationController<>(this, "movement", MOVEMENT_TRANSITION_TICKS,
				event -> this.animate(event, data)));
	}

	private PlayState animate(AnimationEvent<OreLizardEntity> event, AnimationData data) {
		AnimationController<OreLizardEntity> controller = event.getController();
		// State checks take priority over the generic movement check below, so burrow/appear
		// can't get interrupted by some incidental movement source during those windows.
		State lizardState = this.getLizardState();

		// Both state animations start with a zero-tick transition. GeckoLib otherwise spends
		// transitionLengthTicks blending from whatever pose the model is currently in into the
		// new animation's first frame, and only starts the animation's own clock once that blend
		// finishes. For "appear" that was ruinous: its first frame puts the body 13 units (0.81
		// blocks) underground, but the pose it blends from is the rest pose at ground level - so
		// the lizard became visible standing on top of the block, slid down into it over a
		// quarter second, and only then began erupting. "burrow" starts from the rest pose so it
		// never sank, but it still stood there motionless for those five ticks before digging.
		// Starting on frame one also makes the animation lengths line up with the state timers:
		// appear is exactly 1 second, as is ERUPT_DURATION_TICKS, where previously the transition
		// ate a quarter of the eruption. (GeckoLib 3 exposes the blend length as a public field.)
		if (lizardState == State.DIGGING_DOWN) {
			controller.transitionLengthTicks = STATE_TRANSITION_TICKS;
			// The hold. Set before the clip can end, and left in place until the entity is gone.
			data.setResetSpeedInTicks(HOLD_RESET_TICKS);
			controller.setAnimation(BURROW_ANIM);
			return PlayState.CONTINUE;
		}
		data.setResetSpeedInTicks(DEFAULT_RESET_TICKS);
		if (lizardState == State.ERUPTING) {
			controller.transitionLengthTicks = STATE_TRANSITION_TICKS;
			controller.setAnimation(APPEAR_ANIM);
			return PlayState.CONTINUE;
		}

		controller.transitionLengthTicks = MOVEMENT_TRANSITION_TICKS;
		// Driven by actual velocity/limb-swing (GeckoLib's isMoving(), same signal vanilla
		// mobs use for their walk cycle) rather than our own isFleeing() flag, so it scuttles
		// whenever it's genuinely moving for any reason - fleeing, knockback, pushed by
		// another entity, etc. - not only during the AI's own flee state.
		if (event.isMoving()) {
			controller.setAnimation(SCUTTLE_ANIM);
			return PlayState.CONTINUE;
		}
		return PlayState.STOP;
	}
}
