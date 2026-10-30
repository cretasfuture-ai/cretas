<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { useRouter } from 'vue-router';
import { menuConfig, type MenuItem } from '@/components/layout/menuConfig';
import { getFactoryModuleMenuOptions } from '@/components/layout/factoryMenuVisibility';
import {
  getFactoryMenuConfig,
  listPlatformFactories,
  updateFactoryMenuConfig,
  type FactoryMenuConfig,
  type FactoryMenuModule,
  type PlatformFactory,
} from '@/api/platformFactoryMenus';
import { usePlatformFactoryContextStore } from '@/store/modules/platformFactoryContext';

const router = useRouter();
const factoryView = usePlatformFactoryContextStore();
const factories = ref<PlatformFactory[]>([]);
const selectedFactoryId = ref('');
const menuConfigState = ref<FactoryMenuConfig | null>(null);
const loadingFactories = ref(false);
const loadingConfig = ref(false);
const savingModule = ref<string | null>(null);
let menuLoadSequence = 0;
const search = ref('');
const errorMessage = ref('');

const filteredFactories = computed(() => {
  const term = search.value.trim().toLowerCase();
  if (!term) return factories.value;
  return factories.value.filter((factory) => `${factory.id} ${factory.name}`.toLowerCase().includes(term));
});

const menuModules = computed(() => menuConfigState.value?.modules || []);

function menuOptions(moduleId: string) {
  // The legacy SmartBI module id is still returned by older feature rows;
  // its web permission/menu id is analytics and must reuse that tree.
  return getFactoryModuleMenuOptions(menuConfig as readonly MenuItem[], moduleId === 'smartbi' ? 'analytics' : moduleId);
}

async function loadFactories(): Promise<void> {
  loadingFactories.value = true;
  errorMessage.value = '';
  try {
    factories.value = await listPlatformFactories();
    if (!selectedFactoryId.value && factories.value[0]) selectedFactoryId.value = factories.value[0].id;
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '工厂列表加载失败';
  } finally {
    loadingFactories.value = false;
  }
}

async function loadMenuConfig(factoryId: string): Promise<void> {
  const sequence = ++menuLoadSequence;
  if (!factoryId) {
    menuConfigState.value = null;
    return;
  }
  loadingConfig.value = true;
  errorMessage.value = '';
  menuConfigState.value = null;
  try {
    const response = await getFactoryMenuConfig(factoryId);
    if (!response.success || !response.data) throw new Error(response.message || '菜单配置加载失败');
    if (response.data.factoryId !== factoryId) throw new Error('工厂菜单配置范围不匹配，已停止加载');
    if (sequence !== menuLoadSequence || selectedFactoryId.value !== factoryId) return;
    menuConfigState.value = {
      ...response.data,
      modules: response.data.modules.map((module) => ({
        ...module,
        disabledMenuPaths: Array.isArray(module.disabledMenuPaths) ? [...module.disabledMenuPaths] : [],
      })),
    };
  } catch (error) {
    if (sequence !== menuLoadSequence || selectedFactoryId.value !== factoryId) return;
    errorMessage.value = error instanceof Error ? error.message : '菜单配置加载失败';
  } finally {
    if (sequence === menuLoadSequence) loadingConfig.value = false;
  }
}

watch(selectedFactoryId, (factoryId) => { void loadMenuConfig(factoryId); });

async function saveModule(module: FactoryMenuModule): Promise<void> {
  if (!selectedFactoryId.value || savingModule.value || loadingConfig.value
    || menuConfigState.value?.factoryId !== selectedFactoryId.value) return;
  const targetFactoryId = selectedFactoryId.value;
  savingModule.value = module.moduleId;
  const snapshot = {
    visible: module.visible,
    disabledMenuPaths: [...module.disabledMenuPaths],
  };
  try {
    const response = await updateFactoryMenuConfig(targetFactoryId, module.moduleId, snapshot);
    if (!response.success) throw new Error(response.message || '菜单配置保存失败');
    if (selectedFactoryId.value === targetFactoryId) await loadMenuConfig(targetFactoryId);
    ElMessage.success('菜单显示配置已保存并回读');
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '菜单配置保存失败');
    if (selectedFactoryId.value === targetFactoryId) await loadMenuConfig(targetFactoryId);
  } finally {
    savingModule.value = null;
  }
}

async function enterReadOnly(factory: PlatformFactory): Promise<void> {
  try {
    await factoryView.enterFactoryView(factory.id);
    await router.push('/dashboard');
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '无法进入工厂只读查看');
  }
}

function returnToPlatform(): void {
  factoryView.leaveFactoryView();
  void router.push('/platform/factories');
}

onMounted(() => { void loadFactories(); });
</script>

