package com.storyforge.modules.autowrite.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.storyforge.common.context.DataPermissionContext;
import com.storyforge.common.context.UserContext;
import com.storyforge.modules.autowrite.entity.AutoWriteRunEntity;
import com.storyforge.modules.autowrite.entity.AutoWriteTaskEntity;
import com.storyforge.modules.autowrite.mapper.AutoWriteRunMapper;
import com.storyforge.modules.autowrite.mapper.AutoWriteTaskMapper;
import com.storyforge.modules.autowrite.support.AutoWriteGuard;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.scheduling.support.SimpleTriggerContext;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 自动写书调度器：把每个启用中的任务按 cron 注册到线程池调度器，到点异步跑一轮。
 *
 * <p>职责边界：调度器只管「什么时候跑」——注册/注销、防重入、跑完后的计数与熔断。
 * 真正的一轮逻辑在 {@link AutoWriteRunner}。</p>
 *
 * <p>启动时把库里残留的 {@code RUNNING} 重置为 {@code IDLE}（进程重启等于中断），
 * 再按 cron 注册所有启用任务；任务增删改时动态注销再注册，无需重启。</p>
 */
@Slf4j
@Component
public class AutoWriteScheduler {

    /** 连续失败达到这个周期数就停止任务 */
    public static final int MAX_CONSECUTIVE_FAILURES = 3;

    private static final ZoneId ZONE = ZoneId.systemDefault();

    private final AutoWriteTaskMapper taskMapper;
    private final AutoWriteRunMapper runMapper;
    private final AutoWriteRunner runner;
    private final AutoWriteGuard guard;
    private final TaskScheduler taskScheduler;
    private final TaskExecutor runExecutor;

    /** taskId → 已注册的 cron 句柄 */
    private final Map<Long, ScheduledFuture<?>> futures = new ConcurrentHashMap<>();
    /** taskId → cron 触发器（用于算下次触发时间） */
    private final Map<Long, CronTrigger> triggers = new ConcurrentHashMap<>();
    /** 正在跑的任务集合：上一轮未完成则跳过本次 */
    private final Map<Long, AtomicBoolean> running = new ConcurrentHashMap<>();

    public AutoWriteScheduler(AutoWriteTaskMapper taskMapper,
                              AutoWriteRunMapper runMapper,
                              AutoWriteRunner runner,
                              AutoWriteGuard guard,
                              TaskScheduler taskScheduler,
                              @Qualifier("autoWriteExecutor") TaskExecutor runExecutor) {
        this.taskMapper = taskMapper;
        this.runMapper = runMapper;
        this.runner = runner;
        this.guard = guard;
        this.taskScheduler = taskScheduler;
        this.runExecutor = runExecutor;
    }

