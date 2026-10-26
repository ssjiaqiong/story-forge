package com.storyforge.bootstrap;

import com.storyforge.modules.bookshelf.service.VolumeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 卷表的启动回填。
 *
 * <p>{@code V6} 迁移只建表，不插数据：回填要生成雪花 ID，SQL 里造不出来
 * （三库的迁移文件必须一致，而序列 / 自增的做法在三个库里各不相同）。
 * 所以放在应用启动后补，幂等——每次只补 (novel_id, volume_no) 缺失的组合，反复启动不会重复插入。</p>
 *
 * <p>没有用户上下文，数据隔离插件此时不追加条件（见 {@code UserDataPermissionHandler}），
 * 因此这里扫的是全库的章节；卷行的归属直接取章节的 {@code user_id}。</p>
 */
@Slf4j
@Component
@Order(20)
@RequiredArgsConstructor
public class VolumeBackfillRunner implements ApplicationRunner {

    private final VolumeService volumeService;

    @Override
    public void run(ApplicationArguments args) {
        try {
            int created = volumeService.backfill();
            if (created == 0) {
                log.info("[volume] backfill: 无缺失卷记录，跳过");
            }
        } catch (RuntimeException ex) {
            // 回填失败不能挡住启动：卷列表会为空，但建章 / 导出这些主链路都还能用，
            // 下次启动会再补一次。启动期抛异常只会让整个应用起不来，得不偿失。
            log.warn("[volume] backfill failed, will retry on next startup: {}", ex.toString());
        }
    }
}
