package com.storyforge.modules.ai.scene;

import lombok.Getter;

/**
 * 文生图平台协议。
 *
 * <p>各平台的请求体与响应结构差异较大，光有「OpenAI 兼容地址」并不够：</p>
 * <ul>
 *   <li>{@link #OPENAI}：{@code POST /images/generations}，出参 {@code data[].url | b64_json}；</li>
 *   <li>{@link #SILICONFLOW}：同上地址，但入参是 {@code image_size / batch_size}，
 *       出参在 {@code images[].url}；</li>
 *   <li>{@link #VOLCENGINE}：火山方舟 {@code /api/v3/images/generations}，出参 {@code data[].url}；</li>
 *   <li>{@link #ZHIPU}：智谱 {@code /api/paas/v4/images/generations}，出参 {@code data[].url}；</li>
 *   <li>{@link #DASHSCOPE}：阿里云百炼异步任务，先提交再轮询 {@code /tasks/{id}}；</li>
 *   <li>{@link #MODELSCOPE}：魔搭同样异步（{@code X-ModelScope-Async-Mode}），轮询取
 *       {@code output_images}。</li>
 * </ul>
 *
 * <p>枚举里的默认值只用于「新增接口」时预填表单，用户可改。</p>
 */
@Getter
public enum ImageProvider {

    OPENAI("OpenAI 官方 / 兼容协议",
            "https://api.openai.com/v1",
            "gpt-image-1",
            "1024x1536",
            "任何遵循 OpenAI 图像协议的网关都可以选这一项；若模型是 dall-e-3，尺寸请填 1024x1024 或 1024x1792。",
            true, true),

    SILICONFLOW("硅基流动 SiliconFlow",
            "https://api.siliconflow.cn/v1",
            "Kwai-Kolors/Kolors",
            "960x1280",
            "推荐模型 Kwai-Kolors/Kolors 或 Qwen/Qwen-Image；出图链接 1 小时后失效，系统会立即下载转存。",
            true, true),

    VOLCENGINE("火山方舟（豆包 Seedream）",
            "https://ark.cn-beijing.volces.com/api/v3",
            "doubao-seedream-3-0-t2i-250415",
            "1024x1536",
            "模型名可填 Seedream 版本别名，也可填推理接入点 ID（ep-xxxx）。",
            true, true),

    ZHIPU("智谱 BigModel（CogView）",
            "https://open.bigmodel.cn/api/paas/v4",
            "cogview-3-flash",
            "768x1344",
            "CogView 支持中文提示词；竖版建议 768x1344 或 864x1152。",
            true, true),

    DASHSCOPE("阿里云百炼（通义万相）",
            "https://dashscope.aliyuncs.com/api/v1",
            "wanx2.1-t2i-turbo",
            "720x1280",
            "异步任务：提交后轮询结果，单张通常 10~60 秒；尺寸用「宽x高」填，系统会转成平台要求的「宽*高」。",
            true, false),

    MODELSCOPE("魔搭 ModelScope",
            "https://api-inference.modelscope.cn/v1",
            "Qwen/Qwen-Image",
            "1024x1024",
            "异步任务：模型名用 魔搭 Model-Id（如 Qwen/Qwen-Image），首次调用需在魔搭完成模型授权。",
            true, true);

    /** 界面显示名 */
    private final String label;
    /** 默认接口基址（含版本段） */
    private final String defaultBaseUrl;
    /** 推荐模型 */
    private final String defaultModel;
    /** 推荐出图尺寸 */
    private final String defaultImageSize;
    /** 表单提示 */
    private final String hint;
    /** 是否支持负面提示词 */
    private final boolean supportsNegativePrompt;
    /** 是否提供 /models 模型列表（百炼没有） */
    private final boolean supportsModelList;

    ImageProvider(String label, String defaultBaseUrl, String defaultModel, String defaultImageSize,
                  String hint, boolean supportsNegativePrompt, boolean supportsModelList) {
        this.label = label;
        this.defaultBaseUrl = defaultBaseUrl;
        this.defaultModel = defaultModel;
        this.defaultImageSize = defaultImageSize;
        this.hint = hint;
        this.supportsNegativePrompt = supportsNegativePrompt;
        this.supportsModelList = supportsModelList;
    }

    public static ImageProvider of(String code) {
        if (code == null || code.isBlank()) {
            return OPENAI;
        }
        for (ImageProvider provider : values()) {
            if (provider.name().equalsIgnoreCase(code.trim())) {
                return provider;
            }
        }
        return null;
    }
}
