package com.storyforge.modules.bookshelf.service;

import com.storyforge.modules.bookshelf.dto.AttributeRequest;
import com.storyforge.modules.bookshelf.dto.AttributeVO;
import com.storyforge.modules.bookshelf.dto.InventoryRequest;
import com.storyforge.modules.bookshelf.dto.InventoryVO;

import java.util.List;
import java.util.Map;

/**
 * 人物资产：自定义属性 + 背包。
 */
public interface CharacterAssetService {

    List<AttributeVO> listAttributes(Long characterId);

    AttributeVO createAttribute(Long characterId, AttributeRequest request);

    AttributeVO updateAttribute(Long attributeId, AttributeRequest request);

    void deleteAttribute(Long attributeId);

    List<InventoryVO> listInventory(Long characterId);

    InventoryVO createInventory(Long characterId, InventoryRequest request);

    InventoryVO updateInventory(Long inventoryId, InventoryRequest request);

    void deleteInventory(Long inventoryId);

    /** 生成类提示词用：角色 ID → 属性（按 sort_no） */
    Map<Long, List<AttributeVO>> attributesByCharacter(Long novelId);

    /** 生成类提示词用：角色 ID → 背包（货币优先，按 sort_no） */
    Map<Long, List<InventoryVO>> inventoryByCharacter(Long novelId);

    /** 章节分析用：整本书的属性原始行（要带去改当前值） */
    List<com.storyforge.modules.bookshelf.entity.CharacterAttributeEntity> attributeEntities(Long novelId);

    /** 章节分析用：整本书的背包原始行 */
    List<com.storyforge.modules.bookshelf.entity.CharacterInventoryEntity> inventoryEntities(Long novelId);
}
