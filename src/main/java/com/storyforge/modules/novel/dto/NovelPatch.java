package com.storyforge.modules.novel.dto;

import java.util.List;

/**
 * 小说基础信息补丁。
 *
 * <p>字段为 {@code null} 表示不修改；空串表示清空（书名除外，书名不允许为空）。</p>
 */
public record NovelPatch(
        String title,
        String penName,
        String genre,
        List<String> tags,
        String intro,
        String status) {
}
