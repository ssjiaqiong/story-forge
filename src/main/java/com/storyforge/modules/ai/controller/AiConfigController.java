package com.storyforge.modules.ai.controller;

import com.storyforge.common.api.R;
import com.storyforge.common.audit.Audit;
import com.storyforge.common.ratelimit.RateLimit;
import com.storyforge.infra.ai.AiTestResult;
import com.storyforge.modules.ai.dto.AiConfigCreateRequest;
import com.storyforge.modules.ai.dto.AiConfigStatusRequest;
import com.storyforge.modules.ai.dto.AiConfigUpdateRequest;
import com.storyforge.modules.ai.dto.AiConfigVO;
import com.storyforge.modules.ai.dto.AiModelProbeRequest;
import com.storyforge.modules.ai.dto.ImageProviderPresetVO;
import com.storyforge.modules.ai.service.AiConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * AI 接口配置（设置 → AI 接口管理）。
 *
 * <p>所有接口按当前登录用户隔离：只能看到、只能操作自己的配置。</p>
 */
@Tag(name = "设置 - AI 接口管理", description = "OpenAI 兼容端点的增删改查、默认端点、连通性测试与模型拉取")
@RestController
@RequestMapping("/api/ai-configs")
@RequiredArgsConstructor
public class AiConfigController {

    private final AiConfigService aiConfigService;

    @Operation(summary = "接口配置列表", description = "默认端点排在最前；API Key 仅返回掩码")
    @GetMapping
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 600, windowSeconds = 60)
    public R<List<AiConfigVO>> list() {
        return R.ok(aiConfigService.list());
    }

    @Operation(summary = "文生图平台预设", description = "新增图像接口时一键填入地址 / 模型 / 尺寸")
    @GetMapping("/presets")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 600, windowSeconds = 60)
    public R<List<ImageProviderPresetVO>> presets() {
        return R.ok(aiConfigService.presets());
    }

    @Operation(summary = "接口配置详情")
    @GetMapping("/{id}")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 600, windowSeconds = 60)
    public R<AiConfigVO> detail(@PathVariable Long id) {
        return R.ok(aiConfigService.detail(id));
    }

    @Operation(summary = "新增接口配置", description = "首个配置自动成为默认端点；API Key 加密入库")
    @PostMapping
    @Audit(action = "AI_CONFIG_CREATE", targetType = "AI_CONFIG")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 120, windowSeconds = 60)
    public R<AiConfigVO> create(@Valid @RequestBody AiConfigCreateRequest request) {
        return R.ok(aiConfigService.create(request));
    }

    @Operation(summary = "编辑接口配置", description = "字段为 null 表示不修改；apiKey 留空表示保留原密钥")
    @PutMapping("/{id}")
    @Audit(action = "AI_CONFIG_UPDATE", targetType = "AI_CONFIG", targetIdIndex = 0)
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 120, windowSeconds = 60)
    public R<AiConfigVO> update(@PathVariable Long id, @Valid @RequestBody AiConfigUpdateRequest request) {
        return R.ok(aiConfigService.update(id, request));
    }

    @Operation(summary = "删除接口配置", description = "被场景绑定时拒绝删除")
    @DeleteMapping("/{id}")
    @Audit(action = "AI_CONFIG_DELETE", targetType = "AI_CONFIG", targetIdIndex = 0)
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 120, windowSeconds = 60)
    public R<Void> delete(@PathVariable Long id) {
        aiConfigService.delete(id);
        return R.ok();
    }

    @Operation(summary = "设为默认端点", description = "同一用户仅一个默认；停用的接口需先启用")
    @PutMapping("/{id}/default")
    @Audit(action = "AI_CONFIG_SET_DEFAULT", targetType = "AI_CONFIG", targetIdIndex = 0)
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 120, windowSeconds = 60)
    public R<AiConfigVO> setDefault(@PathVariable Long id) {
        return R.ok(aiConfigService.setDefault(id));
    }

    @Operation(summary = "启用 / 停用", description = "停用默认端点会同时取消其默认标记")
    @PutMapping("/{id}/status")
    @Audit(action = "AI_CONFIG_STATUS", targetType = "AI_CONFIG", targetIdIndex = 0)
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 120, windowSeconds = 60)
    public R<AiConfigVO> changeEnabled(@PathVariable Long id, @Valid @RequestBody AiConfigStatusRequest request) {
        return R.ok(aiConfigService.changeEnabled(id, request.getEnabled()));
    }

    @Operation(summary = "连通性测试", description = "发送一次最小 chat 请求，返回成功与否、延迟、模型回显与错误详情")
    @PostMapping("/{id}/test")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 30, windowSeconds = 60)
    public R<AiTestResult> test(@PathVariable Long id) {
        return R.ok(aiConfigService.test(id));
    }

    @Operation(summary = "拉取模型列表", description = "调用 {baseUrl}/models 并缓存到该配置")
    @GetMapping("/{id}/models")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 30, windowSeconds = 60)
    public R<List<String>> models(@PathVariable Long id) {
        return R.ok(aiConfigService.fetchModels(id));
    }

    @Operation(summary = "试拉模型列表（表单未保存也可用）",
            description = "用入参里的 baseUrl / apiKey 探测；apiKey 留空且传了 id 时使用该配置已存的密钥")
    @PostMapping("/models/probe")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 30, windowSeconds = 60)
    public R<List<String>> probeModels(@Valid @RequestBody AiModelProbeRequest request) {
        return R.ok(aiConfigService.probeModels(request));
    }
}
