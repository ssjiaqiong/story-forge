<template>
  <div class="login-page">
    <div class="login-card">
      <div class="login-brand">
        <span class="brand-mark">SF</span>
        <div>
          <h1>StoryForge</h1>
          <p>AI 长篇小说创作平台</p>
        </div>
      </div>

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

      <el-alert
        class="login-tip"
        type="info"
        :closable="false"
        show-icon
        title="系统已关闭自助注册"
        description="账号由超级管理员在「用户管理」中创建，首次登录需修改初始口令。"
      />

      <!-- AGPL-3.0 §13：对外提供服务的实例须向使用者开放源码获取入口 -->
      <div class="login-license">
        本项目以
        <a :href="LICENSE_URL" target="_blank" rel="noopener noreferrer">{{ LICENSE_NAME }}</a>
        协议开源 ·
        <a :href="SOURCE_REPO_URL" target="_blank" rel="noopener noreferrer">获取源码</a>
      </div>
    </div>

    <div class="login-footer">
      <a :href="ICP_URL" target="_blank" rel="noopener noreferrer">{{ ICP_NUMBER }}</a>
      <span class="footer-sep">·</span>
      <span>开源仓库</span>
      <a :href="SOURCE_REPO_URL" target="_blank" rel="noopener noreferrer">{{ SOURCE_REPO_URL }}</a>
      <template v-if="systemStore.version">
        <span class="footer-sep">·</span>
        <span class="footer-version" :title="systemStore.buildTime ? `构建于 ${systemStore.buildTime}` : '当前部署的代码版本'">
          v{{ systemStore.version }}
        </span>
      </template>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { Lock, User } from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'
import { useSystemStore } from '@/stores/system'
import { ICP_NUMBER, ICP_URL, LICENSE_NAME, LICENSE_URL, SOURCE_REPO_URL } from '@/config/about'

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
.login-page {
  height: 100vh;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 24px;
  background: linear-gradient(135deg, #1f2733 0%, #2d3f5e 55%, #3b6ef6 100%);
}

.login-card {
  width: 400px;
  background: var(--sf-bg-card);
  border-radius: 12px;
  padding: 32px 32px 24px;
  box-shadow: 0 16px 48px rgba(0, 0, 0, 0.25);
}

.login-brand {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 24px;
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

.login-brand h1 {
  margin: 0;
  font-size: 20px;
}

.login-brand p {
  margin: 2px 0 0;
  font-size: 12px;
  color: var(--sf-text-regular);
}

.login-button {
  width: 100%;
  margin-top: 4px;
}

.login-tip {
  margin-top: 20px;
}

.login-license {
  margin-top: 14px;
  text-align: center;
  font-size: 12px;
  color: var(--sf-text-regular);
}

.login-license a {
  color: var(--sf-brand);
  text-decoration: none;
}

.login-license a:hover {
  text-decoration: underline;
}

.login-footer {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-wrap: wrap;
  gap: 6px;
  text-align: center;
  font-size: 12px;
  line-height: 1.8;
  color: rgba(255, 255, 255, 0.72);
  word-break: break-all;
}

.login-footer a {
  color: rgba(255, 255, 255, 0.85);
  text-decoration: none;
}

.login-footer a:hover {
  text-decoration: underline;
}

.footer-sep {
  margin: 0 2px;
  opacity: 0.6;
}

/* 部署版本号：与备案号、仓库地址同排，弱化处理 */
.footer-version {
  font-variant-numeric: tabular-nums;
  opacity: 0.85;
}
</style>
