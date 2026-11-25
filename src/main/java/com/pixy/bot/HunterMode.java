package com.pixy.bot;

import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Items;

/**
 * Horn trigger. There is NO global "hunter is on" flag any more: every bot keeps its own state
 * (PixyBotPlayer#isHunting) and a horn only arms the bots that exist when it is blown.
 */
public final class HunterMode {
    /** Number of horn blasts so far (the "activation generation"). */
    private static long hornCount = 0;

    private HunterMode() { }

    public static long hornCount() { return hornCount; }

    /** A horn was blown: arms all existing, not-yet-hunting bots. */
    public static int onHorn() { return HornLogic.blow(BotManager.all(), ++hornCount); }

    /** Admin command: arm every existing bot. */
    public static int activateAll() { return HornLogic.blow(BotManager.all(), hornCount); }

    /** Admin command: put every bot back to passive. */
    public static int deactivateAll() {
        int n = 0;
        for (PixyBotPlayer b : BotManager.all()) if (b.isHunting()) { b.deactivate(); n++; }
        return n;
    }

    public static int huntingCount() {
        int n = 0;
        for (PixyBotPlayer b : BotManager.all()) if (b.isHunting()) n++;
        return n;
    }

    /** Any goat horn (every variant is the same item) used by a real player. */
    public static void registerHornTrigger() {
        UseItemCallback.EVENT.register((player, level, hand) -> {
            if (!(player instanceof ServerPlayer sp)) return InteractionResult.PASS; // server side only
            if (BotManager.isBot(sp)) return InteractionResult.PASS;                  // bots never trigger it
            if (!PixyConfig.I.hornActivation || !PixyConfig.I.hunterEnabled) return InteractionResult.PASS;
            if (!sp.getItemInHand(hand).is(Items.GOAT_HORN)) return InteractionResult.PASS;

            int armed = onHorn();
            MinecraftServer server = PixyBotMod.SERVER;
            if (armed > 0 && server != null)
                server.getPlayerList().broadcastSystemMessage(
                    Component.literal("Hunter Mode activated for " + armed + " bot(s)!"), false);
            return InteractionResult.PASS;
        });
    }
}
