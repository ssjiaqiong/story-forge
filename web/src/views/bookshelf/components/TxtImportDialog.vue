<template>
  <el-dialog
    v-model="visible"
    title="整本导入"
    width="760px"
    :close-on-click-modal="false"
    @closed="onClosed"
  >
    <div class="ti-body">
      <!-- 步骤 1：选文件 -->
      <template v-if="!task">
        <p class="ti-lead">
          把一本只剩正文的 TXT 导进来：原文按章<b>完整落库</b>（一个字不丢），随后 AI 逆推出这本书的世界观、人物、物品、势力、总纲与逐章大纲，导完就能直接续写。
        </p>
        <el-upload
          drag
          :auto-upload="false"
          :show-file-list="false"
          :limit="1"
          accept=".txt,.md,.text"
          :on-change="onFileChange"
          :on-exceed="onExceed"
          class="ti-upload"
        >
          <div class="ti-drop">
            <div class="ti-drop-title">{{ file ? file.name : '把 TXT 拖到这里，或点击选择' }}</div>
            <div class="ti-drop-sub">
              {{ file ? formatSize(file.size) + ' · 已选择，点下方「开始导入」' : '支持 .txt / .md，UTF-8 与 GBK 都能自动识别' }}
            </div>
          </div>
        </el-upload>

        <el-form label-width="72px" class="ti-form">
          <el-form-item label="书名">
            <el-input v-model="title" maxlength="40" clearable placeholder="留空则自动从文件名或正文推断" />
          </el-form-item>
          <el-form-item label="笔名">
            <el-input v-model="penName" maxlength="20" clearable placeholder="选填" />
          </el-form-item>
        </el-form>

        <div class="ti-hint">
          <span>导入耗时取决于篇幅：30 万字约 2~4 分钟，100 万字约 6~10 分钟。期间可以关掉这个窗口，回来在「导入记录」里继续看。</span>
        </div>
      </template>

      <!-- 步骤 2：进度 -->
      <template v-else>
        <div class="ti-head">
          <div class="ti-head-main">
            <div class="ti-title">{{ task.title || task.sourceName }}</div>
            <div class="ti-meta">
              <span v-if="task.sourceChars">{{ formatWan(task.sourceChars) }}</span>
              <span v-if="task.chapterCount">· 识别 {{ task.chapterCount }} 章</span>
              <span v-if="task.importedChapters">· 已入库 {{ task.importedChapters }} 章</span>
            </div>
          </div>
          <el-tag :type="statusType" size="small" effect="plain">{{ statusLabel }}</el-tag>
        </div>

        <el-progress
          :percentage="task.percent || 0"
          :status="task.status === 'FAILED' ? 'exception' : task.status === 'SUCCEEDED' ? 'success' : undefined"
          :stroke-width="10"
          class="ti-progress"
        />
        <div class="ti-step">{{ task.stepLabel || '准备中' }}</div>

        <el-alert
          v-if="task.status === 'FAILED'"
          type="error"
          :closable="false"
          show-icon
          class="ti-alert"
          title="分析中断"
          :description="task.errorMessage || '请重试'"
        />

        <!-- 完成 -->
        <template v-if="task.status === 'SUCCEEDED'">
          <div class="ti-stat">
            <div class="ti-stat-item"><b>{{ task.importedChapters || 0 }}</b><span>章正文</span></div>
            <div class="ti-stat-item"><b>{{ task.summary?.characters || 0 }}</b><span>人物</span></div>
            <div class="ti-stat-item"><b>{{ task.summary?.items || 0 }}</b><span>物品</span></div>
            <div class="ti-stat-item"><b>{{ task.summary?.factions || 0 }}</b><span>势力</span></div>
            <div class="ti-stat-item"><b>{{ task.summary?.chapterOutlines || 0 }}</b><span>章大纲</span></div>
          </div>
          <div class="ti-hint">
            <span v-for="(note, i) in task.summary?.notes || []" :key="i">{{ note }}　</span>
          </div>
        </template>

        <div v-if="task.status === 'FAILED' && task.novelId" class="ti-hint warn">
          正文已经入库，这本书可以直接打开编辑；只差 AI 档案没补完，可以点「继续分析」再跑一次。
        </div>
      </template>

      <!-- 导入记录 -->
      <div v-if="records.length" class="ti-records">
        <div class="ti-records-head">
          <span>最近导入</span>
          <el-button link size="small" :loading="recordLoading" @click="loadRecords">刷新</el-button>
        </div>
        <div v-for="item in records" :key="item.taskId" class="ti-record">
          <div class="ti-record-main">
            <span class="ti-record-title">{{ item.title || item.sourceName }}</span>
            <span class="ti-record-meta">{{ formatWan(item.sourceChars) }} · {{ item.chapterCount || 0 }} 章</span>
          </div>
          <el-tag size="small" :type="item.status === 'SUCCEEDED' ? 'success' : item.status === 'FAILED' ? 'danger' : 'warning'" effect="plain">
            {{ item.status === 'SUCCEEDED' ? '已完成' : item.status === 'FAILED' ? '失败' : '进行中' }}
          </el-tag>
          <el-button v-if="item.status !== 'RUNNING'" link size="small" @click="openRecord(item)">查看</el-button>
          <el-button link size="small" @click="removeRecord(item)">删除</el-button>
        </div>
      </div>
    </div>

    <template #footer>
      <template v-if="!task">
        <el-button @click="visible = false">取消</el-button>
        <el-button type="primary" :disabled="!file" :loading="starting" @click="onStart">开始导入</el-button>
      </template>
      <template v-else>
        <el-button v-if="task.status === 'RUNNING'" @click="visible = false">后台运行</el-button>
        <el-button v-if="task.status === 'FAILED' && task.novelId" :loading="retrying" @click="onRetry">继续分析</el-button>
        <el-button v-if="task.novelId" @click="openNovel">打开这本书</el-button>
        <el-button type="primary" @click="backToPick">{{ task.novelId ? '再导入一本' : '重新选择文件' }}</el-button>
      </template>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { UploadFile, UploadRawFile } from 'element-plus'
