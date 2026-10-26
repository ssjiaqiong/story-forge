package com.storyforge.modules.bookshelf.controller;

import com.storyforge.common.api.R;
import com.storyforge.common.ratelimit.RateLimit;
import com.storyforge.modules.bookshelf.dto.MasterOutlineGenerateRequest;
import com.storyforge.modules.bookshelf.dto.MasterOutlineRequest;
import com.storyforge.modules.bookshelf.dto.MasterOutlineVO;
import com.storyforge.modules.bookshelf.service.MasterOutlineService;
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
 * 总纲：一段粗粒度的剧情走向，用来指导章节大纲生成。
 */
@Tag(name = "总纲", description = "总纲的增删改查与 AI 生成")
@RestController
@RequestMapping("/api/bookshelf")
@RequiredArgsConstructor
public class MasterOutlineController {

    private final MasterOutlineService masterOutlineService;

    @Operation(summary = "总纲列表", description = "按排序号返回，附每条总纲关联的章节大纲数量")
    @GetMapping("/novels/{novelId}/master-outlines")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 600, windowSeconds = 60)
    public R<List<MasterOutlineVO>> list(@PathVariable Long novelId) {
        return R.ok(masterOutlineService.list(novelId));
    }

    @Operation(summary = "新增总纲")
    @PostMapping("/novels/{novelId}/master-outlines")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 120, windowSeconds = 60)
    public R<MasterOutlineVO> create(@PathVariable Long novelId,
                                     @Valid @RequestBody MasterOutlineRequest request) {
        return R.ok(masterOutlineService.create(novelId, request));
    }

    @Operation(summary = "修改总纲")
    @PutMapping("/master-outlines/{id}")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 300, windowSeconds = 60)
    public R<MasterOutlineVO> update(@PathVariable Long id,
                                     @Valid @RequestBody MasterOutlineRequest request) {
        return R.ok(masterOutlineService.update(id, request));
    }

    @Operation(summary = "删除总纲", description = "引用它的章节大纲会解除关联，章节本身保留")
    @DeleteMapping("/master-outlines/{id}")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 120, windowSeconds = 60)
    public R<Void> delete(@PathVariable Long id) {
        masterOutlineService.delete(id);
        return R.ok(null);
    }

    @Operation(summary = "AI 生成总纲",
            description = "资料充足时覆盖全书生成，资料不足只生成一部分；已生成的部分不会被重复覆盖")
    @PostMapping("/novels/{novelId}/master-outlines/generate")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 10, windowSeconds = 60)
    public R<List<MasterOutlineVO>> generate(@PathVariable Long novelId,
                                             @Valid @RequestBody MasterOutlineGenerateRequest request) {
        return R.ok(masterOutlineService.generate(novelId, request));
    }
}
