package com.pixy.bot;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.util.Set;

/**
 * Armor/weapon scoring, loot evaluation, auto-equip, weapon selection and golden-apple eating.
 * Nothing in here REQUIRES a shield: shield and totem are both optional off-hand items.
 */
public final class BotEquipment {
    private static final int MAIN = 36; // main inventory slots (hotbar + storage)

    private BotEquipment() { }

    private static String path(ItemStack s) { return BuiltInRegistries.ITEM.getKey(s.getItem()).getPath(); }

    private static double enchantBonus(ItemStack s) {
        var e = s.getEnchantments();
        double sum = 0;
        for (var h : e.keySet()) sum += e.getLevel(h);
        return sum * 0.05;
    }

    // ---------- armor ----------
    private static double armorTier(String p) {
        if (p.startsWith("netherite_")) return 6;
        if (p.startsWith("diamond_")) return 5;
        if (p.startsWith("iron_")) return 4;
        if (p.startsWith("chainmail_")) return 3;
        if (p.startsWith("copper_")) return 2.5;
        if (p.startsWith("golden_")) return 2;
        if (p.startsWith("turtle_")) return 2;
        if (p.startsWith("leather_")) return 1;
        return 0;
    }

    static EquipmentSlot armorSlot(PixyBotPlayer bot, ItemStack s) {
        if (s.isEmpty() || s.is(Items.ELYTRA)) return null;
        EquipmentSlot sl = bot.getEquipmentSlotForItem(s);
        return (sl == EquipmentSlot.HEAD || sl == EquipmentSlot.CHEST
                || sl == EquipmentSlot.LEGS || sl == EquipmentSlot.FEET) ? sl : null;
    }

    static double armorScore(ItemStack s) {
        double t = armorTier(path(s));
        return t <= 0 ? 0 : t + enchantBonus(s);
    }

    // ---------- weapons ----------
    private static double weaponTier(String p) {
        if (!p.endsWith("_sword") && !p.endsWith("_axe") && !p.endsWith("_spear")) return 0;
        if (p.startsWith("netherite_")) return 6;
        if (p.startsWith("diamond_")) return 5;
        if (p.startsWith("iron_")) return 4;
        if (p.startsWith("copper_")) return 3.5;
        if (p.startsWith("stone_")) return 3;
        if (p.startsWith("golden_")) return 2;
        if (p.startsWith("wooden_")) return 1;
        return 0;
    }

    private static double weaponScore(ItemStack s) {
        double t = weaponTier(path(s));
        return t <= 0 ? 0 : t + enchantBonus(s);
    }

    /** 0 sword, 1 axe, 2 spear, 3 mace, -1 not a weapon */
    private static int kind(String p) {
        if (p.equals("mace")) return 3;
        if (weaponTier(p) <= 0) return -1;
        return p.endsWith("_sword") ? 0 : p.endsWith("_axe") ? 1 : 2;
    }

    private static double scoreOf(ItemStack st) {
        String p = path(st);
        int k = kind(p);
        if (k < 0) return 0;
        return (k == 3 ? 5.0 : weaponTier(p)) + enchantBonus(st);
    }

    /** Best weapon of the given kind the bot already owns (0 = none). */
    private static double bestOwned(PixyBotPlayer bot, int kind) {
        Inventory inv = bot.getInventory();
        double best = 0;
        for (int i = 0; i < MAIN; i++) {
            ItemStack st = inv.getItem(i);
            if (st.isEmpty() || kind(path(st)) != kind) continue;
            best = Math.max(best, scoreOf(st));
        }
        return best;
    }

    // ---------- inventory helpers ----------
    static int count(PixyBotPlayer bot, Item item) {
        Inventory inv = bot.getInventory();
        int n = 0;
        for (int i = 0; i < MAIN; i++) { ItemStack s = inv.getItem(i); if (s.is(item)) n += s.getCount(); }
        ItemStack off = bot.getOffhandItem();
        if (off.is(item)) n += off.getCount();
        return n;
    }

    // ---------- blocks for bridging / pillaring ----------
    private static final Set<String> PLACEABLE = Set.of("cobblestone", "stone", "dirt", "netherrack", "cobbled_deepslate",
        "deepslate", "andesite", "diorite", "granite", "blackstone", "end_stone", "obsidian", "sandstone",
        "terracotta", "bricks", "stone_bricks");

