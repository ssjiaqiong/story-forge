package com.storyforge.modules.bookshelf.dto;

import com.storyforge.modules.search.dto.SearchBundleVO;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * AI 生成事件结果。
 *
 * @param skipped 被跳过的标题（与已有事件重名）
 */
@Schema(description = "AI 生成事件结果")
public record EventGenerateResultVO(
        List<TimelineVO> events,
        List<CharacterVO> createdCharacters,
        List<String> skipped,
        String model,
        Long latencyMs,
        @Schema(description = "本次联网检索情况与引用来源") SearchBundleVO search) {
}
