package com.glodon.mordor.kmate.kelsy;

import com.glodon.mordor.kmate.model.RoomMember;

public final class KelsyMention {

    public static final String NAME = RoomMember.SECRETARY_NAME;
    public static final String INSERT = insert(NAME);

    private KelsyMention() {
    }

    public static String insert(String nickname) {
        return "@" + normalize(nickname) + " ";
    }

    public static boolean isMention(String text, String nickname) {
        String at = "@" + normalize(nickname);
        String body = text == null ? "" : text.strip();
        int n = at.length();
        if (body.length() < n || !body.regionMatches(true, 0, at, 0, n)) {
            return false;
        }
        return body.length() == n || Character.isWhitespace(body.charAt(n));
    }

    public static String strip(String text, String nickname) {
        if (!isMention(text, nickname)) {
            return text == null ? "" : text.strip();
        }
        String body = text.strip();
        int n = ("@" + normalize(nickname)).length();
        return body.length() == n ? "" : body.substring(n).strip();
    }

    public static boolean isMention(String text) {
        return isMention(text, NAME);
    }

    public static String strip(String text) {
        return strip(text, NAME);
    }

    private static String normalize(String nickname) {
        String n = nickname == null ? "" : nickname.strip();
        return n.isEmpty() ? RoomMember.SECRETARY_NAME : n;
    }
}
