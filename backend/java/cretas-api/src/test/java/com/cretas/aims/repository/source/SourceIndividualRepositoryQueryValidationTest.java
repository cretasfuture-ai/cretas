package com.cretas.aims.repository.source;

import com.cretas.aims.entity.enums.DnaVerificationStatus;
import com.cretas.aims.entity.source.SourceIndividual;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 契约闸: {@link SourceIndividualRepository} 的查询要在真实 Hibernate schema 上跑得通。
 *
 * <p>它同时守两件<b>互相独立</b>的事, 所以下面的断言分组写:
 * <ol>
 *   <li><b>schema 对得上</b> —— 实体 → DDL → 查询能落地。字段改名/类型漂移在这里现形,
 *       而不是等到部署。</li>
 *   <li><b>按耳标号查会返回多条</b> —— 这是本功能里最容易被写成 {@code Optional} 的地方。
 *       耳标号库上<b>没有</b>唯一约束(脱落重挂/跨场撞号), 返回类型必须是 {@code List}。
 *       本仓记过同型缺陷: 「唯一性只编码在返回类型里、库上没约束 ⇒ 失败从写时推迟到读时,
 *       而那个 NonUniqueResultException 长得像查询写错了」。</li>
 * </ol>
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
class SourceIndividualRepositoryQueryValidationTest {

    private static final String FACTORY = "F-TEST-SI";
    private static final String OTHER_FACTORY = "F-OTHER-SI";

    @Autowired TestEntityManager entityManager;
    @Autowired SourceIndividualRepository repository;

    @Test
    @DisplayName("按档案编号查: 命中本厂那条, 不误命中别的工厂的同名编号")
    void findByFactoryIdAndIndividualCodeIsScopedToTheFactory() {
        persist(FACTORY, "SI-0001", "1180245", "CATTLE");
        persist(OTHER_FACTORY, "SI-0001", "9990001", "DUCK");
        flushAndClear();

        assertThat(repository.findByFactoryIdAndIndividualCode(FACTORY, "SI-0001"))
                .get()
                .extracting(SourceIndividual::getSpecies)
                .isEqualTo("CATTLE");

        // 阴性对照: 同一个编号在两个工厂各存在一次, 查 A 厂不许拿到 B 厂那条。
        assertThat(repository.findByFactoryIdAndIndividualCode(OTHER_FACTORY, "SI-0001"))
                .get()
                .extracting(SourceIndividual::getSpecies)
                .isEqualTo("DUCK");

        // 阴性对照: 不存在的编号返回空, 不抛异常、不误命中。
        assertThat(repository.findByFactoryIdAndIndividualCode(FACTORY, "SI-NOPE")).isEmpty();
    }

    /**
     * 🔴 <b>建档时间必须用原生 UPDATE 改, ⛔ 不能用 {@code setCreatedAt()}。</b>
     *
     * <p>2026-09-14 实测: 本方法第一版写的是
     * {@code older.setCreatedAt(LocalDateTime.now().minusDays(30))} ——
     * 单独跑绿, 进全量套件<b>红</b>(expected SI-NEW but was SI-OLD)。
     * 加一行探针把真实落库值打出来:
     * <pre>
     * PROBE_CREATED_AT code=SI-NEW createdAt=2026-09-14T23:03:16.336964
     * PROBE_CREATED_AT code=SI-OLD createdAt=2026-09-14T23:03:16.336463
     * </pre>
     * 两条只差 <b>0.5 毫秒</b> —— 那个 {@code minusDays(30)} <b>一点作用都没有</b>:
     * {@code BaseEntity.createdAt} 带 {@code @CreatedDate} + {@code AuditingEntityListener},
     * 持久化时被审计监听器<b>覆盖成 now()</b>。
     *
     * <p>⚠️ 于是那条排序断言此前靠的是「两次 now() 恰好差了 0.5ms 且方向正确」这种运气,
     * 在时钟精度被截断/两次调用落进同一刻时就翻面。
     * <b>它不是「偶尔抖一下」, 它是一条根本没被我控制的输入。</b>
     * ⇒ 改成 flush 之后用原生 SQL 直接写 {@code created_at}, 审计监听器够不着它。
     */
    @Test
    @DisplayName("🔴 按耳标号查**会**返回多条 —— 耳标号库上没有唯一约束, 返回类型必须是 List")
    void findByTagNumberReturnsEveryMatchBecauseTagsAreNotUnique() {
        // 同一个耳标号两条: 现实里是「旧个体离场、标牌重挂给新个体」。
        SourceIndividual older = persist(FACTORY, "SI-OLD", "1180245", "CATTLE");
        SourceIndividual newer = persist(FACTORY, "SI-NEW", "1180245", "CATTLE");
        entityManager.flush();
        forceCreatedAt(older.getId(), LocalDateTime.now().minusDays(30));
        forceCreatedAt(newer.getId(), LocalDateTime.now());
        entityManager.clear();

        List<SourceIndividual> hits =
                repository.findByFactoryIdAndTagNumberOrderByCreatedAtDesc(FACTORY, "1180245");

        // 硬约束 9 / C″: 先证明「我想造的那个差异真的造出来了」——
        // 少了这一条, 下面的排序断言在「两条时间戳又一样」时会以**同样的方式**红,
        // 而那两种红的修法完全不同(改仪器 vs 改产品)。
        assertThat(hits).hasSize(2);
        assertThat(hits.get(0).getCreatedAt())
                .as("两条的建档时间必须真的拉开了 —— 否则排序断言测的是运气不是行为")
                .isAfter(hits.get(1).getCreatedAt().plusDays(1));

        // ⚠️ 这条断言是本测试的主判据。它一旦变成 hasSize(1), 就等于宣称耳标号唯一 ——
        //    而库上没有任何东西保证那件事。
        // 最近建档的排在最前 —— 重挂标牌时「当前个体」是新的那个。
        assertThat(hits.get(0).getIndividualCode()).isEqualTo("SI-NEW");
        assertThat(hits.get(1).getIndividualCode()).isEqualTo("SI-OLD");

        // 阴性对照: 没建过档的耳标号返回空列表(不是 null, 也不是异常)。
        assertThat(repository.findByFactoryIdAndTagNumberOrderByCreatedAtDesc(FACTORY, "0000000"))
                .isEmpty();
    }

