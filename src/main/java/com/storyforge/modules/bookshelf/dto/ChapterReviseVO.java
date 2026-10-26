package com.storyforge.modules.bookshelf.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 「按分析修改」结果：只生成不落库，等作者在对比窗口确认后再保存。
 *
 * @param originalContent 修改前正文（服务端读取，避免前端拿着过期内容做对比）
 * @param revisedContent  修改后正文
 * @param notes           AI 自述的改动要点，可能为空
 */
@Schema(description = "按分析修改结果")
public record ChapterReviseVO(
        Long chapterId,
        Integer chapterNo,
        String chapterTitle,
        String originalContent,
        String revisedContent,
        List<String> notes,
        Integer wordCountBefore,
        Integer wordCountAfter,
        String intensity,
        String model,
        Long latencyMs) {
}
