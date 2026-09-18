package com.shilian.infrastructure.time;

import com.shilian.domain.port.Clock;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 生产环境的时间源：就是系统时钟。
 *
 * <p>它的存在意义是让「依赖系统时钟」这件事显式化、可替换。
 * 测试里换成固定时钟，业务规则就变成可穷举的纯函数。
 */
@Component
public class SystemClock implements Clock {

    @Override
    public LocalDateTime now() {
        return LocalDateTime.now();
    }
}
