package com.storyforge.modules.bookshelf.dto;

import com.storyforge.modules.search.dto.SearchBundleVO;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * AI 生成大纲结果。
 *
 * <p>除章节本身，还会带回「生成阶段顺带初始化出来的档案」：人物、物品、势力，
 * 以及事件池里没有合适事件时新建的事件。</p>
 *
 * @param skipped 与已有章节重名、被跳过的标题
 */
@Schema(description = "AI 生成大纲结果")
public record OutlineGenerateResultVO(
        List<ChapterVO> chapters,
        List<String> skipped,
        String model,
        Long latencyMs,
        @Schema(description = "本次联网检索情况与引用来源") SearchBundleVO search,
        @Schema(description = "生成阶段补建的人物") List<CharacterVO> newCharacters,
        @Schema(description = "生成阶段补建的物品") List<ItemVO> newItems,
        @Schema(description = "生成阶段补建的势力") List<FactionVO> newFactions,
        @Schema(description = "本次新建的事件（事件池无合适事件时）") List<TimelineVO> newEvents) {
}
