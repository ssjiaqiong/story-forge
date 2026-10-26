package com.storyforge.infra.text;

import com.storyforge.common.util.WordCounts;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 上传小说文本的解析工具：解码 → 清洗 → 按章节切段。
 *
 * <p>拆书要「读完整本」，但模型上下文装不下几十上百万字，所以先在本机把全文切成若干段
 * （每段目标 9000 汉字，单次最多 12 段，超出则头/中/尾均匀抽样），再逐段交给 AI 摘要。
 * 这样既能覆盖主线，又能把调用次数与成本控制在可预期范围。</p>
 */
public final class NovelTextParser {

    /** 单段目标汉字数。 */
    public static final int SEGMENT_CHARS = 9000;
    /** 单次拆书最多通读的段数。 */
    public static final int MAX_SEGMENTS = 12;

    /** 章节标题行：第 12 章 / 第一百零三节 / 第三章 回 / 第一卷 等。 */
    private static final Pattern CHAPTER_LINE = Pattern.compile(
            "^\\s*(?:第\\s*[0-9一二三四五六七八九十百千万零〇两]{1,10}\\s*[章节回卷集部篇幕]"
                    + "|(?:Chapter|CHAPTER|chapter)\\s*\\d{1,4}"
                    + "|卷\\s*[0-9一二三四五六七八九十百千万零〇两]{1,10})"
                    + "[^\\n]{0,60}$");

    /** 盗版站广告行，直接丢弃。 */
    private static final Pattern AD_LINE = Pattern.compile(
            ".*(?:请记住本站|本站网址|最新章节|无弹窗|手机版阅读|加入书签|投推荐票|www\\.|http://|https://|"
                    + "笔趣阁|顶点小说|69书吧|uu看书|天才一秒记住).*",
            Pattern.CASE_INSENSITIVE);

    private NovelTextParser() {
    }

