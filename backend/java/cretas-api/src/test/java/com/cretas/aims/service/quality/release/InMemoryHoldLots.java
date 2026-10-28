package com.cretas.aims.service.quality.release;

import com.cretas.aims.entity.quality.SemiQcHoldLot;
import com.cretas.aims.repository.quality.SemiQcHoldLotRepository;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * 测试用: 内存版待检分笔账 (按 {@link SemiQcHoldLotRepository} 的三条查询语义实现), 让单测跑真的
 * {@link SemiQcHoldLots} 口径而不是桩出来的结果。查询语义本身另由 QcReleaseRepositoryQueryValidationTest 在 H2 上执行。
 */
public final class InMemoryHoldLots {

    public final List<SemiQcHoldLot> lots = new ArrayList<>();
    public final SemiQcHoldLotRepository repo = mock(SemiQcHoldLotRepository.class);
    public final SemiQcHoldLots service = new SemiQcHoldLots(repo);

    public InMemoryHoldLots() {
        lenient().when(repo.save(any(SemiQcHoldLot.class))).thenAnswer(inv -> {
            SemiQcHoldLot l = inv.getArgument(0);
            if (l.getId() == null) {
                l.setId((long) lots.size() + 1);
                lots.add(l);
            }
            return l;
        });
        lenient().when(repo.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(repo.findOpenBySemi(any(), any())).thenAnswer(inv -> open(inv.getArgument(0),
                inv.getArgument(1), null, null, false));
        lenient().when(repo.findOpenBySemiAndReport(any(), any(), any())).thenAnswer(inv -> open(inv.getArgument(0),
                inv.getArgument(1), inv.getArgument(2), null, true));
        lenient().when(repo.findOpenBySemiAndRef(any(), any(), any())).thenAnswer(inv -> open(inv.getArgument(0),
                inv.getArgument(1), null, inv.getArgument(2), true));
    }

    private List<SemiQcHoldLot> open(String factoryId, Long semiId, Long reportId, String ref, boolean newestFirst) {
        Comparator<SemiQcHoldLot> byId = Comparator.comparing(SemiQcHoldLot::getId);
        return lots.stream()
                .filter(l -> Objects.equals(l.getFactoryId(), factoryId) && Objects.equals(l.getSemiFinishedId(), semiId))
                .filter(l -> reportId == null || Objects.equals(l.getReportId(), reportId))
                .filter(l -> ref == null || Objects.equals(l.getHoldRef(), ref))
                .filter(l -> l.getQuantityOpen().signum() > 0)
                .sorted(newestFirst ? byId.reversed() : byId)
                .toList();
    }

    /** 这一行仍未放行的分笔合计。 */
    public BigDecimal openTotal(Long semiId) {
        return lots.stream().filter(l -> Objects.equals(l.getSemiFinishedId(), semiId))
                .map(SemiQcHoldLot::getQuantityOpen).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
