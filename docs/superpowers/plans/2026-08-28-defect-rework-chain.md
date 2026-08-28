# 不良品→回料链路打通 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让金检/X光 剔出的不良品从「一个没人填、填了也没用的数字」变成「一批有重量、有成本、下一批领得到的回料半成品库存」。

**Architecture:** 画布上一道工序可以有多个半成品产出端口（后端已支持，F006 生产上有 1 进 2 出的图在跑）。缺口在两端：报工屏只渲染 `outputOptions[0]`，后端只过账单个 `semiCode`/`semiOutputQuantity`。本计划把两端都改成「列表」，并给产出项加 `isRework` 标记，让报工屏把回料项显示成「不良品数量」、同时回填 `ProductionReport.defectQuantity`（质量账）。

**Tech Stack:** Java 21 / Spring Boot 3.2.12 / JPA；React Native (Expo) + TypeScript；Vue 3 (web-admin 画布编辑器)

**Spec:** 本文件 §Spec 段（设计讨论见 2026-08-28 与 Steve 的六膳门汉堡线对话；配比与工艺来自六膳门 4 张配比表 + 工艺说明截图）

---

## Spec

### 业务场景

六膳门汉堡线（LIUSHANMEN 租户）4 个成品：美式汉堡饼 / 小吃吧汉堡饼 / 熟肉饼 / 汉堡排。

工艺：`领料 → 拆包 → 缓化 → 破碎 → 绞肉(加回料) → 搅拌 → 成型压饼 → 缩膜 → 金检 → X光 → 成品`

**金检和 X 光两道都会剔出不良品**，不良品回到下一批的绞肉工序当回料。回料在配方里是正式的一块：熟肉饼 19.50%、小吃吧 5.67%、美式 5%（表尾作业说明「每锅按投料 5% 添加，20 公斤，过 3MM 绞肉机」）。

### 回料流向矩阵（美式是纯肉无辅料，所以它的回料最"干净"）

| 投入到 ↓ ／ 回料来自 → | 美式 | 小吃吧 | 熟肉饼 | 汉堡排 |
|---|---|---|---|---|
| 美式 | ✅ | ❌ | ❌ | ❌ |
| 小吃吧 | ✅ | ✅ | ❌ | ❌ |
| 熟肉饼 | ✅ | ✅ | ✅ | ❌ |
| 汉堡排 | ❌ | ❌ | ❌ | ❌ |

矩阵**由画布连线表达**（美式的绞肉节点只连 1 个回料投入端口，熟肉饼连 3 个）。本计划**不做**纯度校验闸——那是独立一件事，见 §不做的事。

### 已在生产上建好的主数据（LIUSHANMEN，2026-08-28）

- 工序 26 道，本轮新增 `破碎`(前处理) / `缩膜`(包装) / `金检`(质检) / `X光异物检测`(质检)
- 成品 4 个：`CPLIUSHANMEN0018~0021`
- 不良品回料 SKU 4 个：`PTSEMI-LSM-0003`(美式纯肉) / `0004`(小吃吧) / `0005`(熟肉饼) / `0006`(汉堡排)
- 工序副产回料 SKU 3 个：`PTSEMI-LSM-0007`(料头3MM) / `0008`(筋膜泥) / `0009`(气调碎肉3MM) —— 来自别的产线，在汉堡图上是无上游的入料 Cell
- 配方物料 21 个

### 三处实测缺口（本计划要补的）

| # | 位置 | 现状 |
|---|---|---|
| ① | `YieldReportRequest` | 没有 `defectQuantity` 字段 —— 逐工序报工连填的地方都没有 |
| ② | `YieldStepReportScreen.tsx:1572` 与 `:3115` | 硬取 `outputOptions[0]`，两个产出只认第一个 |
| ③ | `WipInventoryServiceImpl.postSemiOutputLedger` | 只过账单个 `semiCode`/`semiOutputQuantity` |

生产实测佐证：`production_reports` 43 条，`defect_quantity > 0` 的 **0 条**，`good_quantity` 非空的 **0 条**，`output_kind IN ('SEMI','BOTH')` 的 **0 条**。

### 不做的事（明确排除，避免范围蔓延）

- ⛔ **不做纯度校验闸**。矩阵靠画布连线表达；画错了系统不拦。要拦得给物料加纯度等级，是独立一件事。
- ⛔ **不改 `WorkReportingServiceImpl`（老式整合报工）那条路**。只改逐工序报工（yield）这条路。
- ⛔ **不动副产（BYPRODUCT → `material_batches`）那条链**。生产实测 0 条批次、`buildByproductBatch` 刻意不写单价，不适合承载回料。
- ⛔ **不给 `standard_yield_min/max` 填默认值**。没有真实数据前填任何范围都会天天误报。

---

## Global Constraints

- **工作区**：`C:/Users/Steve/cretas-defect-rework`，分支 `codex/claude-defect-rework-chain-20260828`，基于 `origin/main` = `eadcf7a8bd`。⛔ 不在主工作目录 `C:/Users/Steve/my-prototype-logistics` 干活。
- **提交**：一律 `./scripts/safe-commit.sh "<msg>" <file1> <file2>`，⛔ 不用 `git add . && git commit`（并发 session 会污染提交范围）。
- **向后兼容零回归**：`defectQuantity == null` 且 `semiOutputs == null` 时，所有过账行为**与改动前逐字节一致**。这是本计划最硬的约束——`postApprovedOutput` 是全部产品共用的过账主干。
- **禁止降级处理**：读不到就报错，⛔ 不写 `.get(k, 0)` / `orElse(BigDecimal.ZERO)` 把「不知道」翻译成「是 0」。
- **响应格式**：`{ success, data, message }`。
- **TS 类型**：⛔ 不用 `as any`。
- **变异验证**：每条新断言都要先证明「变异真的到达了被测代码」再看它红不红（本仓 `tests/test_mutation_targets_are_reachable.py` 同一条纪律）。
- **Java 测试**：`cd backend/java/cretas-api && mvn -o test -Dtest='<ClassName>'`
- **RN 测试**：`cd frontend/CretasFoodTrace && npx jest <path> --runInBand`

---

## File Structure

| 文件 | 职责 | 动作 |
|---|---|---|
| `backend/.../dto/yield/OutputOptionsResponse.java` | 产出选项 DTO | 修改：`OutputOptionItem` 加 `isRework` |
| `backend/.../service/workflow/WorkflowReworkNodes.java` | 「这个物料节点是不是回料」的**唯一判据** | 新建 |
| `backend/.../service/wip/impl/WipInventoryServiceImpl.java` | 产出选项组装 + 半成品过账 | 修改：标 `isRework`；`postSemiOutputLedger` 改成遍历列表 |
| `backend/.../dto/yield/YieldReportRequest.java` | 逐工序报工请求体 | 修改：加 `defectQuantity` + `semiOutputs` 列表 |
| `backend/.../service/yield/impl/YieldReportServiceImpl.java` | 报工落库 | 修改：把新字段落到 `ProductionReport` |
| `frontend/.../services/api/yieldReportApi.ts` | 报工 API 类型 | 修改：`OutputOptionItem` 加 `isRework`；请求体加新字段 |
| `frontend/.../screens/processing/YieldStepReportScreen.tsx` | 报工屏 | 修改：渲染全部产出项；回料项标题改「不良品数量」 |
| `web-admin/.../workflow/ProductProcessWorkflowEditor.vue` | 画布编辑器 | 修改：产出 Cell 加「标记为回料」 |

