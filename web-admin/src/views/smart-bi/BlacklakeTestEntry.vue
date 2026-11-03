<script setup lang="ts">
import { ref } from 'vue';
import BlacklakeAnalysis from './BlacklakeAnalysis.vue';

const signedIn = ref(!!localStorage.getItem('blacklake_test_signed_in'));
const username = ref('');
const password = ref('');
const busy = ref(false);
const error = ref('');
async function login() {
  error.value = ''; busy.value = true;
  try {
    const response = await fetch('/api/mobile/auth/unified-login', { method: 'POST', credentials: 'include',
      headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username: username.value, password: password.value }) });
    const result = await response.json();
    if (!response.ok || !result.success || result.data?.factoryId !== 'BLACKLAKE_POC_TEST' || result.data?.role !== 'factory_super_admin') {
      throw new Error('请使用六膳门独立测试分析账号登录');
    }
    const token = result.data.accessToken || result.data.token;
    if (!token) throw new Error('登录未返回有效会话');
    localStorage.setItem('cretas_access_token', token);
    localStorage.setItem('blacklake_test_signed_in', 'true');
    password.value = ''; signedIn.value = true;
  } catch (e) { error.value = e instanceof Error ? e.message : '登录失败'; }
  finally { busy.value = false; }
}
function logout() {
  localStorage.removeItem('cretas_access_token'); localStorage.removeItem('blacklake_test_signed_in');
  signedIn.value = false;
}
</script>

<template>
  <div class="test-shell">
    <header class="topbar"><strong>Cretas <span>SmartBI</span></strong><div>六膳门 · 隔离测试环境 <el-button v-if="signedIn" @click="logout" text>退出分析</el-button></div></header>
    <BlacklakeAnalysis v-if="signedIn" />
    <el-card v-else class="login-card" shadow="never">
      <h1>六膳门生产成本分析</h1><p>使用 Cretas 独立测试账号登录。此处不使用黑湖账号。</p>
      <el-alert v-if="error" type="error" :closable="false" :title="error" />
      <el-form @submit.prevent="login" label-position="top">
        <el-form-item label="测试账号"><el-input v-model="username" placeholder="请输入测试账号" autocomplete="username" /></el-form-item>
        <el-form-item label="密码"><el-input v-model="password" placeholder="请输入密码" type="password" autocomplete="current-password" show-password @keyup.enter="login" /></el-form-item>
        <el-button type="primary" :loading="busy" @click="login">登录分析</el-button>
      </el-form>
    </el-card>
  </div>
</template>

<style scoped>
.test-shell { min-height: 100vh; background: #f4f6f9; color: #1a2332; font: 14px -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; }
.topbar { height: 64px; box-sizing: border-box; display: flex; justify-content: space-between; align-items: center; padding: 0 24px; background: white; border-bottom: 1px solid #edf2f7; gap: 16px; }
.topbar strong { font-size: 20px; white-space: nowrap; }.topbar span { color: #1b65a8; margin-left: 8px; }.topbar div { font-size: 12px; }
.login-card { max-width: 440px; margin: 80px auto; border-radius: 10px; padding: 16px; } h1 { font-size: 24px; } p { color: #7a8599; line-height: 1.7; } .el-form { margin-top: 24px; }
:global(body) { margin: 0; }
@media(max-width: 600px) { .login-card { margin: 32px 16px; }.topbar { padding: 0 16px; height: auto; min-height: 64px; flex-wrap: wrap; } }
</style>
