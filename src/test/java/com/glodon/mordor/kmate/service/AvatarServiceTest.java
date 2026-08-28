package com.glodon.mordor.kmate.service;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AvatarServiceTest {

    /** 与当前 kserver handle_avatar 对齐；超限会被静默丢弃。 */
    private static final int SERVER_AVATAR_CONTENT_LIMIT = 32768;

    @Test
    void thumbnailCipherFitsServerLimit() throws Exception {
        Path tmp = Files.createTempFile("kmate-avatar", ".png");
        try {
            BufferedImage src = new BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < 400; y++) {
                for (int x = 0; x < 400; x++) {
                    src.setRGB(x, y, (x * 73 + y * 91) & 0xFFFFFF);
                }
            }
            assertTrue(ImageIO.write(src, "png", tmp.toFile()));

            String b64 = AvatarService.thumbnailBase64(tmp.toString()).orElseThrow();
            assertFalse(b64.isBlank());
            byte[] raw = Base64.getDecoder().decode(b64);
            assertTrue(raw.length > 32);

            CryptoService crypto = new CryptoService();
            crypto.initialize("secret", "padding");
            String cipher = crypto.encrypt(b64);
            assertTrue(cipher.length() <= SERVER_AVATAR_CONTENT_LIMIT,
                    "encrypted avatar content " + cipher.length()
                            + " exceeds server drop limit " + SERVER_AVATAR_CONTENT_LIMIT);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