    /**
     * 字节解码：优先 UTF-8（严格模式），失败回退 GB18030（兼容 GBK / GB2312）。
     */
    public static String decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        int offset = 0;
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            offset = 3;
        }
        String utf8 = strictDecode(bytes, offset, StandardCharsets.UTF_8);
        if (utf8 != null) {
            return utf8;
        }
        String gb = strictDecode(bytes, offset, Charset.forName("GB18030"));
        return gb != null ? gb : new String(bytes, offset, bytes.length - offset, StandardCharsets.UTF_8);
    }

    private static String strictDecode(byte[] bytes, int offset, Charset charset) {
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            CharBuffer buffer = decoder.decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset));
            return buffer.toString();
        } catch (CharacterCodingException ex) {
            return null;
        }
    }

    /** 清洗：统一换行、去零宽字符、去广告行、压缩连续空行。 */
    public static String clean(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String text = raw.replace("\r\n", "\n").replace('\r', '\n')
                .replace("\u0000", "")
                .replace("\uFEFF", "")
                .replaceAll("[\\u200B-\\u200F\\u202A-\\u202E]", "");
        StringBuilder builder = new StringBuilder(text.length());
        int blankRun = 0;
        for (String line : text.split("\n", -1)) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                blankRun++;
                if (blankRun <= 1) {
                    builder.append('\n');
                }
                continue;
            }
            blankRun = 0;
            if (AD_LINE.matcher(trimmed).matches() && trimmed.length() < 120) {
                continue;
            }
            builder.append(trimmed).append('\n');
        }
        return builder.toString().trim();
    }

    /** 解析全文：清洗、统计汉字、识别章节、切段。 */
    public static Digest parse(String raw) {
        String text = clean(raw);
        int chars = WordCounts.countChinese(text);
        List<String> blocks = splitBlocks(text);
        int chapterCount = 0;
        for (String block : blocks) {
            if (isChapterTitle(firstLine(block))) {
                chapterCount++;
            }
        }
        List<Segment> segments = buildSegments(blocks, MAX_SEGMENTS);
        boolean sampled = segments.stream().anyMatch(Segment::sampled);
        return new Digest(text, chars, chapterCount, segments, sampled);
    }

    public static boolean isChapterTitle(String line) {
        return line != null && !line.isBlank() && CHAPTER_LINE.matcher(line.strip()).matches();
    }

    /**
     * 先按章节切块；识别不到章节时按空行/句末切成不超过 2000 字的块。
     */
    private static List<String> splitBlocks(String text) {
        List<String> blocks = new ArrayList<>();
        if (text.isBlank()) {
            return blocks;
        }
        StringBuilder current = new StringBuilder();
        int chapterBlocks = 0;
        for (String line : text.split("\n")) {
            if (isChapterTitle(line) && current.length() > 0) {
                blocks.add(current.toString().strip());
                current.setLength(0);
                chapterBlocks++;
            }
            current.append(line).append('\n');
        }
        if (current.length() > 0) {
            blocks.add(current.toString().strip());
        }
        if (chapterBlocks >= 2) {
            return blocks;
        }
        // 没有章节标记：按长度硬切，尽量在段落边界断开
        blocks.clear();
        StringBuilder chunk = new StringBuilder();
        for (String paragraph : text.split("\n")) {
            if (chunk.length() + paragraph.length() > 2000 && chunk.length() > 0) {
                blocks.add(chunk.toString().strip());
                chunk.setLength(0);
            }
            chunk.append(paragraph).append('\n');
        }
        if (chunk.length() > 0) {
            blocks.add(chunk.toString().strip());
        }
        return blocks;
    }

    /** 把块合并成目标长度的段；段数超限时头/中/尾均匀抽样。 */
    private static List<Segment> buildSegments(List<String> blocks, int maxSegments) {
        List<String> merged = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String block : blocks) {
            if (current.length() + block.length() > SEGMENT_CHARS && current.length() > 0) {
                merged.add(current.toString().strip());
                current.setLength(0);
            }
            // 单块本身超长（比如整本没有章节），硬截断，避免单段无限增长
            if (block.length() > SEGMENT_CHARS * 2) {
                if (current.length() > 0) {
                    merged.add(current.toString().strip());
                    current.setLength(0);
                }
                for (int start = 0; start < block.length(); start += SEGMENT_CHARS) {
                    merged.add(block.substring(start, Math.min(block.length(), start + SEGMENT_CHARS)));
                }
                continue;
            }
            current.append(block).append('\n');
        }
        if (current.length() > 0) {
            merged.add(current.toString().strip());
        }

        List<Integer> picked = new ArrayList<>();
        boolean sampled = merged.size() > maxSegments;
        if (!sampled) {
            for (int i = 0; i < merged.size(); i++) {
                picked.add(i);
            }
        } else {
            int head = Math.max(1, maxSegments / 3);
            int tail = Math.max(1, maxSegments / 3);
            int middle = maxSegments - head - tail;
            for (int i = 0; i < head; i++) {
                picked.add(i);
            }
            for (int i = 1; i <= middle; i++) {
                int index = (int) Math.round((double) i * merged.size() / (middle + 1));
                index = Math.min(merged.size() - 2, Math.max(head, index));
                if (!picked.contains(index)) {
                    picked.add(index);
                }
            }
            for (int i = merged.size() - tail; i < merged.size(); i++) {
                if (!picked.contains(i)) {
                    picked.add(i);
                }
            }
            picked.sort(Integer::compareTo);
        }

        List<Segment> segments = new ArrayList<>();
        int total = picked.size();
        for (int i = 0; i < total; i++) {
            int index = picked.get(i);
            String body = merged.get(index);
            String title = firstLine(body);
            segments.add(new Segment(i + 1, total, abbreviate(title, 60), body, WordCounts.countChinese(body), sampled));
        }
        return segments;
    }

    private static String firstLine(String text) {
        if (text == null) {
            return "";
        }
        int index = text.indexOf('\n');
        return index < 0 ? text.strip() : text.substring(0, index).strip();
    }

    private static String abbreviate(String value, int max) {
        if (value == null) {
            return "";
        }
        String text = value.strip();
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    /**
     * 解析结果。
     *
     * @param text     清洗后的全文
     * @param chars    全文汉字数
     * @param chapters 识别出的章节数（0 表示未识别到章节标记）
     * @param segments 待通读的段落
     * @param sampled  是否做了抽样（原文段数超过上限）
     */
    public record Digest(String text, int chars, int chapters, List<Segment> segments, boolean sampled) {
    }

    /**
     * 通读段。
     *
     * @param index   序号，从 1 开始
     * @param total   总段数
     * @param title   段首标题（章节名或首行）
     * @param text    段正文
     * @param chars   段汉字数
     * @param sampled 是否属于抽样段落
     */
    public record Segment(int index, int total, String title, String text, int chars, boolean sampled) {
    }
}
