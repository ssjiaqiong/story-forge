package com.storyforge.modules.bookshelf.dto;

/**
 * 势力成员视图。
 */
public record FactionMemberVO(
        Long id,
        Long characterId,
        String name,
        String roleLabel,
        String position) {
}
