package com.cretas.aims.service.processsecret;

import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.processsecret.ProcessSecretAction;
import com.cretas.aims.entity.processsecret.ProcessSecretExternalPackage;
import com.cretas.aims.entity.processsecret.ProcessSecretExternalResult;
import com.cretas.aims.entity.processsecret.ProcessSecretPackageStatus;
import com.cretas.aims.entity.processsecret.ProcessSecretPolicy;
import com.cretas.aims.entity.processsecret.ProcessSecretStorageMode;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.processsecret.ProcessSecretExternalPackageRepository;
import com.cretas.aims.repository.processsecret.ProcessSecretExternalResultRepository;
import com.cretas.aims.repository.processsecret.ProcessSecretPolicyRepository;
import com.cretas.aims.security.AuthenticatedActor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * B 档「核心工艺不入云」的云端边界契约 (方案 §6)。
 *
 * <ul>
 *   <li>云端只登记工艺包引用 + 版本 + 产出单位 + 客户侧执行端的 Ed25519 <b>公钥</b>; 私钥与配方只在客户侧。</li>
 *   <li>结果回传必须带执行端签名; 绑定 工厂 / 包版本 / 批次 / 单位 / 事件 ID, 同一事件只收一次。</li>
 *   <li>验签只证明来源与完整性, 不证明生产事实: 结果一律 PENDING_REVIEW, ⛔ 不直接改库存或成本。</li>
 * </ul>
 */
@Service
public class ProcessSecretExternalService {

