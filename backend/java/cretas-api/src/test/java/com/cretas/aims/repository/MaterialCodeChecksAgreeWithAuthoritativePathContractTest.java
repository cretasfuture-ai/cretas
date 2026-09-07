package com.cretas.aims.repository;

import com.cretas.aims.entity.RawMaterialType;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 物料编码的查重口径必须**只有一个**：权威路径 {@code findCodeConflictIncludingDeleted}（原生，含已删）。
 *
 * <p>2026-09-07 之前有 5 个查重调用点，只有 {@code createMaterialType} 用权威路径，另外三处
 * （前端「编码可用性」预检 / Excel 导入 / 默认物料初始化）用的是**派生查询** {@code existsByFactoryIdAndCode}。
 * 派生查询受类级 {@code @Where(deleted_at IS NULL)} 过滤，**只看得见活行**，而 DB 唯一键
 * {@code ukoddtjujjj3qfsb0o2nyqvcddi (factory_id, code)} 是**全量**的（软删后编码不回收，与权威路径同政策）。
 * ⇒ 软删一个物料之后，那三处会说「编码可用」，然后 {@code save()} 被 DB 拒绝，用户看到的是**原始约束名**。
 * prod 当时已有 4 个软删 {@code raw_material_types} 行 ⇒ 这个分歧当时就活着；而软删批 3-A 给该实体
 * 补了 {@code @SQLDelete}（此前是物理删）⇒ 分歧行从此会增长。
 *
 * <p>本测试守两件事：
 * <ol>
 *   <li><b>机制</b>：两条查询对软删行的**读数确实相反**——这是整件事的根因，也是「为什么必须用权威那条」的证明。</li>
 *   <li><b>接线</b>：{@code RawMaterialTypeServiceImpl} 里**不再有**按编码查重的派生调用。
 *       ⛔ 机制对了不代表调用点改了（形态 B），所以这一条单独守。</li>
 * </ol>
 *
 * <p>⚠️ 同形风险已登记、不在本票：{@code ProductType}（{@code ukd96kjf87wgjnvn95q1qyom8pg}）与
 * {@code SopConfig}（{@code uk_sop_factory_code}）也是「全量唯一键 + 派生 code 查重」，
 * 它们在软删批 3-B 里补 {@code @SQLDelete} 时会踩到同一个坑。
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@DisplayName("物料编码查重: 全部调用点必须与权威路径(含已删)同口径")
class MaterialCodeChecksAgreeWithAuthoritativePathContractTest {

    private static final Path SERVICE =
            Path.of("src/main/java/com/cretas/aims/service/impl/RawMaterialTypeServiceImpl.java");

    @Autowired RawMaterialTypeRepository repository;
    @Autowired EntityManager em;

    @Test
    @DisplayName("机制: 软删之后, 派生查询说「没有」而权威查询说「被已删物料占用」—— 两条读数相反")
    void derivedCheckAndAuthoritativeCheckDisagreeOnSoftDeletedRows() {
        // H2 按实体建出来的外键(created_by -> users)与被守行为无关；本测试只造一张表的一行。
        em.createNativeQuery("SET REFERENTIAL_INTEGRITY FALSE").executeUpdate();
        String factoryId = "F-CODE-" + UUID.randomUUID().toString().substring(0, 6);
        String code = "CODE-" + UUID.randomUUID().toString().substring(0, 6);

        RawMaterialType mt = new RawMaterialType();
        mt.setId(UUID.randomUUID().toString());
        mt.setFactoryId(factoryId);
        mt.setCode(code);
        mt.setName("被软删的物料");
        mt.setUnit("kg");
        mt.setCategory("x");
        mt.setCreatedBy(1L);
        repository.saveAndFlush(mt);

        // 阳性对照: 活着的时候两条查询都说「占用」
        assertThat(repository.existsByFactoryIdAndCode(factoryId, code))
                .as("阳性对照: 活行时派生查询也说占用").isTrue();
        assertThat(repository.findCodeConflictIncludingDeleted(factoryId, code))
                .as("阳性对照: 活行时权威查询也说占用").isPresent();

        // 软删（直接写 deleted_at —— 本测试守的是「两条查询怎么读它」，不是软删本身怎么发生的）
        em.createNativeQuery("UPDATE raw_material_types SET deleted_at = :now WHERE id = :id")
                .setParameter("now", LocalDateTime.now())
                .setParameter("id", mt.getId())
                .executeUpdate();
        em.clear();

        assertThat(repository.existsByFactoryIdAndCode(factoryId, code))
                .as("派生查询受 @Where 过滤, 看不见软删行 —— 它会说「编码可用」")
                .isFalse();

        var conflict = repository.findCodeConflictIncludingDeleted(factoryId, code);
        assertThat(conflict).as("权威查询是原生的, 看得见软删行").isPresent();
        assertThat(conflict.orElseThrow().getDeletedAt())
                .as("而且它能说清占用方已被删除 —— 这正是给用户的那句话所依赖的")
                .isNotNull();

        em.createNativeQuery("SET REFERENTIAL_INTEGRITY TRUE").executeUpdate();
    }

