package com.storyforge.modules.novel.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.storyforge.common.api.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 超管「小说管理」列表查询条件。
 *
 * <p>{@code state} 是面向运营的三态口径，与库里的 {@code status} 不是一一对应：
 * {@code CREATING} = DRAFT + WRITING（创作中）、{@code COMPLETED} = FINISHED（完结）、
 * {@code ABANDONED} = ARCHIVED（废弃）。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "超管小说列表查询")
public class AdminNovelQuery extends PageQuery {

    @Schema(description = "关键字：书名 / 创建用户登录名 / 昵称")
    private String keyword;

    @Schema(description = "状态：CREATING 创作中 / COMPLETED 完结 / ABANDONED 废弃")
    private String state;

    @Schema(description = "数据范围：NORMAL 正常（默认）/ DELETED 已逻辑删除 / ALL 全部")
    private String scope;

    @Schema(description = "创建时间起（含），格式 yyyy-MM-dd")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate createdFrom;

    @Schema(description = "创建时间止（含），格式 yyyy-MM-dd")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate createdTo;

    @Schema(description = "排序：CREATED_DESC（默认）/ UPDATED_DESC / WORDS_DESC / CHAPTERS_DESC")
    private String sort;

    /**
     * 「截止日期」的开区间上界（次日零点）。
     *
     * <p>SQL 里不写 {@code + INTERVAL '1 day'}——那是 PostgreSQL 专有语法，MySQL 要写
     * {@code DATE_ADD}、SQLite 要写 {@code datetime(+'1 day')}。日期运算放到 Java 侧，
     * Mapper 只做一次普通的 {@code <} 比较，三种库通用。</p>
     */
    @JsonIgnore
    @Schema(hidden = true)
    public LocalDateTime getCreatedToExclusive() {
        return createdTo == null ? null : createdTo.plusDays(1).atStartOfDay();
    }
}
