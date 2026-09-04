package com.glodon.mordor.kmate.ui.chat;

import com.glodon.mordor.kmate.model.RoomMember;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class MentionQuery {

    public record Token(int atIndex, String query) {
    }

    public record Applied(String text, int caret) {
    }

    private MentionQuery() {
    }

    public static Optional<Token> parse(String text, int caret) {
        if (text == null || text.isEmpty()) {
            return Optional.empty();
        }
        int pos = Math.max(0, Math.min(caret, text.length()));
        int i = pos - 1;
        while (i >= 0 && !Character.isWhitespace(text.charAt(i))) {
            i--;
        }
        int wordStart = i + 1;
        if (wordStart >= pos || text.charAt(wordStart) != '@') {
            return Optional.empty();
        }
        return Optional.of(new Token(wordStart, text.substring(wordStart + 1, pos)));
    }

    public static List<RoomMember> candidates(List<RoomMember> members, String query) {
        return List.of();
    }

    public static Applied apply(String text, int atIndex, int caret, String nickname) {
        return new Applied(text == null ? "" : text, caret);
    }
}
