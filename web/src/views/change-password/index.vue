<template>
  <div class="placeholder-page">
    <el-card shadow="never" class="placeholder-card">
      <template #header>
        <div class="card-header">
          <span>修改口令</span>
          <el-tag v-if="auth.mustChangePassword" type="warning" size="small">首次登录必须修改</el-tag>
        </div>
      </template>

      <el-alert
        v-if="auth.mustChangePassword"
        class="tip"
        type="warning"
        :closable="false"
        show-icon
        title="请先修改初始口令"
        description="在修改口令之前，系统仅允许访问本页面、查看当前用户与登出。"
      />

      <el-form ref="formRef" :model="form" :rules="rules" label-width="110px" class="form">
        <el-form-item label="当前口令" prop="oldPassword">
          <el-input v-model="form.oldPassword" type="password" show-password placeholder="请输入当前口令" />
        </el-form-item>
        <el-form-item label="新口令" prop="newPassword">
          <el-input v-model="form.newPassword" type="password" show-password placeholder="8~64 位，含字母与数字" />
        </el-form-item>
        <el-form-item label="确认新口令" prop="confirmPassword">
          <el-input v-model="form.confirmPassword" type="password" show-password placeholder="再次输入新口令" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" @click="onSubmit">提交</el-button>
          <el-button v-if="!auth.mustChangePassword" @click="router.back()">返回</el-button>
          <el-button v-else type="danger" plain @click="onLogout">退出登录</el-button>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules, type FormItemRule } from 'element-plus'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const auth = useAuthStore()

const formRef = ref<FormInstance>()
const loading = ref(false)

const form = reactive({
  oldPassword: '',
  newPassword: '',
  confirmPassword: ''
})

const passwordRule: FormItemRule = {
  required: true,
  validator: (_rule, value, callback) => {
    if (!value) {
      callback(new Error('请输入新口令'))
      return
    }
    if (!/^(?=.*[A-Za-z])(?=.*\d)[\x21-\x7E]{8,64}$/.test(value as string)) {
      callback(new Error('口令需 8~64 位且同时包含字母与数字'))
      return
    }
    callback()
  },
  trigger: 'blur'
}

const rules: FormRules = {
  oldPassword: [{ required: true, message: '请输入当前口令', trigger: 'blur' }],
  newPassword: [passwordRule],
  confirmPassword: [
    {
      required: true,
      validator: (_rule, value, callback) => {
        if (!value) {
          callback(new Error('请再次输入新口令'))
        } else if (value !== form.newPassword) {
          callback(new Error('两次输入的新口令不一致'))
        } else {
          callback()
        }
      },
      trigger: 'blur'
    }
  ]
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
    await auth.changePassword(form.oldPassword, form.newPassword)
    ElMessage.success('口令修改成功')
    formRef.value.resetFields()
    await router.replace('/dashboard')
  } catch (error) {
    ElMessage.error((error as Error).message || '修改失败')
  } finally {
    loading.value = false
  }
}

async function onLogout() {
  await auth.logout()
  await router.replace({ name: 'login' })
}
</script>

<style scoped>
.placeholder-page {
  padding: 16px;
}

.placeholder-card {
  max-width: 560px;
}

.card-header {
  display: flex;
  align-items: center;
  gap: 10px;
  font-weight: 600;
}

.tip {
  margin-bottom: 18px;
}

.form {
  max-width: 460px;
}
</style>
