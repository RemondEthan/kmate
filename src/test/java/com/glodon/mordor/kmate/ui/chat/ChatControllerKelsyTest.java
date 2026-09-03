package com.glodon.mordor.kmate.ui.chat;

import com.glodon.mordor.kmate.kelsy.KelsyPaths;
import com.glodon.mordor.kmate.kelsy.KelsyRoomSettings;
import com.glodon.mordor.kmate.kelsy.KelsyRoomSettingsTest.MemoryPrefs;
import com.glodon.mordor.kmate.kelsy.KelsyRuntime;
import com.glodon.mordor.kmate.kelsy.service.AssistantService;
import com.glodon.mordor.kmate.model.RoomMember;
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
import static org.junit.jupiter.api.Assertions.assertNull;
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
        assertTrue(c.send("@tars 你好").accepted());
        assertTrue(peer.isEmpty());
        assertEquals(List.of("你好"), asked);
        var last = c.getMessages().getLast();
        assertEquals(Sender.SELF, last.sender());
        assertEquals("@tars 你好", last.content());
    }

    @Test
    void disabledMentionGoesToPeer() throws Exception {
        List<String> peer = new ArrayList<>();
        ChatController c = controller(peer, new ArrayList<>(), false, true);
        assertTrue(c.send("@tars 你好").accepted());
        assertEquals(List.of("@tars 你好"), peer);
    }

    @Test
    void busyMentionRejectedKeepsNoPeerCall() throws Exception {
        List<String> peer = new ArrayList<>();
        List<String> asked = new ArrayList<>();
        ChatController c = controller(peer, asked, true, true);
        c.send("@tars 第一问");
        asked.clear();
        ChatController.SendResult busy = c.send("@tars 第二问");
        assertFalse(busy.accepted());
        assertEquals(ChatController.BUSY_HINT, busy.hint());
        assertTrue(asked.isEmpty());
        assertTrue(peer.isEmpty());
    }

    @Test
    void peerStillSendsWhileBusy() throws Exception {
        List<String> peer = new ArrayList<>();
        ChatController c = controller(peer, new ArrayList<>(), true, true);
        c.send("@tars 第一问");
        assertTrue(c.send("普通").accepted());
        assertEquals(List.of("普通"), peer);
    }

    @Test
    void enableAddsKelsyWithoutChangingHumanCount() throws Exception {
        ChatController c = controller(new ArrayList<>(), new ArrayList<>(), false, true);
        assertEquals("ROOM", c.imCode());
        assertEquals(1, c.humanCountProperty().get());
        assertTrue(c.getMembers().stream().noneMatch(RoomMember::isKelsy));
        c.enableKelsy("");
        assertTrue(c.getMembers().stream().anyMatch(RoomMember::isKelsy));
        assertEquals(2, c.getMembers().size());
        assertEquals(1, c.humanCountProperty().get());
        assertEquals(RoomMember.SECRETARY_NAME, RoomMember.kelsy().username());
        assertNull(c.avatarOf(RoomMember.SECRETARY_NAME));
        assertNull(c.avatarOf("kelsy"));
        c.disableKelsy();
        assertTrue(c.getMembers().stream().noneMatch(RoomMember::isKelsy));
        assertEquals(1, c.getMembers().size());
        assertEquals(1, c.humanCountProperty().get());
    }

    @Test
    void enabledCtorIncludesKelsyInMembersNotHumanCount() throws Exception {
        ChatController c = controller(new ArrayList<>(), new ArrayList<>(), true, true);
        assertEquals(2, c.getMembers().size());
        assertEquals(1, c.humanCountProperty().get());
    }

    @Test
    void offlinePeerIsRejectedWithoutCallingSender() throws Exception {
        List<String> peer = new ArrayList<>();
        ChatController c = controller(peer, new ArrayList<>(), false, true, true);
        assertEquals(ChatController.OFFLINE_IM_CODE, c.imCode());
        ChatController.SendResult r = c.send("普通");
        assertFalse(r.accepted());
        assertEquals(ChatController.OFFLINE_REJECT_HINT, r.hint());
        assertTrue(peer.isEmpty());
        assertTrue(c.getMessages().isEmpty());
    }

    @Test
    void offlineMentionWithoutTarsIsRejected() throws Exception {
        List<String> peer = new ArrayList<>();
        ChatController c = controller(peer, new ArrayList<>(), false, true, true);
        ChatController.SendResult r = c.send("@tars 你好");
        assertFalse(r.accepted());
        assertEquals(ChatController.OFFLINE_REJECT_HINT, r.hint());
        assertTrue(peer.isEmpty());
    }

    @Test
    void offlineAskDoesNotCallPeer() throws Exception {
        List<String> peer = new ArrayList<>();
        List<String> asked = new ArrayList<>();
        ChatController c = controller(peer, asked, true, true, true);
        assertTrue(c.send("@tars 你好").accepted());
        assertEquals(List.of("你好"), asked);
        assertTrue(peer.isEmpty());
        ChatController.SendResult plain = c.send("普通");
        assertFalse(plain.accepted());
        assertEquals(ChatController.OFFLINE_REJECT_HINT, plain.hint());
        assertTrue(peer.isEmpty());
    }

    private ChatController controller(List<String> peer, List<String> asked,
                                      boolean enabled, boolean configured) throws Exception {
        return controller(peer, asked, enabled, configured, false);
    }

    private ChatController controller(List<String> peer, List<String> asked,
                                      boolean enabled, boolean configured,
                                      boolean offline) throws Exception {
        KelsyPaths paths = KelsyPaths.forHome(tmp);
        Files.createDirectories(paths.config().getParent());
        if (configured) {
            Files.writeString(paths.config(), "{\"model\":{\"apiKey\":\"sk-test\"}}");
        }
        String thatImCode = offline ? ChatController.OFFLINE_IM_CODE : "ROOM";
        KelsyRoomSettings settings = new KelsyRoomSettings(new MemoryPrefs());
        if (enabled) {
            settings.enable(thatImCode, "");
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
                CryptoService.forArchive("pw", thatImCode));
        assertTrue(history.open());
        return new ChatController(
                thatImCode,
                "me",
                history,
                peer::add,
                settings,
                runtime,
                offline);
    }
}
