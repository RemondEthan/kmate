package com.glodon.mordor.kmate.kelsy;

public final class KelsyMention {

    public static final String NAME = "kelsy";
    public static final String INSERT = "@kelsy ";

    private KelsyMention() {
    }

    public static boolean isMention(String text) {
        String body = text == null ? "" : text.strip();
        if (body.length() < 6 || !body.regionMatches(true, 0, "@kelsy", 0, 6)) {
            return false;
        }
        return body.length() == 6 || Character.isWhitespace(body.charAt(6));
    }

    public static String strip(String text) {
        if (!isMention(text)) {
            return text == null ? "" : text.strip();
        }
        String body = text.strip();
        return body.length() == 6 ? "" : body.substring(6).strip();
    }
}
