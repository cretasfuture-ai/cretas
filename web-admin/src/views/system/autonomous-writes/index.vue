<script setup lang="ts">
/**
 * S6-04 定时任务写入（本厂超级管理员）：
 * 1. 总开关：默认关闭，打开后定时任务才可能按委托授权写入；关闭后下一次触发起停止，已在执行的不回滚。
 * 2. 委托授权：每条授权只对一个定时任务、一个工具、固定参数、有限次数和期限有效，可随时撤销。
 * 3. 最近的写入运行：包括被拦下的运行和原因。
 */
import { ref, computed, onMounted } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { useAuthStore } from '@/store/modules/auth';
import { handleCatchError } from '@/utils/errorToast';
import {
  getWriteSwitch, setWriteSwitch, listGrants, issueGrant, revokeGrant, listSchedulerConfigs,
  listSchedulerRuns,
  type WriteSwitchView, type AutonomousGrant, type SchedulerConfig, type SchedulerRun
} from '@/api/autonomousWrites';
import { SCHEDULABLE_WRITE_TOOLS, toolLabel, grantState, runOutcome, paramsText } from './labels';

const authStore = useAuthStore();
const factoryId = computed(() => authStore.factoryId);

const loading = ref(false);
const switchState = ref<WriteSwitchView | null>(null);
const grants = ref<AutonomousGrant[]>([]);
const configs = ref<SchedulerConfig[]>([]);
const runs = ref<SchedulerRun[]>([]);

/** 写入类定时任务：用了开放给定时任务的写工具，或者已经有过授权的任务。 */
const writeConfigs = computed(() => configs.value.filter(config =>
  config.toolOrMethod in SCHEDULABLE_WRITE_TOOLS
  || grants.value.some(grant => grant.sourceRef === String(config.id))));

function configOf(grant: AutonomousGrant) {
  return configs.value.find(config => String(config.id) === grant.sourceRef);
}

async function reload() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const [switchRes, grantRes, configRes] = await Promise.all([
      getWriteSwitch(factoryId.value), listGrants(factoryId.value), listSchedulerConfigs(factoryId.value)
    ]);
    switchState.value = switchRes.data ?? null;
    grants.value = grantRes.data ?? [];
    configs.value = configRes.data ?? [];
    const runPages = await Promise.all(writeConfigs.value.map(config =>
      listSchedulerRuns(factoryId.value as string, config.taskCode, 10)));
    runs.value = runPages.flatMap(page => page.data?.content ?? [])
      .sort((a, b) => (b.startedAt ?? '').localeCompare(a.startedAt ?? ''))
      .slice(0, 30);
  } catch (error: unknown) {
    handleCatchError(error, '加载定时任务写入信息失败');
  } finally {
    loading.value = false;
  }
}

// ============ 总开关 ============
const switching = ref(false);

async function toggleSwitch(turnOn: boolean) {
  if (!factoryId.value) return;
  let reason = '';
  try {
    const answer = await ElMessageBox.prompt(
      turnOn
        ? '打开后，已签发委托授权的定时任务会在下一次触发时按授权写入业务数据。请填写原因：'
        : '关闭后，从下一次触发起所有定时任务都不会再写入，已在执行的那一次不回滚。请填写原因：',
      turnOn ? '打开定时任务写入' : '关闭定时任务写入',
      { confirmButtonText: turnOn ? '确认打开' : '确认关闭', cancelButtonText: '取消',
        inputValidator: (value: string) => (value ?? '').length <= 200 || '原因最长 200 个字' }
    );
    reason = answer.value ?? '';
  } catch {
    return;
  }
  switching.value = true;
  try {
    const res = await setWriteSwitch(factoryId.value, turnOn, reason);
    switchState.value = res.data ?? null;
    ElMessage.success(turnOn ? '已打开定时任务写入' : '已关闭定时任务写入');
  } catch (error: unknown) {
    handleCatchError(error, '修改总开关失败');
  } finally {
    switching.value = false;
  }
}

// ============ 签发授权 ============
const issueVisible = ref(false);
const issueForm = ref<{ configId: number | null; maxRuns: number; expiresAt: string }>({
  configId: null, maxRuns: 30, expiresAt: ''
});
const issuing = ref(false);
const selectedConfig = computed(() => configs.value.find(config => config.id === issueForm.value.configId));
const issueProblem = computed(() => {
  const config = selectedConfig.value;
  if (!config) return '请选择定时任务';
  if (!(config.toolOrMethod in SCHEDULABLE_WRITE_TOOLS)) return '这个任务用的工具还没有开放给定时任务写入';
  if (config.toolOrMethod === 'alert_rule_toggle' && typeof config.params?.enabled !== 'boolean') {
    return '这个任务的参数里没有写明设为启用还是停用，请先在定时任务配置里补上 enabled';
  }
  if (!issueForm.value.expiresAt) return '请选择授权到期时间';
  return '';
});

function openIssue() {
  issueForm.value = { configId: null, maxRuns: 30, expiresAt: '' };
  issueVisible.value = true;
}

async function submitIssue() {
  const config = selectedConfig.value;
  if (!factoryId.value || !config || issueProblem.value) return;
  issuing.value = true;
  try {
    await issueGrant(factoryId.value, {
      sourceRef: String(config.id), toolName: config.toolOrMethod,
      maxRuns: issueForm.value.maxRuns, expiresAt: issueForm.value.expiresAt
    });
    ElMessage.success('已签发委托授权');
    issueVisible.value = false;
    await reload();
  } catch (error: unknown) {
    handleCatchError(error, '签发授权失败');
  } finally {
    issuing.value = false;
  }
}

