package com.shilian.domain.port;

import java.util.Optional;

/**
 * 「现在是谁在请求」。
 *
 * <p><b>为什么做成端口而不是给每个方法加一个 {@code userId} 参数。</b>
 * 显式传参看起来更清楚，但它把「别忘了按用户过滤」这件事
 * 变成了每一处调用点都要记得做一遍的事。漏一处就是一次数据泄露，
 * 而且这种漏法不会报错——A 看到 B 的数据，双方都不知道。
 * 收成端口之后，过滤写在仓储内部，<b>想漏都漏不掉</b>。
 *
 * <p>代价是数据访问层多了一个隐式输入，单测要显式喂一个假的实现。
 * 这个代价是值得的：隔离的正确性不该依赖人的记性。
 */
public interface CurrentUser {

    /** 当前登录用户的 id。未登录时抛 {@link AuthenticationRequiredException}。 */
    String id();

    /** 未登录返回空，不抛异常。用于「登录了就带上，没登录就算了」的场景。 */
    Optional<String> maybeId();
}
