<template>
  <div class="login-page">
    <!-- 左侧品牌区：与 promo/og-cover 一致的视觉语言，纯 CSS 渲染以保证任意尺寸下清晰 -->
    <section class="login-hero" aria-hidden="true">
      <div class="hero-glow hero-glow-a"></div>
      <div class="hero-glow hero-glow-b"></div>

      <div class="hero-inner">
        <span class="hero-kicker">AGPL-3.0 开源 · 可离线部署</span>
        <h1 class="hero-title">StoryForge</h1>
        <p class="hero-sub">AI 长篇小说创作平台</p>
        <div class="hero-rule"></div>
        <p class="hero-tagline">写到第 28 章，<br />它还记得你第 12 章埋下的伏笔。</p>
        <ul class="hero-chips">
          <li>分层上下文</li>
          <li>事实抽取</li>
          <li>一致性体检</li>
        </ul>
      </div>

      <div class="hero-stack">
        <span class="hero-sheet is-active"></span>
        <span class="hero-sheet"></span>
        <span class="hero-sheet"></span>
      </div>

      <div class="hero-foot">
        <span class="hero-site">www.jq888.cn</span>
        <span class="hero-lic">Spring Boot · Vue 3 · 无中间件</span>
      </div>
    </section>

    <!-- 右侧登录区 -->
    <main class="login-panel">
      <div class="login-card">
        <div class="panel-brand">
          <span class="brand-mark">SF</span>
          <div>
            <h1>StoryForge</h1>
            <p>AI 长篇小说创作平台</p>
          </div>
        </div>

        <h2 class="panel-title">登录</h2>
        <p class="panel-desc">使用管理员分配的账号进入工作台</p>

        <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @keyup.enter="onSubmit">
          <el-form-item label="登录名" prop="username">
            <el-input v-model="form.username" placeholder="请输入登录名" size="large" clearable>
              <template #prefix>
                <el-icon><User /></el-icon>
              </template>
            </el-input>
          </el-form-item>

          <el-form-item label="口令" prop="password">
            <el-input
              v-model="form.password"
              type="password"
              placeholder="请输入口令"
              size="large"
              show-password
              clearable
            >
              <template #prefix>
                <el-icon><Lock /></el-icon>
              </template>
            </el-input>
          </el-form-item>

          <el-button type="primary" size="large" class="login-button" :loading="loading" @click="onSubmit">
            登录
          </el-button>
        </el-form>

        <el-alert class="login-tip" type="info" :closable="false" show-icon>
          <template #title>
            <span class="tip-title">本项目已关闭自助注册</span>
          </template>
          <div class="tip-body">
            <p class="tip-line">账号由超级管理员在「用户管理」中创建，首次登录需修改初始口令。</p>
            <p class="tip-line">
              想先试试的话，加官方 QQ 交流群，群内会<strong>不定期发放体验账号</strong>（数量有限，先到先得）。
            </p>
            <div class="qq-row">
              <span class="qq-label">QQ 群</span>
              <span class="qq-no">{{ QQ_GROUP }}</span>
              <el-button class="qq-copy" size="small" text type="primary" @click="copyQqGroup">
                {{ qqCopied ? '已复制' : '复制群号' }}
              </el-button>
            </div>
          </div>
        </el-alert>

        <!-- AGPL-3.0 §13：对外提供服务的实例须向使用者开放源码获取入口 -->
        <div class="login-license">
          本项目以
          <a :href="LICENSE_URL" target="_blank" rel="noopener noreferrer">{{ LICENSE_NAME }}</a>
          协议开源 ·
          <a :href="SOURCE_REPO_URL" target="_blank" rel="noopener noreferrer">获取源码</a>
        </div>
      </div>
    </main>

    <!-- 备案号 / 仓库 / 版本号：整页底部居中 -->
    <footer class="login-footer">
      <a :href="ICP_URL" target="_blank" rel="noopener noreferrer">{{ ICP_NUMBER }}</a>
      <span class="footer-sep">·</span>
      <span>开源仓库</span>
      <a :href="SOURCE_REPO_URL" target="_blank" rel="noopener noreferrer">{{ SOURCE_REPO_URL }}</a>
      <template v-if="systemStore.version">
        <span class="footer-sep">·</span>
        <span
          class="footer-version"
          :title="systemStore.buildTime ? `构建于 ${systemStore.buildTime}` : '当前部署的代码版本'"
        >
          v{{ systemStore.version }}
        </span>
      </template>
    </footer>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { Lock, User } from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'
import { useSystemStore } from '@/stores/system'
import { ICP_NUMBER, ICP_URL, LICENSE_NAME, LICENSE_URL, QQ_GROUP, SOURCE_REPO_URL } from '@/config/about'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const systemStore = useSystemStore()

