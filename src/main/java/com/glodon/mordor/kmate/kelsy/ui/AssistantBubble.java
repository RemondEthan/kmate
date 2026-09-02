package com.glodon.mordor.kmate.kelsy.ui;

import com.glodon.mordor.kmate.kelsy.model.AssistantMessage;
import com.glodon.mordor.kmate.kelsy.model.MessageBlock;
import com.glodon.mordor.kmate.kelsy.ui.markdown.MarkdownRenderer;
import com.glodon.mordor.kmate.kelsy.ui.markdown.MarkdownView;
import com.glodon.mordor.kmate.model.Sender;
import com.glodon.mordor.kmate.ui.AvatarView;

import javafx.beans.binding.Bindings;
import javafx.beans.value.ObservableValue;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;

/** kelsy 助手气泡：思考块、工具调用、流式/完成后的 Markdown。 */
public class AssistantBubble extends HBox {

    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ObservableValue<? extends Number> maxBubbleWidth;
    private final ObservableValue<Boolean> thinkingVisible;
    private final Image kelsyPhoto;
    private final Consumer<String> onWorkspaceLink;

    public AssistantBubble(AssistantMessage msg, String assistantName,
                           ObservableValue<? extends Number> maxBubbleWidth,
                           ObservableValue<Boolean> thinkingVisible,
                           Image kelsyPhoto,
                           Consumer<String> onWorkspaceLink) {
        super(4);
        this.maxBubbleWidth = maxBubbleWidth;
        this.thinkingVisible = thinkingVisible;
        this.kelsyPhoto = kelsyPhoto;
        this.onWorkspaceLink = onWorkspaceLink == null ? path -> {
        } : onWorkspaceLink;
        setFillHeight(false);
        setPadding(new Insets(2, 4, 2, 4));
        renderSide(msg, assistantName == null || assistantName.isBlank() ? "kelsy" : assistantName);
    }

    private void renderSide(AssistantMessage msg, String name) {
        setAlignment(Pos.TOP_LEFT);

        Label nameLabel = new Label(name);
        nameLabel.getStyleClass().add("bubble-name");

        AvatarView avatar = new AvatarView(name, kelsyPhoto, false, 28);

        Label time = new Label(DATE_TIME.format(msg.timestamp()));
        time.getStyleClass().add("bubble-time");

        VBox body = new VBox(4);
        body.setMinWidth(0);
        fillBody(body, msg);
        msg.streamingProperty().addListener((obs, o, n) -> fillBody(body, msg));
        msg.blocks().addListener((ListChangeListener<MessageBlock>) c -> fillBody(body, msg));
        for (MessageBlock block : msg.blocks()) {
            if (block.kind() == MessageBlock.Kind.TOOL) {
                block.openPathProperty().addListener((obs, o, n) -> fillBody(body, msg));
            }
        }

        VBox col = new VBox(2, nameLabel, body, time);
        col.setMinWidth(0);
        col.setAlignment(Pos.TOP_LEFT);

        HBox row = new HBox(6, avatar, col);
        row.setMinWidth(0);
        row.setAlignment(Pos.TOP_LEFT);
        getChildren().add(row);
    }

    private void fillBody(VBox body, AssistantMessage msg) {
        body.getChildren().clear();
        if (msg.blocks().isEmpty() && msg.streamingProperty().get()) {
            body.getChildren().add(styled(textLabel("…")));
            return;
        }
        for (MessageBlock block : msg.blocks()) {
            if (block.kind() == MessageBlock.Kind.THINKING) {
                if (!block.content().isBlank() || block.streamingProperty().get()) {
                    body.getChildren().add(thinkingBox(block));
                }
                continue;
            }
            if (block.kind() == MessageBlock.Kind.TOOL) {
                ToolCallCard card = new ToolCallCard(
                        block.toolName(),
                        block.openPathProperty().get(),
                        onWorkspaceLink);
                bindBubbleWidth(card);
                body.getChildren().add(card);
                continue;
            }
            boolean streaming = msg.streamingProperty().get() || block.streamingProperty().get();
            if (streaming || msg.sender() == Sender.SYSTEM) {
                Label label = textLabelFor(block);
                body.getChildren().add(styled(label));
            } else {
                body.getChildren().add(styled(markdownOrPlain(block.content())));
            }
        }
    }

    private Region thinkingBox(MessageBlock block) {
        Label title = new Label("思考过程");
        title.getStyleClass().add("thinking-title");

        Label text = new Label();
        text.setWrapText(true);
        text.getStyleClass().add("thinking-body");
        text.textProperty().bind(Bindings.createStringBinding(
                () -> block.content().isEmpty() && block.streamingProperty().get() ? "…" : block.content(),
                block.contentProperty(), block.streamingProperty()));

        VBox box = new VBox(4, title, text);
        box.getStyleClass().add("thinking-block");
        if (thinkingVisible != null) {
            box.visibleProperty().bind(thinkingVisible);
            box.managedProperty().bind(thinkingVisible);
        }
        bindBubbleWidth(box);
        return box;
    }

    private Label textLabelFor(MessageBlock block) {
        Label label = new Label();
        label.setWrapText(true);
        label.textProperty().bind(Bindings.createStringBinding(
                () -> block.content().isEmpty() && block.streamingProperty().get() ? "…" : block.content(),
                block.contentProperty(), block.streamingProperty()));
        bindBubbleWidth(label);
        return label;
    }

    private Label textLabel(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        bindBubbleWidth(label);
        return label;
    }

    private Node markdownOrPlain(String source) {
        try {
            MarkdownView view = new MarkdownView(MarkdownRenderer.parse(source), onWorkspaceLink);
            bindBubbleWidth(view);
            return view;
        } catch (RuntimeException e) {
            return textLabel(source);
        }
    }

    private Region styled(Node node) {
        if (node instanceof Region region) {
            region.getStyleClass().add("bubble-peer");
            bindBubbleWidth(region);
            return region;
        }
        VBox wrap = new VBox(node);
        wrap.getStyleClass().add("bubble-peer");
        bindBubbleWidth(wrap);
        return wrap;
    }

    private void bindBubbleWidth(Region bubble) {
        bubble.setMinWidth(0);
        bubble.maxWidthProperty().bind(Bindings.createDoubleBinding(
                () -> Math.max(120, maxBubbleWidth.getValue().doubleValue()),
                maxBubbleWidth));
    }
}