**为什么单独建 `WorkflowReworkNodes`**：本仓已经因为「同一个判据写三遍」吃过亏（`WorkflowByproductNodes` 的类注释记录了那次收敛）。「这个物料节点是不是回料」这句话必须只有一处定义。

---

### Task 1: 回料判据收敛成一处 + 产出选项带上标记

**Files:**
- Create: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/workflow/WorkflowReworkNodes.java`
- Create: `backend/java/cretas-api/src/test/java/com/cretas/aims/service/workflow/WorkflowReworkNodesTest.java`
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/dto/yield/OutputOptionsResponse.java`

**Interfaces:**
- Produces: `WorkflowReworkNodes.isReworkFlag(Object) -> boolean`、`WorkflowReworkNodes.reworkMaterialNodeIds(String nodesJson) -> Set<String>`；`OutputOptionsResponse.OutputOptionItem.isRework` (Boolean)

- [ ] **Step 1: 写失败的测试**

```java
package com.cretas.aims.service.workflow;

import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

class WorkflowReworkNodesTest {

    @Test
    void 布尔与字符串两种真值都认() {
        // nodes_json 是 jsonb, 历史快照里这个标记既出现过布尔也出现过字符串。
        // 只认 Boolean.TRUE 会让存量回料静默退化成普通半成品 —— 那正是本类要防的缺陷形状。
        assertThat(WorkflowReworkNodes.isReworkFlag(Boolean.TRUE)).isTrue();
        assertThat(WorkflowReworkNodes.isReworkFlag("true")).isTrue();
        assertThat(WorkflowReworkNodes.isReworkFlag("TRUE")).isTrue();
        assertThat(WorkflowReworkNodes.isReworkFlag(Boolean.FALSE)).isFalse();
        assertThat(WorkflowReworkNodes.isReworkFlag(null)).isFalse();
        assertThat(WorkflowReworkNodes.isReworkFlag("yes")).isFalse();
    }

    @Test
    void 从快照里挑出被标记为回料的物料节点() {
        String nodesJson = """
            [
              {"id":"material:semi:good","kind":"SEMI_FINISHED","data":{"name":"金检良品"}},
              {"id":"material:semi:rework","kind":"SEMI_FINISHED","data":{"name":"美式回料","isRework":true}},
              {"id":"material:semi:rework2","kind":"SEMI_FINISHED","data":{"name":"熟肉饼回料","isRework":"true"}}
            ]
            """;
        Set<String> ids = WorkflowReworkNodes.reworkMaterialNodeIds(nodesJson);
        assertThat(ids).containsExactlyInAnyOrder("material:semi:rework", "material:semi:rework2");
    }

    @Test
    void 解析失败返回空集合而不是抛异常() {
        // 一个 JSON 解析问题不该让整张报工单打不开 —— 与 WorkflowByproductNodes 同口径。
        assertThat(WorkflowReworkNodes.reworkMaterialNodeIds("{ not json")).isEmpty();
        assertThat(WorkflowReworkNodes.reworkMaterialNodeIds(null)).isEmpty();
        assertThat(WorkflowReworkNodes.reworkMaterialNodeIds("")).isEmpty();
        assertThat(WorkflowReworkNodes.reworkMaterialNodeIds("{\"a\":1}")).isEmpty();
    }
}
```

- [ ] **Step 2: 跑测试确认它失败**

```bash
cd backend/java/cretas-api && mvn -o test -Dtest='WorkflowReworkNodesTest'
```

Expected: FAIL —— `cannot find symbol: class WorkflowReworkNodes`

- [ ] **Step 3: 写实现**

```java
package com.cretas.aims.service.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 「这个物料 Cell 是不是回料」的<b>唯一判据</b>。
 *
 * <p>回料 = 金检/X光 剔出的不良品, 回到下一批的绞肉工序重新投入。它与副产
 * ({@link WorkflowByproductNodes}) 是<b>两件事</b>:
 * <ul>
 *   <li>副产 (肥油/料头) —— 打了标记的端口会被 {@code getOutputOptions} <b>过滤掉</b>,
 *       走 {@code material_batches} 那条链, 且刻意不写单价。</li>
 *   <li>回料 —— 端口<b>必须出现在</b>产出选项里 (操作员要填数), 走
 *       {@code semi_finished_inventory} 那条链, 带移动加权成本进下一批。</li>
 * </ul>
 *
 * <p>⛔ 不要把这两个标记合并。合并等于让回料要么进不了报工屏, 要么丢掉成本 ——
 * 而回料是几乎全价的肉, 丢成本会让毛利虚高。
 *
 * <p>本类存在的理由与 {@link WorkflowByproductNodes} 同源: 同一句判据在本仓一度有三份
 * 手写实现。这里从第一天就收敛成一份。
 */
public final class WorkflowReworkNodes {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private WorkflowReworkNodes() {
    }

    /**
     * 回料标记的 truthiness。{@code null} / 缺失 一律 false —— 不猜。
     *
     * <p>必须容忍字符串 {@code "true"}: nodes_json 是 jsonb, 历史快照里这个标记既可能是
     * 布尔也可能是字符串。
     */
    public static boolean isReworkFlag(Object flag) {
        return Boolean.TRUE.equals(flag) || "true".equalsIgnoreCase(String.valueOf(flag));
    }

    /**
     * 从运行时快照 nodes_json 里取出<b>被标记为回料的物料节点 id 集合</b>。
     *
     * <p>{@code WorkflowTaskPort.materialNodeId} 就是这里的节点 {@code id}, 两侧靠它 join。
     * ⛔ 不要为此在 {@code workflow_task_ports} 上加一列 —— nodes_json 是权威源。
     */
    public static Set<String> reworkMaterialNodeIds(String nodesJson) {
        if (nodesJson == null || nodesJson.isBlank()) {
            return Collections.emptySet();
        }
        try {
            JsonNode nodes = MAPPER.readTree(nodesJson);
            if (!nodes.isArray()) {
                return Collections.emptySet();
            }
            Set<String> marked = new LinkedHashSet<>();
            for (JsonNode node : nodes) {
                String id = node.path("id").asText(null);
                if (id == null || id.isBlank()) {
                    continue;
                }
                JsonNode flag = node.path("data").path("isRework");
                if (flag.isMissingNode() || flag.isNull()) {
                    continue;
                }
                if (isReworkFlag(flag.isBoolean() ? flag.asBoolean() : flag.asText())) {
                    marked.add(id);
                }
            }
            return marked;
        } catch (Exception ignored) {
            return Collections.emptySet();
        }
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
cd backend/java/cretas-api && mvn -o test -Dtest='WorkflowReworkNodesTest'
```

