package com.storyforge.modules.bookshelf.dto;

import com.storyforge.modules.bookshelf.entity.ChapterEntity;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 待落库的分析草稿：AI 结果 + 本地统计 + 字数判定，交给 {@code ChapterAnalysisApplier} 落库。
 */
@Data
@Builder
public class AnalysisDraft {

    private ChapterEntity chapter;

    private String novelTitle;

    private String model;

    private long latencyMs;

    private int targetWords;

    private int minWords;

    private int maxWords;

    private boolean wordPass;

    private String wordLabel;

    private String wordComment;

    private int outlineScore;

    private String outlineVerdict;

    private List<String> outlineHits;

    private List<String> outlineMisses;

    private List<String> outlineSuggestions;

    private int aiScore;

    private String aiVerdict;

    private List<ChapterAnalysisVO.AiSignal> aiSignals;

    private List<String> aiExamples;

    private List<ChapterAnalysisVO.LocalSignal> localSignals;

    private List<String> warnings;

    private AnalysisFacts facts;
}
