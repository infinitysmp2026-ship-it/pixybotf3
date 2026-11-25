package com.pixy.bot;

/** One bot identity: the in-game player name + the account whose public skin that identity wears. */
public final class IdentityEntry {
    public String name;
    public String skin;
    public IdentityEntry() { }
    public IdentityEntry(String name, String skin) { this.name = name; this.skin = skin; }
}