Expected: PASS，3 个测试全绿

- [ ] **Step 5: 给 OutputOptionItem 加 isRework 字段**

在 `OutputOptionsResponse.OutputOptionItem` 里，`processOrder` 字段之后加：

```java
        /**
         * 这一项是不是回料 (金检/X光 剔出的不良品去处)。
         *
         * <p>{@code true} 时报工屏把这一栏显示成「不良品数量」而不是「半成品产出量」,
         * 并把填的数同时回填 {@code ProductionReport.defectQuantity} (质量账)。
         *
         * <p>判据唯一来源 {@link com.cretas.aims.service.workflow.WorkflowReworkNodes}。
         * 旧版 App 不认这个字段, 会把回料当普通半成品产出项渲染 —— 物料账仍然对,
         * 只是少了「不良品」这个措辞。这是刻意的向后兼容, 不是缺陷。
         */
        private Boolean isRework;
```

- [ ] **Step 6: 提交**

```bash
./scripts/safe-commit.sh "feat(workflow): 回料判据收敛成一处 + 产出选项带 isRework 标记" \
  backend/java/cretas-api/src/main/java/com/cretas/aims/service/workflow/WorkflowReworkNodes.java \
  backend/java/cretas-api/src/test/java/com/cretas/aims/service/workflow/WorkflowReworkNodesTest.java \
  backend/java/cretas-api/src/main/java/com/cretas/aims/dto/yield/OutputOptionsResponse.java
```

---

### Task 2: 产出选项返回全部半成品产出项并标出回料

**Files:**
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/wip/impl/WipInventoryServiceImpl.java`（方法 `workflowOutputOptions`）
- Test: `backend/java/cretas-api/src/test/java/com/cretas/aims/service/wip/WipInventoryOutputOptionsReworkTest.java`

**Interfaces:**
- Consumes: `WorkflowReworkNodes.reworkMaterialNodeIds(String)`（Task 1）
- Produces: `getOutputOptions` 返回的每个 `OutputOptionItem` 都带 `isRework`

- [ ] **Step 1: 写失败的测试**

```java
package com.cretas.aims.service.wip;

import com.cretas.aims.service.workflow.WorkflowReworkNodes;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class WipInventoryOutputOptionsReworkTest {

    /**
     * 金检节点 1 进 2 出: 良品去 X 光, 回料回下一批绞肉。
     * 两个产出端口都必须出现在选项里 —— 少一个操作员就填不了那个数。
     */
    @Test
    void 金检的两个产出端口都要出现且回料被标出() {
        String nodesJson = """
            [
              {"id":"material:semi:good","kind":"SEMI_FINISHED","data":{"name":"金检良品"}},
              {"id":"material:semi:rw","kind":"SEMI_FINISHED","data":{"name":"美式回料","isRework":true}}
            ]
            """;
        Set<String> rework = WorkflowReworkNodes.reworkMaterialNodeIds(nodesJson);

        assertThat(rework)
                .as("回料节点必须被认出来, 否则报工屏会把它当普通半成品")
                .containsExactly("material:semi:rw");
        assertThat(rework)
                .as("良品节点不能被误标成回料 —— 误标会让良品数被写进 defectQuantity")
                .doesNotContain("material:semi:good");
    }

    /**
     * 阴性对照: 副产标记 (isByproduct) 与回料标记 (isRework) 是两个独立的键,
     * 打了副产标记的节点不该被回料判据认领。
     */
    @Test
    void 副产标记不会被回料判据误认() {
        String nodesJson = """
            [{"id":"material:semi:fat","kind":"SEMI_FINISHED","data":{"name":"肥油","isByproduct":true}}]
            """;
        assertThat(WorkflowReworkNodes.reworkMaterialNodeIds(nodesJson)).isEmpty();
    }
}
```

- [ ] **Step 2: 跑测试确认它失败**

```bash
cd backend/java/cretas-api && mvn -o test -Dtest='WipInventoryOutputOptionsReworkTest'
```

Expected: 若 Task 1 已完成，本测试应 PASS（它验的是判据本身）。**如果 PASS，把它当回归护栏保留**，直接进 Step 3。

- [ ] **Step 3: 在 workflowOutputOptions 里标 isRework**

在 `WipInventoryServiceImpl.workflowOutputOptions` 中，`byproductNodeIds` 那一行之后加一行取回料集合：

```java
        Set<String> byproductNodeIds =
                WorkflowByproductNodes.byproductMaterialNodeIds(instance.getNodesJson());
        // 回料端口必须出现在选项里 (与副产相反 —— 副产是被过滤掉的)。判据唯一来源 WorkflowReworkNodes。
        Set<String> reworkNodeIds =
                WorkflowReworkNodes.reworkMaterialNodeIds(instance.getNodesJson());
```

在组装 `OutputOptionItem` 的 builder 链里，`.processOrder(task.getProcessOrder())` 之后加：

```java
                    .isRework(reworkNodeIds.contains(port.getMaterialNodeId()))
```

并在文件顶部加 import：

```java
import com.cretas.aims.service.workflow.WorkflowReworkNodes;
```

- [ ] **Step 4: 跑测试确认通过 + 编译**

```bash
cd backend/java/cretas-api && mvn -o test -Dtest='WipInventoryOutputOptionsReworkTest' && mvn -o -q compile
```

Expected: PASS + 编译成功

- [ ] **Step 5: 提交**

```bash
./scripts/safe-commit.sh "feat(wip): 产出选项标出回料端口" \
  backend/java/cretas-api/src/main/java/com/cretas/aims/service/wip/impl/WipInventoryServiceImpl.java \
  backend/java/cretas-api/src/test/java/com/cretas/aims/service/wip/WipInventoryOutputOptionsReworkTest.java
```

---

### Task 3: 报工请求体支持多个半成品产出 + 不良品数

**Files:**
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/dto/yield/YieldReportRequest.java`
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/yield/impl/YieldReportServiceImpl.java`
- Test: `backend/java/cretas-api/src/test/java/com/cretas/aims/service/yield/YieldReportDefectFieldTest.java`

**Interfaces:**
- Produces: `YieldReportRequest.getDefectQuantity() -> BigDecimal`、`YieldReportRequest.getSemiOutputs() -> List<SemiOutput>`，其中 `SemiOutput` 有 `getSemiCode() -> String` / `getQuantity() -> BigDecimal` / `getIsRework() -> Boolean`

- [ ] **Step 1: 写失败的测试**

```java
package com.cretas.aims.service.yield;

