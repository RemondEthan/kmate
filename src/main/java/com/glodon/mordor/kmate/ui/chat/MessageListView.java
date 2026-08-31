package com.glodon.mordor.kmate.ui.chat;

import com.glodon.mordor.kmate.model.AppState;
import com.glodon.mordor.kmate.model.Message;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.value.ObservableValue;
import javafx.util.Duration;
import javafx.collections.ListChangeListener;
import javafx.collections.MapChangeListener;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.layout.VBox;

/**
 * 消息列表：一个可滚动的 ScrollPane，里面套一个 VBox，每条消息是一个 {@link MessageBubble}。
 *
 * <p>渲染来源：订阅 {@link ChatController#getMessages()} 的 ListChangeListener，
 * 处理"追加""删除""上翻插入"三种变化。仅在"跟随最新"时钉住底部。
 *
 * <p>背景由 ChatPane 铺在底层，本控件保持透明（不抢背景）。
 *
 * <p>滚动策略由 {@link ScrollFollowPolicy} 决定：
 * <ul>
 *   <li>滚到顶（vvalue <= 0.02）→ 请求加载更老的历史</li>
 *   <li>滚到底（vvalue >= 0.98）→ 切回"跟随最新"</li>
 *   <li>从底部往上拉（>= 0.98 → < 0.95）→ 切到"不再跟随"</li>
 *   <li>程序化改 vvalue → 不触发以上动作（pinning 字段过滤）</li>
 * </ul>
 */
public class MessageListView extends ScrollPane {

    private final VBox container;
    private final ChatController controller;
    // 程序化改 vvalue 时置 true，避免被 ScrollFollowPolicy 当成用户拖动
    private boolean pinning;

    public MessageListView(ChatController controller) {
        this.controller = controller;
        getStyleClass().add("message-list");

        // ScrollPane 配置
        setFitToWidth(true);   // 内容宽度 = ScrollPane 宽度
        setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);  // 不显示横向滚动条
        setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        // 背景透明：让 ChatPane 的背景图透出来
        setStyle("-fx-background-color: transparent; -fx-background: transparent;");

        container = new VBox(4);  // VBox 子节点间距 4
        container.setPadding(new Insets(8));
        container.getStyleClass().add("message-list-container");
        container.setFillWidth(true);

        setContent(container);

        // 初始渲染：把当前 messages 列表里的全部画一遍
        for (Message m : controller.getMessages()) {
            container.getChildren().add(newBubble(m));
        }

        // 订阅 messages 列表变化
        controller.getMessages().addListener((ListChangeListener<Message>) c -> {
            while (c.next()) {
                // 处理删除（一般是 evictFromHead 触发的，从头删）
                if (c.wasRemoved()) {
                    int from = c.getFrom();
                    container.getChildren().remove(from, from + c.getRemovedSize());
                }
                // 处理新增
                if (c.wasAdded()) {
                    // 判断是不是"上翻插入"（add at index 0，且当前不跟随最新）
                    boolean prepend = c.getFrom() == 0 && !controller.followingLatest();
                    // 上翻插入时记住第一个旧节点，加载完后把它的位置固定住
                    Node anchor = prepend && !container.getChildren().isEmpty()
                            ? container.getChildren().get(0)
                            : null;
                    int i = c.getFrom();
                    for (Message m : c.getAddedSubList()) {
                        container.getChildren().add(i++, newBubble(m));
                    }
                    if (prepend && anchor != null) {
                        // 保持锚点位置不变（不要让用户感觉列表跳了一下）
                        keepAnchored(anchor);
                    } else if (ScrollFollowPolicy.shouldPinToBottom(controller.followingLatest(), prepend)) {
                        // 正常追加，且跟随最新 → 钉住底部
                        pinToBottom();
                    }
                }
            }
        });

