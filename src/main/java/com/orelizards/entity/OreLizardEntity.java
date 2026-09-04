package com.orelizards.entity;

import com.orelizards.entity.ai.FleeAndBurrowGoal;
import com.orelizards.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.damagesource.DamageSource;
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
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.core.object.PlayState;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.UUID;

public class OreLizardEntity extends PathfinderMob implements GeoEntity {
	public enum State {
		BURIED,
		ERUPTING,
		FLEEING,
		DIGGING_DOWN
	}

	private static final EntityDataAccessor<Integer> ORE_VARIANT =
			SynchedEntityData.defineId(OreLizardEntity.class, EntityDataSerializers.INT);
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

	private static final UUID FLEE_SPEED_MODIFIER_ID = UUID.fromString("6f6a1f0a-6b6a-4e2b-9b8c-6f2e3a9d1a10");
	// Matches the literal top-level key in ore_lizard.animation.json - your real Blockbench
	// export uses bare "scuttle"/"idle" names, not the "animation.orelizard.X" prefix my earlier
	// hand-written conversion used, and GeckoLib does an exact string lookup against that key.
	private static final RawAnimation SCUTTLE_ANIM = RawAnimation.begin().thenLoop("scuttle");
	// thenPlayAndHold, not thenPlay: a PLAY_ONCE animation stops its controller on completion,
	// which drops the model back to its bind pose. Burrow's last frame leaves the body 32 units
	// (two blocks) underground, so reverting would pop the lizard back up above ground, in full
	// view, for the tail end of DIGGING_DOWN. Holding the final frame keeps it buried until the
	// entity is discarded. Appear ends at the rest pose so it's unaffected either way, but the
	// same treatment removes any chance of a one-tick flicker between it finishing and FLEEING.
	private static final RawAnimation BURROW_ANIM = RawAnimation.begin().thenPlayAndHold("burrow");
	private static final RawAnimation APPEAR_ANIM = RawAnimation.begin().thenPlayAndHold("appear");

	// Blend time when switching animations. The walk cycle wants to ease in; the state animations
	// must not - see registerControllers for why.
	private static final int MOVEMENT_TRANSITION_TICKS = 5;
	private static final int STATE_TRANSITION_TICKS = 0;

	// Spawn band: high enough to reach the stone layer (deepslate takes over below Y=-8), but
	// still required to sit well under the terrain surface so it stays a cave mob.
	private static final int MAX_SPAWN_Y = 50;
	private static final int MIN_DEPTH_BELOW_SURFACE = 8;
	// 1.20.1 worldgen replaces stone with deepslate entirely below Y=-8, blending randomly from
	// Y=0 down. -4 is the midpoint of that band, so it's the closest single threshold to what the
	// surrounding rock actually looks like - and it's the rule that drives the distribution in the
	// first place, so reading it directly beats sampling blocks at spawn time.
	private static final int DEEPSLATE_Y_LEVEL = -4;

	// Despawn tuning. Vanilla re-evaluates despawning every single tick per mob; we only bother
	// every 5 seconds, and even then bail out early on the cheap checks.
	private static final int DESPAWN_CHECK_INTERVAL = 100;
	// How far away the nearest player has to be before a dormant lizard is written off. This was
	// 128 - the entity's own tracking range - back when a lizard was a rare natural spawn worth
	// hoarding wherever it landed. Under the encounter director the incentive inverts: every lizard
	// in the world was deliberately placed a short walk ahead of one specific player, so a radius
	// that generous makes a placement nobody stepped on effectively immortal, and a leftover like
	// that suppresses the next placement through the director's own nearby-lizard check. 48 matches
	// EncounterDirector.PENDING_ABANDON_DISTANCE so the director's abandon path and this cull agree
	// on when an encounter has been missed instead of each waiting on the other.
	private static final double DORMANT_DESPAWN_RADIUS = 48.0;
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

	private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

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
		this.setMaxUpStep(1.0F);
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

