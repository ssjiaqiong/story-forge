package com.storyforge.modules.novel.dto;

/**
 * 内部使用的小说草案（由灵感模式抽取结果组装）。
 *
 * @param title               书名，为空时由 Service 兜底
 * @param genre               题材
 * @param tags                标签
 * @param intro               简介
 * @param penName             笔名
 * @param source              来源：MANUAL / INSPIRATION
 * @param inspirationSessionId 来源灵感会话
 */
public record NovelDraft(
        String title,
        String genre,
        java.util.List<String> tags,
        String intro,
        String penName,
        String source,
        Long inspirationSessionId) {
}
