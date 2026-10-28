<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  createSavedView, deleteSavedView, executeSavedView, getViewOptions, listSavedViews,
  type SavedViewSummary, type ViewDefinition, type ViewEntityOption, type ViewExecuteResult,
} from '@/api/savedViews'
import { useAuthStore } from '@/store/modules/auth'

// 动态业务视图：保存的是「查询定义」，不是结果。每次打开由服务端按当前账号的权限和本厂范围重算，
// 所以同一个视图换一个权限更少的人打开，看到的行列只会更少。
const auth = useAuthStore()
const factoryId = computed(() => auth.factoryId)

const options = ref<ViewEntityOption[]>([])
const views = ref<SavedViewSummary[]>([])
const loading = ref(false)
const error = ref('')

const opened = ref<ViewExecuteResult | null>(null)
const openedName = ref('')
const opening = ref(false)
const openError = ref('')

const dialog = ref(false)
const saving = ref(false)
const form = ref<{ name: string; shared: boolean; def: ViewDefinition }>(blank())

function blank() {
  return {
    name: '',
    shared: false,
    def: { entity: '', columns: [], filters: [], sort: null, limit: 50 } as ViewDefinition,
  }
}

const entityOption = computed(() => options.value.find((o) => o.key === form.value.def.entity))
const allowedFields = computed(() => (entityOption.value?.fields ?? []).filter((f) => f.allowed))
const allowedEntities = computed(() => options.value.filter((o) => o.allowed))
const fieldLabel = (entity: string, name: string) =>
  options.value.find((o) => o.key === entity)?.fields.find((f) => f.name === name)?.label ?? name
const entityLabel = (key: string) => options.value.find((o) => o.key === key)?.label ?? key
const opsFor = (name: string) => allowedFields.value.find((f) => f.name === name)?.ops ?? []

const OP_LABEL: Record<string, string> = {
  eq: '等于', ne: '不等于', contains: '包含', gt: '大于', gte: '大于等于', lt: '小于', lte: '小于等于',
}

function messageOf(reason: unknown, fallback: string) {
  return reason instanceof Error ? reason.message : fallback
}

async function load() {
  if (!factoryId.value) { error.value = '当前账号没有所属工厂'; return }
  loading.value = true
  error.value = ''
  try {
    const [opt, list] = await Promise.all([getViewOptions(factoryId.value), listSavedViews(factoryId.value)])
    options.value = opt.data
    views.value = list.data
  } catch (reason) {
    error.value = messageOf(reason, '视图加载失败')
  } finally {
    loading.value = false
  }
}

async function openView(view: SavedViewSummary) {
  opening.value = true
  openError.value = ''
  opened.value = null
  openedName.value = view.name
  try {
    const res = await executeSavedView(factoryId.value, view.id)
    opened.value = res.data
  } catch (reason) {
    openError.value = messageOf(reason, '视图打开失败')
  } finally {
    opening.value = false
  }
}

function startCreate() {
  form.value = blank()
  dialog.value = true
}

function onEntityChange() {
  form.value.def.columns = []
  form.value.def.filters = []
  form.value.def.sort = null
}

function addFilter() {
  const first = allowedFields.value[0]
  if (!first) return
  form.value.def.filters.push({ field: first.name, op: first.ops[0], value: '' })
}

function onFilterFieldChange(index: number) {
  const f = form.value.def.filters[index]
  f.op = opsFor(f.field)[0]
  f.value = ''
}

function setSortField(field: string | undefined) {
  form.value.def.sort = field ? { field, direction: form.value.def.sort?.direction ?? 'asc' } : null
}

async function save() {
  const { name, shared, def } = form.value
  if (!name.trim()) { ElMessage.error('请填写视图名称'); return }
  if (!def.entity || def.columns.length === 0) { ElMessage.error('请选择数据范围和至少一列'); return }
  saving.value = true
  try {
    const res = await createSavedView(factoryId.value, {
      name: name.trim(),
      shared,
      definition: { ...def, sort: def.sort ?? null },
    })
    dialog.value = false
    ElMessage.success('视图已保存')
    await load()
    await openView(res.data)
  } catch (reason) {
    ElMessage.error(messageOf(reason, '保存失败'))
  } finally {
    saving.value = false
  }
}

async function remove(view: SavedViewSummary) {
  try {
    await ElMessageBox.confirm(`删除视图「${view.name}」？只删除定义，不影响任何业务数据。`, '删除视图', { type: 'warning' })
  } catch { return }
  try {
    await deleteSavedView(factoryId.value, view.id)
    if (opened.value?.viewId === view.id) opened.value = null
    await load()
  } catch (reason) {
    ElMessage.error(messageOf(reason, '删除失败'))
  }
}

function cell(value: unknown) {
  if (value === null || value === undefined) return '-'
  if (typeof value === 'boolean') return value ? '是' : '否'
  return String(value)
}

onMounted(load)
</script>

