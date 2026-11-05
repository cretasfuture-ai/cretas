<script setup lang="ts">
import { onMounted, ref, watch } from 'vue';
import UApp from '@nuxt/ui/components/App.vue';
import UButton from '@nuxt/ui/components/Button.vue';
import UBadge from '@nuxt/ui/components/Badge.vue';
import UCard from '@nuxt/ui/components/Card.vue';
import { defineAsyncComponent } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import BlacklakeAnalysis from './BlacklakePrototypeAnalysis.vue';
import { tokenActivationDelayMs } from './blacklakeSession';

const props = withDefaults(defineProps<{ mode?: 'blacklake' | 'shared-alerts' }>(), { mode: 'blacklake' });
const SharedAlertsTestView = defineAsyncComponent(() => import('./SharedAlertsTestView.vue'));
const CompleteCostAnalysis = defineAsyncComponent(() => import('./BlacklakeCompleteCostAnalysis.vue'));
const sessionKey = props.mode === 'shared-alerts' ? 'smartbi_shared_test_signed_in' : 'blacklake_test_signed_in';
const auth = useAuthStore();
const publicPreview = import.meta.env.MODE === 'blacklake-public';
const signedIn = ref(props.mode!=='blacklake' && !!localStorage.getItem(sessionKey));
const username = ref('');
const password = ref('');
const busy = ref(false);
const error = ref('');
const activeView=ref('complete-cost');
const collapsed=ref(localStorage.getItem('blacklake-nav-collapsed')==='true');
const mobileOpen=ref(false);
const navigation=[
  {id:'complete-cost',label:'产品成本分析',icon:'i-lucide-clipboard-check'},
  {id:'cost',label:'周期成本分析',icon:'i-lucide-chart-no-axes-combined'},
  {id:'alerts',label:'异常与跟进',icon:'i-lucide-circle-alert'},
  {id:'gaps',label:'关联与估值补查',icon:'i-lucide-git-pull-request-arrow'},
  {id:'production',label:'生产与工序',icon:'i-lucide-factory'},
  {id:'inventory',label:'库存与仓库',icon:'i-lucide-boxes'},
  {id:'coverage',label:'数据覆盖与口径',icon:'i-lucide-file-check-2'},
];
watch(collapsed,v=>localStorage.setItem('blacklake-nav-collapsed',String(v)));
function toggleNavigation(){
  if(window.matchMedia('(max-width: 760px)').matches)mobileOpen.value=!mobileOpen.value;
  else collapsed.value=!collapsed.value;
}
function navigate(id:string){activeView.value=id;mobileOpen.value=false;window.scrollTo({top:0});}
async function login() {
  error.value = ''; busy.value = true;
  try {
    if(publicPreview && props.mode==='blacklake'){
      const response=await fetch(`${import.meta.env.BASE_URL}__blacklake/preview-session`,{method:'POST'});
      const result=await response.json();
      if(!response.ok||result.success!==true||result.data?.factoryId!=='BLACKLAKE_POC_TEST'||result.data?.previewOnly!==true)throw new Error(result.message||'预览暂不可用');
      localStorage.removeItem('cretas_access_token');
      auth.setUser({id:-1,username:'公开原型查看者',email:'',isActive:true,createdAt:new Date().toISOString(),updatedAt:new Date().toISOString(),userType:'factory',factoryUser:{factoryId:'BLACKLAKE_POC_TEST',role:'viewer',permissions:[]}});
      signedIn.value=true;return;
    }
    const response = props.mode==='blacklake'
      ? await fetch('/__blacklake/local-session',{method:'POST',headers:{'X-Cretas-Local-Preview':'1'}})
      : await fetch('/api/mobile/auth/unified-login', { method: 'POST', credentials: 'include',
        headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username: username.value, password: password.value }) });
    const result = await response.json();
    if(!response.ok || !result.success)throw new Error(result.message||'测试会话暂不可用，请稍后重新连接');
    if (!response.ok || !result.success || result.data?.factoryId !== 'BLACKLAKE_POC_TEST' || result.data?.role !== 'factory_super_admin') {
      throw new Error('请使用六膳门独立测试分析账号登录');
    }
    const token = result.data.accessToken || result.data.token;
    if (!token) throw new Error('登录未返回有效会话');
    const activationDelay = tokenActivationDelayMs(token);
    if (activationDelay) await new Promise(resolve => setTimeout(resolve, activationDelay));
    localStorage.setItem('cretas_access_token', token);
    auth.setUser({
      id: Number(result.data.userId), username: result.data.username || username.value,
      email: '', isActive: true, createdAt: new Date().toISOString(), updatedAt: new Date().toISOString(), userType: 'factory',
      factoryUser: { factoryId: result.data.factoryId, role: 'factory_super_admin', permissions: Array.isArray(result.data.permissions) ? result.data.permissions : [] },
    });
    localStorage.setItem(sessionKey, 'true');
    password.value = ''; signedIn.value = true;
  } catch (e) { error.value = e instanceof Error ? e.message : '登录失败'; }
  finally { busy.value = false; }
}
onMounted(()=>{if(props.mode==='blacklake')void login();});
function logout() {
  auth.clearAuth();
  localStorage.removeItem('cretas_access_token'); localStorage.removeItem(sessionKey);
  signedIn.value = false;
}
</script>

