<script setup lang="ts">
import { computed } from 'vue';

const props = withDefaults(defineProps<{
  enabled?: boolean;
  role: 'sales' | 'purchase' | 'inventory';
  busy: boolean;
  error: string;
  factoryId: string;
  factoryName?: string;
  canWrite?: boolean;
  paths?: readonly string[];
}>(), { enabled: false, paths: () => [] });

const roles = {
  sales: { title: '销售数字员工工作台', name: '数字文员', avatar: 'clerk',
    description: '跟进客户、核对订单，把销售事项接回原来的业务流程。',
    links: [['客户订单 · AI 录入', '/sales/orders?assistant=order-entry'], ['销售订单', '/sales/orders'], ['客户管理', '/sales/customers']],
    scope: '客户跟进与订单查询；AI 录入在订单页核对后保存。' },
  purchase: { title: '采购数字员工工作台', name: '数字文员', avatar: 'clerk',
    description: '核对低库存与供应商，继续使用现有采购流程。',
    links: [['采购订单 · AI 录入', '/procurement/orders?assistant=order-entry'], ['采购订单', '/procurement/orders'], ['供应商管理', '/procurement/suppliers']],
    scope: '低库存查询与采购订单录入；采购数量与供应商由你核对后确认。' },
  inventory: { title: '库存数字员工工作台', name: '数字仓管', avatar: 'keeper',
    description: '核对今天的待收货物与库存，再进入原收货流程办理。',
    links: [['入库任务与批次', '/warehouse/materials'], ['库存查询', '/warehouse/inventory-query'], ['出货管理', '/warehouse/shipments']],
    scope: '待收清单、库存核对与临期建议；实际收货仍需核对并确认。' },
};
const employee = computed(() => roles[props.role]);
const links = computed(() => employee.value.links.filter(([, to]) => props.paths.includes(to.split('?')[0])
  && (!to.includes('assistant=order-entry') || props.canWrite)));
const state = computed(() => props.busy ? 'working' : props.error ? 'alert' : 'idle');
const stateLabel = computed(() => props.busy ? '正在核对' : props.error ? '需要核实' : '等待交办');
</script>

<template>
  <div v-if="enabled" class="digital-workdesk">
    <header class="employee-header">
      <div class="crew-avatar" :data-state="state" role="img" :aria-label="`${employee.name} · ${stateLabel}`">
        <UAvatar class="employee-avatar" :src="`/digital-employees/${employee.avatar}.png`" :alt="`${employee.name} · ${stateLabel}`" size="xl" />
        <UChip class="employee-state-dot" standalone position="bottom-right" size="sm" :color="state === 'working' ? 'info' : state === 'alert' ? 'error' : 'neutral'" aria-hidden="true" />
      </div>
      <div class="employee-heading">
        <p class="employee-name">{{ employee.name }} · {{ role === 'sales' ? '销售' : role === 'purchase' ? '采购' : '库存' }}</p>
        <h1>{{ employee.title }}</h1>
        <p>{{ employee.description }}</p>
      </div>
      <UBadge class="employee-status" :color="state === 'working' ? 'info' : state === 'alert' ? 'error' : 'neutral'" variant="soft" size="md" role="status">{{ stateLabel }}</UBadge>
    </header>
    <div class="employee-layout">
      <div class="employee-content"><slot /></div>
      <aside class="employee-aside" aria-label="岗位能力与业务入口">
        <UCard>
          <template #header><h2>能做什么</h2></template>
          <p>{{ employee.scope }}</p>
          <p v-if="role !== 'inventory' && !canWrite">你目前只能查看，AI 录入需要对应岗位的写入权限。</p>
          <nav aria-label="现有业务入口">
            <RouterLink v-for="[label, to] in links" :key="to" :to="to">
              <UButton as="span" color="primary" variant="soft" block class="business-link">
                {{ label }} <span aria-hidden="true">→</span>
              </UButton>
            </RouterLink>
          </nav>
          <p v-if="!links.length">当前没有可访问的业务入口，请联系管理员核对模块权限。</p>
        </UCard>
        <UCard>
          <template #header><h2>本次工作范围</h2></template>
          <p>当前企业：{{ factoryName || factoryId }}</p>
          <p>只办已接入的任务。查询结果和操作凭证在左侧显示；提交沿用原有业务确认。</p>
          <p>自动分单、直送执行尚未接入。</p>
        </UCard>
      </aside>
    </div>
  </div>
  <slot v-else />
</template>

<style scoped>
.digital-workdesk { color: var(--cs-text); }
.employee-header { display:flex; align-items:center; gap:var(--cs-space-4); margin-bottom:var(--cs-space-6); flex-wrap:wrap; }
.crew-avatar { width:64px; height:64px; position:relative; flex:none; }
.employee-avatar { width:64px; height:64px; }
.employee-state-dot { position:absolute; right:1px; bottom:1px; border:2px solid var(--cs-surface); }
.crew-avatar[data-state='alert'] .employee-state-dot { border-radius:0; clip-path:polygon(50% 0, 100% 100%, 0 100%); }
.employee-heading { flex:1; min-width:200px; }
h1 { font-size:var(--cs-type-title); font-weight:650; margin:var(--cs-space-1) 0; }
p { margin:0; color:var(--cs-muted); line-height:1.65; }
.employee-name, .employee-status { font-size:13px; }
.employee-layout { display:grid; grid-template-columns:minmax(0,1fr) 300px; gap:var(--cs-space-5); align-items:start; }
.employee-content { min-width:0; display:flex; flex-direction:column; gap:var(--cs-space-4); }
.employee-aside { display:grid; gap:var(--cs-space-4); }
h2 { font-size:var(--cs-type-section); font-weight:600; margin:0 0 var(--cs-space-3); }
.employee-aside p + p { margin-top:var(--cs-space-3); }
nav { display:grid; gap:var(--cs-space-2); margin-top:var(--cs-space-4); }
nav a { display:block; text-decoration:none; min-width:0; }
nav a :deep(.business-link) { justify-content:space-between; min-height:40px; }
nav a:focus-visible { outline:2px solid var(--cs-primary); outline-offset:2px; }
.employee-content :deep(.chat-card) { border-radius:var(--cs-radius-lg); }
.employee-content :deep(.chat-input textarea), .employee-content :deep(.formatted-output) { font:16px/1.6 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,"PingFang SC","Hiragino Sans GB","Microsoft YaHei",sans-serif; letter-spacing:0; }
.employee-content :deep(.chat-input button) { min-height:44px; }
@media (min-width:1440px) { .employee-layout { grid-template-columns:minmax(0,1fr) 360px; } }
@media (max-width:1100px) { .employee-layout { grid-template-columns:minmax(0,1fr); } .employee-aside { grid-template-columns:repeat(2,minmax(0,1fr)); } }
@media (max-width:600px) { .employee-aside { grid-template-columns:minmax(0,1fr); } .employee-content :deep(.chat-input) { flex-wrap:wrap; } .employee-content :deep(.card-header) { flex-wrap:wrap; gap:8px; } }
</style>
