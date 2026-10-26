package com.storyforge.modules.bookshelf.dto;

import com.storyforge.modules.novel.dto.NovelVO;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 书架详情：小说基本信息 + 设定 + 角色。
 */
@Data
@Schema(description = "小说详情（含设定与角色）")
public class NovelDetailVO {

    @Schema(description = "小说基本信息")
    private NovelVO novel;

    @Schema(description = "设定列表，按展示顺序")
    private List<SettingDocVO> settings = new ArrayList<>();

    @Schema(description = "角色列表，主角在前")
    private List<CharacterVO> characters = new ArrayList<>();
}