import com.cretas.aims.dto.yield.YieldReportRequest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class YieldReportDefectFieldTest {

    @Test
    void 请求体能承载不良品数和多个半成品产出() {
        YieldReportRequest req = new YieldReportRequest();
        req.setOutputQuantity(new BigDecimal("950.00"));
        req.setDefectQuantity(new BigDecimal("50.00"));

        YieldReportRequest.SemiOutput rework = new YieldReportRequest.SemiOutput();
        rework.setSemiCode("PTSEMI-LSM-0003");
        rework.setQuantity(new BigDecimal("50.00"));
        rework.setIsRework(Boolean.TRUE);

        YieldReportRequest.SemiOutput good = new YieldReportRequest.SemiOutput();
        good.setSemiCode("PTSEMI-LSM-0010");
        good.setQuantity(new BigDecimal("950.00"));
        good.setIsRework(Boolean.FALSE);

        req.setSemiOutputs(List.of(good, rework));

        assertThat(req.getDefectQuantity()).isEqualByComparingTo("50.00");
        assertThat(req.getSemiOutputs()).hasSize(2);
        assertThat(req.getSemiOutputs().get(1).getSemiCode()).isEqualTo("PTSEMI-LSM-0003");
        assertThat(req.getSemiOutputs().get(1).getIsRework()).isTrue();
    }

    @Test
    void 不传新字段时两个字段都是null_旧版App零回归() {
        // 🔴 这条是本计划最硬的约束的护栏: 旧版 App 的请求体不含新字段,
        //    过账必须走与改动前逐字节一致的老路。null 是那条路的入口条件。
        YieldReportRequest req = new YieldReportRequest();
        req.setOutputQuantity(new BigDecimal("1000"));
        assertThat(req.getDefectQuantity()).isNull();
        assertThat(req.getSemiOutputs()).isNull();
    }
}
```

- [ ] **Step 2: 跑测试确认它失败**

```bash
cd backend/java/cretas-api && mvn -o test -Dtest='YieldReportDefectFieldTest'
```

Expected: FAIL —— `cannot find symbol: method setDefectQuantity` / `class SemiOutput`

- [ ] **Step 3: 给 YieldReportRequest 加字段**

在 `semiCode` 字段之后加：

```java
    /**
     * 本道不良品数量 (金检/X光 剔出的量)。
     *
     * <p>⚠️ 口径: {@code outputQuantity} 已经是<b>良品</b>产出量, 本字段是<b>额外</b>剔出的量,
     * ⛔ 不是从 outputQuantity 里再扣一次。投入 = outputQuantity + defectQuantity + 损耗。
     *
     * <p>{@code null} = 旧版 App / 本道不分流不良品, 过账走原路径零回归。
     * ⛔ {@code null} 不许回填成 0 —— 「没清点」和「确认零不良」是两件事
     * (见 {@code DefectCountOutcome} 的类注释, 那是 2026-08-26 事故的产物)。
     */
    private BigDecimal defectQuantity;

    /**
     * 本道的多个半成品产出 (画布 1 进 N 出)。
     *
     * <p>与旧的单个 {@code semiCode}/{@code semiOutputQuantity} <b>并存</b>:
     * 本字段非空时以本字段为准, 为空时走旧字段 —— 旧版 App 零回归。
     */
    private List<SemiOutput> semiOutputs;
```

在文件末尾的内部类区域（`LaborSegment` 旁边）加：

```java
    /** 画布一道工序的一个半成品产出端口的填报值。 */
    @Data
    public static class SemiOutput {
        /** 半成品编码 (= ProductType.code, 由 output-options 接口下发; ⛔ 不许前端自由输入) */
        private String semiCode;
        /** 本次该产出的数量 */
        private BigDecimal quantity;
        /** 这一项是不是回料 (来自 output-options 的 isRework); true 时其 quantity 同时落 defectQuantity */
        private Boolean isRework;
    }
```

确认文件顶部已有 `import java.util.List;`（已有 `List<MaterialBatchRef>` 等字段，应已存在；若无则补）。

- [ ] **Step 4: 跑测试确认通过**

```bash
cd backend/java/cretas-api && mvn -o test -Dtest='YieldReportDefectFieldTest'
```

Expected: PASS

- [ ] **Step 5: 在 YieldReportServiceImpl 里把新字段落到 ProductionReport**

找到构建 `ProductionReport` 的 builder 链（`.outputQuantity(effOutput).outputUnit(effOutputUnit)` 那一处，约 346 行），在其后加：

```java
                // 不良品数 (质量账)。null 原样传 null —— ⛔ 不兜底成 0。
                .defectQuantity(request.getDefectQuantity())
                // 良品数 = 本道产出。outputQuantity 口径已是良品, 这里只是把它显式化,
                // 让「良品 + 不良品」两个数在同一行上可比 —— 报表侧不用再去别处推。
                .goodQuantity(effOutput)
```

- [ ] **Step 6: 跑现有 yield 测试确认没打破别的**

```bash
cd backend/java/cretas-api && mvn -o test -Dtest='*Yield*'
```

Expected: 全绿。若有红，逐条看是不是本改动引入的——⛔ 不要因为「本来就红」跳过，先跑 `git stash` 前后对比确认基线。

- [ ] **Step 7: 提交**

```bash
./scripts/safe-commit.sh "feat(yield): 逐工序报工支持不良品数与多半成品产出" \
  backend/java/cretas-api/src/main/java/com/cretas/aims/dto/yield/YieldReportRequest.java \
  backend/java/cretas-api/src/main/java/com/cretas/aims/service/yield/impl/YieldReportServiceImpl.java \
  backend/java/cretas-api/src/test/java/com/cretas/aims/service/yield/YieldReportDefectFieldTest.java
```

---

### Task 4: 半成品过账改成遍历多个产出

**Files:**
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/wip/impl/WipInventoryServiceImpl.java`（方法 `postApprovedOutput` / `postSemiOutputLedger`）
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/entity/ProductionReport.java`（加 `semiOutputs` jsonb 字段承载多产出）
- Create: `backend/java/cretas-api/src/main/resources/db/migration/V20261101_01__production_reports_semi_outputs.sql`
- Test: `backend/java/cretas-api/src/test/java/com/cretas/aims/service/wip/MultiSemiOutputPostingTest.java`

**Interfaces:**
- Consumes: `YieldReportRequest.SemiOutput`（Task 3）
- Produces: `postApprovedOutput` 对每个 `semiOutputs` 条目各入一笔 `SemiFinishedInventoryTransaction(IN)`

- [ ] **Step 1: 写迁移**

```sql
-- V20261101_01__production_reports_semi_outputs.sql
-- 多半成品产出 (画布 1 进 N 出) 的填报值。
-- 格式: [{"semiCode":"PTSEMI-LSM-0003","quantity":50.00,"isRework":true}, ...]
-- null = 旧式单产出报工, 走 semi_code/semi_output_quantity 老字段 (向后兼容零回归)。
ALTER TABLE production_reports ADD COLUMN IF NOT EXISTS semi_outputs jsonb;

