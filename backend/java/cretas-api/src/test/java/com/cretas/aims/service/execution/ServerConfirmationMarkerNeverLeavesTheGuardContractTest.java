package com.cretas.aims.service.execution;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 进程内的「服务端已确认」标记，<b>看完就必须剥掉</b> —— 两个出口都要剥。
 *
 * <h2>它守什么</h2>
 *
 * <p>{@code WriteGuardService} 用一个进程内的哨兵对象表示「这次写操作是服务端签发的确认」：</p>
 *
 * <pre>
 *   private static final Object SERVER_CONFIRMATION_MARKER = new Object();
 *   isConfirmed(ctx) -&gt; ctx.get(KEY) == SERVER_CONFIRMATION_MARKER   // 身份比较
 * </pre>
 *
 * <p>它只该活到 W0 写闸和 RBAC 看完为止。看完之后，<b>不许序列化、不许转发给工具</b>。</p>
 *
 * <h2>2026-08-27 实测：两个出口只有一个在剥</h2>
 *
 * <table>
 *   <tr><th>出口</th><th>看完之后</th></tr>
 *   <tr><td>{@code ToolDispatchService}（SITE B）</td><td>✅ 调 {@code withoutServerConfirmationMarker}</td></tr>
 *   <tr><td>{@code ToolRouterServiceImpl}（SITE E）</td><td>❌ 原样 {@code writeValueAsString(context)} 进
 *       {@code ToolCall.arguments}，并原样传给 {@code executor.execute()}</td></tr>
 * </table>
 *
 * <h2>⚠️ 定性：这是卫生问题，<b>不是鉴权漏洞</b></h2>
 *
 * <p>标记是裸 {@code new Object()} 且用 {@code ==} 比身份 ⇒ <b>跨序列化边界不可伪造</b>：
 * 客户端造不出一个 {@code ==} 于该引用的值，replay 回来的是另一个对象，比较必假。</p>
 *
 * <p>真正的问题是两条：</p>
 * <ol>
 *   <li>内部 key 会漏进 {@code arguments}，进而进日志 / 存储 / 上游响应；</li>
 *   <li><b>同一条不变式有两个出口，一个剥一个不剥</b> —— 那是必漂的形状。
 *       下一个人只会看到其中一个，然后以为那就是全部。</li>
 * </ol>
 *
 * <h2>判据的代理性（写明，不靠加启发式逼近）</h2>
 *
 * <p>静态扫描判不出运行时行为。这里用<b>源码级代理判据</b>：两个出口文件里都必须出现
 * {@code withoutServerConfirmationMarker}。</p>
 *
 * <p>⚠️ 已知盲区：它判不出「剥了但剥错了位置」（例如在 W0 之前就剥掉 —— 那会让写闸永远
 * 判定未确认，属于 fail-closed，不会造成越权）。也判不出有人新开第三个出口。
 * 它的作用是<b>让「新出口忘了剥」这件事必须显式绕过一道写着理由的闸</b>。</p>
 */
@DisplayName("服务端确认标记: 看完就剥 —— 两个出口都要剥")
class ServerConfirmationMarkerNeverLeavesTheGuardContractTest {

    private static final Path MAIN = Paths.get("src/main/java/com/cretas/aims");

    private static String read(String rel) throws IOException {
        return Files.readString(MAIN.resolve(rel), StandardCharsets.UTF_8);
    }

    /** 剥注释再判 —— 免得闸把自己/别人的说明文字当成被测对象（本仓多次栽在这上面）。 */
    private static String stripComments(String src) {
        return src.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }

    @Test
    @DisplayName("阳性对照：两个出口文件都读得到，且确实在查这个标记（仪器活着）")
    void bothExitsAreReadableAndActuallyInspectTheMarker() throws IOException {
        String dispatch = read("service/execution/ToolDispatchService.java");
        String router = read("service/impl/ToolRouterServiceImpl.java");

        // 文件被改名/拆分时这两条会红, 提醒来改本闸 ——
        // 否则 stripComments 之后扫到空串, 下面两条会静默恒绿。
        assertThat(dispatch).as("ToolDispatchService 读不到或已改名").isNotEmpty();
        assertThat(router).as("ToolRouterServiceImpl 读不到或已改名").isNotEmpty();
        assertThat(stripComments(router))
                .as("SITE E 不再调 isConfirmed —— 本闸失去被测对象, 请来改它")
                .contains("isConfirmed(");
    }

    @Test
    @DisplayName("SITE B (ToolDispatchService) 看完之后剥掉标记")
    void dispatchServiceStripsTheMarker() throws IOException {
        assertThat(stripComments(read("service/execution/ToolDispatchService.java")))
                .as("SITE B 不再剥进程内确认标记 —— 它会被序列化并转发给工具")
                .contains("withoutServerConfirmationMarker");
    }

    @Test
    @DisplayName("SITE E (ToolRouterServiceImpl) 看完之后也要剥掉标记")
    void routerServiceStripsTheMarker() throws IOException {
        assertThat(stripComments(read("service/impl/ToolRouterServiceImpl.java")))
                .as("SITE E 没有剥进程内确认标记 —— 它会被 writeValueAsString 进 ToolCall.arguments "
                        + "并原样传给 executor.execute()。看完就剥, 两个出口都要剥。")
                .contains("withoutServerConfirmationMarker");
    }
}
