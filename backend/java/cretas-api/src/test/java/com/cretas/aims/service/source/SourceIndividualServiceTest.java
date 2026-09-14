package com.cretas.aims.service.source;

import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.source.CreateSourceIndividualRequest;
import com.cretas.aims.dto.source.SourceIndividualDTO;
import com.cretas.aims.dto.source.SourceIndividualLookupResult;
import com.cretas.aims.dto.source.UpdateSourceIndividualRequest;
import com.cretas.aims.entity.enums.DnaVerificationStatus;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.source.SourceIndividualRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link SourceIndividualService} 的行为闸 —— 跑在真 Hibernate/H2 上, 不是 mock 仓储。
 *
 * <h3>⚠️ 这道闸看不见什么(代理判据边界, 写明)</h3>
 * <b>并发建档撞唯一索引那条路在这里跑不出来</b>: 唯一性由迁移
 * {@code V20261101_42} 里那条<b>部分</b>唯一索引承担, 而 H2 侧是 Hibernate
 * {@code ddl-auto} 建表、<b>不跑 Flyway</b>, 所以那个索引在测试库里不存在。
 * ⇒ 下面测到的是 {@code existsBy…} 那次<b>预检</b>(给人话错误), ⛔ 不是真正的排他。
 * 真正的排他只在 PG 上成立, 由迁移本身 + {@code SourceIndividualRepositoryQueryValidationTest}
 * 之外的真 PG 契约测试负责。⛔ 不要因为这道闸绿了就以为并发建档被守住了。
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
class SourceIndividualServiceTest {

    private static final String FACTORY = "F-SVC-SI";

    @Autowired TestEntityManager entityManager;
    @Autowired SourceIndividualRepository repository;

    private SourceIndividualService service;

    @BeforeEach
    void setUp() {
        service = new SourceIndividualService(repository);
    }

    @Test
    @DisplayName("建档: 只给编号也能建, 状态默认 UNSAMPLED, 耳标号被归一化")
    void createWithMinimalFields() {
        SourceIndividualDTO dto = service.create(FACTORY, CreateSourceIndividualRequest.builder()
                .individualCode("  SI-0001  ")
                .tagNumber("118-02 45")
                .build());

        assertThat(dto.getId()).isNotBlank();
        assertThat(dto.getIndividualCode()).isEqualTo("SI-0001");   // trim
        assertThat(dto.getTagNumber()).isEqualTo("1180245");        // 归一化: 去掉横杠与空格
        assertThat(dto.getDnaVerificationStatus()).isEqualTo(DnaVerificationStatus.UNSAMPLED);
        assertThat(dto.getDnaVerificationStatusDisplayName()).isEqualTo("未采样");
        // ⛔ 没填的字段就是 null —— 不许被兜底成空串或「未知」。
        assertThat(dto.getSpecies()).isNull();
        assertThat(dto.getBreed()).isNull();
        assertThat(dto.getBirthDate()).isNull();
    }

    @Test
    @DisplayName("建档撞编号 ⇒ 409 DUPLICATE (预检给的人话错误)")
    void duplicateIndividualCodeIs409() {
        service.create(FACTORY, CreateSourceIndividualRequest.builder().individualCode("SI-DUP").build());
        flush();

        assertThatThrownBy(() -> service.create(FACTORY,
                CreateSourceIndividualRequest.builder().individualCode("SI-DUP").build()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    assertThat(((BusinessException) e).getCode()).isEqualTo(409);
                    assertThat(((BusinessException) e).getErrorCode()).isEqualTo("DUPLICATE");
                });

        // 阳性对照: 换个工厂用同一个编号必须建得出来 —— 唯一性是**工厂内**唯一,
        // 不是全局唯一。少了这一条, 上面那个 409 也可能是「编号全局唯一」造成的。
        assertThat(service.create("F-SVC-OTHER",
                CreateSourceIndividualRequest.builder().individualCode("SI-DUP").build()).getId())
                .isNotBlank();
    }

