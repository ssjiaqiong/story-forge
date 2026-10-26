package com.storyforge.modules.bookshelf.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 章节分析报告。
 *
 * <p>三项评分口径：</p>
 * <ul>
 *   <li>字数：只统计汉字，与目标字数比较，默认 ±15% 视为达标；</li>
 *   <li>大纲贴合度：0~100，越高越贴合本章大纲与关联事件；</li>
 *   <li>AI 率：0~100，<b>0 分为纯 AI 味</b>，越高越像人写。</li>
 * </ul>
 */
@Schema(description = "章节分析报告")
public record ChapterAnalysisVO(
        Long id,
        Long chapterId,
        Integer chapterNo,
        String chapterTitle,
        Integer wordCount,
        Integer targetWords,
        Integer minWords,
        Integer maxWords,
        Boolean wordPass,
        String wordLabel,
        String wordComment,
        Integer outlineScore,
        String outlineVerdict,
        List<String> outlineHits,
        List<String> outlineMisses,
        List<String> outlineSuggestions,
        Integer aiScore,
        String aiVerdict,
        List<AiSignal> aiSignals,
        List<String> aiExamples,
        List<LocalSignal> localSignals,
        List<NewCharacter> newCharacters,
        List<ChangeRecord> changes,
        List<EventChange> events,
        List<String> warnings,
        String model,
        Long latencyMs,
        LocalDateTime createdAt) {

    /** 模型给出的 AI 味信号 */
    @Schema(description = "AI 味信号")
    public record AiSignal(String name, String detail) {
    }

    /** 本地统计信号，供作者对照，模型评分不得与其矛盾 */
    @Schema(description = "本地统计信号")
    public record LocalSignal(String name, String value, String level) {
    }

    /** 本章新增的人物 */
    @Schema(description = "新增人物")
    public record NewCharacter(Long id, String name, String identity, String rolePos, String roleLabel, String reason) {
    }

    /** 一条要素变更履历 */
    @Schema(description = "变更履历")
    public record ChangeRecord(Long id,
                               String entityType,
                               String entityTypeLabel,
                               Long entityId,
                               String entityName,
                               String fieldKey,
                               String fieldLabel,
                               String beforeState,
                               String afterState,
                               String reason,
                               String evidence) {
    }

    /** 事件状态变化 */
    @Schema(description = "事件状态变化")
    public record EventChange(Long eventId,
                              String title,
                              String fromStatus,
                              String toStatus,
                              String fromLabel,
                              String toLabel,
                              String reason,
                              boolean changed) {
    }
}
