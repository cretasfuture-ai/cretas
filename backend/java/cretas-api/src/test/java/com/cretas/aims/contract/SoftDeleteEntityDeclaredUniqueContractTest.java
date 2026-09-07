package com.cretas.aims.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 源码级契约：<b>软删实体（类上有 {@code @SQLDelete}）自己声明的唯一约束，必须逐个登记在下面的清单里。</b>
 *
 * <p><b>为什么需要这道闸</b>（2026-09-07，P0 FIX-ENTITY-FULL-UNIQUE-VS-PROD-PARTIAL）：
 * 软删批 2（#701）给 {@code LogisticsTrip} 等实体补上 {@code @SQLDelete} 之后，main 上三个 logistics 测试类
 * 出了 4 个 error，全部是 {@code Unique index or primary key violation}。查下来 <b>prod 侧完全正确</b>——
 * 那些业务唯一键在 prod 上本来就是 {@code … WHERE deleted_at IS NULL} 的<b>部分</b>索引，
 * 「软删一行再建同键」在 prod 上是允许的（docker prod-schema 副本实测：活行互斥 → 软删后同键可建 → 再建又被拒）。
 * 红的只有 H2：<b>JPA 表达不了部分唯一</b>，实体里那句 {@code @UniqueConstraint} 只能在 H2 建出<b>全量</b>键，
 * 于是测试库比生产库严，症状是「测试红而线上是对的」。
 *
 * <p>🔴 <b>更要紧的是判据本身的盲区</b>：做前几批软删时，我判「这张表的唯一键是全量还是部分」用的是
 * <b>查 prod 的 {@code pg_indexes}</b>——那个判据对「<b>实体自己声明</b>的唯一约束」是完全看不见的。
 * 一次普查量到：190 个实体声明了唯一约束，其中 <b>12 个</b>已软删且 prod 只有部分索引（本次全部摘掉），
 * 另有 <b>12 个</b>尚未软删但形状相同——它们会在后面的批次里以同样的方式变红。
 * ⇒ 判唯一键必须<b>两侧都看</b>：prod 的真实索引 + 实体注解。这道闸钉的就是「两侧都看过了」。
 *
 * <p><b>怎么用</b>：给一个实体补 {@code @SQLDelete} 时，如果它的 {@code @Table} 上有 {@code @UniqueConstraint}
 * （或某个 {@code @Column} 上有 {@code unique = true}），这道闸会红。红了不是让你把它加进清单，是让你先去
 * <b>看 prod 上那个索引的形状</b>，然后三选一：
 * <ol>
 *   <li>prod 是 {@code WHERE deleted_at IS NULL} 的部分索引 ⇒ <b>把实体上的声明摘掉</b>，唯一键以 prod 为准
 *       （代价：H2 里这张表没有唯一性保护，这是 JPA 的能力边界，不是疏忽）；</li>
 *   <li>prod 是全量键、且业务上「标识故意不释放」（删了也不许别人再用这个编号）⇒ 保留，登记进清单；</li>
 *   <li>prod 上根本没有对应索引 ⇒ 那是<b>另一个问题</b>（H2 比 prod 严，或 prod 少建了索引），
 *       ⛔ 不要顺手删掉了事，登记进清单并写明待裁定。</li>
 * </ol>
 *
 * <p><b>阳性对照（两层）</b>：① 扫到的实体源码数 &gt; 400——扫错目录时「零违规」是恒真的；
 * ② 清单是<b>全等</b>比较而不是「不得新增」的上限——解析器一旦看不懂某种写法，实测集合会<b>变小</b>，
 * 与清单不等同样报红。上限式棘轮抓不到「闸自己瞎了」（2026-09-07 在数字出处闸上实测过：
 * 放宽一行分类逻辑让真实缺口 6 → 0，而计数式棘轮全程是绿的）。
 */
@DisplayName("软删实体自己声明的唯一约束必须逐个登记（prod 索引形状 + 实体注解两侧都看过）")
class SoftDeleteEntityDeclaredUniqueContractTest {

    private static final Path MAIN = Path.of("src/main/java/com/cretas/aims");
    private static final Pattern SQL_DELETE = Pattern.compile("^\\s*@SQLDelete\\s*\\(", Pattern.MULTILINE);
    private static final Pattern TABLE_ANNO = Pattern.compile("^\\s*@Table\\s*\\(", Pattern.MULTILINE);
    private static final Pattern UNIQUE_CONSTRAINT = Pattern.compile("@UniqueConstraint\\s*\\(");
    private static final Pattern COLUMN_ANNO = Pattern.compile("@Column\\s*\\(");
    private static final Pattern NAME_ATTR = Pattern.compile("name\\s*=\\s*\"(\\w+)\"");
    private static final Pattern COLUMN_NAMES_ATTR = Pattern.compile("columnNames\\s*=\\s*\\{([^}]*)}");
    private static final Pattern UNIQUE_TRUE = Pattern.compile("\\bunique\\s*=\\s*true\\b");

