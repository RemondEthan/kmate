package com.glodon.mordor.kmate.kelsy.ui.knowledge;

import com.glodon.mordor.kmate.kelsy.service.KnowledgeStore;
import com.glodon.mordor.kmate.kelsy.ui.markdown.MarkdownRenderer;
import com.glodon.mordor.kmate.kelsy.ui.markdown.MarkdownView;

import javafx.beans.property.BooleanProperty;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.BorderPane;

/** 右侧知识库：目录树 + Markdown 预览。 */
public final class KnowledgePane extends BorderPane {

    private final KnowledgeStore store;
    private final BooleanProperty memoryWarn;
    private final TreeView<KnowledgeStore.Entry> tree = new TreeView<>();
    private final ScrollPane host = new ScrollPane();
    private final SplitPane split = new SplitPane();
    private String currentPath;

    public KnowledgePane(KnowledgeStore store, BooleanProperty memoryWarn) {
        this.store = store;
        this.memoryWarn = memoryWarn;
        getStyleClass().add("knowledge-pane");
        tree.setShowRoot(false);
        tree.setCellFactory(tv -> new TreeCell<>() {
            @Override
            protected void updateItem(KnowledgeStore.Entry item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.label());
            }
        });
        tree.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> {
            if (n != null && n.getValue() != null && !n.getValue().directory()) {
                open(n.getValue().relativePath());
            }
        });
        tree.getStyleClass().add("knowledge-tree");
        tree.setMinWidth(0);
        tree.setMinHeight(96);
        tree.setPrefHeight(1);
        tree.setMaxHeight(Double.MAX_VALUE);
        host.setFitToWidth(true);
        host.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        host.setVbarPolicy(ScrollPane.ScrollBarPolicy.ALWAYS);
        host.getStyleClass().add("knowledge-host");
        host.setMinWidth(0);
        host.setMinHeight(0);
        host.setPrefHeight(1);
        host.setMaxHeight(Double.MAX_VALUE);
        split.getItems().setAll(tree, host);
        split.setOrientation(javafx.geometry.Orientation.VERTICAL);
        split.setDividerPositions(0.38);
        setCenter(split);
        refresh();
        String initial = store.defaultPath();
        if (initial != null) {
            open(initial);
        } else {
            showEmpty();
        }
    }

    public void refresh() {
        String keep = currentPath;
        TreeItem<KnowledgeStore.Entry> root = new TreeItem<>();
        for (KnowledgeStore.Entry entry : store.list()) {
            root.getChildren().add(toItem(entry));
        }
        tree.setRoot(root);
        expand(root);
        memoryWarn.set(store.memoryBytes() > KnowledgeStore.MEMORY_WARN_BYTES);
        if (keep != null) {
            open(keep);
        }
    }

    public void open(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            showEmpty();
            return;
        }
        currentPath = relativePath;
        switch (store.read(relativePath)) {
            case KnowledgeStore.Read.Ok ok -> {
                try {
                    host.setContent(
                            new MarkdownView(MarkdownRenderer.parse(ok.markdown()), this::open));
                } catch (RuntimeException e) {
                    host.setContent(new Label("Markdown 无法解析，已显示原文\n\n" + ok.markdown()));
                }
            }
            case KnowledgeStore.Read.Missing ignored ->
                    host.setContent(new Label("文件不存在"));
            case KnowledgeStore.Read.TooLarge ignored ->
                    host.setContent(new Label("文件过大，未渲染"));
            case KnowledgeStore.Read.Rejected r ->
                    host.setContent(new Label(r.reason()));
        }
        split.setDividerPositions(0.38);
    }

    private void showEmpty() {
        currentPath = null;
        host.setContent(new Label("还没有知识。说一件值得记住的事，或输入 /note"));
        split.setDividerPositions(0.38);
    }

    private static TreeItem<KnowledgeStore.Entry> toItem(KnowledgeStore.Entry entry) {
        TreeItem<KnowledgeStore.Entry> item = new TreeItem<>(entry) {
            @Override
            public String toString() {
                return getValue() == null ? "" : getValue().label();
            }
        };
        for (KnowledgeStore.Entry child : entry.children()) {
            item.getChildren().add(toItem(child));
        }
        return item;
    }

    private static void expand(TreeItem<?> item) {
        item.setExpanded(true);
        for (TreeItem<?> child : item.getChildren()) {
            expand(child);
        }
    }
}
