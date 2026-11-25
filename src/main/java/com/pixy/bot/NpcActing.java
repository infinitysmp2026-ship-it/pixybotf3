package com.pixy.bot;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;

/**
 * Small, per-bot cinematic reaction controller.
 * It never owns navigation or replaces the idle/combat AI; it only supplies
 * temporary look/gesture animation while a real player is paying attention.
 */
final class NpcActing {
    private final PixyBotPlayer bot;
    private final BotNavigator nav;

    private ServerPlayer candidate;
    private ServerPlayer actingPlayer;
    private int attentionTicks;
    private int gestureCooldown;
    private int crouchTicks;

    NpcActing(PixyBotPlayer bot, BotNavigator nav) {
        this.bot = bot;
        this.nav = nav;
        this.gestureCooldown = 20 + bot.getRandom().nextInt(30);
    }

    /**
     * @param lockMovement true when acting should pause the bot's normal movement;
     *                     false when another mode (e.g. Follow) owns movement.
     * @return true while a player is in the active acting range.
     */
    boolean tick(boolean lockMovement) {
        if (actingPlayer != null) {
            if (!valid(actingPlayer) || bot.distanceToSqr(actingPlayer) > 144.0) {
                clearActing();
            }
        }

        if (actingPlayer == null) {
            ServerPlayer looking = findPlayerLookingAtBot();
            if (looking == null) {
                candidate = null;
                attentionTicks = 0;
            } else if (candidate == looking) {
                attentionTicks++;
            } else {
                candidate = looking;
                attentionTicks = 1;
            }

            // 2 seconds at 20 ticks per second.
            if (candidate != null && attentionTicks >= 40) {
                actingPlayer = candidate;
                candidate = null;
                attentionTicks = 0;
                gestureCooldown = 5;
            }
        }

        if (actingPlayer == null) return false;

        if (lockMovement) {
            bot.zza = 0.0f;
            bot.xxa = 0.0f;
            bot.setSprinting(false);
        }

        face(actingPlayer);
        animate();
        return true;
    }

    void stop() {
        clearActing();
        candidate = null;
        attentionTicks = 0;
    }

    private ServerPlayer findPlayerLookingAtBot() {
        ServerPlayer best = null;
        double bestDistance = 100.0; // 10 blocks
        for (ServerPlayer player : bot.botServer().getPlayerList().getPlayers()) {
            if (!valid(player)) continue;
            double d2 = bot.distanceToSqr(player);
            if (d2 >= bestDistance) continue;

            Vec3 towardBot = bot.getEyePosition().subtract(player.getEyePosition());
            if (towardBot.lengthSqr() < 0.0001) continue;
            towardBot = towardBot.normalize();
            Vec3 look = player.getViewVector(1.0f);
            if (look.dot(towardBot) >= 0.94) {
                best = player;
                bestDistance = d2;
            }
        }
        return best;
    }

    private boolean valid(ServerPlayer player) {
        return player != null && player.isAlive() && !player.isRemoved()
            && !player.isSpectator()
            && !BotManager.isBot(player) && player.level() == bot.level();
    }

    private void face(ServerPlayer player) {
        double dx = player.getX() - bot.getX();
        double dy = player.getEyeY() - bot.getEyeY();
        double dz = player.getZ() - bot.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = BotNavigator.yawTo(dx, dz);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.max(0.001, horizontal)));
        nav.turnToward(yaw, pitch, 12.0f);
    }

    private void animate() {
        if (crouchTicks > 0 && --crouchTicks == 0) bot.setShiftKeyDown(false);

        if (--gestureCooldown > 0) return;
        gestureCooldown = 25 + bot.getRandom().nextInt(46);

        switch (bot.getRandom().nextInt(5)) {
            case 0 -> {
                bot.setShiftKeyDown(true);
                crouchTicks = 5 + bot.getRandom().nextInt(8);
            }
            case 1 -> bot.swing(InteractionHand.MAIN_HAND);
            case 2 -> {
                bot.setShiftKeyDown(true);
                crouchTicks = 3 + bot.getRandom().nextInt(5);
            }
            case 3 -> bot.swing(InteractionHand.OFF_HAND);
            default -> { /* a natural pause / head movement is already supplied by face() */ }
        }
    }

    private void clearActing() {
        actingPlayer = null;
        bot.setShiftKeyDown(false);
        crouchTicks = 0;
    }
}