COMMENT ON COLUMN production_reports.semi_outputs IS
  '多半成品产出填报值 [{semiCode,quantity,isRework}]; null=走旧的单产出字段';
```

- [ ] **Step 2: 跑迁移撞号闸**

```bash
cd backend/java/cretas-api && mvn -o test -Dtest='FlywayVersionUniquenessTest'
```

Expected: PASS。**若红说明版本号撞了**——`git log --oneline -5 -- src/main/resources/db/migration` 看最新号，往后顺延再跑。⛔ git 对 Flyway 撞号一个字都不报，要到启动才炸，所以这一步不能省。

- [ ] **Step 3: 给 ProductionReport 加字段**

在 `semiCode` 字段之后加：

```java
    /**
     * 多半成品产出 (画布 1 进 N 出) 的填报值。
     * 格式: [{"semiCode":"PTSEMI-LSM-0003","quantity":50.00,"isRework":true}, ...]
     * null = 旧式单产出报工, 过账走 semiCode/semiOutputQuantity 老字段。
     */
    @Type(JsonType.class)
    @Column(name = "semi_outputs", columnDefinition = "jsonb")
    private List<Map<String, Object>> semiOutputs;
```

- [ ] **Step 4: 写失败的测试**

```java
package com.cretas.aims.service.wip;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MultiSemiOutputPostingTest {

    /**
     * 金检报工: 良品 950 进良品半成品, 回料 50 进回料半成品。
     * 两笔都要入账 —— 只入一笔就等于回料凭空消失。
     */
    @Test
    void 两个产出各入一笔() {
        List<Map<String, Object>> outputs = List.of(
                Map.of("semiCode", "PTSEMI-LSM-0010", "quantity", new BigDecimal("950.00"), "isRework", false),
                Map.of("semiCode", "PTSEMI-LSM-0003", "quantity", new BigDecimal("50.00"), "isRework", true));

        List<Map<String, Object>> postable = WipSemiOutputPlan.postableEntries(outputs);

        assertThat(postable).hasSize(2);
        assertThat(postable).extracting(m -> m.get("semiCode"))
                .containsExactly("PTSEMI-LSM-0010", "PTSEMI-LSM-0003");
    }

    @Test
    void 数量为0或负或缺码的条目被跳过而不是当成0入账() {
        List<Map<String, Object>> outputs = java.util.Arrays.asList(
                Map.of("semiCode", "A", "quantity", new BigDecimal("10")),
                Map.of("semiCode", "B", "quantity", BigDecimal.ZERO),
                Map.of("semiCode", "C", "quantity", new BigDecimal("-5")),
                Map.of("quantity", new BigDecimal("7")),
                Map.of("semiCode", "D"));

        assertThat(WipSemiOutputPlan.postableEntries(outputs))
                .extracting(m -> m.get("semiCode"))
                .containsExactly("A");
    }

    @Test
    void 空列表和null都返回空_旧式报工零回归() {
        assertThat(WipSemiOutputPlan.postableEntries(null)).isEmpty();
        assertThat(WipSemiOutputPlan.postableEntries(List.of())).isEmpty();
    }
}
```

- [ ] **Step 5: 跑测试确认它失败**

```bash
cd backend/java/cretas-api && mvn -o test -Dtest='MultiSemiOutputPostingTest'
```

Expected: FAIL —— `cannot find symbol: class WipSemiOutputPlan`

- [ ] **Step 6: 写纯函数 WipSemiOutputPlan**

Create `backend/java/cretas-api/src/main/java/com/cretas/aims/service/wip/WipSemiOutputPlan.java`：

```java
package com.cretas.aims.service.wip;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 把报工填的多半成品产出筛成「真正可入账的那几条」。
 *
 * <p>抽成纯函数是刻意的: 过账逻辑埋在 {@code @Transactional} 方法里, 单测够不着,
 * 而「够不着被测行为」与「行为没生效」的读数一模一样、结论相反 —— 本仓吃过这个亏。
 */
public final class WipSemiOutputPlan {

    private WipSemiOutputPlan() {
    }

    /** 取一条产出的数量; 读不出来返回 null (⛔ 不兜底成 0)。调用方过账时用它, 不要自己再写一份解析。 */
    public static BigDecimal quantityOf(Map<String, Object> entry) {
        return entry == null ? null : toDecimal(entry.get("quantity"));
    }

    /** 取一条产出的半成品编码; 空白返回 null。 */
    public static String semiCodeOf(Map<String, Object> entry) {
        if (entry == null) return null;
        Object code = entry.get("semiCode");
        if (code == null) return null;
        String s = String.valueOf(code).trim();
        return s.isEmpty() ? null : s;
    }

