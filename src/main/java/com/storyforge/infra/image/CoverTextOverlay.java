package com.storyforge.infra.image;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 封面文字合成：把书名 / 作者名用本地字体渲染到封面底部。
 *
 * <p>为什么不用文生图模型直接画字：模型写汉字几乎必糊、易出同音错别字，且改一版就要重新出图。
 * 这里采用「AI 画背景 + 本地渲染文字」的主流做法——文字清晰、位置与字体可控、换字不重画。</p>
 *
 * <p>字体从系统字体目录取（Windows 的 simhei / 微软雅黑，Linux 容器内置的思源黑体），
 * 找不到中文字体时回退到 Java 逻辑字体，仍保证功能可用。</p>
 */
@Slf4j
@Component
public class CoverTextOverlay {

    /** 书名最大字号，按「最长一行不超过图宽 88%」自动缩小 */
    private static final int TITLE_MAX_SIZE = 72;
    /** 作者名字号系数 */
    private static final float AUTHOR_RATIO = 0.42F;
    /** 文字距底部边距占图高比例 */
    private static final float BOTTOM_MARGIN = 0.055F;
    /** 书名与作者名的行距占图高比例 */
    private static final float LINE_GAP = 0.02F;
    /** 描边粗细系数（相对字号） */
    private static final float STROKE_RATIO = 0.045F;

    private static final String[] CANDIDATE_FONTS = {
            // Windows
            "C:/Windows/Fonts/simhei.ttf",
            "C:/Windows/Fonts/msyhbd.ttc",
            "C:/Windows/Fonts/msyh.ttc",
            "C:/Windows/Fonts/simsun.ttc",
            // Linux 容器常见中文字体
            "/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc",
            "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
            "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc",
            "/usr/share/fonts/truetype/droid/DroidSansFallbackFull.ttf"
    };

    private volatile Font titleFont = null;

