package com.mordor.kmate.ui.chat;

/**
 * 区分「布局/程序化改 vvalue」和「用户拖动滚动条」。
 * 进房灌历史时 vvalue 会落到中间，不能当成用户离开底部。
 */
final class ScrollFollowPolicy {

    enum Action { NONE, STOP_FOLLOWING, LOAD_OLDER, FOLLOW_LATEST }

    private ScrollFollowPolicy() {}

    static Action onVvalue(double oldV, double newV, boolean canScroll, boolean programmatic) {
        if (programmatic || !canScroll) {
            return Action.NONE;
        }
        if (oldV > 0.02 && newV <= 0.02) {
            return Action.LOAD_OLDER;
        }
        if (newV >= 0.98) {
            return Action.FOLLOW_LATEST;
        }
        if (oldV >= 0.98 && newV < 0.95) {
            return Action.STOP_FOLLOWING;
        }
        return Action.NONE;
    }

    static boolean shouldPinToBottom(boolean followingLatest, boolean prepend) {
        return followingLatest && !prepend;
    }
}