async function revoke(grant: AutonomousGrant) {
  if (!factoryId.value) return;
  try {
    await ElMessageBox.confirm(
      `撤销「${configOf(grant)?.taskCode ?? grant.sourceRef}」的委托授权？撤销后下一次触发就不会再写入。`,
      '撤销委托授权', { confirmButtonText: '确认撤销', cancelButtonText: '取消', type: 'warning' });
  } catch {
    return;
  }
  try {
    await revokeGrant(factoryId.value, grant.id);
    ElMessage.success('已撤销');
    await reload();
  } catch (error: unknown) {
    handleCatchError(error, '撤销失败');
  }
}

function formatTime(value: string | null | undefined) {
  return value ? value.replace('T', ' ').slice(0, 19) : '—';
}

onMounted(reload);
</script>

<template>
  <div v-loading="loading" class="page-container">
    <el-card shadow="never" class="block">
      <template #header>定时任务写入总开关</template>
      <div class="switch-row">
        <el-tag :type="switchState?.writesEnabled ? 'success' : 'info'" size="large" data-testid="switch-state">
          {{ switchState?.writesEnabled ? '已打开' : '已关闭' }}
        </el-tag>
        <span class="hint">
          <template v-if="switchState?.changedAt">
            最近由 用户 #{{ switchState.changedBy }} 于 {{ formatTime(switchState.changedAt) }} 修改{{ switchState.reason ? `：${switchState.reason}` : '' }}
          </template>
          <template v-else>还没有打开过（默认关闭，定时任务不会写入任何业务数据）</template>
        </span>
        <el-button v-if="switchState?.writesEnabled" type="danger" :loading="switching" @click="toggleSwitch(false)">关闭</el-button>
        <el-button v-else type="primary" :loading="switching" @click="toggleSwitch(true)">打开</el-button>
      </div>
    </el-card>

    <el-card shadow="never" class="block">
      <template #header>
        <div class="card-header">
          <span>委托授权</span>
          <el-button type="primary" @click="openIssue">签发授权</el-button>
        </div>
      </template>
      <el-table :data="grants" stripe empty-text="还没有委托授权">
        <el-table-column label="工具" min-width="140">
          <template #default="{ row }">{{ toolLabel(row.toolName) }}</template>
        </el-table-column>
        <el-table-column label="定时任务" min-width="160">
          <template #default="{ row }">
            {{ configOf(row)?.taskCode ?? `任务 #${row.sourceRef}` }}
            <div class="hint">{{ configOf(row)?.cronExpression ?? '' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="执行人" width="110">
          <template #default="{ row }">用户 #{{ row.runAsUserId }}</template>
        </el-table-column>
        <el-table-column label="固定参数" min-width="200">
          <template #default="{ row }">{{ paramsText(configOf(row)?.params) }}</template>
        </el-table-column>
        <el-table-column label="次数" width="90" align="center">
          <template #default="{ row }">{{ row.usedRuns }} / {{ row.maxRuns }}</template>
        </el-table-column>
        <el-table-column label="到期" width="170">
          <template #default="{ row }">{{ formatTime(row.expiresAt) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="110" align="center">
          <template #default="{ row }">
            <el-tag :type="grantState(row.state).type">{{ grantState(row.state).label }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="90" align="center">
          <template #default="{ row }">
            <el-button v-if="row.state === 'ACTIVE'" link type="danger" @click="revoke(row)">撤销</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card shadow="never" class="block">
      <template #header>最近的写入运行</template>
      <el-table :data="runs" stripe empty-text="还没有写入类定时任务的运行记录">
        <el-table-column label="时间" width="170">
          <template #default="{ row }">{{ formatTime(row.startedAt) }}</template>
        </el-table-column>
        <el-table-column prop="taskCode" label="定时任务" min-width="150" />
        <el-table-column label="工具" min-width="140">
          <template #default="{ row }">{{ toolLabel(row.toolName) }}</template>
        </el-table-column>
        <el-table-column label="结果" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="runOutcome(row.rawStatus).type">{{ runOutcome(row.rawStatus).label }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="原因" min-width="280">
          <template #default="{ row }">{{ runOutcome(row.rawStatus).reason }}</template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="issueVisible" title="签发委托授权" width="560px">
      <el-form label-width="100px" @submit.prevent>
        <el-form-item label="定时任务" required>
          <el-select v-model="issueForm.configId" placeholder="选择一个写入类定时任务" style="width: 100%">
            <el-option v-for="config in writeConfigs" :key="config.id"
              :label="`${config.taskCode}（${toolLabel(config.toolOrMethod)}，${config.cronExpression}）`" :value="config.id" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="selectedConfig" label="固定参数">
          <span>{{ paramsText(selectedConfig.params) }}</span>
        </el-form-item>
        <el-form-item label="最多次数" required>
          <el-input-number v-model="issueForm.maxRuns" :min="1" :max="1000" :precision="0" />
          <div class="hint">用完后自动失效，需要时再签发</div>
        </el-form-item>
        <el-form-item label="到期时间" required>
          <el-date-picker v-model="issueForm.expiresAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss"
            placeholder="授权在这个时间之后失效" />
        </el-form-item>
        <div class="hint">授权只对这个任务当前的配置、执行人和参数有效；任务改动后授权自动作废。签发人必须是该任务的执行人。</div>
        <div v-if="issueProblem" class="error-hint">{{ issueProblem }}</div>
      </el-form>
      <template #footer>
        <el-button @click="issueVisible = false">取消</el-button>
        <el-button type="primary" :loading="issuing" :disabled="Boolean(issueProblem)" @click="submitIssue">签发</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.block { margin-bottom: 16px; }
.switch-row { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.card-header { display: flex; justify-content: space-between; align-items: center; }
.hint { color: var(--el-text-color-secondary); font-size: 12px; }
.error-hint { color: var(--el-color-danger); font-size: 12px; margin-top: 8px; }
</style>
