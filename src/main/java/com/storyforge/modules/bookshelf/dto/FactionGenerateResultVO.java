package com.storyforge.modules.bookshelf.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * AI 生成势力结果。
 *
 * @param createdCount 成功创建的势力数
 * @param memberCount  关联成功的成员总数
 * @param skipped      因重名等原因跳过的势力名称
 * @param model        使用的模型
 * @param latencyMs    生成耗时（毫秒）
 */
@Schema(description = "AI 生成势力结果")
public record FactionGenerateResultVO(
        int createdCount,
        int memberCount,
        List<String> skipped,
        String model,
        long latencyMs) {
}
