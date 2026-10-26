package com.storyforge.modules.bookshelf.dto;

/**
 * 角色关系视图。
 */
public record CharacterRelationVO(
        Long id,
        Long fromCharacterId,
        String fromCharacterName,
        Long toCharacterId,
        String toCharacterName,
        String relationType,
        String relationLabel,
        String description,
        Integer sortNo) {
}
