package com.glodon.mordor.kmate.kelsy;

import com.glodon.mordor.kmate.kelsy.config.ConfigLoader;
import com.glodon.mordor.kmate.kelsy.config.KelsyConfig;
import com.glodon.mordor.kmate.kelsy.service.AssistantService;
import com.glodon.mordor.kmate.kelsy.service.KnowledgeStore;
import com.glodon.mordor.kmate.kelsy.service.WorkspaceSeeder;

import java.util.function.Function;

public final class KelsyRuntime implements AutoCloseable {

    private static KelsyRuntime instance;

    private final KelsyPaths paths;
    private final Function<KelsyConfig, AssistantService> factory;
    private AssistantService assistant;
    private boolean closed;

    public static synchronized KelsyRuntime shared(String username) {
        if (instance == null) {
            instance = open(KelsyPaths.defaults(), username, KelsyRuntime::createLocal);
        }
        return instance;
    }

    public static synchronized void shutdown() {
        if (instance != null) {
            instance.close();
            instance = null;
        }
    }

    public static KelsyRuntime open(KelsyPaths paths, String username,
                                    Function<KelsyConfig, AssistantService> factory) {
        ConfigLoader.ensureAndHasApiKey(paths);
        WorkspaceSeeder.seed(paths.workspace());
        WorkspaceSeeder.seed(KnowledgeStore.knowledgeRoot(paths.workspace(), username));
        return new KelsyRuntime(paths, factory);
    }

    private KelsyRuntime(KelsyPaths paths, Function<KelsyConfig, AssistantService> factory) {
        this.paths = paths;
        this.factory = factory;
    }

    public boolean hasApiKey() {
        return ConfigLoader.ensureAndHasApiKey(paths);
    }

    public KnowledgeStore store(String username) {
        return KnowledgeStore.forUser(paths.workspace(), username);
    }

    public synchronized AssistantService ensureAssistant() {
        if (closed) {
            return null;
        }
        if (assistant != null) {
            return assistant;
        }
        if (!hasApiKey()) {
            return null;
        }
        assistant = factory.apply(ConfigLoader.loadOrThrow(paths));
        return assistant;
    }

    public AssistantService assistant() {
        return assistant;
    }

    public KelsyPaths paths() {
        return paths;
    }

    @Override
    public synchronized void close() {
        closed = true;
        if (assistant != null) {
            assistant.close();
            assistant = null;
        }
    }

    static AssistantService createLocal(KelsyConfig config) {
        throw new UnsupportedOperationException("LocalAssistantService in a later task");
    }
}
