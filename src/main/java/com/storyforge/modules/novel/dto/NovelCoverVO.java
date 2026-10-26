package com.storyforge.modules.novel.dto;

import com.storyforge.modules.novel.entity.NovelCoverEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 小说封面视图。
 *
 * <p>{@link #imageUrl} 是需要携带登录态的接口地址：前端用带 Authorization 的请求取 blob 再展示，
 * 避免把用户私有图片暴露成公开静态资源。</p>
 */
@Data
@Schema(description = "小说封面")
public class NovelCoverVO {

    private Long id;

    private Long novelId;

    @Schema(description = "取图地址（需带登录态请求）")
    private String imageUrl;

    @Schema(description = "图片类型")
    private String mimeType;

    @Schema(description = "文件大小（字节）")
    private Integer fileSize;

    private Integer width;

    private Integer height;

    @Schema(description = "生成时使用的正向提示词")
    private String prompt;

    @Schema(description = "生成时使用的模型")
    private String model;

    @Schema(description = "封面风格编码")
    private String style;

    @Schema(description = "是否已合成书名/作者文字")
    private Boolean hasText;

    @Schema(description = "合成到封面上的书名")
    private String titleText;

    @Schema(description = "合成到封面上的作者名")
    private String authorText;

    @Schema(description = "文字渲染方式：OVERLAY 本地叠加 / AI_NATIVE 模型直出")
    private String textMode;

    @Schema(description = "生成时使用的平台")
    private String provider;

    @Schema(description = "是否书架默认展示的那一张")
    private Boolean primary;

    @Schema(description = "切换顺序 0~4")
    private Integer sortNo;

    private LocalDateTime createdAt;

    public static NovelCoverVO from(NovelCoverEntity entity) {
        NovelCoverVO vo = new NovelCoverVO();
        vo.setId(entity.getId());
        vo.setNovelId(entity.getNovelId());
        vo.setImageUrl("/api/novels/covers/" + entity.getId() + "/image");
        vo.setMimeType(entity.getMimeType());
        vo.setFileSize(entity.getFileSize());
        vo.setWidth(entity.getWidth());
        vo.setHeight(entity.getHeight());
        vo.setPrompt(entity.getPrompt());
        vo.setModel(entity.getModel());
        vo.setStyle(entity.getStyle());
        vo.setHasText(entity.getHasText() != null && entity.getHasText() == 1);
        vo.setTitleText(entity.getTitleText());
        vo.setAuthorText(entity.getAuthorText());
        vo.setTextMode(entity.getTextMode());
        vo.setProvider(entity.getProvider());
        vo.setPrimary(entity.getIsPrimary() != null && entity.getIsPrimary() == 1);
        vo.setSortNo(entity.getSortNo());
        vo.setCreatedAt(entity.getCreatedAt());
        return vo;
    }
}
