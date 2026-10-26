package com.storyforge.modules.bookshelf.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 章节视图。列表接口不回传 {@code content}，避免一次拉整本书正文。
 *
 * <p>{@code participants} 是本章出场人物，{@code events} 是本章要写到的事件；
 * 两者都由 AI 生成大纲时给出，也可在「编辑章节」里手工调整。生成正文时
 * 它们决定注入哪些人物、物品与势力上下文。</p>
 */
public record ChapterVO(
        Long id,
        Integer volumeNo,
        String volumeTitle,
        Integer chapterNo,
        String title,
        String summary,
        String content,
        Integer wordCount,
        String status,
        LocalDateTime updatedAt,
        Long novelId,
        List<EventParticipantVO> participants,
        List<ChapterEventVO> events,
        @Schema(description = "本章大纲关联的总纲 id，可空")
        Long masterOutlineId) {
}
