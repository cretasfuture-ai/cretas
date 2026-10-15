package com.cretas.aims.service.factory;

import com.cretas.aims.entity.factory.FactoryWarehouse;
import com.cretas.aims.entity.factory.FactoryWarehouse.WarehouseType;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.factory.FactoryWarehouseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 存放区(仓库)主数据的写入口。
 *
 * <h2>为什么新建这个 service (2026-08-07)</h2>
 *
 * <p>{@code factory_warehouses} 一直只有 entity + repository, 写入规则
 * (工厂内 code 唯一防重 / 停用走软删除)散在 {@code FactoryWarehouseController} 里。
 * 只有一个 HTTP 调用方时这能凑合; 但 R6 要给 AI agent 一个
 * {@code factory_warehouse_config} 工具, 于是出现了第二个调用方。
 *
 * <p>本仓有一道存量闸 {@code ToolRepositoryAccessArchitectureTest}: <b>AI 工具不得直接打仓储</b>,
 * 基线「只许降不许涨」。它的理由写在基线文件里 ——
 * <blockquote>AI 拆成独立服务后, repository.findByX 没有远程对应物 ——
 * 总不能把仓储方法暴露成 API。每一条都要换成领域侧的一个操作。</blockquote>
 *
 * <p>🔴 R6 的实施计划(我写的)当时不知道这道闸, 明写了「若只有 repository 没有 service 写入口
 * → 工具直接用 repository」。那条指示本身是缺陷: 工具照做后单独跑绿, 合并时才把闸打红。
 * <b>正确的解法就是这道闸想逼出来的东西 —— 补上缺失的领域服务</b>, 而不是给闸加一条豁免。
 *
 * <p>⛔ 本次<b>不动</b> {@code FactoryWarehouseController}: 它有自己的既有测试与行为,
 * 让它改走本服务是一次独立的重构。当前状态是「两个写入口, 规则各写一份」——
 * 这是已知的、被记录的临时态, 不是没看见。
 *
 * <p>刻意做成单个具体类而非 interface + impl: 只有一个实现, 也不需要给测试留替身缝
 * (调用方 mock 本类即可)。多加一层接口在这里只是仪式。
 */
@Slf4j
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@Service
@RequiredArgsConstructor
public class FactoryWarehouseService {

    private final FactoryWarehouseRepository repository;

    /** 按工厂内编号查在用的存放区。 */
    public Optional<FactoryWarehouse> findByCode(String factoryId, String code) {
        return repository.findByFactoryIdAndCodeAndDeletedAtIsNull(factoryId, safeTrim(code));
    }

    /** 列出工厂的全部在用存放区(按编号排序)。 */
    public List<FactoryWarehouse> list(String factoryId) {
        return repository.findByFactoryIdAndDeletedAtIsNullOrderByCodeAsc(factoryId);
    }

    /**
     * 新建存放区。
     *
     * <p>工厂内编号唯一是防呆规则: 撞号<b>抛 409 原样透传</b>, ⛔ 不允许悄悄改写成「新增成功」——
     * agent 拿到 success 却没落库, 是本仓反复踩过的形状。
     */
    @Transactional
    public FactoryWarehouse create(String factoryId, String code, String name, WarehouseType type,
                                   Boolean isActive, String remarks) {
        String trimmedCode = requireText(code, "请提供存放区编号");
        String trimmedName = requireText(name, "请提供存放区名称");
        if (type == null) {
            throw new BusinessException(400, "请说明这个存放区主要放什么阶段的东西")
                    .withHintTarget("type");
        }
        repository.findByFactoryIdAndCodeAndDeletedAtIsNull(factoryId, trimmedCode)
                .ifPresent(existing -> {
                    throw new BusinessException(409, "存放区 " + trimmedCode + " 已存在，id=" + existing.getId())
                            .withCode("WAREHOUSE_CODE_DUPLICATE")
                            .withHintTarget("code");
                });

        FactoryWarehouse w = new FactoryWarehouse();
        w.setFactoryId(factoryId);
        w.setCode(trimmedCode);
        w.setName(trimmedName);
        w.setType(type);
        w.setIsActive(isActive == null || isActive);
        w.setRemarks(remarks);
        FactoryWarehouse saved = repository.save(w);
        log.info("新增存放区 factoryId={} code={} type={}", factoryId, saved.getCode(), saved.getType());
        return saved;
    }

    /** 改存放区。传 null 的字段保持原值(部分更新)。 */
    @Transactional
    public FactoryWarehouse update(String factoryId, String code, String name, WarehouseType type,
                                   Boolean isActive, String remarks) {
        FactoryWarehouse w = require(factoryId, code);
        if (name != null) {
            w.setName(name.trim());
        }
        if (type != null) {
            w.setType(type);
        }
        if (isActive != null) {
            w.setIsActive(isActive);
        }
        if (remarks != null) {
            w.setRemarks(remarks);
        }
        FactoryWarehouse saved = repository.save(w);
        log.info("更新存放区 factoryId={} code={}", factoryId, saved.getCode());
        return saved;
    }

    /** 停用 = 软删除(保留历史单据对它的引用)。 */
    @Transactional
    public FactoryWarehouse deactivate(String factoryId, String code) {
        FactoryWarehouse w = require(factoryId, code);
        w.softDelete();
        repository.save(w);
        log.info("停用存放区 factoryId={} code={}", factoryId, w.getCode());
        return w;
    }

    private FactoryWarehouse require(String factoryId, String code) {
        String trimmedCode = requireText(code, "请说明要操作哪个存放区（提供它的编号）");
        return repository.findByFactoryIdAndCodeAndDeletedAtIsNull(factoryId, trimmedCode)
                .orElseThrow(() -> new BusinessException(404,
                        "存放区 " + trimmedCode + " 不存在或不属于当前工厂").withHintTarget("code"));
    }

    private static String requireText(String v, String message) {
        if (v == null || v.isBlank()) {
            throw new BusinessException(400, message);
        }
        return v.trim();
    }

    private static String safeTrim(String v) {
        return v == null ? null : v.trim();
    }
}
