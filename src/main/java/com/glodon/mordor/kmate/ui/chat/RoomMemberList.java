package com.glodon.mordor.kmate.ui.chat;

import com.glodon.mordor.kmate.model.AppState;
import com.glodon.mordor.kmate.model.RoomMember;
import com.glodon.mordor.kmate.service.AvatarService;
import com.glodon.mordor.kmate.service.SaveLastLoginService;
import com.glodon.mordor.kmate.ui.AvatarView;
import javafx.beans.binding.Bindings;
import javafx.collections.ListChangeListener;
import javafx.collections.MapChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;

/**
 * 左侧成员列表：头像 + 小字名字，可收起成窄栏。点自己的头像可更换。
 *
 * <p>三种状态：
 * <ul>
 *   <li>展开：宽 120-168px，显示名字 + 人数</li>
 *   <li>收起：宽 52px，只显示头像</li>
 *   <li>点自己的头像：弹文件选择框，换头像</li>
 * </ul>
 *
 * <p>数据来源：订阅 controller.getMembers() + peerAvatars() + state.avatarProperty()，
 * 任意一个变化都重建整列（成员列表规模很小，重建开销可忽略）。
 */
public class RoomMemberList extends VBox {

    // 展开状态的宽度区间：最小 120、首选 136、最大 168
    private static final double EXPANDED_MIN = 120;
    private static final double EXPANDED_PREF = 136;
    private static final double EXPANDED_MAX = 168;
    // 收起状态的宽度：只够放下 28px 头像 + 边距
    private static final double COLLAPSED_WIDTH = 52;

    private final ChatController controller;
    private final VBox rows = new VBox(10);  // 行间距 10
    private final Label title = new Label("聊天室");
    private final Label count = new Label();
    private final FontIcon toggleIcon = new FontIcon(MaterialDesignC.CHEVRON_LEFT);
    private final SaveLastLoginService saveService = new SaveLastLoginService();
    // 当前是否展开
    private boolean expanded = true;

    public RoomMemberList(ChatController controller) {
        this.controller = controller;
        getStyleClass().add("member-list");
        setPadding(new Insets(10, 8, 10, 8));

        title.getStyleClass().add("member-list-title");
        count.getStyleClass().add("member-list-count");
        // "3 人" —— Bindings.size 返回 ObservableIntegerValue，asString("%d 人") 模板
        count.textProperty().bind(Bindings.size(controller.getMembers()).asString("%d 人"));

        // 折叠 / 展开按钮的图标
        toggleIcon.getStyleClass().add("member-list-toggle");

        // 中间弹性空白：把折叠按钮顶到右边
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // 标题栏：标题 + 人数 + 空白 + 折叠图标
        HBox heading = new HBox(4, title, count, spacer, toggleIcon);
        heading.getStyleClass().add("member-list-heading");
        heading.setAlignment(Pos.CENTER_LEFT);
        heading.setCursor(Cursor.HAND);  // 整条标题栏可点
        heading.setOnMouseClicked(e -> setExpanded(!expanded));

        rows.setFillWidth(true);
        // 三个数据源任意变化都重建列表
        controller.getMembers().addListener((ListChangeListener<RoomMember>) c -> rebuild());
        controller.getState().avatarProperty().addListener((obs, o, n) -> rebuild());
        controller.peerAvatars().addListener((MapChangeListener<String, Image>) c -> rebuild());
        rebuild();

        // 行放进 ScrollPane，方便成员多了能滚
        ScrollPane scroll = new ScrollPane(rows);
        scroll.getStyleClass().add("member-list-scroll");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        VBox.setVgrow(scroll, Priority.ALWAYS);  // 让 scroll 占满 VBox 剩余空间

        getChildren().addAll(heading, scroll);
        applyExpanded();
    }

    /**
     * 切换展开 / 收起状态。状态没变就不动，避免无效重建。
     */
    private void setExpanded(boolean value) {
        if (expanded == value) {
            return;
        }
        expanded = value;
        applyExpanded();
        rebuild();
    }

    /**
     * 把 expanded 状态应用到控件：宽度 / 标题可见性 / 图标方向。
     *
     * <p>setManaged(false) 让 title / count 不参与布局（收起时不占空间）。
     * member-list-collapsed 这个 CSS class 让 chat.css 能给收起态写特殊样式。
     */
    private void applyExpanded() {
        getStyleClass().remove("member-list-collapsed");
        title.setVisible(expanded);
        title.setManaged(expanded);
        count.setVisible(expanded);
        count.setManaged(expanded);
        if (expanded) {
            setMinWidth(EXPANDED_MIN);
            setPrefWidth(EXPANDED_PREF);
            setMaxWidth(EXPANDED_MAX);
            toggleIcon.setIconCode(MaterialDesignC.CHEVRON_LEFT);  // 展开时显示左箭头（点它会收起）
        } else {
            getStyleClass().add("member-list-collapsed");
            setMinWidth(COLLAPSED_WIDTH);
            setPrefWidth(COLLAPSED_WIDTH);
            setMaxWidth(COLLAPSED_WIDTH);
            toggleIcon.setIconCode(MaterialDesignC.CHEVRON_RIGHT);  // 收起时显示右箭头（点它会展开）
        }
    }

    /**
     * 重建整列：先清空 rows，按当前 members 一行一行重新生成。
     * 简单粗暴但成员规模小（个位数），没必要做差量更新。
     */
    private void rebuild() {
        rows.getChildren().clear();
        for (RoomMember member : controller.getMembers()) {
            rows.getChildren().add(row(member));
        }
    }

    /**
     * 构造单行：头像 + 名字（可选）。
     *
     * <p>self = true 的行可点击换头像；只有自己一行带这个能力。
     */
    private HBox row(RoomMember member) {
        String name = member.username() == null || member.username().isBlank()
                ? "?" : member.username();
        Image photo = controller.avatarOf(name);
        AvatarView avatar = new AvatarView(name, photo, member.self(), 28);

        HBox cell = new HBox(8);
        cell.getStyleClass().add("member-row");
        cell.setAlignment(expanded ? Pos.CENTER_LEFT : Pos.CENTER);
        cell.getChildren().add(avatar);
        if (expanded) {
            // 展开时显示名字：自己加"（我）"
            Label label = new Label(member.self() ? name + "（我）" : name);
            label.getStyleClass().add("member-name");
            label.setWrapText(false);
            label.setTextOverrun(OverrunStyle.ELLIPSIS);
            label.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(label, Priority.ALWAYS);
            cell.getChildren().add(label);
        }
        if (member.self()) {
            // 只有自己一行能点
            cell.setCursor(Cursor.HAND);
            cell.setOnMouseClicked(e -> pickSelfAvatar(controller.getState()));
        }
        return cell;
    }

    /**
     * 自己换头像：弹文件选择框 → 落盘 → 更新 state.avatar → 通过 ImClient 推给对方。
     */
    private void pickSelfAvatar(AppState state) {
        AvatarService.chooseAndStore(getScene() == null ? null : getScene().getWindow())
                .ifPresent(path -> {
                    saveService.saveAvatarPath(path);
                    // state.avatar 是 ObjectProperty<Image>，触发 peerAvatars 那个 Map 监听不会受影响，
                    // 但本类的 avatarProperty 监听会触发 → rebuild → 自己的头像实时更新
                    state.setAvatar(AvatarService.load(path).orElse(null));
                    // 转 base64 推给对方（ImClient 内部会用 PeerAvatar 事件广播）
                    AvatarService.thumbnailBase64(path).ifPresent(state.client()::setAvatarPlaintext);
                });
    }
}
