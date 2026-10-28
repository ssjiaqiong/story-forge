<template>
  <div class="panel auto-write">
    <header class="panel-head">
      <div>
        <h2>自动写书</h2>
        <p class="panel-sub">
          配好周期与口径后，系统会按「补总纲 → 补大纲 → 逐章写正文 → 每 10 章体检」的顺序自动推进；
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
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, VideoPlay } from '@element-plus/icons-vue'
import { autoWriteApi, type AutoWriteRun, type AutoWriteTask, type AutoWriteTaskRequest } from '@/api/autoWrite'

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

watch(() => props.novelId, load, { immediate: true })

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
</style>