/* 登录页也要显示部署版本号（此时还没有令牌，接口是公开的） */
onMounted(() => {
  systemStore.load()
})

const formRef = ref<FormInstance>()
const loading = ref(false)
const form = reactive({
  username: '',
  password: ''
})

const rules: FormRules = {
  username: [{ required: true, message: '请输入登录名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入口令', trigger: 'blur' }]
}

/* 群号复制：https 下用 Clipboard API，降级到 textarea + execCommand */
const qqCopied = ref(false)
let qqTimer: number | undefined

async function copyQqGroup() {
  const ok = await writeClipboard(QQ_GROUP)
  if (!ok) {
    ElMessage.warning(`复制失败，请手动记下群号：${QQ_GROUP}`)
    return
  }
  qqCopied.value = true
  ElMessage.success('群号已复制，到 QQ 里粘贴搜索即可加群')
  window.clearTimeout(qqTimer)
  qqTimer = window.setTimeout(() => {
    qqCopied.value = false
  }, 2500)
}

async function writeClipboard(text: string): Promise<boolean> {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text)
      return true
    }
  } catch {
    /* 非安全上下文或权限被拒，走兜底 */
  }
  try {
    const el = document.createElement('textarea')
    el.value = text
    el.setAttribute('readonly', '')
    el.style.position = 'fixed'
    el.style.top = '-1000px'
    el.style.opacity = '0'
    document.body.appendChild(el)
    el.select()
    const ok = document.execCommand('copy')
    document.body.removeChild(el)
    return ok
  } catch {
    return false
  }
}