    /**
     * 🔴 这条是<b>变异对照抓出来的</b>, 不是我事先想到的(2026-09-14)。
     *
     * <p>给实体默认值做变异(把 {@code dnaVerificationStatus} 的初始化改成 {@code null})之后,
     * 插入撞的是 {@code NOT NULL} 约束 —— 而 {@code create} 里那个
     * {@code catch (DataIntegrityViolationException)} <b>无条件</b>把它翻译成
     * 「档案编号已存在: SI-0001」。13 条测试全部以这句<b>完全错误</b>的业务话术失败。
     *
     * <p>现场看到的会是「换个编号再试」, 而真相是数据库列不允许 NULL —— 换一百个编号都没用。
     * ⇒ <b>认不出来的完整性冲突必须原样抛出去</b>, ⛔ 不许硬套一个听起来合理的业务解释。
     *
     * <p>这里用一个<b>超长编号</b>(65 字符 &gt; VARCHAR(64))制造一次<b>非</b>重复的完整性冲突,
     * 断言它<b>不</b>被说成「档案编号已存在」。
     * ⚠️ 直接调 service 会绕过 DTO 上的 {@code @Size} 校验 —— 这里正是要绕过它,
     * 才能把数据库那一层的行为暴露出来。
     */
    @Test
    @DisplayName("🔴 非重复的完整性冲突 ⛔ 不许被说成「档案编号已存在」")
    void nonDuplicateIntegrityViolationIsNotMislabelled() {
        String tooLong = "X".repeat(65);   // individual_code 是 VARCHAR(64)

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() ->
                service.create(FACTORY, CreateSourceIndividualRequest.builder()
                        .individualCode(tooLong).build()));

        // 阳性对照: 它**确实**失败了 —— 否则下面那条 "不含某句话" 是恒真的
        // (硬约束 9: 验证「X 没发生」要配一个「Y 一定会发生」的读数)。
        assertThat(thrown)
                .as("65 字符的编号必须写不进 VARCHAR(64) —— 没失败说明这条对照没量到东西")
                .isNotNull();
        assertThat(String.valueOf(thrown.getMessage()))
                .as("把「值太长」说成「编号已存在」= 让用户去换一百个编号都没用")
                .doesNotContain("档案编号已存在");