    /**
     * 把书名与作者名合成到图片上，返回新的 JPEG 字节。
     *
     * @param imageData 原始图片字节
     * @param title     书名，可空（空则跳过书名）
     * @param author    作者名，可空
     * @return 合成后的 JPEG 字节
     */
    public byte[] overlay(byte[] imageData, String title, String author) {
        String t = hasText(title) ? title.trim() : null;
        String a = hasText(author) ? author.trim() : null;
        if (t == null && a == null) {
            return imageData;
        }
        try {
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(imageData));
            if (source == null) {
                return imageData;
            }
            int width = source.getWidth();
            int height = source.getHeight();

            BufferedImage canvas = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = canvas.createGraphics();
            try {
                graphics.drawImage(source, 0, 0, null);
                applyText(graphics, width, height, t, a);
            } finally {
                graphics.dispose();
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(imageData.length, 8192));
            if (!ImageIO.write(canvas, "jpg", out)) {
                log.warn("[cover] overlay: write jpeg failed");
                return imageData;
            }
            return out.toByteArray();
        } catch (Exception ex) {
            log.warn("[cover] overlay failed, keep original: {}", ex.toString());
            return imageData;
        }
    }

    private void applyText(Graphics2D graphics, int width, int height, String title, String author) {
        Font titleFont = resolveTitleFont();
        int baseTitle = Math.min(TITLE_MAX_SIZE, Math.max(24, (int) (height * 0.085F)));

        // 自动换行：书名过长时折成两行
        List<String> titleLines = wrap(title, width, baseTitle, titleFont, 0.88F);
        int titleSize = fitSize(titleLines, width, baseTitle, titleFont, 0.88F);
        int authorSize = Math.max(16, (int) (titleSize * AUTHOR_RATIO));

        Font tFont = titleFont.deriveFont(Font.BOLD, titleSize);
        Font aFont = titleFont.deriveFont(Font.PLAIN, authorSize);

        int titleLineHeight = (int) (titleSize * 1.22F);
        int authorLineHeight = (int) (authorSize * 1.5F);
        int gap = (int) (height * LINE_GAP);

        int totalHeight = titleLineHeight * titleLines.size() + (author == null ? 0 : authorLineHeight + gap);
        int bottom = (int) (height * (1 - BOTTOM_MARGIN));
        int baseline = bottom - totalHeight + titleLineHeight;

        // 半透明底部衬条，保证浅色图上文字可读
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setColor(new Color(0, 0, 0, 92));
        graphics.fillRect(0, bottom - totalHeight - (int) (height * 0.03F), width,
                totalHeight + (int) (height * 0.06F));

        int y = baseline;
        for (String line : titleLines) {
            drawCenteredText(graphics, line, tFont, width, y, Color.WHITE, new Color(20, 20, 24));
            y += titleLineHeight;
        }
        if (author != null) {
            y += gap;
            // 作者名左侧加一条短横线，视觉上区分书名
            drawCenteredText(graphics, "— " + author + " —", aFont, width, y,
                    new Color(245, 245, 245), new Color(20, 20, 24));
        }
    }

    /** 居中绘制带描边的文字。 */
    private void drawCenteredText(Graphics2D graphics, String text, Font font, int width, int baselineY,
                                  Color fill, Color outline) {
        graphics.setFont(font);
        FontMetrics metrics = graphics.getFontMetrics();
        int textWidth = metrics.stringWidth(text);
        int x = (width - textWidth) / 2;

        graphics.setColor(outline);
        int stroke = Math.max(1, Math.round(font.getSize() * STROKE_RATIO));
        var oldStroke = graphics.getStroke();
        graphics.setStroke(new java.awt.BasicStroke(stroke,
                java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
        var frc = new FontRenderContext(null, true, true);
        var outlineShape = font.createGlyphVector(frc, text).getOutline(x, baselineY);
        graphics.draw(outlineShape);
        graphics.setStroke(oldStroke);

        graphics.setColor(fill);
        graphics.drawString(text, x, baselineY);
    }

    /** 按宽度把书名折成不超过两行；只折到两行，再多就截断加省略号。 */
    private List<String> wrap(String title, int width, int fontSize, Font font, float maxRatio) {
        Font f = font.deriveFont(Font.BOLD, fontSize);
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = probe.createGraphics();
        FontMetrics metrics = g.getFontMetrics(f);
        g.dispose();
        int maxWidth = (int) (width * maxRatio);

        if (metrics.stringWidth(title) <= maxWidth) {
            return List.of(title);
        }
        // 逐字累加，超宽处折行
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < title.length(); i++) {
            char ch = title.charAt(i);
            if (metrics.stringWidth(current.toString() + ch) > maxWidth && current.length() > 0) {
                lines.add(current.toString());
                current = new StringBuilder();
                if (lines.size() == 2) {
                    // 已经两行，剩余截断
                    String rest = title.substring(i);
                    lines.add(truncateToWidth(rest, metrics, maxWidth) + "…");
                    return lines;
                }
            }
            current.append(ch);
        }
        lines.add(current.toString());
        return lines;
    }

    private String truncateToWidth(String text, FontMetrics metrics, int maxWidth) {
        StringBuilder sb = new StringBuilder();
        for (char ch : text.toCharArray()) {
            if (metrics.stringWidth(sb.toString() + ch) > maxWidth) {
                break;
            }
            sb.append(ch);
        }
        return sb.toString();
    }

    /** 保证所有行都不超宽，必要时整体缩字号。 */
    private int fitSize(List<String> lines, int width, int baseSize, Font font, float maxRatio) {
        int size = baseSize;
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = probe.createGraphics();
        while (size > 20) {
            Font f = font.deriveFont(Font.BOLD, size);
            FontMetrics metrics = g.getFontMetrics(f);
            int maxWidth = (int) (width * maxRatio);
            boolean fits = lines.stream().allMatch(line -> metrics.stringWidth(line) <= maxWidth);
            if (fits) {
                break;
            }
            size -= 2;
        }
        g.dispose();
        return size;
    }

    private Font resolveTitleFont() {
        if (titleFont != null) {
            return titleFont;
        }
        synchronized (this) {
            if (titleFont != null) {
                return titleFont;
            }
            for (String path : CANDIDATE_FONTS) {
                File file = new File(path);
                if (file.exists()) {
                    try {
                        titleFont = Font.createFont(Font.TRUETYPE_FONT, file).deriveFont(Font.PLAIN, 64F);
                        log.info("[cover] use cover font: {}", path);
                        return titleFont;
                    } catch (Exception ex) {
                        log.warn("[cover] load font failed {}: {}", path, ex.getMessage());
                    }
                }
            }
            // 回退：Java 逻辑字体（SansSerif），中文可能缺字形，但保证不抛异常
            titleFont = new Font(Font.SANS_SERIF, Font.PLAIN, 64);
            log.warn("[cover] no CJK font found, fallback to SansSerif");
            return titleFont;
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
