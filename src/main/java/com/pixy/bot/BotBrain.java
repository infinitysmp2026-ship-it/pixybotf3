package com.pixy.bot;

import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Server-side AI for one bot, called from PixyBotPlayer.tick().
 *
 *  - Passive (not armed by a horn): loots thrown items, otherwise plays its own idle routine (IdleBehavior).
 *  - Hunting (armed by a horn AFTER this bot spawned): loot nearby upgrades -> choose the nearest real player ->
 *    heal / retreat when hurt -> special items (pearl, wind charge) -> position (BotNavigator) -> shield timing ->
 *    cooldown-timed, critical-hit-aware attacks with a per-bot reaction time.
 * Nothing here depends on the bot owning a shield, a weapon or any particular item.
 */
public final class BotBrain {
    private final PixyBotPlayer bot;
    final BotNavigator nav;
    private final ItemTactics tactics;
    private final BlockPlacer blocks;
    private final IdleBehavior idle;

    private ServerPlayer target;
    private ItemEntity pickupTarget;

    private int scanAge, equipAge, pickupAge, weaponAge, pickupBudget;
    private int eatCooldown, noTargetTicks, retreatTicks;
    private int shieldHold, shieldCd;
    private int reaction = -1, readyTicks, critJumpCooldown, strafeTicks;
    private float strafeDir = 1f;
    private double peakY;
    private boolean lastSmash;
    private int activeMode = -1; // 0=normal, 1=following, 2=hunting

    BotBrain(PixyBotPlayer bot, long seed) {
        this.bot = bot;
        this.nav = new BotNavigator(bot);
        this.tactics = new ItemTactics(bot);
        this.blocks = new BlockPlacer(bot);
        this.idle = new IdleBehavior(bot, nav, seed);
        this.acting = new NpcActing(bot, nav);
        int off = Math.abs(bot.getId()) % 10;   // stagger bots so they don't all work on the same tick
        scanAge = off; equipAge = off; pickupAge = off;
    }

    /**
     * Called when this bot is armed. Everything left over from the idle routine (its path home, a loot run, the idle
     * state) is dropped so the chase starts on the very next tick - a stale idle path used to be followed for up to
     * 1.5 s before the bot turned toward the player.
     */
    public void onFollowChanged() { nav.reset(); }

    public void wake() {
        scanAge = 0;
        pickupTarget = null;
        target = null;
        nav.reset();
        idle.interrupt();
        tactics.onHuntStart();
        blocks.stop();
    }

    public void tick() {
        if (!bot.isAlive()) return;
        PixyConfig cfg = PixyConfig.I;

        bot.zza = 0f;
        bot.xxa = 0f;
        bot.setSprinting(false);
        nav.beginTick();
        tactics.beginTick();
        if (eatCooldown > 0) eatCooldown--;
        if (shieldCd > 0) shieldCd--;
        peakY = bot.onGround() ? bot.getY() : Math.max(peakY, bot.getY());

        if (cfg.autoEquip && ++equipAge >= 20) { equipAge = 0; BotEquipment.autoEquip(bot); }

        boolean huntingNow = bot.isHunting() && cfg.hunterEnabled;
        ServerPlayer follow = huntingNow ? null : bot.followTarget();
        int mode = huntingNow ? 2 : (follow != null ? 1 : 0);
        if (mode != activeMode) {
            nav.reset();
            if (huntingNow) acting.stop();
            activeMode = mode;
        }

        if (huntingNow) {
            hunt(cfg);
        } else if (follow != null) {
            target = null; reaction = -1; readyTicks = 0; retreatTicks = 0;
            blocks.stop();
            lowerShield();

            boolean actingNow = acting.tick(false);
            double d2 = bot.distanceToSqr(follow);
            if (d2 > 6.25) {
                nav.moveToPoint(follow.position(), 2.5, d2 > 25.0);
            } else {
                bot.zza = 0.0f;
                bot.xxa = 0.0f;
                bot.setSprinting(false);
                nav.reset();
                if (!actingNow) bot.lookAt(EntityAnchorArgument.Anchor.EYES, follow.getEyePosition());
            }
        } else {
            target = null; reaction = -1; readyTicks = 0; retreatTicks = 0;
            nav.reset();
            blocks.stop();
            lowerShield();
            if (acting.tick(true)) {
                // Acting temporarily pauses the current idle/loot movement without resetting its state.
            } else if (pickup(cfg, cfg.pickupRadius)) {
                idle.interrupt();
            } else {
                idle.tick(cfg);
            }
        }
        nav.endTick();
    }