    /**
     * @param entries 报工填的产出条目; null/空 → 空列表 (旧式报工走老路径)
     * @return 有码且数量为正的条目, 保持原顺序
     */
    public static List<Map<String, Object>> postableEntries(List<Map<String, Object>> entries) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> e : entries) {
            if (e == null) continue;
            Object code = e.get("semiCode");
            if (code == null || String.valueOf(code).isBlank()) continue;
            BigDecimal qty = toDecimal(e.get("quantity"));
            // ⛔ 数量读不出来就跳过, 不兜底成 0 —— 0 会被下游读成「这批产出为零」这个真实读数。
            if (qty == null || qty.signum() <= 0) continue;
            out.add(e);
        }
        return out;
    }

    private static BigDecimal toDecimal(Object v) {
        if (v == null) return null;
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return new BigDecimal(n.toString());
        try {
            return new BigDecimal(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
```

- [ ] **Step 7: 跑测试确认通过**

```bash
cd backend/java/cretas-api && mvn -o test -Dtest='MultiSemiOutputPostingTest'
```

Expected: PASS，3 个测试全绿

- [ ] **Step 8: 接进 postApprovedOutput**

在 `WipInventoryServiceImpl.postApprovedOutput` 里，把

```java
        if (isSemi) {
            postSemiOutputLedger(factoryId, report, task, operatorId);
        }
```

替换成

```java
        // 多产出优先: semiOutputs 非空时逐条入账 (画布 1 进 N 出)。
        // ⛔ 为空时必须走原来的单产出路径 —— 旧版 App 与存量报工零回归。
        List<Map<String, Object>> multi = WipSemiOutputPlan.postableEntries(report.getSemiOutputs());
        if (!multi.isEmpty()) {
            for (Map<String, Object> entry : multi) {
                // ⛔ 不在这里另写一份解析 —— 与 postableEntries 用同一份, 否则「能不能入账」
                //    和「入多少」两个判断会漂到不一致。
                postSemiOutputLedgerFor(factoryId, report, task, operatorId,
                        WipSemiOutputPlan.semiCodeOf(entry), WipSemiOutputPlan.quantityOf(entry));
            }
        } else if (isSemi) {
            postSemiOutputLedger(factoryId, report, task, operatorId);
        }
```

把现有 `postSemiOutputLedger` 的方法体抽成 `postSemiOutputLedgerFor(factoryId, report, task, operatorId, semiCode, inQty)`，原方法改成一行委派：

```java
    private void postSemiOutputLedger(String factoryId, ProductionReport report,
                                       WorkProcessTask task, Long operatorId) {
        postSemiOutputLedgerFor(factoryId, report, task, operatorId,
                report.getSemiCode(), report.getSemiOutputQuantity());
    }
```

🔴 **幂等键要改**：原来用 `(factoryId, report.getId(), IN)` 判重，多产出下同一份报工要入 N 笔，会被误判「已入账」跳过第二笔。改成 `(factoryId, reportId, sourceRef=semiCode, IN)`：

```java
        boolean alreadyPosted = txnRepo.findByFactoryIdAndReportId(factoryId, report.getId()).stream()
                .anyMatch(t -> SemiFinishedInventoryTransaction.TxnType.IN.equals(t.getTxnType())
                        && semiCode.equals(t.getSourceRef()));
```

- [ ] **Step 9: 跑全套 WIP 测试**

```bash
cd backend/java/cretas-api && mvn -o test -Dtest='*Wip*,*SemiFinished*'
```

Expected: 全绿。**重点看幂等相关的既有测试**——若红，多半是幂等键那处改动打到了旧路径，回去确认单产出时 `sourceRef` 确实等于 `semiCode`。

- [ ] **Step 10: 提交**

```bash
./scripts/safe-commit.sh "feat(wip): 半成品过账支持一道工序多个产出 (回料链路)" \
  backend/java/cretas-api/src/main/java/com/cretas/aims/service/wip/WipSemiOutputPlan.java \
  backend/java/cretas-api/src/main/java/com/cretas/aims/service/wip/impl/WipInventoryServiceImpl.java \
  backend/java/cretas-api/src/main/java/com/cretas/aims/entity/ProductionReport.java \
  backend/java/cretas-api/src/main/resources/db/migration/V20261101_01__production_reports_semi_outputs.sql \
  backend/java/cretas-api/src/test/java/com/cretas/aims/service/wip/MultiSemiOutputPostingTest.java
```

---

### Task 5: 报工屏渲染全部产出项，回料项显示成「不良品数量」

**Files:**
- Modify: `frontend/CretasFoodTrace/src/services/api/yieldReportApi.ts`
- Modify: `frontend/CretasFoodTrace/src/screens/processing/YieldStepReportScreen.tsx`（`:1572` 提交逻辑、`:3115` UI 区块）
- Test: `frontend/CretasFoodTrace/src/__tests__/unit/screens/yieldStepMultiOutput.test.ts`

**Interfaces:**
- Consumes: `OutputOptionItem.isRework`（Task 1/2）、`YieldReportRequest.semiOutputs` + `defectQuantity`（Task 3）
- Produces: 提交请求体带 `semiOutputs: [{semiCode, quantity, isRework}]` 与 `defectQuantity`

- [ ] **Step 1: 写失败的测试（纯函数先行）**

```ts
import { buildSemiOutputsPayload } from '../../../screens/processing/yieldStepOutputPayload';

describe('多产出提交载荷', () => {
  const options = [
    { taskId: 1, processName: '金检', semiCode: 'PTSEMI-LSM-0010', processOrder: 9, isRework: false },
    { taskId: 1, processName: '金检', semiCode: 'PTSEMI-LSM-0003', processOrder: 9, isRework: true },
  ];

  it('两个产出都填了 → 两条都带上, 并把回料那条的量作为 defectQuantity', () => {
    const r = buildSemiOutputsPayload(options, { 'PTSEMI-LSM-0010': '950', 'PTSEMI-LSM-0003': '50' });
    expect(r.semiOutputs).toHaveLength(2);
    expect(r.defectQuantity).toBe(50);
  });

  it('回料没填 → defectQuantity 是 undefined 而不是 0', () => {
    // 🔴「没清点」不许被写成「确认零不良」—— 这是 2026-08-26 事故的核心。
    const r = buildSemiOutputsPayload(options, { 'PTSEMI-LSM-0010': '950' });
    expect(r.semiOutputs).toHaveLength(1);
    expect(r.defectQuantity).toBeUndefined();
  });

  it('回料明确填 0 → defectQuantity 是 0 (确认零不良), 但不生成入账条目', () => {
    const r = buildSemiOutputsPayload(options, { 'PTSEMI-LSM-0010': '950', 'PTSEMI-LSM-0003': '0' });
    expect(r.semiOutputs).toHaveLength(1);
    expect(r.defectQuantity).toBe(0);
  });

  it('没有任何产出选项 → 全部 undefined, 走旧路径', () => {
    const r = buildSemiOutputsPayload([], {});
    expect(r.semiOutputs).toBeUndefined();
    expect(r.defectQuantity).toBeUndefined();
  });
});
```

- [ ] **Step 2: 跑测试确认它失败**

```bash
cd frontend/CretasFoodTrace && npx jest src/__tests__/unit/screens/yieldStepMultiOutput.test.ts --runInBand
```

Expected: FAIL —— `Cannot find module '.../yieldStepOutputPayload'`

- [ ] **Step 3: 写纯函数**

Create `frontend/CretasFoodTrace/src/screens/processing/yieldStepOutputPayload.ts`：

```ts
import type { OutputOptionItem } from '../../services/api/yieldReportApi';

export interface SemiOutputPayload {
  semiCode: string;
  quantity: number;
  isRework: boolean;
}

export interface OutputPayloadResult {
  semiOutputs?: SemiOutputPayload[];
  /** 回料那一项填的数。undefined = 没填(没清点); 0 = 明确填了 0(确认零不良) —— 两者不可合并 */
  defectQuantity?: number;
}

/**
 * 把「每个产出项填了多少」折成提交载荷。
 *
 * ⛔ 不填与填 0 必须分开: 前者是「没清点」, 后者是「确认零不良」。
 *    把前者写成 0 就是编一个读数, 本仓 2026-08-26 因此让 8 个批次全部谎称 100% 良品。
 */
export function buildSemiOutputsPayload(
  options: OutputOptionItem[],
  quantities: Record<string, string>,
): OutputPayloadResult {
  if (!options || options.length === 0) return {};

  const semiOutputs: SemiOutputPayload[] = [];
  let defectQuantity: number | undefined;

  for (const opt of options) {
    const code = opt.semiCode;
    if (!code) continue;
    const raw = quantities[code];
    if (raw === undefined || raw.trim() === '') continue;   // 没填 → 跳过, 不产生 0
    const n = Number.parseFloat(raw);
    if (Number.isNaN(n) || n < 0) continue;
    if (opt.isRework) defectQuantity = n;                    // 填了就记, 包括 0
    if (n > 0) semiOutputs.push({ semiCode: code, quantity: n, isRework: !!opt.isRework });
  }

  return {
    ...(semiOutputs.length > 0 ? { semiOutputs } : {}),
    ...(defectQuantity !== undefined ? { defectQuantity } : {}),
  };
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
cd frontend/CretasFoodTrace && npx jest src/__tests__/unit/screens/yieldStepMultiOutput.test.ts --runInBand
```

Expected: PASS，4 个测试全绿

- [ ] **Step 5: 给 API 类型加字段**

在 `yieldReportApi.ts` 的 `OutputOptionItem` 接口里加：

```ts
  /** 这一项是不是回料 (金检/X光 剔出的不良品去处); 旧后端不下发此字段 → undefined，按普通半成品渲染 */
  isRework?: boolean;
```

在 `YieldReportRequest` 接口里加：

```ts
  /** 本道不良品数 (质量账); undefined=没清点, 0=确认零不良 —— 两者不可合并 */
  defectQuantity?: number;
  /** 多半成品产出 (画布 1 进 N 出); 为空时后端走旧的单产出字段 */
  semiOutputs?: { semiCode: string; quantity: number; isRework: boolean }[];
```

- [ ] **Step 6: 报工屏改成按产出项渲染**

把状态从单值改成按 semiCode 的字典（替换 `const [semiOutputQty, setSemiOutputQty] = useState('')`）：

```tsx
  // SP1→多产出: 每个产出端口一个输入框, 按 semiCode 索引
  const [semiQtyByCode, setSemiQtyByCode] = useState<Record<string, string>>({});
```

把 `:3115` 那个 `outputOptions[0]` 区块整体替换成遍历：

```tsx
              {!outputOptionsLoading && outputOptions.filter((o) => !!o.semiCode).map((opt) => (
                <View key={opt.semiCode} style={styles.semiOutputSection} testID={`semi-output-section-${opt.semiCode}`}>
                  <View style={styles.divider} />
                  <Text style={styles.semiOutputTitle}>
                    {opt.isRework ? `不良品 → 回料 — ${opt.processName}` : `剩余转半成品 — ${opt.processName}`}
                  </Text>
                  <View style={styles.semiOutputCodeRow}>
                    <Text style={styles.semiOutputCodeLabel}>{opt.isRework ? '回料编码' : '半成品编码'}</Text>
                    <Text style={styles.semiOutputCodeValue} testID={`semi-code-display-${opt.semiCode}`}>
                      {opt.semiCode}
                    </Text>
                    <Text style={styles.semiOutputCodeFixed}>(自动)</Text>
                  </View>
                  <YieldQuantityInput
                    label={opt.isRework ? '不良品数量 (选填)' : '半成品产出量 (选填)'}
                    value={semiQtyByCode[opt.semiCode!] ?? ''}
                    onChangeText={(v) => setSemiQtyByCode((prev) => ({ ...prev, [opt.semiCode!]: v }))}
                    unit={outUnit}
                    disabled={submitting}
                    testID={`semi-output-qty-${opt.semiCode}`}
                  />
                  <Text style={styles.semiOutputHint}>
                    {opt.isRework
                      ? '填写后, 该数量入回料库存, 下一批绞肉可领用; 不填 = 本批未清点(不会被记成零不良)'
                      : '填写后, 该数量将同步入半成品库存台账, 可用于二次加工'}
                  </Text>
                </View>
              ))}
```

- [ ] **Step 7: 提交逻辑改用纯函数**

在 `doSubmitOutput` 里，把 `const semiOption = outputOptions.length > 0 ? outputOptions[0] : null;` 起的那一段（到 `outputKind` 计算结束）替换成：

```tsx
    // 多产出: 每个产出端口各取各的数; 回料那一项的数同时作为 defectQuantity (质量账)
    const outputPayload = buildSemiOutputsPayload(outputOptions, semiQtyByCode);
    const hasAnySemi = (outputPayload.semiOutputs?.length ?? 0) > 0;
    const outputKind: YieldReportRequest['outputKind'] =
      outputOptions.some((o) => !!o.semiCode)
        ? (hasAnySemi ? 'BOTH' : 'FINISHED')
        : undefined;
```

并把请求体里原来那三行 SP1 字段替换成：

```tsx
      ...(outputKind ? { outputKind } : {}),
      ...(outputPayload.semiOutputs ? { semiOutputs: outputPayload.semiOutputs } : {}),
      ...(outputPayload.defectQuantity !== undefined ? { defectQuantity: outputPayload.defectQuantity } : {}),
```

文件顶部加 import：

```tsx
import { buildSemiOutputsPayload } from './yieldStepOutputPayload';
```

- [ ] **Step 8: 跑 RN 相关测试 + 类型检查**

```bash
cd frontend/CretasFoodTrace && npx jest src/__tests__/unit/screens --runInBand && npx tsc --noEmit
```

Expected: 测试全绿 + 零类型错误。**若 tsc 报 `semiOutputQty` 未定义**，说明还有残留引用（第 1670 行的 useCallback 依赖数组），一并改掉。

- [ ] **Step 9: 提交**

```bash
./scripts/safe-commit.sh "feat(rn): 报工屏支持一道工序多个产出, 回料项显示为不良品数量" \
  frontend/CretasFoodTrace/src/screens/processing/yieldStepOutputPayload.ts \
  frontend/CretasFoodTrace/src/screens/processing/YieldStepReportScreen.tsx \
  frontend/CretasFoodTrace/src/services/api/yieldReportApi.ts \
  frontend/CretasFoodTrace/src/__tests__/unit/screens/yieldStepMultiOutput.test.ts
```

---

### Task 6: 画布编辑器加「标记为回料」

**Files:**
- Modify: `web-admin/src/views/system/product-processes/workflow/ProductProcessWorkflowEditor.vue`
- Test: `web-admin/src/views/system/product-processes/workflow/__tests__/reworkOutputNode.spec.ts`

**Interfaces:**
- Produces: 画布产出物料节点 `data.isRework: true`，被 `WorkflowReworkNodes`（Task 1）读取

- [ ] **Step 1: 写失败的测试**

```ts
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const EDITOR = readFileSync(
  resolve(__dirname, '../ProductProcessWorkflowEditor.vue'), 'utf8');

describe('回料产出节点', () => {
  it('复用已有的 addOutputToProcess 入口, 不新造函数', () => {
    // 与副产同一条纪律: 「复用画布已有的『＋ 产出 Cell（分流）』入口, 不要新造入口」
    expect(EDITOR).not.toMatch(/function addReworkToProcess/);
    expect(EDITOR).toContain("addOutputToProcess(slotProps.id, { rework: true })");
  });

  it('回料标记与副产标记是两个独立的键 —— 合并会让回料进不了报工屏', () => {
    // 副产端口会被 getOutputOptions 过滤掉; 回料端口必须留在选项里。
    expect(EDITOR).toMatch(/isRework/);
    expect(EDITOR).not.toMatch(/isRework\s*[:=]\s*options\.byproduct/);
  });
});
```

- [ ] **Step 2: 跑测试确认它失败**

```bash
cd web-admin && npx vitest run src/views/system/product-processes/workflow/__tests__/reworkOutputNode.spec.ts
```

Expected: FAIL —— 两条断言都不满足

- [ ] **Step 3: 扩展 addOutputToProcess**

把签名从

```ts
function addOutputToProcess(processId: string, options: { byproduct?: boolean } = {}): void {
```

改成

```ts
function addOutputToProcess(
  processId: string,
  options: { byproduct?: boolean; rework?: boolean } = {},
): void {
```

在函数体内 `const isByproduct = options.byproduct === true;` 之后加：

```ts
  // 回料 = 金检/X光 剔出的不良品去处。与副产刻意分开:
  // 副产端口会被后端 getOutputOptions 过滤掉, 回料端口必须留在选项里让操作员填数。
  const isRework = options.rework === true;
```

并在写入物料节点 `data` 的地方带上 `...(isRework ? { isRework: true } : {})`。

- [ ] **Step 4: 加入口按钮**

在工序节点的「＋ 副产」按钮旁边加：

```vue
              @add-rework="addOutputToProcess(slotProps.id, { rework: true })"
```

并在 `WorkflowProcessNode.vue` 的 emits 里加 `addRework: [];` 及对应按钮。

- [ ] **Step 5: 跑测试确认通过 + 类型检查**

```bash
cd web-admin && npx vitest run src/views/system/product-processes/workflow/__tests__/reworkOutputNode.spec.ts && npx vue-tsc -b
```

Expected: PASS + 零类型错误。⚠️ 必须用 `vue-tsc -b`——`--noEmit` 在本仓**一个文件都不检查**（`include` 只有 `src/**`）。

- [ ] **Step 6: 提交**

```bash
./scripts/safe-commit.sh "feat(canvas): 产出 Cell 支持标记为回料" \
  web-admin/src/views/system/product-processes/workflow/ProductProcessWorkflowEditor.vue \
  web-admin/src/views/system/product-processes/workflow/WorkflowProcessNode.vue \
  web-admin/src/views/system/product-processes/workflow/__tests__/reworkOutputNode.spec.ts
```

---

### Task 7: 端到端验证（在 F006 上跑，不在 LIUSHANMEN 上跑）

**Files:**
- Create: `docs/superpowers/plans/2026-08-28-defect-rework-chain-验收.md`

**Interfaces:**
- Consumes: Task 1–6 全部产出

- [ ] **Step 1: 挑验收环境**

⛔ **不要用干净租户验收。** 本仓的纪律是「挑一个能让闸红的样本，而不是挑一个干净的」——干净样本会让每一道判据都绿，而你分不清是产品对了还是判据不可能红。

用 **F006**（有 43 条真实报工、8 行半成品库存、IN/OUT/REVERSE 台账都在跑）建一张 1 进 2 出的测试工序图。

- [ ] **Step 2: 建图并报一次工**

在 F006 建一个工序图：某道工序两个半成品产出，其中一个 `data.isRework: true`。启动一个生产批次，在报工屏上给两个产出各填一个数（良品 950 / 回料 50）。

- [ ] **Step 3: 三条判据，缺一不可**

```bash
# ① 两笔 IN 都进了台账 (不是一笔)
psql ... -c "select semi_finished_id, txn_type, quantity, source_ref, unit_cost_at_txn
             from semi_finished_inventory_transactions
             where report_id = <本次报工id> order by id"
# 预期: 恰好 2 行 IN, source_ref 分别是两个 semiCode, 且 unit_cost_at_txn 非空

# ② 回料库存行建起来了且可领
psql ... -c "select intermediate_batch_no, produced_quantity, available_quantity, unit_cost
             from semi_finished_inventory where factory_id='F006' and intermediate_batch_no='<回料码>'"
# 预期: produced=50, available=50, unit_cost 非空

# ③ 不良品数落进了质量账
psql ... -c "select id, output_quantity, good_quantity, defect_quantity, semi_outputs
             from production_reports where id = <本次报工id>"
# 预期: defect_quantity=50, semi_outputs 两条
```

🔴 **配一个「必发生」的对照**：判据 ① 是「有没有涨」，而「根本没跑」和「跑了没涨」读数一样。所以同时数 `production_reports` 的行数——它每次报工**必然** +1。它没涨 = 根本没提交成功，①②③ 的读数全部作废。

- [ ] **Step 4: 变异对照 —— 证明这三条判据真的在守东西**

把 Task 4 里 `postableEntries` 的 `for` 循环改成只处理第一条（`if (out.size() >= 1) break;`），重跑 Step 2–3。

Expected: 判据 ① 从 2 行变成 1 行 → **变红**。

⛔ 如果没红，先证明「变异真的到达了被测代码」再下结论——变异不红有五种成因，「断言在守空气」只是其中一种。改回来。

- [ ] **Step 5: 旧路径零回归对照**

在 F006 挑一个**没有多产出**的既有工序，照常报一次工。

Expected: `semi_outputs` 为 null，过账行为与改动前一致（1 笔 IN，走 `semiCode`/`semiOutputQuantity` 老字段）。

- [ ] **Step 6: 写验收记录并提交**

把上面五步的**实际命令与实际输出**（不是「预期」）写进验收文档。

```bash
./scripts/safe-commit.sh "docs(plan): 不良品→回料链路端到端验收记录" \
  docs/superpowers/plans/2026-08-28-defect-rework-chain-验收.md
```

---

## 完成后

- [ ] `git diff origin/main...HEAD --stat` 确认 scope 干净，没有夹带别的 session 的文件
- [ ] 开 PR（碰 backend + web-admin 代码，按「合入通道双轨」**必须走 PR**，不能 fastlane）
- [ ] merge 后先 `./scripts/deploy/prewarm-main-artifact.sh`，再 `release-cretas.sh --phase deploy`
- [ ] 部署后**在运行中的 jar 里 grep 到本次改动的标记**才算部署成功——`DEPLOY_EXIT=0` 不是判据

## 遗留（明确不在本计划内）

| 事项 | 为什么留 |
|---|---|
| 纯度校验闸（美式只能用美式回料） | 需要给物料加纯度等级；本计划靠画布连线表达，画错了不拦 |
| 6 个包材建档 | 卡在采购价（系统硬性要求含税单价 > 0），是数据不是代码 |
| 汉堡排不良品去向 | 业务未定：报废 / 降级 / 回熟肉饼 |
| 金检 vs X光 剔出物是否可回料 | 食品安全问题：真检出金属的不该回料。本计划让去向可配，不预设 |
| 4 张汉堡工序图 | 主数据活，等包材补齐后配置即可，无需代码 |
