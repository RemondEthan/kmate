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
 * Windows 上 JavaFX 无法渲染彩色 emoji 字体，用 Twemoji PNG 画表情。
 * 协议仍传 Unicode，只在界面替换为图片。
 */
public final class EmojiImages {

    static final String[] CATALOG = {
            "😀", "😁", "😂", "🤣", "😊", "😍",
            "😘", "😎", "🤩", "🥳", "🤔", "🙄",
            "😴", "😪", "🤗", "🤭", "🤫", "🤐",
            "👍", "👎", "👏", "🙏", "💪", "🤝",
            "❤️", "💔", "💯", "🔥", "✨", "🎉",
            "☕", "🍕", "🍺", "🎁", "📎", "📁"
    };

    private static final List<String> LONGEST_FIRST = longestFirst();
    private static final Map<String, Image> CACHE = new ConcurrentHashMap<>();

    private EmojiImages() {}

    public static ImageView view(String emoji, double size) {
        ImageView view = new ImageView();
        Image img = image(emoji);
        if (img != null) {
            view.setImage(img);
        }
        view.setFitHeight(size);
        view.setFitWidth(size);
        view.setPreserveRatio(true);
        view.setSmooth(true);
        return view;
    }

    public static TextFlow flow(String text) {
        TextFlow flow = new TextFlow();
        if (text == null || text.isEmpty()) {
            return flow;
        }
        int i = 0;
        StringBuilder buf = new StringBuilder();
        while (i < text.length()) {
            String match = matchAt(text, i);
            if (match != null && image(match) != null) {
                flushText(flow, buf);
                flow.getChildren().add(view(match, 16));
                i += match.length();
            } else {
                buf.append(text.charAt(i));
                i++;
            }
        }
        flushText(flow, buf);
        return flow;
    }

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

    private static String matchAt(String text, int index) {
        for (String emoji : LONGEST_FIRST) {
            if (text.startsWith(emoji, index)) {
                return emoji;
            }
        }
        return null;
    }

    private static void flushText(TextFlow flow, StringBuilder buf) {
        if (buf.isEmpty()) {
            return;
        }
        flow.getChildren().add(new Text(buf.toString()));
        buf.setLength(0);
    }

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

    private static boolean resourceExists(String key) {
        return EmojiImages.class.getResource("/emoji/" + key + ".png") != null;
    }

    private static List<String> longestFirst() {
        List<String> list = new ArrayList<>(List.of(CATALOG));
        list.sort(Comparator.comparingInt(String::length).reversed());
        return List.copyOf(list);
    }
}