        // 容器高度变化时：跟随最新就重新钉住底部（防止窗口缩放后位置错乱）
        container.heightProperty().addListener((obs, o, n) -> {
            if (controller.followingLatest()) {
                pinToBottom();
            }
        });
        // 监听滚动条位置变化：决定要不要触发"加载更老""回到最新"等动作
        vvalueProperty().addListener((obs, oldV, v) -> {
            switch (ScrollFollowPolicy.onVvalue(
                    oldV.doubleValue(), v.doubleValue(), canScroll(), pinning)) {
                case LOAD_OLDER -> controller.requestOlder();
                case FOLLOW_LATEST -> controller.followLatest();
                case STOP_FOLLOWING -> controller.stopFollowing();
                case NONE -> {
                }
            }
        });
        // 对方头像缓存变化时，整个列表重建（每条消息都要拿头像）
        controller.peerAvatars().addListener((MapChangeListener<String, Image>) c -> rebuild());
    }

    /**
     * 钉到底部：分多帧 + 短暂停顿执行 4 次 setVvalue(1.0)。
     *
     * <p>为什么要这么麻烦：FX 的布局是异步的，新加进去的 MessageBubble 还没完成布局时
     * setVvalue(1.0) 不一定真到底。多重 runLater + 250ms PauseTransition 保证
     * 图片加载完 / 布局完成后还能再钉一次。
     */
    private void pinToBottom() {
        pinning = true;
        setVvalue(1.0);
        Platform.runLater(() -> {
            setVvalue(1.0);
            Platform.runLater(() -> {
                setVvalue(1.0);
                // 再等 250ms 钉一次（处理图片加载完后撑高容器的情况）
                PauseTransition hold = new PauseTransition(Duration.millis(250));
                hold.setOnFinished(e -> {
                    setVvalue(1.0);
                    pinning = false;
                });
                hold.play();
            });
        });
    }

    /**
     * 上翻加载历史时，保持锚点（用户当前正在看的那条）位置不变。
     *
     * <p>做法：算出锚点在新内容里的归一化位置，setVvalue 到那个位置。
     * 顺便：如果当前已经接近顶部（<= 0.02），再次触发加载更多历史。
     */
    private void keepAnchored(Node anchor) {
        Platform.runLater(() -> {
            double contentH = container.getHeight();
            double viewH = getViewportBounds().getHeight();
            if (contentH > viewH) {
                // 锚点位置 / 可滚动范围 = 新的 vvalue
                setVvalue(anchor.getBoundsInParent().getMinY() / (contentH - viewH));
            }
            if (canScroll() && getVvalue() <= 0.02) {
                controller.requestOlder();
            }
        });
    }

    /**
     * 当前是否真的能滚动（内容高度 > 视口高度 + 8 容差）。
     * 内容比视口还短时，vvalue 永远在 0~1 之间抖动，没意义判定"滚到顶/底"。
     */
    private boolean canScroll() {
        return container.getHeight() > getViewportBounds().getHeight() + 8;
    }

    /**
     * 头像缓存变了 → 整列表重建。
     * 不做差量更新是因为 MessageBubble 本身就有头像，重画一遍最简单。
     */
    private void rebuild() {
        container.getChildren().clear();
        for (Message m : controller.getMessages()) {
            container.getChildren().add(newBubble(m));
        }
    }

    /**
     * 气泡最大宽度 = ScrollPane 宽 × 0.7。
     * 用 Observable 返回，这样宽度会随窗口缩放自动重算。
     */
    private ObservableValue<? extends Number> bubbleMaxWidth() {
        return widthProperty().multiply(0.7);
    }

    /**
     * 构造一个 MessageBubble。
     *
     * <p>对方名兜底：如果消息没 from 字段（系统消息转的等），用 state.peerName。
     */
    private MessageBubble newBubble(Message m) {
        AppState s = controller.getState();
        String peer = (m.from() != null && !m.from().isBlank()) ? m.from() : s.peerName();
        return new MessageBubble(m, s.username(), peer, controller.avatarOf(m.from()),
                s.avatar(), bubbleMaxWidth());
    }
}