    /* ------------------------------------------------------------------ */
    /* 启动与关闭                                                          */
    /* ------------------------------------------------------------------ */

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        List<AutoWriteTaskEntity> tasks = DataPermissionContext.withoutIsolation(
                () -> taskMapper.selectList(Wrappers.<AutoWriteTaskEntity>lambdaQuery()));
        int reset = 0;
        int registered = 0;
        for (AutoWriteTaskEntity task : tasks) {
            if (AutoWriteTaskEntity.STATUS_RUNNING.equals(task.getStatus())) {
                patchStatus(task.getId(), AutoWriteTaskEntity.STATUS_IDLE, null);
                task.setStatus(AutoWriteTaskEntity.STATUS_IDLE);
                reset++;
            }
            if (shouldSchedule(task)) {
                register(task);
                registered++;
            }
        }
        if (reset > 0 || registered > 0) {
            log.info("[auto-write] scheduler ready: resetRunning={} registered={}", reset, registered);
        }
    }

    @PreDestroy
    public void shutdown() {
        futures.values().forEach(future -> future.cancel(false));
        futures.clear();
        triggers.clear();
    }

    /* ------------------------------------------------------------------ */
    /* 注册 / 注销                                                         */
    /* ------------------------------------------------------------------ */

    /** 配置变更后调用：按最新状态重新注册或注销。 */
    public void reschedule(AutoWriteTaskEntity task) {
        if (task == null || task.getId() == null) {
            return;
        }
        unregister(task.getId());
        if (shouldSchedule(task)) {
            register(task);
        }
    }

    public void unregister(Long taskId) {
        ScheduledFuture<?> future = futures.remove(taskId);
        if (future != null) {
            future.cancel(false);
        }
        triggers.remove(taskId);
    }

    private void register(AutoWriteTaskEntity task) {
        String cron = task.getCronExpr();
        if (cron == null || cron.isBlank()) {
            return;
        }
        try {
            CronTrigger trigger = new CronTrigger(cron, ZONE);
            Long taskId = task.getId();
            ScheduledFuture<?> future = taskScheduler.schedule(() -> fire(taskId), trigger);
            if (future != null) {
                futures.put(taskId, future);
                triggers.put(taskId, trigger);
                log.info("[auto-write] registered taskId={} novelId={} cron={}", taskId, task.getNovelId(), cron);
            }
        } catch (RuntimeException ex) {
            log.warn("[auto-write] register failed taskId={} cron={} reason={}", task.getId(), cron, ex.toString());
        }
    }

    /** 该任务此刻是否该挂到调度器上。 */
    private boolean shouldSchedule(AutoWriteTaskEntity task) {
        if (!task.enabledOn()) {
            return false;
        }
        String status = task.getStatus();
        return AutoWriteTaskEntity.STATUS_IDLE.equals(status)
                || AutoWriteTaskEntity.STATUS_RUNNING.equals(status);
    }

    /** 下次触发时间；未注册返回 null。 */
    public LocalDateTime nextRunAt(Long taskId) {
        CronTrigger trigger = triggers.get(taskId);
        if (trigger == null) {
            return null;
        }
        Date next = trigger.nextExecutionTime(new SimpleTriggerContext());
        return next == null ? null : LocalDateTime.ofInstant(next.toInstant(), ZONE);
    }

    /* ------------------------------------------------------------------ */
    /* 触发                                                                */
    /* ------------------------------------------------------------------ */

    /** 手动「立即执行一轮」；返回是否被接受（上一个还没跑完则拒绝）。 */
    public boolean runNow(Long taskId) {
        return fire(taskId);
    }

    /**
     * 触发一轮。
     *
     * @return 是否真正提交执行；上一轮未跑完返回 false（并记一条 SKIPPED 流水）
     */
    private boolean fire(Long taskId) {
        AutoWriteTaskEntity task = DataPermissionContext.withoutIsolation(
                () -> taskMapper.selectById(taskId));
        if (task == null) {
            unregister(taskId);
            return false;
        }
        if (!shouldSchedule(task)) {
            // 已被暂停 / 停止 / 完成：注销并退出
            unregister(taskId);
            return false;
        }
        AtomicBoolean flag = running.computeIfAbsent(taskId, key -> new AtomicBoolean(false));
        if (!flag.compareAndSet(false, true)) {
            writeSkipped(task);
            log.info("[auto-write] skip taskId={}：上一轮还没跑完", taskId);
            return false;
        }
        // 置为 RUNNING（此时起该书进入只读锁）
        patchStatus(taskId, AutoWriteTaskEntity.STATUS_RUNNING, null);
        task.setStatus(AutoWriteTaskEntity.STATUS_RUNNING);

        UserContext context = new UserContext(task.getUserId(), null, "USER");
        runExecutor.execute(() -> {
            AutoWriteRunner.RunResult result;
            try {
                guard.markBypass();
                result = runner.execute(task, context);
            } catch (RuntimeException ex) {
                log.warn("[auto-write] run crashed taskId={} reason={}", taskId, ex.toString());
                result = null;
            } finally {
                guard.clearBypass();
                flag.set(false);
            }
            afterRun(task, result);
        });
        return true;
    }

    /** 一轮结束后的记账：轮次、生成章节数、连续失败计数与熔断。 */
    private void afterRun(AutoWriteTaskEntity task, AutoWriteRunner.RunResult result) {
        Long taskId = task.getId();
        AutoWriteTaskEntity latest = DataPermissionContext.withoutIsolation(
                () -> taskMapper.selectById(taskId));
        if (latest == null) {
            return;
        }
        int consecutive = latest.getConsecutiveFailures() == null ? 0 : latest.getConsecutiveFailures();
        int generated = (latest.getGeneratedChapters() == null ? 0 : latest.getGeneratedChapters())
                + (result == null ? 0 : result.chaptersWritten());
        int totalRuns = (latest.getTotalRuns() == null ? 0 : latest.getTotalRuns()) + 1;

        boolean ok = result != null && result.ok();
        boolean finished = latest.getStatus() != null
                && (AutoWriteTaskEntity.STATUS_COMPLETED.equals(latest.getStatus())
                || AutoWriteTaskEntity.STATUS_PAUSED.equals(latest.getStatus())
                || AutoWriteTaskEntity.STATUS_STOPPED.equals(latest.getStatus()));

        AutoWriteTaskEntity patch = new AutoWriteTaskEntity();
        patch.setId(taskId);
        patch.setGeneratedChapters(generated);
        patch.setTotalRuns(totalRuns);
        patch.setLastRunAt(LocalDateTime.now());
        patch.setLastResult(result == null ? "执行异常，请查看日志" : result.message());

        if (ok || finished) {
            patch.setConsecutiveFailures(0);
        } else {
            consecutive++;
            patch.setConsecutiveFailures(consecutive);
            if (consecutive >= MAX_CONSECUTIVE_FAILURES) {
                patch.setStatus(AutoWriteTaskEntity.STATUS_PAUSED);
                patch.setStoppedReason("连续 " + consecutive + " 个周期执行失败，已停下等待人工介入");
            } else {
                patch.setStatus(AutoWriteTaskEntity.STATUS_IDLE);
            }
        }
        if (ok && !finished) {
            patch.setStatus(AutoWriteTaskEntity.STATUS_IDLE);
        }
        DataPermissionContext.withoutIsolation(() -> {
            taskMapper.updateById(patch);
            return null;
        });

        // 状态不再是「空闲/运行中」就注销
        AutoWriteTaskEntity after = DataPermissionContext.withoutIsolation(
                () -> taskMapper.selectById(taskId));
        if (after == null || !shouldSchedule(after)) {
            unregister(taskId);
        }
        log.info("[auto-write] run done taskId={} ok={} written={} message={}",
                taskId, ok, result == null ? 0 : result.chaptersWritten(),
                result == null ? "n/a" : result.message());
    }

    /* ------------------------------------------------------------------ */
    /* 内部                                                                */
    /* ------------------------------------------------------------------ */

    private void patchStatus(Long taskId, String status, String reason) {
        AutoWriteTaskEntity patch = new AutoWriteTaskEntity();
        patch.setId(taskId);
        patch.setStatus(status);
        if (reason != null) {
            patch.setStoppedReason(reason);
        }
        DataPermissionContext.withoutIsolation(() -> {
            taskMapper.updateById(patch);
            return null;
        });
    }

    private void writeSkipped(AutoWriteTaskEntity task) {
        try {
            AutoWriteRunEntity run = new AutoWriteRunEntity();
            run.setUserId(task.getUserId());
            run.setNovelId(task.getNovelId());
            run.setTaskId(task.getId());
            run.setStartedAt(LocalDateTime.now());
            run.setFinishedAt(LocalDateTime.now());
            run.setStatus(AutoWriteRunEntity.STATUS_SKIPPED);
            run.setChaptersWritten(0);
            run.setOutlinesCreated(0);
            run.setMasterOutlinesCreated(0);
            run.setMessage("上一轮还没跑完，本次跳过");
            run.setDeleted(0);
            run.setVersion(0);
            DataPermissionContext.withoutIsolation(() -> {
                runMapper.insert(run);
                return null;
            });
        } catch (RuntimeException ex) {
            log.warn("[auto-write] write skipped run failed taskId={} reason={}", task.getId(), ex.toString());
        }
    }
}
