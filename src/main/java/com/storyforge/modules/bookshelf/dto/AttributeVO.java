package com.storyforge.modules.bookshelf.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 人物属性视图。ID 由全局 Jackson 配置序列化为字符串下发。
 */
@Schema(description = "人物属性")
public record AttributeVO(
        Long id,
        Long characterId,
        String name,
        String value,
        String unit,
        String attrType,
        String note,
        Integer sortNo) {
}
