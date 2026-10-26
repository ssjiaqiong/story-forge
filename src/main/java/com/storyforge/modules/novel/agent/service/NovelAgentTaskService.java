package com.storyforge.modules.novel.agent.service;

import com.storyforge.common.api.ErrorCode;
import com.storyforge.common.context.UserContext;
import com.storyforge.common.context.UserContextHolder;
import com.storyforge.common.exception.BizException;
import com.storyforge.modules.novel.agent.dto.AgentOperationVO;
import com.storyforge.modules.novel.agent.dto.AgentSendRequest;
import com.storyforge.modules.novel.agent.dto.AgentSendResult;
import com.storyforge.modules.novel.agent.dto.AgentTaskVO;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 智能助手「多步执行」的异步任务编排。
 *
 * <p>智能助手一条指令可能要反复调用 AI 多轮（改世界观、删人物、建关系…逐轮推进），单轮
 * 又要读入完整小说快照、输出结构化操作，耗时常常超过前端 HTTP 超时。这里把整个多步循环
 * 放进后台线程，前端发 {@code POST /tasks} 拿到 taskId 后轮询 {@code GET /tasks/{id}}，
 * 进度里带「当前第几轮 + 已执行的操作摘要」，让用户实时感知中间过程，同时规避超时。</p>
 *
 * <p>任务只存内存（单机部署），进程重启即丢；结束的任务保留 30 分钟供前端补偿拉取后惰性清理。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NovelAgentTaskService {

    private static final int TURN_TOTAL = 6;
    private static final long TASK_TTL_MS = 30 * 60 * 1000L;
    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_SUCCEEDED = "SUCCEEDED";
    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_CANCELLED = "CANCELLED";

    private final NovelAgentService agentService;

    private final Map<String, Task> tasks = new ConcurrentHashMap<>();
    private final AtomicInteger threadSeq = new AtomicInteger();
    private final ExecutorService executor = Executors.newFixedThreadPool(3, runnable -> {
        Thread thread = new Thread(runnable, "novel-agent-" + threadSeq.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });

    /** 启动智能助手多步任务。 */
    public AgentTaskVO start(Long novelId, AgentSendRequest request) {
        purgeExpired();
        UserContext context = UserContextHolder.require();
        Task task = new Task(UUID.randomUUID().toString().replace("-", ""), context.userId(), novelId);
        tasks.put(task.id, task);
        executor.submit(() -> run(task, novelId, request, context));
        log.info("[novel-agent-task] started taskId={} novelId={}", task.id, novelId);
        return toVO(task);
    }

    /**
     * 查询某小说当前正在执行的任务（用于前端刷新后恢复轮询），无则返回 null。
     * 仅返回发起人本人的 RUNNING 任务。
     */
    public AgentTaskVO findRunning(Long novelId, Long userId) {
        for (Task task : tasks.values()) {
            if (task.userId.equals(userId) && novelId.equals(task.novelId) && STATUS_RUNNING.equals(task.status)) {
                return toVO(task);
            }
        }
        return null;
    }

    /** 查询任务进度；仅任务发起人可查。 */
    public AgentTaskVO status(String taskId, Long userId) {
        Task task = taskId == null ? null : tasks.get(taskId);
        if (task == null || !task.userId.equals(userId)) {
            throw BizException.of(ErrorCode.TASK_NOT_FOUND);
        }
        return toVO(task);
    }

    /** 取消正在执行的任务；仅任务发起人可取消。 */
    public AgentTaskVO cancel(String taskId, Long userId) {
        Task task = taskId == null ? null : tasks.get(taskId);
        if (task == null || !task.userId.equals(userId)) {
            throw BizException.of(ErrorCode.TASK_NOT_FOUND);
        }
        task.cancel();
        log.info("[novel-agent-task] cancel requested taskId={}", task.id);
        return toVO(task);
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    /* ------------------------------------------------------------------ */

    private void run(Task task, Long novelId, AgentSendRequest request, UserContext context) {
        UserContextHolder.set(context);
        try {
            task.step(1, "第 1 轮：正在分析你的指令");
            AgentSendResult result = agentService.sendWithSink(novelId, request,
                    (turnNo, reply, operations) -> {
                        task.onTurn(turnNo, reply, new ArrayList<>(operations == null ? List.of() : operations));
                    },
                    task::isCancelled);
            task.succeed(result);
            log.info("[novel-agent-task] succeeded taskId={} novelId={}", task.id, novelId);
        } catch (NovelAgentService.AgentCancelledException ex) {
            task.cancelled();
            log.info("[novel-agent-task] cancelled taskId={} novelId={}", task.id, novelId);
        } catch (BizException ex) {
            task.fail(ex.getMessage());
            log.warn("[novel-agent-task] failed taskId={} novelId={}: {}", task.id, novelId, ex.getMessage());
        } catch (RuntimeException ex) {
            task.fail(hasText(ex.getMessage()) ? ex.getMessage() : "AI 调用失败");
            log.warn("[novel-agent-task] failed taskId={} novelId={}: {}", task.id, novelId, ex.toString());
        } finally {
            UserContextHolder.clear();
        }
    }

    private AgentTaskVO toVO(Task task) {
        AgentTaskVO vo = new AgentTaskVO();
        vo.setTaskId(task.id);
        vo.setStatus(task.status);
        vo.setPercent(task.percent);
        vo.setTurnNo(Math.min(task.turnNo, TURN_TOTAL));
        vo.setTurnTotal(TURN_TOTAL);
        vo.setStepLabel(task.stepLabel);
        vo.setOperations(task.operations);
        vo.setReply(task.reply);
        vo.setErrorMessage(task.errorMessage);
        vo.setResult(task.result);
        return vo;
    }

    private void purgeExpired() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Task>> iterator = tasks.entrySet().iterator();
        while (iterator.hasNext()) {
            Task task = iterator.next().getValue();
            if (!STATUS_RUNNING.equals(task.status) && now - task.updatedAt > TASK_TTL_MS) {
                iterator.remove();
            }
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /** 内存任务快照；字段用 volatile 保证轮询能看到最新进度。 */
    private static final class Task {

        private final String id;
        private final Long userId;
        private final Long novelId;
        private volatile String status = STATUS_RUNNING;
        private volatile boolean cancelled = false;
        private volatile int percent = 5;
        private volatile int turnNo = 0;
        private volatile String stepLabel = "正在准备";
        private volatile List<AgentOperationVO> operations = List.of();
        private volatile String reply;
        private volatile String errorMessage;
        private volatile AgentSendResult result;
        private volatile long updatedAt = System.currentTimeMillis();

        private Task(String id, Long userId, Long novelId) {
            this.id = id;
            this.userId = userId;
            this.novelId = novelId;
        }

        private void step(int turnNo, String label) {
            this.turnNo = turnNo;
            this.stepLabel = label;
            this.percent = Math.min(10 + turnNo * 15, 95);
            this.updatedAt = System.currentTimeMillis();
        }

        private void onTurn(int turnNo, String reply, List<AgentOperationVO> operations) {
            this.turnNo = turnNo;
            this.stepLabel = "第 " + turnNo + " 轮：已完成，继续处理剩余指令";
            this.operations = new ArrayList<>(operations);
            this.reply = reply;
            this.percent = Math.min(10 + turnNo * 15, 95);
            this.updatedAt = System.currentTimeMillis();
        }

        private void succeed(AgentSendResult result) {
            this.result = result;
            this.status = STATUS_SUCCEEDED;
            this.percent = 100;
            this.stepLabel = "已完成";
            this.reply = result.getAssistantMessage() == null ? null : result.getAssistantMessage().getContent();
            this.operations = result.getAssistantMessage() == null
                    ? this.operations : result.getAssistantMessage().getOperations();
            this.updatedAt = System.currentTimeMillis();
        }

        private void fail(String message) {
            this.status = STATUS_FAILED;
            this.errorMessage = message;
            this.stepLabel = "执行失败";
            this.updatedAt = System.currentTimeMillis();
        }

        private boolean isCancelled() {
            return this.cancelled;
        }

        private void cancel() {
            this.cancelled = true;
            this.stepLabel = "正在停止…";
            this.updatedAt = System.currentTimeMillis();
        }

        private void cancelled() {
            this.cancelled = true;
            this.status = STATUS_CANCELLED;
            this.stepLabel = "已停止";
            this.percent = 100;
            this.updatedAt = System.currentTimeMillis();
        }
    }
}
