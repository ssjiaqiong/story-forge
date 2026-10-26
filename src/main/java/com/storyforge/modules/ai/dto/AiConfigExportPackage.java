package com.storyforge.modules.ai.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 接口配置导出包（导入的入参也是它）。
 *
 * <p>不搬运的数据：雪花 id、创建/更新时间、最近测试结果（都是本实例的运行痕迹，
 * 跨站点没有意义）。导入时按名称重建，重名自动追加序号。</p>
 *
 * <p><b>安全提示</b>：默认包含 API Key 明文，文件等同于凭据，导出后请妥善保管。
 * 需要分享配置文件时可选择不含密钥导出（导入后需重新填写）。</p>
 */
@Data
@Schema(description = "AI 接口配置导出包")
public class AiConfigExportPackage {

    public static final String FORMAT = "storyforge-ai-config-export";
    public static final int FORMAT_VERSION = 1;

    /** 固定标识，导入时校验 */
    private String format = FORMAT;

    private Integer formatVersion = FORMAT_VERSION;

    /** 导出时间（ISO-8601 本地时间字符串，仅作说明用途） */
    private String exportedAt;

    /** 包内条数 */
    private Integer total;

    /** 文件里是否真的含有 API Key 明文 */
    private Boolean containsApiKey;

    /** 导出站点信息：只写产品名，不写域名/IP，避免泄露部署位置 */
    private String site = "StoryForge";

    private List<AiConfigExportItem> configs = new ArrayList<>();
}
