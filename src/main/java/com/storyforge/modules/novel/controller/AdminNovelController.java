package com.storyforge.modules.novel.controller;

import com.storyforge.common.api.PageResult;
import com.storyforge.common.api.R;
import com.storyforge.common.audit.Audit;
import com.storyforge.common.ratelimit.RateLimit;
import com.storyforge.modules.novel.dto.AdminChapterVO;
import com.storyforge.modules.novel.dto.AdminNovelDetailVO;
import com.storyforge.modules.novel.dto.AdminNovelQuery;
import com.storyforge.modules.novel.dto.AdminNovelVO;
import com.storyforge.modules.novel.dto.AdminPurgeRequest;
import com.storyforge.modules.novel.dto.AdminPurgeResultVO;
import com.storyforge.modules.novel.service.AdminNovelService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 超级管理员「小说管理」接口。
 *
 * <p>用于平台运营查看全部用户的小说：书名、创建账号、创建/更新时间、状态、字数、章节数，
 * 并按需查看章节目录与正文。查看类接口全部只读，不提供修改用户创作内容的入口。</p>
 *
 * <p>唯一的写操作是「物理清除」：仅当小说已被用户逻辑删除时可用，会把该小说的全部关联数据
 * （章节、人物、关系、事件、势力、物品、总纲、大纲、分析、封面文件、助手会话）一并彻底删除，
 * 用于回收存储与清理垃圾数据，<b>不可恢复</b>。</p>
 */
@Tag(name = "超管 - 小说管理", description = "全平台小说列表、目录与正文；已逻辑删除小说的物理清除")
@RestController
@RequestMapping("/api/admin/novels")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@RequiredArgsConstructor
public class AdminNovelController {

    private final AdminNovelService adminNovelService;

    @Operation(summary = "小说列表",
            description = "跨用户分页查询；keyword 匹配书名 / 创建账号 / 昵称，state 为 CREATING / COMPLETED / ABANDONED，"
                    + "scope 为 NORMAL（正常，默认）/ DELETED（已逻辑删除）/ ALL（全部），"
                    + "支持按创建时间范围筛选与多种排序")
    @GetMapping
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 600, windowSeconds = 60)
    public R<PageResult<AdminNovelVO>> page(AdminNovelQuery query) {
        return R.ok(adminNovelService.page(query));
    }

    @Operation(summary = "小说详情与目录", description = "返回小说基本信息与章节目录（不含正文）")
    @GetMapping("/{id}")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 600, windowSeconds = 60)
    public R<AdminNovelDetailVO> detail(@PathVariable Long id) {
        return R.ok(adminNovelService.detail(id));
    }

    @Operation(summary = "查看章节正文")
    @GetMapping("/{id}/chapters/{chapterId}")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 600, windowSeconds = 60)
    public R<AdminChapterVO> chapter(@PathVariable Long id, @PathVariable Long chapterId) {
        return R.ok(adminNovelService.chapter(id, chapterId));
    }

    @Operation(summary = "物理清除已删除的小说",
            description = "仅对已逻辑删除（用户在书架删除）的小说有效；会一并彻底删除章节、人物、关系、事件、势力、物品、"
                    + "总纲、大纲、分析、封面（含图片文件）与创作助手会话等全部关联数据，不可恢复")
    @DeleteMapping("/{id}")
    @Audit(action = "ADMIN_NOVEL_PURGE", targetType = "NOVEL", targetIdIndex = 0)
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 60, windowSeconds = 60)
    public R<AdminPurgeResultVO> purge(@PathVariable Long id) {
        return R.ok(adminNovelService.purge(id));
    }

    @Operation(summary = "批量物理清除已删除的小说", description = "单次最多 50 本；逐本返回结果，单本失败不影响其余")
    @PostMapping("/purge")
    @Audit(action = "ADMIN_NOVEL_PURGE_BATCH", targetType = "NOVEL")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 30, windowSeconds = 60)
    public R<List<AdminPurgeResultVO>> purgeBatch(@Valid @RequestBody AdminPurgeRequest request) {
        return R.ok(adminNovelService.purgeBatch(request.getIds()));
    }
}
