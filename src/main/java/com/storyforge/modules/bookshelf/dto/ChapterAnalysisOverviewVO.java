package com.storyforge.modules.bookshelf.dto;

import java.time.LocalDateTime;

/**
 * 分析面板的一行：一章 + 它最近一次分析的结果。
 *
 * <p>分析必须按章节顺序进行，所以每行都要给出当前是否可分析、以及被哪一章挡住，
 * 前端据此把「分析」按钮置灰并显示原因。</p>
 *
 * <p>{@code status} 取值：</p>
 * <ul>
 *   <li>{@code ANALYZED} 已分析（可重跑）</li>
 *   <li>{@code READY} 前面的章节都已分析，可以开始</li>
 *   <li>{@code LOCKED} 前面的章节还没分析完，暂时锁定</li>
 *   <li>{@code EMPTY} 本章没有正文，无法分析，也会挡住后面的章节</li>
 * </ul>
 */
public record ChapterAnalysisOverviewVO(
        Long chapterId,
        Integer volumeNo,
        String volumeTitle,
        Integer chapterNo,
        String title,
        Integer wordCount,
        String status,
        String statusLabel,
        boolean analyzable,
        String blockReason,
        Long analysisId,
        Integer outlineScore,
        Integer aiScore,
        Boolean wordPass,
        String wordLabel,
        Integer targetWords,
        Integer analysisCount,
        LocalDateTime analyzedAt) {
}
