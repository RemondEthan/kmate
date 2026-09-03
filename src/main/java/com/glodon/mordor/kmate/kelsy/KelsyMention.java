package com.glodon.mordor.kmate.kelsy;

import com.glodon.mordor.kmate.model.RoomMember;

public final class KelsyMention {

    public static final String NAME = RoomMember.SECRETARY_NAME;
    public static final String AT = "@" + NAME;
    public static final String INSERT = AT + " ";

    private KelsyMention() {
    }

    public static boolean isMention(String text) {
        String body = text == null ? "" : text.strip();
        int n = AT.length();
        if (body.length() < n || !body.regionMatches(true, 0, AT, 0, n)) {
            return false;
        }
        return body.length() == n || Character.isWhitespace(body.charAt(n));
    }

    public static String strip(String text) {
        if (!isMention(text)) {
            return text == null ? "" : text.strip();
        }
        String body = text.strip();
        int n = AT.length();
        return body.length() == n ? "" : body.substring(n).strip();
    }
}
