package com.storyforge.modules.novel.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storyforge.modules.novel.entity.NovelEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 封面提示词拼装。
 *
 * <p>封面图有三种构图要求，取决于文字渲染方式：</p>
 * <ol>
 *   <li><b>OVERLAY 后期叠加</b>（默认，推荐）——画面内禁止出现任何文字，画面下方预留纯净留白，
 *       书名/作者由本地字体清晰渲染叠上去；</li>
 *   <li><b>AI_NATIVE 模型直出</b>——允许模型画出书名（和作者名）作为封面标题字，但除此之外
 *       画面内不得出现任何其他文字（简介、标签、对白、水印等一律禁止）；</li>
 *   <li><b>不含文字</b>——全面禁止文字。</li>
 * </ol>
 *
 * <p>无论哪种方式，画面中除「AI 直出模式下允许的书名/作者标题字」外，不得出现任何文字。
 * 小说简介（intro）<b>不会进入提示词</b>——把简介原文喂给模型会导致它把整段文字原样画成
 * 封面文字块（标题重复、版面脏乱）；画面方向只由题材、标签等离散关键词给出。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CoverPromptBuilder {

    /** 标签最多引用个数 */
    private static final int MAX_TAGS = 4;

    /** 平台不支持负面提示词时用的兜底否定描述（会被并入正向提示词） */
    public static final String DEFAULT_NEGATIVE =
            "文字, 汉字, 英文字母, 数字, 简介文字块, 段落, 对话气泡, 字幕, 水印, 平台logo, 签名, 印章, 边框, 拼图, 多手多指, 畸形手指, 扭曲五官, 糊脸, 低清晰度";

    private final ObjectMapper objectMapper;

    /**
     * 拼装封面提示词。
     *
     * @param novel     小说
     * @param extra     作者补充画面描述，可空
     * @param style     风格编码，可空（{@link CoverStyle#of} 解析，未知编码忽略）
     * @param withText  封面上是否要有书名/作者文字
     * @param aiNative  true = 允许模型直接画出书名/作者文字；false = 画面禁字（文字后期叠加或没有文字）
     * @param titleText 要出现在封面上的书名（AI 直出时注入提示词），可空
     * @param authorText 要出现在封面上的作者名（AI 直出时注入提示词），可空
     */
    public String build(NovelEntity novel, String extra, String style,
                        boolean withText, boolean aiNative, String titleText, String authorText) {
        StringBuilder builder = new StringBuilder(512);
        builder.append("中文网络小说封面插画，竖版书籍封面构图，单幅完整作品。\n");
        CoverStyle coverStyle = CoverStyle.of(style);
        if (coverStyle != null) {
            builder.append("【风格】").append(coverStyle.getPrompt()).append("。\n");
        }
        builder.append("【作品】《").append(hasText(novel.getTitle()) ? novel.getTitle() : "未命名").append("》");
        if (hasText(novel.getGenre())) {
            builder.append("｜题材：").append(novel.getGenre().trim());
        }
        List<String> tags = readTags(novel.getTags());
        if (!tags.isEmpty()) {
            builder.append("｜标签：").append(String.join("、", tags));
        }
        builder.append('\n');

        // 简介原文不进入提示词——否则模型会把这段文字原样画成封面上的文字块（标题重复、版面脏乱）。
        // 只用「题材 + 标签」这类离散关键词给模型画面方向，关键词本身不含可被排版的成句文字。
        // （题材/标签已在【作品】行注入，这里不再重复）

        if (withText && aiNative) {
            String textDesc = buildNativeTextDesc(titleText, authorText);
            builder.append("""
                    【画面】从故事里挑一个最有张力的瞬间取景：主体人物或核心意象清晰可辨，构图有视觉中心，
                    电影级光影、真实材质、服饰与道具细节丰富；色调贴合题材气质，画面整体统一干净。
                    """);
            builder.append("【封面标题字】").append(textDesc).append("""
                    标题字要大而醒目、笔画完整清晰、符合小说气质；除上述标题字外，
                    画面内不得出现任何其他文字、汉字、字母、数字、段落、对话框、标注、印章、水印、logo 或排版边框；
                    不要把故事简介写成文字块。
                    """);
            builder.append("【硬性要求】人物解剖结构正确，不要多手多指、五官扭曲；不要拼接多幅小图或分镜。\n");
        } else if (withText) {
            builder.append("""
                    【画面】从故事里挑一个最有张力的瞬间取景：主体人物或核心意象清晰可辨，构图有视觉中心，
                    画面下方预留约六分之一高度的纯净留白（无主体、无杂乱纹理），用于后期叠放书名与作者名；
                    电影级光影、真实材质、服饰与道具细节丰富；色调贴合题材气质，画面整体统一干净。
                    【硬性要求】画面内不要出现任何文字、汉字、字母、数字、段落、对话框、印章、水印、logo 或排版边框，
                    不要把故事简介写成文字块；人物解剖结构正确，不要多手多指、五官扭曲；不要拼接多幅小图或分镜。
                    """);
        } else {
            builder.append("""
                    【画面】从故事里挑一个最有张力的瞬间取景：主体人物或核心意象清晰可辨，构图有视觉中心，
                    上方或两侧留出可叠放书名的空白；电影级光影、真实材质、服饰与道具细节丰富；
                    色调贴合题材气质，画面整体统一干净。
                    【硬性要求】画面内不要出现任何文字、汉字、字母、数字、段落、对话框、印章、水印、logo 或排版边框，
                    不要把故事简介写成文字块；人物解剖结构正确，不要多手多指、五官扭曲；不要拼接多幅小图或分镜。
                    """);
        }

        if (hasText(extra)) {
            builder.append("【作者补充】").append(extra.trim());
        }
        return builder.toString().trim();
    }

    /** AI 直出模式下的标题字描述。 */
    private String buildNativeTextDesc(String titleText, String authorText) {
        boolean hasTitle = hasText(titleText);
        boolean hasAuthor = hasText(authorText);
        if (hasTitle && hasAuthor) {
            return "在画面上方或视觉空白处画出书名「" + titleText + "」作为主标题（大字），"
                    + "并在其下方以小字画出作者名「" + authorText + "」；";
        }
        if (hasTitle) {
            return "在画面上方或视觉空白处画出书名「" + titleText + "」作为主标题（大字）；";
        }
        if (hasAuthor) {
            return "在画面合适位置画出作者名「" + authorText + "」；";
        }
        return "可在画面上方留出的标题位置留白；";
    }

    /**
     * 计算「文字要求」变量：给模板渲染用，也是兜底 build 里三分支的共用口径。
     *
     * @param withText  是否要有书名/作者文字
     * @param aiNative  AI 直出（模型画字）还是后期叠加
     * @param titleText 书名文字
     * @param authorText 作者名文字
     */
    public String textRequirement(boolean withText, boolean aiNative, String titleText, String authorText) {
        if (withText && aiNative) {
            String desc = buildNativeTextDesc(titleText, authorText);
            return desc + "标题字要大而醒目、笔画完整清晰、符合小说气质；除标题字外画面内不出现其他文字。";
        }
        if (withText) {
            return "画面下方预留约六分之一高度的纯净留白（无主体、无杂乱纹理），用于后期叠放书名与作者名。";
        }
        return "上方或两侧留出可叠放书名的空白。";
    }

    /** 兼容旧签名：不指定风格、不含文字。 */
    public String build(NovelEntity novel, String extra) {
        return build(novel, extra, null, false, false, null, null);
    }

    /** 风格编码 → 美术描述；未指定或未知返回空串。 */
    public String stylePrompt(String style) {
        CoverStyle coverStyle = CoverStyle.of(style);
        return coverStyle == null ? "" : coverStyle.getPrompt();
    }

    /** 标签 → 顿号连接串（最多 4 个），供模板变量使用。 */
    public String tagsString(NovelEntity novel) {
        List<String> tags = readTags(novel.getTags());
        return tags.isEmpty() ? "" : String.join("、", tags);
    }

    /** AI 直出标题字时不能否定「文字」，只否定标题以外的杂字。 */
    public static final String NATIVE_NEGATIVE =
            "段落文字, 简介文字块, 对话气泡, 字幕, 英文字母, 数字, 水印, 平台logo, 签名, 印章, 边框, 拼图, 多手多指, 畸形手指, 扭曲五官, 糊脸, 低清晰度";

    /**
     * 负面提示词 = 内置底线 + 作者补充，避免重复拼接。
     *
     * @param aiNative 模型直出标题字时不能把「文字/汉字」放进负面提示词，否则与正向要求打架
     */
    public String negative(String extra, boolean aiNative) {
        String base = aiNative ? NATIVE_NEGATIVE : DEFAULT_NEGATIVE;
        if (!hasText(extra)) {
            return base;
        }
        String cleaned = extra.trim();
        return base.contains(cleaned) ? base : base + ", " + cleaned;
    }

    /** 兼容旧签名：默认禁字模式。 */
    public String negative(String extra) {
        return negative(extra, false);
    }

    private List<String> readTags(String raw) {
        if (!hasText(raw)) {
            return List.of();
        }
        try {
            List<String> tags = objectMapper.readValue(raw, new TypeReference<>() {
            });
            return tags.stream().filter(this::hasText).limit(MAX_TAGS).map(String::trim).toList();
        } catch (Exception ex) {
            log.warn("[cover] parse tags failed: {}", ex.getMessage());
            return List.of();
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
