package com.storyforge.infra.ai;

/**
 * 一条对话消息（OpenAI chat 协议的 role / content）。
 *
 * @param role    取值为 {@code system} / {@code user} / {@code assistant}
 * @param content 消息正文
 */
public record ChatMessage(String role, String content) {

    public static ChatMessage system(String content) {
        return new ChatMessage("system", content);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage("user", content);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage("assistant", content);
    }
}
