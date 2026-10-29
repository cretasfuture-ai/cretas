<script setup lang="ts">
/** S6-02 本厂可复用 Skill：工厂管理员试运行已注册的 Skill、保存成功的运行、用新参数复跑。 */
import { ref, computed, onMounted } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { handleCatchError } from '@/utils/errorToast';
import { listSkills, type SkillInfo } from '@/api/governance';
import ReusableSkillsPanel from './ReusableSkillsPanel.vue';

const authStore = useAuthStore();
const factoryId = computed(() => authStore.factoryId);
const skills = ref<SkillInfo[]>([]);
const loading = ref(false);

async function loadSkills() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const res = await listSkills(factoryId.value);
    skills.value = res.data ?? [];
  } catch (error: unknown) {
    handleCatchError(error, '加载 Skill 列表失败');
  } finally {
    loading.value = false;
  }
}

onMounted(loadSkills);
</script>

<template>
  <div v-loading="loading" class="page-container">
    <ReusableSkillsPanel :skills="skills" />
  </div>
</template>
