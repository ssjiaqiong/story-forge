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
    </div>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { Lock, User } from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

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
  align-items: center;
  justify-content: center;
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
</style>