async function onSubmit() {
  if (!formRef.value) {
    return
  }
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) {
    return
  }
  loading.value = true
  try {
    const user = await auth.login({ username: form.username.trim(), password: form.password })
    if (user.mustChangePassword) {
      ElMessage.warning('首次登录请先修改初始口令')
      await router.replace({ name: 'change-password' })
      return
    }
    const redirect = (route.query.redirect as string) || '/dashboard'
    await router.replace(redirect)
  } catch (error) {
    ElMessage.error((error as Error).message || '登录失败')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
/*
 * 登录页是固定深色的品牌页：两套主题下外观一致，因此这里的颜色写死为深色系，
 * 不再使用 --sf-* 语义变量（那些变量会跟着主题翻白，导致黑底黑字）。
 */
.login-page {
  min-height: 100vh;
  min-height: 100dvh;
  display: grid;
  grid-template-columns: minmax(0, 1fr) clamp(420px, 36vw, 540px);
  grid-template-rows: 1fr auto;
  background: #000;
}

/* ---------- 左侧品牌区 ---------- */
.login-hero {
  position: relative;
  isolation: isolate;
  display: flex;
  flex-direction: column;
  justify-content: center;
  overflow: hidden;
  padding: clamp(44px, 8vh, 96px) clamp(40px, 5vw, 88px);
  background: linear-gradient(158deg, #121926 0%, #0d1014 54%, #0a0d11 100%);
  color: #e8edf4;
}

/*
 * 右端渐隐成纯黑：与右栏的纯黑起点无缝衔接，两栏看起来是同一张背景。
 * z-index 0 只压住光晕，文字与装饰卡在 z-index 1 之上，不会被一起压暗。
 */
.login-hero::after {
  content: '';
  position: absolute;
  inset: 0;
  z-index: 0;
  pointer-events: none;
  background: linear-gradient(
    90deg,
    rgba(0, 0, 0, 0) 38%,
    rgba(0, 0, 0, 0.42) 68%,
    rgba(0, 0, 0, 0.86) 88%,
    #000 100%
  );
}

.hero-glow {
  position: absolute;
  border-radius: 50%;
  pointer-events: none;
}

.hero-glow-a {
  width: clamp(620px, 68vw, 1040px);
  height: clamp(620px, 68vw, 1040px);
  top: -30%;
  right: -10%;
  background: radial-gradient(
    circle,
    rgba(90, 140, 255, 0.34) 0%,
    rgba(90, 140, 255, 0.12) 38%,
    rgba(90, 140, 255, 0) 68%
  );
}

.hero-glow-b {
  width: clamp(460px, 48vw, 720px);
  height: clamp(460px, 48vw, 720px);
  bottom: -28%;
  left: -18%;
  background: radial-gradient(
    circle,
    rgba(224, 168, 96, 0.15) 0%,
    rgba(224, 168, 96, 0.05) 42%,
    rgba(224, 168, 96, 0) 70%
  );
}

.hero-inner {
  position: relative;
  z-index: 1;
  max-width: 560px;
}

.hero-kicker {
  display: inline-block;
  font-size: clamp(12px, 0.95vw, 15px);
  letter-spacing: 0.16em;
  color: #8fa3bf;
  border: 1px solid rgba(143, 163, 191, 0.35);
  border-radius: 999px;
  padding: 6px 16px 5px;
}

.hero-title {
  margin: clamp(20px, 3.2vh, 34px) 0 0;
  font-size: clamp(44px, 4.6vw, 76px);
  font-weight: 700;
  line-height: 1;
  letter-spacing: -0.03em;
  background: linear-gradient(96deg, #ffffff 22%, #9fc0ff 78%);
  -webkit-background-clip: text;
  background-clip: text;
  -webkit-text-fill-color: transparent;
  color: transparent;
}

.hero-sub {
  margin: clamp(10px, 1.6vh, 18px) 0 0;
  font-size: clamp(18px, 1.7vw, 28px);
  letter-spacing: 0.06em;
  color: #cfdaea;
}

.hero-rule {
  margin: clamp(20px, 3.4vh, 34px) 0 clamp(16px, 2.4vh, 24px);
  width: 88px;
  height: 3px;
  border-radius: 2px;
  background: linear-gradient(90deg, #5a8cff, rgba(90, 140, 255, 0));
}

.hero-tagline {
  margin: 0;
  max-width: 420px;
  font-size: clamp(15px, 1.35vw, 21px);
  line-height: 1.7;
  color: #9fb0c6;
}

.hero-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin: clamp(18px, 3vh, 30px) 0 0;
  padding: 0;
  list-style: none;
}

.hero-chips li {
  font-size: clamp(12px, 0.95vw, 14px);
  color: #b9c8dc;
  background: rgba(255, 255, 255, 0.05);
  border: 1px solid rgba(255, 255, 255, 0.09);
  border-radius: 8px;
  padding: 7px 13px;
}

/* 右下角装饰：稿件堆叠 */
.hero-stack {
  position: absolute;
  right: clamp(32px, 4vw, 64px);
  bottom: clamp(104px, 17vh, 156px);
  z-index: 1;
  width: clamp(176px, 16vw, 240px);
  display: flex;
  flex-direction: column;
  gap: 11px;
  pointer-events: none;
}

.hero-sheet {
  position: relative;
  display: block;
  height: clamp(50px, 6.2vh, 72px);
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.055), rgba(255, 255, 255, 0.02));
  border: 1px solid rgba(255, 255, 255, 0.075);
  border-radius: 10px;
}

.hero-sheet::before {
  content: '';
  position: absolute;
  left: 16px;
  top: 30%;
  width: 46%;
  height: 8px;
  border-radius: 4px;
  background: rgba(255, 255, 255, 0.2);
}

.hero-sheet::after {
  content: '';
  position: absolute;
  left: 16px;
  top: 55%;
  width: 70%;
  height: 6px;
  border-radius: 4px;
  background: rgba(255, 255, 255, 0.09);
}

.hero-sheet.is-active {
  border-color: rgba(90, 140, 255, 0.42);
  background: linear-gradient(180deg, rgba(90, 140, 255, 0.16), rgba(90, 140, 255, 0.04));
}

.hero-sheet.is-active::before {
  background: rgba(160, 190, 255, 0.62);
}

.hero-foot {
  position: absolute;
  left: clamp(40px, 5vw, 88px);
  right: clamp(40px, 5vw, 88px);
  bottom: clamp(30px, 4.5vh, 52px);
  z-index: 1;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  font-size: clamp(12px, 0.95vw, 14px);
  color: #7e8fa5;
}

.hero-site {
  color: #cfdaea;
  letter-spacing: 0.04em;
}

/* ---------- 右侧登录区（从纯黑起头，与左侧渐隐无缝接续） ---------- */
.login-panel {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 32px 20px;
  background: linear-gradient(90deg, #000 0%, #04070a 46%, #080c11 100%);
  overflow-y: auto;
}

.login-card {
  width: min(400px, 100%);
  margin: auto 0;
  padding: 30px 30px 24px;
  border-radius: 16px;
  background-image: linear-gradient(180deg, rgba(255, 255, 255, 0.065), rgba(255, 255, 255, 0.022));
  border: 1px solid rgba(255, 255, 255, 0.1);
  box-shadow:
    0 24px 60px rgba(0, 0, 0, 0.45),
    inset 0 1px 0 rgba(255, 255, 255, 0.06);
  backdrop-filter: blur(14px);
  color: #e8edf4;
}

/* 品牌标识只在窄屏（左侧品牌区隐藏时）出现 */
.panel-brand {
  display: none;
  align-items: center;
  gap: 12px;
  margin-bottom: 26px;
}

.brand-mark {
  width: 44px;
  height: 44px;
  border-radius: 10px;
  background: var(--sf-brand);
  color: #fff;
  font-weight: 700;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 18px;
}

.panel-brand h1 {
  margin: 0;
  font-size: 19px;
  color: #f2f5f9;
}

.panel-brand p {
  margin: 2px 0 0;
  font-size: 12px;
  color: #8b9bb0;
}

.panel-title {
  margin: 0;
  font-size: 22px;
  font-weight: 600;
  color: #f2f5f9;
}

.panel-desc {
  margin: 6px 0 22px;
  font-size: 13px;
  color: #8b9bb0;
}

.login-button {
  width: 100%;
  margin-top: 4px;
}

/* ---------- 深色下的表单控件适配 ---------- */
.login-card :deep(.el-form-item__label) {
  color: #b7c3d4;
}

.login-card :deep(.el-input__wrapper) {
  background-color: rgba(255, 255, 255, 0.05);
  box-shadow: 0 0 0 1px rgba(255, 255, 255, 0.11) inset;
}

.login-card :deep(.el-input__wrapper:hover) {
  box-shadow: 0 0 0 1px rgba(255, 255, 255, 0.22) inset;
}

.login-card :deep(.el-input__wrapper.is-focus) {
  box-shadow: 0 0 0 1px var(--sf-brand) inset;
}

.login-card :deep(.el-input__inner) {
  color: #e8edf4;
  /* 浏览器自动填充会把底色刷白，用超长过渡盖住 */
  transition: background-color 9999s ease-out 0s;
}

.login-card :deep(.el-input__inner::placeholder) {
  color: #6b7a8f;
}

.login-card :deep(.el-input__prefix),
.login-card :deep(.el-input__suffix) {
  color: #7c8ca1;
}

.login-card :deep(.el-form-item__error) {
  color: #ff8a8a;
}

/* ---------- 关闭注册说明 ---------- */
.login-tip {
  margin-top: 20px;
  align-items: flex-start;
  /* Element Plus 的 alert 底色由 is-light 规则给出，这里需要强覆盖 */
  background-color: rgba(90, 140, 255, 0.1) !important;
  border: 1px solid rgba(90, 140, 255, 0.22);
}

/* el-alert 用默认插槽装多段说明时，内容区默认宽度受限，这里放开 */
.login-tip :deep(.el-alert__content) {
  width: 100%;
}

/* 多行说明时图标居中会显得偏下，改为与首行对齐 */
.login-tip :deep(.el-alert__icon) {
  margin-top: 1px;
  color: #7aa1ff;
}

.tip-title {
  font-size: 13px;
  font-weight: 600;
  color: #dbe4f0;
}

.tip-body {
  margin-top: 2px;
}

.tip-line {
  margin: 0 0 6px;
  font-size: 12px;
  line-height: 1.7;
  color: #9fb0c6;
}

.tip-line strong {
  color: #e8edf4;
  font-weight: 600;
}

.qq-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
  padding: 6px 10px;
  border-radius: 6px;
  background: rgba(90, 140, 255, 0.14);
}

.qq-label {
  font-size: 12px;
  color: #9fb0c6;
}

.qq-no {
  font-family: ui-monospace, Menlo, Consolas, monospace;
  font-size: 15px;
  font-weight: 600;
  letter-spacing: 0.06em;
  color: #e8edf4;
  user-select: all;
}

.qq-copy {
  margin-left: auto;
  padding: 0 2px;
}

.login-license {
  margin-top: 14px;
  text-align: center;
  font-size: 12px;
  color: #8b9bb0;
}

.login-license a {
  color: #7aa1ff;
  text-decoration: none;
}

.login-license a:hover {
  text-decoration: underline;
}

/* ---------- 整页底部：备案号 / 仓库 / 版本号 ---------- */
.login-footer {
  grid-column: 1 / -1;
  grid-row: 2;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-wrap: wrap;
  gap: 6px;
  padding: 14px 24px;
  text-align: center;
  font-size: 12px;
  line-height: 1.8;
  color: #6f7f94;
  background: #000;
  border-top: 1px solid rgba(255, 255, 255, 0.04);
  word-break: break-all;
}

.login-footer a {
  color: #93a3b8;
  text-decoration: none;
}

.login-footer a:hover {
  color: #cfe0f5;
}

.footer-sep {
  margin: 0 2px;
  opacity: 0.6;
}

/* 部署版本号：与备案号、仓库地址同排，弱化处理 */
.footer-version {
  font-variant-numeric: tabular-nums;
  opacity: 0.8;
}

/* 中等高度下装饰卡片容易压到标语，直接收掉 */
@media (max-height: 780px) {
  .hero-stack {
    display: none;
  }
}

/* ---------- 窄屏：单列，品牌标识回到卡片里 ---------- */
@media (max-width: 1080px) {
  .login-page {
    grid-template-columns: 1fr;
  }

  .login-hero {
    display: none;
  }

  .login-panel {
    padding: 40px 24px 28px;
  }

  .panel-brand {
    display: flex;
  }
}
</style>
