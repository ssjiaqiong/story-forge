package com.storyforge.modules.autowrite.support;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.storyforge.common.api.ErrorCode;
import com.storyforge.common.exception.BizException;
import com.storyforge.modules.autowrite.entity.AutoWriteTaskEntity;
import com.storyforge.modules.autowrite.mapper.AutoWriteTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * 自动写书只读锁：书处于「自动生成中」时，禁止一切写操作，只留查看与导出。
 *
 * <p><b>判定口径</b>：该书存在自动写书任务、定时开启（{@code enabled=1}）、且运行态是
 * {@code IDLE} 或 {@code RUNNING} → 视为自动生成中。一旦任务被停止 / 暂停 / 完成，
 * 或者定时被关掉，锁立即解除，作者可继续编辑。</p>
 *
 * <p><b>放行</b>：自动写书自己的流水线线程需要写库，用 {@link #bypass} 在
 * {@link ThreadLocal} 上打标放行；标记只对当前线程生效，绝不影响其它请求线程。</p>
 */
@Component
@RequiredArgsConstructor
public class AutoWriteGuard {

    private static final ThreadLocal<Boolean> BYPASS = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private final AutoWriteTaskMapper taskMapper;

    /** 命中锁则抛 {@code NOVEL_AUTO_WRITING}；放行线程与未知小说直接通过。 */
    public void assertEditable(Long novelId) {
        if (novelId == null || Boolean.TRUE.equals(BYPASS.get())) {
            return;
        }
        if (isLocked(novelId)) {
            throw BizException.of(ErrorCode.NOVEL_AUTO_WRITING);
        }
    }

    /** 该书当前是否处于自动生成中（锁生效）。 */
    public boolean isLocked(Long novelId) {
        if (novelId == null) {
            return false;
        }
        Long count = taskMapper.selectCount(Wrappers.<AutoWriteTaskEntity>lambdaQuery()
                .eq(AutoWriteTaskEntity::getNovelId, novelId)
                .eq(AutoWriteTaskEntity::getEnabled, 1)
                .in(AutoWriteTaskEntity::getStatus,
                        AutoWriteTaskEntity.STATUS_IDLE, AutoWriteTaskEntity.STATUS_RUNNING));
        return count != null && count > 0;
    }

    /** 在当前线程标记放行，执行 action，结束后恢复原标记。 */
    public <T> T bypass(Supplier<T> action) {
        boolean previous = Boolean.TRUE.equals(BYPASS.get());
        BYPASS.set(Boolean.TRUE);
        try {
            return action.get();
        } finally {
            BYPASS.set(previous);
        }
    }

    /** {@link #bypass(Supplier)} 的无返回值版本。 */
    public void bypass(Runnable action) {
        bypass(() -> {
            action.run();
            return null;
        });
    }

    /**
     * 把当前线程整体标记为「自动写书线程」，直到显式 {@link #clearBypass()}。
     *
     * <p>流水线一轮里要调很多次写入口，逐次 bypass 太啰嗦；调度线程在轮次开始时调用它，
     * 轮次结束务必在 finally 里清掉，避免线程复用后把标记带给下一个任务。</p>
     */
    public void markBypass() {
        BYPASS.set(Boolean.TRUE);
    }

    public void clearBypass() {
        BYPASS.remove();
    }
}
