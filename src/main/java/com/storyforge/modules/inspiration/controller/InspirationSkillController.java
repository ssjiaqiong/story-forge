package com.storyforge.modules.inspiration.controller;

import com.storyforge.common.api.R;
import com.storyforge.common.audit.Audit;
import com.storyforge.common.ratelimit.RateLimit;
import com.storyforge.modules.inspiration.dto.SkillCreateRequest;
import com.storyforge.modules.inspiration.dto.SkillUpdateRequest;
import com.storyforge.modules.inspiration.dto.SkillVO;
import com.storyforge.modules.inspiration.service.InspirationSkillService;
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
 * 灵感模式技能管理。
 *
 * <p>技能按用户隔离：内置「创建小说」技能在用户首次进入时复制到本人名下，
 * 之后的修改只影响该用户，其他用户看到的仍是各自副本。</p>
 */
@Tag(name = "灵感模式 - 技能", description = "技能列表、修改提示词、恢复默认与自定义技能")
@RestController
@RequestMapping("/api/inspiration/skills")
@RequiredArgsConstructor
public class InspirationSkillController {

    private final InspirationSkillService skillService;

    @Operation(summary = "技能列表", description = "首次调用会自动初始化内置「创建小说」技能")
    @GetMapping
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 600, windowSeconds = 60)
    public R<List<SkillVO>> list() {
        return R.ok(skillService.list());
    }

    @Operation(summary = "新建自定义技能")
    @PostMapping
    @Audit(action = "INSPIRATION_SKILL_CREATE", targetType = "INSPIRATION_SKILL")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 60, windowSeconds = 60)
    public R<SkillVO> create(@Valid @RequestBody SkillCreateRequest request) {
        return R.ok(skillService.create(request));
    }

    @Operation(summary = "修改技能", description = "修改仅对当前用户生效；字段为 null 表示不修改")
    @PutMapping("/{id}")
    @Audit(action = "INSPIRATION_SKILL_UPDATE", targetType = "INSPIRATION_SKILL", targetIdIndex = 0)
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 120, windowSeconds = 60)
    public R<SkillVO> update(@PathVariable Long id, @Valid @RequestBody SkillUpdateRequest request) {
        return R.ok(skillService.update(id, request));
    }

    @Operation(summary = "恢复默认提示词", description = "仅内置「创建小说」技能支持")
    @PostMapping("/{id}/reset")
    @Audit(action = "INSPIRATION_SKILL_RESET", targetType = "INSPIRATION_SKILL", targetIdIndex = 0)
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 60, windowSeconds = 60)
    public R<SkillVO> reset(@PathVariable Long id) {
        return R.ok(skillService.reset(id));
    }

    @Operation(summary = "删除技能", description = "内置技能不可删除")
    @DeleteMapping("/{id}")
    @Audit(action = "INSPIRATION_SKILL_DELETE", targetType = "INSPIRATION_SKILL", targetIdIndex = 0)
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 60, windowSeconds = 60)
    public R<Void> delete(@PathVariable Long id) {
        skillService.delete(id);
        return R.ok();
    }
}
