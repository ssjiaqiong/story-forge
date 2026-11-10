package com.storyforge.modules.bookshelf.service;

import com.storyforge.modules.bookshelf.dto.ConsistencyDraft;
import com.storyforge.modules.bookshelf.entity.ChapterEntity;
import com.storyforge.modules.bookshelf.entity.ForeshadowEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R9 伏笔超期的进度口径回归。
 *
 * <p>线上真实事故：一本书排了 50 章大纲（第 11–50 章只有标题、正文为空），作者写到第 10 章，
 * 第 1 章埋的两条伏笔却提示「已超期 19 章」（= 50 − 31）。原因是把章节表的最大章号当成了写作进度。</p>
 *
 * <p>这里锁死三件事：空章节不算进度、真写到第 50 章仍要报、只有一章正文时不能报。</p>
 */
class ConsistencyRuleCheckerForeshadowTest {

    private final ConsistencyRuleChecker checker = new ConsistencyRuleChecker();

    /** 造一本书：共 chapterCount 章，其中前 bodyChapters 章有正文，其余是只排了大纲的空章节。 */
    private static List<ChapterEntity> book(int chapterCount, int bodyChapters) {
        List<ChapterEntity> chapters = new ArrayList<>();
        for (int no = 1; no <= chapterCount; no++) {
            boolean written = no <= bodyChapters;
            ChapterEntity chapter = new ChapterEntity();
            chapter.setId((long) no);
            chapter.setChapterNo(no);
            chapter.setVolumeNo(1);
            chapter.setTitle("第" + no + "章");
            chapter.setSortNo(1000 + no);
            chapter.setStatus(written ? "DONE" : "DRAFT");
            chapter.setContent(written ? "正文正文正文正文正文".repeat(40) : null);
            chapter.setWordCount(written ? 2500 : 0);
            chapters.add(chapter);
        }
        return chapters;
    }

    private static ForeshadowEntity foreshadow(int planted, Integer plannedPayoff) {
        ForeshadowEntity item = new ForeshadowEntity();
        item.setId(1L);
        item.setTitle("被当成毒物的红辣椒");
        item.setPlantedChapterNo(planted);
        item.setPlantedChapterId(1000L + planted);
        item.setPlannedPayoffChapter(plannedPayoff);
        item.setStatus(ForeshadowEntity.STATUS_OPEN);
        return item;
    }

    private List<ConsistencyDraft> run(List<ChapterEntity> chapters, List<ForeshadowEntity> foreshadows) {
        return checker.check(chapters, List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), Set.of(), foreshadows);
    }

    private static long foreshadowIssues(List<ConsistencyDraft> drafts) {
        return drafts.stream().filter(draft -> "FORESHADOW".equals(draft.issueType())).count();
    }

    @Test
    @DisplayName("只排了大纲、正文还空着的章节不算写作进度，不报超期")
    void emptyDraftChaptersDoNotCountAsProgress() {
        // 线上实况：50 章大纲，只有 1–10 有正文；伏笔埋在第 1 章、没定计划回收章
        long issues = foreshadowIssues(run(book(50, 10), List.of(foreshadow(1, null))));

        assertThat(issues).isZero();
    }

    @Test
    @DisplayName("正文真的写到第 50 章时，同一条伏笔要报超期 19 章")
    void overdueStillReportedWhenBodyReachesChapter50() {
        List<ConsistencyDraft> drafts = run(book(50, 50), List.of(foreshadow(1, null)));

        assertThat(drafts)
                .filteredOn(draft -> "FORESHADOW".equals(draft.issueType()))
                .singleElement()
                .satisfies(draft -> {
                    assertThat(draft.title()).contains("已超期 19 章");
                    assertThat(draft.evidence()).contains("当前最新第 50 章");
                });
    }

    @Test
    @DisplayName("正文只写了第 1 章时不报超期")
    void singleWrittenChapterDoesNotTriggerOverdue() {
        long issues = foreshadowIssues(run(book(50, 1), List.of(foreshadow(1, null))));

        assertThat(issues).isZero();
    }

    @Test
    @DisplayName("计划回收章已过时按计划章算，而不是按埋设章 + 30")
    void plannedPayoffChapterWins() {
        // 第 2 章埋、计划第 5 章收，正文写到第 10 章 → 超期 5 章
        List<ConsistencyDraft> drafts = run(book(10, 10), List.of(foreshadow(2, 5)));

        assertThat(drafts)
                .filteredOn(draft -> "FORESHADOW".equals(draft.issueType()))
                .singleElement()
                .satisfies(draft -> assertThat(draft.title()).contains("已超期 5 章"));
    }
}
