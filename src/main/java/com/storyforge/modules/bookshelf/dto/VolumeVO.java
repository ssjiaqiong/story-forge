package com.storyforge.modules.bookshelf.dto;

import com.storyforge.modules.bookshelf.entity.VolumeEntity;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 卷的列表项。
 *
 * <p>章数与字数从章节表实时聚合，不冗余到卷表——冗余就要在每次正文保存时同步，
 * 而这两个数字只在卷列表用，实时查一次更省心。</p>
 */
public record VolumeVO(
        @Schema(description = "卷 ID") String id,
        @Schema(description = "卷序号，从 1 开始") Integer volumeNo,
        @Schema(description = "卷名") String title,
        @Schema(description = "卷名是否为系统兜底（未填写过）") boolean titleFallback,
        @Schema(description = "卷简介") String intro,
        @Schema(description = "本章卷下的章节数") Integer chapterCount,
        @Schema(description = "本章卷下的正文字数") Integer wordCount,
        @Schema(description = "卷内最小章号；空卷为 null") Integer firstChapterNo,
        @Schema(description = "卷内最大章号；空卷为 null") Integer lastChapterNo
) {

    public static VolumeVO of(VolumeEntity entity, Integer chapterCount, Integer wordCount,
                              Integer firstChapterNo, Integer lastChapterNo) {
        boolean fallback = entity.getTitle() == null || entity.getTitle().isBlank();
        return new VolumeVO(
                entity.getId() == null ? null : entity.getId().toString(),
                entity.getVolumeNo(),
                fallback ? VolumeEntity.fallbackTitle(entity.getVolumeNo()) : entity.getTitle(),
                fallback,
                entity.getIntro(),
                chapterCount == null ? 0 : chapterCount,
                wordCount == null ? 0 : wordCount,
                firstChapterNo,
                lastChapterNo);
    }
}
