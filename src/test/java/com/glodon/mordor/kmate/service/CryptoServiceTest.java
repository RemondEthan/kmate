package com.glodon.mordor.kmate.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CryptoServiceTest {

    @Test
    void encryptThenDecryptReturnsPlaintext() {
        CryptoService crypto = new CryptoService();
        crypto.initialize("secret", "aB3dE5gH");

        String plain = "你好，Kmate";
        String cipher = crypto.encrypt(plain);

        assertNotEquals(plain, cipher);
        assertEquals(plain, crypto.decrypt(cipher));
    }

    @Test
    void differentPaddingProducesDifferentKey() {
        CryptoService a = new CryptoService();
        a.initialize("secret", "padding-one");
        String cipher = a.encrypt("hello");

        CryptoService b = new CryptoService();
        b.initialize("secret", "padding-two");

        assertThrows(CryptoService.CryptoException.class, () -> b.decrypt(cipher));
    }

    @Test
    void samePasswordAndPaddingCanCrossDecrypt() {
        CryptoService sender = new CryptoService();
        sender.initialize("pass", "pad==");
        CryptoService receiver = new CryptoService();
        receiver.initialize("pass", "pad==");

        assertEquals("ping", receiver.decrypt(sender.encrypt("ping")));
    }

    @Test
    void rejectsShortCiphertext() {
        CryptoService crypto = new CryptoService();
        crypto.initialize("secret", "pad");
        assertThrows(CryptoService.CryptoException.class, () -> crypto.decrypt("AAAA"));
    }
}
