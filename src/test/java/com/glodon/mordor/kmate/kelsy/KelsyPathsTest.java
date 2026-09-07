package com.glodon.mordor.kmate.kelsy;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KelsyPathsTest {

    @Test
    void defaultsUnderKmateKelsy() {
        Path home = Path.of("/tmp/home");
        KelsyPaths paths = KelsyPaths.forHome(home);
        assertEquals(home.resolve(".kmate/kelsy/config.json"), paths.config());
        assertEquals(home.resolve(".kmate/kelsy/workspace"), paths.workspace());
        assertEquals(home.resolve(".kelsy/config.json"), paths.legacyConfig());
    }

    @Test
    void withWorkspaceKeepsConfig() {
        Path home = Path.of("/tmp/home");
        KelsyPaths paths = KelsyPaths.forHome(home).withWorkspace(Path.of("/data/ws"));
        assertEquals(home.resolve(".kmate/kelsy/config.json"), paths.config());
        assertEquals(Path.of("/data/ws"), paths.workspace());
        assertEquals(home.resolve(".kelsy/config.json"), paths.legacyConfig());
    }
}
