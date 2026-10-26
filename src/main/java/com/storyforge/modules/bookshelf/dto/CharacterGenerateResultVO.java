package com.storyforge.modules.bookshelf.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * AI 创建角色结果：本次新建的角色与关系。
 *
 * @param skipped  被跳过的名字（重名等），供前端提示
 */
@Schema(description = "AI 创建角色结果")
public record CharacterGenerateResultVO(
        List<CharacterVO> characters,
        List<CharacterRelationVO> relations,
        List<String> skipped,
        String model,
        Long latencyMs) {
}
