package com.pixy.bot;

import java.util.ArrayList;
import java.util.List;

/**
 * The built-in 50 identities (names are plain Minecraft-style usernames, no numbers).
 * "skin" is the Minecraft account whose PUBLIC skin is fetched from Mojang at runtime - no skin file is bundled.
 * 50 different accounts = 50 different looks. Edit config/pixybot-identities.json to swap any of them.
 */
public final class DefaultIdentities {
    private DefaultIdentities() { }

    private static final String[][] PAIRS = {
        {"PixiePlays", "Dream"},          {"WardenPlays", "Technoblade"}, {"Xyros", "TommyInnit"},
        {"Cyrus", "Sapnap"},              {"Zayox", "GeorgeNotFound"},    {"Sylphie", "Skeppy"},
        {"Rudeus", "BadBoyHalo"},         {"Aether", "Ph1LzA"},           {"Nyx", "Ranboo"},
        {"Kaizen", "Quackity"},           {"Vex", "Tubbo_"},              {"Draco", "WilburSoot"},
        {"Zephyr", "Fundy"},              {"Orion", "Punz"},              {"Axel", "Nihachu"},
        {"Kairo", "Awesamdude"},          {"Frost", "Antfrost"},          {"Blaze", "Eret"},
        {"Echo", "Jschlatt"},             {"Nova", "Purpled"},            {"Lyra", "Sneegsnag"},
        {"Kael", "Grian"},                {"Soren", "MumboJumbo"},        {"Mira", "Xisuma"},
        {"Jinx", "Vikkstar123"},          {"Onyx", "CaptainSparklez"},    {"Talon", "DanTDM"},
        {"Rex", "SSundee"},               {"Sable", "PopularMMOs"},       {"Quill", "Mithzan"},
        {"Ronan", "Dinnerbone"},          {"Elara", "jeb_"},              {"Fenrir", "Notch"},
        {"Gale", "Grumm"},                {"Hexa", "Searge"},             {"Ivor", "PewDiePie"},
        {"Juno", "MrBeast"},              {"Knox", "Ninja"},              {"Luna", "Sky"},
        {"Maverick", "LDShadowLady"},     {"Neo", "InTheLittleWood"},     {"Odin", "Smajor1995"},
        {"Phoenix", "Tangotek"},          {"Riven", "Docm77"},            {"Slate", "Zombiecleo"},
        {"Thorne", "Gizzy14gazza"},       {"Ursa", "Bdoubleo100"},        {"Wren", "GeminiTay"},
        {"Yuki", "Aphmau"},               {"Zane", "iHasCupquake"}
    };

    /** Used when a skin lookup fails, so the first 50 bots still keep different skins. */
    private static final String[] BACKUP_SKINS = {
        "Herobrine", "Hypixel", "Badlion", "Lunar", "Mineplex", "CubeCraft",
        "Hermitcraft", "stampylonghead", "Technoblade_", "Skeppy_"
    };

    public static List<IdentityEntry> list() {
        List<IdentityEntry> l = new ArrayList<>();
        for (String[] p : PAIRS) l.add(new IdentityEntry(p[0], p[1]));
        return l;
    }

    public static List<String> backups() { return new ArrayList<>(List.of(BACKUP_SKINS)); }
}
