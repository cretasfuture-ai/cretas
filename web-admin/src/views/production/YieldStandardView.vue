<script setup lang="ts">
/**
 * 标准出成率维护 — 「一个成品投多少料出多少货」的那条基准线。
 *
 * <h3>为什么要有这个页面</h3>
 *
 * 2026-09-05 之前，`bom_recipes.overall_yield_rate` 有且只有**一个**写入者：
 * `BomYieldSuggestionServiceImpl` 在结单后把实测中位数自动写回去（appliedBy=SYSTEM，
 * 生产上改过 12 次）。人工那条路 `recalculate-apply` 端点一直在，
 * **但整个 web-admin 里 0 个调用方** —— 人类从来没有地方可以点。
 *
 * 自动回写已删除（owner 裁定「标准只能人改」），于是必须补上人能点的那一处，
 * 否则这条基准线就变成了**谁也改不了**，而下游的备料计算全靠它。
 *
 * <h3>为什么不做在工艺画布里</h3>
 *
 * 画布上那层 BOM 是**只读投影**（见 `bomOverlay.ts` 顶部注释：「不属于工艺定义」）。
 * 往只读的东西上挂写操作，会让「标准存在哪」长出第二个答案 —— 而同一个东西有两份，
 * 它一定会漂。标准挂在成品的 BomRecipe 上，就在成品这一层维护。
 *
 * <h3>这页不做什么</h3>
 *
 * ⛔ 不自动填、不批量「一键采纳建议」。系统建议只作为**参考列**显示，
 * 每一行都要人自己敲数字再保存 —— 那正是本次改动要恢复的东西。
 */
import { ref, computed, onMounted } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Refresh, EditPen } from '@element-plus/icons-vue';
import { useAuthStore } from '@/store/modules/auth';
import { PROCESS_SECRET_LABEL, isProcessSecretRow, processSecretMessage } from '@/utils/processSecretField';
import {
  bomYieldEstimateApi,
  type RecalculatePreviewRow,
} from '@/api/bom';

const authStore = useAuthStore();

/**
 * 谁能改 —— 与后端 `PermissionServiceImpl#YIELD_STANDARD_WRITE_ROLES` 同一份口径。
 *
 * ⚠️ 前端权限 store 是**模块级**矩阵（production: 'rw'），认不出
 * `bom:yield_standard:write` 这种细粒度码，所以这里按角色判。
 * 它只是**防呆**：真正的门在后端，这里判错了顶多是按钮该灰没灰（后端照样 403）。
 * ⛔ 反过来不成立 —— 不要因为这里灰了就以为后端安全。
 */
const WRITE_ROLES = ['platform_admin', 'factory_super_admin'];

/**
 * 标准出成率的防手滑上限(%)。
 *
 * 🔴 2026-09-06 (RECON-LEGAL-VALUE-SENTINEL-20260906): 这一层原来拒绝 `>100` 且额外
 * 拒绝 `===100`（说 100 会被当成「未配置」）。两条都作废了：
 *
 * · `100` 是**合法**的零损耗值 —— 后端已删掉那个把 100.00 当哨兵的判据；
 * · `>100` 本来就合法（保水/腌制，六膳门猪舌实测 105–126%），旧上限把它们挡死了。
 *
 * ⚠️ **三层一条规则**，改这个数就要同时改另外两处，否则会有一层先说不行(形态 D)：
 *   ① 本文件这个常量（UI 输入校验）
 *   ② `BomYieldApplyRequest` 上的 `@DecimalMax("300.00")` + `BomYieldEstimateServiceImpl#assertYieldRateInRange`
 *   ③ DB 的 `bom_recipes.chk_br_yield`（`V20261101_32` 放宽到 `<= 300`）
 */
const YIELD_RATE_MAX_PERCENT = 300;
/**
 * 🔴 2026-09-05 部署后打开页面才发现: 原来写的是 `authStore.user?.role`，
 * 而 `cretas_user` 对象**根本没有 role 这个键**（实测键为
 * id/username/fullName/email/isActive/createdAt/updatedAt/userType/factoryUser）——
 * 角色在 `user.factoryUser.role`（工厂账号）或 `user.platformUser.role`（平台账号）。
 *
 * <p>后果是<b>反的</b>：`canEdit` 恒 false ⇒ 连 `factory_super_admin` 都点不了，
 * 页面顶上还挂着「你的账号只能查看」。用 `liushanmen_admin`
 * (factory_super_admin) 实测：三个「填写」按钮全部 disabled=true。</p>
 *
 * <p>⇒ 一律用 `authStore.currentRole`：它是 store 里那个<b>唯一</b>的归一化出口，
 * 处理平台/工厂两种账号形状，还把「super_admin 且无 factoryId」归一成 platform_admin。
 * ⛔ 不要再从 user 对象里自己挖 role —— 那正是这个 bug。</p>
 *
 * <p>⚠️ 这是「抄了形状没抄判据」：我照抄 CalibrationListView.vue 的
 * `authStore.user?.role === 'platform_admin'`，而<b>那一处本身就是坏的</b>（恒 false）。
 * 全仓另有 3 处同病，见 yieldStandardUsesNormalizedRole.source.spec.ts 里的登记。</p>
 */
