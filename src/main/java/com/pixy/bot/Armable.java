package com.pixy.bot;

/** Anything that can be switched into hunting by a horn. Kept free of Minecraft types so it can be unit-tested. */
public interface Armable {
    boolean isHunting();
    void activate(long generation);
}