import {
  deleteImport,
  getImportTask,
  listImports,
  retryImport,
  startImport,
  type ImportRecordVO,
  type ImportTaskVO
} from '@/api/import'

const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{ (e: 'update:modelValue', value: boolean): void; (e: 'saved', novelId: string): void }>()

const router = useRouter()

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value)
})

const file = ref<File | null>(null)
const title = ref('')
const penName = ref('')
const starting = ref(false)
const retrying = ref(false)
const task = ref<ImportTaskVO | null>(null)
const records = ref<ImportRecordVO[]>([])
const recordLoading = ref(false)

let timer: number | undefined

const statusLabel = computed(() => {
  if (!task.value) {
    return ''
  }
  if (task.value.status === 'SUCCEEDED') {
    return '已完成'
  }
  if (task.value.status === 'FAILED') {
    return '失败'
  }
  return '进行中'
})

const statusType = computed(() => {
  if (!task.value) {
    return 'info'
  }
  if (task.value.status === 'SUCCEEDED') {
    return 'success'
  }
  if (task.value.status === 'FAILED') {
    return 'danger'
  }
  return 'warning'
})

watch(
  () => props.modelValue,
  (open) => {
    if (open) {
      void loadRecords()
    }
  }
)

function onFileChange(uploadFile: UploadFile) {
  const raw = uploadFile.raw as UploadRawFile | undefined
  if (!raw) {
    return
  }
  const name = raw.name.toLowerCase()
  if (!name.endsWith('.txt') && !name.endsWith('.md') && !name.endsWith('.text')) {
    ElMessage.warning('只支持 txt / md 纯文本文件')
    return
  }
  if (raw.size > 50 * 1024 * 1024) {
    ElMessage.warning('文件超过 50MB，请先拆分再导入')
    return
  }
  file.value = raw
}

function onExceed() {
  ElMessage.info('一次只能导入一个文件')
}

async function onStart() {
  if (!file.value) {
    return
  }
  starting.value = true
  try {
    const created = await startImport({
      file: file.value,
      title: title.value.trim() || undefined,
      penName: penName.value.trim() || undefined
    })
    task.value = created
    startPolling()
    void loadRecords()
  } catch (error) {
    ElMessage.error((error as Error)?.message || '导入启动失败')
  } finally {
    starting.value = false
  }
}

