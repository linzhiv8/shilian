package com.shilian.infrastructure.resilience;

import com.shilian.config.ShilianProperties;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;

/**
 * 同时进行的分析数量闸门。
 *
 * <p><b>为什么需要它。</b>一次分析要「抓网页 + 调 AI」，几秒到几十秒。
 * 书签小工具是「点一下就发一次请求」，连点几下就是几个并发长任务。
 * 没有闸门时它们会一起压向上游，结果是谁都变慢，最后一起超时——
 * 本来只是慢一点，变成全部失败。
 *
 * <p><b>为什么满了直接拒绝而不是排队。</b>
 * 排队的话用户还是得等，而且等到最后很可能是超时——
 * 白等一场，还占着一个线程。直接告诉他「现在忙，等几秒再点」，
 * 他可以决定是等还是先干别的。这比让他盯着转圈猜进度好。
 *
 * <p><b>刻意不放进 {@code AnalyzeService}。</b>限流是「这一刻系统能接多少活」，
 * 属于入口层的策略，不是业务逻辑。放进去会让服务类多一个和领域无关的职责。
 */
@Component
public class AnalysisGate {

    /** 拿不到名额时抛这个，映射到 429。 */
    public static class TooBusyException extends RuntimeException {
        public TooBusyException(String message) {
            super(message);
        }
    }

    private final Semaphore slots;
    private final int capacity;

    public AnalysisGate(ShilianProperties props) {
        this.capacity = Math.max(1, props.resilience().maxConcurrentAnalyses());
        this.slots = new Semaphore(capacity);
    }

    public void acquire() {
        if (!slots.tryAcquire()) {
            throw new TooBusyException("同时最多分析 " + capacity + " 条，现在都占着。"
                    + "等几秒再点一次就行——只是慢一点，这次没分析成功也不会丢东西。");
        }
    }

    public void release() {
        slots.release();
    }

    /** 给 /api/health 用。 */
    public int available() {
        return slots.availablePermits();
    }

    public int capacity() {
        return capacity;
    }
}
