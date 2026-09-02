package com.glodon.mordor.kmate.kelsy;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.prefs.AbstractPreferences;
import java.util.prefs.BackingStoreException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KelsyRoomSettingsTest {

    @Test
    void roomsAreIsolatedAndRemovable() {
        MemoryPrefs prefs = new MemoryPrefs();
        KelsyRoomSettings settings = new KelsyRoomSettings(prefs);
        assertFalse(settings.enabled("ROOM-A"));
        settings.enable("ROOM-A", "/tmp/a.png");
        settings.enable("ROOM-B", "/tmp/b.png");
        assertTrue(settings.enabled("ROOM-A"));
        assertEquals("/tmp/a.png", settings.avatarPath("ROOM-A"));
        assertEquals("/tmp/b.png", settings.avatarPath("ROOM-B"));
        settings.disable("ROOM-A");
        assertFalse(settings.enabled("ROOM-A"));
        assertTrue(settings.enabled("ROOM-B"));
        assertEquals("", settings.avatarPath("ROOM-A"));
    }

    static final class MemoryPrefs extends AbstractPreferences {
        private final Map<String, String> values = new HashMap<>();

        MemoryPrefs() {
            super(null, "");
        }

        @Override protected void putSpi(String key, String value) { values.put(key, value); }
        @Override protected String getSpi(String key) { return values.get(key); }
        @Override protected void removeSpi(String key) { values.remove(key); }
        @Override protected void removeNodeSpi() { values.clear(); }
        @Override protected String[] keysSpi() { return values.keySet().toArray(String[]::new); }
        @Override protected String[] childrenNamesSpi() { return new String[0]; }
        @Override protected AbstractPreferences childSpi(String name) { return this; }
        @Override protected void syncSpi() {}
        @Override protected void flushSpi() {}
    }
}
