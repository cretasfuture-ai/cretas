package com.cretas.aims.service.source;

import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.source.CreateSourceIndividualRequest;
import com.cretas.aims.dto.source.SourceIndividualDTO;
import com.cretas.aims.dto.source.SourceIndividualLookupResult;
import com.cretas.aims.dto.source.UpdateSourceIndividualRequest;
import com.cretas.aims.entity.source.SourceIndividual;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.source.SourceIndividualRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.UUID;

/**
 * 源头个体档案服务。
 *
 * <h3>唯一性由库保证, 不由这里保证</h3>
 * {@link #create} 里那次 {@code existsBy…} 预检<b>只为了给一句人话错误</b>。
 * 真正的排他是迁移里那条部分唯一索引 —— 并发下 check-then-insert 补不住,
 * 所以下面还有一个 {@link DataIntegrityViolationException} 的接住点。
 * ⛔ 不要把预检删掉(错误信息会退化成 500), 也⛔ 不要以为有了预检就不需要捕获
 * (那正是本仓记过的「唯一性只编码在应用层」那一类缺陷)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SourceIndividualService {

    private final SourceIndividualRepository repository;

    // ── 读 ────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PageResponse<SourceIndividualDTO> list(String factoryId, int page, int size, String keyword) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 200);
        PageRequest pageable = PageRequest.of(safePage - 1, safeSize,
                Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<SourceIndividual> result = StringUtils.hasText(keyword)
                ? repository.searchByKeyword(factoryId, keyword.trim(), pageable)
                : repository.findByFactoryId(factoryId, pageable);

        return PageResponse.of(
                result.getContent().stream().map(SourceIndividualService::toDTO).toList(),
                safePage, safeSize, result.getTotalElements());
    }

    @Transactional(readOnly = true)
    public SourceIndividualDTO getById(String factoryId, String id) {
        return toDTO(require(factoryId, id));
    }

    /**
     * 按物理标识(耳标/脚环号)反查。
     *
     * <p>命中 0 条不是错误 —— 现场扫到一个还没建档的耳标是**正常流程**的第一步,
     * 前端据此跳「登记新个体」。⇒ 这里返回 {@code matchCount = 0} 而不是抛 404。
     */
    @Transactional(readOnly = true)
    public SourceIndividualLookupResult findByTagNumber(String factoryId, String tagNumber) {
        if (!StringUtils.hasText(tagNumber)) {
            throw new BusinessException(400, "耳标号不能为空");
        }
        String normalised = normaliseTag(tagNumber);
        List<SourceIndividual> hits =
                repository.findByFactoryIdAndTagNumberOrderByCreatedAtDesc(factoryId, normalised);
        List<SourceIndividualDTO> all = hits.stream().map(SourceIndividualService::toDTO).toList();

        if (all.size() > 1) {
            // 撞号是真实存在的业务状态, 不是异常 —— 但它必须留痕, 否则没人知道发生过。
            log.warn("耳标号命中多条源头个体: factoryId={}, tagNumber={}, matchCount={}",
                    factoryId, normalised, all.size());
        }
        return SourceIndividualLookupResult.builder()
                .tagNumber(normalised)
                .matchCount(all.size())
                .matched(all.isEmpty() ? null : all.get(0))
                .allMatches(all)
                .build();
    }

    // ── 写 ────────────────────────────────────────────────────────────────

    @Transactional
    public SourceIndividualDTO create(String factoryId, CreateSourceIndividualRequest request) {
        String code = request.getIndividualCode() == null ? null : request.getIndividualCode().trim();
        if (!StringUtils.hasText(code)) {
            throw new BusinessException(400, "档案编号不能为空");
        }
        if (repository.existsByFactoryIdAndIndividualCode(factoryId, code)) {
            throw new BusinessException(409, "档案编号已存在: " + code)
                    .withCode("DUPLICATE")
                    .withHint("换一个档案编号, 或直接打开已有的那份档案");
        }

        String newId = UUID.randomUUID().toString();
        String sireId = requireParentInSameFactory(factoryId, request.getSireIndividualId(), newId, "父本");
        String damId = requireParentInSameFactory(factoryId, request.getDamIndividualId(), newId, "母本");

        SourceIndividual entity = SourceIndividual.builder()
                .id(newId)
                .factoryId(factoryId)
                .individualCode(code)
                .sireIndividualId(sireId)
                .damIndividualId(damId)
                .tagNumber(normaliseTagOrNull(request.getTagNumber()))
                .species(trimToNull(request.getSpecies()))
                .breed(trimToNull(request.getBreed()))
                .sex(trimToNull(request.getSex()))
                .birthDate(request.getBirthDate())
                .originFarm(trimToNull(request.getOriginFarm()))
                .externalGeneBankId(trimToNull(request.getExternalGeneBankId()))
                .dnaSampleCode(trimToNull(request.getDnaSampleCode()))
                .extAttributes(request.getExtAttributes())
                .notes(trimToNull(request.getNotes()))
                .build();
        // 不传就用实体默认 UNSAMPLED —— ⛔ 不在 DTO 里再写一份默认值(两份会漂)。
        if (request.getDnaVerificationStatus() != null) {
            entity.setDnaVerificationStatus(request.getDnaVerificationStatus());
        }

        try {
            return toDTO(repository.saveAndFlush(entity));
        } catch (DataIntegrityViolationException e) {
            // 并发下 check-then-insert 补不住 —— 排他真正发生在库上那条部分唯一索引。
            log.warn("源头个体建档撞唯一索引: factoryId={}, individualCode={}", factoryId, code);
            throw new BusinessException(409, "档案编号已存在: " + code).withCode("DUPLICATE");
        }
    }

    @Transactional
    public SourceIndividualDTO update(String factoryId, String id, UpdateSourceIndividualRequest request) {
        SourceIndividual entity = require(factoryId, id);

        // partial body: 只写回非 null 的字段 (语义与 UpdateMaterialBatchRequest 一致)。
        if (request.getTagNumber() != null) {
            entity.setTagNumber(normaliseTagOrNull(request.getTagNumber()));
        }
        if (request.getSpecies() != null) {
            entity.setSpecies(trimToNull(request.getSpecies()));
        }
        if (request.getBreed() != null) {
            entity.setBreed(trimToNull(request.getBreed()));
        }
        if (request.getSex() != null) {
            entity.setSex(trimToNull(request.getSex()));
        }
        if (request.getBirthDate() != null) {
            entity.setBirthDate(request.getBirthDate());
        }
        if (request.getOriginFarm() != null) {
            entity.setOriginFarm(trimToNull(request.getOriginFarm()));
        }
        if (request.getExternalGeneBankId() != null) {
            entity.setExternalGeneBankId(trimToNull(request.getExternalGeneBankId()));
        }
        if (request.getDnaSampleCode() != null) {
            entity.setDnaSampleCode(trimToNull(request.getDnaSampleCode()));
        }
        if (request.getDnaVerificationStatus() != null) {
            entity.setDnaVerificationStatus(request.getDnaVerificationStatus());
        }
        if (request.getSireIndividualId() != null) {
            entity.setSireIndividualId(
                    requireParentInSameFactory(factoryId, request.getSireIndividualId(), id, "父本"));
        }
        if (request.getDamIndividualId() != null) {
            entity.setDamIndividualId(
                    requireParentInSameFactory(factoryId, request.getDamIndividualId(), id, "母本"));
        }
        if (request.getExtAttributes() != null) {
            entity.setExtAttributes(request.getExtAttributes());
        }
        if (request.getNotes() != null) {
            entity.setNotes(trimToNull(request.getNotes()));
        }
        return toDTO(repository.saveAndFlush(entity));
    }

    /** 软删 —— 实体类上的 {@code @SQLDelete} 把它翻译成 {@code UPDATE … SET deleted_at = NOW()}。 */
    @Transactional
    public void softDelete(String factoryId, String id) {
        repository.delete(require(factoryId, id));
    }

    // ── 内部 ──────────────────────────────────────────────────────────────

    /**
     * 系谱引用校验 —— 空串当「没填」, 其余一律要能在<b>同一个工厂</b>里查得到。
     *
     * <p>三条规则, 每条各自 422 并说清是哪一条:
     * <ol>
     *   <li>不能指向自己(一头个体不可能是自己的父本)</li>
     *   <li>必须是本厂已存在的活档案 —— <b>查不到就报错</b>, ⛔ 不静默置空:
     *       静默置空会让用户以为系谱记上了, 而库里那一格是空的。</li>
     *   <li>别厂的个体也算查不到 —— 库上那条 FK 只保证「这个 id 存在」,
     *       保证不了「它是本厂的」(PK 里没有 factory_id)。两道各管一半, 都不能省。</li>
     * </ol>
     *
     * @param selfId 正在建/改的那一条自己的 id, 用来判「指向自己」
     * @return 归一化后的父本 id(没填时为 {@code null})
     */
    private String requireParentInSameFactory(String factoryId, String rawParentId,
                                              String selfId, String role) {
        String parentId = trimToNull(rawParentId);
        if (parentId == null) {
            return null;
        }
        if (parentId.equals(selfId)) {
            throw new BusinessException(422, role + "不能指向个体自己")
                    .withCode("INVALID_PEDIGREE");
        }
        if (repository.findByIdAndFactoryId(parentId, factoryId).isEmpty()) {
            throw new BusinessException(422,
                    role + "个体档案不存在或不属于本工厂: " + parentId)
                    .withCode("INVALID_PEDIGREE")
                    .withHint("先建立" + role + "的个体档案, 再回来填这一栏");
        }
        return parentId;
    }

    private SourceIndividual require(String factoryId, String id) {
        return repository.findByIdAndFactoryId(id, factoryId)
                .orElseThrow(() -> new BusinessException(404, "源头个体档案不存在: " + id));
    }

    /**
     * 耳标号归一化: 去掉空白与常见分隔符, 只留字母数字。
     *
     * <p>与 {@code VisionVerifyService} 对 VL 读出来的 tagNumber 做的处理<b>是同一个方法</b> ——
     * ⛔ 两边各写一份归一化, 迟早会出现「拍照查得到、手输查不到」。
     */
    public static String normaliseTag(String raw) {
        return raw == null ? null : raw.replaceAll("[^A-Za-z0-9]", "").toUpperCase();
    }

    private static String normaliseTagOrNull(String raw) {
        String n = normaliseTag(raw);
        return StringUtils.hasText(n) ? n : null;
    }

    private static String trimToNull(String s) {
        return StringUtils.hasText(s) ? s.trim() : null;
    }

    public static SourceIndividualDTO toDTO(SourceIndividual e) {
        if (e == null) {
            return null;
        }
        return SourceIndividualDTO.builder()
                .id(e.getId())
                .factoryId(e.getFactoryId())
                .individualCode(e.getIndividualCode())
                .tagNumber(e.getTagNumber())
                .species(e.getSpecies())
                .breed(e.getBreed())
                .sex(e.getSex())
                .birthDate(e.getBirthDate())
                .originFarm(e.getOriginFarm())
                .externalGeneBankId(e.getExternalGeneBankId())
                .dnaSampleCode(e.getDnaSampleCode())
                .dnaVerificationStatus(e.getDnaVerificationStatus())
                // 读不到状态就让它是 null —— ⛔ 不兜底成「未采样」(核心原则 1)。
                .dnaVerificationStatusDisplayName(e.getDnaVerificationStatus() == null
                        ? null : e.getDnaVerificationStatus().getDisplayName())
                .sireIndividualId(e.getSireIndividualId())
                .damIndividualId(e.getDamIndividualId())
                .extAttributes(e.getExtAttributes())
                .notes(e.getNotes())
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .version(e.getVersion())
                .build();
    }
}
