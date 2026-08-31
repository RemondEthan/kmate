package com.glodon.mordor.kmate.service;

import javafx.scene.image.Image;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.Iterator;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 本地头像：选图后拷到 ~/.kmate/avatars/self.*，路径可落 Preferences。
 *
 * 四个能力：
 *   - chooseAndStore: 弹出 FileChooser 让用户选图，复制到 ~/.kmate/avatars/self.<ext>，返回绝对路径字符串。
 *   - thumbnailBase64: 把磁盘图缩放成 64×64 JPEG，再 Base64 编码（用于加密后通过 avatar 帧发给对端）。
 *   - fromPngBytes:    把从 WebSocket 收到的 PNG 字节转成 JavaFX Image（用于显示对方头像）。
 *   - load:            从磁盘路径读图转成 JavaFX Image（用于显示自己头像）。
 *
 * 文件命名约定：永远叫 self.<ext>（ext 跟随用户选的原文件扩展名），
 * 复制而非移动——保留用户的源文件不被吞掉。
 */
public final class AvatarService {

    // FileChooser 允许的文件类型白名单。
    private static final Set<String> EXTS = Set.of("png", "jpg", "jpeg", "gif", "webp");

    // 工具类私有构造器。
    private AvatarService() {}

    /**
     * 弹出文件选择对话框，让用户选一张图。
     * 选完拷贝到 ~/.kmate/avatars/self.<ext>，返回绝对路径字符串。
     *
     * FileChooser 是 JavaFX 提供的模态文件对话框：
     *   showOpenDialog(owner) 阻塞到用户选完/取消；返回 File 或 null。
     *   getExtensionFilters().add(...) 设置允许的文件类型过滤器。
     *
     * @param owner 父窗口（用作模态定位），可以为 null
     * @return 选完图 → 拷贝后的绝对路径；取消或失败 → Optional.empty()
     */
    public static Optional<String> chooseAndStore(Window owner) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择头像");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("图片", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.webp"));
        File picked = chooser.showOpenDialog(owner);
        if (picked == null) {
            return Optional.empty();
        }
        return copyLocal(picked).map(Path::toAbsolutePath).map(Path::toString);
    }

    /**
     * 把磁盘图缩放成 64×64 JPEG 再 Base64 编码。
     * 用于 ImClient.setAvatarPlaintext，传输给对端显示。
     *
     * 实现细节：
     *   - BufferedImage：Java AWT 的内存图像（和 JavaFX 的 Image 是两套）。
     *   - Graphics2D.drawImage(...)：用双线性插值缩放。
     *   - JPEG 而不是 PNG：相同视觉质量下体积更小；服务端有 32KB 丢帧阈值，PNG 太容易超。
     *   - 0.72 压缩质量：肉眼接近无损但体积可控。
     */
    public static Optional<String> thumbnailBase64(String path) {
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }
        try {
            BufferedImage src = ImageIO.read(Path.of(path).toFile());
            if (src == null) {
                return Optional.empty();
            }
            // JPEG RGB：96×96 ARGB PNG 加密后常超过 kserver 的 32KB 丢弃线
            int size = 64;
            BufferedImage dst = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = dst.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, size, size);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            double scale = Math.max(size / (double) src.getWidth(), size / (double) src.getHeight());
            int w = ( int) Math.round(src.getWidth() * scale);
            int h = (int) Math.round(src.getHeight() * scale);
            g.drawImage(src, (size - w) / 2, (size - h) / 2, w, h, null);
            g.dispose();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!writeJpeg(dst, out)) {
                return Optional.empty();
            }
            return Optional.of(Base64.getEncoder().encodeToString(out.toByteArray()));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * 把 JPEG 字节写到 ByteArrayOutputStream。
     * 用 ImageIO 找 JPEG writer、显式设置压缩质量 0.72。
     * 失败返回 false（理论上不会发生，除非 JVM 缺 JPEG 编解码器）。
     */
    private static boolean writeJpeg(BufferedImage img, ByteArrayOutputStream out) {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            return false;
        }
        ImageWriter writer = writers.next();
        try {
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(0.72f);
            }
            writer.setOutput(new MemoryCacheImageOutputStream(out));
            writer.write(null, new IIOImage(img, null, null), param);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            writer.dispose();
        }
    }

    /**
     * 把从 WebSocket 收到的 PNG 字节转成 JavaFX Image（96×96 缩略图）。
     * 失败（字节不是合法 PNG）返回 Optional.empty()。
     */
    public static Optional<Image> fromPngBytes(byte[] png) {
        if (png == null || png.length == 0) {
            return Optional.empty();
        }
        Image image = new Image(new ByteArrayInputStream(png), 96, 96, true, true);
        if (image.isError()) {
            return Optional.empty();
        }
        return Optional.of(image);
    }

    /**
     * 从磁盘路径加载 JavaFX Image。
     * 失败（路径为空、文件不存在、不是合法图片）返回 Optional.empty()。
     */
    public static Optional<Image> load(String path) {
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }
        Path file = Path.of(path);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        // 用 file.toUri() 而不是 file.toString()，避免 Windows 路径里的反斜杠/冒号出问题。
        Image image = new Image(file.toUri().toString(), 96, 96, true, true, false);
        if (image.isError()) {
            return Optional.empty();
        }
        return Optional.of(image);
    }

    /**
     * 把用户选的文件拷到 ~/.kmate/avatars/self.<ext>。
     * 扩展名不在白名单返回 empty；IO 失败也返回 empty。
     */
    static Optional<Path> copyLocal(File src) {
        String ext = extension(src.getName());
        if (!EXTS.contains(ext)) {
            return Optional.empty();
        }
        try {
            Path dir = Path.of(System.getProperty("user.home"), ".kmate", "avatars");
            Files.createDirectories(dir);
            Path dest = dir.resolve("self." + ext);
            // REPLACE_EXISTING：覆盖旧头像。
            Files.copy(src.toPath(), dest, StandardCopyOption.REPLACE_EXISTING);
            return Optional.of(dest);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** 取文件名后缀（不含点），全部小写。失败返回空串。 */
    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