    // ================================================================== hunting
    private void hunt(PixyConfig cfg) {
        // finish eating first
        if (bot.isUsingItem() && !bot.getUseItem().is(Items.SHIELD)) return;

        if (!valid(target)) { target = null; reaction = -1; readyTicks = 0; retreatTicks = 0; nav.reset(); }
        if (target == null) {
            if (--scanAge <= 0) {
                scanAge = cfg.targetScanTicks;
                target = findTarget();
                if (target != null) { nav.reset(); tactics.onHuntStart(); }   // new target: plan from scratch
            }
            if (target == null) {                                   // nobody to fight right now
                noTargetTicks++;
                if (pickup(cfg, cfg.pickupRadius)) idle.interrupt();
                else if (noTargetTicks > 100) idle.tick(cfg);       // combat is over: back to the idle routine
                return;
            }
        }
        noTargetTicks = 0;
        idle.interrupt();                                           // idle routine stops while fighting

        double d2 = bot.distanceToSqr(target);

        // Before engaging: if nobody is on top of us, collect useful upgrades that lie nearby.
        boolean threatened = d2 < 144.0 || bot.hurtTime > 0;          // enemy within 12 blocks: no detours
        if (cfg.lootBeforeCombat && !threatened
                && pickup(cfg, Math.min(cfg.pickupRadius, cfg.lootRadiusInCombat))) return;

        if (healing(cfg, d2)) return;
        combat(cfg, d2);
    }

    // ------------------------------------------------------------------ healing / retreat
    /**
     * Golden apple only when hurt (and not already absorbing), and only at a sensible moment: not while the enemy
     * is right on top of us (unless it is an emergency or the enemy is busy) - the bot backs off first.
     */
    private boolean healing(PixyConfig cfg, double d2) {
        float hp = bot.getHealth();
        if (eatCooldown > 0 || hp > cfg.eatHealth || bot.getAbsorptionAmount() >= 2f || !BotEquipment.hasHealing(bot)) {
            retreatTicks = 0;
            return false;
        }
        boolean emergency = hp <= 4f;
        boolean enemyClose = d2 < 16.0;
        boolean enemyBusy = target.isUsingItem() && !target.isBlocking();
        if (!enemyClose || enemyBusy || emergency || retreatTicks >= 30) {
            eatCooldown = 60;
            retreatTicks = 0;
            if (bot.isUsingItem()) bot.stopUsingItem();
            return BotEquipment.tryEat(bot);
        }
        retreatTicks++;
        retreat();
        return true;
    }

