<template>
  <div class="panel fs-panel">
    <div class="panel-head">
      <div>
        <h3 class="panel-title">伏笔</h3>
        <p class="panel-tip">
          埋了什么、计划何时收、有没有超期。正文生成时会把「未回收」清单一并交给模型，
          一直不收的线会被体检标成超期。
        </p>
      </div>
      <div class="head-actions">
        <el-select v-model="filter" size="small" style="width: 120px" @change="load">
          <el-option label="未回收" value="OPEN" />
          <el-option label="已回收" value="PAID" />
          <el-option label="已放弃" value="DROPPED" />
          <el-option label="全部" value="" />
        </el-select>
        <el-button :icon="Refresh" circle size="small" :loading="loading" @click="load" />
        <el-button type="primary" size="small" :icon="Plus" :disabled="locked" @click="openCreate">
          新增伏笔
        </el-button>
      </div>
    </div>

    <el-alert
      v-if="overdueCount > 0"
      type="warning"
      :closable="false"
      show-icon
      class="fs-alert"
      :title="`有 ${overdueCount} 条伏笔已超期`"
      description="超期不等于写错，但读者会记得这条线。可以在近期章节给它一个交代，或标记为已回收 / 放弃。"
    />

    <el-empty v-if="!loading && rows.length === 0" description="还没有登记伏笔。章节分析会自动抽取，也可以手工新增。" />

    <div v-else class="fs-list" v-loading="loading">
      <div v-for="item in rows" :key="item.id" class="fs-item" :class="{ overdue: item.overdue, done: item.status !== 'OPEN' }">
        <div class="fs-head">
          <span class="fs-title">{{ item.title }}</span>
          <el-tag size="small" :type="tagType(item)" effect="plain">{{ item.statusLabel }}</el-tag>
          <el-tag v-if="item.overdue" size="small" type="danger" effect="dark">
            超期 {{ item.overdueChapters }} 章
          </el-tag>
        </div>
        <p v-if="item.content" class="fs-content">{{ item.content }}</p>
        <div class="fs-meta">
          <span>埋于第 {{ item.plantedChapterNo || '—' }} 章</span>
          <span>计划第 {{ item.plannedPayoffChapter || '—' }} 章回收</span>
          <span v-if="item.payoffChapterNo">实际第 {{ item.payoffChapterNo }} 章回收</span>
          <span v-if="item.note" class="fs-note">{{ item.note }}</span>
        </div>
        <div class="fs-actions">
          <el-button text size="small" :disabled="locked" @click="openEdit(item)">编辑</el-button>
          <el-button
            v-if="item.status === 'OPEN'"
            text
            size="small"
            type="success"
            :disabled="locked"
            @click="markPaid(item)"
          >
            标记已回收
          </el-button>
          <el-button v-if="item.status === 'OPEN'" text size="small" :disabled="locked" @click="markDropped(item)">
            放弃
          </el-button>
          <el-button text size="small" type="danger" :disabled="locked" @click="remove(item)">删除</el-button>
        </div>
      </div>
    </div>

    <el-dialog v-model="dialogOpen" :title="editing ? '编辑伏笔' : '新增伏笔'" width="520px" append-to-body>
      <el-form :model="form" label-width="88px">
        <el-form-item label="标题" required>
          <el-input v-model="form.title" maxlength="200" show-word-limit placeholder="一句话，如「母亲留下的铜钥匙」" />
        </el-form-item>
        <el-form-item label="内容">
          <el-input
            v-model="form.content"
            type="textarea"
            :rows="4"
            maxlength="2000"
            show-word-limit
            placeholder="埋了什么、读者应该记住什么、将来会有什么用"
          />
        </el-form-item>
        <el-form-item label="埋设章">
          <el-input-number v-model="form.plantedChapterNo" :min="1" :controls="false" style="width: 140px" />
          <span class="form-hint">留空取当前最新章</span>
        </el-form-item>
        <el-form-item label="计划回收">
          <el-input-number v-model="form.plannedPayoffChapter" :min="0" :controls="false" style="width: 140px" />
          <span class="form-hint">0 表示还没想好，30 章后算超期</span>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.note" maxlength="500" placeholder="可空" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogOpen = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { Plus, Refresh } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  createForeshadow,
  deleteForeshadow,
  listForeshadows,
  updateForeshadow,
  type ForeshadowRequest,
  type ForeshadowStatus,
  type ForeshadowVO
} from '@/api/fact'