    /** Plain full blocks that are safe to build with (no TNT, no gravity blocks, no containers). */
    static boolean isPlaceable(ItemStack s) {
        if (s.isEmpty()) return false;
        String p = path(s);
        boolean ok = PLACEABLE.contains(p) || p.endsWith("_planks") || p.endsWith("_wool")
            || p.endsWith("_concrete") || p.endsWith("_terracotta");
        return ok && net.minecraft.world.level.block.Block.byItem(s.getItem()) != Blocks.AIR;
    }

    static int placeableCount(PixyBotPlayer bot) {
        Inventory inv = bot.getInventory();
        int n = 0;
        for (int i = 0; i < MAIN; i++) { ItemStack st = inv.getItem(i); if (isPlaceable(st)) n += st.getCount(); }
        return n;
    }

    static ItemStack findPlaceable(PixyBotPlayer bot) {
        Inventory inv = bot.getInventory();
        for (int i = 0; i < MAIN; i++) { ItemStack st = inv.getItem(i); if (isPlaceable(st)) return st; }
        return null;
    }

    static boolean hasMace(PixyBotPlayer bot) { return count(bot, Items.MACE) > 0; }

    static boolean hasHealing(PixyBotPlayer bot) {
        return count(bot, Items.GOLDEN_APPLE) + count(bot, Items.ENCHANTED_GOLDEN_APPLE) > 0;
    }

    /** Swap the first stack of this item into the selected hotbar slot. Nothing is created or lost. */
    static boolean holdItem(PixyBotPlayer bot, Item item) {
        Inventory inv = bot.getInventory();
        if (bot.getMainHandItem().is(item)) return true;
        for (int i = 0; i < MAIN; i++) {
            if (inv.getItem(i).is(item)) {
                int sel = inv.getSelectedSlot();
                ItemStack a = inv.getItem(i), b = inv.getItem(sel);
                inv.setItem(sel, a);
                inv.setItem(i, b);
                return true;
            }
        }
        return false;
    }

    /** Should the bot want to walk to / pick up this item? (better gear and useful consumables only) */
    public static boolean wants(PixyBotPlayer bot, ItemStack s) {
        if (s.isEmpty()) return false;
        if (bot.getInventory().getFreeSlot() < 0) return false;

        // consumables
        if (s.is(Items.GOLDEN_APPLE) || s.is(Items.ENCHANTED_GOLDEN_APPLE))
            return count(bot, Items.GOLDEN_APPLE) + count(bot, Items.ENCHANTED_GOLDEN_APPLE) < 16;
        if (s.is(Items.TOTEM_OF_UNDYING)) return count(bot, Items.TOTEM_OF_UNDYING) == 0;
        if (s.is(Items.SHIELD)) return PixyConfig.I.useShield && count(bot, Items.SHIELD) == 0;
        if (s.is(Items.ENDER_PEARL)) return PixyConfig.I.useEnderPearls && count(bot, Items.ENDER_PEARL) < 8;
        if (s.is(Items.WIND_CHARGE)) return PixyConfig.I.useWindCharges && count(bot, Items.WIND_CHARGE) < 16;
        if (isPlaceable(s)) return PixyConfig.I.useBridging && placeableCount(bot) < 64;

        // armor: only if it beats what is worn
        EquipmentSlot sl = armorSlot(bot, s);
        if (sl != null) {
            double ns = armorScore(s);
            if (ns <= 0) return false;
            ItemStack cur = bot.getItemBySlot(sl);
            return cur.isEmpty() || ns > armorScore(cur) + 0.001;
        }

        // weapons: only if it beats the best sword / axe already owned
        int k = kind(path(s));
        if (k < 0) return false;
        return scoreOf(s) > bestOwned(bot, k) + 0.001;
    }