<template>
  <UApp>
  <div class="test-shell" :class="{'with-navigation':signedIn && mode==='blacklake','nav-collapsed':collapsed,'mobile-open':mobileOpen}" data-prototype="samples-v5">
    <template v-if="signedIn && mode==='blacklake'">
      <button v-if="mobileOpen" class="nav-backdrop" aria-label="关闭导航" @click="mobileOpen=false" />
      <aside class="prototype-sidebar" aria-label="SmartBI 导航">
        <div class="brand"><span class="brand-symbol">C</span><span class="nav-label">Cretas<small>工厂经营工作台</small></span></div>
        <div class="workspace"><span class="workspace-symbol">六</span><span class="nav-label"><strong>六膳门</strong><small>只读生产数据分析</small></span></div>
        <p class="nav-group nav-label">SMARTBI / 生产经营</p>
        <nav><UButton v-for="item in navigation" :key="item.id" :icon="item.icon" color="neutral" variant="ghost" :class="['nav-link',{active:activeView===item.id}]" :aria-label="item.label" :aria-current="activeView===item.id?'page':undefined" @click="navigate(item.id)"><span class="nav-label">{{ item.label }}</span></UButton></nav>
        <div class="sidebar-footer"><UBadge color="primary" variant="soft">测试</UBadge><span class="nav-label">数据与生产系统隔离</span></div>
      </aside>
    </template>
    <div class="prototype-main">
    <header class="topbar">
      <div class="header-start"><UButton v-if="signedIn && mode==='blacklake'" :icon="collapsed?'i-lucide-panel-left-open':'i-lucide-panel-left-close'" color="neutral" variant="ghost" :aria-label="collapsed?'展开导航':'收起导航'" @click="toggleNavigation" /><span v-if="signedIn && mode==='blacklake'" class="breadcrumb">SmartBI <span>/</span> {{ navigation.find(n=>n.id===activeView)?.label }}</span><strong v-else>Cretas <span>SmartBI</span></strong></div>
      <div class="header-end"><UBadge color="primary" variant="soft">{{ mode === 'shared-alerts' ? '合成数据测试' : '六膳门 · 隔离测试' }}</UBadge><UButton v-if="signedIn" @click="mode==='blacklake'?login():logout()" color="neutral" variant="ghost" size="sm">{{ mode==='blacklake'?'重新连接':'退出分析' }}</UButton></div>
    </header>
    <SharedAlertsTestView v-if="signedIn && mode === 'shared-alerts'" />
    <CompleteCostAnalysis v-else-if="signedIn && activeView==='complete-cost'" />
    <BlacklakeAnalysis v-else-if="signedIn" v-model:active-view="activeView" />
    <UCard v-else-if="mode==='blacklake'" class="login-card">
      <h2>{{ busy?'正在连接六膳门分析…':'连接六膳门分析' }}</h2>
      <p>{{ publicPreview?'正在打开只读原型，无需账号密码。':'使用本机测试会话自动进入。' }}</p>
      <el-alert v-if="error" type="error" :closable="false" :title="error" />
      <UButton v-if="!busy" @click="login">重新连接</UButton>
    </UCard>
    <UCard v-else class="login-card">
      <h1>{{ mode === 'shared-alerts' ? 'SmartBI 多来源异常分析' : '六膳门生产成本分析' }}</h1><p>使用 Cretas 独立测试账号登录。此处不使用黑湖账号。</p>
      <el-alert v-if="error" type="error" :closable="false" :title="error" />
      <el-form @submit.prevent="login" label-position="top">
        <el-form-item label="测试账号"><el-input v-model="username" placeholder="请输入测试账号" autocomplete="username" /></el-form-item>
        <el-form-item label="密码"><el-input v-model="password" placeholder="请输入密码" type="password" autocomplete="current-password" show-password @keyup.enter="login" /></el-form-item>
        <UButton color="primary" :loading="busy" @click="login">登录分析</UButton>
      </el-form>
    </UCard>
    </div>
  </div>
  </UApp>
