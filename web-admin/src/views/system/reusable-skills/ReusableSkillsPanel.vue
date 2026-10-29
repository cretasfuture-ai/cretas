<script setup lang="ts">
/**
 * S6-02 可复用 Skill：试运行一个已注册的 Skill（工具经工具网关、按当前账号权限执行，留下运行回执）→
 * 把成功的运行保存为本厂可复用 Skill（参数契约可编辑，但只能覆盖原运行里有的参数）→ 用新参数复跑。
 * 参数在后端按契约校验，越界 / 未声明 / 指向别厂记录的参数在执行任何工具前被拒绝。
 */
import { ref, computed, onMounted } from 'vue';
import { ElMessage } from 'element-plus';
import { useAuthStore } from '@/store/modules/auth';
import { handleCatchError } from '@/utils/errorToast';
import type { SkillInfo } from '@/api/governance';
import {
  runRegisteredSkill, listMySkillRuns, saveSkillFromRun, listSavedSkills, runSavedSkill,
  setSavedSkillEnabled,
  type SkillRun, type SavedSkill, type SkillParamSpec, type SkillRunResult
} from '@/api/reusableSkills';

const props = defineProps<{ skills: SkillInfo[] }>();

const authStore = useAuthStore();
const factoryId = computed(() => authStore.factoryId);

const runs = ref<SkillRun[]>([]);
const saved = ref<SavedSkill[]>([]);
const loading = ref(false);

async function reload() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const [runRes, savedRes] = await Promise.all([
      listMySkillRuns(factoryId.value),
      listSavedSkills(factoryId.value)
    ]);
    runs.value = runRes.data ?? [];
    saved.value = savedRes.data ?? [];
  } catch (error: unknown) {
    handleCatchError(error, '加载可复用 Skill 失败');
  } finally {
    loading.value = false;
  }
}

// ============ 试运行已注册 Skill ============
interface ParamRow { key: string; value: string }
const trialSkill = ref('');
const trialParams = ref<ParamRow[]>([]);
const trialRunning = ref(false);
const trialResult = ref<SkillRunResult | null>(null);
const enabledSkills = computed(() => props.skills.filter(skill => skill.enabled));

/** 文字值按 JSON 读（数字 / true / false），读不出就当文字。 */
function parseValue(raw: string): unknown {
  const text = raw.trim();
  if (text === '') return '';
  try {
    return JSON.parse(text);
  } catch {
    return text;
  }
}

function rowsToParams(rows: ParamRow[]): Record<string, unknown> {
  const params: Record<string, unknown> = {};
  rows.filter(row => row.key.trim() !== '').forEach(row => { params[row.key.trim()] = parseValue(row.value); });
  return params;
}

async function runTrial() {
  if (!factoryId.value || !trialSkill.value) return;
  trialRunning.value = true;
  trialResult.value = null;
  try {
    const res = await runRegisteredSkill(factoryId.value, trialSkill.value, rowsToParams(trialParams.value));
    trialResult.value = res.data ?? null;
    ElMessage.success('运行成功，已留下运行回执，可以保存为可复用 Skill');
    await reload();
  } catch (error: unknown) {
    handleCatchError(error, 'Skill 运行失败');
    await reload();
  } finally {
    trialRunning.value = false;
  }
}

// ============ 保存为可复用 Skill ============
const saveVisible = ref(false);
const saveRun = ref<SkillRun | null>(null);
const saveForm = ref({ name: '', displayName: '' });
const saveSpecs = ref<SkillParamSpec[]>([]);
const saving = ref(false);

function reusable(run: SkillRun) {
  return run.status === 'SUCCEEDED' && run.steps.length > 0
    && run.steps.every(step => step.auditEventId && step.gatewayStatus === 'SUCCEEDED');
}

function inferType(value: unknown): SkillParamSpec['type'] | null {
  if (typeof value === 'boolean') return 'BOOLEAN';
  if (typeof value === 'number') return Number.isInteger(value) ? 'INTEGER' : 'NUMBER';
  if (typeof value === 'string') return /^\d{4}-\d{2}-\d{2}$/.test(value) ? 'DATE' : 'STRING';
  return null;
}