<template>
  <div class="saved-views" data-testid="saved-views-page">
    <div class="head">
      <div>
        <h2>业务视图</h2>
        <p class="hint">保存的是查询条件，不是数据。每次打开都会按您当前的权限重新计算，权限更少的同事打开同一个视图只会看到更少。</p>
      </div>
      <el-button type="primary" data-testid="saved-view-new" @click="startCreate">新建视图</el-button>
    </div>

    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" />

    <el-row :gutter="16">
      <el-col :span="8">
        <el-card v-loading="loading" header="我的与共享的视图">
          <el-empty v-if="!loading && views.length === 0" description="还没有视图" />
          <div v-for="v in views" :key="v.id" class="view-row" :data-testid="`saved-view-${v.id}`">
            <div class="meta">
              <b>{{ v.name }}</b>
              <span class="sub">{{ entityLabel(v.entity) }} · v{{ v.version }}<template v-if="!v.mine"> · 同事共享</template><template v-else-if="v.shared"> · 已共享</template></span>
            </div>
            <div>
              <el-button size="small" @click="openView(v)">打开</el-button>
              <el-button v-if="v.mine" size="small" type="danger" plain @click="remove(v)">删除</el-button>
            </div>
          </div>
        </el-card>
      </el-col>

      <el-col :span="16">
        <el-card :header="openedName ? `视图结果：${openedName}` : '视图结果'" v-loading="opening">
          <el-alert v-if="openError" :title="openError" type="error" show-icon :closable="false" data-testid="saved-view-error" />
          <el-empty v-else-if="!opened" description="从左侧打开一个视图" />
          <template v-else>
            <el-alert
              v-if="opened.hiddenColumns.length"
              type="warning" show-icon :closable="false" data-testid="saved-view-hidden"
              :title="`您的账号无权查看这些列，已隐藏：${opened.hiddenColumns.map((c) => fieldLabel(opened!.entity, c)).join('、')}`"
            />
            <el-table :data="opened.rows" border size="small" data-testid="saved-view-table">
              <el-table-column v-for="c in opened.columns" :key="c" :label="fieldLabel(opened.entity, c)">
                <template #default="{ row }">{{ cell(row[c]) }}</template>
              </el-table-column>
            </el-table>
            <p class="sub">
              共 {{ opened.rowCount }} 行<template v-if="opened.truncated">（已达上限，仅显示前 {{ opened.rowCount }} 行）</template>，
              计算时间 {{ opened.executedAt }}
            </p>
          </template>
        </el-card>
      </el-col>
    </el-row>

    <el-dialog v-model="dialog" title="新建视图" width="640px">
      <el-form label-width="90px">
        <el-form-item label="名称"><el-input v-model="form.name" maxlength="100" data-testid="saved-view-name" /></el-form-item>
        <el-form-item label="数据范围">
          <el-select v-model="form.def.entity" placeholder="选择" data-testid="saved-view-entity" @change="onEntityChange">
            <el-option v-for="e in allowedEntities" :key="e.key" :label="e.label" :value="e.key" />
          </el-select>
        </el-form-item>
        <template v-if="form.def.entity">
          <el-form-item label="显示列">
            <el-checkbox-group v-model="form.def.columns">
              <el-checkbox v-for="f in allowedFields" :key="f.name" :value="f.name">{{ f.label }}</el-checkbox>
            </el-checkbox-group>
          </el-form-item>
          <el-form-item label="筛选">
            <div v-for="(f, i) in form.def.filters" :key="i" class="filter">
              <el-select v-model="f.field" style="width: 130px" @change="onFilterFieldChange(i)">
                <el-option v-for="o in allowedFields" :key="o.name" :label="o.label" :value="o.name" />
              </el-select>
              <el-select v-model="f.op" style="width: 110px">
                <el-option v-for="op in opsFor(f.field)" :key="op" :label="OP_LABEL[op]" :value="op" />
              </el-select>
              <el-input v-model="f.value" style="width: 140px" />
              <el-button link type="danger" @click="form.def.filters.splice(i, 1)">移除</el-button>
            </div>
            <el-button size="small" @click="addFilter">添加条件</el-button>
          </el-form-item>
          <el-form-item label="排序">
            <el-select :model-value="form.def.sort?.field" clearable style="width: 160px" @update:model-value="setSortField">
              <el-option v-for="o in allowedFields" :key="o.name" :label="o.label" :value="o.name" />
            </el-select>
            <el-radio-group v-if="form.def.sort" v-model="form.def.sort.direction" class="dir">
              <el-radio value="asc">升序</el-radio>
              <el-radio value="desc">降序</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="最多行数"><el-input-number v-model="form.def.limit" :min="1" :max="200" /></el-form-item>
        </template>
        <el-form-item label="共享">
          <el-switch v-model="form.shared" active-text="本厂同事可打开（各自按自己的权限看）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" data-testid="saved-view-save" @click="save">保存并打开</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.saved-views { padding: 16px; }
.head { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 12px; }
.hint, .sub { color: var(--el-text-color-secondary); font-size: 13px; margin: 4px 0; }
.view-row { display: flex; justify-content: space-between; align-items: center; padding: 8px 0; border-bottom: 1px solid var(--el-border-color-lighter); }
.meta { display: flex; flex-direction: column; }
.filter { display: flex; gap: 8px; margin-bottom: 8px; align-items: center; }
.dir { margin-left: 12px; }
</style>
