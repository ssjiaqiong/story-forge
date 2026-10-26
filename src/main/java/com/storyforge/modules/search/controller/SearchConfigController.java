package com.storyforge.modules.search.controller;

import com.storyforge.common.api.R;
import com.storyforge.common.audit.Audit;
import com.storyforge.common.ratelimit.RateLimit;
import com.storyforge.modules.search.dto.SearchConfigRequest;
import com.storyforge.modules.search.dto.SearchConfigVO;
import com.storyforge.modules.search.dto.SearchTestRequest;
import com.storyforge.modules.search.dto.SearchTestResultVO;
import com.storyforge.modules.search.service.SearchConfigService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 设置 → 联网搜索：搜索源管理。
 */
@Tag(name = "设置 - 联网搜索", description = "搜索源的增删改查、默认源、连通性测试")
@RestController
@RequestMapping("/api/search-configs")
@RequiredArgsConstructor
public class SearchConfigController {

    private final SearchConfigService configService;

    @Operation(summary = "搜索源列表", description = "首次访问自动初始化免密钥搜索源（必应 / DuckDuckGo / 维基百科）")
    @GetMapping
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 600, windowSeconds = 60)
    public R<List<SearchConfigVO>> list() {
        return R.ok(configService.list());
    }

    @Operation(summary = "搜索源详情", description = "额外返回明文 API Key，供编辑页回填")
    @GetMapping("/{id}")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 600, windowSeconds = 60)
    public R<SearchConfigVO> detail(@PathVariable Long id) {
        return R.ok(configService.detail(id));
    }

    @Operation(summary = "新增搜索源")
    @PostMapping
    @Audit(action = "SEARCH_CONFIG_CREATE", targetType = "SEARCH_CONFIG")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 60, windowSeconds = 60)
    public R<SearchConfigVO> create(@Valid @RequestBody SearchConfigRequest request) {
        return R.ok(configService.create(request));
    }

    @Operation(summary = "编辑搜索源", description = "字段为 null 表示不修改；apiKey 留空表示保留原密钥")
    @PutMapping("/{id}")
    @Audit(action = "SEARCH_CONFIG_UPDATE", targetType = "SEARCH_CONFIG", targetIdIndex = 0)
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 60, windowSeconds = 60)
    public R<SearchConfigVO> update(@PathVariable Long id, @Valid @RequestBody SearchConfigRequest request) {
        return R.ok(configService.update(id, request));
    }

    @Operation(summary = "删除搜索源", description = "被场景绑定的会自动改为继承默认源")
    @DeleteMapping("/{id}")
    @Audit(action = "SEARCH_CONFIG_DELETE", targetType = "SEARCH_CONFIG", targetIdIndex = 0)
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 60, windowSeconds = 60)
    public R<Void> delete(@PathVariable Long id) {
        configService.delete(id);
        return R.ok();
    }

    @Operation(summary = "设为默认搜索源")
    @PostMapping("/{id}/default")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 120, windowSeconds = 60)
    public R<SearchConfigVO> setDefault(@PathVariable Long id) {
        return R.ok(configService.setDefault(id));
    }

    @Operation(summary = "启用 / 停用搜索源")
    @PostMapping("/{id}/status")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 120, windowSeconds = 60)
    public R<SearchConfigVO> status(@PathVariable Long id, @RequestParam boolean enabled) {
        return R.ok(configService.toggle(id, enabled));
    }

    @Operation(summary = "测试已保存的搜索源")
    @PostMapping("/{id}/test")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 60, windowSeconds = 60)
    public R<SearchTestResultVO> test(@PathVariable Long id,
                                      @RequestBody(required = false) SearchTestRequest request) {
        return R.ok(configService.test(id, request));
    }

    @Operation(summary = "测试表单中的搜索源", description = "未保存也可测试；id 存在时可复用已保存的密钥")
    @PostMapping("/test")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 60, windowSeconds = 60)
    public R<SearchTestResultVO> probe(@Valid @RequestBody SearchTestRequest request) {
        return R.ok(configService.probe(request));
    }
}
