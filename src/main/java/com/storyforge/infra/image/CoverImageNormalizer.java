package com.storyforge.infra.image;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

/**
 * 封面图片归一化：统一转成 JPEG。
 *
 * <p>各家平台默认出图格式不一致（硅基流动多为 PNG、部分模型给 WebP），而封面路径规则是
 * {@code user_id/novel_id/{uuid}.jpg}，所以这里统一转码：</p>
 * <ul>
 *   <li>JPEG 直接透传，不做无谓的有损二次压缩；</li>
 *   <li>PNG / GIF / BMP 走 ImageIO 转 JPEG（透明区域铺白底）；</li>
 *   <li>WebP 等 ImageIO 不支持的格式保留原字节与真实扩展名，宁可扩名不一致也不让图片打不开。</li>
 * </ul>
 */
@Slf4j
@Component
public class CoverImageNormalizer {

    /** JPEG 质量：0.92 肉眼无损，体积约为 1.0 的一半 */
    private static final float JPEG_QUALITY = 0.92F;

    /**
     * 归一化图片。
     *
     * @param data      原始字节
     * @param mimeType  客户端识别出的类型，可为空
     */
    public NormalizedImage normalize(byte[] data, String mimeType) {
        String detected = (mimeType == null || mimeType.isBlank()) ? detect(data) : mimeType;
        if ("image/jpeg".equals(detected)) {
            return new NormalizedImage(data, "jpg", "image/jpeg", dimension(data));
        }
        try {
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(data));
            if (source == null) {
                return fallback(data, detected);
            }
            int width = source.getWidth();
            int height = source.getHeight();
            // 铺白底：PNG 的透明区域直接写 JPEG 会变成黑块
            BufferedImage rgb = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = rgb.createGraphics();
            try {
                graphics.setColor(Color.WHITE);
                graphics.fillRect(0, 0, width, height);
                graphics.drawImage(source, 0, 0, null);
            } finally {
                graphics.dispose();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(data.length / 2, 8192));
            if (!writeJpeg(rgb, out)) {
                return fallback(data, detected);
            }
            return new NormalizedImage(out.toByteArray(), "jpg", "image/jpeg", new int[]{width, height});
        } catch (Exception ex) {
            log.warn("[cover] convert to jpeg failed, keep original: {}", ex.toString());
            return fallback(data, detected);
        }
    }

    /** 显式指定压缩质量（ImageIO 默认 0.75，封面会明显发糊）。 */
    private boolean writeJpeg(BufferedImage image, ByteArrayOutputStream out) {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY);
            writer.write(null, new IIOImage(image, null, null), param);
            return true;
        } catch (Exception ex) {
            log.warn("[cover] write jpeg failed: {}", ex.toString());
            return false;
        } finally {
            writer.dispose();
        }
    }

    private NormalizedImage fallback(byte[] data, String mimeType) {
        String ext = switch (mimeType == null ? "" : mimeType) {
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            case "image/gif" -> "gif";
            default -> "bin";
        };
        return new NormalizedImage(data, ext, mimeType == null ? "application/octet-stream" : mimeType, null);
    }

    /** 读取宽高，失败返回 null（不影响存储）。 */
    private int[] dimension(byte[] data) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(data));
            return image == null ? null : new int[]{image.getWidth(), image.getHeight()};
        } catch (Exception ex) {
            return null;
        }
    }

    static String detect(byte[] data) {
        if (data == null || data.length < 12) {
            return "application/octet-stream";
        }
        if ((data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8) {
            return "image/jpeg";
        }
        if ((data[0] & 0xFF) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G') {
            return "image/png";
        }
        if (data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P') {
            return "image/webp";
        }
        if (data[0] == 'G' && data[1] == 'I' && data[2] == 'F' && data[3] == '8') {
            return "image/gif";
        }
        return "application/octet-stream";
    }

    /**
     * 归一化结果。
     *
     * @param data     最终字节
     * @param extension 落盘扩展名（不含点）
     * @param mimeType 最终类型
     * @param dimension 宽高，识别不出时为 {@code null}
     */
    public record NormalizedImage(byte[] data, String extension, String mimeType, int[] dimension) {

        public int size() {
            return data == null ? 0 : data.length;
        }

        public Integer width() {
            return dimension == null ? null : dimension[0];
        }

        public Integer height() {
            return dimension == null ? null : dimension[1];
        }
    }
}
