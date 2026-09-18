package com.shilian.domain.port;

import java.io.IOException;
import java.util.List;

/**
 * 向量服务的能力端口。
 *
 * <p>业务只依赖这个接口，不关心背后是硅基流动、Ollama 还是本地模型。
 */
public interface EmbeddingPort {

    boolean available();

    String unavailableReason();

    /**
     * 一次算多条，顺序与入参严格对应。
     *
     * <p>批量是必须的：补齐历史记录时一条一发就是几十个 TLS 握手。
     */
    List<float[]> embed(List<String> texts) throws IOException, InterruptedException;

    /**
     * 没配置向量服务时抛这个。
     *
     * <p>刻意继承 {@link IllegalStateException} 而不是 IOException：
     * 这不是「请求本身有问题」，而是「环境没准备好」，和「DeepSeek 没配 Key」
     * 是同一类事。{@code GlobalExceptionHandler} 里已有 IllegalStateException
     * 到 503 的分支，于是它自动得到正确的状态码和能照做的提示语。
     */
    class NotConfiguredException extends IllegalStateException {
        public NotConfiguredException(String message) {
            super(message);
        }
    }
}
