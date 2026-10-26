package com.storyforge.modules.ai.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 提示词模板导出包（导入的入参也是它）。
 *
 * <p>只搬「用户可编辑的指令段」：输出格式契约、变量清单、场景绑定都是系统行为或跨站点无意义的数据，
 * 一律不进包；默认模板全文也不带（默认随版本升级会变，回灌没意义）。</p>
 */
@Data
@Schema(description = "提示词模板导出包")
public class PromptExportPackage {

    public static final String FORMAT = "storyforge-prompt-export";
    public static final int FORMAT_VERSION = 1;

    /** 固定标识，导入时校验 */
    private String format = FORMAT;

    private Integer formatVersion = FORMAT_VERSION;

    /** 导出时间（ISO-8601 本地时间字符串，仅作说明用途） */
    private String exportedAt;

    /** 导出站点信息：只写产品名，不写域名/IP */
    private String site = "StoryForge";

    /** 是否只导出了「用户改过」的场景 */
    private Boolean onlyOverridden;

    /** 包说明：告诉使用者什么搬了、什么没搬 */
    private String note;

    /** 包内条数 */
    private Integer total;

    private List<PromptExportItem> scenes = new ArrayList<>();
}
