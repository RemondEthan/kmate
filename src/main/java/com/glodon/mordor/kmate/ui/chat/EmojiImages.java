package com.glodon.mordor.kmate.ui.chat;

import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 表情图片资源管理。
 *
 * <p>Windows 上 JavaFX 无法渲染彩色 emoji 字体（缺少 Segoe UI Emoji 等），
 * 这里把每个 emoji 替换成 Twemoji 项目的彩色 PNG。
 *
 * <p>对外行为：
 * <ul>
 *   <li>{@link #view(String, double)}：给一个 emoji，返回对应 PNG 的 ImageView（用于按钮）</li>
 *   <li>{@link #flow(String)}：把一段混合文字里的 emoji 全部替换成图片，
 *       文字部分保留为 Text 节点，整体塞进 TextFlow（用于消息气泡里混排）</li>
 * </ul>
 *
 * <p>协议层（ImClient）始终传 Unicode 字符串，只在界面渲染时替换图片，协议不受影响。
 */
public final class EmojiImages {

    /**
     * 表情目录：6×6 = 36 个常用 emoji。
     * 这里同时承担"协议层允许哪些 emoji"的角色（虽然协议没限制，但 UI 只展示这 36 个）。
     */
    static final String[] CATALOG = {
            "😀", "😁", "😂", "🤣", "😊", "😍",
            "😘", "😎", "🤩", "🥳", "🤔", "🙄",
            "😴", "😪", "🤗", "🤭", "🤫", "🤐",
            "👍", "👎", "👏", "🙏", "💪", "🤝",
            "❤️", "💔", "💯", "🔥", "✨", "🎉",
            "☕", "🍕", "🍺", "🎁", "📎", "📁"
    };

    // 按字符长度倒序排：长的 emoji 先匹配（避免 "❤" 被 "❤️" 截一半）
    private static final List<String> LONGEST_FIRST = longestFirst();
    // PNG → 已加载的 Image。ConcurrentHashMap 因为可能多线程访问（背景线程渲染时）
    private static final Map<String, Image> CACHE = new ConcurrentHashMap<>();

    private EmojiImages() {}

    /**
     * 给一个 emoji 字符串，返回大小为 size 的 ImageView。
     * 没找到资源时返回空的 ImageView（不会报错）。
     */
    public static ImageView view(String emoji, double size) {
        ImageView view = new ImageView();
        Image img = image(emoji);
        if (img != null) {
            view.setImage(img);
        }
        view.setFitHeight(size);
        view.setFitWidth(size);
        view.setPreserveRatio(true);  // 保持宽高比
        view.setSmooth(true);         // 高质量缩放
        return view;
    }

    /**
     * 把一段文本里的 emoji 都换成图片节点，文字部分保留 Text 节点，
     * 整体打包成一个 TextFlow。
     *
     * <p>注意 TextFlow 渲染 emoji 是用图片节点（Node），不是字符，所以可以正常显示彩色。
     * 普通文字用 Text 节点可以正常选中和复制。
     */
    public static TextFlow flow(String text) {
        TextFlow flow = new TextFlow();
        if (text == null || text.isEmpty()) {
            return flow;
        }
        int i = 0;
        // buf 累积"不是 emoji 的普通文字"，遇到 emoji 就 flush 一次
        StringBuilder buf = new StringBuilder();
        while (i < text.length()) {
            // 在位置 i 试着匹配一个 emoji；matchAt 找不到就返回 null
            String match = matchAt(text, i);
            if (match != null && image(match) != null) {
                flushText(flow, buf);
                // 把匹配到的 emoji 渲染成图片塞进 TextFlow
                flow.getChildren().add(view(match, 16));
                i += match.length();
            } else {
                buf.append(text.charAt(i));
                i++;
            }
        }
        // 循环结束后把残留的 buf 也 flush 进去
        flushText(flow, buf);
        return flow;
    }

    /**
     * 把 emoji 转成资源文件名（不带后缀）。
     *
     * <p>emoji 转成 hex：用 emoji 的每个 code point 的 hex 用 "-" 连接。
     * 比如 "😀" 是 U+1F600 → "1f600"；"❤️" 是 U+2764 + U+FE0F → "2764-fe0f"。
     *
     * <p>为什么还要 stripVs：同一个 emoji 有时带 U+FE0F（variation selector-16，表示要彩色渲染），
     * 有时不带；我们准备两份资源名，优先查带 VS 的（彩色 PNG），找不到就用纯 code point。
     */
    static String resourceKey(String emoji) {
        if (emoji == null || emoji.isEmpty()) {
            return "";
        }
        String withVs = hexKey(emoji, false);
        if (resourceExists(withVs)) {
            return withVs;
        }
        String noVs = hexKey(emoji, true);
        return resourceExists(noVs) ? noVs : withVs;
    }

    /**
     * 取 PNG Image。先看缓存，缓存没有就 lazy load。
     * true = 后台加载，加载完后自动更新 ImageView
     */
    private static Image image(String emoji) {
        String key = resourceKey(emoji);
        if (key.isEmpty()) {
            return null;
        }
        Image cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        var url = EmojiImages.class.getResource("/emoji/" + key + ".png");
        if (url == null) {
            return null;
        }
        Image loaded = new Image(url.toExternalForm(), true);
        CACHE.put(key, loaded);
        return loaded;
    }

    /**
     * 在 text[index..] 处尝试匹配 CATALOG 里的某个 emoji。
     * 用 LONGEST_FIRST 是为了 "❤️" 优先于 "❤"。
     */
    private static String matchAt(String text, int index) {
        for (String emoji : LONGEST_FIRST) {
            if (text.startsWith(emoji, index)) {
                return emoji;
            }
        }
        return null;
    }

    /**
     * 把累积在 buf 里的普通文字作为 Text 节点塞进 TextFlow，然后清空 buf。
     */
    private static void flushText(TextFlow flow, StringBuilder buf) {
        if (buf.isEmpty()) {
            return;
        }
        flow.getChildren().add(new Text(buf.toString()));
        buf.setLength(0);
    }

    /**
     * emoji → hex 文件名。把每个 code point 转 hex 用 "-" 连接。
     * stripVs = true 时跳过 U+FE0F（variation selector）。
     */
    private static String hexKey(String emoji, boolean stripVs) {
        List<String> parts = new ArrayList<>();
        emoji.codePoints().forEach(cp -> {
            if (stripVs && cp == 0xFE0F) {
                return;
            }
            parts.add(Integer.toHexString(cp));
        });
        return String.join("-", parts);
    }

    /**
     * 资源是否存在：通过 classpath 试探 getResource。
     * 不读文件只查 classpath，开销小。
     */
    private static boolean resourceExists(String key) {
        return EmojiImages.class.getResource("/emoji/" + key + ".png") != null;
    }

    /**
     * 把 CATALOG 按 emoji 字符串长度倒序排序，复制成不可变 List。
     * 用 Comparator.comparingInt(String::length).reversed()：长度长的排前面。
     */
    private static List<String> longestFirst() {
        List<String> list = new ArrayList<>(List.of(CATALOG));
        list.sort(Comparator.comparingInt(String::length).reversed());
        return List.copyOf(list);
    }
}