const canEdit = computed(() => WRITE_ROLES.includes(String(authStore.currentRole ?? '')));
const factoryId = computed(() => authStore.factoryId);

const loading = ref(false);
const rows = ref<RecalculatePreviewRow[]>([]);
const saving = ref<Record<string, boolean>>({});

async function loadData() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const res = await bomYieldEstimateApi.recalculatePreview(factoryId.value);
    rows.value = res.success && Array.isArray(res.data) ? res.data : [];
  } catch (e) {
    // ⛔ 不吞错、不把「拉不到」显示成「一条都没有」——那两件事对用户完全不同
    rows.value = [];
    ElMessage({
      message: `读取标准出成率失败：${(e as Error)?.message || e}`,
      type: 'error',
      duration: 0,
      showClose: true,
    });
  } finally {
    loading.value = false;
  }
}

/** 当前值的显示 —— null 是「没填」，不是 0。 */
function currentText(row: RecalculatePreviewRow): string {
  return row.currentYieldRate == null ? '未填' : `${row.currentYieldRate}%`;
}

/**
 * 建议值的显示。
 * ⚠️ 样本不足时后端给 null —— 显示成「样本不足」，⛔ 不要显示 0%，
 * 那会被读成「系统算出来是 0」。
 */
function suggestionText(row: RecalculatePreviewRow): string {
  if (row.suggestedYieldRate == null) {
    return row.sampleCount > 0 ? `样本不足 (${row.sampleCount}/3)` : '尚无生产数据';
  }
  return `${row.suggestedYieldRate}%  (${row.sampleCount} 批)`;
}

async function handleEdit(row: RecalculatePreviewRow) {
  const hint = row.suggestedYieldRate == null
    ? '这个成品还没有足够的生产数据，请按研发标准填写。'
    : `系统按 ${row.sampleCount} 个批次实测的中位数是 ${row.suggestedYieldRate}%，仅供参考。`;

  let input: string;
  try {
    const result = await ElMessageBox.prompt(
      `${hint}\n\n标准出成率是备料计算的基准线，改动会影响之后所有计划的用料估算。`,
      `设置标准出成率 — ${row.productName}`,
      {
        confirmButtonText: '保存',
        cancelButtonText: '取消',
        inputValue: row.currentYieldRate == null ? '' : String(row.currentYieldRate),
        inputPlaceholder: '例如 60 表示 60%',
        inputValidator: (value: string) => {
          const v = Number(value);
          if (!value || Number.isNaN(v)) return '请输入数字';
          if (v <= 0) return '出成率必须大于 0';
          // 🔴 2026-09-06 (RECON-LEGAL-VALUE-SENTINEL-20260906): 放开 >100 与 ===100 两条拒绝。
          //    · 100% 现在是**合法**的零损耗值 —— 后端已不再把它当「未配置」哨兵
          //      (ProductionPlanServiceImpl#resolveEffectiveProductYieldRate 那道
          //       isMainMaterialCandidate 闸已删除)。旧提示教用户填 99.99 是在教一个错误的绕法。
          //    · 保水/腌制工序出成率天然 >100 (六膳门猪舌 105–126%), 原来的 ≤100 把它们挡死了。
          // ⚠️ 三层同一条规则, 改这里必须同时改另外两处(形态 D):
          //    BomYieldApplyRequest 的 @DecimalMin/@DecimalMax、
          //    BomYieldEstimateServiceImpl#assertYieldRateInRange、
          //    DB 的 bom_recipes.chk_br_yield (V20261101_32 放宽到 300)。
          if (v > YIELD_RATE_MAX_PERCENT) {
            return `出成率不能超过 ${YIELD_RATE_MAX_PERCENT}%（超过通常是小数点点错了，请核对）`;
          }
          return true;
        },
      },
    ) as { value: string };
    input = result.value;
  } catch {
    return; // 用户取消
  }

  saving.value[row.recipeId] = true;
  try {
    const res = await bomYieldEstimateApi.recalculateApply(factoryId.value!, [{
      recipeId: row.recipeId,
      yieldRate: Number(input),
      // 乐观锁：拿预览时看到的值当期望值，别人改过就让后端拦下来
      expectedCurrentYieldRate: row.currentYieldRate,
    }]);
    if (res.success) {
      ElMessage.success(`${row.productName} 标准出成率已保存为 ${input}%`);
      await loadData();
    } else {
      ElMessage({
        message: res.message || '保存失败',
        type: 'error',
        duration: 0,
        showClose: true,
      });
    }
  } catch (e) {
    ElMessage({
      message: `保存失败：${(e as Error)?.message || e}`,
      type: 'error',
      duration: 0,
      showClose: true,
    });
  } finally {
    saving.value[row.recipeId] = false;
  }
}

