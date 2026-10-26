package com.storyforge.modules.bookshelf.entity;

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
 * 小说设定文档（世界观 / 背景 / 核心冲突 / 核心看点 / 文风）。
 *
 * <p>{@code user_id} 为数据隔离键，查询由数据权限插件自动追加条件。</p>
 */
@Data
@TableName("t_setting_doc")
public class SettingDocEntity implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;

    private Long novelId;

    /** WORLDVIEW / BACKGROUND / CORE_CONFLICT / HIGHLIGHT / STYLE */
    private String category;

    private String title;

    /** 设定正文；HIGHLIGHT 每行一条 */
    private String contentMd;

    /** GLOBAL / CHAPTER / MANUAL */
    private String injectScope;

    private Integer sort;

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
