package com.orelizards.entity;

import com.orelizards.OreLizardsMod;
import com.orelizards.entity.ai.FleeAndBurrowGoal;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.LivingEntity;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animatable.manager.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.animation.object.PlayState;
import software.bernie.geckolib.util.GeckoLibUtil;

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

	// 1.21 keys attribute modifiers by Identifier instead of UUID, and the id doubles as the
	// modifier's name - there is no separate display string any more.
	private static final Identifier FLEE_SPEED_MODIFIER_ID =
			Identifier.fromNamespaceAndPath(OreLizardsMod.MOD_ID, "flee_speed_boost");
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
	// Step over full blocks instead of jumping them. MoveControl only triggers a jump when the
	// height of the next waypoint exceeds the step height, and the default 0.6 means every one-block
	// rise in a cave floor became a jump - which kills the mob's momentum and made the flee speed
	// boost read as much slower than it is. 1.0 is what vanilla gives horses and ravagers. The same
	// value feeds WalkNodeEvaluator, so paths route over those rises as steps too. Since 1.20.5 this
	// is the STEP_HEIGHT attribute (Entity.maxUpStep() reads it) rather than a setter on the entity.
	private static final double MAX_UP_STEP = 1.0;

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
				.add(Attributes.MOVEMENT_SPEED, 0.3)
				.add(Attributes.KNOCKBACK_RESISTANCE, 0.5)
				.add(Attributes.ARMOR, 15.0)
				.add(Attributes.ARMOR_TOUGHNESS, 8.0)
				.add(Attributes.STEP_HEIGHT, MAX_UP_STEP);
	}

	public static boolean canSpawn(EntityType<OreLizardEntity> type, ServerLevelAccessor level, EntitySpawnReason spawnReason,
			BlockPos pos, RandomSource random) {
		// Light level intentionally not checked - it should spawn regardless of a torch-carrying
		// player's light, so it can be found while exploring lit-up caves, not just pitch darkness.
		//
		// The old "Y < 0" rule made stone lizards unreachable: 1.20.1 worldgen fully replaces
		// stone with deepslate below Y=-8 (blending from Y=0 down), so every natural spawn landed
		// on deepslate. The ceiling now reaches up into the stone band instead. Depth-below-
		// surface (rather than a light check) is what keeps it genuinely underground, since it
		// works during worldgen when lighting isn't computed yet and ignores player torches.
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

		Player player = this.level().getNearestPlayer(this, -1.0);
		// No players in this dimension at all: nobody to preserve it for, but nobody to notice it
		// go either, and in practice its chunk isn't loaded to tick this. Leave it be.
		if (player == null) {
			return;
		}
		if (player.distanceToSqr(this) < DORMANT_DESPAWN_RADIUS_SQ) {
			return;
		}
		if (isUnderground(this.level(), player.blockPosition())) {
			return;
		}

		this.discard();
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		super.defineSynchedData(builder);
		builder.define(ORE_VARIANT, OreVariant.COAL.ordinal());
		builder.define(DEEPSLATE, false);
		builder.define(STATE, State.BURIED.ordinal());
	}

	@Override
	public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty, EntitySpawnReason spawnReason,
			@Nullable SpawnGroupData spawnGroupData) {
		boolean deepslate = this.blockPosition().getY() < DEEPSLATE_Y_LEVEL;
		this.setDeepslate(deepslate);
		this.setOreVariant(deepslate ? OreVariant.randomDeepslate(this.random) : OreVariant.random(this.random));
		this.becomeDormant();
		return super.finalizeSpawn(level, difficulty, spawnReason, spawnGroupData);
	}

	// 1.21.6 replaced the CompoundTag overloads with ValueOutput/ValueInput - typed views over the
	// save data that the game fills in (or reads back) itself - so the entity never sees the tag. The
	// keys and value types written are the same as before, so worlds saved by earlier versions load.
	@Override
	public void addAdditionalSaveData(ValueOutput output) {
		super.addAdditionalSaveData(output);
		output.putString(TAG_ORE_VARIANT, this.getOreVariant().name());
		output.putBoolean(TAG_DEEPSLATE, this.isDeepslate());
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
	public void readAdditionalSaveData(ValueInput input) {
		super.readAdditionalSaveData(input);
		// ValueInput keeps the Optional-returning getters 1.21.5 gave CompoundTag: getString is empty
		// when the key is missing or holds a different type, getBooleanOr takes the default. The two
		// "keep the default" cases, no variant recorded and a name this version doesn't know, both fall
		// out of the empty Optional: byName returns null for an unknown name and map() turns that into
		// empty, so setOreVariant is only ever called with a real variant.
		input.getString(TAG_ORE_VARIANT).map(OreVariant::byName).ifPresent(this::setOreVariant);
		this.setDeepslate(input.getBooleanOr(TAG_DEEPSLATE, false));
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
		if (this.level().isClientSide()) {
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
		if (speed != null && !speed.hasModifier(FLEE_SPEED_MODIFIER_ID)) {
			// ADD_MULTIPLIED_TOTAL is 1.21's name for what used to be MULTIPLY_TOTAL: same maths,
			// final = total * (1 + amount).
			speed.addTransientModifier(new AttributeModifier(
					FLEE_SPEED_MODIFIER_ID, FLEE_SPEED_BONUS, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
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

	// 1.21.2 split Entity.hurt into a final dispatcher and a server-only hurtServer, which is the half
	// that carries the damage logic - so this is the same override it always was, minus the client-side
	// early return LivingEntity.hurt used to do for us (the dispatcher now handles that).
	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
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
			boolean result = super.hurtServer(level, source, amount);
			if (armor != null) armor.setBaseValue(armorBase);
			if (toughness != null) toughness.setBaseValue(toughnessBase);
			return result;
		}
		return super.hurtServer(level, source, amount);
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
	 * deleted itself. A lizard taking environmental damage with no player in sight stays in the rock
	 * and takes it.
	 */
	private void panicFromDamageIfDormant(DamageSource source) {
		State current = this.getLizardState();
		if (current != State.BURIED && current != State.ERUPTING) {
			return;
		}

		LivingEntity threat = source.getEntity() instanceof LivingEntity attacker ? attacker : null;
		if (threat == null) {
			// Environmental damage - lava, a falling block, a hit from something with no owner.
			// There is usually still a player behind it, so run from the nearest one if there is any.
			threat = this.level().getNearestPlayer(this.getX(), this.getY(), this.getZ(),
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
		if (!(source.getEntity() instanceof Player player)) {
			return false;
		}
		ItemStack weapon = player.getMainHandItem();
		// 1.21.5 removed PickaxeItem along with the other tool subclasses - a pickaxe is a plain Item
		// now, its digging behaviour coming entirely from its TOOL component - so "is it a pickaxe" is
		// the minecraft:pickaxes item tag, which every vanilla pickaxe is in and which modded pickaxes
		// are expected to join (it is what vanilla itself checks, e.g. for pickaxe-only enchantments).
		if (!weapon.is(ItemTags.PICKAXES)) {
			return false;
		}
		// "Iron or better" used to be a Tier identity check (IRON, DIAMOND or NETHERITE). 1.21.2
		// replaced Tier/Tiers with ToolMaterial, which has no comparable identity - what it carries is
		// the tag of blocks the tool is *not* good enough to harvest, folded into the stack's TOOL
		// component. So the question is asked the way the game itself asks it: could this pickaxe drop
		// diamond ore? Diamond ore needs an iron tool, so wood, stone and gold picks fail it and iron,
		// diamond and netherite pass - exactly the set the tier comparison accepted.
		return weapon.isCorrectToolForDrops(Blocks.DIAMOND_ORE.defaultBlockState());
	}

	// 1.21 dropped the looting-multiplier argument (looting is applied through the enchantment system
	// now) and passes the level instead; the boolean is still "was recently hit by a player".
	@Override
	protected void dropCustomDeathLoot(ServerLevel level, DamageSource damageSource, boolean allowDrops) {
		super.dropCustomDeathLoot(level, damageSource, allowDrops);
		if (!allowDrops) {
			return;
		}
		OreVariant variant = this.getOreVariant();
		ItemLike dropItem = variant.getDropItem();
		this.spawnAtLocation(level, new ItemStack(dropItem, variant.rollDropCount(this.random)));
	}

	@Override
	public AnimatableInstanceCache getAnimatableInstanceCache() {
		return this.cache;
	}

	// GeckoLib 5 dropped the animatable from the controller constructor (the type argument has to be
	// spelt out instead, or the diamond infers a bare GeoAnimatable and getLizardState is unreachable)
	// and hands the handler an AnimationTest - a record of the animatable, its render state, its
	// manager and the controller - in place of the old AnimationState. The handler now runs while the
	// client extracts the entity's render state (GeoModel.prepareForRenderPass, called from
	// GeoEntityRenderer.extractRenderState) rather than mid-render, but that is still on the client
	// with the client's copy of the entity, so the synced STATE data is read exactly as before.
	@Override
	public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
		controllers.add(new AnimationController<OreLizardEntity>("movement", MOVEMENT_TRANSITION_TICKS, test -> {
			AnimationController<OreLizardEntity> controller = test.controller();
			// State checks take priority over the generic movement check below, so burrow/appear
			// can't get interrupted by some incidental movement source during those windows.
			State lizardState = test.animatable().getLizardState();

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
				controller.setTransitionTicks(STATE_TRANSITION_TICKS);
				return test.setAndContinue(BURROW_ANIM);
			}
			if (lizardState == State.ERUPTING) {
				controller.setTransitionTicks(STATE_TRANSITION_TICKS);
				return test.setAndContinue(APPEAR_ANIM);
			}

			controller.setTransitionTicks(MOVEMENT_TRANSITION_TICKS);
			// Driven by actual velocity/limb-swing (GeckoLib's isMoving(), same signal vanilla
			// mobs use for their walk cycle; in GeckoLib 5 it reads the IS_MOVING render-state entry
			// GeoEntityRenderer fills from walkAnimation.speed()) rather than our own isFleeing()
			// flag, so it scuttles whenever it's genuinely moving for any reason - fleeing,
			// knockback, pushed by another entity, etc. - not only during the AI's own flee state.
			if (test.isMoving()) {
				return test.setAndContinue(SCUTTLE_ANIM);
			}
			return PlayState.STOP;
		}));
	}
}
