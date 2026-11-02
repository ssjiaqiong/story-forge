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
 * 角色状态快照：只记**发生变化**的章节。
 *
 * <p>人物档案（{@code t_character}）是静态的「他是谁」，属性与背包是数值化的「他有多少」，
 * 但写下一章真正需要的是「他现在在哪、伤成什么样、情绪如何、知道什么、还被蒙在鼓里什么」。
 * 后者随时间变化，档案里放不下，所以单独一张时间线表。</p>
 *
 * <p>查「第 N 章时他的状态」= 取 {@code chapter_no <= N} 里最大的一条；
 * 某角色连续几十章没变化就一条都不写，500 章的书实测约两三千行，应用侧内存里算得动。</p>
 */
@Data
@TableName("t_character_state")
public class CharacterStateEntity implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;

    private Long novelId;

    /** 发生变化的那一章 */
    private Integer chapterNo;

    private Long chapterId;

    private Long characterId;

    /** 本章结束时所在位置 */
    private String location;

    /** 身体 / 精神状态，如「左臂骨折，失血过多」 */
    private String conditionText;

    /** 情绪底色 */
    private String mood;

    /** 本章新得知的信息，一条一行 */
    private String knowsAdded;

    /** 本章被隐瞒或误信的信息，一条一行 */
    private String knowsHidden;

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
