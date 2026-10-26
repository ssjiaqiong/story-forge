package com.storyforge.common.ratelimit;

import com.storyforge.config.RateLimitProperties;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内令牌桶注册表。
 *
 * <p>无 Redis 约束下按单实例部署实现；桶数量按「维度:主体:接口」增长，定时清理长期空闲的桶。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimiterRegistry {

    /** 空闲超过该时长（毫秒）的桶将被回收 */
    private static final long IDLE_TTL_MILLIS = 30 * 60 * 1000L;

    private final RateLimitProperties properties;

    private final Map<String, Holder> buckets = new ConcurrentHashMap<>();

    /**
     * 尝试消费一个令牌。
     *
     * @return {@code true} 表示放行；{@code false} 表示已触发限流
     */
    public boolean tryAcquire(String key, long limit, long windowSeconds) {
        if (!properties.isEnabled()) {
            return true;
        }
        Holder holder = buckets.compute(key, (k, existing) -> {
            if (existing == null || existing.limit != limit || existing.windowSeconds != windowSeconds) {
                Bandwidth bandwidth = Bandwidth.builder()
                        .capacity(Math.max(1L, limit))
                        .refillGreedy(Math.max(1L, limit), Duration.ofSeconds(Math.max(1L, windowSeconds)))
                        .build();
                return new Holder(Bucket.builder().addLimit(bandwidth).build(), limit, windowSeconds);
            }
            return existing;
        });
        holder.lastAccessMillis = System.currentTimeMillis();
        ConsumptionProbe probe = holder.bucket.tryConsumeAndReturnRemaining(1);
        if (!probe.isConsumed()) {
            log.warn("[ratelimit] key={} limit={}/{}s rejected, wait {} ms",
                    key, limit, windowSeconds, probe.getNanosToWaitForRefill() / 1_000_000);
        }
        return probe.isConsumed();
    }

    /** 每 10 分钟回收空闲桶，避免长跑内存增长。 */
    @Scheduled(fixedDelayString = "PT10M", initialDelayString = "PT10M")
    public void evictIdleBuckets() {
        long deadline = System.currentTimeMillis() - IDLE_TTL_MILLIS;
        int before = buckets.size();
        buckets.entrySet().removeIf(entry -> entry.getValue().lastAccessMillis < deadline);
        int removed = before - buckets.size();
        if (removed > 0) {
            log.debug("[ratelimit] evicted {} idle buckets, remaining {}", removed, buckets.size());
        }
    }

    private static final class Holder {
        private final Bucket bucket;
        private final long limit;
        private final long windowSeconds;
        private volatile long lastAccessMillis = System.currentTimeMillis();

        private Holder(Bucket bucket, long limit, long windowSeconds) {
            this.bucket = bucket;
            this.limit = limit;
            this.windowSeconds = windowSeconds;
        }
    }
}