    static final Pattern REF = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}");
    static final Pattern EVENT_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.:-]{7,127}");
    static final Duration MAX_SIGNATURE_AGE = Duration.ofDays(7);
    static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(10);
    static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private final ProcessSecretPolicyRepository policyRepository;
    private final ProcessSecretExternalPackageRepository packageRepository;
    private final ProcessSecretExternalResultRepository resultRepository;
    private final ProductionBatchRepository batchRepository;
    private final BomRecipeRepository recipeRepository;
    private final ProcessSecretPolicyService policyService;
    private final ProcessSecretAccessService accessService;
    private final com.cretas.aims.service.unit.UnitContractService unitContractService;

    public ProcessSecretExternalService(ProcessSecretPolicyRepository policyRepository,
                                        ProcessSecretExternalPackageRepository packageRepository,
                                        ProcessSecretExternalResultRepository resultRepository,
                                        ProductionBatchRepository batchRepository,
                                        BomRecipeRepository recipeRepository,
                                        ProcessSecretPolicyService policyService,
                                        ProcessSecretAccessService accessService,
                                        com.cretas.aims.service.unit.UnitContractService unitContractService) {
        this.policyRepository = policyRepository;
        this.packageRepository = packageRepository;
        this.resultRepository = resultRepository;
        this.batchRepository = batchRepository;
        this.recipeRepository = recipeRepository;
        this.policyService = policyService;
        this.accessService = accessService;
        this.unitContractService = unitContractService;
    }

    public record PackageRequest(String productTypeId, String packageRef, String packageVersion,
                                 String outputUnit, String executorPublicKey) { }

    public record PackageView(Long id, String productTypeId, String packageRef, String packageVersion,
                              String outputUnit, ProcessSecretPackageStatus status, LocalDateTime createdAt) { }

    /**
     * {@code cloudCopyStatus}: B 档产品在云端是否还留着历史完整工艺 (BOM 配方)。
     * 有 = HISTORICAL_CLOUD_COPY_PENDING_DISPOSAL —— 这时 ⛔ 不能对外宣称「从未入云」(方案 §6.3)。
     */
    public record PackageStatusView(String productTypeId, ProcessSecretStorageMode storageMode,
                                    String cloudCopyStatus, List<PackageView> packages) { }

    public record ResultRequest(String packageRef, String packageVersion, String eventId, Long productionBatchId,
                                BigDecimal outputQuantity, String outputUnit, String signedAt, String signature) { }

    public record ResultView(Long id, String eventId, Long productionBatchId, BigDecimal outputQuantity,
                             String outputUnit, String reviewStatus, boolean duplicate, LocalDateTime receivedAt) { }

    @Transactional
    public PackageView registerPackage(AuthenticatedActor actor, String factoryId, PackageRequest request) {
        if (request == null) throw new BusinessException(400, "请求体不能为空");
        requireText(request.productTypeId(), "productTypeId");
        ProcessSecretPolicy policy = policyService.requirePolicy(factoryId, request.productTypeId());
        policyService.requireOwner(actor, factoryId, policy);
        if (policy.getStorageMode() != ProcessSecretStorageMode.CUSTOMER_HELD) {
            throw new BusinessException(409, "只有「核心工艺不入云」的产品才能登记客户侧工艺包").withHint("先在保密设置里把该产品切换为「核心工艺不入云」，再登记工艺包");
        }
        requireRef(request.packageRef(), "packageRef");
        requireRef(request.packageVersion(), "packageVersion");
        requireText(request.outputUnit(), "outputUnit");
        if (request.outputUnit().trim().length() > 20) throw new BusinessException(400, "outputUnit 过长");
        // 单位同一性问单位契约 (同义写法如 kg / 千克 视为同一单位); 契约不认识的单位不允许登记
        if (!unitContractService.areEquivalent(factoryId, request.outputUnit().trim(), request.outputUnit().trim())) {
            throw new BusinessException(400, "outputUnit 不是系统认可的单位: " + request.outputUnit().trim());
        }
        parsePublicKey(request.executorPublicKey());
        if (packageRepository.findByFactoryIdAndPackageRefAndPackageVersion(factoryId, request.packageRef(),
                request.packageVersion()).isPresent()) {
            throw new BusinessException(409, "该工艺包版本已登记").withHint("换一个新的版本号登记，或在列表里查看已登记的版本");
        }
        ProcessSecretExternalPackage saved = packageRepository.save(ProcessSecretExternalPackage.builder()
                .factoryId(factoryId).productTypeId(request.productTypeId())
                .packageRef(request.packageRef()).packageVersion(request.packageVersion())
                .outputUnit(request.outputUnit().trim())
                .executorPublicKey(request.executorPublicKey().trim())
                .createdBy(actor.id())
                .build());
        if (!accessService.audit(factoryId, request.productTypeId(), actor, ProcessSecretAction.REGISTER_PACKAGE,
                "ALLOW", "登记工艺包", request.packageRef() + "@" + request.packageVersion())) {
            throw new BusinessException(503, "保密审计暂不可用，本次登记未生效");
        }
        return view(saved);
    }

    @Transactional(readOnly = true)
    public PackageStatusView packageStatus(AuthenticatedActor actor, String factoryId, String productTypeId) {
        if (actor == null || actor.isPlatformAdmin() || !factoryId.equals(actor.factoryId())) {
            throw new BusinessException(403, "只有本厂用户可以查看工艺包状态");
        }
        ProcessSecretPolicy policy = policyService.requirePolicy(factoryId, productTypeId);
        String cloudCopy = policy.getStorageMode() != ProcessSecretStorageMode.CUSTOMER_HELD
                ? "NOT_APPLICABLE"
                : recipeRepository.countByFactoryIdAndProductTypeId(factoryId, productTypeId) > 0
                    ? "HISTORICAL_CLOUD_COPY_PENDING_DISPOSAL"
                    : "NONE";
        List<PackageView> packages = packageRepository
                .findByFactoryIdAndProductTypeIdOrderByCreatedAtDesc(factoryId, productTypeId)
                .stream().map(ProcessSecretExternalService::view).toList();
        return new PackageStatusView(productTypeId, policy.getStorageMode(), cloudCopy, packages);
    }

    @Transactional
    public ResultView submitResult(AuthenticatedActor actor, String factoryId, ResultRequest request) {
        if (actor == null || actor.isPlatformAdmin() || !factoryId.equals(actor.factoryId())) {
            throw new BusinessException(403, "只有本厂账号可以回传客户侧结果");
        }
        if (request == null) throw new BusinessException(400, "请求体不能为空");
        requireRef(request.packageRef(), "packageRef");
        requireRef(request.packageVersion(), "packageVersion");
        if (request.eventId() == null || !EVENT_ID.matcher(request.eventId()).matches()) {
            throw new BusinessException(400, "eventId 格式不正确 (8-128 位字母数字及 _.:-)");
        }
        if (request.productionBatchId() == null) throw new BusinessException(400, "productionBatchId 不能为空");
        if (request.outputQuantity() == null || request.outputQuantity().signum() <= 0) {
            throw new BusinessException(400, "outputQuantity 必须大于 0；没有结果请不要回传 0");
        }
        requireStorableQuantity(request.outputQuantity());
        requireText(request.outputUnit(), "outputUnit");
        requireText(request.signature(), "signature");
        LocalDateTime signedAt = parseSignedAt(request.signedAt());

        ProcessSecretExternalPackage pkg = packageRepository.findByFactoryIdAndPackageRefAndPackageVersion(
                        factoryId, request.packageRef(), request.packageVersion())
                .orElseThrow(() -> new BusinessException(409, "工艺包版本未登记或不匹配").withHint("核对执行端使用的工艺包编号与版本是否和云端登记一致"));
        if (pkg.getStatus() != ProcessSecretPackageStatus.ACTIVE) {
            throw new BusinessException(409, "工艺包版本已停用").withHint("请执行端改用当前有效的工艺包版本");
        }
        // 产品已不再是「客户侧保存」(改回 A 档或关闭保密) 时, 旧包的结果一律不收
        boolean stillCustomerHeld = policyRepository.findByFactoryIdAndProductTypeId(factoryId, pkg.getProductTypeId())
                .map(p -> p.getStorageMode() == ProcessSecretStorageMode.CUSTOMER_HELD).orElse(false);
        if (!stillCustomerHeld) {
            throw new BusinessException(409, "该产品已不是「核心工艺不入云」模式，不再接收客户侧结果").withHint("如需继续接收，请保密负责人把产品切回「核心工艺不入云」");
        }
        String canonical = canonicalPayload(factoryId, request);
        if (!verify(pkg.getExecutorPublicKey(), canonical, request.signature())) {
            accessService.audit(factoryId, pkg.getProductTypeId(), actor, ProcessSecretAction.SUBMIT_RESULT,
                    "DENY", "签名无效", request.eventId());
            throw new BusinessException(403, "结果签名无效，未接收");
        }
        String digest = sha256Hex(canonical);

        Optional<ProcessSecretExternalResult> existing = resultRepository.findByFactoryIdAndEventId(factoryId,
                request.eventId());
        if (existing.isPresent()) {
            if (digest.equals(existing.get().getPayloadDigest())) {
                return view(existing.get(), true);
            }
            throw new BusinessException(409, "同一事件 ID 已提交过不同内容，已拒绝").withHint("同一结果重发请保持内容不变；新的结果请使用新的事件 ID");
        }

        if (!unitContractService.areEquivalent(factoryId, pkg.getOutputUnit(), request.outputUnit().trim())) {
            throw new BusinessException(409, "产出单位与工艺包登记不一致").withHint("按工艺包登记的产出单位回传数量");
        }
        ProductionBatch batch = batchRepository.findByIdAndFactoryId(request.productionBatchId(), factoryId)
                .orElseThrow(() -> new BusinessException(409, "生产批次不存在或不属于本厂").withHint("核对批次编号是否属于本厂"));
        if (!pkg.getProductTypeId().equals(batch.getProductTypeId())) {
            throw new BusinessException(409, "生产批次的产品与工艺包不一致").withHint("选择该工艺包对应产品的生产批次");
        }
        LocalDateTime now = LocalDateTime.now(BUSINESS_ZONE);
        if (signedAt.isAfter(now.plus(MAX_CLOCK_SKEW)) || signedAt.isBefore(now.minus(MAX_SIGNATURE_AGE))) {
            throw new BusinessException(409, "签名时间超出允许范围，请重新签发").withHint("核对执行端时钟后重新签名再提交");
        }

        ProcessSecretExternalResult saved;
        try {
            saved = resultRepository.saveAndFlush(ProcessSecretExternalResult.builder()
                    .factoryId(factoryId).packageId(pkg.getId()).eventId(request.eventId())
                    .productionBatchId(batch.getId()).outputQuantity(request.outputQuantity())
                    .outputUnit(pkg.getOutputUnit()).signedAt(signedAt).payloadDigest(digest)
                    .submittedBy(actor.id())
                    .build());
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // 同一事件并发提交: 另一请求已先落库。重试即可得到幂等应答 (内容相同) 或 409 (内容不同)
            throw new BusinessException(409, "同一事件正在被并发提交，请稍后重试").withHint("稍后用相同内容重试一次即可");
        }
        accessService.audit(factoryId, pkg.getProductTypeId(), actor, ProcessSecretAction.SUBMIT_RESULT,
                "ALLOW", "签名有效，待核验", request.eventId());
        return view(saved, false);
    }

    /**
     * 执行端签名的规范串。字段顺序固定、用 {@code |} 连接; 数量去掉尾随 0 后用普通十进制。
     * 客户侧执行端按同样规则拼串后用 Ed25519 私钥签名, 签名做 Base64。
     */
    public static String canonicalPayload(String factoryId, ResultRequest r) {
        return String.join("|",
                "cretas-process-result-v1",
                factoryId,
                r.packageRef(),
                r.packageVersion(),
                r.eventId(),
                String.valueOf(r.productionBatchId()),
                r.outputQuantity().stripTrailingZeros().toPlainString(),
                r.outputUnit().trim(),
                r.signedAt().trim());
    }

    /**
     * 必须在拼规范串之前做: {@code 1e2147483000} 这类值 signum>0、Jackson 照收,
     * 而 {@code toPlainString()} 会拼出十亿位字符串直接 OOM (审查 H4)。
     * 与库列 NUMERIC(15,4) 一致: 最多 4 位小数、11 位整数 —— 超了不静默四舍五入 (签名值 ≠ 存储值)。
     */
    static void requireStorableQuantity(BigDecimal quantity) {
        long integerDigits = (long) quantity.precision() - quantity.scale();
        if (quantity.scale() > 4 || integerDigits > 11) {
            throw new BusinessException(400, "outputQuantity 最多 11 位整数、4 位小数");
        }
    }

    /** 停用某个工艺包版本 (执行端密钥泄露 / 版本作废)。仅负责人; 停用后该版本的结果一律不收。 */
    @Transactional
    public PackageView retirePackage(AuthenticatedActor actor, String factoryId, Long packageId) {
        ProcessSecretExternalPackage pkg = packageRepository.findById(packageId)
                .filter(p -> factoryId.equals(p.getFactoryId()))
                .orElseThrow(() -> new BusinessException(404, "工艺包不存在"));
        ProcessSecretPolicy policy = policyService.requirePolicy(factoryId, pkg.getProductTypeId());
        policyService.requireOwner(actor, factoryId, policy);
        if (pkg.getStatus() != ProcessSecretPackageStatus.RETIRED) {
            pkg.setStatus(ProcessSecretPackageStatus.RETIRED);
            packageRepository.save(pkg);
            if (!accessService.audit(factoryId, pkg.getProductTypeId(), actor, ProcessSecretAction.REGISTER_PACKAGE,
                    "ALLOW", "停用工艺包", pkg.getPackageRef() + "@" + pkg.getPackageVersion())) {
                throw new BusinessException(503, "保密审计暂不可用，本次停用未生效");
            }
        }
        return view(pkg);
    }

    static PublicKey parsePublicKey(String base64) {
        if (base64 == null || base64.isBlank()) throw new BusinessException(400, "executorPublicKey 不能为空");
        try {
            byte[] der = Base64.getDecoder().decode(base64.trim());
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der));
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            throw new BusinessException(400, "executorPublicKey 不是有效的 Ed25519 公钥 (X.509, Base64)");
        }
    }

    static boolean verify(String publicKeyBase64, String canonical, String signatureBase64) {
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(parsePublicKey(publicKeyBase64));
            verifier.update(canonical.getBytes(StandardCharsets.UTF_8));
            return verifier.verify(Base64.getDecoder().decode(signatureBase64.trim()));
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            return false;
        }
    }

    private static LocalDateTime parseSignedAt(String value) {
        if (value == null || value.isBlank()) throw new BusinessException(400, "signedAt 不能为空");
        try {
            return OffsetDateTime.parse(value.trim()).atZoneSameInstant(BUSINESS_ZONE).toLocalDateTime();
        } catch (DateTimeParseException e) {
            throw new BusinessException(400, "signedAt 必须是带时区的 ISO-8601 时间，例如 2026-10-22T10:00:00+08:00");
        }
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static void requireRef(String value, String field) {
        if (value == null || !REF.matcher(value).matches()) {
            throw new BusinessException(400, field + " 只能是 1-64 位字母数字及 _.-");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new BusinessException(400, field + " 不能为空");
    }

    private static PackageView view(ProcessSecretExternalPackage p) {
        return new PackageView(p.getId(), p.getProductTypeId(), p.getPackageRef(), p.getPackageVersion(),
                p.getOutputUnit(), p.getStatus(), p.getCreatedAt());
    }

    private static ResultView view(ProcessSecretExternalResult r, boolean duplicate) {
        return new ResultView(r.getId(), r.getEventId(), r.getProductionBatchId(), r.getOutputQuantity(),
                r.getOutputUnit(), r.getReviewStatus().name(), duplicate, r.getReceivedAt());
    }
}