<template>
  <div class="platform-factories page-container">
    <div class="page-header">
      <div>
        <h2>工厂管理</h2>
        <p>平台日常工作区。这里仅控制网页菜单显示，不会开通业务模块或改变接口权限。</p>
      </div>
      <el-button v-if="factoryView.isViewingFactory" type="primary" plain @click="returnToPlatform">
        返回平台
      </el-button>
    </div>

    <el-alert v-if="errorMessage" :title="errorMessage" type="error" show-icon :closable="false" class="error-alert" />

    <div class="factory-console">
      <el-card shadow="never" class="factory-list-card">
        <template #header>
          <div class="card-title"><span>工厂列表</span><el-tag type="info">{{ factories.length }}</el-tag></div>
        </template>
        <el-input v-model="search" clearable placeholder="按工厂名称或 ID 搜索" class="factory-search" />
        <el-skeleton v-if="loadingFactories" :rows="5" animated />
        <el-empty v-else-if="!filteredFactories.length" description="没有可用工厂" />
        <div v-else class="factory-list">
          <button
            v-for="factory in filteredFactories"
            :key="factory.id"
            class="factory-row"
            :class="{ active: selectedFactoryId === factory.id }"
            :aria-disabled="savingModule ? 'true' : undefined"
            type="button"
            @click="!savingModule && (selectedFactoryId = factory.id)"
          >
            <span><strong>{{ factory.name }}</strong><small>{{ factory.id }}</small></span>
            <el-tag v-if="factory.isActive === false" type="info" size="small">停用</el-tag>
          </button>
        </div>
      </el-card>

      <el-card shadow="never" class="config-card">
        <template #header>
          <div class="card-title">
            <span>{{ menuConfigState?.factoryName || '选择工厂后配置菜单显示' }}</span>
            <el-tag v-if="menuConfigState" type="warning">菜单显示配置</el-tag>
          </div>
        </template>
        <el-skeleton v-if="loadingConfig" :rows="8" animated />
        <el-empty v-else-if="!menuConfigState" description="请选择左侧工厂" />
        <div v-else class="module-list">
          <section v-for="module in menuModules" :key="module.moduleId" class="module-card">
            <div class="module-heading">
              <div>
                <h3>{{ module.moduleName }}</h3>
                <p>{{ module.configured ? '已配置显示规则' : '沿用当前显示逻辑，尚未单独配置' }}</p>
              </div>
              <el-switch v-model="module.visible" :disabled="!!savingModule" :loading="savingModule === module.moduleId" @change="saveModule(module)" />
            </div>
            <p class="module-note">关闭只隐藏网页入口，不修改模块开通、发布版本或接口权限。</p>
            <div v-if="menuOptions(module.moduleId).length" class="menu-options">
              <span class="options-label">隐藏网页子菜单</span>
              <el-checkbox-group v-model="module.disabledMenuPaths">
                <el-checkbox v-for="option in menuOptions(module.moduleId)" :key="option.path" :disabled="!!savingModule" :value="option.path">
                  {{ option.label }}
                </el-checkbox>
              </el-checkbox-group>
              <el-button size="small" type="primary" plain :disabled="!!savingModule" :loading="savingModule === module.moduleId" @click="saveModule(module)">
                保存本组
              </el-button>
            </div>
            <span v-else class="no-options">当前大菜单没有可配置子菜单</span>
          </section>
        </div>
      </el-card>
    </div>

    <el-card shadow="never" class="read-only-card">
      <div class="read-only-copy"><strong>进入指定工厂</strong><span>查看所选工厂的业务信息。当前为只读模式，可随时返回平台。</span></div>
      <el-button v-if="selectedFactoryId" type="primary" @click="enterReadOnly(factories.find(f => f.id === selectedFactoryId)!)">进入只读查看</el-button>
    </el-card>
  </div>
</template>

<style lang="scss" scoped>
.page-container { padding: 20px; }
.page-header { display: flex; align-items: flex-start; justify-content: space-between; margin-bottom: 16px; }
h2 { margin: 0 0 6px; color: #1A2332; }
.page-header p { margin: 0; color: #7A8599; }
.error-alert { margin-bottom: 16px; }
.factory-console { display: grid; grid-template-columns: 280px minmax(0, 1fr); gap: 16px; align-items: start; }
.factory-list-card, .config-card, .read-only-card { border-radius: 10px; }
.card-title { display: flex; align-items: center; justify-content: space-between; gap: 8px; font-weight: 600; }
.factory-search { margin-bottom: 12px; }
.factory-list { display: flex; flex-direction: column; gap: 4px; }
.factory-row { display: flex; align-items: center; justify-content: space-between; width: 100%; border: 0; border-radius: 8px; padding: 10px; background: transparent; color: #1A2332; text-align: left; cursor: pointer; }
.factory-row:hover, .factory-row.active { background: #edf5fc; color: #1B65A8; }
.factory-row span { display: flex; flex-direction: column; gap: 3px; }
.factory-row small { color: #7A8599; font-size: 12px; }
.module-list { display: grid; grid-template-columns: repeat(auto-fit, minmax(320px, 1fr)); gap: 12px; }
.module-card { border: 1px solid #EDF2F7; border-radius: 10px; padding: 14px; }
.module-heading { display: flex; justify-content: space-between; gap: 10px; }
.module-heading h3 { margin: 0; font-size: 16px; }
.module-heading p, .module-note { margin: 5px 0 0; color: #7A8599; font-size: 12px; }
.module-note { padding-bottom: 10px; border-bottom: 1px solid #EDF2F7; }
.menu-options { display: flex; flex-direction: column; gap: 8px; margin-top: 10px; }
.options-label { font-size: 13px; font-weight: 600; }
.el-checkbox { display: block; margin: 0 0 6px; }
.no-options { display: block; margin-top: 12px; color: #a0a8b5; font-size: 12px; }
.read-only-card { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-top: 16px; }
.read-only-copy { display: flex; flex-direction: column; gap: 5px; }
.read-only-copy span { color: #7A8599; font-size: 13px; }
@media (max-width: 900px) { .factory-console { grid-template-columns: 1fr; } .read-only-card { align-items: flex-start; flex-direction: column; } }
</style>
