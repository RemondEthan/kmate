package com.glodon.mordor.kmate.model;

public record RoomMember(int userId, String username, boolean self) {
    public static final int KELSY_ID = -2;
    /** 本地秘书在成员列表和 @ 提及里的显示名，不占用 server 席位。 */
    public static final String SECRETARY_NAME = "tars";

    public static RoomMember kelsy() {
        return new RoomMember(KELSY_ID, SECRETARY_NAME, false);
    }

    public boolean isKelsy() {
        return userId == KELSY_ID;
    }
}
