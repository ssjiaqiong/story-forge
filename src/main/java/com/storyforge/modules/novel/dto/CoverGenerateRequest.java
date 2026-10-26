package com.storyforge.modules.novel.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 生成封面入参。
 *
 * <p>{@code prompt} 留空时由服务端按小说信息（书名 / 题材 / 标签 / 简介）拼装提示词；
 * 填了则作为「作者补充」追加在统一的封面美术要求之后，保证「画面不出现文字」这类硬性约束始终生效。</p>
 */
@Data
@Schema(description = "生成封面入参")
public class CoverGenerateRequest {

    @Schema(description = "作者补充要求，可留空", example = "雪山上的一袭红衣，背影，冷色调")
    @Size(max = 1000, message = "补充要求最长 1000 个字符")
    private String prompt;

    @Schema(description = "负面提示词（不希望出现的元素），可留空")
    @Size(max = 500, message = "负面提示词最长 500 个字符")
    private String negativePrompt;

    @Schema(description = "本次出图尺寸，形如 1024x1536；留空用接口配置里的默认尺寸")
    @Size(max = 32, message = "尺寸格式过长")
    private String size;

    @Schema(description = "封面风格：ANIME 动漫 / REAL 真人写实 / CG 3D / INK 水墨国风 / OIL 油画 / FLAT 扁平插画；留空不指定")
    @Size(max = 32, message = "风格编码过长")
    private String style;

    @Schema(description = "是否在封面上合成书名文字")
    private Boolean withTitle;

    @Schema(description = "是否在封面上合成作者名文字")
    private Boolean withAuthor;

    @Schema(description = "文字渲染方式：OVERLAY 后期本地字体叠加（默认，推荐）/ AI_NATIVE 由模型直接画出文字")
    @Size(max = 16, message = "文字渲染方式编码过长")
    private String textRenderMode;

    @Schema(description = "书名文字；留空时用小说现有书名")
    @Size(max = 120, message = "书名文字最长 120 个字符")
    private String titleText;

    @Schema(description = "作者名文字；留空时用小说现有笔名，仍无则由 AI 起一个")
    @Size(max = 80, message = "作者名最长 80 个字符")
    private String authorText;
}
