<template>
  <div class="panel auto-write">
    <header class="panel-head">
      <div>
        <h2>自动写书</h2>
        <p class="panel-sub">
          配好周期与口径后，系统会按「补总纲 → 当前总纲排大纲 → 逐章写正文 → 每 10 章体检」自动推进；
          一条总纲里的故事讲完才开下一条，进度与内容可在下方「创作进度」里查看。
          开启期间本书只能查看，需停止后才能继续手工编辑。
        </p>
      </div>
      <div class="aw-head-actions">
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        <el-button
          v-if="task"
          type="primary"
          :icon="VideoPlay"
          :disabled="!canRun"
          :loading="running"
          @click="runNow"
        >
          立即执行一轮
        </el-button>
      </div>
    </header>

    <el-alert
      v-if="task?.stoppedReason"
      :type="task.status === 'PAUSED' ? 'warning' : 'info'"
      :closable="false"
      show-icon
      class="aw-alert"
      :title="stopTitle"
      :description="task.stoppedReason"
    />

    <el-card shadow="never" class="aw-card">
      <template #header>
        <div class="aw-card-head">
          <span>定时任务配置</span>
          <el-tag v-if="task" :type="statusTagType" effect="dark" size="small">{{ statusLabel }}</el-tag>
          <el-tag v-else type="info" size="small">未配置</el-tag>
        </div>
      </template>

      <el-form label-width="132px" class="aw-form">
        <el-form-item label="启用自动写书">
          <el-switch v-model="form.enabled" :disabled="saving" />
          <span class="aw-hint">开启后到达周期即自动执行，并锁定本书编辑</span>
        </el-form-item>

        <el-form-item label="执行周期">
          <div class="aw-row">
            <el-input v-model="form.cronExpr" placeholder="秒 分 时 日 月 周，如 0 0 2 * * ?" class="aw-cron" />
            <el-select v-model="cronPreset" placeholder="常用周期" class="aw-preset" @change="applyPreset">
              <el-option
                v-for="item in CRON_PRESETS"
                :key="item.value"
                :label="item.label"
                :value="item.value"
              />
            </el-select>
          </div>
          <div class="aw-hint">6 段式：秒 分 时 日 月 周。例：每天凌晨 2 点 = 0 0 2 * * ?</div>
        </el-form-item>

        <el-form-item label="每次生成章数">
          <el-input-number v-model="form.chaptersPerRun" :min="1" :max="20" :step="1" controls-position="right" />
          <span class="aw-hint">单轮最多写几章，1~20</span>
        </el-form-item>

        <el-form-item label="每章目标字数">
          <el-input-number
            v-model="form.targetWordsPerChapter"
            :min="300"
            :max="6000"
            :step="500"
            controls-position="right"
          />
          <span class="aw-hint">300~6000，默认 3000</span>
        </el-form-item>

        <el-form-item label="完结口径">
          <el-radio-group v-model="form.endMode">
            <el-radio value="CHAPTERS">写满指定章数</el-radio>
            <el-radio value="UNTIL_END">直到写完预计字数</el-radio>
          </el-radio-group>
        </el-form-item>

        <el-form-item v-if="form.endMode === 'CHAPTERS'" label="目标章数">
          <el-input-number v-model="form.endChapterNo" :min="1" :max="100000" :step="10" controls-position="right" />
        </el-form-item>

        <el-form-item v-else label="预计完结字数">
          <span class="aw-readonly">{{ plannedWords > 0 ? `${plannedWords} 字` : '未设置' }}</span>
          <span class="aw-hint">
            取自「基本信息 · 预计完结字数」；写到距目标 1 万字时触发一次收尾评估
          </span>
        </el-form-item>

        <el-form-item label="体检硬伤阈值">
          <el-input-number v-model="form.hardIssueLimit" :min="0" :max="99" :step="1" controls-position="right" />
          <span class="aw-hint">每 10 章体检一次，硬伤数超过该值即停止任务，默认 5</span>
        </el-form-item>

        <el-form-item label="体检 AI 深检">
          <el-switch v-model="form.consistencyAi" :disabled="saving" />
          <span class="aw-hint">开启走 AI 深检（更准、耗 token）；关闭只用规则层</span>
        </el-form-item>
      </el-form>

      <div class="aw-actions">
        <el-button type="primary" :loading="saving" @click="save">保存配置</el-button>
        <el-button v-if="task" :loading="toggling" @click="toggleEnabled">
          {{ task.enabled ? '停止并解锁编辑' : '启用自动写书' }}
        </el-button>
        <el-button v-if="task" type="danger" plain :loading="removing" @click="remove">删除任务</el-button>
      </div>
    </el-card>

    <el-card v-if="task" shadow="never" class="aw-card">
      <template #header>
        <div class="aw-card-head"><span>运行概览</span></div>
      </template>
      <div class="aw-metrics">
        <div class="aw-metric"><span class="k">运行态</span><span class="v">{{ statusLabel }}</span></div>
        <div class="aw-metric"><span class="k">下次触发</span><span class="v">{{ formatTime(task.nextRunAt) }}</span></div>
        <div class="aw-metric"><span class="k">累计执行</span><span class="v">{{ task.totalRuns }} 轮</span></div>
        <div class="aw-metric"><span class="k">累计成章</span><span class="v">{{ task.generatedChapters }} 章</span></div>
        <div class="aw-metric"><span class="k">最近执行</span><span class="v">{{ formatTime(task.lastRunAt) }}</span></div>
        <div class="aw-metric">
          <span class="k">连续失败</span>
          <span class="v" :class="{ danger: task.consecutiveFailures > 0 }">{{ task.consecutiveFailures }} 次</span>
        </div>
      </div>
      <p v-if="task.lastResult" class="aw-last">最近结果：{{ task.lastResult }}</p>
    </el-card>

    <el-card v-if="task" shadow="never" class="aw-card">
      <template #header>
        <div class="aw-card-head">
          <span>执行流水（最近 50 轮）</span>
          <el-button text size="small" :icon="Refresh" @click="loadRuns">刷新</el-button>
        </div>
      </template>
      <el-table :data="runs" size="small" empty-text="暂无执行记录">
        <el-table-column label="开始时间" width="180">
          <template #default="{ row }">{{ formatTime(row.startedAt) }}</template>
        </el-table-column>
        <el-table-column label="结果" width="96">
          <template #default="{ row }">
            <el-tag size="small" :type="runTagType(row.status)" effect="plain">{{ runLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="chaptersWritten" label="成章" width="70" />
        <el-table-column prop="outlinesCreated" label="补大纲" width="80" />
        <el-table-column prop="masterOutlinesCreated" label="补总纲" width="80" />
        <el-table-column label="硬伤" width="70">
          <template #default="{ row }">{{ row.consistencyHard ?? '—' }}</template>
        </el-table-column>
        <el-table-column prop="message" label="说明" min-width="220" show-overflow-tooltip />
      </el-table>
    </el-card>

    <el-card shadow="never" class="aw-card">
      <template #header>
        <div class="aw-card-head">
          <span>创作进度</span>
          <span class="aw-progress-summary">{{ progressSummary }}</span>
          <el-button text size="small" :icon="Refresh" :loading="progressLoading" @click="loadProgress">
            刷新
          </el-button>
        </div>
      </template>

      <el-table :data="masters" size="small" empty-text="这本书还没有总纲">
        <el-table-column label="#" width="52">
          <template #default="{ row }">{{ row.sortNo }}</template>
        </el-table-column>
        <el-table-column prop="title" label="总纲" min-width="200" show-overflow-tooltip />
        <el-table-column label="状态" width="92">
          <template #default="{ row }">
            <el-tag size="small" :type="masterTagType(row.status)" effect="plain">
              {{ masterLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="大纲数（实际 / 预计）" width="176">
          <template #default="{ row }">
            <span class="aw-outline-count" :class="{ 'is-over': isOverPlanned(row) }">
              {{ row.chapterCount }} / {{ row.plannedOutlines ? row.plannedOutlines : '未规划' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="76">
          <template #default="{ row }">
            <el-button text type="primary" size="small" @click="openMaster(row)">查看</el-button>
          </template>
        </el-table-column>
      </el-table>
      <p class="aw-hint aw-progress-hint">
        一条总纲讲完之后才会开始下一条；「预计」只是规划时的估数，实际会按故事需要多排或少排。
      </p>
    </el-card>

    <el-drawer v-model="masterDrawer" :title="activeMaster?.title || '总纲详情'" size="620px">
      <div v-if="activeMaster" class="aw-master-detail">
        <div class="aw-master-tags">
          <el-tag size="small" :type="masterTagType(activeMaster.status)" effect="plain">
            {{ masterLabel(activeMaster.status) }}
          </el-tag>
          <span class="aw-master-meta">
            预计 {{ activeMaster.plannedOutlines || '未规划' }} 条大纲 · 已排 {{ activeMaster.chapterCount }} 条
          </span>
        </div>
        <div class="aw-master-content">{{ activeMaster.content || '（这条总纲还没有正文）' }}</div>

        <h4 class="aw-outline-title">章节大纲（{{ detailChapters.length }} 条）</h4>
        <div v-for="chapter in detailChapters" :key="chapter.id" class="aw-outline-item">
          <div class="aw-outline-head">
            <span class="aw-outline-no">第 {{ chapter.chapterNo }} 章</span>
            <strong class="aw-outline-name">{{ chapter.title }}</strong>
            <el-tag size="small" :type="chapter.wordCount > 0 ? 'success' : 'info'" effect="plain">
              {{ chapter.wordCount > 0 ? '已写 ' + chapter.wordCount + ' 字' : '待写' }}
            </el-tag>
            <el-button text type="primary" size="small" @click="openChapterDetail(chapter)">详情</el-button>
          </div>
          <p v-if="chapter.summary" class="aw-outline-summary">{{ chapter.summary }}</p>
        </div>
        <el-empty
          v-if="!detailChapters.length"
          description="这条总纲还没有章节大纲"
          :image-size="64"
        />
      </div>
    </el-drawer>

    <!-- 章节大纲详情（只读） -->
    <ChapterOutlineDetailDrawer
      v-model="chapterDrawer"
      :chapter-id="chapterPreview.id"
      :chapter="chapterPreview.row"
      :master-title="chapterPreview.masterTitle"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, VideoPlay } from '@element-plus/icons-vue'
import { autoWriteApi, type AutoWriteRun, type AutoWriteTask, type AutoWriteTaskRequest } from '@/api/autoWrite'
import {
  listChapters,
  listMasterOutlines,
  type ChapterVO,
  type MasterOutlineVO
} from '@/api/novel-asset'
import ChapterOutlineDetailDrawer from './ChapterOutlineDetailDrawer.vue'

const props = defineProps<{ novelId: string; plannedWords: number }>()
const emit = defineEmits<{ (e: 'locked-change', locked: boolean): void }>()

const CRON_PRESETS = [
  { label: '每天 02:00', value: '0 0 2 * * ?' },
  { label: '每天 09:00', value: '0 0 9 * * ?' },
  { label: '每 6 小时', value: '0 0 */6 * * ?' },
  { label: '每小时', value: '0 0 * * * ?' },
  { label: '每 10 分钟', value: '0 */10 * * * ?' }
]

const task = ref<AutoWriteTask | null>(null)
const runs = ref<AutoWriteRun[]>([])
const loading = ref(false)
const saving = ref(false)
const toggling = ref(false)
const removing = ref(false)
const running = ref(false)
const cronPreset = ref('')

/** 创作进度：总纲 + 章节大纲，用于「查看」当前讲到哪儿了 */
const masters = ref<MasterOutlineVO[]>([])
const chapters = ref<ChapterVO[]>([])
const progressLoading = ref(false)
const masterDrawer = ref(false)
const activeMaster = ref<MasterOutlineVO | null>(null)
/** 章节大纲详情（只读）：画面上已有数据，直接传给抽屉不用再请求 */
const chapterDrawer = ref(false)
const chapterPreview = reactive<{
  id: string
  row: ChapterVO | null
  masterTitle: string
}>({ id: '', row: null, masterTitle: '' })

const form = reactive<AutoWriteTaskRequest>({
  enabled: false,
  cronExpr: '0 0 2 * * ?',
  chaptersPerRun: 3,
  endMode: 'CHAPTERS',
  endChapterNo: 30,
  hardIssueLimit: 5,
  consistencyAi: true,
  targetWordsPerChapter: 3000
})

const canRun = computed(() => !!task.value && (task.value.status === 'IDLE' || task.value.status === 'RUNNING')
  && task.value.enabled)

const STATUS_LABEL: Record<string, string> = {
  IDLE: '等待触发',
  RUNNING: '正在生成',
  PAUSED: '已暂停（需人工介入）',
  STOPPED: '已停止',
  COMPLETED: '已完成'
}
const statusLabel = computed(() => (task.value ? STATUS_LABEL[task.value.status] || task.value.status : '未配置'))
const statusTagType = computed(() => {
  switch (task.value?.status) {
    case 'RUNNING':
      return 'primary'
    case 'PAUSED':
      return 'warning'
    case 'COMPLETED':
      return 'success'
    case 'STOPPED':
      return 'info'
    default:
      return 'success'
  }
})
const stopTitle = computed(() => (task.value?.status === 'PAUSED' ? '任务已暂停，需人工介入' : '任务已停止'))

const MASTER_STATUS_LABEL: Record<string, string> = {
  PENDING: '未开始',
  RUNNING: '进行中',
  DONE: '已完成'
}

function masterLabel(status?: string) {
  return MASTER_STATUS_LABEL[status || 'PENDING'] || status || '未开始'
}

function masterTagType(status?: string) {
  if (status === 'RUNNING') return 'primary'
  if (status === 'DONE') return 'success'
  return 'info'
}

/** 实际大纲数超出规划值：不是错误，只是提示「这段比预想的长」 */
function isOverPlanned(row: MasterOutlineVO) {
  return !!row.plannedOutlines && row.chapterCount > (row.plannedOutlines || 0)
}

/** 抽屉里展示的章节大纲：只属于当前这条总纲 */
const detailChapters = computed(() => {
  const current = activeMaster.value
  if (!current) return []
  return chapters.value.filter((item) => item.masterOutlineId === current.id)
})

const progressSummary = computed(() => {
  if (!masters.value.length) return '暂无总纲'
  const done = masters.value.filter((item) => item.status === 'DONE').length
  const current = masters.value.find((item) => item.status !== 'DONE')
  const tail = current ? `当前：${current.title}` : '全部总纲已讲完'
  return `共 ${masters.value.length} 条 · 已完成 ${done} 条 · ${tail}`
})

async function loadProgress(silent = false) {
  if (!props.novelId) return
  progressLoading.value = true
  try {
    const [masterList, chapterList] = await Promise.all([
      listMasterOutlines(props.novelId),
      listChapters(props.novelId)
    ])
    masters.value = masterList || []
    chapters.value = chapterList || []
    const current = activeMaster.value
    if (current) {
      const fresh = masters.value.find((item) => item.id === current.id)
      activeMaster.value = fresh || null
      if (!fresh) masterDrawer.value = false
    }
  } catch (error) {
    if (!silent) {
      ElMessage.error((error as Error).message || '加载创作进度失败')
    }
  } finally {
    progressLoading.value = false
  }
}

function openMaster(row: MasterOutlineVO) {
  activeMaster.value = row
  masterDrawer.value = true
}

/** 打开某条章节大纲的只读详情 */
function openChapterDetail(chapter: ChapterVO) {
  chapterPreview.id = chapter.id
  chapterPreview.row = chapter
  chapterPreview.masterTitle = activeMaster.value?.title || ''
  chapterDrawer.value = true
}

function applyPreset(value: string) {
  if (value) {
    form.cronExpr = value
    cronPreset.value = ''
  }
}

function fillForm(src: AutoWriteTask) {
  form.enabled = src.enabled
  form.cronExpr = src.cronExpr
  form.chaptersPerRun = src.chaptersPerRun
  form.endMode = src.endMode
  form.endChapterNo = src.endChapterNo ?? 30
  form.hardIssueLimit = src.hardIssueLimit
  form.consistencyAi = src.consistencyAi
  form.targetWordsPerChapter = src.targetWordsPerChapter
}

async function load() {
  if (!props.novelId) return
  loading.value = true
  try {
    const data = await autoWriteApi.getByNovel(props.novelId)
    task.value = data
    if (data) {
      fillForm(data)
      emit('locked-change', data.autoWriting)
      await loadRuns()
    } else {
      emit('locked-change', false)
      runs.value = []
    }
  } catch (error) {
    ElMessage.error((error as Error).message || '加载自动写书配置失败')
  } finally {
    loading.value = false
  }
}

async function loadRuns() {
  if (!task.value) return
  try {
    runs.value = await autoWriteApi.runs(task.value.id)
  } catch {
    runs.value = []
  }
}

async function save() {
  if (form.endMode === 'UNTIL_END' && props.plannedWords <= 0) {
    ElMessage.warning('请先在「基本信息」里填写预计完结字数')
    return
  }
  if (form.endMode === 'CHAPTERS' && !form.endChapterNo) {
    ElMessage.warning('请填写目标章数')
    return
  }
  saving.value = true
  try {
    const data = await autoWriteApi.save(props.novelId, { ...form })
    task.value = data
    fillForm(data)
    emit('locked-change', data.autoWriting)
    await loadRuns()
    ElMessage.success(data.enabled ? '已保存，自动写书已开启' : '已保存')
  } catch (error) {
    ElMessage.error((error as Error).message || '保存失败')
  } finally {
    saving.value = false
  }
}

async function toggleEnabled() {
  if (!task.value) return
  toggling.value = true
  try {
    const data = await autoWriteApi.setEnabled(task.value.id, !task.value.enabled)
    task.value = data
    fillForm(data)
    emit('locked-change', data.autoWriting)
    ElMessage.success(data.enabled ? '已启用自动写书' : '已停止，可以继续编辑本书')
  } catch (error) {
    ElMessage.error((error as Error).message || '操作失败')
  } finally {
    toggling.value = false
  }
}

async function runNow() {
  if (!task.value) return
  running.value = true
  try {
    await autoWriteApi.runNow(task.value.id)
    ElMessage.success('已触发一轮，稍后刷新查看结果')
    setTimeout(() => {
      load()
      loadProgress()
    }, 3000)
  } catch (error) {
    ElMessage.error((error as Error).message || '触发失败')
  } finally {
    running.value = false
  }
}

async function remove() {
  if (!task.value) return
  try {
    await ElMessageBox.confirm('删除后不再自动写书，已生成的内容会保留，确定删除？', '删除定时任务', {
      type: 'warning'
    })
  } catch {
    return
  }
  removing.value = true
  try {
    await autoWriteApi.remove(task.value.id)
    task.value = null
    runs.value = []
    emit('locked-change', false)
    ElMessage.success('已删除')
  } catch (error) {
    ElMessage.error((error as Error).message || '删除失败')
  } finally {
    removing.value = false
  }
}

function runTagType(status: string) {
  if (status === 'SUCCEEDED') return 'success'
  if (status === 'FAILED') return 'danger'
  return 'info'
}

function runLabel(status: string) {
  if (status === 'SUCCEEDED') return '成功'
  if (status === 'FAILED') return '失败'
  if (status === 'SKIPPED') return '跳过'
  return status
}

function formatTime(value?: string | null) {
  if (!value) return '—'
  return value.replace('T', ' ').slice(0, 19)
}

watch(
  () => props.novelId,
  () => {
    load()
    loadProgress()
  },
  { immediate: true }
)

// 任务在跑时进度会自动变，静默轮询一下，省得盯着手动刷新
let progressTimer: ReturnType<typeof setInterval> | null = null
watch(
  () => task.value?.status,
  (status) => {
    if (progressTimer) {
      clearInterval(progressTimer)
      progressTimer = null
    }
    if (status === 'RUNNING') {
      progressTimer = setInterval(() => loadProgress(true), 15000)
    }
  }
)

onUnmounted(() => {
  if (progressTimer) {
    clearInterval(progressTimer)
    progressTimer = null
  }
})

defineExpose({ reload: load })
</script>

<style scoped>
.auto-write {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.aw-head-actions {
  display: flex;
  gap: 8px;
}

.aw-alert {
  margin-bottom: 4px;
}

.aw-card {
  border: 1px solid var(--el-border-color-lighter);
}

.aw-card-head {
  display: flex;
  align-items: center;
  gap: 10px;
  font-weight: 600;
}

.aw-form {
  max-width: 760px;
}

.aw-row {
  display: flex;
  gap: 10px;
  width: 100%;
}

.aw-cron {
  max-width: 260px;
}

.aw-preset {
  width: 160px;
}

.aw-hint {
  margin-left: 10px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.aw-readonly {
  font-weight: 600;
}

.aw-actions {
  display: flex;
  gap: 10px;
  margin-top: 8px;
}

.aw-metrics {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(160px, 1fr));
  gap: 12px;
}

.aw-metric {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 10px 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
}

.aw-metric .k {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.aw-metric .v {
  font-weight: 600;
}

.aw-metric .v.danger {
  color: var(--el-color-danger);
}

.aw-last {
  margin: 12px 0 0;
  color: var(--el-text-color-regular);
  font-size: 13px;
}

/* 创作进度：总纲列表 + 查看抽屉 */
.aw-progress-summary {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  font-weight: 400;
}

.aw-outline-count {
  font-variant-numeric: tabular-nums;
}

.aw-outline-count.is-over {
  color: var(--el-color-warning);
}

.aw-progress-hint {
  display: block;
  margin: 10px 0 0;
  line-height: 1.6;
}

.aw-master-detail {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.aw-master-tags {
  display: flex;
  align-items: center;
  gap: 10px;
}

.aw-master-meta {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.aw-master-content {
  padding: 12px;
  border-radius: 8px;
  background: var(--el-fill-color-lighter);
  color: var(--el-text-color-primary);
  font-size: 14px;
  line-height: 1.8;
  white-space: pre-wrap;
}

.aw-outline-title {
  margin: 8px 0 0;
  font-size: 14px;
  font-weight: 600;
}

.aw-outline-item {
  padding: 10px 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
}

.aw-outline-head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.aw-outline-no {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  white-space: nowrap;
}

.aw-outline-name {
  flex: 1;
  font-size: 14px;
}

.aw-outline-summary {
  margin: 6px 0 0;
  color: var(--el-text-color-regular);
  font-size: 13px;
  line-height: 1.7;
  white-space: pre-wrap;
}
</style>
