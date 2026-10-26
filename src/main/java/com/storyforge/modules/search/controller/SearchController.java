package com.storyforge.modules.search.controller;

import com.storyforge.common.api.R;
import com.storyforge.common.ratelimit.RateLimit;
import com.storyforge.modules.search.dto.SearchBundleVO;
import com.storyforge.modules.search.dto.SearchPreviewRequest;
import com.storyforge.modules.search.dto.SearchSceneSaveRequest;
import com.storyforge.modules.search.dto.SearchSceneVO;
import com.storyforge.modules.search.service.SearchSceneService;
import com.storyforge.modules.search.service.WebSearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 联网搜索：场景绑定与试搜。
 */
@Tag(name = "联网搜索", description = "事件 / 大纲 / 正文的搜索场景绑定与即时试搜")
@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class SearchController {

    private final SearchSceneService sceneService;
    private final WebSearchService webSearchService;

    @Operation(summary = "搜索场景绑定列表")
    @GetMapping("/scenes")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 600, windowSeconds = 60)
    public R<List<SearchSceneVO>> scenes() {
        return R.ok(sceneService.list());
    }

    @Operation(summary = "保存搜索场景绑定")
    @PutMapping("/scenes")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 120, windowSeconds = 60)
    public R<Void> saveScenes(@Valid @RequestBody SearchSceneSaveRequest request) {
        sceneService.save(request);
        return R.ok();
    }

    @Operation(summary = "即时试搜", description = "不落库，只返回检索结果，用于验证搜索源效果")
    @PostMapping("/preview")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 60, windowSeconds = 60)
    public R<SearchBundleVO> preview(@Valid @RequestBody SearchPreviewRequest request) {
        return R.ok(webSearchService.searchWith(request.getConfigId(), request.getQuery(), request.getTopK()));
    }
}
