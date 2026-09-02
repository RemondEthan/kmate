package com.glodon.mordor.kmate.kelsy.service;

import com.glodon.mordor.kmate.kelsy.config.KelsyConfig.ModelSettings;

import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.extensions.model.openai.compat.deepseek.DeepSeekFormatter;
import io.agentscope.extensions.model.openai.compat.glm.GLMFormatter;
import io.agentscope.extensions.model.openai.compat.kimi.KimiFormatter;
import io.agentscope.extensions.model.openai.compat.minimax.MiniMaxFormatter;
import io.agentscope.extensions.model.openai.formatter.OpenAIBaseFormatter;

public final class ModelFactory {

    public record Resolved(
            String provider,
            String apiKey,
            String baseUrl,
            String modelName,
            OpenAIBaseFormatter formatter) {
    }

    private ModelFactory() {
    }

    public static Resolved resolve(ModelSettings settings) {
        String provider = settings.provider() == null || settings.provider().isBlank()
                ? "minimax"
                : settings.provider().strip().toLowerCase();
        String apiKey = settings.apiKey();
        return switch (provider) {
            case "minimax" -> resolved(provider, apiKey, settings,
                    "https://api.minimaxi.com/v1", "MiniMax-M3", new MiniMaxFormatter());
            case "kimi" -> resolved(provider, apiKey, settings,
                    "https://api.moonshot.cn/v1", "kimi-k2.5", new KimiFormatter());
            case "glm" -> resolved(provider, apiKey, settings,
                    "https://open.bigmodel.cn/api/paas/v4", "glm-4.7", new GLMFormatter());
            case "deepseek" -> resolved(provider, apiKey, settings,
                    "https://api.deepseek.com", "deepseek-chat", new DeepSeekFormatter());
            default -> throw new IllegalArgumentException(
                    "未知 model.provider：" + settings.provider()
                            + "。合法值：minimax, kimi, glm, deepseek");
        };
    }

    public static Model create(ModelSettings settings) {
        Resolved r = resolve(settings);
        return OpenAIChatModel.builder()
                .apiKey(r.apiKey())
                .baseUrl(r.baseUrl())
                .modelName(r.modelName())
                .formatter(r.formatter())
                .stream(true)
                .build();
    }

    private static Resolved resolved(
            String provider,
            String apiKey,
            ModelSettings settings,
            String defaultUrl,
            String defaultName,
            OpenAIBaseFormatter formatter) {
        String url = settings.baseUrl() == null ? defaultUrl : settings.baseUrl();
        String name = settings.modelName() == null ? defaultName : settings.modelName();
        return new Resolved(provider, apiKey, url, name, formatter);
    }
}
