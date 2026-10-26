package com.storyforge.infra.ai;

import com.storyforge.modules.ai.scene.ImageProvider;

/**
 * 文生图结果（已下载到内存，调用方负责落盘）。
 *
 * @param data     图片字节
 * @param mimeType 识别出的真实类型，如 {@code image/jpeg} / {@code image/png} / {@code image/webp}
 * @param model    服务端回显的模型名，取不到时回落到请求里的模型
 * @param provider 生成该图使用的平台协议
 */
public record ImageGenResult(byte[] data, String mimeType, String model, ImageProvider provider) {

    public int size() {
        return data == null ? 0 : data.length;
    }
}
