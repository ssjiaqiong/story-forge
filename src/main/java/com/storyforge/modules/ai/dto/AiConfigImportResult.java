package com.storyforge.modules.ai.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 接口配置导入结果。
 *
 * <p>导入逐条进行：单条数据不合法（缺名称、地址为空、尺寸非法等）只跳过该条并记录原因，
 * 不影响其余条目。重名不报错，自动追加序号。</p>
 */
@Data
@Schema(description = "AI 接口配置导入结果")
public class AiConfigImportResult {

    /** 包内条数 */
    private int total;

    /** 实际写入的条数 */
    private int imported;

    /** 跳过的条数 */
    private int skipped;

    /** 其中带密钥导入的条数 */
    private int withApiKey;

    /** 重命名明细，如「OpenAI → OpenAI（2）」 */
    private List<String> renamed = new ArrayList<>();

    /** 跳过明细，如「测试接口：接口地址不能为空」 */
    private List<String> skippedItems = new ArrayList<>();

    /** 导入后的完整列表（前端可直接刷新，不必再请求一次） */
    private List<AiConfigVO> configs = new ArrayList<>();
}
