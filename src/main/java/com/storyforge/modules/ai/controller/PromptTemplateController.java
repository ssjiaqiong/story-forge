package com.storyforge.modules.ai.controller;

import com.storyforge.common.api.R;
import com.storyforge.common.ratelimit.RateLimit;
import com.storyforge.modules.ai.dto.PromptTemplateSaveRequest;
import com.storyforge.modules.ai.dto.PromptTemplateVO;
import com.storyforge.modules.ai.service.PromptTemplateService;
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
 * 设置中心 · 提示词模板。
 *
 * <p>用户只能改「指令段」；输出格式契约由系统追加，接口里只读返回。</p>
 */
@Tag(name = "设置 - 提示词模板", description = "按 AI 场景定制提示词；输出格式由系统统一封装")
@RestController
@RequestMapping("/api/prompt-templates")
@RequiredArgsConstructor
public class PromptTemplateController {

    private final PromptTemplateService promptTemplateService;

    @Operation(summary = "场景模板列表")
    @GetMapping
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 600, windowSeconds = 60)
    public R<List<PromptTemplateVO.Item>> list() {
        return R.ok(promptTemplateService.list());
    }

    @Operation(summary = "场景模板详情", description = "含默认模板、当前生效模板、可用变量与锁定的输出格式")
    @GetMapping("/{scene}")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 600, windowSeconds = 60)
    public R<PromptTemplateVO.Detail> detail(@PathVariable String scene) {
        return R.ok(promptTemplateService.detail(scene));
    }

    @Operation(summary = "保存模板")
    @PutMapping("/{scene}")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 120, windowSeconds = 60)
    public R<PromptTemplateVO.Detail> save(@PathVariable String scene,
                                           @Valid @RequestBody PromptTemplateSaveRequest request) {
        return R.ok(promptTemplateService.save(scene, request.getContent()));
    }

    @Operation(summary = "恢复默认")
    @DeleteMapping("/{scene}")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 120, windowSeconds = 60)
    public R<Void> reset(@PathVariable String scene) {
        promptTemplateService.reset(scene);
        return R.ok();
    }

    @Operation(summary = "预览渲染结果", description = "用示例变量渲染，不调用 AI")
    @PostMapping("/{scene}/preview")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 120, windowSeconds = 60)
    public R<PromptTemplateVO.Preview> preview(@PathVariable String scene) {
        return R.ok(promptTemplateService.preview(scene));
    }
}
