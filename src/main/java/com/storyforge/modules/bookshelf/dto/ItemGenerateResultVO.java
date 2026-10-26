package com.storyforge.modules.bookshelf.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * AI 创建物品结果。
 *
 * @param skipped 与已有物品重名、被跳过的名称
 */
@Schema(description = "AI 创建物品结果")
public record ItemGenerateResultVO(
        List<ItemVO> items,
        List<String> skipped,
        String model,
        Long latencyMs) {
}
