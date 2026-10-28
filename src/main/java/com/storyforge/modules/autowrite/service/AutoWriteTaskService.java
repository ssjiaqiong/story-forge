package com.storyforge.modules.autowrite.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.storyforge.common.api.ErrorCode;
import com.storyforge.common.context.UserContextHolder;
import com.storyforge.common.exception.BizException;
import com.storyforge.modules.autowrite.dto.AutoWriteRunVO;
import com.storyforge.modules.autowrite.dto.AutoWriteStatusVO;
import com.storyforge.modules.autowrite.dto.AutoWriteTaskRequest;
import com.storyforge.modules.autowrite.dto.AutoWriteTaskVO;
import com.storyforge.modules.autowrite.entity.AutoWriteRunEntity;
import com.storyforge.modules.autowrite.entity.AutoWriteTaskEntity;
import com.storyforge.modules.autowrite.mapper.AutoWriteRunMapper;
import com.storyforge.modules.autowrite.mapper.AutoWriteTaskMapper;
import com.storyforge.modules.novel.entity.NovelEntity;
import com.storyforge.modules.novel.mapper.NovelMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 自动写书任务配置的读写与校验：一本书一个任务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AutoWriteTaskService {

    private static final int DEFAULT_PER_RUN = 3;
    private static final int DEFAULT_HARD_LIMIT = 5;
    private static final int DEFAULT_TARGET_WORDS = 3000;
    private static final String DEFAULT_CRON = "0 0 * * * ?";

    private final AutoWriteTaskMapper taskMapper;
    private final AutoWriteRunMapper runMapper;
    private final NovelMapper novelMapper;
    private final AutoWriteScheduler scheduler;

    /* ------------------------------------------------------------------ */
    /* 查询                                                                */
    /* ------------------------------------------------------------------ */

    /** 取某本书的任务配置；没有则返回 null。 */
    public AutoWriteTaskVO getByNovel(Long novelId) {
        requireNovel(novelId);
        AutoWriteTaskEntity task = taskOfNovel(novelId);
        return task == null ? null : toVO(task);
    }

    public AutoWriteStatusVO status(Long novelId) {
        AutoWriteTaskEntity task = novelId == null ? null : taskOfNovel(novelId);
        if (task == null) {
            return AutoWriteStatusVO.none();
        }
        AutoWriteTaskVO vo = toVO(task);
        return new AutoWriteStatusVO(true, vo.autoWriting(), vo.status(), vo.enabled(),
                vo.generatedChapters(), vo.lastResult(), vo.lastRunAt(), vo.nextRunAt(), vo.stoppedReason());
    }

    public List<AutoWriteRunVO> listRuns(Long taskId) {
        requireTask(taskId);
        return runMapper.selectList(Wrappers.<AutoWriteRunEntity>lambdaQuery()
                        .eq(AutoWriteRunEntity::getTaskId, taskId)
                        .orderByDesc(AutoWriteRunEntity::getStartedAt)
                        .last("limit 50"))
                .stream().map(AutoWriteRunVO::of).toList();
    }

    /* ------------------------------------------------------------------ */
    /* 写操作                                                              */
    /* ------------------------------------------------------------------ */

    /** 新建 / 覆盖配置（一本书一个）。 */
    @Transactional(rollbackFor = Exception.class)
    public AutoWriteTaskVO save(Long novelId, AutoWriteTaskRequest request) {
        NovelEntity novel = requireNovel(novelId);
        AutoWriteTaskRequest safe = request == null ? new AutoWriteTaskRequest() : request;

        String cron = hasText(safe.getCronExpr()) ? safe.getCronExpr().trim() : DEFAULT_CRON;
        if (!CronExpression.isValidExpression(cron)) {
            throw BizException.of(ErrorCode.AUTO_WRITE_CRON_INVALID);
        }
        int perRun = safe.getChaptersPerRun() == null ? DEFAULT_PER_RUN : safe.getChaptersPerRun();
        if (perRun < 1 || perRun > 20) {
            throw BizException.of(ErrorCode.AUTO_WRITE_CONFIG_INVALID, "每次生成的章节数需在 1~20 之间");
        }
        int targetWords = safe.getTargetWordsPerChapter() == null
                ? DEFAULT_TARGET_WORDS : safe.getTargetWordsPerChapter();
        if (targetWords < 300 || targetWords > 6000) {
            throw BizException.of(ErrorCode.AUTO_WRITE_CONFIG_INVALID, "每章目标字数需在 300~6000 之间");
        }
        int hardLimit = safe.getHardIssueLimit() == null ? DEFAULT_HARD_LIMIT : safe.getHardIssueLimit();
        if (hardLimit < 0) {
            throw BizException.of(ErrorCode.AUTO_WRITE_CONFIG_INVALID, "硬伤熔断阈值不能为负数");
        }
        String endMode = normalizeEndMode(safe.getEndMode());
        int endChapterNo = safe.getEndChapterNo() == null ? 0 : safe.getEndChapterNo();
        if (AutoWriteTaskEntity.END_MODE_UNTIL_END.equals(endMode)) {
            int plannedWords = novel.getPlannedWords() == null ? 0 : novel.getPlannedWords();
            if (plannedWords <= 0) {
                throw BizException.of(ErrorCode.AUTO_WRITE_CONFIG_INVALID,
                        "「直到完结」需要先在「基本信息」里填写预计完结字数");
            }
        } else if (endChapterNo <= 0) {
            throw BizException.of(ErrorCode.AUTO_WRITE_CONFIG_INVALID, "「写到指定章数」需要填写目标章数");
        }
        boolean enabled = safe.getEnabled() == null || safe.getEnabled();

        AutoWriteTaskEntity existing = taskOfNovel(novelId);
        AutoWriteTaskEntity entity;
        if (existing == null) {
            entity = new AutoWriteTaskEntity();
            entity.setUserId(novel.getUserId() == null ? UserContextHolder.requireUserId() : novel.getUserId());
            entity.setNovelId(novelId);
            entity.setStatus(enabled ? AutoWriteTaskEntity.STATUS_IDLE : AutoWriteTaskEntity.STATUS_STOPPED);
            entity.setClosingTriggered(0);
            entity.setConsecutiveFailures(0);
            entity.setTotalRuns(0);
            entity.setGeneratedChapters(0);
            entity.setDeleted(0);
            entity.setVersion(0);
        } else {
            entity = existing;
            if (enabled) {
                // 重新启用：清掉停止原因，回到空闲
                entity.setStatus(AutoWriteTaskEntity.STATUS_IDLE);
                entity.setStoppedReason(null);
                entity.setConsecutiveFailures(0);
            } else {
                entity.setStatus(AutoWriteTaskEntity.STATUS_STOPPED);
            }
        }
        entity.setEnabled(enabled ? 1 : 0);
        entity.setCronExpr(cron);
        entity.setChaptersPerRun(perRun);
        entity.setEndMode(endMode);
        entity.setEndChapterNo(endChapterNo);
        entity.setHardIssueLimit(hardLimit);
        entity.setConsistencyAi(safe.getConsistencyAi() == null || safe.getConsistencyAi() ? 1 : 0);
        entity.setTargetWordsPerChapter(targetWords);

        if (existing == null) {
            taskMapper.insert(entity);
        } else {
            taskMapper.updateById(entity);
            if (enabled) {
                // updateById 会跳过 null 字段，停止原因必须显式清
                clearStoppedReason(entity.getId());
            }
        }
        log.info("[auto-write] saved taskId={} novelId={} cron={} perRun={} mode={} enabled={}",
                entity.getId(), novelId, cron, perRun, endMode, enabled);
        scheduler.reschedule(entity);
        return toVO(entity);
    }

    /** 暂停 / 启用：关掉即解锁编辑。 */
    @Transactional(rollbackFor = Exception.class)
    public AutoWriteTaskVO setEnabled(Long taskId, boolean enabled) {
        AutoWriteTaskEntity task = requireTask(taskId);
        Long userId = UserContextHolder.requireUserId();
        LambdaUpdateWrapper<AutoWriteTaskEntity> uw = Wrappers.<AutoWriteTaskEntity>lambdaUpdate()
                .eq(AutoWriteTaskEntity::getId, taskId)
                .set(AutoWriteTaskEntity::getEnabled, enabled ? 1 : 0)
                .set(AutoWriteTaskEntity::getUpdatedAt, LocalDateTime.now())
                .set(AutoWriteTaskEntity::getUpdatedBy, userId);
        if (enabled) {
            // 重新启用：停止原因置空（updateById 跳 null，只能写 SQL 片段）、回到空闲、失败计数清零
            uw.setSql("stopped_reason = null")
                    .set(AutoWriteTaskEntity::getStatus, AutoWriteTaskEntity.STATUS_IDLE)
                    .set(AutoWriteTaskEntity::getConsecutiveFailures, 0);
        } else {
            uw.set(AutoWriteTaskEntity::getStatus, AutoWriteTaskEntity.STATUS_STOPPED)
                    .set(AutoWriteTaskEntity::getStoppedReason, "已手动停止，编辑已解锁");
        }
        taskMapper.update(null, uw);
        AutoWriteTaskEntity latest = taskMapper.selectById(taskId);
        scheduler.reschedule(latest);
        log.info("[auto-write] enabled={} taskId={}", enabled, taskId);
        return toVO(latest);
    }

    /** 显式清空停止原因（MyBatis-Plus 的 updateById 对 null 字段不生成 SET）。 */
    private void clearStoppedReason(Long taskId) {
        taskMapper.update(null, Wrappers.<AutoWriteTaskEntity>lambdaUpdate()
                .eq(AutoWriteTaskEntity::getId, taskId)
                .setSql("stopped_reason = null")
                .set(AutoWriteTaskEntity::getUpdatedAt, LocalDateTime.now()));
    }

    /** 立即执行一轮；上一个还没跑完时拒绝。 */
    public boolean runNow(Long taskId) {
        AutoWriteTaskEntity task = requireTask(taskId);
        return scheduler.runNow(task.getId());
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long taskId) {
        AutoWriteTaskEntity task = requireTask(taskId);
        scheduler.unregister(taskId);
        taskMapper.deleteById(taskId);
        runMapper.delete(Wrappers.<AutoWriteRunEntity>lambdaQuery()
                .eq(AutoWriteRunEntity::getTaskId, taskId));
        log.info("[auto-write] deleted taskId={} novelId={}", taskId, task.getNovelId());
    }

    /* ------------------------------------------------------------------ */
    /* 内部                                                                */
    /* ------------------------------------------------------------------ */

    private AutoWriteTaskVO toVO(AutoWriteTaskEntity task) {
        LocalDateTime next = scheduler.nextRunAt(task.getId());
        return AutoWriteTaskVO.of(task, next);
    }

    private AutoWriteTaskEntity taskOfNovel(Long novelId) {
        return taskMapper.selectOne(Wrappers.<AutoWriteTaskEntity>lambdaQuery()
                .eq(AutoWriteTaskEntity::getNovelId, novelId)
                .last("limit 1"));
    }

    private NovelEntity requireNovel(Long novelId) {
        NovelEntity novel = novelId == null ? null : novelMapper.selectById(novelId);
        if (novel == null) {
            throw BizException.of(ErrorCode.NOVEL_NOT_FOUND);
        }
        return novel;
    }

    private AutoWriteTaskEntity requireTask(Long taskId) {
        AutoWriteTaskEntity task = taskId == null ? null : taskMapper.selectById(taskId);
        if (task == null) {
            throw BizException.of(ErrorCode.AUTO_WRITE_TASK_NOT_FOUND);
        }
        return task;
    }

    private String normalizeEndMode(String raw) {
        if (raw != null && AutoWriteTaskEntity.END_MODE_UNTIL_END.equalsIgnoreCase(raw.trim())) {
            return AutoWriteTaskEntity.END_MODE_UNTIL_END;
        }
        return AutoWriteTaskEntity.END_MODE_CHAPTERS;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
