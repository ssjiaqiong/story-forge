package com.storyforge.modules.novel.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 小说封面。
 *
 * <p>一部小说最多 5 张（{@code sort_no} 0~4，书架左右切换）；图片本体落在封面目录，
 * 表里只存相对路径 {@code user_id/novel_id/{uuid}.jpg}。</p>
 */
@Data
@TableName("t_novel_cover")
public class NovelCoverEntity implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 归属用户（数据隔离键） */
    private Long userId;

    private Long novelId;

    /** 相对封面根目录的路径 */
    private String filePath;

    /** 实际图片类型：image/jpeg / image/png / image/webp */
    private String mimeType;

    private Integer fileSize;

    private Integer width;

    private Integer height;

    /** 生成时使用的正向提示词 */
    private String prompt;

    /** 生成时使用的负面提示词 */
    private String negativePrompt;

    /** 封面风格编码（ANIME/REAL/CG/INK/OIL/FLAT），可空 */
    private String style;

    /** 1 = 封面已合成书名/作者文字 */
    private Integer hasText;

    /** 合成到封面上的书名文字 */
    private String titleText;

    /** 合成到封面上的作者名文字 */
    private String authorText;

    /** 文字渲染方式：OVERLAY 本地叠加 / AI_NATIVE 模型直出 */
    private String textMode;

    /** 生成时使用的平台协议 */
    private String provider;

    /** 生成时实际使用的模型名 */
    private String model;

    /** 1 = 书架默认展示的那一张 */
    private Integer isPrimary;

    /** 切换顺序 0~4 */
    private Integer sortNo;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Long updatedBy;

    @Version
    private Integer version;
}
