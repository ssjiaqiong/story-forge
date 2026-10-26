package com.storyforge.modules.bookshelf.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 小说蓝图：AI 生成结果，也是「保存到书架」时的提交内容。
 *
 * <p>用户在预览步骤可以逐项修改，服务端不再重新调用 AI。</p>
 */
@Data
@Schema(description = "小说蓝图")
public class NovelBlueprint {

    @Schema(description = "书名")
    private String title;

    @Schema(description = "题材")
    private String genre;

    @Schema(description = "标签")
    private List<String> tags = new ArrayList<>();

    @Schema(description = "简介")
    private String synopsis;

    @Schema(description = "世界观：世界规则、力量体系、地理格局")
    private String worldview;

    @Schema(description = "背景设定：时代背景、历史事件、当前局势")
    private String background;

    @Schema(description = "核心冲突")
    private String coreConflict;

    @Schema(description = "核心看点，每项一句")
    private List<String> highlights = new ArrayList<>();

    @Schema(description = "文风与叙事基调")
    private String style;

    @Schema(description = "主角")
    private CharacterDraft protagonist;

    @Schema(description = "配角 / 反派")
    private List<CharacterDraft> characters = new ArrayList<>();
}
