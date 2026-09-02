package com.glodon.mordor.kmate.ui.chat;

import com.glodon.mordor.kmate.kelsy.KelsyPaths;
import com.glodon.mordor.kmate.kelsy.KelsyRoomSettings;
import com.glodon.mordor.kmate.kelsy.KelsyRoomSettingsTest.MemoryPrefs;
import com.glodon.mordor.kmate.kelsy.KelsyRuntime;
import com.glodon.mordor.kmate.kelsy.service.AssistantService;
import com.glodon.mordor.kmate.model.Sender;
import com.glodon.mordor.kmate.service.ChatHistory;
import com.glodon.mordor.kmate.service.CryptoService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatControllerKelsyTest {

    @TempDir Path tmp;

    private ChatHistory history;
    private KelsyRuntime runtime;

    @AfterEach
    void tearDown() throws Exception {
        if (history != null) {
            history.flush(2, TimeUnit.SECONDS);
            history.close();
        }
        if (runtime != null) {
            runtime.close();
        }
    }

    @Test
    void mentionDoesNotCallPeerWhenEnabled() throws Exception {
        List<String> peer = new ArrayList<>();
        List<String> asked = new ArrayList<>();
        ChatController c = controller(peer, asked, true, true);
        assertTrue(c.send("@kelsy 你好"));
        assertTrue(peer.isEmpty());
        assertEquals(List.of("你好"), asked);
        var last = c.getMessages().getLast();
        assertEquals(Sender.SELF, last.sender());
        assertEquals("@kelsy 你好", last.content());
    }

    @Test
    void disabledMentionGoesToPeer() throws Exception {
        List<String> peer = new ArrayList<>();
        ChatController c = controller(peer, new ArrayList<>(), false, true);
        assertTrue(c.send("@kelsy 你好"));
        assertEquals(List.of("@kelsy 你好"), peer);
    }

    @Test
    void busyMentionRejectedKeepsNoPeerCall() throws Exception {
        List<String> peer = new ArrayList<>();
        List<String> asked = new ArrayList<>();
        ChatController c = controller(peer, asked, true, true);
        c.send("@kelsy 第一问");
        asked.clear();
        assertFalse(c.send("@kelsy 第二问"));
        assertTrue(asked.isEmpty());
        assertTrue(peer.isEmpty());
    }

    @Test
    void peerStillSendsWhileBusy() throws Exception {
        List<String> peer = new ArrayList<>();
        ChatController c = controller(peer, new ArrayList<>(), true, true);
        c.send("@kelsy 第一问");
        assertTrue(c.send("普通"));
        assertEquals(List.of("普通"), peer);
    }

    private ChatController controller(List<String> peer, List<String> asked,
                                      boolean enabled, boolean configured) throws Exception {
        KelsyPaths paths = KelsyPaths.forHome(tmp);
        Files.createDirectories(paths.config().getParent());
        if (configured) {
            Files.writeString(paths.config(), "{\"model\":{\"apiKey\":\"sk-test\"}}");
        }
        KelsyRoomSettings settings = new KelsyRoomSettings(new MemoryPrefs());
        if (enabled) {
            settings.enable("ROOM", "");
        }
        AssistantService fake = new AssistantService() {
            @Override
            public void chat(String text, ReplyHandler handler) {
                asked.add(text);
            }

            @Override
            public void close() {
            }
        };
        runtime = KelsyRuntime.open(paths, "me", cfg -> fake);
        history = new ChatHistory(
                tmp.resolve("messages.log"),
                CryptoService.forArchive("pw", "ROOM"));
        assertTrue(history.open());
        return new ChatController(
                "ROOM",
                "me",
                history,
                peer::add,
                settings,
                runtime);
    }
}