function openSave(run: SkillRun) {
  saveRun.value = run;
  saveForm.value = { name: '', displayName: run.skillName };
  const specs = new Map<string, SkillParamSpec>();
  run.steps.forEach(step => Object.entries(step.params ?? {}).forEach(([name, value]) => {
    const type = inferType(value);
    if (type && !specs.has(name)) specs.set(name, { name, type, required: false });
  }));
  saveSpecs.value = [...specs.values()];
  saveVisible.value = true;
}

const nameValid = computed(() => /^[a-z0-9][a-z0-9_-]{0,63}$/.test(saveForm.value.name));
const boundsValid = computed(() => saveSpecs.value.every(spec =>
  spec.min === undefined || spec.max === undefined || spec.min <= spec.max));

async function submitSave() {
  if (!factoryId.value || !saveRun.value || !nameValid.value || !boundsValid.value) return;
  saving.value = true;
  try {
    await saveSkillFromRun(factoryId.value, {
      runId: saveRun.value.id,
      name: saveForm.value.name,
      displayName: saveForm.value.displayName || undefined,
      parameterContract: { params: saveSpecs.value }
    });
    ElMessage.success('已保存为可复用 Skill');
    saveVisible.value = false;
    await reload();
  } catch (error: unknown) {
    handleCatchError(error, '保存失败');
  } finally {
    saving.value = false;
  }
}

// ============ 复跑可复用 Skill ============
const rerunVisible = ref(false);
const rerunSkill = ref<SavedSkill | null>(null);
const rerunValues = ref<Record<string, unknown>>({});
const rerunning = ref(false);
const rerunResult = ref<SkillRunResult | null>(null);

function capturedValue(skill: SavedSkill, name: string): unknown {
  const step = skill.steps.find(candidate => candidate.params && name in candidate.params);
  return step ? step.params[name] : undefined;
}

function openRerun(skill: SavedSkill) {
  rerunSkill.value = skill;
  rerunResult.value = null;
  const values: Record<string, unknown> = {};
  skill.parameterContract.params.forEach(spec => { values[spec.name] = capturedValue(skill, spec.name); });
  rerunValues.value = values;
  rerunVisible.value = true;
}

function boundsText(spec: SkillParamSpec) {
  const parts: string[] = [spec.required ? '必填' : '可不填'];
  if (spec.min !== undefined) parts.push(`最小 ${spec.min}`);
  if (spec.max !== undefined) parts.push(`最大 ${spec.max}`);
  if (spec.allowedValues?.length) parts.push(`可选：${spec.allowedValues.join('、')}`);
  if (spec.factoryScopedRef) parts.push('必须是本厂的记录');
  return parts.join('，');
}

const rerunMissing = computed(() => (rerunSkill.value?.parameterContract.params ?? [])
  .filter(spec => spec.required && (rerunValues.value[spec.name] === undefined || rerunValues.value[spec.name] === ''))
  .map(spec => spec.name));

async function submitRerun() {
  if (!factoryId.value || !rerunSkill.value || rerunMissing.value.length) return;
  const params: Record<string, unknown> = {};
  Object.entries(rerunValues.value).forEach(([name, value]) => {
    if (value !== undefined && value !== '' && value !== null) params[name] = value;
  });
  rerunning.value = true;
  try {
    const res = await runSavedSkill(factoryId.value, rerunSkill.value.id, params);
    rerunResult.value = res.data?.result ?? null;
    ElMessage.success('运行成功，已留下新的运行回执');
    await reload();
  } catch (error: unknown) {
    handleCatchError(error, 'Skill 运行失败');
    await reload();
  } finally {
    rerunning.value = false;
  }
}

async function toggleSaved(skill: SavedSkill, enabled: boolean) {
  if (!factoryId.value) return;
  try {
    await setSavedSkillEnabled(factoryId.value, skill.id, enabled);
    ElMessage.success(enabled ? '已启用' : '已停用');
    await reload();
  } catch (error: unknown) {
    handleCatchError(error, '操作失败');
    await reload();
  }
}

