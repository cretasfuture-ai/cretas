package com.cretas.aims.gate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 闸：凡是写 {@code storage_location} 的文件，都必须过 {@link
 * com.cretas.aims.service.warehouse.WarehouseLocationCatalog#requireConfiguredLocation} 这道判据。
 *
 * <h4>为什么需要</h4>
 *
 * <p>产品裁定（2026-08-27，Owner）：<b>储存位置是受控词表，必须是有编号的具体位置</b>。</p>
 *
 * <p>这个字段唯一的真实消费方是 web-admin「仓储空间」页的
 * {@code countAt(code) = batches.filter(b => b.location === code)}（<b>精确字符串相等</b>）。
 * 写进一个不是编码的字符串，那条批次就从所有位置的计数里消失，卡片显示「空闲」而货在那儿。
 * <b>不可信的计数比没有计数更糟。</b></p>
 *
 * <h4>2026-08-27 动手前的实测分布</h4>
 *
 * <p>全后端 <b>8 处</b> {@code setStorageLocation}，其中<b>只有 1 处</b>（成品移位）过了判据。
 * ⚠️ 而当时的交接件写的是「原料/包材复用旧接口」——按那个描述去改只会覆盖 3 处，
 * 漏掉的正是 {@code SalesServiceImpl}（<b>成品的第二条写路</b>，销售侧编辑批次）。
 * 「成品已 fail closed」这句话此前只对两条写路中的一条成立。</p>
 *
 * <h4>这道闸的代理判据与已知盲区</h4>
 *
 * <p>判据是<b>文件级</b>的：含 {@code setStorageLocation} 的文件必须同时含
 * {@code requireConfiguredLocation}。这是代理指标，不是结构判定 ——</p>
 *
 * <ul>
 *   <li>⚠️ 盲区一：同一个文件里若有两处写入而只有一处过判据，本闸看不见。</li>
 *   <li>⚠️ 盲区二：把值先存进局部变量再写，本闸也只看得到「同文件出现过判据」。</li>
 *   <li>⛔ 不要为了堵这两个盲区去堆正则 —— 那会做出一个更复杂、误报更多、
 *       仍然漏 case 的东西。要真判准只有一条路：把写入收敛到一个方法里，闸扫那一处。</li>
 * </ul>
 *
 * <p>即便如此，它守住了最容易复发的那一种：<b>新增一个写入点的人，
 * 不会知道有这条规则</b>。文件级判据足以让那个人当场变红。</p>
 */
@DisplayName("储存位置必须是受控词表 —— 每个写入点都要过同一道判据")
class StorageLocationIsAControlledVocabularyTest {

    private static final Path MAIN_ROOT = Paths.get("src/main/java");

    /** 往实体上写储存位置。 */
    private static final String WRITE_MARKER = "setStorageLocation";

    /** 唯一那道判据。 */
    private static final String GUARD_MARKER = "requireConfiguredLocation";

    /**
     * 允许写但不过判据的文件 —— 每条必须写清楚<b>为什么</b>。
     *
     * <p>⛔ 这是留痕，不是豁免。名单只许变短（见 {@link #debtRatchetOnlyShrinks}）。</p>
     */
    private static final Map<String, String> KNOWN_WITHOUT_GUARD = Map.of(
            "ai/tool/impl/material/MaterialUpdateTool.java",
            "写的是 UpdateMaterialBatchRequest 这个 DTO, 不是实体; 它随后流进 "
                    + "MaterialBatchMapper.updateEntity, 判据在那里生效。"
                    + "⚠️ 在这里再判一次会变成第二份判据(形态 D)。"
    );
    // 🔴 这份名单第一次跑就少了一条: 我曾防御性地把 WarehouseLocationCatalog 自己
    //    也登记进来, 而它根本不含 setStorageLocation ⇒ registryHasNoStaleEntries 当场红。
    //    留个记号: **防御性登记就是在造过期条目**, 名单只该写真的在写的文件。

    private static Set<String> filesWriting(String marker) throws IOException {
        try (Stream<Path> paths = Files.walk(MAIN_ROOT)) {
            return paths.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> {
                        try {
                            return stripped(p).contains(marker);
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .map(p -> MAIN_ROOT.resolve("com/cretas/aims").relativize(p)
                            .toString().replace('\\', '/'))
                    .collect(Collectors.toCollection(TreeSet::new));
        }
    }

    /**
     * 逐行剔掉注释再判。
     *
     * <p>🔴 本仓多次栽在「闸把自己写的注释当被测对象」上 —— 而这次改动留下的注释里
     * 正好逐字写着 {@code setStorageLocation} 和 {@code requireConfiguredLocation}
     * （实测：改完直接 grep 得到 10 处，其中 2 处是注释）。不剔注释，这道闸从第一天起就是假的。</p>
     */
    private static String stripped(Path p) throws IOException {
        StringBuilder out = new StringBuilder();
        for (String line : Files.readString(p, StandardCharsets.UTF_8).split("\n", -1)) {
            String t = line.trim();
            if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) {
                continue;
            }
            int slash = line.indexOf("//");
            out.append(slash >= 0 ? line.substring(0, slash) : line).append('\n');
        }
        return out.toString();
    }

    @Test
    @DisplayName("阳性对照: 扫描器确实找得到写入点")
    void scannerActuallyFindsWriteSites() throws IOException {
        Set<String> sites = filesWriting(WRITE_MARKER);
        assertThat(sites)
                .as("一个写入点都扫不到 —— 扫描器坏了, 后面的绿不作数")
                .isNotEmpty();
        assertThat(sites)
                .as("这两个是 2026-08-27 已知的真实写入点; 扫不到说明路径或剔注释逻辑坏了")
                .contains("mapper/MaterialBatchMapper.java",
                          "service/inventory/impl/SalesServiceImpl.java");
    }

    @Test
    @DisplayName("阳性对照: 判据本身找得到, 而且不止一个文件在用")
    void guardIsActuallyReferenced() throws IOException {
        assertThat(filesWriting(GUARD_MARKER))
                .as("判据零引用 —— 那它就没在守任何东西")
                .hasSizeGreaterThan(1);
    }

    @Test
    @DisplayName("每个写入点都过判据, 否则必须登记理由")
    void everyWriteSitePassesTheGuardOrIsRegistered() throws IOException {
        Set<String> guarded = filesWriting(GUARD_MARKER);
        Set<String> offenders = new TreeSet<>();
        for (String site : filesWriting(WRITE_MARKER)) {
            if (KNOWN_WITHOUT_GUARD.containsKey(site)) continue;
            if (!guarded.contains(site)) offenders.add(site);
        }
        assertThat(offenders)
                .as("储存位置是受控词表(Owner 2026-08-27 裁定): 写它之前必须过 "
                        + "WarehouseLocationCatalog.requireConfiguredLocation。"
                        + "直接写会让「某位置有几批」在生产数据上恒为 0 —— "
                        + "请过判据, 或在 KNOWN_WITHOUT_GUARD 里登记理由")
                .isEmpty();
    }

    @Test
    @DisplayName("名单不许烂掉: 登记的文件必须还真的在写")
    void registryHasNoStaleEntries() throws IOException {
        Set<String> writing = filesWriting(WRITE_MARKER);
        Set<String> stale = new TreeSet<>(KNOWN_WITHOUT_GUARD.keySet());
        stale.removeAll(writing);
        assertThat(stale)
                .as("这些条目已经不再写 storage_location, 请从 KNOWN_WITHOUT_GUARD 里删掉 "
                        + "—— 一份烂掉的名单会让下一个人以为这里还欠着债")
                .isEmpty();
    }

    @Test
    @DisplayName("棘轮: 名单只许变短")
    void debtRatchetOnlyShrinks() {
        assertThat(KNOWN_WITHOUT_GUARD)
                .as("⛔ 不许新增免检文件。要加一处新的写入点, 就让它过判据。"
                        + "确有理由绕开时, 先把理由说服 Owner, 再调这个上限")
                .hasSizeLessThanOrEqualTo(1);
    }
}
