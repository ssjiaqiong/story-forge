package com.storyforge.modules.bookshelf.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * 人物背包条目视图。
 *
 * <p>ITEM 型会带出物品档案的描述与说明，前端表格展开即可看到，不必再查一次物品接口。</p>
 */
@Schema(description = "人物背包条目")
public record InventoryVO(
        Long id,
        Long characterId,
        String entryType,
        String entryTypeLabel,
        Long itemId,
        String name,
        BigDecimal quantity,
        String unit,
        String status,
        String statusLabel,
        String note,
        String itemDescription,
        String itemOrigin,
        Integer sortNo) {
}
