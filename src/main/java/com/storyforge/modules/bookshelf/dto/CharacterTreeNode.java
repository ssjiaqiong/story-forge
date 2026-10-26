package com.storyforge.modules.bookshelf.dto;

import java.util.List;

/**
 * 人物关系树节点。
 *
 * @param relationType  与父节点的关系类型（根节点为 null）
 * @param relationLabel 与父节点的关系中文名（根节点为 null）
 */
public record CharacterTreeNode(
        Long characterId,
        String name,
        String identity,
        String rolePos,
        String roleLabel,
        String relationType,
        String relationLabel,
        List<CharacterTreeNode> children) {
}