onMounted(loadData);
</script>

<template>
  <div class="page-wrapper">
    <el-card class="page-card" shadow="never">
      <template #header>
        <div class="card-header">
          <span class="page-title">标准出成率维护</span>
          <el-button :icon="Refresh" :loading="loading" @click="loadData">刷新</el-button>
        </div>
      </template>

      <el-alert type="info" :closable="false" show-icon class="intro">
        <template #title>标准出成率只能人工填写，不会被生产结果自动改掉</template>
        <div class="intro-body">
          它是<b>报价、备料、成本对账共同的基准线</b>：填了之后，新建生产计划时才能按
          「计划量 ÷ 出成率」算出要投多少主料 —— 哪怕这个成品<b>一次都还没生产过</b>。
          实际生产的波动记在报工里，与这条线分开看，用来发现偏差。
        </div>
      </el-alert>

      <el-alert
        v-if="!canEdit"
        type="warning"
        :closable="false"
        show-icon
        class="intro"
        title="你的账号只能查看"
      >
        <div class="intro-body">标准出成率的修改权限限工厂超管 / 平台管理员。需要修改请联系张权。</div>
      </el-alert>

      <el-table v-loading="loading" :data="rows" border stripe class="yield-table">
        <el-table-column prop="productName" label="成品" min-width="200" show-overflow-tooltip />
        <el-table-column prop="materialName" label="主原料" min-width="150" show-overflow-tooltip />
        <el-table-column label="标准出成率" width="150" align="center">
          <template #default="{ row }">
            <el-tooltip v-if="isProcessSecretRow(row)" :content="processSecretMessage(row)">
              <el-tag type="info" effect="plain">{{ PROCESS_SECRET_LABEL }}</el-tag>
            </el-tooltip>
            <el-tag v-else-if="row.currentYieldRate == null" type="danger" effect="plain">未填</el-tag>
            <span v-else class="current-value">{{ currentText(row) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="系统实测 (参考)" width="200" align="center">
          <template #default="{ row }">
            <span v-if="isProcessSecretRow(row)" class="suggestion">{{ PROCESS_SECRET_LABEL }}</span>
            <span v-else class="suggestion">{{ suggestionText(row) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="140" align="center" fixed="right">
          <template #default="{ row }">
            <el-button
              link
              type="primary"
              :icon="EditPen"
              :disabled="!canEdit || isProcessSecretRow(row)"
              :title="isProcessSecretRow(row) ? '工艺已保密，需保密负责人授权才能修改' : undefined"
              :loading="saving[row.recipeId]"
              @click="handleEdit(row)"
            >
              {{ row.currentYieldRate == null ? '填写' : '修改' }}
            </el-button>
          </template>
        </el-table-column>
        <template #empty>
          <div class="empty-hint">
            没有可维护的配方。<br />
            标准出成率挂在成品的 BOM 配方上 —— 若这里是空的，先确认该成品已经有 ACTIVE 配方且配了物料明细。
          </div>
        </template>
      </el-table>
    </el-card>
  </div>
</template>

<style scoped>
.page-wrapper { padding: 16px; }
.card-header { display: flex; align-items: center; justify-content: space-between; }
.page-title { font-size: 16px; font-weight: 600; }
.intro { margin-bottom: 12px; }
.intro-body { line-height: 1.7; font-size: 13px; }
.yield-table { margin-top: 8px; }
.current-value { font-weight: 600; }
.suggestion { color: #909399; font-size: 13px; }
.empty-hint { padding: 24px; color: #909399; line-height: 1.8; }
</style>
