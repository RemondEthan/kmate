package com.glodon.mordor.kmate.kelsy;

import com.glodon.mordor.kmate.service.ChatHistory;

import java.util.prefs.Preferences;

public final class KelsyRoomSettings {

    private final Preferences prefs;

    public KelsyRoomSettings() {
        this(Preferences.userNodeForPackage(KelsyRoomSettings.class));
    }

    public KelsyRoomSettings(Preferences prefs) {
        this.prefs = prefs;
    }

    public boolean enabled(String imCode) {
        return prefs.getBoolean(onKey(imCode), false);
    }

    public String avatarPath(String imCode) {
        return prefs.get(avatarKey(imCode), "");
    }

    public void enable(String imCode, String avatarPath) {
        prefs.putBoolean(onKey(imCode), true);
        prefs.put(avatarKey(imCode), avatarPath == null ? "" : avatarPath);
    }

    public void disable(String imCode) {
        prefs.putBoolean(onKey(imCode), false);
        prefs.remove(avatarKey(imCode));
    }

    private static String onKey(String imCode) {
        return "on." + ChatHistory.sha256Hex(imCode);
    }

    private static String avatarKey(String imCode) {
        return "avatar." + ChatHistory.sha256Hex(imCode);
    }
}
