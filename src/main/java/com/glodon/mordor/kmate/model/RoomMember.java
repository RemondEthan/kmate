package com.glodon.mordor.kmate.model;

public record RoomMember(int userId, String username, boolean self) {
    public static final int KELSY_ID = -2;

    public static RoomMember kelsy() {
        return new RoomMember(KELSY_ID, "kelsy", false);
    }

    public boolean isKelsy() {
        return userId == KELSY_ID;
    }
}