    /**
     * 已登记的「软删实体 + 它自己声明的唯一约束」。key 是 {@code 实体#标签}，value 是<b>为什么允许它留着</b>。
     *
     * <p>⚠️ 每一条后面那个 prod 侧形状都是 2026-09-07 在 docker 的 prod schema 副本（{@code pg_indexes}）上现查的，
     * ⛔ 不是从实体注解反推的——那正是本闸要防的那个循环论证。
     */
    private static final Map<String, String> REGISTERED = new TreeMap<>(Map.ofEntries(
            // ── 类别 A：prod 也是全量键，业务上标识故意不释放 ⇒ 实体声明与 prod 一致，保留 ────────────
            Map.entry("SalesTarget#uk_sales_target_unique",
                    "prod: uk_sales_target_unique (factory_id,owner_id,period,year,period_num) 全量；逐列一致"),
            // ── 软删批 3-A（#712）新补 @SQLDelete 的实体，本闸因此报红并要求逐个分类 ────────────────
            // ⚠️ 这两条是「本闸按设计工作」的产物，不是缺陷：#712 给它们补了 @SQLDelete，而它们各自
            //    声明着唯一约束 ⇒ 闸要求先去看 prod 上那个索引的形状再定。两条的 prod 形状都是现查的。
            Map.entry("RawMaterialType#cols:factory_id,code",
                    "prod: ukoddtjujjj3qfsb0o2nyqvcddi (factory_id,code) 全量、逐列一致；"
                            + "业务口径「编码不回收」——权威查重路径 findCodeConflictIncludingDeleted 也把已删的算进冲突"),

            // ── 类别 A′：prod 也是全量键、逐列一致，但那个键把 deleted_at 写进了键列 ────────────────
            // 🔴 2026-09-07 实测：PG 里 NULL 互不相等，(device_id, channel_id, deleted_at) 这种键
            // 对**活行零约束**——同一个 (DEV-1, 1) 连插两条活行，两条都成功。
            // 也就是说这 4 个键 prod 与实体两侧一致地**什么都没守住**。正确形状是部分索引。
            // 这是 P0 之外的真实生产缺陷，已单独登记，⛔ 不在本闸里顺手改（要迁移 + 要先查存量重复）。
            Map.entry("DahuaDevice#uk_dahua_factory_ip_port",
                    "prod: (factory_id,ip_address,port,deleted_at) 全量；🔴 deleted_at 在键里 ⇒ 对活行零约束，待修"),
            Map.entry("DahuaDeviceChannel#uk_dahua_device_channel",
                    "prod: (device_id,channel_id,deleted_at) 全量；🔴 deleted_at 在键里 ⇒ 对活行零约束，待修"),
            Map.entry("IsapiDevice#uk_factory_ip_port",
                    "prod: (factory_id,ip_address,port,deleted_at) 全量；🔴 deleted_at 在键里 ⇒ 对活行零约束，待修"),
            Map.entry("IsapiDeviceChannel#uk_device_channel",
                    "prod: (device_id,channel_id,deleted_at) 全量；🔴 deleted_at 在键里 ⇒ 对活行零约束，待修"),

            // ── 类别 B：prod 上根本没有对应的唯一索引 ⇒ H2 比 prod 严，待裁定 ──────────────────────
            // ⛔ 不要因为「摘掉就绿了」就摘：prod 少建索引和实体多声明是两个相反的结论，
            // 而它们在 H2 这一侧长得一模一样。这三条要么补 prod 索引，要么摘实体声明，需要业务口径。
            Map.entry("ProductionPlan#column:plan_number",
                    "prod: production_plans 上唯一索引只有 pkey 和部分索引 uk_production_plan_create_request，"
                            + "plan_number 没有任何唯一索引 ⇒ 待裁定"),
            Map.entry("ProductionBatch#column:batch_number",
                    "prod: production_batches 上只有 pkey 和 uk_production_batch_runtime_owner(id,factory_id,product_type_id)，"
                            + "batch_number 没有任何唯一索引 ⇒ 待裁定"),
            Map.entry("ScaleProtocolConfig#column:protocol_code",
                    "prod: scale_protocol_configs 上只有 pkey，protocol_code 没有任何唯一索引 ⇒ 待裁定"),
            Map.entry("MaterialBatch#column:batch_number",
                    "prod: material_batches 上只有 pkey 和 uq_material_batch_source_event"
                            + "(factory_id,source_doc_type,source_event_key)——那是**别的列**上的键，"
                            + "batch_number 没有任何唯一索引 ⇒ 待裁定（与上面三条并成一组，共 4 个字段）"),

            // ── #710（删用户级联）给 User 补 @SQLDelete 之后新增的两条 ──────────────────────────
            Map.entry("User#cols:username",
                    "prod: ukr43af9ap4edm43mmtq01oddj6 (username) 全量、逐列一致；"
                            + "业务口径「用户名不回收」——UserRepository.countByUsernameIncludingDeleted "
                            + "也把已删的算进冲突 ⇒ 类别 A，保留")

            // 📌 `User#column:employee_code` 曾登记在这里，2026-09-07 **移除** ——
            //    不是漏登记，是那条声明本身没了。Steve 裁定「员工工号默认自动生成，不需要修改」
            //    ⇒ 摘掉实体上的 `unique = true`，让声明与 prod 一致（prod 上它没有任何唯一索引）。
            //    ⛔ 不给 prod 补索引：没人要求这个约束，prod 已这样跑很久，真要唯一是将来一条可逆迁移的事。
            //    完整理由 + 那个「82 行只有 1 行有值」的留档观察，都写在 User.employeeCode 字段上方。
            //    ⚠️ `User#cols:username` 仍然登记着（在上面，#710 加的），⛔ 不要在这里重复添加 ——
            //    `Map.ofEntries` 遇到重复键抛 ExceptionInInitializerError，而它长得像「闸自己坏了」
            //    （我改这一条时就先后撞了两次重复键：User#cols:username 与 ScaleProtocolConfig#…）。
    ));

