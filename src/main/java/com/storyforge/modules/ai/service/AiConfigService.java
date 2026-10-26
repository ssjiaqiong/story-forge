package com.storyforge.modules.ai.service;

import com.storyforge.infra.ai.AiTestResult;
import com.storyforge.modules.ai.dto.AiConfigCreateRequest;
import com.storyforge.modules.ai.dto.AiConfigUpdateRequest;
import com.storyforge.modules.ai.dto.AiConfigVO;
import com.storyforge.modules.ai.dto.AiModelProbeRequest;
import com.storyforge.modules.ai.dto.ImageProviderPresetVO;

import java.util.List;

/**
 * AI 接口配置服务（按用户隔离）。
 */
public interface AiConfigService {

    /** 当前用户的全部配置，默认端点排在最前。 */
    List<AiConfigVO> list();

    /** 文生图平台预设（地址 / 模型 / 尺寸建议），供新增图像接口时一键填入。 */
    List<ImageProviderPresetVO> presets();

    AiConfigVO detail(Long id);

    AiConfigVO create(AiConfigCreateRequest request);

    AiConfigVO update(Long id, AiConfigUpdateRequest request);

    void delete(Long id);

    /** 设为默认端点（同一用户仅一个）。 */
    AiConfigVO setDefault(Long id);

    AiConfigVO changeEnabled(Long id, boolean enabled);

    /** 连通性测试，结果同时写回该配置。 */
    AiTestResult test(Long id);

    /** 拉取并缓存模型列表。 */
    List<String> fetchModels(Long id);

    /**
     * 试拉模型列表：允许用表单里尚未保存的 baseUrl / apiKey 探测。
     *
     * <p>传了 {@code id} 时顺带把结果写回该配置的模型缓存。</p>
     */
    List<String> probeModels(AiModelProbeRequest request);
}