	/**
	 * Somewhere a lizard belongs, vertically: inside the stone band and well under the terrain
	 * surface for this column.
	 *
	 * <p>Light level is intentionally not part of this. A lizard should be findable in a cave
	 * somebody has already torched up, not only in pitch darkness, and depth-below-surface is
	 * measurable during worldgen before lighting has been computed at all - which the old
	 * {@code canSpawn} needed, and which the encounter director still leans on when it asks this
	 * same question about the player's own position once a second.
	 *
	 * <p>The ceiling is {@value #MAX_SPAWN_Y} rather than the Y=0 it originally was: 1.20.1 worldgen
	 * replaces stone with deepslate entirely below Y=-8, so a Y&lt;0 rule put every lizard on
	 * deepslate and made the stone variants unreachable.
	 *
	 * <p>Callers own the chunk-loading question - {@code getHeight} generates the chunk if it is
	 * missing, on the calling thread.
	 */
	public static boolean isCaveDepth(LevelReader level, BlockPos pos) {
		return pos.getY() < MAX_SPAWN_Y && isUnderground(level, pos);
	}

	/**
	 * The full "a lizard may be placed here" rule: cave depth, standing on natural overworld stone,
	 * and not in a fluid.
	 *
	 * <p>The fluid clause is belt-and-braces. {@code CaveTerrain.isStandable} now refuses wet blocks
	 * itself, but it did not always: vanilla's {@code LiquidBlock.isPathfindable(LAND)} is true for
	 * anything but lava, so the director's first playtest placed both of its lizards on flooded
	 * cave floors, and a dormant lizard cannot breathe underwater. It takes its first drowning tick
	 * 320 ticks (16 s) after placement and is dead by 400: with a survival player inside
	 * {@value #PANIC_TARGET_SEARCH_RANGE} blocks the final hit sent it fleeing, which the director
	 * scored as a delivered encounter nobody saw; with only a spectator in range it died in the
	 * floor. The clause stays here so that the placement rule is safe on its own terms and does not
	 * silently depend on which floor-finder fed it the position.
	 *
	 * <p>This is what {@code canSpawn} used to be, minus its 30% rejection roll. That roll only ever
	 * existed because vanilla spawn weights are integers and ours was already at the floor of 1;
	 * the encounter director sets its cadence in minutes, so a dice roll on top would add nothing
	 * but noise. The rules survive here, the method does not: {@code canSpawn}'s signature names
	 * {@code MobSpawnType}, which is {@code EntitySpawnReason} from 1.21.3 on, and that would drag
	 * a per-version type into a director call path that is otherwise identical on all 20 branches.
	 * The vanilla {@code SpawnPlacements} registration adapts to this with a lambda, whose parameter
	 * types are inferred and so never written down.
	 */
	public static boolean isDirectorSiteValid(LevelReader level, BlockPos pos) {
		return isCaveDepth(level, pos)
				&& level.getBlockState(pos.below()).is(BlockTags.BASE_STONE_OVERWORLD)
				&& level.getFluidState(pos).isEmpty();
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
	 * Places a fully-formed dormant lizard at {@code pos}, or returns null if the level refused it.
	 *
	 * <p>Going through {@link EntityType#spawn} is load-bearing, not stylistic. It runs
	 * {@code create} then positions the entity then calls {@link #finalizeSpawn} and only then adds
	 * it to the level - and our {@code finalizeSpawn} derives both the deepslate flag and the ore
	 * variant from {@code blockPosition().getY()}. Constructing the entity by hand and calling
	 * {@code finalizeSpawn} yourself therefore hands you a stone coal lizard wherever you put it,
	 * Y=-50 deepslate included. This mod has already shipped that bug once. It is also what runs
	 * {@code becomeDormant()}, so the lizard arrives invisible, buried and NoAi with no further work.
	 *
	 * <p>{@code setPersistenceRequired()} is deliberately <em>not</em> called on the result. It
	 * short-circuits {@link #checkDespawn} outright, so any placement the player never walked into
	 * would sit in that cave permanently. A director-placed lizard is meant to be collected by
	 * exactly the same rule as every other one: nobody is near it, nobody can find it, remove it.
	 *
	 * <p>One of the four lines a port branch has to touch: the reason enum is
	 * {@code EntitySpawnReason} from 1.21.3 on, and this 3-argument overload does not exist before
	 * 1.19.4, where the 8-argument form is the fallback. Both changes are contained in this method.
	 */
	@Nullable
	public static OreLizardEntity spawnDormant(ServerLevel level, BlockPos pos) {
		return ModEntities.ORE_LIZARD.spawn(level, pos, MobSpawnType.NATURAL);
	}

	/**
	 * Ore Lizards are meant to be a rare find, so a player who surfaces for a moment shouldn't come
	 * back to an emptied-out cave system. Despawning is therefore split by state, and nothing like
	 * vanilla's roll:
	 *
	 * <ul>
	 *   <li><b>Dormant.</b> Never despawns while any player is within
	 *       {@value #DORMANT_DESPAWN_RADIUS} blocks - a buried lizard is the whole point of the mob,
	 *       and one vanishing out of the floor of the cave someone is standing in is
	 *       indistinguishable from it never having spawned. Past that radius the player has walked
	 *       away from an encounter that was placed for them specifically, so it is removed rather
	 *       than left waiting on a return trip.
	 *       <p>There was a second keep-clause here until the encounter director landed: never
	 *       despawn while the nearest player is still underground. It protected a rare natural spawn
	 *       from being culled out of a cave system somebody was still working through. Under the
	 *       director it inverts, because every lizard in the world was placed for a specific
	 *       underground player - the clause is true by construction for exactly the lizards that
	 *       most need collecting, so a missed placement would never be collected at all.</li>
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

		Player player = this.level().getNearestPlayer(this, -1.0);
		// No players in this dimension at all: nobody to preserve it for, but nobody to notice it
		// go either, and in practice its chunk isn't loaded to tick this. Leave it be.
		if (player == null) {
			return;
		}
		if (player.distanceToSqr(this) < DORMANT_DESPAWN_RADIUS_SQ) {
			return;
		}

		this.discard();
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
		boolean deepslate = this.blockPosition().getY() < DEEPSLATE_Y_LEVEL;
		this.setDeepslate(deepslate);
		this.setOreVariant(deepslate ? OreVariant.randomDeepslate(this.random) : OreVariant.random(this.random));
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
		if (tag.contains(TAG_ORE_VARIANT, Tag.TAG_STRING)) {
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

	/**
	 * Still in the ground, still unfound. The encounter director's entire notion of "delivered" is
	 * the negation of this: nothing but a genuine activation can take a lizard out of BURIED (see
	 * {@link #beginErupting}), so a director-placed lizard that is no longer dormant is one a player
	 * walked into.
	 */
	public boolean isDormant() {
		return this.getLizardState() == State.BURIED;
	}

	/**
	 * Removes a lizard that was placed for a player who never came near it. Mechanically identical
	 * to {@link #discard()}; the separate name is the point, because this is the one removal that is
	 * not the mob's own state machine reaching its end, and it should be possible to find every
	 * caller of it. On 1.16.5 the underlying call is {@code remove()}.
	 */
	public void removeAsUnfound() {
		this.discard();
	}

	@Nullable
	public LivingEntity getFleeTarget() {
		return this.fleeTarget;
	}

	@Override
	public void tick() {
		super.tick();
		if (this.level().isClientSide) {
			return;
		}
		this.emitSparkTrail();
		switch (this.getLizardState()) {
			case BURIED -> this.tickBuried();
			case ERUPTING -> this.tickErupting();
			case FLEEING -> this.tickFleeing();
			case DIGGING_DOWN -> this.tickDiggingDown();
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
		if (!(this.level() instanceof ServerLevel serverLevel)) {
			return;
		}
		// Zero speed: the sparks are left hanging where the lizard was rather than being thrown, so
		// the trail marks its actual path. FireworkParticles fade and twinkle out on their own.
		serverLevel.sendParticles(ParticleTypes.FIREWORK, this.getX(), this.getY() + SPARK_Y_OFFSET, this.getZ(),
				1, SPARK_SPREAD, SPARK_SPREAD, SPARK_SPREAD, 0.0);
	}

	private void tickBuried() {
		// Uses vanilla's own named predicate rather than the boolean overload - that boolean's
		// polarity is the opposite of what it reads like (false = NO_SPECTATORS, which still
		// detects creative players), which previously let creative players wake dormant lizards.
		Player nearest = this.level().getNearestPlayer(this.getX(), this.getY(), this.getZ(), TRIGGER_RANGE,
				EntitySelector.NO_CREATIVE_OR_SPECTATOR);
		// The trigger is a 5-block sphere, and underground a sphere that size routinely reaches
		// through a wall into the next cave pocket. Without this a lizard in a sealed pocket four
		// blocks through stone erupted and fled where nobody could see, spending the encounter - the
		// director's first playtest lost placements to exactly that. Requiring a clear line to the
		// player means eruption is something they can witness, which is the entire product. This
		// protects natural spawns just as much as director placements, so it lives here rather
		// than in the director.
		//
		// LivingEntity.hasLineOfSight does its own Level.clip from eye to eye (javap: ClipContext
		// COLLIDER/NONE, no reference to Sensing), which matters because a dormant lizard is NoAi
		// and Mob.getSensing() is only refreshed from serverAiStep - a cached answer would be stale
		// forever. Older branches spell this method canSee.
		if (nearest != null && this.hasLineOfSight(nearest)) {
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
			this.discard();
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
		// Hand the goals and the navigator back - see becomeDormant for why they were taken away.
		this.setNoAi(false);
		this.setLizardState(State.ERUPTING);
		this.stateTimer = ERUPT_DURATION_TICKS;
		this.setInvisible(false);
		this.spawnBurstParticles();
	}

	private void beginFleeing(LivingEntity target) {
		this.fleeTarget = target;
		// The second route out of BURIED (panicFromDamageIfDormant skips the eruption), so NoAi has
		// to be cleared here too - a FLEEING lizard with NoAi still set has no goal selector or
		// navigator running, and stands visible and motionless until its timer buries it.
		this.setNoAi(false);
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
		// A dormant lizard has nothing for the AI to do - FleeAndBurrowGoal is inert without a flee
		// target and the two look goals only matter while it is visible - yet without this it would
		// still tick sensing, the goal selector and the navigator every tick for however many hours
		// it spends in the floor. NoAi makes Mob.isEffectiveAi() false, which is the flag
		// LivingEntity.aiStep gates serverAiStep on, so all of that stops. Three things it
		// deliberately does not touch: tickBuried runs from our own tick() override, so proximity
		// triggering is unaffected; checkDespawn is called by ServerLevel directly rather than from
		// the AI step, so dormant lizards still get culled; and gravity is a separate flag
		// (NoGravity), so a lizard whose floor is mined still falls. Mob persists NoAi to NBT for
		// free, and becomeDormant re-asserts it on load anyway.
		this.setNoAi(true);
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
		return target != null && target.isAlive() && target.level() == this.level();
	}

	private void spawnBurstParticles() {
		this.playSound(this.isDeepslate() ? SoundEvents.DEEPSLATE_BREAK : SoundEvents.STONE_BREAK, 1.0F, 1.0F);
		if (!(this.level() instanceof ServerLevel serverLevel)) {
			return;
		}
		BlockState blockState = this.level().getBlockState(this.blockPosition().below());
		serverLevel.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, blockState),
				this.getX(), this.getY() + 0.5, this.getZ(), 20, 0.3, 0.3, 0.3, 0.05);
	}

	@Override
	public boolean hurt(DamageSource source, float amount) {
		if (source.getEntity() instanceof Player player && (player.isCreative() || player.isSpectator())) {
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
		return this.isDeepslate() ? SoundEvents.DEEPSLATE_HIT : SoundEvents.STONE_HIT;
	}

	/**
	 * The scuttle sound. Vanilla calls this from {@code Entity.move()} paced by distance
	 * travelled, so it automatically speeds up with the 1.925x flee boost rather than needing its
	 * own timer. Stone/deepslate to match its body, pitched well up so it reads as a small
	 * skittering critter rather than something heavy walking.
	 */
	@Override
	protected void playStepSound(BlockPos pos, BlockState state) {
		this.playSound(this.isDeepslate() ? SoundEvents.DEEPSLATE_STEP : SoundEvents.STONE_STEP, 0.18F, 1.6F);
	}

	/**
	 * Vanilla suffocates any living entity whose hitbox overlaps a solid block each tick - fine
	 * for normal mobs, but ours is meant to sit embedded in stone/deepslate while dormant and sink
	 * back into it while burrowing down, so it needs to be exempt during those phases. Only
	 * FLEEING (out in open cave air) keeps the normal vanilla check as a safety net.
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
	 * deleted itself. A lizard taking environmental damage with no player in sight - literally: the
	 * fallback player has to pass {@code hasLineOfSight}, like the proximity trigger - stays in the
	 * rock and takes it.
	 */
	private void panicFromDamageIfDormant(DamageSource source) {
		State current = this.getLizardState();
		if (current != State.BURIED && current != State.ERUPTING) {
			return;
		}

		LivingEntity threat = source.getEntity() instanceof LivingEntity attacker ? attacker : null;
		if (threat == null) {
			// Environmental damage - lava, a falling block, a hit from something with no owner.
			// There is usually still a player behind it, so run from the nearest one if there is any
			// - but only one the lizard can actually see, by the same rule as tickBuried. Sixteen
			// blocks underground crosses several cave walls, and a drowning lizard picking a player
			// on the other side of one of them as its "threat" is how a placement nobody could reach
			// got counted as found.
			threat = this.level().getNearestPlayer(this.getX(), this.getY(), this.getZ(),
					PANIC_TARGET_SEARCH_RANGE, EntitySelector.NO_CREATIVE_OR_SPECTATOR);
			if (threat != null && !this.hasLineOfSight(threat)) {
				threat = null;
			}
		}
		if (!this.isValidFleeTarget(threat)) {
			return;
		}

		this.setInvisible(false);
		this.beginFleeing(threat);
		this.spawnBurstParticles();
	}

	private boolean isPickaxeHit(DamageSource source) {
		if (!(source.getEntity() instanceof Player player)) {
			return false;
		}
		ItemStack weapon = player.getMainHandItem();
		if (!(weapon.getItem() instanceof PickaxeItem pickaxe)) {
			return false;
		}
		Tier tier = pickaxe.getTier();
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
	public AnimatableInstanceCache getAnimatableInstanceCache() {
		return this.cache;
	}

	@Override
	public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
		controllers.add(new AnimationController<>(this, "movement", MOVEMENT_TRANSITION_TICKS, state -> {
			AnimationController<OreLizardEntity> controller = state.getController();
			// State checks take priority over the generic movement check below, so burrow/appear
			// can't get interrupted by some incidental movement source during those windows.
			State lizardState = state.getAnimatable().getLizardState();

			// Both state animations start with a zero-tick transition. GeckoLib otherwise spends
			// transitionLength ticks blending from whatever pose the model is currently in into
			// the new animation's first frame, and only starts the animation's own clock once
			// that blend finishes. For "appear" that was ruinous: its first frame puts the body
			// 13 units (0.81 blocks) underground, but the pose it blends from is the rest pose at
			// ground level - so the lizard became visible standing on top of the block, slid down
			// into it over a quarter second, and only then began erupting. "burrow" starts from
			// the rest pose so it never sank, but it still stood there motionless for those five
			// ticks before digging. Starting on frame one also makes the animation lengths line
			// up with the state timers: appear is exactly 1 second, as is ERUPT_DURATION_TICKS,
			// where previously the transition ate a quarter of the eruption.
			if (lizardState == State.DIGGING_DOWN) {
				controller.transitionLength(STATE_TRANSITION_TICKS);
				return state.setAndContinue(BURROW_ANIM);
			}
			if (lizardState == State.ERUPTING) {
				controller.transitionLength(STATE_TRANSITION_TICKS);
				return state.setAndContinue(APPEAR_ANIM);
			}

			controller.transitionLength(MOVEMENT_TRANSITION_TICKS);
			// Driven by actual velocity/limb-swing (GeckoLib's isMoving(), same signal vanilla
			// mobs use for their walk cycle) rather than our own isFleeing() flag, so it scuttles
			// whenever it's genuinely moving for any reason - fleeing, knockback, pushed by
			// another entity, etc. - not only during the AI's own flee state.
			if (state.isMoving()) {
				return state.setAndContinue(SCUTTLE_ANIM);
			}
			return PlayState.STOP;
		}));
	}
}
