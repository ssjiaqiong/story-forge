package com.storyforge.modules.bookshelf.dto;

import java.util.List;

/**
 * 模型给出的质量评审结果（字数点评也在这里，数值由服务端计算）。
 */
public record AnalysisScores(
        int outlineScore,
        String outlineVerdict,
        List<String> outlineHits,
        List<String> outlineMisses,
        List<String> outlineSuggestions,
        int aiScore,
        String aiVerdict,
        List<Signal> aiSignals,
        List<String> aiExamples,
        String lengthComment) {

    public record Signal(String name, String detail) {
    }

    public static AnalysisScores empty() {
        return new AnalysisScores(0, "", List.of(), List.of(), List.of(),
                0, "", List.of(), List.of(), "");
    }
}
