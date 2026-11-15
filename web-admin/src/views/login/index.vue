<script setup lang="ts">
import { ref, reactive } from 'vue';
import { useRouter, useRoute } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { ElMessage } from 'element-plus';
import heroImage from '@/assets/login/login-hero.webp';
import iconWeighing from '@/assets/login/feature-weighing.webp';
import iconTrace from '@/assets/login/feature-trace.webp';
import iconCost from '@/assets/login/feature-cost.webp';
import iconAiBi from '@/assets/login/feature-ai-bi.webp';
import iconOutsource from '@/assets/login/feature-outsource.webp';
import iconAiVision from '@/assets/login/feature-ai-vision.webp';

const router = useRouter();
const route = useRoute();
const authStore = useAuthStore();

const formRef = ref();
const showPassword = ref(false);
const forgotOpen = ref(false);

const highlights = [
  { icon: iconWeighing, title: '报工称重', desc: '现场称重直接入账，批次数据实时可查' },
  { icon: iconTrace, title: '全链溯源', desc: '从原料到成品，一键追到每个批次' },
  { icon: iconCost, title: '成本分析', desc: '物料、人工、损耗逐项归集，毛利一目了然' },
  { icon: iconAiBi, title: 'AI 问答 BI', desc: '用一句话问经营数据，直接得到图表和结论' },
  { icon: iconOutsource, title: '委外协同', desc: '发料、回收、费用对账全程在线' },
  { icon: iconAiVision, title: 'AI 视觉质检', desc: '拍照即检，异常自动留痕、自动预警' }
];
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
    <!-- 左侧品牌展示区（窄屏隐藏） -->
    <section class="brand-pane">
      <div class="brand-inner">
        <h2 class="brand-headline">让每一批货都可追溯，<br />每一分成本都算得清</h2>
        <p class="brand-sub">面向食品加工企业的一体化经营管理平台</p>
        <ul class="highlight-grid">
          <li v-for="h in highlights" :key="h.title" class="highlight">
            <span class="highlight-icon"><img :src="h.icon" alt="" class="highlight-img" /></span>
            <span class="highlight-copy">
              <span class="highlight-title">{{ h.title }}</span>
              <span class="highlight-desc">{{ h.desc }}</span>
            </span>
          </li>
        </ul>
        <div class="brand-art-slot"><img :src="heroImage" alt="" class="brand-art" /></div>
      </div>
    </section>

    <!-- 右侧登录面板 -->
    <section class="form-pane">
      <div class="form-top">
        <img :src="authStore.factoryLogoUrl" :alt="authStore.brandTitle" class="login-logo" />
        <span class="form-brand">{{ authStore.brandTitle }}</span>
      </div>

      <div class="form-main">
        <div class="form-box">
          <p class="login-subtitle">白垩纪AI Agent · 企业级智能管理平台</p>
          <h1 class="login-title">欢迎登录</h1>

          <UForm
            ref="formRef"
            :state="loginForm"
            :validate="validate"
            :validate-on="['blur']"
            class="login-form"
            @keyup.enter="handleLogin"
            @submit.prevent
          >
            <UFormField label="账号" name="username">
              <UInput
                v-model="loginForm.username"
                placeholder="请输入手机号或用户名"
                size="xl"
                icon="i-lucide-user"
                class="w-full"
                autocomplete="username"
              />
            </UFormField>

            <UFormField label="密码" name="password">
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

            <!-- 登录状态始终保留（语义不变），与忘记密码同一行分居两端 -->
            <div class="login-row">
              <span class="login-hint">登录状态将保留，可随时退出登录</span>
              <!-- UX P2-4: 忘记密码提示 — prod 无自助 reset, 引导联系管理员 -->
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

            <UButton
              block
              size="xl"
              :loading="loading"
              class="login-button"
              @click="handleLogin"
            >
              {{ loading ? '登录中...' : '登 录' }}
            </UButton>
          </UForm>
        </div>
      </div>

      <div class="login-footer">
        <p>© 2026 白垩纪科技. All rights reserved.</p>
      </div>
    </section>
  </div>
</template>

<style lang="scss" scoped>
.login-page {
  min-height: 100vh;
  display: flex;
  background: var(--cs-bg);
}