    @Test
    @DisplayName("关键词检索命中 档案编号/耳标号/物种/品种/来源场 任一, 且不跨工厂")
    void searchByKeywordMatchesAnyOfTheIdentityColumns() {
        persist(FACTORY, "SI-A", "1180245", "CATTLE", "西门塔尔", "北山牧场");
        persist(FACTORY, "SI-B", "2290333", "DUCK", "樱桃谷", "南湖基地");
        persist(OTHER_FACTORY, "SI-C", "1180245", "CATTLE", "西门塔尔", "北山牧场");
        flushAndClear();

        PageRequest pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"));

        assertThat(codesOf(repository.searchByKeyword(FACTORY, "1180245", pageable)))
                .containsExactly("SI-A");
        assertThat(codesOf(repository.searchByKeyword(FACTORY, "樱桃谷", pageable)))
                .containsExactly("SI-B");
        assertThat(codesOf(repository.searchByKeyword(FACTORY, "北山", pageable)))
                .containsExactly("SI-A");
        // 大小写不敏感 —— 现场手输 species 不会管大小写。
        assertThat(codesOf(repository.searchByKeyword(FACTORY, "cattle", pageable)))
                .containsExactly("SI-A");
        // 阴性对照: 关键词只命中别厂那条时, 本厂查不到任何东西。
        assertThat(codesOf(repository.searchByKeyword(FACTORY, "SI-C", pageable))).isEmpty();
    }

    @Test
    @DisplayName("软删之后查不到 —— @Where 过滤真的挂在这个实体上")
    void softDeletedRowsDisappearFromEveryQuery() {
        SourceIndividual si = persist(FACTORY, "SI-DEL", "3330001", "PIG");
        flushAndClear();

        // 硬约束 9: 「删了之后查不到」这个读数, 在「压根没建出来」时同样成立。
        // 先证明它**在删之前查得到**, 后面那个「查不到」才有意义。
        assertThat(repository.findByFactoryIdAndIndividualCode(FACTORY, "SI-DEL")).isPresent();

        repository.delete(repository.findById(si.getId()).orElseThrow());
        flushAndClear();

        assertThat(repository.findByFactoryIdAndIndividualCode(FACTORY, "SI-DEL")).isEmpty();
        assertThat(repository.findByFactoryIdAndTagNumberOrderByCreatedAtDesc(FACTORY, "3330001")).isEmpty();
        assertThat(repository.existsByFactoryIdAndIndividualCode(FACTORY, "SI-DEL")).isFalse();
    }

    @Test
    @DisplayName("dnaVerificationStatus 省略时落库为 UNSAMPLED, 不是 NULL")
    void dnaVerificationStatusHasAJavaSideDefault() {
        SourceIndividual si = SourceIndividual.builder()
                .id(UUID.randomUUID().toString())
                .factoryId(FACTORY)
                .individualCode("SI-DEFAULT")
                .build();   // ⚠️ 刻意不设 dnaVerificationStatus
        entityManager.persist(si);
        flushAndClear();

        // 这一条守的是 accepts_rework 同型缺陷: DB 侧 NOT NULL DEFAULT 对 Hibernate
        // **不生效**(它是显式 INSERT NULL, 不是省略该列), 所以默认值必须在 Java 侧。
        assertThat(repository.findByFactoryIdAndIndividualCode(FACTORY, "SI-DEFAULT"))
                .get()
                .extracting(SourceIndividual::getDnaVerificationStatus)
                .isEqualTo(DnaVerificationStatus.UNSAMPLED);
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static List<String> codesOf(Page<SourceIndividual> page) {
        return page.getContent().stream().map(SourceIndividual::getIndividualCode).toList();
    }

    private SourceIndividual persist(String factoryId, String code, String tag, String species) {
        return persist(factoryId, code, tag, species, null, null);
    }

    private SourceIndividual persist(String factoryId, String code, String tag, String species,
                                     String breed, String originFarm) {
        SourceIndividual si = SourceIndividual.builder()
                .id(UUID.randomUUID().toString())
                .factoryId(factoryId)
                .individualCode(code)
                .tagNumber(tag)
                .species(species)
                .breed(breed)
                .originFarm(originFarm)
                .birthDate(LocalDate.of(2024, 3, 1))
                .dnaVerificationStatus(DnaVerificationStatus.UNSAMPLED)
                .build();
        entityManager.persist(si);
        return si;
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    /** 绕开 {@code @CreatedDate} 审计监听器直接写库 —— 见上面那段说明。 */
    private void forceCreatedAt(String id, LocalDateTime at) {
        entityManager.getEntityManager()
                .createNativeQuery("UPDATE source_individuals SET created_at = :at WHERE id = :id")
                .setParameter("at", at)
                .setParameter("id", id)
                .executeUpdate();
    }
}
