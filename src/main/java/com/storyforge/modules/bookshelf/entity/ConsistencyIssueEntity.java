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
 * 一致性 / 矛盾检查的问题台账。
 *
 * <p>两个来源共用一张表：{@code RULE} 是规则层查出来的硬矛盾（秒级、零 token、必有证据），
 * {@code AI} 是模型读正文找出的语义冲突（慢、要花钱、可能误报）。前端默认只展示最近一次
 * {@code runId} 的结果，历史批次仍可按 run 回看。</p>
 *
 * <p>为什么落库而不是每次现算：作者要能「忽略」某条——误报一旦每次重跑都弹回来，
 * 这个页面就没人看了。{@code status} 就是给作者一个「这条我看过了」的位置。</p>
 */
@Data
@TableName("t_consistency_issue")
public class ConsistencyIssueEntity implements Serializable {

    public static final String SOURCE_RULE = "RULE";
    public static final String SOURCE_AI = "AI";

    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_IGNORED = "IGNORED";
    public static final String STATUS_FIXED = "FIXED";

    public static final String SEVERITY_P0 = "P0";
    public static final String SEVERITY_P1 = "P1";
    public static final String SEVERITY_P2 = "P2";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;

    private Long novelId;

    /** 一次检查的批次号，形如 {@code ci20261026...} */
    private String runId;

    /** RULE / AI */
    private String source;

    /** 全书级问题（如断号、卷号异常）没有所属章节 */
    private Long chapterId;

    private Integer chapterNo;

    private String chapterTitle;

    /** 问题类型，取值见 {@code ConsistencyIssueTypes} */
    private String issueType;

    /** P0 / P1 / P2 */
    private String severity;

    /** 一句话标题，列表里直接展示 */
    private String title;

    /** 问题说明：为什么判它是矛盾 */
    private String detail;

    /** 正文依据或判定依据；没有证据的问题不产出 */
    private String evidence;

    /** 修改建议 */
    private String suggestion;

    /** OPEN / IGNORED / FIXED */
    private String status;

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