const props = defineProps<{
  novelId: string
  /** 自动写书运行中时全书写操作被锁 */
  locked?: boolean
  /** 当前激活的分区，用于懒加载 */
  active?: boolean
}>()

const rows = ref<ForeshadowVO[]>([])
const loading = ref(false)
const saving = ref(false)
const filter = ref<ForeshadowStatus | ''>('OPEN')
const dialogOpen = ref(false)
const editing = ref<ForeshadowVO | null>(null)

const form = reactive<ForeshadowRequest>({
  title: '',
  content: '',
  plantedChapterNo: undefined,
  plannedPayoffChapter: 0,
  note: ''
})

const overdueCount = computed(() => rows.value.filter((item) => item.overdue).length)

function tagType(item: ForeshadowVO) {
  if (item.status === 'PAID') return 'success'
  if (item.status === 'DROPPED') return 'info'
  return item.overdue ? 'danger' : 'warning'
}

async function load() {
  if (!props.novelId) return
  loading.value = true
  try {
    rows.value = await listForeshadows(props.novelId, filter.value || undefined)
  } catch (error) {
    ElMessage.error((error as Error).message || '加载伏笔失败')
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editing.value = null
  form.title = ''
  form.content = ''
  form.plantedChapterNo = undefined
  form.plannedPayoffChapter = 0
  form.note = ''
  dialogOpen.value = true
}

function openEdit(item: ForeshadowVO) {
  editing.value = item
  form.title = item.title
  form.content = item.content ?? ''
  form.plantedChapterNo = item.plantedChapterNo
  form.plannedPayoffChapter = item.plannedPayoffChapter ?? 0
  form.note = item.note ?? ''
  dialogOpen.value = true
}

async function submit() {
  if (!form.title?.trim()) {
    ElMessage.warning('标题不能为空')
    return
  }
  saving.value = true
  try {
    if (editing.value) {
      await updateForeshadow(editing.value.id, { ...form })
    } else {
      await createForeshadow(props.novelId, { ...form })
    }
    dialogOpen.value = false
    ElMessage.success('已保存')
    await load()
  } catch (error) {
    ElMessage.error((error as Error).message || '保存失败')
  } finally {
    saving.value = false
  }
}

async function markPaid(item: ForeshadowVO) {
  try {
    await updateForeshadow(item.id, { status: 'PAID' })
    ElMessage.success('已标记回收')
    await load()
  } catch (error) {
    ElMessage.error((error as Error).message || '操作失败')
  }
}

async function markDropped(item: ForeshadowVO) {
  try {
    await ElMessageBox.confirm('标记为放弃后，这条伏笔不再出现在正文提示词里。确定吗？', '放弃伏笔', {
      type: 'warning'
    })
  } catch {
    return
  }
  try {
    await updateForeshadow(item.id, { status: 'DROPPED' })
    ElMessage.success('已放弃')
    await load()
  } catch (error) {
    ElMessage.error((error as Error).message || '操作失败')
  }
}

async function remove(item: ForeshadowVO) {
  try {
    await ElMessageBox.confirm(`删除「${item.title}」？删除后不可恢复。`, '删除伏笔', { type: 'warning' })
  } catch {
    return
  }
  try {
    await deleteForeshadow(item.id)
    ElMessage.success('已删除')
    await load()
  } catch (error) {
    ElMessage.error((error as Error).message || '删除失败')
  }
}

watch(
  () => [props.novelId, props.active] as const,
  ([, active]) => {
    if (active) {
      void load()
    }
  },
  { immediate: true }
)
</script>

<style scoped>
.fs-panel {
  padding: 4px 4px 24px;
}

.fs-alert {
  margin-bottom: 12px;
}

.fs-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.fs-item {
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  padding: 12px 14px;
  background: var(--el-bg-color);
}

.fs-item.overdue {
  border-color: var(--el-color-danger-light-5);
  background: var(--el-color-danger-light-9);
}

.fs-item.done {
  opacity: 0.72;
}

.fs-head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.fs-title {
  font-weight: 600;
  font-size: 14px;
}

.fs-content {
  margin: 6px 0 4px;
  font-size: 13px;
  color: var(--el-text-color-regular);
  line-height: 1.6;
}

.fs-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.fs-note {
  font-style: italic;
}

.fs-actions {
  margin-top: 6px;
  display: flex;
  gap: 4px;
}

.form-hint {
  margin-left: 10px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
