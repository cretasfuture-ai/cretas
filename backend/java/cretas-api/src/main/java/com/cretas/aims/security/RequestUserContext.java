package com.cretas.aims.security;

import com.cretas.aims.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 从当前请求取已认证用户 —— 读 {@link com.cretas.aims.config.JwtAuthInterceptor} 写入的
 * request attribute。**读取方与写入方住在同一个模块**，这是刻意的：谁负责写，谁给出唯一的读法。
 *
 * <h2>为什么需要它</h2>
 *
 * 业务域模块此前拿 userId 的写法是注入 {@code MobileService.getUserFromToken(token).getId()}。
 * 那个接口的实现在主干 {@code cretas-api}（237 行门面，转发给 {@code service.mobile.*} 共
 * 2504 行），于是**每个想独立部署的域都会被这一句话钉在主干上** —— 编译得过（只见接口），
 * 装配不起来（没有实现）。2026-07-31 实测 supply / finance / factory 三个域都栽在同一句上。
 *
 * <p>而这件事地基早就做过了：{@code JwtAuthInterceptor}（order=1，注册在
 * {@code WebMvcConfig}）解析完 token 就把 {@code userId} 放进了 request attribute。
 * 所以正确的问题不是「怎么把 MobileService 的实现搬下来」，是「这件事地基做过没有」。
 *
 * <h2>⚠️ 使用前提：路径必须被 JwtAuthInterceptor 守着</h2>
 *
 * {@code /api/mobile/&#123;factoryId&#125;/**} 满足，因为：
 * <ol>
 *   <li>{@code WebMvcConfig} 把拦截器挂在 {@code /api/mobile/**}（order=1）</li>
 *   <li>不在 {@code /api/mobile/public/**} 匿名白名单里</li>
 *   <li>{@code FACTORY_ID_PATTERN}（{@code /api/mobile/([^/]+)/}）命中，且首段是真 factoryId，
 *       不在 {@code auth}/{@code activation}/{@code health}/{@code workflow}/… 那串排除名单里</li>
 *   <li>于是走 {@code JwtAuthInterceptor} 的 401 分支：{@code userId == null} 直接拒</li>
 * </ol>
 * ⇒ 能走到 controller，userId 一定非空。
 *
 * <p>⛔ 换到别的路径前先核对这四条。不满足的话这里会抛 401 而不是返回假值 ——
 * 那是 CLAUDE.md 核心原则 1「禁止降级处理，不返回假数据」。仓里有另一种写法是
 * 兜底返回 {@code 0L}（{@code StocktakeController}），**别照抄那个**：一个不存在的
 * 用户 id 会被当成真的写进业务单据。
 *
 * <h2>与 {@code MobileService.getUserFromToken} 的唯一语义差别</h2>
 *
 * 后者顺带查库确认用户行存在（查不到抛 {@code ResourceNotFoundException}），这里不查。
 * 影响：token 未过期但用户已被删除的场景，旧写法 404、这里放行 id。这与仓内既有的
 * 37 处直接读 attribute 的调用点承担的风险一致，不是新引入的口子。
 *
 * <p>平台管理员也一致：{@code JwtUtil.getUserIdFromToken} 会剥掉 {@code platform_} 前缀
 * 返回同一个数字 id，与 {@code getUserFromToken} 经 {@code platformAdminRepository}
 * 拿到的 {@code admin.getId()} 相同。
 */
public final class RequestUserContext {

    private RequestUserContext() {
    }

    /**
     * 当前已认证用户的 id。
     *
     * @throws BusinessException 401 —— attribute 缺失或不是数字。正常情况下不可能发生
     *                           （拦截器已经拒过），发生了说明这个端点的路径没被守住。
     */
    public static ActorRef currentActor(HttpServletRequest request) {
        return AuthenticatedActor.current(request)
                .map(actor -> new ActorRef(actor.type(), actor.id()))
                .orElseThrow(() -> new BusinessException(401, "无法获取已认证操作者，请重新登录"));
    }

    public static Long currentUserId(HttpServletRequest request) {
        Object raw = request == null ? null : request.getAttribute("userId");
        if (raw instanceof Number n) {
            return n.longValue();
        }
        throw new BusinessException(401, "无法获取用户信息，请重新登录");
    }
}
