package com.storyforge.modules.ai.dto;

import com.storyforge.modules.ai.entity.AiConfigEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * AI 接口配置出参。
 *
 * <p>密钥以 AES-256-GCM 密文落库。列表接口只回显 {@link #apiKeyMask}；详情接口额外解密出
 * {@link #apiKey}，供编辑页在密码框中回填（可切换明文）。写操作响应不回传明文。</p>
 */
@Data
@Schema(description = "AI 接口配置")
public class AiConfigVO {

    private Long id;
    private String name;
    /** TEXT 文本接口 / IMAGE 图像（文生图） */
    private String kind;
    /** 平台协议（文生图），文本接口固定 OPENAI */
    private String provider;
    /** 图像接口默认出图尺寸 */
    private String imageSize;
    private String baseUrl;
    /** 掩码，如 sk-****abcd */
    private String apiKeyMask;
    /** 明文密钥，仅详情接口返回；解密失败时为 null */
    private String apiKey;
    /** 是否已配置密钥（掩码为空时为 false） */
    private Boolean apiKeyConfigured;
    private String defaultModel;
    /** 最近一次拉取的模型列表缓存 */
    private List<String> modelList;
    private Integer timeoutMs;
    private Double temperature;
    private Integer maxTokens;
    private Double topP;
    /** 附加请求头（JSON 对象文本） */
    private String extraHeaders;
    private Boolean enabled;
    private Boolean isDefault;
    private String remark;
    private LocalDateTime lastTestAt;
    private Boolean lastTestOk;
    private Integer lastTestLatencyMs;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static AiConfigVO from(AiConfigEntity entity) {
        AiConfigVO vo = new AiConfigVO();
        vo.setId(entity.getId());
        vo.setName(entity.getName());
        vo.setKind(entity.getKind() == null ? "TEXT" : entity.getKind());
        vo.setProvider(entity.getProvider() == null ? "OPENAI" : entity.getProvider());
        vo.setImageSize(entity.getImageSize());
        vo.setBaseUrl(entity.getBaseUrl());
        vo.setApiKeyMask(entity.getApiKeyMask());
        vo.setApiKeyConfigured(entity.getApiKeyCipher() != null && !entity.getApiKeyCipher().isBlank());
        vo.setDefaultModel(entity.getDefaultModel());
        vo.setTimeoutMs(entity.getTimeoutMs());
        vo.setTemperature(entity.getTemperature());
        vo.setMaxTokens(entity.getMaxTokens());
        vo.setTopP(entity.getTopP());
        vo.setExtraHeaders(entity.getExtraHeaders());
        vo.setEnabled(entity.getEnabled());
        vo.setIsDefault(entity.getIsDefault());
        vo.setRemark(entity.getRemark());
        vo.setLastTestAt(entity.getLastTestAt());
        vo.setLastTestOk(entity.getLastTestOk());
        vo.setLastTestLatencyMs(entity.getLastTestLatencyMs());
        vo.setCreatedAt(entity.getCreatedAt());
        vo.setUpdatedAt(entity.getUpdatedAt());
        return vo;
    }

    public static AiConfigVO from(AiConfigEntity entity, List<String> modelList) {
        AiConfigVO vo = from(entity);
        vo.setModelList(modelList);
        return vo;
    }
}
