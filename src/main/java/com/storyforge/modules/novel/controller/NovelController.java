package com.storyforge.modules.novel.controller;

import com.storyforge.common.api.PageResult;
import com.storyforge.common.api.R;
import com.storyforge.common.ratelimit.RateLimit;
import com.storyforge.modules.novel.dto.NovelQuery;
import com.storyforge.modules.novel.dto.NovelVO;
import com.storyforge.modules.novel.service.NovelService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 我的小说（按当前登录用户隔离）。
 */
@Tag(name = "小说", description = "当前用户的小说列表与详情")
@RestController
@RequestMapping("/api/novels")
@RequiredArgsConstructor
public class NovelController {

    private final NovelService novelService;

    @Operation(summary = "我的小说列表", description = "支持按书名/简介关键字与状态筛选")
    @GetMapping
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 600, windowSeconds = 60)
    public R<PageResult<NovelVO>> page(NovelQuery query) {
        return R.ok(novelService.page(query));
    }

    @Operation(summary = "小说详情")
    @GetMapping("/{id}")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 600, windowSeconds = 60)
    public R<NovelVO> detail(@PathVariable Long id) {
        return R.ok(novelService.detail(id));
    }
}