    /** Better armor on the body, totem/shield into the off hand, best weapon into the hand. Never loses items. */
    public static void autoEquip(PixyBotPlayer bot) {
        if (bot.isUsingItem()) return;
        Inventory inv = bot.getInventory();
        for (int i = 0; i < MAIN; i++) {
            ItemStack st = inv.getItem(i);
            if (st.isEmpty()) continue;

            if (st.is(Items.TOTEM_OF_UNDYING)) {
                ItemStack off = bot.getOffhandItem();
                if (off.isEmpty()) {
                    inv.setItem(i, ItemStack.EMPTY);
                    bot.setItemSlot(EquipmentSlot.OFFHAND, st);
                } else if (off.is(Items.SHIELD)) {            // totem outranks a shield in the off hand
                    inv.setItem(i, ItemStack.EMPTY);
                    bot.setItemSlot(EquipmentSlot.OFFHAND, st);
                    inv.setItem(i, off);                      // the shield goes back into the freed slot
                }
                continue;
            }

            if (st.is(Items.SHIELD)) {
                if (PixyConfig.I.useShield && bot.getOffhandItem().isEmpty()) {
                    inv.setItem(i, ItemStack.EMPTY);
                    bot.setItemSlot(EquipmentSlot.OFFHAND, st);
                }
                continue;
            }

            EquipmentSlot sl = armorSlot(bot, st);
            if (sl == null) continue;
            double ns = armorScore(st);
            if (ns <= 0) continue;
            ItemStack cur = bot.getItemBySlot(sl);
            if (cur.isEmpty() || ns > armorScore(cur) + 0.001) {
                inv.setItem(i, ItemStack.EMPTY);   // take it out first -> never duplicated
                bot.setItemSlot(sl, st);
                if (!cur.isEmpty() && !inv.add(cur)) bot.drop(cur, false); // old piece kept or dropped, never lost
            }
        }
        if (PixyConfig.I.autoWeapon) selectWeapon(bot, null);
    }

    public static void selectWeapon(PixyBotPlayer bot, LivingEntity target) { selectWeapon(bot, target, false); }

    /**
     * Sword by default; axe when the target is blocking with a shield (axes disable shields); mace only for a
     * smash (falling attack after a wind-charge launch); spear as a fallback / for its reach; fists if nothing.
     */
    public static void selectWeapon(PixyBotPlayer bot, LivingEntity target, boolean smash) {
        Inventory inv = bot.getInventory();
        boolean breakShield = target != null && target.isBlocking();
        int[] bestIdx = { -1, -1, -1, -1 };
        double[] bestScore = new double[4];
        for (int i = 0; i < MAIN; i++) {
            ItemStack st = inv.getItem(i);
            if (st.isEmpty()) continue;
            int k = kind(path(st));
            if (k < 0) continue;
            double sc = scoreOf(st);
            if (sc > bestScore[k]) { bestScore[k] = sc; bestIdx[k] = i; }
        }
        int pick;
        if (smash && bestIdx[3] >= 0) pick = bestIdx[3];
        else if (breakShield && bestIdx[1] >= 0) pick = bestIdx[1];
        else if (bestIdx[0] >= 0) pick = bestIdx[0];
        else if (bestIdx[2] >= 0) pick = bestIdx[2];
        else if (bestIdx[1] >= 0) pick = bestIdx[1];
        else pick = bestIdx[3];
        if (pick < 0) return;                  // no weapon at all: the bot fights bare-handed (crits still apply)
        int sel = inv.getSelectedSlot();
        if (pick == sel) return;
        ItemStack a = inv.getItem(pick), b = inv.getItem(sel);
        inv.setItem(sel, a);   // swap, nothing created or destroyed
        inv.setItem(pick, b);
    }

    /** Start eating a golden apple. Returns true if eating started. */
    public static boolean tryEat(PixyBotPlayer bot) {
        Inventory inv = bot.getInventory();
        int gap = -1, ench = -1;
        for (int i = 0; i < MAIN; i++) {
            ItemStack st = inv.getItem(i);
            if (gap < 0 && st.is(Items.GOLDEN_APPLE)) gap = i;
            if (ench < 0 && st.is(Items.ENCHANTED_GOLDEN_APPLE)) ench = i;
        }
        boolean veryLow = bot.getHealth() <= 6f;
        int pick = (veryLow && ench >= 0) ? ench : (gap >= 0 ? gap : ench);
        if (pick < 0) return false;
        int sel = inv.getSelectedSlot();
        if (pick != sel) {
            ItemStack a = inv.getItem(pick), b = inv.getItem(sel);
            inv.setItem(sel, a);
            inv.setItem(pick, b);
        }
        bot.gameMode.useItem(bot, bot.level(), bot.getMainHandItem(), InteractionHand.MAIN_HAND);
        return bot.isUsingItem();
    }
}