        // ⚠️ 另一侧的阳性对照(「真的重复时那句话出得来」)在
        // {@link #duplicateIndividualCodeIs409()} 里 —— ⛔ 不放在本方法里:
        // 上面那次失败已经把当前事务标成 rollback-only, 同一个事务里再建一条会以
        // **另一个**原因炸掉, 读数就不再指向本方法要测的东西了。
    }

    @Test
    @DisplayName("更新是 partial: 只写回非 null 的字段, 没传的原样保留")
    void updateIsPartial() {
        String id = service.create(FACTORY, CreateSourceIndividualRequest.builder()
                .individualCode("SI-UPD")
                .species("CATTLE")
                .breed("西门塔尔")
                .originFarm("北山牧场")
                .birthDate(LocalDate.of(2024, 1, 15))
                .build()).getId();
        flush();

        SourceIndividualDTO updated = service.update(FACTORY, id, UpdateSourceIndividualRequest.builder()
                .dnaVerificationStatus(DnaVerificationStatus.MATCHED)
                .externalGeneBankId("GB-77")
                .build());

        assertThat(updated.getDnaVerificationStatus()).isEqualTo(DnaVerificationStatus.MATCHED);
        assertThat(updated.getDnaVerificationStatusDisplayName()).isEqualTo("已比中");
        assertThat(updated.getExternalGeneBankId()).isEqualTo("GB-77");
        // 没传的字段原样保留 —— 这是 partial 语义的另一半, 少了它「partial」只是一半的断言。
        assertThat(updated.getSpecies()).isEqualTo("CATTLE");
        assertThat(updated.getBreed()).isEqualTo("西门塔尔");
        assertThat(updated.getOriginFarm()).isEqualTo("北山牧场");
        assertThat(updated.getBirthDate()).isEqualTo(LocalDate.of(2024, 1, 15));
        // 编号不可改 —— UpdateSourceIndividualRequest 里压根没有这个字段。
        assertThat(updated.getIndividualCode()).isEqualTo("SI-UPD");
    }

    /**
     * ⚠️ 只断言<b>写进去了</b>, 不断言读回来 —— 这不是偷懒, 是 H2 与 PG 的真实差异:
     * H2 的 {@code JSON} 类型把值读回来时带一层 JSON 字符串转义,
     * hypersistence 的 {@code JsonBinaryType} 读到的是
     * {@code "{\"penNumber\":\"A-12\"}"} 这种<b>被引号包起来的</b>串, 当场
     * {@code IllegalArgumentException: cannot be transformed to Json object}。
     *
     * <p>🔴 这是<b>环境</b>限制, 不是产品缺陷: 全仓 136 个实体用同一种
     * {@code @Type(JsonBinaryType.class) + columnDefinition = "jsonb"} 写法在 prod 上跑着。
     * ⇒ 在 H2 上写一条「读回来内容不变」的断言, 量的是 H2 不是 prod(形态 B‴: 桩/环境的形状
     * 要能在生产上出现)。这里改成用<b>原生查询</b>读那一列的字面内容 ——
     * 它证明序列化确实落了库, 而且绕开了 H2 的读回路。
     *
     * <p>⛔ 别把这条改成 {@code service.getById(...).getExtAttributes()} 让它「更完整」,
     * 那只会得到一条在 H2 上恒红的断言。JSONB 读回路的真实覆盖要靠真 PG 的契约测试。
     */
    @Test
    @DisplayName("extAttributes 序列化后真的落进 jsonb 列 (H2 读回路有已知限制, 见方法注释)")
    void extAttributesArePersistedAsJson() {
        String id = service.create(FACTORY, CreateSourceIndividualRequest.builder()
                .individualCode("SI-EXT")
                .extAttributes(Map.of("penNumber", "A-12", "entryWeightKg", 420))
                .build()).getId();
        flush();

        Object raw = entityManager.getEntityManager()
                .createNativeQuery("SELECT CAST(ext_attributes AS VARCHAR) FROM source_individuals WHERE id = :id")
                .setParameter("id", id)
                .getSingleResult();

        assertThat(String.valueOf(raw))
                .contains("penNumber")
                .contains("A-12")
                .contains("entryWeightKg")
                .contains("420");

        // 阴性对照: 没传 extAttributes 的那条, 列是 NULL —— ⛔ 不是 `{}`。
        // (「没填」和「填了个空对象」在下游是两件事。)
        String plainId = service.create(FACTORY, CreateSourceIndividualRequest.builder()
                .individualCode("SI-EXT-NONE").build()).getId();
        flush();
        Object none = entityManager.getEntityManager()
                .createNativeQuery("SELECT CAST(ext_attributes AS VARCHAR) FROM source_individuals WHERE id = :id")
                .setParameter("id", plainId)
                .getSingleResult();
        assertThat(none).isNull();
    }

    @Test
    @DisplayName("🔴 按耳标号查: 0 / 1 / 多 三种情况在 matchCount 上是三个不同读数")
    void lookupByTagDistinguishesThreeCases() {
        // 0 条 —— 扫到一个还没建档的耳标是正常流程, ⛔ 不该是 404。
        SourceIndividualLookupResult none = service.findByTagNumber(FACTORY, "0000000");
        assertThat(none.getMatchCount()).isZero();
        assertThat(none.getMatched()).isNull();
        assertThat(none.getAllMatches()).isEmpty();

        // 1 条
        service.create(FACTORY, CreateSourceIndividualRequest.builder()
                .individualCode("SI-T1").tagNumber("1180245").build());
        flush();
        SourceIndividualLookupResult one = service.findByTagNumber(FACTORY, "1180245");
        assertThat(one.getMatchCount()).isEqualTo(1);
        assertThat(one.getMatched().getIndividualCode()).isEqualTo("SI-T1");

        // 多条 —— 耳标重挂。库上没有唯一约束, 所以这**真的**会发生。
        service.create(FACTORY, CreateSourceIndividualRequest.builder()
                .individualCode("SI-T2").tagNumber("1180245").build());
        flush();
        SourceIndividualLookupResult many = service.findByTagNumber(FACTORY, "1180245");
        assertThat(many.getMatchCount()).isEqualTo(2);
        assertThat(many.getAllMatches()).hasSize(2);
        // matched 仍然给一条(最近建档的), 但 matchCount=2 让调用方知道它是「N 选 1」。
        assertThat(many.getMatched()).isNotNull();
    }

    @Test
    @DisplayName("查询时耳标号也走同一套归一化 —— 手输带横杠也查得到")
    void lookupNormalisesTheQueryToo() {
        service.create(FACTORY, CreateSourceIndividualRequest.builder()
                .individualCode("SI-NORM").tagNumber("1180245").build());
        flush();

        assertThat(service.findByTagNumber(FACTORY, "118-0245").getMatchCount()).isEqualTo(1);
        assertThat(service.findByTagNumber(FACTORY, " 1180245 ").getMatchCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("软删之后 getById 报 404, 列表里也没有了")
    void softDeleteRemovesItFromReads() {
        String id = service.create(FACTORY, CreateSourceIndividualRequest.builder()
                .individualCode("SI-DEL").tagNumber("3330001").build()).getId();
        flush();

        // 硬约束 9: 先证明删之前读得到, 「删之后读不到」才有意义。
        assertThat(service.getById(FACTORY, id).getIndividualCode()).isEqualTo("SI-DEL");
        PageResponse<SourceIndividualDTO> before = service.list(FACTORY, 1, 20, null);
        assertThat(before.getTotalElements()).isEqualTo(1L);

        service.softDelete(FACTORY, id);
        flush();

        assertThatThrownBy(() -> service.getById(FACTORY, id))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(404));
        assertThat(service.list(FACTORY, 1, 20, null).getTotalElements()).isZero();
    }

    @Test
    @DisplayName("跨工厂隔离: 拿别厂的 id 读/改/删一律 404")
    void crossFactoryAccessIs404() {
        String id = service.create(FACTORY, CreateSourceIndividualRequest.builder()
                .individualCode("SI-ISO").build()).getId();
        flush();

        assertThatThrownBy(() -> service.getById("F-SVC-INTRUDER", id))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.update("F-SVC-INTRUDER", id,
                UpdateSourceIndividualRequest.builder().notes("x").build()))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.softDelete("F-SVC-INTRUDER", id))
                .isInstanceOf(BusinessException.class);

        // 阳性对照: 本厂读得到 —— 否则上面三条 404 可能只是「这条记录根本不存在」。
        assertThat(service.getById(FACTORY, id).getIndividualCode()).isEqualTo("SI-ISO");
    }

    @Test
    @DisplayName("列表关键词检索命中多列, 且分页元数据正确")
    void listWithKeyword() {
        service.create(FACTORY, CreateSourceIndividualRequest.builder()
                .individualCode("SI-K1").species("CATTLE").breed("西门塔尔").build());
        service.create(FACTORY, CreateSourceIndividualRequest.builder()
                .individualCode("SI-K2").species("DUCK").breed("樱桃谷").build());
        flush();

        PageResponse<SourceIndividualDTO> all = service.list(FACTORY, 1, 20, null);
        assertThat(all.getTotalElements()).isEqualTo(2L);

        PageResponse<SourceIndividualDTO> ducks = service.list(FACTORY, 1, 20, "DUCK");
        assertThat(ducks.getTotalElements()).isEqualTo(1L);
        assertThat(ducks.getContent().get(0).getIndividualCode()).isEqualTo("SI-K2");
    }

    // ── 系谱 (父本 / 母本) ────────────────────────────────────────────────

    @Test
    @DisplayName("系谱正向: 父本/母本引用同厂已存在的个体, 往返读得回来")
    void pedigreeRoundTrip() {
        String sire = service.create(FACTORY, CreateSourceIndividualRequest.builder()
                .individualCode("SI-SIRE").species("CATTLE").sex("公").build()).getId();
        String dam = service.create(FACTORY, CreateSourceIndividualRequest.builder()
                .individualCode("SI-DAM").species("CATTLE").sex("母").build()).getId();
        flush();

        SourceIndividualDTO calf = service.create(FACTORY, CreateSourceIndividualRequest.builder()
                .individualCode("SI-CALF")
                .sireIndividualId(sire)
                .damIndividualId(dam)
                .build());
        flush();

        assertThat(calf.getSireIndividualId()).isEqualTo(sire);
        assertThat(calf.getDamIndividualId()).isEqualTo(dam);
        // 读回来也要有 —— 「写进去了」和「读得到」是两件事(形态 B: 投影可能丢)。
        SourceIndividualDTO reread = service.getById(FACTORY, calf.getId());
        assertThat(reread.getSireIndividualId()).isEqualTo(sire);
        assertThat(reread.getDamIndividualId()).isEqualTo(dam);

        // 阴性对照: 不填系谱时就是 null, ⛔ 不被兜底成空串。
        SourceIndividualDTO orphan = service.create(FACTORY,
                CreateSourceIndividualRequest.builder().individualCode("SI-NOPED").build());
        assertThat(orphan.getSireIndividualId()).isNull();
        assertThat(orphan.getDamIndividualId()).isNull();
    }

    @Test
    @DisplayName("🔴 系谱指向自己 ⇒ 422 INVALID_PEDIGREE (只有 update 到得了这一支)")
    void selfReferenceIsRejected() {
        String id = service.create(FACTORY, CreateSourceIndividualRequest.builder()
                .individualCode("SI-SELF").build()).getId();
        flush();

        assertThatThrownBy(() -> service.update(FACTORY, id,
                UpdateSourceIndividualRequest.builder().sireIndividualId(id).build()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    assertThat(((BusinessException) e).getCode()).isEqualTo(422);
                    assertThat(((BusinessException) e).getErrorCode()).isEqualTo("INVALID_PEDIGREE");
                })
                .hasMessageContaining("父本");

        assertThatThrownBy(() -> service.update(FACTORY, id,
                UpdateSourceIndividualRequest.builder().damIndividualId(id).build()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("母本");

        // 阳性对照: 指向**别的**个体是合法的 —— 否则上面两条 422 也可能只是
        // 「这个接口根本改不了系谱」。
        String other = service.create(FACTORY, CreateSourceIndividualRequest.builder()
                .individualCode("SI-SELF-OTHER").build()).getId();
        flush();
        assertThat(service.update(FACTORY, id,
                UpdateSourceIndividualRequest.builder().sireIndividualId(other).build())
                .getSireIndividualId()).isEqualTo(other);
    }

    @Test
    @DisplayName("🔴 系谱跨厂引用 ⇒ 422, ⛔ 不静默置空")
    void crossFactoryPedigreeIsRejected() {
        String foreignSire = service.create("F-SVC-FOREIGN", CreateSourceIndividualRequest.builder()
                .individualCode("SI-FOREIGN-SIRE").build()).getId();
        flush();

        assertThatThrownBy(() -> service.create(FACTORY, CreateSourceIndividualRequest.builder()
                .individualCode("SI-XFACTORY")
                .sireIndividualId(foreignSire)
                .build()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    assertThat(((BusinessException) e).getCode()).isEqualTo(422);
                    assertThat(((BusinessException) e).getErrorCode()).isEqualTo("INVALID_PEDIGREE");
                });

        // ⛔ 关键: 被拒之后**整条建档都不该落库**。
        //    静默置空(建了档但系谱是空的)才是这道校验真正要防的那件事 ——
        //    用户会以为系谱记上了, 而库里那一格是空的。
        assertThat(service.list(FACTORY, 1, 20, "SI-XFACTORY").getTotalElements()).isZero();

        // 不存在的 id 同样 422(与跨厂走同一条判据: 本厂查不到就是查不到)。
        assertThatThrownBy(() -> service.create(FACTORY, CreateSourceIndividualRequest.builder()
                .individualCode("SI-GHOST")
                .damIndividualId("no-such-individual")
                .build()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("母本");

        // 阳性对照: 同厂的父本可以用 —— 上面两条 422 不是「这个字段根本没接上」。
        String localSire = service.create(FACTORY, CreateSourceIndividualRequest.builder()
                .individualCode("SI-LOCAL-SIRE").build()).getId();
        flush();
        assertThat(service.create(FACTORY, CreateSourceIndividualRequest.builder()
                .individualCode("SI-LOCAL-CALF").sireIndividualId(localSire).build())
                .getSireIndividualId()).isEqualTo(localSire);
    }

    @Test
    @DisplayName("空耳标号 ⇒ 400, ⛔ 不是「查了没有」")
    void blankTagIsARequestError() {
        assertThatThrownBy(() -> service.findByTagNumber(FACTORY, "  "))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(400));
    }

    private void flush() {
        entityManager.flush();
        entityManager.clear();
    }
}
