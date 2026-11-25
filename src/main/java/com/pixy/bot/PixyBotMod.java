package com.pixy.bot;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public class PixyBotMod implements ModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("pixybot");
    public static volatile MinecraftServer SERVER;

    private static final SuggestionProvider<CommandSourceStack> BOT_NAMES = (ctx, b) ->
        SharedSuggestionProvider.suggest(
            BotManager.all().stream().map(x -> x.getGameProfile().name()), b);

    @Override
    public void onInitialize() {
        PixyConfig.load();
        IdentityPool.load();
        HunterMode.registerHornTrigger();

        ServerLifecycleEvents.SERVER_STARTED.register(s -> {
            SERVER = s;
            // warm the skin cache in the background so a 50-bot spawn does not wait for Mojang
            for (IdentityEntry e : IdentityPool.all()) SkinPool.lookup(s, e.skin);
        });
        // Bots are not persisted yet: remove them immediately on shutdown.
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
            BotManager.removeAllNow();
            SERVER = null;
        });

        // Bots never hurt other bots (unless friendlyFire is switched on in the config).
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) ->
            PixyConfig.I.friendlyFire
                || !(entity instanceof PixyBotPlayer && source.getEntity() instanceof PixyBotPlayer));

        CommandRegistrationCallback.EVENT.register((dispatcher, registry, env) ->
            dispatcher.register(
                literal("pixybot")
                    // NOTE: if your build needed a different permission line, keep that one.
                    .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))

                    // ---------------- spawning ----------------
                    .then(literal("spawn")
                        .executes(c -> spawn(c.getSource(), null, null))
                        .then(argument("name", StringArgumentType.word())
                            .executes(c -> spawn(c.getSource(), StringArgumentType.getString(c, "name"), null))
                            .then(argument("skin", StringArgumentType.word())
                                .executes(c -> spawn(c.getSource(),
                                    StringArgumentType.getString(c, "name"),
                                    StringArgumentType.getString(c, "skin"))))))

                    .then(literal("random")
                        .executes(c -> spawn(c.getSource(), null, null))
                        .then(argument("skin", StringArgumentType.word())
                            .executes(c -> spawn(c.getSource(), null,
                                StringArgumentType.getString(c, "skin")))))

                    .then(literal("remove")
                        .then(argument("name", StringArgumentType.word()).suggests(BOT_NAMES)
                            .executes(c -> {
                                String n = StringArgumentType.getString(c, "name");
                                int removed = BotManager.remove(n);
                                if (removed > 0) {
                                    c.getSource().sendSuccess(() -> Component.literal("Removed " + removed + " bot(s) named " + n), true);
                                    return removed;
                                }
                                c.getSource().sendFailure(Component.literal("No bot named " + n));
                                return 0;
                            })))

                    .then(literal("removeall").executes(c -> {
                        int n = BotManager.removeAll();
                        c.getSource().sendSuccess(() -> Component.literal("Removed " + n + " bot(s)."), true);
                        return n;
                    }))

                    .then(literal("list").executes(c -> {
                        var bots = BotManager.all();
                        if (bots.isEmpty()) {
                            c.getSource().sendSuccess(() -> Component.literal("No bots."), false);
                            return 0;
                        }
                        String names = String.join(", ", bots.stream()
                            .map(b -> b.getGameProfile().name() + (b.isHunting() ? " (hunting)" : " (waiting)")).toList());
                        c.getSource().sendSuccess(() ->
                            Component.literal(bots.size() + " bot(s): " + names), false);
                        return bots.size();
                    }))

                    .then(literal("info")
                        .then(argument("name", StringArgumentType.word()).suggests(BOT_NAMES)
                            .executes(c -> info(c.getSource(), StringArgumentType.getString(c, "name")))))

                    .then(literal("identities")
                        .executes(c -> {
                            var ids = IdentityPool.all();
                            String names = String.join(", ", ids.stream().map(i -> i.name).toList());
                            c.getSource().sendSuccess(() -> Component.literal(ids.size() + " identities: " + names
                                + "  (edit config/pixybot-identities.json, then /pixybot config reload;"
                                + " /pixybot identities check tests the skins)"), false);
                            return ids.size();
                        })
                        .then(literal("check").executes(c -> checkIdentities(c.getSource()))))

                    // ---------------- Hunter Mode ----------------
                    .then(literal("hunter")
                        .then(literal("on").executes(c -> {
                            int n = HunterMode.activateAll();
                            c.getSource().sendSuccess(() -> Component.literal(
                                "Hunter Mode ON for " + n + " waiting bot(s). Bots spawned later stay passive until a horn."), true);
                            return n;
                        }))
                        .then(literal("off").executes(c -> {
                            int n = HunterMode.deactivateAll();
                            c.getSource().sendSuccess(() -> Component.literal(
                                "Hunter Mode OFF for " + n + " bot(s)."), true);
                            return n;
                        }))
                        .then(literal("status").executes(c -> {
                            int total = BotManager.all().size();
                            int hunting = HunterMode.huntingCount();
                            c.getSource().sendSuccess(() -> Component.literal(
                                hunting + " hunting, " + (total - hunting) + " waiting for a horn (horns blown: "
                                + HunterMode.hornCount() + ")"), false);
                            return hunting;
                        })))

                    .then(literal("horn")
                        .then(literal("on").executes(c -> toggle(c.getSource(), "Goat horn activation", true,
                            v -> PixyConfig.I.hornActivation = v)))
                        .then(literal("off").executes(c -> toggle(c.getSource(), "Goat horn activation", false,
                            v -> PixyConfig.I.hornActivation = v))))

                    // ---------------- items / equipment ----------------
                    .then(literal("pickup")
                        .then(literal("on").executes(c -> toggle(c.getSource(), "Item pickup", true,
                            v -> PixyConfig.I.pickupEnabled = v)))
                        .then(literal("off").executes(c -> toggle(c.getSource(), "Item pickup", false,
                            v -> PixyConfig.I.pickupEnabled = v)))
                        .then(literal("radius")
                            .then(argument("blocks", DoubleArgumentType.doubleArg(1.0, 32.0))
                                .executes(c -> {
                                    double r = DoubleArgumentType.getDouble(c, "blocks");
                                    PixyConfig.I.pickupRadius = r;
                                    PixyConfig.save();
                                    c.getSource().sendSuccess(() ->
                                        Component.literal("Pickup radius = " + r + " blocks"), true);
                                    return 1;
                                }))))

                    .then(literal("autoequip")
                        .then(literal("on").executes(c -> toggle(c.getSource(), "Auto equip", true,
                            v -> PixyConfig.I.autoEquip = v)))
                        .then(literal("off").executes(c -> toggle(c.getSource(), "Auto equip", false,
                            v -> PixyConfig.I.autoEquip = v))))

                    .then(literal("autoweapon")
                        .then(literal("on").executes(c -> toggle(c.getSource(), "Auto weapon", true,
                            v -> PixyConfig.I.autoWeapon = v)))
                        .then(literal("off").executes(c -> toggle(c.getSource(), "Auto weapon", false,
                            v -> PixyConfig.I.autoWeapon = v))))

                    .then(literal("loot")
                        .then(literal("on").executes(c -> toggle(c.getSource(), "Loot before combat", true,
                            v -> PixyConfig.I.lootBeforeCombat = v)))
                        .then(literal("off").executes(c -> toggle(c.getSource(), "Loot before combat", false,
                            v -> PixyConfig.I.lootBeforeCombat = v))))

                    // ---------------- combat options ----------------
                    .then(literal("crits")
                        .then(literal("on").executes(c -> toggle(c.getSource(), "Critical-hit timing", true,
                            v -> PixyConfig.I.criticals = v)))
                        .then(literal("off").executes(c -> toggle(c.getSource(), "Critical-hit timing", false,
                            v -> PixyConfig.I.criticals = v))))

                    .then(literal("idle")
                        .then(literal("on").executes(c -> toggle(c.getSource(), "Idle movement", true,
                            v -> PixyConfig.I.idleMovement = v)))
                        .then(literal("off").executes(c -> toggle(c.getSource(), "Idle movement", false,
                            v -> PixyConfig.I.idleMovement = v))))

                    .then(literal("pearls")
                        .then(literal("on").executes(c -> toggle(c.getSource(), "Ender pearl use", true,
                            v -> PixyConfig.I.useEnderPearls = v)))
                        .then(literal("off").executes(c -> toggle(c.getSource(), "Ender pearl use", false,
                            v -> PixyConfig.I.useEnderPearls = v))))

                    .then(literal("bridging")
                        .then(literal("on").executes(c -> toggle(c.getSource(), "Bridging / pillaring", true,
                            v -> PixyConfig.I.useBridging = v)))
                        .then(literal("off").executes(c -> toggle(c.getSource(), "Bridging / pillaring", false,
                            v -> PixyConfig.I.useBridging = v))))

                    .then(literal("windcharges")
                        .then(literal("on").executes(c -> toggle(c.getSource(), "Wind charge use", true,
                            v -> PixyConfig.I.useWindCharges = v)))
                        .then(literal("off").executes(c -> toggle(c.getSource(), "Wind charge use", false,
                            v -> PixyConfig.I.useWindCharges = v))))

                    .then(literal("shield")
                        .then(literal("on").executes(c -> toggle(c.getSource(), "Shield use", true,
                            v -> PixyConfig.I.useShield = v)))
                        .then(literal("off").executes(c -> toggle(c.getSource(), "Shield use", false,
                            v -> PixyConfig.I.useShield = v))))

                    .then(literal("config")
                        .then(literal("reload").executes(c -> {
                            PixyConfig.load();
                            IdentityPool.load();
                            c.getSource().sendSuccess(() -> Component.literal("Config and identities reloaded."), true);
                            return 1;
                        })))
            ));

        // Short chat-command alias requested for individual NPC following.
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, env) ->
            dispatcher.register(literal("bot")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(literal("follow")
                    .then(argument("name", StringArgumentType.word()).suggests(BOT_NAMES)
                        .executes(c -> followBot(c.getSource(), StringArgumentType.getString(c, "name")))))
                .then(literal("stop")
                    .then(argument("name", StringArgumentType.word()).suggests(BOT_NAMES)
                        .executes(c -> stopFollowingBot(c.getSource(), StringArgumentType.getString(c, "name")))))
            ));
    }

    private static int followBot(CommandSourceStack src, String name) {
        ServerPlayer player = src.getPlayer();
        if (player == null) {
            src.sendFailure(Component.literal("Run this command as a player."));
            return 0;
        }
        PixyBotPlayer bot = BotManager.byName(name);
        if (bot == null) {
            src.sendFailure(Component.literal("No bot named " + name));
            return 0;
        }
        if (!bot.startFollowing(player)) {
            src.sendFailure(Component.literal("Could not make " + name + " follow you."));
            return 0;
        }
        src.sendSuccess(() -> Component.literal(name + " is now following you."), false);
        return 1;
    }

    private static int stopFollowingBot(CommandSourceStack src, String name) {
        PixyBotPlayer bot = BotManager.byName(name);
        if (bot == null) {
            src.sendFailure(Component.literal("No bot named " + name));
            return 0;
        }
        if (!bot.isFollowing()) {
            src.sendFailure(Component.literal(name + " is not following anyone."));
            return 0;
        }
        bot.stopFollowing();
        src.sendSuccess(() -> Component.literal(name + " stopped following."), false);
        return 1;
    }

    /** Looks up all 50 identity skins (also warms the cache) and reports which accounts could not be found. */
    private static int checkIdentities(CommandSourceStack src) {
        MinecraftServer server = src.getServer();
        List<IdentityEntry> ids = new ArrayList<>(IdentityPool.all());
        List<CompletableFuture<Boolean>> fs = new ArrayList<>();
        for (IdentityEntry e : ids) fs.add(SkinPool.lookup(server, e.skin).thenApply(o -> o.isPresent()));
        src.sendSuccess(() -> Component.literal("Checking " + ids.size() + " skins with Mojang..."), false);
        CompletableFuture.allOf(fs.toArray(new CompletableFuture[0])).whenComplete((v, err) -> server.execute(() -> {
            List<String> bad = new ArrayList<>();
            for (int i = 0; i < ids.size(); i++) {
                boolean ok = false;
                try { ok = fs.get(i).getNow(false); } catch (Exception ignored) { }
                if (!ok) { bad.add(ids.get(i).name + " -> " + ids.get(i).skin); SkinPool.markFailed(ids.get(i).skin); }
            }
            int good = ids.size() - bad.size();
            src.sendSuccess(() -> Component.literal(good + "/" + ids.size() + " skins OK."
                + (bad.isEmpty() ? "" : " Not found (edit them in config/pixybot-identities.json): " + String.join(", ", bad))), false);
        }));
        return ids.size();
    }

    private static int toggle(CommandSourceStack src, String label, boolean value, Consumer<Boolean> setter) {
        setter.accept(value);
        PixyConfig.save();
        src.sendSuccess(() -> Component.literal(label + " " + (value ? "enabled" : "disabled")), true);
        return 1;
    }

    private static int spawn(CommandSourceStack src, String name, String skin) {
        ServerPlayer p = src.getPlayer();
        if (p == null) { src.sendFailure(Component.literal("Run this as a player.")); return 0; }
        // name == null -> BotManager hands out the next free identity (name + skin) from the 50
        if (name != null && name.length() > 16) { src.sendFailure(Component.literal("Name max 16 characters.")); return 0; }

        // Exact copy of the player's state at the moment of the command.
        BotManager.Rot rot = new BotManager.Rot(p.getYRot(), p.getYHeadRot(), p.yBodyRot, p.getXRot());
        BotManager.spawn(src.getServer(), p.level(), p.position(), rot,
            name, skin, msg -> src.sendSuccess(() -> msg, false));
        return 1;
    }

    private static String fmt(String label, LivingEntity e) {
        return String.format(Locale.ROOT,
            "%s: pos=(%.3f, %.3f, %.3f) yaw=%.2f head=%.2f body=%.2f pitch=%.2f",
            label, e.getX(), e.getY(), e.getZ(), e.getYRot(), e.getYHeadRot(), e.yBodyRot, e.getXRot());
    }

    /** /pixybot info <name>: bot + your position/rotation, plus the bot's game mode and horn state. */
    private static int info(CommandSourceStack src, String name) {
        PixyBotPlayer b = BotManager.byName(name);
        if (b == null) { src.sendFailure(Component.literal("No bot named " + name)); return 0; }
        src.sendSuccess(() -> Component.literal(fmt("Bot " + name, b)), false);
        src.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
            "Bot state: mode=%s hunting=%s spawnedAtHorn=%d armedByHorn=%d health=%.1f skin=%s",
            b.gameMode.getGameModeForPlayer().getName(), b.isHunting(),
            b.spawnGeneration(), b.activationGeneration(), b.getHealth(),
            b.skinSource() == null ? "default" : b.skinSource())), false);
        ServerPlayer p = src.getPlayer();
        if (p != null) src.sendSuccess(() -> Component.literal(fmt("You", p)), false);
        return 1;
    }
}
