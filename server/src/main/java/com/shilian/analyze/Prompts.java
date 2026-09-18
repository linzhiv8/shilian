package com.shilian.analyze;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 加载打包进 jar 的提示词。
 *
 * <p>提示词只有一份，在 {@code server/tools/prompt-lab/}，构建时由 pom 复制到 classpath 的
 * {@code prompts/}。实验室里调好什么，线上就跑什么，不会出现「改了一处忘了另一处」。
 *
 * <p><b>上下文缓存</b>：系统提示词占单次输入的一半，DeepSeek 的上下文缓存按
 * 「前缀完全一致」命中，所以调用方应该把结果在启动时读一次就固定住，
 * 中间不做任何拼接、替换。任何动态内容都必须放到用户消息里。
 */
public final class Prompts {

    private static final Logger log = LoggerFactory.getLogger(Prompts.class);

    /**
     * 提示词文件顶部的「给人看的」说明区，到第一个 --- 为止，不进模型。
     *
     * <p>容忍 CRLF：这个文件在 Windows 上被别的编辑器存过一次就会变成 \r\n，
     * 那时如果只匹配 \n，头部说明会被原样喂给模型。
     */
    private static final String HEADER_PATTERN = "(?s)^#.*?\\r?\\n---\\r?\\n";

    private Prompts() {
    }

    /**
     * 读一个提示词文件并剥掉给人看的头部。
     *
     * @param name classpath 下的文件名，例如 {@code system-prompt.md}
     */
    public static String load(String name) {
        ClassPathResource res = new ClassPathResource("prompts/" + name);
        if (!res.exists()) {
            throw new IllegalStateException(
                    "找不到提示词文件 prompts/" + name + "。"
                            + "它由构建从 server/tools/prompt-lab/ 复制而来，"
                            + "请确认在 server/ 目录下执行构建。");
        }
        try (InputStream in = res.getInputStream()) {
            String raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            String body = raw.replaceFirst(HEADER_PATTERN, "").trim();
            log.info("已加载提示词 {} · {} 字（{} token 左右）", name, body.length(), body.length() / 2);
            return body;
        } catch (IOException e) {
            throw new IllegalStateException("读取提示词 " + name + " 失败：" + e.getMessage(), e);
        }
    }
}
