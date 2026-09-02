package com.glodon.mordor.kmate.kelsy.service;

public final class SlashCommands {

    public record Result(boolean send, boolean find, String outgoing, String error) {
        public static Result send(String outgoing) {
            return new Result(true, false, outgoing, null);
        }

        public static Result reject(String error) {
            return new Result(false, false, "", error);
        }

        public static Result find(String query) {
            return new Result(false, true, query, null);
        }
    }

    private SlashCommands() {
    }

    public static Result parse(String raw) {
        String text = raw == null ? "" : raw.strip();
        if (!text.startsWith("/")) {
            return Result.send(text);
        }
        int space = text.indexOf(' ');
        String cmd = (space < 0 ? text : text.substring(0, space)).toLowerCase();
        String rest = space < 0 ? "" : text.substring(space + 1).strip();
        return switch (cmd) {
            case "/note" -> rest.isEmpty()
                    ? Result.reject("请写上要记的内容")
                    : Result.send("请将以下内容用 memory_save 归档到知识库，按 AGENTS.md 的记录规范组织：\n" + rest);
            case "/today" -> Result.send(
                    "请用 memory_search / memory_get 汇总今天已归档的工作，列出条目并注明来源路径。");
            case "/tidy" -> Result.send(
                    "MEMORY.md 可能过长。请按 AGENTS.md：把流水迁到 memory/ 或 knowledge/，索引只留现在仍为真的短条目。只许用 memory_save，不要 write_file。");
            case "/find" -> rest.isEmpty()
                    ? Result.reject("用法：/find 关键词，可加 2026-03 或 半年前")
                    : Result.find(rest);
            default -> Result.send(text);
        };
    }
}
