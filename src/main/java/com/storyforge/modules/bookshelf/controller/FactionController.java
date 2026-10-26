package com.storyforge.modules.bookshelf.controller;

import com.storyforge.common.api.R;
import com.storyforge.common.ratelimit.RateLimit;
import com.storyforge.modules.bookshelf.dto.FactionMemberRequest;
import com.storyforge.modules.bookshelf.dto.FactionMemberUpdateRequest;
import com.storyforge.modules.bookshelf.dto.FactionMemberVO;
import com.storyforge.modules.bookshelf.dto.FactionRequest;
import com.storyforge.modules.bookshelf.dto.FactionVO;
import com.storyforge.modules.bookshelf.service.FactionService;
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
 * 势力管理：势力增删改查 + 成员职位维护。
 */
@Tag(name = "势力管理", description = "公司 / 帮派 / 国家等势力及其成员职位")
@RestController
@RequestMapping("/api/bookshelf")
@RequiredArgsConstructor
public class FactionController {

    private final FactionService factionService;

    @Operation(summary = "势力列表", description = "含各势力成员与其职位")
    @GetMapping("/novels/{novelId}/factions")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 600, windowSeconds = 60)
    public R<List<FactionVO>> list(@PathVariable Long novelId) {
        return R.ok(factionService.list(novelId));
    }

    @Operation(summary = "新增势力")
    @PostMapping("/novels/{novelId}/factions")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 120, windowSeconds = 60)
    public R<FactionVO> create(@PathVariable Long novelId, @Valid @RequestBody FactionRequest request) {
        return R.ok(factionService.create(novelId, request));
    }

    @Operation(summary = "修改势力")
    @PutMapping("/factions/{id}")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 300, windowSeconds = 60)
    public R<FactionVO> update(@PathVariable Long id, @Valid @RequestBody FactionRequest request) {
        return R.ok(factionService.update(id, request));
    }

    @Operation(summary = "删除势力", description = "同时删除其成员任职记录")
    @DeleteMapping("/factions/{id}")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 120, windowSeconds = 60)
    public R<Void> delete(@PathVariable Long id) {
        factionService.delete(id);
        return R.ok();
    }

    @Operation(summary = "添加势力成员", description = "同一角色在同一势力只能有一条任职记录；职位为自由文本")
    @PostMapping("/factions/{id}/members")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 300, windowSeconds = 60)
    public R<FactionMemberVO> addMember(@PathVariable Long id, @Valid @RequestBody FactionMemberRequest request) {
        return R.ok(factionService.addMember(id, request));
    }

    @Operation(summary = "修改成员职位")
    @PutMapping("/faction-members/{id}")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 300, windowSeconds = 60)
    public R<FactionMemberVO> updateMember(@PathVariable Long id,
                                           @Valid @RequestBody FactionMemberUpdateRequest request) {
        return R.ok(factionService.updateMember(id, request.getPosition()));
    }

    @Operation(summary = "移除势力成员")
    @DeleteMapping("/faction-members/{id}")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 300, windowSeconds = 60)
    public R<Void> removeMember(@PathVariable Long id) {
        factionService.removeMember(id);
        return R.ok();
    }
}
