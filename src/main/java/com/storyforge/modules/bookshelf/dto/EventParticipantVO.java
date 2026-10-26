package com.storyforge.modules.bookshelf.dto;

/**
 * 事件参与角色视图。
 */
public record EventParticipantVO(
        Long id,
        String name,
        String roleLabel,
        String roleDesc) {
}
