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
 * 小说主表。
 *
 * <p>{@code user_id} 为数据隔离键：查询由 MyBatis-Plus 数据权限插件自动追加条件，
 * 写入时由 Service 显式赋值。</p>
 */
@Data
@TableName("t_novel")
public class NovelEntity implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 归属用户 */
    private Long userId;

    /** 书名，同一用户内唯一 */
    private String title;

    /** 笔名 */
    private String penName;

    /** 题材，如 玄幻 / 都市 / 悬疑 */
    private String genre;

    /** 标签 JSON 数组文本 */
    private String tags;

    /** 简介 */
    private String intro;

    private String coverPath;

    /** DRAFT / WRITING / FINISHED / ARCHIVED */
    private String status;

    /** MANUAL 手动创建 / INSPIRATION 灵感模式创建 */
    private String source;

    /** 来源灵感会话 */
    private Long inspirationSessionId;

    private Integer chapterCount;

    private Integer wordCount;

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
