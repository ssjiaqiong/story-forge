package com.storyforge.modules.bookshelf.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 连写任务里单章的进展。
 *
 * @param chapterId 章节 ID
 * @param chapterNo 章号
 * @param title     章节标题
 * @param status    PENDING / RUNNING / SUCCEEDED / FAILED / SKIPPED（已完成被跳过）/ CANCELLED（未开始就被停止）
 * @param wordCount 写完后的汉字数
 * @param warning   不影响成稿的提示，如「本章没有大纲摘要」
 * @param error     失败原因
 */
@Schema(description = "连写任务中的单章进展")
public record WriteTaskChapterVO(
        String chapterId,
        Integer chapterNo,
        String title,
        String status,
        Integer wordCount,
        String warning,
        String error) {
}