/* ---- 左侧品牌区 ---- */
.brand-pane {
  flex: 0 0 58%;
  display: flex;
  justify-content: center;
  height: 100vh;
  padding: 40px 48px 0;
  box-sizing: border-box;
  overflow: hidden;
  background: var(--cs-bg);
}

.brand-inner {
  width: 100%;
  max-width: 720px;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.brand-headline {
  margin: 0 0 var(--cs-space-3);
  font-size: 32px;
  line-height: 1.4;
  font-weight: 700;
  color: var(--cs-text);
}

.brand-sub {
  margin: 0 0 28px;
  color: var(--cs-muted);
  font-size: var(--cs-font);
}

.highlight-grid {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 24px 32px;
}

.highlight {
  display: flex;
  gap: var(--cs-space-3);
  align-items: flex-start;
}

.highlight-icon {
  flex: none;
  width: 40px;
  height: 40px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: var(--cs-radius-sm);
  background: var(--cs-surface);
  border: 1px solid var(--cs-border);
  overflow: hidden;
}

.highlight-img {
  width: 100%;
  height: 100%;
  object-fit: contain;
}

.highlight-copy {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.highlight-title {
  font-weight: 600;
  color: var(--cs-text);
}

.highlight-desc {
  font-size: var(--cs-font-small);
  color: var(--cs-muted);
  line-height: 1.5;
}

/* 主插图：无底色/圆角/边框，直接融进左侧背景；占满剩余高度，不够就按比例缩小，底部出血到视口底边。
   换图只改 heroImage 的 import。边缘用 mask 羽化，和背景色无缝衔接。 */
.brand-art-slot {
  flex: 1 1 0;
  min-height: 0;
  margin-top: 24px;
}

.brand-art {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: contain;
  object-position: center bottom;
  -webkit-mask-image: linear-gradient(to right, transparent 0, #000 8%, #000 92%, transparent 100%),
    linear-gradient(to bottom, transparent 0, #000 10%);
  -webkit-mask-composite: source-in;
  mask-image: linear-gradient(to right, transparent 0, #000 8%, #000 92%, transparent 100%),
    linear-gradient(to bottom, transparent 0, #000 10%);
  mask-composite: intersect;
}

/* ---- 右侧登录面板 ---- */
.form-pane {
  flex: 1 1 0;
  min-width: 0;
  display: flex;
  flex-direction: column;
  background: var(--cs-surface);
  padding: 24px 40px 20px;
  box-sizing: border-box;
}

.form-top {
  display: flex;
  align-items: center;
  gap: var(--cs-space-2);
}

.login-logo {
  width: 32px;
  height: 32px;
  object-fit: contain;
  border-radius: 8px;
}

.form-brand {
  font-weight: 600;
  color: var(--cs-text);
}

.form-main {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
}

.form-box {
  width: 100%;
  max-width: 360px;
}

.login-subtitle {
  margin: 0 0 var(--cs-space-2);
  font-size: var(--cs-font);
  color: var(--cs-primary);
}

.login-title {
  margin: 0 0 28px;
  font-size: var(--cs-type-title);
  font-weight: 700;
  color: var(--cs-text);
}

.login-form {
  display: flex;
  flex-direction: column;
  gap: var(--cs-space-4);
}

.login-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--cs-space-2);
}

.login-hint {
  font-size: var(--cs-font-small);
  color: var(--cs-muted);
}

.login-button {
  font-weight: 600;
  letter-spacing: 2px;
}

.forgot-link {
  flex: none;
  color: var(--cs-primary);
  font-size: var(--cs-font-small);
  cursor: pointer;

  &:hover {
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
  text-align: center;

  p {
    font-size: var(--cs-font-small);
    color: var(--cs-muted);
    margin: 0;
  }
}

@media (max-width: 1100px) {
  .brand-pane {
    flex-basis: 50%;
    padding: 32px 32px 0;
  }
  .brand-headline {
    font-size: 26px;
  }
  .highlight-grid {
    gap: 20px 20px;
  }
}

@media (max-width: 900px) {
  .highlight-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 767px) {
  .brand-pane {
    display: none;
  }
  .form-pane {
    padding: 20px 20px 16px;
  }
}
</style>
