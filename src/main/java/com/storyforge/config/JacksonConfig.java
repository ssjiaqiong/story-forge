package com.storyforge.config;

import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 全局 JSON 序列化约定。
 *
 * <p>主键由雪花算法生成，是 19 位长整型（约 2.1×10^18），超过 JavaScript
 * {@code Number.MAX_SAFE_INTEGER}（9007199254740991）。若按 JSON number 下发，
 * 浏览器解析时会四舍五入（如 {@code ...591362 → ...591400}），前端拿到的 ID 再回传就查不到记录。
 * 因此把包装类型 {@link Long} 统一序列化为字符串。</p>
 *
 * <p>只注册 {@code Long.class}，不影响 {@code long} 基本类型——分页总数、页码等计数字段
 * 仍是 JSON number，前端无需改造。</p>
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer longToStringCustomizer() {
        return builder -> builder.serializerByType(Long.class, ToStringSerializer.instance);
    }
}
