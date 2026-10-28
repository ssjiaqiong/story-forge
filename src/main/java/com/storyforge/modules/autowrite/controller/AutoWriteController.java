package com.storyforge.modules.autowrite.controller;

import com.storyforge.common.api.R;
import com.storyforge.common.ratelimit.RateLimit;
import com.storyforge.modules.autowrite.dto.AutoWriteRunVO;
import com.storyforge.modules.autowrite.dto.AutoWriteStatusVO;
import com.storyforge.modules.autowrite.dto.AutoWriteTaskRequest;
import com.storyforge.modules.autowrite.dto.AutoWriteTaskVO;
import com.storyforge.modules.autowrite.service.AutoWriteTaskService;
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
 * 自动写书：给一本书配一个定时任务，到点自动从总纲推进到正文。
 *
 * <p>一本书只能有一个任务（数据库唯一约束兜底）；任务处于运行态时该书进入只读，
 * 需先停止才能继续在别处编辑。</p>
 */
@Tag(name = "自动写书", description = "定时自动推进一本书的创作")
@RestController
@RequestMapping("/api/auto-write")
@RequiredArgsConstructor
public class AutoWriteController {

    private final AutoWriteTaskService taskService;

    @Operation(summary = "取某本书的自动写书任务", description = "没有配置时返回 null")
    @GetMapping("/novels/{novelId}")
    public R<AutoWriteTaskVO> getByNovel(@PathVariable Long novelId) {
        return R.ok(taskService.getByNovel(novelId));
    }

    @Operation(summary = "新建 / 覆盖自动写书任务", description = "一本书一个，已存在则更新")
    @PostMapping("/novels/{novelId}")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 60, windowSeconds = 60)
    public R<AutoWriteTaskVO> save(@PathVariable Long novelId,
                                   @Valid @RequestBody(required = false) AutoWriteTaskRequest request) {
        return R.ok(taskService.save(novelId, request));
    }

    @Operation(summary = "暂停 / 启用任务", description = "暂停后该书解锁编辑；启用后回到空闲并按 cron 继续")
    @PutMapping("/{id}/enabled")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 60, windowSeconds = 60)
    public R<AutoWriteTaskVO> setEnabled(@PathVariable Long id,
                                         @RequestParam boolean enabled) {
        return R.ok(taskService.setEnabled(id, enabled));
    }

    @Operation(summary = "立即执行一轮", description = "异步执行；上一轮未跑完时返回 accepted=false")
    @PostMapping("/{id}/run-now")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 30, windowSeconds = 60)
    public R<RunNowResult> runNow(@PathVariable Long id) {
        boolean accepted = taskService.runNow(id);
        return R.ok(new RunNowResult(accepted));
    }

    @Operation(summary = "删除任务")
    @DeleteMapping("/{id}")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 30, windowSeconds = 60)
    public R<Void> delete(@PathVariable Long id) {
        taskService.delete(id);
        return R.ok();
    }

    @Operation(summary = "执行流水", description = "最近 50 轮")
    @GetMapping("/{id}/runs")
    public R<List<AutoWriteRunVO>> runs(@PathVariable Long id) {
        return R.ok(taskService.listRuns(id));
    }

    @Operation(summary = "轻量运行态", description = "工作台横幅轮询用：是否自动生成中、上次结果、下次触发时间")
    @GetMapping("/novels/{novelId}/status")
    public R<AutoWriteStatusVO> status(@PathVariable Long novelId) {
        return R.ok(taskService.status(novelId));
    }

    /** 立即执行的回执。 */
    public record RunNowResult(boolean accepted) {
    }
}