function startPolling() {
  stopPolling()
  timer = window.setInterval(async () => {
    if (!task.value) {
      return
    }
    try {
      const next = await getImportTask(task.value.taskId)
      task.value = next
      if (next.status !== 'RUNNING') {
        stopPolling()
        void loadRecords()
        if (next.status === 'SUCCEEDED') {
          ElMessage.success('导入完成，可以开始续写了')
          emit('saved', String(next.novelId || ''))
        }
      }
    } catch {
      stopPolling()
    }
  }, 1500)
}

function stopPolling() {
  if (timer) {
    window.clearInterval(timer)
    timer = undefined
  }
}

async function onRetry() {
  if (!task.value) {
    return
  }
  retrying.value = true
  try {
    task.value = await retryImport(task.value.taskId)
    startPolling()
  } catch (error) {
    ElMessage.error((error as Error)?.message || '重试失败')
  } finally {
    retrying.value = false
  }
}

function openNovel() {
  if (!task.value?.novelId) {
    return
  }
  visible.value = false
  void router.push(`/novels/${task.value.novelId}`)
}

function backToPick() {
  stopPolling()
  task.value = null
  file.value = null
  title.value = ''
  penName.value = ''
}

async function openRecord(item: ImportRecordVO) {
  try {
    task.value = await getImportTask(item.taskId)
    if (task.value.status === 'RUNNING') {
      startPolling()
    }
  } catch (error) {
    ElMessage.error((error as Error)?.message || '记录读取失败')
  }
}

async function removeRecord(item: ImportRecordVO) {
  try {
    await ElMessageBox.confirm(
      `删除导入记录「${item.title || item.sourceName}」？已生成的书会保留。`,
      '删除导入记录',
      { type: 'warning' }
    )
  } catch {
    return
  }
  try {
    await deleteImport(item.taskId)
    ElMessage.success('已删除')
    if (task.value?.taskId === item.taskId) {
      backToPick()
    }
    void loadRecords()
  } catch (error) {
    ElMessage.error((error as Error)?.message || '删除失败')
  }
}

async function loadRecords() {
  recordLoading.value = true
  try {
    const page = await listImports(1, 5)
    records.value = page.records || []
  } catch {
    records.value = []
  } finally {
    recordLoading.value = false
  }
}

function onClosed() {
  stopPolling()
  if (task.value && task.value.status !== 'RUNNING') {
    task.value = null
    file.value = null
  }
}

onBeforeUnmount(stopPolling)

function formatSize(size: number) {
  if (size >= 1024 * 1024) {
    return (size / 1024 / 1024).toFixed(1) + ' MB'
  }
  return Math.max(1, Math.round(size / 1024)) + ' KB'
}

function formatWan(chars?: number) {
  if (!chars) {
    return '—'
  }
  return chars >= 10000 ? (chars / 10000).toFixed(1) + ' 万字' : chars + ' 字'
}
</script>

<style scoped>
.ti-body {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.ti-lead {
  margin: 0;
  font-size: 13px;
  line-height: 1.7;
  color: var(--el-text-color-regular);
}

.ti-upload :deep(.el-upload-dragger) {
  padding: 22px 16px;
  border-radius: 10px;
}

.ti-drop-title {
  font-size: 14px;
  color: var(--el-text-color-primary);
}

.ti-drop-sub {
  margin-top: 6px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.ti-form {
  margin-top: 4px;
}

.ti-hint {
  font-size: 12px;
  line-height: 1.7;
  color: var(--el-text-color-secondary);
}

.ti-hint.warn {
  color: var(--el-color-warning);
}

.ti-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.ti-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--el-text-color-primary);
  word-break: break-all;
}

.ti-meta {
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.ti-progress {
  margin-top: 4px;
}

.ti-step {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.ti-alert {
  margin-top: 2px;
}

.ti-stat {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: 10px;
  margin-top: 4px;
}

.ti-stat-item {
  padding: 10px 8px;
  border-radius: 10px;
  background: var(--el-fill-color-lighter);
  text-align: center;
}

.ti-stat-item b {
  display: block;
  font-size: 18px;
  color: var(--el-text-color-primary);
}

.ti-stat-item span {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.ti-records {
  margin-top: 6px;
  border-top: 1px dashed var(--el-border-color-lighter);
  padding-top: 10px;
}

.ti-records-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-bottom: 6px;
}

.ti-record {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 0;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.ti-record:last-child {
  border-bottom: none;
}

.ti-record-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.ti-record-title {
  font-size: 13px;
  color: var(--el-text-color-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ti-record-meta {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