    @Test
    @DisplayName("接线: RawMaterialTypeServiceImpl 里不再有按编码查重的派生调用")
    void serviceNoLongerUsesDerivedCodeChecks() throws IOException {
        String src = stripComments(Files.readString(SERVICE, StandardCharsets.UTF_8));

        // 阳性对照: 确实读到了那个文件（路径写错时「零命中」是恒真的）
        assertThat(src.length()).as("阳性对照: 读到的源码长度").isGreaterThan(20000);
        assertThat(count(src, "findCodeConflictIncludingDeleted"))
                .as("阳性对照: 权威查询的调用点数（2026-09-07: 创建 / 预检 / 导入 / 初始化 = 4 处）")
                .isGreaterThanOrEqualTo(4);

        assertThat(count(src, "existsByFactoryIdAndCode"))
                .as("⛔ 派生的编码存在性查询不许再用于查重 —— 它看不见软删行, "
                        + "会判「可用」然后被 DB 全量唯一键拒绝, 用户看到原始约束名")
                .isZero();
        assertThat(count(src, "findByFactoryIdAndCode(factoryId, code)"))
                .as("⛔ 同上: 按编码取活行来判占用, 同样看不见软删行")
                .isZero();
    }

    private static int count(String src, String needle) {
        int n = 0, i = 0;
        while ((i = src.indexOf(needle, i)) >= 0) { n++; i += needle.length(); }
        return n;
    }

    /** 剥注释——否则解释这条规则的注释本身会把闸咬红。 */
    /**
     * ⚠️ <b>这道闸的正确性依赖这一步</b>，⛔ 不是可有可无的整洁：被测文件里<b>有</b>注释字面提到
     * 被禁的方法名（{@code svc:857} 那条「改前用的是派生查询(…)」的说明注释）——不剥注释，
     * 上面那条 {@code isZero()} 会直接红，而红的原因与「真的还有人在调它」<b>完全同形</b>。
     *
     * <p><b>已知边界</b>（写出来，⛔ 不假装没有）：
     * <ul>
     *   <li>{@code (?m)//.*$} 是<b>无条件</b>剥的 —— 字符串字面量里出现 {@code //}
     *       （如一个 URL {@code "https://…"}）会把那一行从 {@code //} 起截断。
     *       今天被测文件里没有这种写法（已核），但这不是设计上的保证；</li>
     *   <li>同理 {@code /*…*}{@code /} 也不认字符串字面量。⚠️ 而且它必须<b>逐文件</b>用：
     *       2026-09-07 在另一处把多个文件拼起来再剥，一处不成对的 {@code /*} 跨文件吞掉一大段，
     *       读数变成「133 条里 126 条是注释」——荒谬，但看起来是个确定的数。</li>
     * </ul>
     *
     * <p>⇒ 真正承重的是只钉<b>方法名</b>的那条断言；另一条 {@code find…(factoryId, code)}
     * 钉的是<b>参数拼写</b>，换个变量名就绕过去 —— 登记，⛔ 不假装它同样强。
     */
    private static String stripComments(String s) {
        Matcher m = Pattern.compile("(?s)/\\*.*?\\*/").matcher(s);
        return m.replaceAll("").replaceAll("(?m)//.*$", "");
    }
}