function statusType(status: string) {
  return status === 'SUCCEEDED' ? 'success' : status === 'OUTCOME_UNKNOWN' ? 'warning' : 'danger';
}

function statusLabel(status: string) {
  return ({ SUCCEEDED: '成功', FAILED: '失败', DENIED: '被拒绝', OUTCOME_UNKNOWN: '结果未知' } as Record<string, string>)[status] ?? status;
}

onMounted(reload);
</script>

<template>
  <div v-loading="loading" class="reusable-skills">
    <el-card shadow="never" class="block">
      <template #header>试运行 Skill（按你的账号权限执行，成功后可保存）</template>
      <el-form label-width="90px" @submit.prevent>
        <el-form-item label="Skill">
          <el-select v-model="trialSkill" filterable placeholder="选择一个已启用的 Skill" style="width: 320px">
            <el-option v-for="skill in enabledSkills" :key="skill.name" :label="`${skill.displayName}（${skill.name}）`"
              :value="skill.name" />
          </el-select>
        </el-form-item>
        <el-form-item label="参数">
          <div class="param-rows">
            <div v-for="(row, index) in trialParams" :key="index" class="param-row">
              <el-input v-model="row.key" placeholder="参数名，如 limit" style="width: 160px" />
              <el-input v-model="row.value" placeholder="值，如 5" style="width: 200px" />
              <el-button link type="danger" @click="trialParams.splice(index, 1)">删除</el-button>
            </div>
            <el-button link type="primary" @click="trialParams.push({ key: '', value: '' })">+ 加一个参数</el-button>
            <div class="hint">不要填工厂、用户、角色：执行人就是当前登录的你。</div>
          </div>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="trialRunning" :disabled="!trialSkill" @click="runTrial">运行</el-button>
        </el-form-item>
      </el-form>
      <el-alert v-if="trialResult" type="success" :closable="false"
        :title="`运行成功：${trialResult.executedTools.join('、')}，回执 ${trialResult.runId ?? '（未记录）'}`" />
    </el-card>

    <el-card shadow="never" class="block">
      <template #header>我最近的运行</template>
      <el-table :data="runs" stripe empty-text="还没有运行记录">
        <el-table-column prop="skillName" label="Skill" min-width="160" />
        <el-table-column label="工具" min-width="200">
          <template #default="{ row }">{{ row.steps.map((step: { tool: string }) => step.tool).join('、') }}</template>
        </el-table-column>
        <el-table-column label="结果" width="100" align="center">
          <template #default="{ row }"><el-tag :type="statusType(row.status)">{{ statusLabel(row.status) }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="createdAt" label="时间" width="180" />
        <el-table-column label="操作" width="160" align="center">
          <template #default="{ row }">
            <el-button v-if="reusable(row)" link type="primary" @click="openSave(row)">保存为可复用</el-button>
            <span v-else class="hint">{{ row.savedSkillId ? '复跑回执' : '未全部经网关成功，不能保存' }}</span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card shadow="never" class="block">
      <template #header>本厂可复用 Skill</template>
      <el-table :data="saved" stripe empty-text="还没有可复用 Skill：先试运行并保存一次成功的运行">
        <el-table-column prop="displayName" label="名称" min-width="160" />
        <el-table-column label="参数" min-width="220">
          <template #default="{ row }">{{ row.parameterContract.params.map((spec: SkillParamSpec) => spec.name).join('、') || '无' }}</template>
        </el-table-column>
        <el-table-column prop="sourceRunId" label="来源运行" min-width="200" />
        <el-table-column label="启用" width="80" align="center">
          <template #default="{ row }">
            <el-switch :model-value="row.enabled" @change="(value: string | number | boolean) => toggleSaved(row, Boolean(value))" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="100" align="center">
          <template #default="{ row }">
            <el-button link type="primary" :disabled="!row.enabled" @click="openRerun(row)">运行</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="saveVisible" :title="`保存为可复用 Skill — ${saveRun?.skillName ?? ''}（回执 ${saveRun?.id ?? ''}）`" width="680px">
      <el-form label-width="90px" @submit.prevent>
        <el-form-item label="名称" required>
          <el-input v-model="saveForm.name" placeholder="小写字母、数字、下划线、短横线，如 expired-check" />
          <div v-if="saveForm.name && !nameValid" class="error-hint">只能用小写字母、数字、下划线和短横线，最长 64 个字符</div>
        </el-form-item>
        <el-form-item label="显示名称">
          <el-input v-model="saveForm.displayName" maxlength="200" />
        </el-form-item>
        <el-form-item label="参数契约">
          <div class="hint">只能调整原运行里有的参数；复跑时超出范围的值会在执行前被拒绝。</div>
          <el-table :data="saveSpecs" size="small" empty-text="原运行没有可调整的参数">
            <el-table-column prop="name" label="参数" width="130" />
            <el-table-column prop="type" label="类型" width="90" />
            <el-table-column label="必填" width="70">
              <template #default="{ row }"><el-checkbox v-model="row.required" /></template>
            </el-table-column>
            <el-table-column label="最小 / 最大" min-width="220">
              <template #default="{ row }">
                <template v-if="row.type === 'INTEGER' || row.type === 'NUMBER'">
                  <el-input-number v-model="row.min" size="small" :controls="false" style="width: 90px" />
                  ~
                  <el-input-number v-model="row.max" size="small" :controls="false" style="width: 90px" />
                </template>
                <span v-else class="hint">—</span>
              </template>
            </el-table-column>
          </el-table>
          <div v-if="!boundsValid" class="error-hint">最小值不能大于最大值</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="saveVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" :disabled="!nameValid || !boundsValid" @click="submitSave">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="rerunVisible" :title="`运行可复用 Skill — ${rerunSkill?.displayName ?? ''}`" width="560px">
      <div class="hint">按当前登录账号的权限执行；每次运行都会留下新的回执，原 Skill 不变。</div>
      <el-form label-width="110px" @submit.prevent>
        <el-form-item v-for="spec in rerunSkill?.parameterContract.params ?? []" :key="spec.name" :label="spec.name"
          :required="spec.required">
          <el-input-number v-if="spec.type === 'INTEGER' || spec.type === 'NUMBER'"
            v-model="(rerunValues[spec.name] as number | undefined)" :min="spec.min" :max="spec.max"
            :precision="spec.type === 'INTEGER' ? 0 : undefined" />
          <el-switch v-else-if="spec.type === 'BOOLEAN'" v-model="(rerunValues[spec.name] as boolean | undefined)" />
          <el-select v-else-if="spec.type === 'ENUM'" v-model="(rerunValues[spec.name] as string | undefined)">
            <el-option v-for="value in spec.allowedValues ?? []" :key="value" :label="value" :value="value" />
          </el-select>
          <el-date-picker v-else-if="spec.type === 'DATE'" v-model="(rerunValues[spec.name] as string | undefined)"
            type="date" value-format="YYYY-MM-DD" />
          <el-input v-else v-model="(rerunValues[spec.name] as string | undefined)" :maxlength="spec.maxLength ?? 200" />
          <div class="hint">{{ boundsText(spec) }}</div>
        </el-form-item>
      </el-form>
      <div v-if="rerunMissing.length" class="error-hint">还缺必填参数：{{ rerunMissing.join('、') }}</div>
      <el-alert v-if="rerunResult" type="success" :closable="false"
        :title="`运行成功，回执 ${rerunResult.runId ?? '（未记录）'}`" />
      <template #footer>
        <el-button @click="rerunVisible = false">关闭</el-button>
        <el-button type="primary" :loading="rerunning" :disabled="rerunMissing.length > 0" @click="submitRerun">运行</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.block { margin-bottom: 16px; }
.param-rows { display: flex; flex-direction: column; gap: 8px; }
.param-row { display: flex; gap: 8px; align-items: center; }
.hint { color: var(--el-text-color-secondary); font-size: 12px; }
.error-hint { color: var(--el-color-danger); font-size: 12px; }
</style>
