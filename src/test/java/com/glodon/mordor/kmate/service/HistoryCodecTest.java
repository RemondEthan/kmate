package com.glodon.mordor.kmate.service;

import com.glodon.mordor.kmate.model.Message;
import com.glodon.mordor.kmate.model.Sender;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HistoryCodecTest {

    @Test
    void encodeThenDecodePreservesFieldsIncludingTimestamp() {
        LocalDateTime at = LocalDateTime.of(2026, 8, 28, 15, 16, 32);
        Message original = new Message("id-1", Sender.PEER, "你好\n下一行", at, "张三");

        Message back = HistoryCodec.decode(HistoryCodec.encode(original));

        assertEquals(original, back);
        assertEquals(at, back.timestamp());
    }

    @Test
    void systemMessageRoundTripWithEmptyFrom() {
        Message original = new Message(
                "sys", Sender.SYSTEM, "已加入房间", LocalDateTime.of(2026, 1, 2, 3, 4, 5));

        assertEquals(original, HistoryCodec.decode(HistoryCodec.encode(original)));
    }

    @Test
    void rejectsIncompleteJson() {
        assertThrows(IllegalArgumentException.class, () -> HistoryCodec.decode("{\"id\":\"x\"}"));
    }
}