    /** Run away from the target (sprinting, never over an edge) to create space. */
    private void retreat() {
        double dx = bot.getX() - target.getX(), dz = bot.getZ() - target.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.01) { dx = 1; dz = 0; len = 1; }
        Vec3 goal = bot.position().add(dx / len * 6.0, 0, dz / len * 6.0);
        float yaw = BotNavigator.yawTo(goal.x - bot.getX(), goal.z - bot.getZ());
        bot.setYRot(yaw);
        bot.setYHeadRot(yaw);
        nav.steer(goal, true);
    }

    // ------------------------------------------------------------------ combat
    private void combat(PixyConfig cfg, double d2) {
        bot.lookAt(EntityAnchorArgument.Anchor.EYES, target.getEyePosition());
        bot.setYHeadRot(bot.getYRot());

        boolean los = d2 <= 36.0 && bot.hasLineOfSight(target);
        float str = bot.getAttackStrengthScale(0.5f);
        boolean grounded = bot.onGround();
        boolean smashAir = !grounded && (peakY - bot.getY()) >= 1.5;

        // weapon: re-evaluated every 0.5 s, and immediately when a smash opportunity starts / ends
        if (cfg.autoWeapon && (++weaponAge >= 10 || smashAir != lastSmash)) {
            weaponAge = 0;
            BotEquipment.selectWeapon(bot, target, smashAir || tactics.windActive());
        }
        lastSmash = smashAir;

        // bridging / pillaring: only when walking failed (edge refusal, no route) and the bot owns blocks
        boolean needHelp = nav.edgeBlocked() || nav.isUnreachable() || nav.isStranded();
        boolean canBridge = cfg.useBridging && BotEquipment.placeableCount(bot) > 0;
        if (blocks.tick(target, nav, needHelp)) return;

        // special items: ender pearl (PearlPolicy) / wind charge (only when walking is clearly worse)
        if (!blocks.active() && tactics.tick(target, nav, nav.isUnreachable(), d2, canBridge, str)) return;

        // ---------- positioning
        if (tactics.windActive()) {
            // airborne after a launch: steering is done by ItemTactics
        } else if (!los || d2 > 7.5) {
            nav.moveToEntity(target, d2 > 25.0, los);       // may end up STRANDED: then the bot waits instead of pushing
        } else {
            closeRange(d2);
        }
        if (d2 <= 16.0) bot.setSprinting(false);              // a sprinting attacker cannot crit

        // ---------- shield (optional)
        boolean shieldUp = shield(cfg, d2, str);

        // ---------- attack decisions
        boolean canCrit = cfg.criticals && !bot.isInWater() && !bot.onClimbable() && !bot.isPassenger();
        boolean falling = !grounded && bot.getDeltaMovement().y < -0.01;
        boolean inReach = los && d2 <= 9.0;
        // Mace smash: only counts while FALLING 1.5+ blocks, and the window is just 1-2 ticks at that speed, so no
        // reaction delay and no line-of-sight requirement (the target is below the bot).
        boolean maceInHand = bot.getMainHandItem().is(Items.MACE);
        boolean smashWindow = maceInHand && !grounded && falling && (peakY - bot.getY()) >= 1.6;
        if (smashWindow && d2 <= 12.25 && str >= 0.9f) { swing(); return; }
        // when the enemy is weak or busy eating there is no point in waiting for a perfect crit
        boolean opportunity = target.getHealth() <= 6.0f || (target.isUsingItem() && !target.isBlocking());

        if (critJumpCooldown > 0) critJumpCooldown--;
        if (!inReach) { reaction = -1; readyTicks = 0; return; }

        if (str < 0.95f) {                                      // cooldown still running: do NOT swing
            readyTicks = 0;
            reaction = -1;
            if (!shieldUp && canCrit && !opportunity && grounded && str >= 0.3f
                    && critJumpCooldown == 0 && d2 > 2.25) {
                jump();                                         // time the fall so it lands as the cooldown ends
            }
            return;
        }

        // cooldown ready
        if (target.invulnerableTime > 10 && !smashWindow) return;     // just hit by someone else: the hit would do nothing
        readyTicks++;
        if (reaction < 0) reaction = 1 + bot.getRandom().nextInt(3);   // 1-3 tick reaction time, differs per attack
        if (reaction > 0) { reaction--; return; }

        boolean wantsCrit = canCrit && !opportunity && readyTicks < 15;
        if (wantsCrit && grounded) { if (critJumpCooldown == 0) jump(); return; }   // ready but grounded: jump first
        if (wantsCrit && !falling) return;                                          // rising: wait for the descent
        swing();                                                                    // falling = crit, otherwise normal hit
    }

    private void jump() {
        lowerShield();
        bot.jumpFromGround();
        critJumpCooldown = 20;
    }

    private void swing() {
        lowerShield();                                          // shields come down before the bot attacks
        bot.setSprinting(false);
        bot.swing(InteractionHand.MAIN_HAND);
        bot.attack(target);
        bot.resetAttackStrengthTicker();
        reaction = -1;
        readyTicks = 0;
    }

    // ------------------------------------------------------------------ shield (optional behaviour)
    /**
     * Raise only when a hit is about to come (enemy near, facing us, its own cooldown ready or it is using an item),
     * for a short random time, then lower and wait before raising again - never held permanently. Skipped entirely
     * without a shield or with /pixybot shield off.
     */
    private boolean shield(PixyConfig cfg, double d2, float str) {
        boolean has = cfg.useShield && bot.getOffhandItem().is(Items.SHIELD);
        boolean up = bot.isUsingItem() && bot.getUseItem().is(Items.SHIELD);
        if (!has) { if (up) bot.stopUsingItem(); return false; }

        if (up) {
            if (--shieldHold <= 0 || str >= 0.95f) { bot.stopUsingItem(); shieldCd = 5 + bot.getRandom().nextInt(10); return false; }
            return true;
        }
        if (shieldCd > 0 || str >= 0.9f || d2 > 20.0) return false;
        Vec3 look = target.getViewVector(1.0f);
        double dx = bot.getX() - target.getX(), dz = bot.getZ() - target.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        boolean facingUs = len > 0.01 && (look.x * dx + look.z * dz) / len > 0.5;
        boolean incoming = facingUs && (target.getAttackStrengthScale(0.0f) >= 0.85f || target.isUsingItem());
        if (!incoming) return false;
        boolean enemyAxe = path(target.getMainHandItem().getItem()).endsWith("_axe");
        if (enemyAxe && bot.getRandom().nextInt(2) == 0) return false;   // an axe disables shields: block less eagerly
        bot.startUsingItem(InteractionHand.OFF_HAND);
        shieldHold = 6 + bot.getRandom().nextInt(12);
        return true;
    }

    private void lowerShield() {
        if (bot.isUsingItem() && bot.getUseItem().is(Items.SHIELD)) bot.stopUsingItem();
    }

    private static String path(net.minecraft.world.item.Item item) {
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).getPath();
    }

    /** Spacing + strafing while in melee range: keep ~2.5 blocks away, circle, never walk off an edge. */
    private void closeRange(double d2) {
        if (d2 < 2.25) {
            double yaw = Math.toRadians(bot.getYRot());
            if (nav.hasGroundAt(bot.getX() + Math.sin(yaw) * 0.9, bot.getZ() - Math.cos(yaw) * 0.9)) bot.zza = -0.6f;
        } else if (d2 > 6.25) {
            bot.zza = 0.7f;
        }
        if (--strafeTicks <= 0) {
            strafeTicks = 12 + bot.getRandom().nextInt(25);
            int r = bot.getRandom().nextInt(4);
            strafeDir = r == 0 ? 0f : (r <= 2 ? 1f : -1f);
        }
        if (strafeDir != 0f) {
            double yaw = Math.toRadians(bot.getYRot());
            double lx = Math.cos(yaw) * strafeDir, lz = Math.sin(yaw) * strafeDir;
            if (nav.hasGroundAt(bot.getX() + lx * 0.9, bot.getZ() + lz * 0.9)) bot.xxa = strafeDir * 0.8f;
            else strafeDir = -strafeDir;
        }
        if (bot.horizontalCollision && bot.onGround()) bot.jumpFromGround();
    }

    private boolean valid(ServerPlayer p) {
        return p != null && p.isAlive() && !p.isRemoved() && !p.isSpectator()
                && !p.isCreative() && p.level() == bot.level();
    }

    private ServerPlayer findTarget() {
        ServerPlayer best = null;
        double bd = Double.MAX_VALUE;
        for (ServerPlayer p : bot.botServer().getPlayerList().getPlayers()) {
            if (BotManager.isBot(p) || !valid(p)) continue;   // real players only
            double d = bot.distanceToSqr(p);
            if (d < bd) { bd = d; best = p; }
        }
        return best;
    }

    // ================================================================== loot
    /** @return true while the bot is busy walking to / collecting an item */
    private boolean pickup(PixyConfig cfg, double radius) {
        if (!cfg.pickupEnabled || bot.isUsingItem()) { pickupTarget = null; return false; }

        if (pickupTarget != null && (pickupTarget.isRemoved()
                || !BotEquipment.wants(bot, pickupTarget.getItem()))) pickupTarget = null;

        if (pickupTarget == null) {
            if (--pickupAge > 0) return false;
            pickupAge = 10;
            pickupTarget = findItem(radius);
            pickupBudget = 200;                    // give up after 10 s if it is unreachable
            if (pickupTarget == null) return false;
        }

        if (--pickupBudget <= 0) { pickupTarget = null; pickupAge = 100; return false; }

        double d2 = bot.distanceToSqr(pickupTarget);
        if (d2 > radius * radius * 2.25) { pickupTarget = null; return false; }

        if (d2 <= 2.25) {
            pickupTarget.playerTouch(bot);         // vanilla pickup: no dupes, keeps enchants/name/data
            if (pickupTarget.isRemoved()) pickupTarget = null;
            equipAge = 20;                         // equip on the next tick
            return pickupTarget != null;
        }
        bot.lookAt(EntityAnchorArgument.Anchor.FEET, pickupTarget.position());
        BotNavigator.Status st = nav.moveToPoint(pickupTarget.position(), 1.2, false);
        if (st == BotNavigator.Status.STRANDED) { pickupTarget = null; pickupAge = 100; return false; }   // unreachable item
        return true;
    }

    private ItemEntity findItem(double radius) {
        List<ItemEntity> items = bot.level().getEntitiesOfClass(ItemEntity.class,
                bot.getBoundingBox().inflate(radius),
                e -> e.isAlive() && !e.hasPickUpDelay() && BotEquipment.wants(bot, e.getItem()));
        ItemEntity best = null;
        double bd = radius * radius;
        for (ItemEntity e : items) {
            double d = bot.distanceToSqr(e);
            if (d <= bd) { bd = d; best = e; }
        }
        return best;
    }
}
