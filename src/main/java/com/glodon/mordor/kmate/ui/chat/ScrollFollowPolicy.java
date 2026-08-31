package com.glodon.mordor.kmate.ui.chat;

/**
 * 滚动位置 → 行为的判定逻辑。
 *
 * <p>区分「布局/程序化改 vvalue」和「用户拖动滚动条」：
 * 进房灌历史时 vvalue 会落到中间，不能当成用户"离开底部"。
 *
 * <p>MessageListView 在以下两种情况会程序化改 vvalue：
 * <ul>
 *   <li>新消息到达 + 跟随最新 → 钉到底部（vvalue = 1.0）</li>
 *   <li>上翻加载历史 → keepAnchored（vvalue 落到中间某个值）</li>
 * </ul>
 * 这两种都要被 "programmatic" 过滤掉，不触发 controller 的状态切换。
 */
final class ScrollFollowPolicy {

    /**
     * 滚动行为枚举：
     * <ul>
     *   <li>NONE：什么都不做</li>
     *   <li>STOP_FOLLOWING：从底部往上拉，标记"不再跟随最新"</li>
     *   <li>LOAD_OLDER：滚到顶，请求加载更老的历史</li>
     *   <li>FOLLOW_LATEST：滚到底，切回"跟随最新"</li>
     * </ul>
     */
    enum Action { NONE, STOP_FOLLOWING, LOAD_OLDER, FOLLOW_LATEST }

    private ScrollFollowPolicy() {}

    /**
     * 把 vvalue 的新旧值翻译成 Action。
     *
     * @param oldV        上一次的 vvalue（0~1）
     * @param newV        当前的 vvalue
     * @param canScroll   当前是否真的有可滚动空间（内容比视口高）
     * @param programmatic 是否程序化设置 vvalue（MessageListView 的 pinning 字段）
     */
    static Action onVvalue(double oldV, double newV, boolean canScroll, boolean programmatic) {
        // 程序化改动 or 没法滚动 → 忽略（不是用户主动行为）
        if (programmatic || !canScroll) {
            return Action.NONE;
        }
        // 从非顶部滚到顶部：触发加载更老
        if (oldV > 0.02 && newV <= 0.02) {
            return Action.LOAD_OLDER;
        }
        // 滚到底部：切回跟随最新
        if (newV >= 0.98) {
            return Action.FOLLOW_LATEST;
        }
        // 从底部（>=0.98）往下拉到 < 0.95：标记不再跟随
        // 用 0.95 而不是 0.98 是给一点缓冲（避免抖动来回切）
        if (oldV >= 0.98 && newV < 0.95) {
            return Action.STOP_FOLLOWING;
        }
        return Action.NONE;
    }

    /**
     * 是否应该把列表钉到底部。
     * 条件：跟随最新 && 不是 prepend（上翻加载）。
     */
    static boolean shouldPinToBottom(boolean followingLatest, boolean prepend) {
        return followingLatest && !prepend;
    }
}
