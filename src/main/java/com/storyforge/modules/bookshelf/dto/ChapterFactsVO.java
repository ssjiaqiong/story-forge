package com.storyforge.modules.bookshelf.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 章节事实层：这一章**实际发生了什么**，以及它留下的线头。
 *
 * <p>{@code summary} 是计划（要写什么），{@code factSummary} 是事实（写了什么）。
 * 作者改后者时 {@code factSource} 会变成 {@code MANUAL}，之后重新分析不会覆盖——
 * 事实层是下一章写正文的依据，必须以作者的判断为准。</p>
 */
@Schema(description = "章节事实层")
public record ChapterFactsVO(
        Long chapterId,
        Integer chapterNo,
        String chapterTitle,
        @Schema(description = "本章实际发生的事实摘要；为空表示还没分析或分析没产出")
        String factSummary,
        @Schema(description = "AI / MANUAL / BACKFILL；为空表示还没产出")
        String factSource,
        @Schema(description = "本章新埋的伏笔")
        List<ForeshadowVO> planted,
        @Schema(description = "本章回收的伏笔")
        List<ForeshadowVO> paid,
        @Schema(description = "本章有状态变化的角色")
        List<CharacterStateVO> states) {

    /** 角色在本章结束时的状态 */
    @Schema(description = "角色状态")
    public record CharacterStateVO(
            Long characterId,
            String characterName,
            Integer chapterNo,
            String location,
            String condition,
            String mood,
            @Schema(description = "本章新得知的信息")
            List<String> knowsAdded,
            @Schema(description = "本章被隐瞒或误信的信息")
            List<String> knowsHidden) {
    }
}
