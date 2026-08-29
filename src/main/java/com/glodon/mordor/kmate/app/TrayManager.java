package com.glodon.mordor.kmate.app;

import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.awt.AWTException;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;

/**
 * 系统托盘封装。
 *
 * install(Stage) 立即返回占位对象，真正的 SystemTray.add 在 AWT 线程完成；
 * 系统不支持托盘或加载失败时,tray() / icon() 返回 null,QuitManager 据此跳过清理。
 *
 * 图标路径固定为 /icons/tray.png(平台资源,不挂特性)。
 */
public final class TrayManager {

    private volatile SystemTray tray;
    private volatile TrayIcon icon;
    private volatile Image normalImage;
    private volatile Image alertImage;
    private volatile Dimension slotSize = new Dimension(24, 24);
    private volatile Runnable onQuit = () -> {};

    public static TrayManager install(Stage stage) {
        TrayManager tm = new TrayManager();
        AwtSupport.run(() -> tm.attach(stage));
        return tm;
    }

    private void attach(Stage stage) {
        if (!SystemTray.isSupported()) {
            System.out.println("[Tray] 当前系统不支持托盘图标,跳过");
            return;
        }
        Image image = loadTrayImage("/icons/tray.png");
        if (image == null) {
            return;
        }
        Image alert = loadTrayImage("/icons/tray-alert.png");

        try {
            SystemTray systemTray = SystemTray.getSystemTray();
            // Linux 托盘走 XEmbed：预先缩放到槽位；X11 无法真透明，深色面板压近黑底。
            Dimension slot = resolveSlotSize(systemTray.getTrayIconSize());
            this.slotSize = slot;
            Image scaledNormal = scaleTo(image, slot);
            Image scaledAlert = alert != null ? scaleTo(alert, slot) : scaledNormal;
            TrayIcon trayIcon = new TrayIcon(scaledNormal, "Kmate");
            trayIcon.setImageAutoSize(false);
            trayIcon.addActionListener(e -> FxStageSupport.show(stage));
            systemTray.add(trayIcon);
            trayIcon.setPopupMenu(buildMenu(stage));
            this.normalImage = scaledNormal;
            this.alertImage = scaledAlert;
            this.icon = trayIcon;
            this.tray = systemTray;
            System.out.println("[Tray] 托盘已安装, slot=" + slot.width + "x" + slot.height);
        } catch (AWTException e) {
            System.err.println("[Tray] 无法添加托盘图标: " + e.getMessage());
        }
    }

    public SystemTray tray() { return tray; }
    public TrayIcon icon() { return icon; }

    public void setOnQuit(Runnable onQuit) {
        this.onQuit = onQuit == null ? () -> {} : onQuit;
    }

    void setAlert(boolean alert) {
        AwtSupport.run(() -> {
            if (icon == null || normalImage == null) {
                return;
            }
            icon.setImage(alert && alertImage != null ? alertImage : normalImage);
        });
    }

    void setIconImage(java.awt.Image image) {
        AwtSupport.run(() -> {
            if (icon != null && image != null) {
                // 外部传入的可能是 512 大图，按托盘槽位缩放（保留透明）。
                icon.setImage(scaleTo(image, slotSize));
            }
        });
    }

    private static Image loadTrayImage(String path) {
        try (InputStream is = TrayManager.class.getResourceAsStream(path)) {
            if (is == null) {
                System.err.println("[Tray] 找不到 " + path);
                return null;
            }
            return ImageIO.read(is);
        } catch (IOException e) {
            System.err.println("[Tray] 加载图标失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * AWT 在 Linux 常固定回报 24x24，与面板实际显示不一致时容易裁切。
     * 将边长限制在 [16, 32]，既够清晰又减少被 AppIndicator 裁切的概率。
     */
    private static Dimension resolveSlotSize(Dimension reported) {
        int side = 24;
        if (reported != null) {
            side = Math.max(reported.width, reported.height);
        }
        side = Math.max(16, Math.min(32, side));
        return new Dimension(side, side);
    }

    /**
     * 缩放到托盘槽位；图标内容为格子的 {@link #CONTENT_RATIO}，居中绘制。
     * <p>
     * Linux/X11 托盘（JDK-6453521）不支持真正透明：透明像素会被画成白底。
     * 因此在 Linux 上把透明区域压到与深色面板接近的底色上；其它平台保留 Alpha。
     */
    private static final double CONTENT_RATIO = 0.90;

    private static Image scaleTo(Image src, Dimension slot) {
        int w = Math.max(1, slot.width);
        int h = Math.max(1, slot.height);
        boolean linux = isLinux();
        BufferedImage out = new BufferedImage(w, h,
                linux ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING,
                RenderingHints.VALUE_RENDER_QUALITY);
        if (linux) {
            g.setColor(linuxTrayBackground());
            g.fillRect(0, 0, w, h);
        }
        int cw = Math.max(1, (int) Math.round(w * CONTENT_RATIO));
        int ch = Math.max(1, (int) Math.round(h * CONTENT_RATIO));
        int x = (w - cw) / 2;
        int y = (h - ch) / 2;
        g.drawImage(src, x, y, cw, ch, null);
        g.dispose();
        return out;
    }

    /**
     * 深色主题（如 WhiteSur-Dark）用近黑底，避免透明变白块；
     * 浅色主题用浅灰。可通过 {@code kmate.tray.bg} 覆盖，格式 {@code #RRGGBB}。
     */
    private static Color linuxTrayBackground() {
        String override = System.getProperty("kmate.tray.bg");
        if (override != null && override.matches("#[0-9a-fA-F]{6}")) {
            return Color.decode(override);
        }
        String theme = "";
        String gtkEnv = System.getenv("GTK_THEME");
        if (gtkEnv != null) {
            theme = gtkEnv;
        } else {
            theme = readGsettings("org.gnome.desktop.interface", "gtk-theme");
        }
        String lower = theme.toLowerCase();
        boolean light = lower.contains("light") && !lower.contains("dark");
        return light ? new Color(0xe8, 0xe8, 0xe8) : new Color(0x2c, 0x2c, 0x2c);
    }

    private static String readGsettings(String schema, String key) {
        try {
            Process p = new ProcessBuilder("gsettings", "get", schema, key)
                    .redirectErrorStream(true)
                    .start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            // gsettings 输出形如 'WhiteSur-Dark'
            if (out.length() >= 2 && out.charAt(0) == '\'' && out.charAt(out.length() - 1) == '\'') {
                return out.substring(1, out.length() - 1);
            }
            return out;
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean isLinux() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("linux");
    }

    private PopupMenu buildMenu(Stage stage) {
        PopupMenu menu = new PopupMenu();

        MenuItem openItem = new MenuItem("打开 Kmate");
        openItem.addActionListener(e -> FxStageSupport.show(stage));

        MenuItem hideItem = new MenuItem("隐藏窗口");
        hideItem.addActionListener(e -> FxStageSupport.hide(stage));

        MenuItem quitItem = new MenuItem("退出");
        quitItem.addActionListener(e -> onQuit.run());

        menu.add(openItem);
        menu.add(hideItem);
        menu.addSeparator();
        menu.add(quitItem);
        return menu;
    }

}
