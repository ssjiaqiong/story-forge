package com.storyforge.modules.ai.controller;

import com.storyforge.common.api.R;
import com.storyforge.common.audit.Audit;
import com.storyforge.common.ratelimit.RateLimit;
import com.storyforge.modules.ai.dto.SceneBindingSaveRequest;
import com.storyforge.modules.ai.dto.SceneBindingVO;
import com.storyforge.modules.ai.service.SceneBindingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 场景级模型绑定（设置 → 场景绑定）。
 *
 * <p>每个 AI 场景可指定独立端点与模型参数；不指定则回落到默认端点。</p>
 */
@Tag(name = "设置 - 场景绑定", description = "为每个 AI 场景指定端点、模型与参数")
@RestController
@RequestMapping("/api/scene-bindings")
@RequiredArgsConstructor
public class SceneBindingController {

    private final SceneBindingService sceneBindingService;

    @Operation(summary = "场景绑定列表", description = "返回全部 AI 场景及当前绑定状态")
    @GetMapping
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 600, windowSeconds = 60)
    public R<List<SceneBindingVO>> list() {
        return R.ok(sceneBindingService.list());
    }

    @Operation(summary = "批量保存场景绑定", description = "aiConfigId 为空表示清除绑定、回落到默认端点")
    @PutMapping
    @Audit(action = "SCENE_BINDING_SAVE", targetType = "SCENE_BINDING")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 120, windowSeconds = 60)
    public R<Void> save(@Valid @RequestBody SceneBindingSaveRequest request) {
        sceneBindingService.save(request);
        return R.ok();
    }
}
