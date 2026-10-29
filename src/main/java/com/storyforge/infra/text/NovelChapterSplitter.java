package com.storyforge.infra.text;

import com.storyforge.common.util.WordCounts;

import java.util.ArrayList;
import java.util.List;

/**
 * 整本导入的切章器：把清洗后的全文切成「卷 + 章 + 正文」。
 *
 * <p>与拆书的切段（{@link NovelTextParser}）目标不同：拆书允许抽样，只要读到主线；
 * 导入<b>不允许丢字</b>，每一行正文都必须落进某一章的 {@code content}。</p>
 *
 * <p>识别顺序：先按「第 X 卷」划卷，再按「第 X 章 / Chapter N / 第 X 节」划章；
 * 一个章标记都识别不到时（整本一坨或分章格式不规范），按 2000 字在段落边界硬切并补「第 N 章」标题，
 * 保证「导进来就能用」，而不是直接失败。</p>
 */
public final class NovelChapterSplitter {

    /** 单本最多导入的章节数，超过视为异常文件（防止一次插入几十万行）。 */
    public static final int MAX_CHAPTERS = 3000;
    /** 识别不到章节时的兜底切块字数。 */
    public static final int FALLBACK_CHUNK = 2000;
    /** 章标题最大长度。 */
    private static final int MAX_TITLE = 100;
    /** 前言被当成独立一章的最小汉字数，太短就并进第 1 章之前的说明里丢弃。 */
    private static final int PREFACE_MIN_CHARS = 100;

    private NovelChapterSplitter() {
    }

