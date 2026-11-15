<script setup lang="ts">
import { ref, reactive } from 'vue';
import { useRouter, useRoute } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { ElMessage } from 'element-plus';

const router = useRouter();
const route = useRoute();
const authStore = useAuthStore();

const formRef = ref();
const showPassword = ref(false);
const forgotOpen = ref(false);
const loading = ref(false);

const loginForm = reactive({
  username: '',
  password: ''
});

// 校验规则与旧 el-form 一致：用户名必填；密码必填且至少 6 位（提交时校验）
function validate(state: { username: string; password: string }) {
  const errors: { name: string; message: string }[] = [];
  if (!state.username) errors.push({ name: 'username', message: '请输入手机号或用户名' });
  if (!state.password) errors.push({ name: 'password', message: '请输入密码' });
  else if (state.password.length < 6) errors.push({ name: 'password', message: '密码长度至少6位' });
  return errors;
}

async function handleLogin() {
  const form = formRef.value;
  if (!form) return;

  try {
    await form.validate();
  } catch {
    return;
  }

  loading.value = true;
  try {
    const success = await authStore.login(loginForm.username, loginForm.password);

    if (success) {
      ElMessage.success('登录成功');

      // 跳转到原页面或首页
      const redirect = route.query.redirect as string;
      const platformRedirectAllowed = !authStore.isPlatform || ['/platform/factories', '/system/llm-usage']
        .some((path) => redirect === path || redirect?.startsWith(`${path}/`));
      router.push(authStore.isPlatform && !platformRedirectAllowed
        ? '/platform/factories'
        : (redirect || (authStore.isPlatform ? '/platform/factories' : '/dashboard')));
    }
    // R18-ext fix #280: do not show a hardcoded "用户名或密码错误" on failure.
    // The response interceptor already surfaces the server's actual message
    // (e.g. "用户账号已被禁用", "用户名或密码错误") from data.message. A second
    // toast here just stacked misleading info on the screen.
  } catch (error) {
    console.error('Login error:', error);
    // Interceptor shows server message for API errors; only fallback for
    // thrown non-ApiError cases (network, unexpected JS).
  } finally {
    loading.value = false;
  }
}
</script>

<template>
  <div class="login-page">
    <div class="login-container">
      <!-- Logo 区域：与外壳侧栏同一份品牌资源 -->
      <div class="login-header">
        <img :src="authStore.factoryLogoUrl" :alt="authStore.brandTitle" class="login-logo" />
        <h1 class="login-title">白垩纪AI Agent</h1>
        <p class="login-subtitle">企业级智能管理平台</p>
      </div>

      <!-- 登录表单 -->
      <UForm
        ref="formRef"
        :state="loginForm"
        :validate="validate"
        :validate-on="['blur']"
        class="login-form"
        @keyup.enter="handleLogin"
        @submit.prevent
      >
        <UFormField name="username">
          <UInput
            v-model="loginForm.username"
            placeholder="请输入手机号或用户名"
            size="xl"
            icon="i-lucide-user"
            class="w-full"
            autocomplete="username"
          />
        </UFormField>

        <UFormField name="password">
          <UInput
            v-model="loginForm.password"
            :type="showPassword ? 'text' : 'password'"
            placeholder="请输入密码"
            size="xl"
            icon="i-lucide-lock"
            class="w-full"
            autocomplete="current-password"
            :ui="{ trailing: 'pe-1' }"
          >
            <template #trailing>
              <UButton
                color="neutral"
                variant="link"
                size="sm"
                :icon="showPassword ? 'i-lucide-eye-off' : 'i-lucide-eye'"
                :aria-label="showPassword ? '隐藏密码' : '显示密码'"
                @click="showPassword = !showPassword"
              />
            </template>
          </UInput>
        </UFormField>

        <p class="login-hint">登录状态将保留，可随时退出登录</p>

        <UButton
          block
          size="xl"
          :loading="loading"
          class="login-button"
          @click="handleLogin"
        >
          {{ loading ? '登录中...' : '登 录' }}
        </UButton>

        <!-- UX P2-4: 忘记密码提示 — prod 无自助 reset, 引导联系管理员 -->
        <div class="forgot-tip">
          <UPopover v-model:open="forgotOpen">
            <a class="forgot-link" role="button" tabindex="0" @keyup.enter.stop="forgotOpen = !forgotOpen">忘记密码?</a>
            <template #content>
              <div class="forgot-pop">
                <p class="forgot-pop-title">请联系您的工厂管理员</p>
                <p class="forgot-pop-text">
                  由于账号权限与工厂绑定, 密码重置需通过管理员在"人事管理 → 账号管理"中操作.
                  如您就是管理员, 请联系平台 superadmin.
                </p>
              </div>
            </template>
          </UPopover>
        </div>
      </UForm>

      <!-- 底部信息 -->
      <div class="login-footer">
        <p>© 2026 白垩纪科技. All rights reserved.</p>
      </div>
    </div>
  </div>
</template>

<style lang="scss" scoped>
.login-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--cs-bg);
  padding: var(--cs-space-5);
  box-sizing: border-box;
}

.login-container {
  width: 100%;
  max-width: 400px;
  background: var(--cs-surface);
  border: 1px solid var(--cs-border);
  border-radius: var(--cs-radius-lg);
  padding: 40px 36px 28px;
  box-shadow: var(--cs-shadow);
  box-sizing: border-box;
}

.login-header {
  text-align: center;
  margin-bottom: 28px;

  .login-logo {
    width: 48px;
    height: 48px;
    object-fit: contain;
    border-radius: 12px;
    margin-bottom: var(--cs-space-4);
  }

  .login-title {
    font-size: var(--cs-type-title);
    font-weight: 700;
    color: var(--cs-text);
    margin: 0 0 var(--cs-space-1);
  }

  .login-subtitle {
    font-size: var(--cs-font);
    color: var(--cs-muted);
    margin: 0;
  }
}

.login-form {
  display: flex;
  flex-direction: column;
  gap: var(--cs-space-4);
}

.login-hint {
  margin: 0;
  font-size: var(--cs-font-small);
  color: var(--cs-muted);
  text-align: center;
}

.login-button {
  font-weight: 600;
  letter-spacing: 2px;
}

.forgot-tip {
  text-align: center;
}

.forgot-link {
  color: var(--cs-muted);
  font-size: var(--cs-font-small);
  cursor: pointer;

  &:hover {
    color: var(--cs-primary);
    text-decoration: underline;
  }
}

.forgot-pop {
  width: 280px;
  max-width: calc(100vw - 48px);
  padding: var(--cs-space-3);
}

.forgot-pop-title {
  margin: 0 0 var(--cs-space-2);
  font-weight: 500;
  color: var(--cs-text);
}

.forgot-pop-text {
  margin: 0;
  color: var(--cs-muted);
  font-size: var(--cs-font-small);
  line-height: 1.6;
}

.login-footer {
  margin-top: var(--cs-space-6);
  text-align: center;

  p {
    font-size: var(--cs-font-small);
    color: var(--cs-muted);
    margin: 0;
  }
}

@media (max-width: 480px) {
  .login-page {
    padding: var(--cs-space-4);
  }
  .login-container {
    padding: 28px 20px 20px;
  }
}
</style>
