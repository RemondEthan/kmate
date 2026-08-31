package com.glodon.mordor.kmate.model;

import com.glodon.mordor.kmate.service.ImClient;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.scene.image.Image;

/**
 * 当前登录态的可观察(observable)快照。
 *
 * 这里大量用到 JavaFX 的 *Property 类,这是 JavaFX 数据绑定的核心概念:
 *
 *   StringProperty / BooleanProperty / ObjectProperty&lt;T&gt;: 可观察的值容器。
 *     - getValue() / get() 取当前值(也可写作 avatar.get())
 *     - setValue() / set() 改值,改值时会自动通知所有监听者(listener)
 *     - addListener(...) 注册一个 Observable → 旧值/新值的回调
 *     - bind(otherProperty) 把本属性绑定到另一个属性,值永远跟随对方
 *
 *   为什么要这样:UI 上很多控件需要随状态变化自动刷新(标题、成员数、对方在线状态、头像)。
 *   不用 Property 就要在每个 setXxx 里手动调一遍 UI 更新;用了 Property 之后
 *   UI 控件(如 Label.textProperty().bind(...))直接订阅,源头改了 UI 自动跟着改。
 *
 *   不可变字段(username / client):登录时确定,运行期不变,直接存 final。
 *   可变字段(peerDisplay / online / avatar):运行期会变,所以用 Property。
 *
 *   字段含义:
 *     - username:      自己用户名,登录后不变。
 *     - client:        WebSocket 客户端(ImClient),用于收发消息和监听事件。
 *     - peerDisplay:   顶部右侧"我 ↔ &lt;名字&gt;"里对方的名字;无对方时显示"等待对方"。
 *     - online:        是否在线(连接是否还活着),用于标题里的绿点/红点 emoji。
 *     - avatar:        自己的头像;选头像后会被更新,触发 UI 重建头像。
 */
public final class AppState {

    // 自己用户名,登录后不变;record-like 的访问器 username()。
    private final String username;
    // WebSocket 客户端引用;用于 sendChat / addListener 等。
    private final ImClient client;

    /*
     * StringProperty:可观察的字符串。SimpleStringProperty 是其最简单的实现。
     * 创建时给初始值"等待对方"——登录后还未收到对端时显示。
     */
    private final StringProperty peerDisplay = new SimpleStringProperty("等待对方");
    /*
     * BooleanProperty:可观察的布尔。true = 在线。onClose 事件里会改为 false,
     * ChatHeader 通过 onlineProperty().addListener(...) 自动更新 🟢/🔴 表情。
     */
    private final BooleanProperty online = new SimpleBooleanProperty(true);
    /*
     * ObjectProperty<Image>:可观察的任意对象(这里是 javafx.scene.image.Image)。
     * 选完本地头像后 setAvatar(Image) 会触发监听者重建头像。
     */
    private final ObjectProperty<Image> avatar = new SimpleObjectProperty<>();

    /**
     * 便捷构造器:头像传 null。
     */
    public AppState(String username, ImClient client) {
        this(username, client, null);
    }

    /**
     * 完整构造器。
     * 字段初始化完后,把传入的 avatar 推给 ObjectProperty,
     * 这一步会触发任何已注册的监听者。
     */
    public AppState(String username, ImClient client, Image avatar) {
        this.username = username;
        this.client = client;
        this.avatar.set(avatar);
    }

    /** 用户名(运行期不变)。 */
    public String username() {
        return username;
    }

    /** 对方显示名(当前 peerDisplay 值)。 */
    public String peerName() {
        return peerDisplay.get();
    }

    /** WebSocket 客户端引用。 */
    public ImClient client() {
        return client;
    }

    /** 当前头像(可能为 null,表示还没选)。 */
    public Image avatar() {
        return avatar.get();
    }

    /**
     * 暴露头像的 Property 给外部做绑定/监听。
     * 比如 RoomMemberList 订阅 state.avatarProperty() 来刷新"我"那一行的头像。
     */
    public ObjectProperty<Image> avatarProperty() {
        return avatar;
    }

    /**
     * 换头像时调用。set 内部会 fire 事件给监听者,
     * 所以选完本地头像后 UI 立刻刷新。
     */
    public void setAvatar(Image value) {
        avatar.set(value);
    }

    /** 暴露 peerDisplay 的 Property,供 ChatHeader 的 Bindings.createStringBinding 用。 */
    public StringProperty peerDisplayProperty() {
        return peerDisplay;
    }

    /** 暴露 online 的 Property,供 ChatHeader 显示 🟢/🔴 用。 */
    public BooleanProperty onlineProperty() {
        return online;
    }

    /** 更新对方显示名。set 触发绑定/监听。 */
    public void setPeerDisplay(String value) {
        peerDisplay.set(value);
    }

    /** 更新在线状态。 */
    public void setOnline(boolean value) {
        online.set(value);
    }
}
