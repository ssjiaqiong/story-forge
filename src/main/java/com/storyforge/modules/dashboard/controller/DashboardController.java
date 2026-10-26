package com.storyforge.modules.dashboard.controller;

import com.storyforge.common.api.R;
import com.storyforge.common.ratelimit.RateLimit;
import com.storyforge.modules.dashboard.dto.DashboardOverviewVO;
import com.storyforge.modules.dashboard.service.DashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 仪表盘：当前登录用户的创作与 AI 用量总览。
 *
 * <p>只读接口，数据范围恒为「本人」，不需要任何额外参数。</p>
 */
@Tag(name = "仪表盘", description = "小说数量 / 字数 / 章节 / Token 消耗 / 登录信息总览")
@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    @Operation(summary = "总览数据",
            description = "小说状态分布、字数与章节统计、近 14 天趋势、近 30 天场景用量、最近编辑的小说与登录信息")
    @GetMapping("/overview")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 120, windowSeconds = 60)
    public R<DashboardOverviewVO> overview() {
        return R.ok(dashboardService.overview());
    }
}