</template>

<style scoped>
.test-shell { min-height:100vh;background:var(--c-page);color:var(--c-ink);font:14px/1.6 var(--font-sans); }
.topbar { height:61px;display:flex;justify-content:space-between;align-items:center;padding:0 28px;background:var(--c-surface);border-bottom:1px solid var(--c-line);gap:12px; }
.topbar strong { font-size: 20px; white-space: nowrap; }.topbar span { color: #1b65a8; margin-left: 8px; }.topbar div { font-size: 12px; }
.login-card { max-width: 440px; margin: 80px auto; border-radius: 10px; padding: 16px; } h1 { font-size: 24px; } p { color: #7a8599; line-height: 1.7; } .el-form { margin-top: 24px; }
.header-start,.header-end{display:flex;align-items:center;gap:12px;min-width:0}.breadcrumb{font-size:12px;color:var(--c-text)}.breadcrumb>span{padding:0 8px;color:var(--c-muted)}
.prototype-sidebar{position:fixed;inset:0 auto 0 0;z-index:30;width:232px;display:flex;flex-direction:column;padding:0 13px;background:var(--c-surface);border-right:1px solid var(--c-line);overflow:hidden;transition:width 220ms ease,transform 220ms ease}
.with-navigation .prototype-main{margin-left:232px;min-width:0;transition:margin-left 220ms ease}
.brand{height:82px;display:flex;align-items:center;gap:10px;padding-left:11px;font-size:21px;font-weight:750;white-space:nowrap}.brand small{display:block;font-size:10px;font-weight:400;color:var(--c-text)}
.brand-symbol{display:grid;place-items:center;width:35px;height:37px;flex:none;color:var(--c-primary);font-size:28px}.workspace{display:flex;align-items:center;gap:9px;padding:10px 8px;border:1px solid var(--c-line);border-radius:8px;background:#fafbfd;margin-bottom:20px}.workspace-symbol{display:grid;place-items:center;width:30px;height:32px;flex:none;border:1px solid var(--c-line);border-radius:6px;color:var(--c-primary);background:white}.workspace strong{display:block;font-size:12px}.workspace small{display:block;font-size:11px;color:var(--c-text)}.nav-group{padding:8px 13px;color:var(--c-text);font-size:10px}
.nav-link{width:100%;min-height:43px;justify-content:flex-start;gap:11px;border-radius:7px;color:var(--c-text);padding:10px 13px;margin-bottom:3px;white-space:nowrap;font-size:13px}.nav-link.active{color:var(--c-primary);background:var(--c-primary-soft);font-weight:600}.sidebar-footer{margin-top:auto;display:flex;align-items:center;gap:8px;border-top:1px solid var(--c-line);padding:18px 6px;font-size:10px;color:var(--c-text)}
.nav-label{display:inline-block;min-width:0;max-width:180px;opacity:1;overflow:hidden;transition:opacity 140ms ease,max-width 220ms ease}.nav-collapsed .prototype-sidebar{width:68px;padding:0 7px}.nav-collapsed .prototype-main{margin-left:68px}.nav-collapsed .nav-label{max-width:0;opacity:0;pointer-events:none}.nav-collapsed .workspace{padding:10px 10px}.nav-collapsed .brand{padding-left:8px}.nav-collapsed .nav-link{padding-inline:14px}.nav-backdrop{display:none}
:global(body) { margin: 0; }
@media(max-width:760px){.with-navigation .prototype-main{margin-left:0}.prototype-sidebar,.nav-collapsed .prototype-sidebar{width:232px;padding:0 13px;transform:translateX(-100%)}.mobile-open .prototype-sidebar{transform:translateX(0)}.mobile-open .nav-label{max-width:180px;opacity:1;pointer-events:auto}.mobile-open .nav-backdrop{display:block;position:fixed;inset:0;z-index:25;background:rgb(26 41 61 / 25%);border:0}.breadcrumb{display:none}.topbar{padding:0 12px;gap:6px}.header-end{gap:4px}.login-card{margin:32px 16px}.header-start{gap:4px}}
</style>
