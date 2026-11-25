package com.pixy.bot;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.PropertyMap;
import net.minecraft.server.MinecraftServer;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cached skin lookup. The public skin of a Minecraft account is fetched from Mojang at runtime (nothing bundled).
 * 20 bots asking for the same skin cause ONE request; failures are remembered for 5 minutes.
 */
public final class SkinPool {
    private static final Map<String, CompletableFuture<Optional<PropertyMap>>> CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Long> FAILED_UNTIL = new ConcurrentHashMap<>();
    private static final long RETRY_MS = 5 * 60 * 1000L;

    private SkinPool() { }

    public static boolean recentlyFailed(String name) {
        Long until = FAILED_UNTIL.get(name.toLowerCase(Locale.ROOT));
        return until != null && until > System.currentTimeMillis();
    }

    public static void markFailed(String name) {
        String k = name.toLowerCase(Locale.ROOT);
        FAILED_UNTIL.put(k, System.currentTimeMillis() + RETRY_MS);
        CACHE.remove(k);
    }

    public static CompletableFuture<Optional<PropertyMap>> lookup(MinecraftServer server, String name) {
        if (CACHE.size() > 300) CACHE.clear();
        return CACHE.computeIfAbsent(name.toLowerCase(Locale.ROOT), k ->
            CompletableFuture.supplyAsync(() -> {
                try {
                    return server.services().profileResolver().fetchByName(name).map(GameProfile::properties);
                } catch (Exception e) {
                    return Optional.<PropertyMap>empty();
                }
            }));
    }
}
