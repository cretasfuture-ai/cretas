package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.enums.SnapshotType;
import com.cretas.aims.entity.finance.AccountingPeriod;
import com.cretas.aims.entity.inventory.InventoryLedgerSnapshot;
import com.cretas.aims.repository.finance.AccountingPeriodRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.inventory.InventoryLedgerSnapshotRepository;
import com.cretas.aims.service.inventory.InventoryLedgerService;
import com.cretas.aims.service.inventory.InventoryLedgerSnapshotService;
import com.cretas.aims.dto.inventory.InventoryLedgerLineDTO;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * SP11: 进销存期末快照服务实现.
 *
 * <p>在 {@link com.cretas.aims.service.impl.AccountingPeriodServiceImpl#confirmClose}
 * 完成结账后调用, 将当期期末结存写入 {@code inventory_ledger_snapshots}.
 *
 * <p>幂等: 同 factoryId + accountingPeriodId + materialTypeId 唯一键, ON CONFLICT 时覆盖.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryLedgerSnapshotServiceImpl implements InventoryLedgerSnapshotService {

    private final InventoryLedgerSnapshotRepository snapshotRepo;
    private final AccountingPeriodRepository accountingPeriodRepo;
    private final RawMaterialTypeRepository materialTypeRepo;
    private final InventoryLedgerService ledgerService;
    private final EntityManager entityManager;

    @Override
    @Transactional
    public void createForPeriod(String factoryId, String accountingPeriodId) {
        // 1. 取会计期
        AccountingPeriod period = accountingPeriodRepo.findById(accountingPeriodId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "AccountingPeriod not found: " + accountingPeriodId));

        if (!period.getFactoryId().equals(factoryId)) {
            throw new IllegalArgumentException(
                    "AccountingPeriod " + accountingPeriodId + " 不属于工厂 " + factoryId);
        }

        // 2. 计算期间范围
        createSnapshotsForPeriod(factoryId, accountingPeriodId, period, false);
    }

    @Override
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public RepairResult repairMissingInventorySnapshots(String factoryId, String accountingPeriodId) {
        AccountingPeriod period = accountingPeriodRepo.findById(accountingPeriodId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "AccountingPeriod not found: " + accountingPeriodId));
        assertFactory(period, factoryId, accountingPeriodId);

        // The repository lookup above only validates the request. Clear it
        // before the pessimistic refresh so the lock is acquired against a
        // fresh row inside the coherent REPEATABLE_READ repair snapshot.
        entityManager.clear();
        AccountingPeriod lockedPeriod = entityManager.find(
                AccountingPeriod.class, accountingPeriodId, LockModeType.PESSIMISTIC_WRITE);
        if (lockedPeriod == null) {
            throw new IllegalArgumentException("AccountingPeriod not found: " + accountingPeriodId);
        }
        assertFactory(lockedPeriod, factoryId, accountingPeriodId);
        if (lockedPeriod.getStatus() != AccountingPeriod.Status.CLOSED) {
            throw new IllegalStateException("Inventory snapshot repair requires a CLOSED accounting period");
        }

        List<InventoryLedgerSnapshot> existing = snapshotRepo
                .findByFactoryIdAndAccountingPeriodIdAndSnapshotTypeAndDeletedAtIsNull(
                        factoryId, accountingPeriodId, SnapshotType.PERIOD_CLOSE);

        PreviousSnapshotState previous = validatePreviousPeriodAndSnapshotState(factoryId, lockedPeriod);
        List<InventoryLedgerLineDTO> ledgerLines = validateLedgerOpeningBalances(
                factoryId, lockedPeriod, previous);

        if (!existing.isEmpty()) {
            validateExistingTargetSnapshots(factoryId, accountingPeriodId, existing, ledgerLines);
            return new RepairResult(factoryId, accountingPeriodId, 0, existing.size(),
                    existing.stream().map(InventoryLedgerSnapshot::getMaterialTypeId).toList());
        }

        CreationResult created = createSnapshotsForPeriod(
                factoryId, accountingPeriodId, lockedPeriod, ledgerLines, true);
        if (created.createdCount() != ledgerLines.size()
                || !new java.util.HashSet<>(created.materialTypeIds()).equals(
                        ledgerLines.stream().map(InventoryLedgerLineDTO::getMaterialTypeId)
                                .collect(java.util.stream.Collectors.toSet()))) {
            throw new IllegalStateException("Inventory snapshot repair did not preserve the complete validated material set");
        }
        return new RepairResult(factoryId, accountingPeriodId, created.createdCount(), 0,
                created.materialTypeIds());
    }

    private void validateExistingTargetSnapshots(
            String factoryId, String accountingPeriodId,
            List<InventoryLedgerSnapshot> existing,
            List<InventoryLedgerLineDTO> ledgerLines) {
        Map<MaterialKey, InventoryLedgerSnapshot> actual = new LinkedHashMap<>();
        for (InventoryLedgerSnapshot snapshot : existing) {
            if (!factoryId.equals(snapshot.getFactoryId())
                    || !accountingPeriodId.equals(snapshot.getAccountingPeriodId())
                    || snapshot.getSnapshotType() != SnapshotType.PERIOD_CLOSE
                    || snapshot.getMaterialTypeId() == null || snapshot.getMaterialTypeId().isBlank()
                    || snapshot.getUnit() == null || snapshot.getUnit().isBlank()
                    || snapshot.getClosingQty() == null) {
                throw new IllegalStateException(
                        "Existing inventory snapshots contain an incomplete or cross-scope row");
            }
            MaterialKey key = new MaterialKey(snapshot.getMaterialTypeId(), snapshot.getUnit());
            if (actual.putIfAbsent(key, snapshot) != null) {
                throw new IllegalStateException("Existing inventory snapshots contain duplicate material rows");
            }
        }

        Map<MaterialKey, InventoryLedgerLineDTO> expected = new LinkedHashMap<>();
        for (InventoryLedgerLineDTO line : ledgerLines) {
            if (line.getMaterialTypeId() == null || line.getMaterialTypeId().isBlank()
                    || line.getUnit() == null || line.getUnit().isBlank()
                    || line.getClosingQty() == null) {
                throw new IllegalStateException("Validated inventory ledger contains an incomplete closing row");
            }
            MaterialKey key = new MaterialKey(line.getMaterialTypeId(), line.getUnit());
            if (expected.putIfAbsent(key, line) != null) {
                throw new IllegalStateException("Validated inventory ledger contains duplicate material rows");
            }
        }
        if (!actual.keySet().equals(expected.keySet())) {
            throw new IllegalStateException(
                    "Existing inventory snapshots do not match the complete validated material set");
        }
        for (Map.Entry<MaterialKey, InventoryLedgerLineDTO> entry : expected.entrySet()) {
            BigDecimal actualClosing = actual.get(entry.getKey()).getClosingQty();
            if (actualClosing.compareTo(entry.getValue().getClosingQty()) != 0) {
                throw new IllegalStateException(
                        "Existing inventory snapshot closing quantity does not match the validated ledger");
            }
        }
    }

    private void assertFactory(AccountingPeriod period, String factoryId, String accountingPeriodId) {
        if (!factoryId.equals(period.getFactoryId())) {
            throw new IllegalArgumentException(
                    "AccountingPeriod " + accountingPeriodId + " 不属于工厂 " + factoryId);
        }
    }

    private PreviousSnapshotState validatePreviousPeriodAndSnapshotState(
            String factoryId, AccountingPeriod targetPeriod) {
        YearMonth target = YearMonth.of(targetPeriod.getYear(), targetPeriod.getMonth());
        YearMonth previousMonth = target.minusMonths(1);
        AccountingPeriod previousPeriod = accountingPeriodRepo
                .findByFactoryIdAndYearAndMonthAndDeletedAtIsNull(
                        factoryId, previousMonth.getYear(), previousMonth.getMonthValue())
                .orElseThrow(() -> new IllegalStateException(
                        "Previous CLOSED accounting period is required before inventory snapshot repair"));
        assertFactory(previousPeriod, factoryId, previousPeriod.getId());
        if (previousPeriod.getStatus() != AccountingPeriod.Status.CLOSED) {
            throw new IllegalStateException(
                    "Previous accounting period must be CLOSED before inventory snapshot repair");
        }

        List<InventoryLedgerSnapshot> snapshots = snapshotRepo
                .findByFactoryIdAndAccountingPeriodIdAndSnapshotTypeAndDeletedAtIsNull(
                        factoryId, previousPeriod.getId(), SnapshotType.PERIOD_CLOSE);
        Map<MaterialKey, BigDecimal> closingQtyByMaterial = new LinkedHashMap<>();
        for (InventoryLedgerSnapshot snapshot : snapshots) {
            String materialTypeId = snapshot.getMaterialTypeId();
            String unit = snapshot.getUnit();
            if (materialTypeId == null || materialTypeId.isBlank()
                    || unit == null || unit.isBlank() || snapshot.getClosingQty() == null) {
                throw new IllegalStateException(
                        "Previous inventory snapshot contains an incomplete material row");
            }
            MaterialKey key = new MaterialKey(materialTypeId, unit);
            if (closingQtyByMaterial.putIfAbsent(key, snapshot.getClosingQty()) != null) {
                throw new IllegalStateException(
                        "Previous inventory snapshots contain duplicate material rows");
            }
        }

        Map<MaterialKey, RawMaterialType> materialTypesByKey = new LinkedHashMap<>();
        for (RawMaterialType materialType : materialTypeRepo.findByFactoryId(factoryId)) {
            String materialTypeId = materialType.getId();
            String unit = materialType.getUnit();
            if (materialTypeId == null || materialTypeId.isBlank()
                    || unit == null || unit.isBlank()) {
                throw new IllegalStateException("Factory material catalog contains an incomplete material row");
            }
            MaterialKey key = new MaterialKey(materialTypeId, unit);
            if (materialTypesByKey.putIfAbsent(key, materialType) != null) {
                throw new IllegalStateException("Factory material catalog contains duplicate material rows");
            }
        }
        if (!closingQtyByMaterial.keySet().equals(materialTypesByKey.keySet())) {
            throw new IllegalStateException(
                    "Previous inventory snapshots do not match the factory material catalog");
        }
        return new PreviousSnapshotState(closingQtyByMaterial);
    }

    private List<InventoryLedgerLineDTO> validateLedgerOpeningBalances(
            String factoryId, AccountingPeriod targetPeriod, PreviousSnapshotState previous) {
        YearMonth target = YearMonth.of(targetPeriod.getYear(), targetPeriod.getMonth());
        List<InventoryLedgerLineDTO> ledgerLines = ledgerService.getLedger(
                factoryId, target.atDay(1), target.atEndOfMonth(), null,
                InventoryLedgerService.LedgerReadMode.STRICT_REPAIR);
        if (ledgerLines == null || ledgerLines.isEmpty()) {
            throw new IllegalStateException("Inventory ledger returned no rows for accounting period: "
                    + targetPeriod.getId());
        }
        Map<MaterialKey, InventoryLedgerLineDTO> ledgerByMaterial = new LinkedHashMap<>();
        for (InventoryLedgerLineDTO line : ledgerLines) {
            String materialTypeId = line.getMaterialTypeId();
            String unit = line.getUnit();
            if (materialTypeId == null || materialTypeId.isBlank()
                    || unit == null || unit.isBlank() || line.getOpeningQty() == null
                    || line.getClosingQty() == null) {
                throw new IllegalStateException(
                        "Inventory ledger contains an incomplete opening balance row");
            }
            MaterialKey key = new MaterialKey(materialTypeId, unit);
            if (ledgerByMaterial.putIfAbsent(key, line) != null) {
                throw new IllegalStateException("Inventory ledger contains duplicate material rows");
            }
        }
        if (!ledgerByMaterial.keySet().equals(previous.closingQtyByMaterial().keySet())) {
            throw new IllegalStateException(
                    "Inventory ledger material rows do not match the previous inventory snapshot");
        }
        for (Map.Entry<MaterialKey, BigDecimal> entry : previous.closingQtyByMaterial().entrySet()) {
            BigDecimal openingQty = ledgerByMaterial.get(entry.getKey()).getOpeningQty();
            if (openingQty == null || openingQty.compareTo(entry.getValue()) != 0) {
                throw new IllegalStateException(
                        "Inventory ledger opening quantity does not match the previous snapshot");
            }
        }
        return ledgerLines;
    }

    private CreationResult createSnapshotsForPeriod(
            String factoryId, String accountingPeriodId, AccountingPeriod period,
            boolean requireNonEmptyLedger) {
        // 调用台账服务获取期末数据
        List<InventoryLedgerLineDTO> lines = ledgerService.getLedger(
                factoryId,
                YearMonth.of(period.getYear(), period.getMonth()).atDay(1),
                YearMonth.of(period.getYear(), period.getMonth()).atEndOfMonth(),
                null);
        return createSnapshotsForPeriod(
                factoryId, accountingPeriodId, period, lines, requireNonEmptyLedger);
    }

    private CreationResult createSnapshotsForPeriod(
            String factoryId, String accountingPeriodId, AccountingPeriod period,
            List<InventoryLedgerLineDTO> lines, boolean requireNonEmptyLedger) {
        if (requireNonEmptyLedger && (lines == null || lines.isEmpty())) {
            throw new IllegalStateException("Inventory ledger returned no rows for accounting period: "
                    + accountingPeriodId);
        }

        int upsertCount = 0;
        List<String> createdMaterialTypeIds = new java.util.ArrayList<>();
        for (InventoryLedgerLineDTO line : lines) {
            if (line.getClosingQty() == null) {
                if (requireNonEmptyLedger) {
                    throw new IllegalStateException(
                            "Inventory ledger contains a null closing quantity during strict repair");
                }
                continue; // 普通结账保留历史行为: 跳过未提供期末数量的行
            }

            // 幂等 upsert: 查 existing
            List<InventoryLedgerSnapshot> existing = snapshotRepo.findLatestBeforePeriod(
                    factoryId, line.getMaterialTypeId(), SnapshotType.PERIOD_CLOSE,
                    period.getYear() * 100 + period.getMonth() + 1); // 查本期 = 下一月前的最近快照
            // 找到本期快照 (同 accountingPeriodId)
            boolean newSnapshot = existing.stream()
                    .noneMatch(s -> accountingPeriodId.equals(s.getAccountingPeriodId()));
            InventoryLedgerSnapshot snap = existing.stream()
                    .filter(s -> accountingPeriodId.equals(s.getAccountingPeriodId()))
                    .findFirst()
                    .orElseGet(() -> {
                        InventoryLedgerSnapshot s = new InventoryLedgerSnapshot();
                        s.setId(UUID.randomUUID().toString());
                        s.setFactoryId(factoryId);
                        s.setAccountingPeriodId(accountingPeriodId);
                        s.setMaterialTypeId(line.getMaterialTypeId());
                        s.setSnapshotType(SnapshotType.PERIOD_CLOSE);
                        return s;
                    });

            snap.setMaterialCode(line.getMaterialCode());
            snap.setMaterialName(line.getMaterialName());
            snap.setUnit(line.getUnit());
            snap.setClosingQty(line.getClosingQty() != null ? line.getClosingQty() : BigDecimal.ZERO);
            snap.setClosingUnitPrice(line.getMovingAvgUnitPrice()); // null if price-sensitive
            snap.setClosingAmount(line.getClosingAmount());         // null if price-sensitive

            snapshotRepo.save(snap);
            upsertCount++;
            if (newSnapshot) {
                createdMaterialTypeIds.add(line.getMaterialTypeId());
            }
        }

        log.info("[SP11] createForPeriod: factoryId={} accountingPeriodId={} period={}/{} upserted={}",
                factoryId, accountingPeriodId, period.getYear(), period.getMonth(), upsertCount);
        return new CreationResult(createdMaterialTypeIds.size(), List.copyOf(createdMaterialTypeIds));
    }

    private record CreationResult(int createdCount, List<String> materialTypeIds) {
    }

    private record MaterialKey(String materialTypeId, String unit) {
    }

    private record PreviousSnapshotState(Map<MaterialKey, BigDecimal> closingQtyByMaterial) {
    }
}
