package com.storyforge.modules.autowrite.dto;

import java.time.LocalDateTime;

/**
 * 轻量运行态：工作台横幅轮询用，避免每次都把整份配置拉回来。
 *
 * @param exists           该书是否配了自动写书任务
 * @param autoWriting      是否正在自动生成（锁编辑）
 * @param status           运行态
 * @param enabled          定时是否开启
 * @param generatedChapters 累计生成章节数
 * @param lastResult       上次结果摘要
 * @param lastRunAt        上次执行时间
 * @param nextRunAt        下次触发时间
 * @param stoppedReason    停止原因
 */
public record AutoWriteStatusVO(
        boolean exists,
        boolean autoWriting,
        String status,
        boolean enabled,
        int generatedChapters,
        String lastResult,
        LocalDateTime lastRunAt,
        LocalDateTime nextRunAt,
        String stoppedReason) {

    public static AutoWriteStatusVO none() {
        return new AutoWriteStatusVO(false, false, null, false, 0, null, null, null, null);
    }
}
