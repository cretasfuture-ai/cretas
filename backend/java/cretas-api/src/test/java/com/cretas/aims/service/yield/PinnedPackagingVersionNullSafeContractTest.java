package com.cretas.aims.service.yield;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code resolvePinnedPackaging} 里的 {@code version} 可能是 null —— 不许裸解引用。
 *
 * <h2>2026-08-26 生产实测（界面走查发现）</h2>
 *
 * <pre>
 * GET /api/mobile/F006/production/batches/{no}/cost-breakdown  →  500
 * NullPointerException: Cannot invoke "BomVersion.getVersionNumber()" because "version" is null
 *   at OrderCostBreakdownService.resolvePinnedPackaging(:1175)
 * 界面表现: 批次详情页顶部常驻「系统处理异常, 请稍后重试」, 每次刷新换一个追踪码。
 * </pre>
 *
 * <h2>为什么会这样 —— 「只改了一半」</h2>
 *
 * <p>F006 的 BOM 走画布那条路，<b>不写 {@code bom_versions}</b>（生产实测：13 个 ACTIVE
 * {@code bom_recipes} 里有版本快照的是 <b>0 个</b>）。于是 {@code version} 查不到 = null，
 * {@code snapshot} 也 = null。</p>
 *
 * <p>2026-08-25 为此加了一条兜底：{@code snapshot = pinnedBomSnapshotFromRecipe(...)}，
 * 从 {@code bom_recipes} 直接取。<b>但它只救回了 snapshot，没有救回 version 对象。</b>
 * 兜底成功 ⇒ 执行越过 {@code if (snapshot == null)} 那道守卫 ⇒ 后面
 * {@code version.getVersionNumber()} 当场 NPE。</p>
 *
 * <p>🔴 <b>所以这条恢复路径「只在它成功的时候崩」</b> —— 失败时反而正常报
 * {@code PINNED_BOM_SNAPSHOT_MISSING}。把「诚实报缺」变成了 500。</p>
 *
 * <h2>判据的代理性（写明）</h2>
 *
 * <p>真正想守的是「运行时不对 null 解引用」。私有方法 + 重度依赖仓储，单测要 mock 四五个
 * repository 才跑得起来，那种测试本身就会因为脆而被删掉。所以这里用<b>源码级代理判据</b>：
 * 那个具体的裸解引用形状不许回来，且版本号必须经过 null 判断。</p>
 *
 * <p>⚠️ 已知盲区：换个写法（先赋给局部变量再取）本闸看不出来。它的作用是<b>让「把裸
 * version.getVersionNumber() 写回去」这个动作必须先绕过一条写着理由的断言</b>，不是物理上不可能。</p>
 */
@DisplayName("pinned 包材成本: version 可能为 null, 不许裸解引用")
class PinnedPackagingVersionNullSafeContractTest {

    private static final Path SRC = Paths.get(
            "src/main/java/com/cretas/aims/service/yield/OrderCostBreakdownService.java");

    /** 剥注释 —— 否则本闸会把上面这段说明和源码里的说明当成被测对象。 */
    private static String strippedSource() throws IOException {
        String src = Files.readString(SRC, StandardCharsets.UTF_8);
        return src.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }

    @Test
    @DisplayName("阳性对照: 源文件读得到且确实含这段逻辑 (防剥注释剥成空串后恒绿)")
    void scannerActuallyReadsTheFile() throws IOException {
        String src = strippedSource();
        assertThat(src.length()).isGreaterThan(20_000);
        assertThat(src).contains("resolvePinnedPackaging");
        assertThat(src).contains("pinnedBomSnapshotFromRecipe");
    }

    @Test
    @DisplayName("不许再出现裸的 PINNED_BOM_ITEM_COST:v + version.getVersionNumber()")
    void mustNotDereferenceVersionBare() throws IOException {
        assertThat(strippedSource())
                .as("这正是 2026-08-26 那个 500 的形状: recipe 兜底成功时 version 仍是 null。"
                        + "版本号请走 null 判断后的 pinnedVersionNumber。")
                .doesNotContain("\"PINNED_BOM_ITEM_COST:v\" + version.getVersionNumber()");
    }

    @Test
    @DisplayName("版本号必须经过 null 判断, 且兜底来源单独标注")
    void versionNumberMustBeNullGuardedAndSourceLabelled() throws IOException {
        String src = strippedSource();
        // ⚠️ 2026-08-27 更新: 退路变了, 不是断言失效。
        //    原来「version 为 null 时退回 plan.getSelectedBomVersion()」——
        //    现在成本改读【批次】创建时钉下的绑定(BatchBomBinding), 计划只是批次没钉时的兜底。
        //    守的行为没变(版本号必须有来处、不许裸解引用), 变的是来处本身。
        assertThat(src)
                .as("version 为 null 时必须有明确退路, 不许裸用")
                .contains("version != null");
        assertThat(src)
                .as("成本必须走批次绑定 —— 批次的账按它创建时绑的那一版算")
                .contains("BatchBomBinding.resolve");
        // ⛔ 来源不能混为一谈: 冻结快照 vs「当前配方恰好还是那个版本」是两回事,
        //    混掉会让历史成本的可追溯性悄悄丢失。
        assertThat(src)
                .as("走 recipe 兜底时价格来源必须与 pinned 快照区分开")
                .contains("RECIPE_FALLBACK_ITEM_COST:v");
    }
}
