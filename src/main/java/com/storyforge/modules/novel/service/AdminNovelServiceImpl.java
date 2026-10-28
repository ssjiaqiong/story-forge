package com.storyforge.modules.novel.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.storyforge.common.api.ErrorCode;
import com.storyforge.common.api.PageResult;
import com.storyforge.common.context.DataPermissionContext;
import com.storyforge.common.exception.BizException;
import com.storyforge.infra.storage.CoverStorage;
import com.storyforge.modules.novel.dto.AdminChapterVO;
import com.storyforge.modules.novel.dto.AdminNovelDetailVO;
import com.storyforge.modules.novel.dto.AdminNovelQuery;
import com.storyforge.modules.novel.dto.AdminNovelVO;
import com.storyforge.modules.novel.dto.AdminPurgeResultVO;
import com.storyforge.modules.novel.mapper.AdminNovelMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 超管「小说管理」服务实现。
 *
 * <p>所有查询都包在 {@link DataPermissionContext#withoutIsolation} 里：这是平台唯一需要
 * 跨用户读取创作数据的入口，Controller 层的 {@code @PreAuthorize("hasRole('SUPER_ADMIN')")}
 * 保证不会对普通用户开放。</p>
 *
 * <p><b>物理删除</b>：只允许清除用户已逻辑删除（{@code t_novel.deleted = 1}）的小说，
 * 先按白名单逐表清掉所有 {@code novel_id} 关联数据，再删主表，最后删除封面图片文件。
 * 行删除放在一个事务里，文件删除放在事务提交之后，避免回滚时图片已被误删。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminNovelServiceImpl implements AdminNovelService {

    /** 运营三态 → 库内状态集合。 */
    private static final Map<String, List<String>> STATE_STATUSES = Map.of(
            "CREATING", List.of("DRAFT", "WRITING"),
            "COMPLETED", List.of("FINISHED"),
            "ABANDONED", List.of("ARCHIVED"));

    private static final Set<String> SORTS = Set.of("CREATED_DESC", "UPDATED_DESC", "WORDS_DESC", "CHAPTERS_DESC");

    private static final Set<String> SCOPES = Set.of("NORMAL", "DELETED", "ALL");

    /**
     * 物理删除时按 novel_id 清理的关联表白名单（顺序仅影响日志可读性）。
     *
     * <p><b>新增带 {@code novel_id} 的业务表时必须在此登记</b>，否则清除小说后会残留孤儿数据。
     * 该列表是代码内常量，是 XML 里 {@code ${table}} 的唯一来源，不接收任何外部输入。</p>
     */
    static final List<String> NOVEL_CHILD_TABLES = List.of(
            // 正文与分析
            "t_chapter_character",
            "t_chapter_event",
            "t_chapter_analysis",
            "t_change_history",
            "t_chapter",
            // 人物与设定
            "t_character_attribute",
            "t_character_inventory",
            "t_character_relation",
            "t_character",
            "t_setting_doc",
            // 事件 / 势力 / 物品
            "t_event_participant",
            "t_timeline_event",
            "t_faction_member",
            "t_faction",
            "t_item",
            // 总纲与封面
            "t_master_outline",
            "t_novel_cover",
            // 创作助手会话
            "t_novel_agent_message",
            "t_novel_agent_session",
            // 助手分步任务（步骤表只有 plan_id，走子查询单独删，见 purgeOne）
            "t_novel_agent_plan",
            // 正文历史、一致性台账、卷（后加的表，别漏在级联之外）
            "t_chapter_version",
            "t_consistency_issue",
            "t_volume");

    private final AdminNovelMapper adminNovelMapper;
    private final CoverStorage coverStorage;
    private final PlatformTransactionManager transactionManager;

    @Override
    public PageResult<AdminNovelVO> page(AdminNovelQuery query) {
        AdminNovelQuery condition = query == null ? new AdminNovelQuery() : query;
        condition.setKeyword(trimToNull(condition.getKeyword()));
        condition.setState(normalizeState(condition.getState()));
        condition.setSort(normalizeSort(condition.getSort()));
        condition.setScope(normalizeScope(condition.getScope()));

        List<String> statuses = condition.getState() == null ? null : STATE_STATUSES.get(condition.getState());
        Integer deletedFlag = deletedFlag(condition.getScope());
        long pageNo = condition.normalizedPage();
        long pageSize = condition.normalizedSize();
        Page<AdminNovelVO> page = new Page<>(pageNo, pageSize);

        IPage<AdminNovelVO> result = DataPermissionContext.withoutIsolation(
                () -> adminNovelMapper.selectAdminNovelPage(page, condition, statuses, deletedFlag));
        return PageResult.of(result.getRecords(), result.getTotal(), pageNo, pageSize);
    }

    @Override
    public AdminNovelDetailVO detail(Long novelId) {
        AdminNovelVO novel = DataPermissionContext.withoutIsolation(
                () -> adminNovelMapper.selectAdminNovelById(novelId));
        if (novel == null) {
            throw BizException.of(ErrorCode.NOVEL_NOT_FOUND);
        }
        // 小说被逻辑删除时，章节通常也一起被逻辑删除；超管仍需看到完整目录
        boolean includeDeleted = Integer.valueOf(1).equals(novel.getDeleted());
        List<AdminChapterVO> chapters = DataPermissionContext.withoutIsolation(
                () -> adminNovelMapper.selectAdminChapters(novelId, includeDeleted));

        AdminNovelDetailVO detail = new AdminNovelDetailVO();
        detail.setNovel(novel);
        detail.setChapters(chapters);
        return detail;
    }

    @Override
    public AdminChapterVO chapter(Long novelId, Long chapterId) {
        AdminChapterVO chapter = DataPermissionContext.withoutIsolation(
                () -> adminNovelMapper.selectAdminChapter(novelId, chapterId));
        if (chapter == null) {
            throw BizException.of(ErrorCode.CHAPTER_NOT_FOUND);
        }
        return chapter;
    }

    /* ---------------------------- 物理删除 ---------------------------- */

    @Override
    public AdminPurgeResultVO purge(Long novelId) {
        if (novelId == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "小说 ID 不能为空");
        }
        AdminNovelVO novel = DataPermissionContext.withoutIsolation(
                () -> adminNovelMapper.selectAdminNovelById(novelId));
        if (novel == null) {
            throw BizException.of(ErrorCode.NOVEL_NOT_FOUND);
        }
        if (!Integer.valueOf(1).equals(novel.getDeleted())) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "《" + novel.getTitle() + "》未被逻辑删除，不能物理清除；请先由用户在书架中删除");
        }

        AdminPurgeResultVO result = new AdminPurgeResultVO();
        result.setNovelId(novelId);
        result.setTitle(novel.getTitle());
        result.setUsername(novel.getUsername());

        // 行删除走一个事务；图片文件删除放在提交之后，避免回滚时图片已被误删
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.executeWithoutResult(status -> deleteRows(novelId, result));
        result.setCoversRemoved(coverStorage.deleteNovelDirectory(novel.getUserId(), novelId));

        log.warn("[admin-novel] purged novelId={} title={} userId={} username={} tables={} rows={} coversRemoved={}",
                novelId, novel.getTitle(), novel.getUserId(), novel.getUsername(),
                result.getTableRows(), result.getTotalRows(), result.isCoversRemoved());
        return result;
    }

    @Override
    public List<AdminPurgeResultVO> purgeBatch(List<Long> novelIds) {
        List<AdminPurgeResultVO> results = new ArrayList<>();
        if (novelIds == null || novelIds.isEmpty()) {
            return results;
        }
        for (Long novelId : novelIds) {
            if (novelId == null) {
                continue;
            }
            try {
                results.add(purge(novelId));
            } catch (BizException ex) {
                // 单本失败不影响其余，把原因回传给前端逐条展示
                AdminPurgeResultVO failed = new AdminPurgeResultVO();
                failed.setNovelId(novelId);
                failed.setSuccess(false);
                failed.setMessage(ex.getMessage());
                results.add(failed);
                log.warn("[admin-novel] purge skipped novelId={} reason={}", novelId, ex.getMessage());
            }
        }
        log.warn("[admin-novel] purge batch requested={} succeeded={} failed={}",
                novelIds.size(),
                results.stream().filter(AdminPurgeResultVO::isSuccess).count(),
                results.stream().filter(item -> !item.isSuccess()).count());
        return results;
    }

    /** 事务内逐表删除关联数据 + 主表。 */
    private void deleteRows(Long novelId, AdminPurgeResultVO result) {
        // 助手分步任务的步骤表只有 plan_id，没有 novel_id，白名单那套 ${table} WHERE novel_id 用不上，
        // 必须赶在计划表被删之前用子查询清掉，否则留下孤儿行。
        int stepRows = DataPermissionContext.withoutIsolation(
                () -> adminNovelMapper.deleteAgentPlanStepByNovelId(novelId));
        result.addRows("t_novel_agent_plan_step", stepRows);

        for (String table : NOVEL_CHILD_TABLES) {
            int rows = DataPermissionContext.withoutIsolation(
                    () -> adminNovelMapper.deleteByNovelId(table, novelId));
            result.addRows(table, rows);
        }
        int novelRows = DataPermissionContext.withoutIsolation(
                () -> adminNovelMapper.deleteNovelById(novelId));
        result.addRows("t_novel", novelRows);

        // 拆书记录不属于小说本体，只解除引用，保留用户历史
        int digestRows = DataPermissionContext.withoutIsolation(
                () -> adminNovelMapper.clearBookDigestNovel(novelId));
        result.addRows("t_book_digest", digestRows);
    }

    /* ------------------------------------------------------------------ */

    private String normalizeState(String raw) {
        String state = trimToNull(raw);
        if (state == null) {
            return null;
        }
        state = state.toUpperCase();
        if (!STATE_STATUSES.containsKey(state)) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "状态筛选非法，可选 CREATING / COMPLETED / ABANDONED");
        }
        return state;
    }

    private String normalizeSort(String raw) {
        String sort = trimToNull(raw);
        if (sort == null) {
            return "CREATED_DESC";
        }
        sort = sort.toUpperCase();
        if (!SORTS.contains(sort)) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "排序参数非法");
        }
        return sort;
    }

    private String normalizeScope(String raw) {
        String scope = trimToNull(raw);
        if (scope == null) {
            return "NORMAL";
        }
        scope = scope.toUpperCase();
        if (!SCOPES.contains(scope)) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "数据范围非法，可选 NORMAL / DELETED / ALL");
        }
        return scope;
    }

    /** 数据范围 → 库内 deleted 值；ALL 返回 null 表示不限。 */
    private Integer deletedFlag(String scope) {
        if ("ALL".equals(scope)) {
            return null;
        }
        return "DELETED".equals(scope) ? 1 : 0;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
