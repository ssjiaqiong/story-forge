package com.storyforge.modules.bookshelf.dto;

import com.storyforge.modules.search.dto.SearchBundleVO;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * AI 写正文结果。
 *
 * <p>除正文本身，还会带回「写完之后补建出来的档案」：正文里新出现、档案里原来没有的
 * 人物、关键道具与势力。补建失败不影响正文，此时三个列表都是空的。</p>
 *
 * @param content      写入后的完整章节正文
 * @param generated    本次新生成的文字
 * @param generatedWords 本次生成的字数
 */
@Schema(description = "AI 写正文结果")
public record ContentGenerateResultVO(
        Long chapterId,
        String content,
        String generated,
        Integer wordCount,
        Integer generatedWords,
        String mode,
        String model,
        Long latencyMs,
        @Schema(description = "本次联网检索情况与引用来源") SearchBundleVO search,
        @Schema(description = "正文里新出现、已补建进档案的人物") List<CharacterVO> newCharacters,
        @Schema(description = "正文里新出现、已补建进档案的物品") List<ItemVO> newItems,
        @Schema(description = "正文里新出现、已补建进档案的势力") List<FactionVO> newFactions) {
}
