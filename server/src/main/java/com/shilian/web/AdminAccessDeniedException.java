package com.shilian.web;

/**
 * 非管理员访问了管理接口。
 *
 * <p><b>映射到 404，不是 403。</b>
 * 403 的语义是「你要的东西存在，但你不配碰」——
 * 对一个本来就不该知道管理端存在的人，这句话本身就是一条信息。
 * 404 让管理员看到「没有这个用户」、让普通人看到「没有这个地方」，
 * 两种人在接口上得到的是同一个答案。
 *
 * <p>这也是全项目的一贯约定，不是这里的特例：
 * {@code LinkRepository.delete} 对「不存在」和「不是你的」也返回同一个 false。
 *
 * <p>前端对管理端的 404 有兜底（提示「没有权限」并退回列表视图），
 * 所以这里保持 404 语义即可，不要为了「更准确」改成 403。
 */
public class AdminAccessDeniedException extends RuntimeException {

    public AdminAccessDeniedException() {
        super("没有权限");
    }
}