    /**
     * 切章。
     *
     * @param raw 上传的原始字节解码后的文本
     */
    public static Result split(String raw) {
        String text = NovelTextParser.clean(raw);
        List<Draft> drafts = new ArrayList<>();
        if (text.isBlank()) {
            return new Result(drafts, 0, 0, "");
        }

        String preface = "";
        int volumeNo = 1;
        String volumeTitle = null;
        int volumeCount = 1;
        int chapterNo = 0;
        int chapterInVolume = 0;
        String currentTitle = null;
        StringBuilder body = new StringBuilder();
        StringBuilder head = new StringBuilder();
        boolean namedVolumeSeen = false;

        for (String line : text.split("\n", -1)) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                if (currentTitle != null && body.length() > 0) {
                    body.append('\n');
                } else if (currentTitle == null) {
                    head.append('\n');
                }
                continue;
            }
            if (isVolumeTitle(trimmed)) {
                // 卷标题：先把在手的一章结掉，再换卷
                if (currentTitle != null) {
                    drafts.add(new Draft(volumeNo, volumeTitle, chapterNo, chapterInVolume,
                            currentTitle, body.toString().strip()));
                    currentTitle = null;
                    body.setLength(0);
                }
                if (!namedVolumeSeen && drafts.isEmpty()) {
                    // 开头的「第一卷 xxx」只是给第 1 卷命名，不增号
                    volumeTitle = normalize(trimmed);
                    namedVolumeSeen = true;
                } else {
                    volumeNo++;
                    volumeCount = Math.max(volumeCount, volumeNo);
                    volumeTitle = normalize(trimmed);
                    chapterInVolume = 0;
                }
                continue;
            }
            if (NovelTextParser.isChapterTitle(trimmed)) {
                if (currentTitle != null) {
                    drafts.add(new Draft(volumeNo, volumeTitle, chapterNo, chapterInVolume,
                            currentTitle, body.toString().strip()));
                }
                chapterNo++;
                chapterInVolume++;
                currentTitle = normalize(trimmed);
                body.setLength(0);
                continue;
            }
            if (currentTitle == null) {
                head.append(trimmed).append('\n');
            } else {
                body.append(trimmed).append('\n');
            }
        }
        if (currentTitle != null) {
            drafts.add(new Draft(volumeNo, volumeTitle, chapterNo, chapterInVolume,
                    currentTitle, body.toString().strip()));
        }

        String headText = head.toString().strip();
        if (WordCounts.countChinese(headText) >= PREFACE_MIN_CHARS) {
            preface = headText;
        }

        // 一章都没识别出来：按字数硬切，让用户至少能编辑、能续写
        boolean hasPrefaceChapter = false;
        if (drafts.size() < 2) {
            drafts = hardSplit(preface.isEmpty() ? text : preface + "\n" + text);
            preface = "";
            volumeNo = 1;
            volumeCount = 1;
        } else if (!preface.isEmpty()) {
            // 有前言：单独成「序章」，排在最前（章号 0，不影响后续编号）
            List<Draft> withPreface = new ArrayList<>(drafts.size() + 1);
            withPreface.add(new Draft(1, drafts.get(0).volumeTitle(), 0, 0, "序章", preface));
            withPreface.addAll(drafts);
            drafts = withPreface;
            hasPrefaceChapter = true;
        }

        if (drafts.size() > MAX_CHAPTERS) {
            throw new TooManyChaptersException(drafts.size());
        }

        // 章号重排为连续递增（有序章时从 0 起），sortNo 由调用方按列表顺序生成
        List<Draft> normalized = new ArrayList<>(drafts.size());
        int seq = hasPrefaceChapter ? 0 : 1;
        for (Draft draft : drafts) {
            normalized.add(new Draft(draft.volumeNo(), draft.volumeTitle(), seq++, draft.chapterInVolume(),
                    draft.title(), draft.content()));
        }
        return new Result(normalized, volumeCount, WordCounts.countChinese(text), headText);
    }

    /** 整本一坨时的兜底切分：按段落边界切到 2000 字以内。 */
    private static List<Draft> hardSplit(String text) {
        List<Draft> drafts = new ArrayList<>();
        StringBuilder chunk = new StringBuilder();
        int seq = 0;
        for (String paragraph : text.split("\n")) {
            String line = paragraph.strip();
            if (line.isEmpty()) {
                continue;
            }
            if (chunk.length() + line.length() > FALLBACK_CHUNK && chunk.length() > 0) {
                drafts.add(new Draft(1, null, seq, seq + 1, "第 " + (seq + 1) + " 章", chunk.toString().strip()));
                seq++;
                chunk.setLength(0);
            }
            chunk.append(line).append('\n');
        }
        if (chunk.length() > 0) {
            drafts.add(new Draft(1, null, seq, seq + 1, "第 " + (seq + 1) + " 章", chunk.toString().strip()));
        }
        return drafts;
    }

    /** 卷标题：含「卷」且不含「章 / 节 / 回」。 */
    private static boolean isVolumeTitle(String line) {
        if (line == null || line.isBlank()) {
            return false;
        }
        String compact = line.replaceAll("\\s+", "");
        return compact.contains("卷")
                && !compact.contains("章") && !compact.contains("节") && !compact.contains("回")
                && compact.length() <= 40;
    }

    private static String normalize(String line) {
        String title = line.strip().replaceAll("\\s+", " ");
        return title.length() <= MAX_TITLE ? title : title.substring(0, MAX_TITLE);
    }

    /**
     * 切章结果。
     *
     * @param chapters    章节列表，按原文顺序
     * @param volumeCount 卷数
     * @param chars       全文汉字数
     * @param preface     被当作「序章」收进去的前言原文（已消费，仅供日志）
     */
    public record Result(List<Draft> chapters, int volumeCount, int chars, String preface) {
    }

    /**
     * 单章。
     *
     * @param volumeNo        卷号，从 1 起
     * @param volumeTitle     卷名，可空
     * @param chapterNo       全局连续章号，从 0 起（0 表示序章）
     * @param chapterInVolume 卷内章号，从 1 起
     * @param title           章标题
     * @param content         正文（原文，未加工）
     */
    public record Draft(int volumeNo, String volumeTitle, int chapterNo, int chapterInVolume,
                        String title, String content) {

        public int chars() {
            return WordCounts.countChinese(content);
        }
    }

    /** 章节数超过上限。 */
    public static class TooManyChaptersException extends RuntimeException {
        public TooManyChaptersException(int chapters) {
            super("识别出 " + chapters + " 章，超过单本上限 " + MAX_CHAPTERS + " 章；请先拆分文件再导入");
        }
    }
}