    @Test
    void everyDeclaredUniqueOnASoftDeletedEntityIsRegistered() throws IOException {
        List<String> found = new ArrayList<>();
        int scanned = 0, softDeleted = 0;
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String src = stripComments(Files.readString(p, StandardCharsets.UTF_8));
                if (!src.contains("@Entity")) continue;
                scanned++;
                if (!SQL_DELETE.matcher(src).find()) continue;
                softDeleted++;
                String entity = p.getFileName().toString().replace(".java", "");
                found.addAll(declaredUniques(entity, src));
            }
        }
        found.sort(String::compareTo);
        System.out.println("SOFT_DELETE_DECLARED_UNIQUE_CENSUS=" + found.size()
                + " SOFT_DELETED_ENTITIES=" + softDeleted + " SCANNED_ENTITY_FILES=" + scanned);
        found.forEach(f -> System.out.println("   " + f + "  ->  " + REGISTERED.getOrDefault(f, "🔴 未登记")));

        assertThat(scanned)
                .as("扫到的 @Entity 源码数——扫错目录时「零违规」是恒真的")
                .isGreaterThan(400);

        assertThat(found)
                .as("""
                        软删实体自己声明的唯一约束必须与清单**全等**。
                        多出来的：给实体补 @SQLDelete 之前先去看 prod 上那个索引的形状，按类文档里的三选一处置，再登记。
                        少掉的：要么有人把声明摘了（顺手把清单也改掉），要么**解析器看不懂新的写法**——
                        后者才是这道闸真正要抓的，所以这里是全等而不是上限。""")
                .containsExactlyElementsOf(REGISTERED.keySet());
    }

    /** 抽出这个实体自己声明的唯一约束：{@code @Table} 里的 {@code @UniqueConstraint} + 字段上的 {@code unique = true}。 */
    private static List<String> declaredUniques(String entity, String src) {
        List<String> out = new ArrayList<>();
        Matcher t = TABLE_ANNO.matcher(src);
        if (t.find()) {
            String table = balanced(src, t.end() - 1);
            Matcher uc = UNIQUE_CONSTRAINT.matcher(table);
            while (uc.find()) {
                String body = balanced(table, uc.end() - 1);
                Matcher nm = NAME_ATTR.matcher(body);
                if (nm.find()) {
                    out.add(entity + "#" + nm.group(1));
                } else {
                    Matcher cn = COLUMN_NAMES_ATTR.matcher(body);
                    out.add(entity + "#cols:" + (cn.find() ? cn.group(1).replaceAll("[\\s\"]", "") : "?"));
                }
            }
        }
        Matcher col = COLUMN_ANNO.matcher(src);
        while (col.find()) {
            String body = balanced(src, col.end() - 1);
            if (!UNIQUE_TRUE.matcher(body).find()) continue;
            Matcher nm = NAME_ATTR.matcher(body);
            out.add(entity + "#column:" + (nm.find() ? nm.group(1) : "?"));
        }
        return out;
    }

    /** 从 {@code src[open]} 那个左括号开始取到配平的右括号（不含两端括号）。⛔ 不用正则——括号是嵌套的。 */
    private static String balanced(String src, int open) {
        int depth = 0;
        for (int i = open; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '(' || c == '{' || c == '[') depth++;
            else if (c == ')' || c == '}' || c == ']') {
                depth--;
                if (depth == 0) return src.substring(open + 1, i);
            }
        }
        return src.substring(open + 1);
    }

    /** ⚠️ 必须先去注释：本次改动往每个实体上加了说明注释，注释里就含 {@code @UniqueConstraint} 这个字面串。
     *  第一版改写脚本忘了这一步，读数从 13 变成 12，与「脚本几乎没生效」完全同形。 */
    private static String stripComments(String src) {
        return src.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }
}
