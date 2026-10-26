package com.storyforge.modules.bookshelf.dto;

/**
 * 检查过程中产出的一个问题草稿。
 *
 * <p>规则层与 AI 层都产出它，再由 {@code ConsistencyService} 统一落库：
 * 两层走的是同一条「去重 → 截断 → 写表」的收尾，不会各写各的字段。</p>
 *
 * <p>{@code chapterId} 可空：断号、排序异常这类问题属于全书，定位不到某一章。</p>
 */
public record ConsistencyDraft(
        /** RULE / AI */
        String source,
        Long chapterId,
        Integer chapterNo,
        String chapterTitle,
        String issueType,
        String severity,
        String title,
        String detail,
        String evidence,
        String suggestion) {

    /** 同一批次内的去重指纹：同一章、同一类型、同一标题视为同一条。 */
    public String fingerprint() {
        return (source == null ? "" : source)
                + "|" + (chapterNo == null ? "-" : chapterNo)
                + "|" + (issueType == null ? "" : issueType)
                + "|" + (title == null ? "" : title);
    }
}
