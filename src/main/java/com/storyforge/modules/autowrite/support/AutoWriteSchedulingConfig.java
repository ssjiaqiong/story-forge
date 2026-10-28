package com.storyforge.modules.autowrite.support;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 自动写书的线程池与调度器。
 *
 * <p>两个池分工：{@code taskScheduler} 只负责按 cron 触发（顺带承接工程里已有的
 * {@code @Scheduled} 清理任务），{@code autoWriteExecutor} 专门跑一轮一轮的写书流水线。
 * 分开是为了让「一轮跑十几分钟」不把 cron 触发线程占死，别的书到点还能正常触发。</p>
 */
@Configuration
public class AutoWriteSchedulingConfig {

    /** cron 调度器：4 个线程，足够触发 + 承接其它 @Scheduled 清理任务。 */
    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("sf-sched-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    /** 写书执行池：一轮就是一次长任务，池小但要有余量给「立即执行」与多本书。 */
    @Bean("autoWriteExecutor")
    public ThreadPoolTaskExecutor autoWriteExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("sf-autowrite-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }
}
