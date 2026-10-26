<template>
  <div class="page-container">
    <el-card shadow="never">
      <!-- 查询条件 -->
      <div class="toolbar">
        <el-input
          v-model="query.keyword"
          placeholder="登录名 / 昵称"
          clearable
          style="width: 220px"
          @keyup.enter="onSearch"
        />
        <el-select v-model="query.role" placeholder="角色" clearable style="width: 140px">
          <el-option label="超级管理员" value="SUPER_ADMIN" />
          <el-option label="普通用户" value="USER" />
        </el-select>
        <el-select v-model="query.status" placeholder="状态" clearable style="width: 120px">
          <el-option label="启用" value="ACTIVE" />
          <el-option label="停用" value="DISABLED" />
        </el-select>
        <el-checkbox v-model="query.includeDeleted">包含已删除</el-checkbox>
        <el-button type="primary" :icon="Search" @click="onSearch">查询</el-button>
        <el-button :icon="Refresh" @click="onReset">重置</el-button>

        <div class="toolbar-right">
          <el-button type="primary" :icon="Plus" @click="openCreate">新增用户</el-button>
        </div>
      </div>

      <el-alert
        class="mb12"
        type="info"
        :closable="false"
        show-icon
        title="平台已关闭自助注册，账号只能在此创建；新账号与重置口令后的账号，首次登录必须修改口令。"
      />

      <!-- 列表 -->
      <el-table v-loading="loading" :data="records" border stripe size="default">
        <el-table-column prop="id" label="ID" width="190" show-overflow-tooltip />
        <el-table-column prop="username" label="登录名" width="150" show-overflow-tooltip />
        <el-table-column prop="nickname" label="昵称" width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ row.nickname || '-' }}</template>
        </el-table-column>
        <el-table-column label="角色" width="120">
          <template #default="{ row }">
            <el-tag :type="row.role === 'SUPER_ADMIN' ? 'warning' : 'primary'" size="small" effect="plain">
              {{ row.role === 'SUPER_ADMIN' ? '超级管理员' : '普通用户' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'danger'" size="small">
              {{ row.status === 'ACTIVE' ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="账号标记" width="130">
          <template #default="{ row }">
            <el-tag v-if="row.deleted === 1" type="info" size="small">已删除</el-tag>
            <el-tag v-else-if="row.mustChangePassword" type="warning" size="small" effect="plain">待改密</el-tag>
            <span v-else class="text-muted">-</span>
          </template>
        </el-table-column>
        <el-table-column prop="lastLoginAt" label="最近登录" width="170">
          <template #default="{ row }">{{ row.lastLoginAt || '-' }}</template>
        </el-table-column>
        <el-table-column prop="createdAt" label="创建时间" width="170">
          <template #default="{ row }">{{ row.createdAt || '-' }}</template>
        </el-table-column>
        <el-table-column label="备注" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ row.remark || '-' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="290" fixed="right">
          <template #default="{ row }">
            <template v-if="row.deleted === 1">
              <el-button link type="primary" size="small" @click="onRestore(row)">恢复</el-button>
            </template>
            <template v-else>
              <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
              <el-button
                link
                :type="row.status === 'ACTIVE' ? 'warning' : 'success'"
                size="small"
                :disabled="isSelf(row)"
                @click="onToggleStatus(row)"
              >
                {{ row.status === 'ACTIVE' ? '停用' : '启用' }}
              </el-button>
              <el-button link type="primary" size="small" :disabled="isSelf(row)" @click="openReset(row)">
                重置口令
              </el-button>
              <el-button link type="danger" size="small" :disabled="isSelf(row)" @click="onDelete(row)">
                删除
              </el-button>
            </template>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无用户" />
        </template>
      </el-table>

      <div class="pagination-bar">
        <el-pagination
          v-model:current-page="query.page"
          v-model:page-size="query.size"
          :total="total"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next, jumper"
          background
          @current-change="load"
          @size-change="onSearch"
        />
      </div>
    </el-card>

    <!-- 新增 -->
    <el-dialog v-model="createVisible" title="新增用户" width="520px" :close-on-click-modal="false">
      <el-form ref="createFormRef" :model="createForm" :rules="createRules" label-width="92px">
        <el-form-item label="登录名" prop="username">
          <el-input v-model="createForm.username" placeholder="3~32 位字母、数字或下划线" />
        </el-form-item>
        <el-form-item label="昵称" prop="nickname">
          <el-input v-model="createForm.nickname" placeholder="选填，默认与登录名一致" />
        </el-form-item>
        <el-form-item label="角色" prop="role">
          <el-select v-model="createForm.role" style="width: 100%">
            <el-option label="普通用户" value="USER" />
            <el-option label="超级管理员" value="SUPER_ADMIN" />
          </el-select>
        </el-form-item>
        <el-form-item label="初始口令" prop="initialPassword">
          <el-input v-model="createForm.initialPassword" placeholder="留空则使用系统默认口令" show-password />
          <div class="field-tip">8~64 位且同时包含字母与数字；用户首次登录必须修改。</div>
        </el-form-item>
        <el-form-item label="备注" prop="remark">
          <el-input v-model="createForm.remark" type="textarea" :rows="2" maxlength="255" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitCreate">确定</el-button>
      </template>
    </el-dialog>

    <!-- 编辑 -->
    <el-dialog v-model="editVisible" title="编辑用户" width="520px" :close-on-click-modal="false">
      <el-form ref="editFormRef" :model="editForm" :rules="editRules" label-width="92px">
        <el-form-item label="登录名">
          <el-input :model-value="editForm.username" disabled />
        </el-form-item>
        <el-form-item label="昵称" prop="nickname">
          <el-input v-model="editForm.nickname" />
        </el-form-item>
        <el-form-item label="角色" prop="role">
          <el-select v-model="editForm.role" style="width: 100%" :disabled="isSelf(editForm)">
            <el-option label="普通用户" value="USER" />
            <el-option label="超级管理员" value="SUPER_ADMIN" />
          </el-select>
        </el-form-item>
        <el-form-item label="备注" prop="remark">
          <el-input v-model="editForm.remark" type="textarea" :rows="2" maxlength="255" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitEdit">保存</el-button>
      </template>
    </el-dialog>

    <!-- 重置口令 -->
    <el-dialog v-model="resetVisible" title="重置口令" width="480px" :close-on-click-modal="false">
      <el-alert
        class="mb12"
        type="warning"
        :closable="false"
        show-icon
        title="重置后该账号下次登录必须先修改口令"
      />
      <el-form ref="resetFormRef" :model="resetForm" :rules="resetRules" label-width="92px">
        <el-form-item label="目标账号">
          <el-input :model-value="resetForm.username" disabled />
        </el-form-item>
        <el-form-item label="新口令" prop="newPassword">
          <el-input v-model="resetForm.newPassword" show-password placeholder="8~64 位，含字母与数字" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="resetVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitReset">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormItemRule, type FormRules } from 'element-plus'
import { Plus, Refresh, Search } from '@element-plus/icons-vue'
import type { UserRole, UserStatus, UserVO } from '@/api/auth'
import {
  changeUserStatus,
  createUser,
  deleteUser,
  pageUsers,
  resetUserPassword,
  restoreUser,
  updateUser
} from '@/api/user'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()

const loading = ref(false)
const submitting = ref(false)
const records = ref<UserVO[]>([])
const total = ref(0)

const query = reactive({
  page: 1,
  size: 20,
  keyword: '',
  role: '' as UserRole | '',
  status: '' as UserStatus | '',
  includeDeleted: false
})

const passwordValidator = (_rule: unknown, value: unknown, callback: (error?: Error) => void) => {
  if (!value) {
    callback(new Error('请输入口令'))
    return
  }
  if (!/^(?=.*[A-Za-z])(?=.*\d)[\x21-\x7E]{8,64}$/.test(value as string)) {
    callback(new Error('口令需 8~64 位且同时包含字母与数字'))
    return
  }
  callback()
}

/* ---------------- 列表 ---------------- */

async function load() {
  loading.value = true
  try {
    const result = await pageUsers({
      page: query.page,
      size: query.size,
      keyword: query.keyword || undefined,
      role: query.role || undefined,
      status: query.status || undefined,
      includeDeleted: query.includeDeleted
    })
    records.value = result.records
    total.value = result.total
  } catch (error) {
    ElMessage.error((error as Error).message || '加载失败')
  } finally {
    loading.value = false
  }
}

function onSearch() {
  query.page = 1
  load()
}

function onReset() {
  query.keyword = ''
  query.role = ''
  query.status = ''
  query.includeDeleted = false
  onSearch()
}

function isSelf(row: { id?: string; username?: string }) {
  return auth.user?.id !== undefined && row.id === auth.user?.id
}

/* ---------------- 新增 ---------------- */

const createVisible = ref(false)
const createFormRef = ref<FormInstance>()
const createForm = reactive({
  username: '',
  nickname: '',
  role: 'USER' as UserRole,
  initialPassword: '',
  remark: ''
})

const createRules: FormRules = {
  username: [
    { required: true, message: '请输入登录名', trigger: 'blur' },
    { pattern: /^[A-Za-z0-9_]{3,32}$/, message: '登录名需为 3~32 位字母、数字或下划线', trigger: 'blur' }
  ],
  role: [{ required: true, message: '请选择角色', trigger: 'change' }],
  initialPassword: [{ validator: passwordValidator, trigger: 'blur', required: false } as FormItemRule]
}

function openCreate() {
  createForm.username = ''
  createForm.nickname = ''
  createForm.role = 'USER'
  createForm.initialPassword = ''
  createForm.remark = ''
  createVisible.value = true
}

async function submitCreate() {
  if (!createFormRef.value) {
    return
  }
  const valid = await createFormRef.value.validate().catch(() => false)
  if (!valid) {
    return
  }
  submitting.value = true
  try {
    await createUser({
      username: createForm.username.trim(),
      nickname: createForm.nickname.trim() || undefined,
      role: createForm.role,
      initialPassword: createForm.initialPassword || undefined,
      remark: createForm.remark || undefined
    })
    ElMessage.success('创建成功，请将初始口令告知用户，首次登录需改密')
    createVisible.value = false
    await load()
  } catch (error) {
    ElMessage.error((error as Error).message || '创建失败')
  } finally {
    submitting.value = false
  }
}

/* ---------------- 编辑 ---------------- */

const editVisible = ref(false)
const editFormRef = ref<FormInstance>()
const editForm = reactive({
  id: '',
  username: '',
  nickname: '',
  role: 'USER' as UserRole,
  remark: ''
})

const editRules: FormRules = {
  role: [{ required: true, message: '请选择角色', trigger: 'change' }]
}

function openEdit(row: UserVO) {
  editForm.id = row.id
  editForm.username = row.username
  editForm.nickname = row.nickname ?? ''
  editForm.role = row.role
  editForm.remark = row.remark ?? ''
  editVisible.value = true
}

async function submitEdit() {
  if (!editFormRef.value) {
    return
  }
  const valid = await editFormRef.value.validate().catch(() => false)
  if (!valid) {
    return
  }
  submitting.value = true
  try {
    await updateUser(editForm.id, {
      nickname: editForm.nickname,
      role: editForm.role,
      remark: editForm.remark
    })
    ElMessage.success('保存成功')
    editVisible.value = false
    await load()
  } catch (error) {
    ElMessage.error((error as Error).message || '保存失败')
  } finally {
    submitting.value = false
  }
}

/* ---------------- 启停 ---------------- */

async function onToggleStatus(row: UserVO) {
  const target: UserStatus = row.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'
  const actionText = target === 'DISABLED' ? '停用' : '启用'
  await ElMessageBox.confirm(
    target === 'DISABLED'
      ? `确认停用「${row.username}」？停用后该账号已签发的令牌会立即失效。`
      : `确认启用「${row.username}」？`,
    `${actionText}账号`,
    { type: 'warning' }
  )
  try {
    await changeUserStatus(row.id, target)
    ElMessage.success(`${actionText}成功`)
    await load()
  } catch (error) {
    ElMessage.error((error as Error).message || `${actionText}失败`)
  }
}

/* ---------------- 重置口令 ---------------- */

const resetVisible = ref(false)
const resetFormRef = ref<FormInstance>()
const resetForm = reactive({
  id: '',
  username: '',
  newPassword: ''
})

const resetRules: FormRules = {
  newPassword: [{ required: true, validator: passwordValidator, trigger: 'blur' } as FormItemRule]
}

function openReset(row: UserVO) {
  resetForm.id = row.id
  resetForm.username = row.username
  resetForm.newPassword = ''
  resetVisible.value = true
}

async function submitReset() {
  if (!resetFormRef.value) {
    return
  }
  const valid = await resetFormRef.value.validate().catch(() => false)
  if (!valid) {
    return
  }
  submitting.value = true
  try {
    await resetUserPassword(resetForm.id, resetForm.newPassword)
    ElMessage.success('口令已重置，该账号下次登录需改密')
    resetVisible.value = false
    await load()
  } catch (error) {
    ElMessage.error((error as Error).message || '重置失败')
  } finally {
    submitting.value = false
  }
}

/* ---------------- 删除 / 恢复 ---------------- */

async function onDelete(row: UserVO) {
  await ElMessageBox.confirm(
    `确认删除「${row.username}」？删除后该账号无法登录，数据保留 30 天，可在「包含已删除」列表中恢复。`,
    '删除账号',
    { type: 'warning', confirmButtonText: '删除', confirmButtonClass: 'el-button--danger' }
  )
  try {
    await deleteUser(row.id)
    ElMessage.success('已删除')
    await load()
  } catch (error) {
    ElMessage.error((error as Error).message || '删除失败')
  }
}

async function onRestore(row: UserVO) {
  await ElMessageBox.confirm(`确认恢复账号「${row.username}」？`, '恢复账号', { type: 'info' })
  try {
    await restoreUser(row.id)
    ElMessage.success('已恢复')
    await load()
  } catch (error) {
    ElMessage.error((error as Error).message || '恢复失败')
  }
}

onMounted(load)
</script>

<style scoped>
.mb12 {
  margin-bottom: 12px;
}

.field-tip {
  font-size: 12px;
  color: var(--sf-text-regular);
  line-height: 1.6;
}
</style>
