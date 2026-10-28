<template>
  <el-drawer
    v-model="visible"
    class="agent-drawer"
    :title="`智能助手 · ${novel?.title || ''}`"
    :size="drawerSize"
    direction="rtl"
    :destroy-on-close="false"
    :close-on-click-modal="false"
  >
    <div class="agent-shell">
      <div ref="resizeHandle" class="resize-handle" @mousedown.prevent="onResizeStart" />
      <div class="agent-main">
      <div v-if="plan" class="aplan" :class="`aplan-${plan.status.toLowerCase()}`">
        <div class="aplan-head">
          <span class="aplan-title">任务计划</span>
          <span class="aplan-status">{{ planStatusLabel(plan.status) }}</span>
          <span class="aplan-spacer" />
          <el-button v-if="planRunning" size="small" text @click="onPausePlan">暂停</el-button>
          <el-button v-if="planCanResume" size="small" text type="primary" @click="onResumePlan">继续</el-button>
          <el-button v-if="planRunning || planCanResume" size="small" text type="danger" @click="onCancelPlan">
            取消
          </el-button>
        </div>
        <div class="aplan-goal">{{ plan.goal }}</div>
        <div class="aplan-progress">
          <div class="aplan-bar"><div class="aplan-bar-fill" :style="{ width: `${plan.percent}%` }" /></div>
          <span class="aplan-count">
            {{ plan.stepTotal ? `${plan.stepDone} / ${plan.stepTotal} 步 · ${plan.percent}%` : '拆解中…' }}
          </span>
        </div>
        <div v-if="plan.pausedReason" class="aplan-note">{{ plan.pausedReason }}</div>
        <div v-if="plan.errorMessage" class="aplan-note is-error">{{ plan.errorMessage }}</div>
        <ol v-if="plan.steps.length" class="aplan-steps">
          <li v-for="step in plan.steps" :key="step.id" class="aplan-step" :class="`is-${step.status.toLowerCase()}`">
            <span class="aplan-mark">{{ stepMark(step.status) }}</span>
            <div class="aplan-step-body">
              <div class="aplan-step-title">{{ step.stepNo }}. {{ step.title }}</div>
              <div v-if="step.reply" class="aplan-step-reply">{{ step.reply }}</div>
              <div v-if="step.errorMessage" class="aplan-step-error">{{ step.errorMessage }}</div>
            </div>
          </li>
        </ol>
      </div>

      <div ref="scrollRef" v-loading="loading" class="agent-list">
        <div v-if="!loading && messages.length === 0" class="agent-empty">
          <div class="agent-empty-mark">助手</div>
          <p class="agent-empty-title">告诉我你想改什么</p>
          <p class="agent-empty-desc">
            例如：把世界观里主角的能力改得更克制、删掉「张三」这个角色、把「修书刀」改为主角的传家宝、
            让「沈默」和「林晚」从好友变成宿敌。
          </p>
          <div class="agent-starters">
            <button v-for="text in starters" :key="text" type="button" class="agent-starter" @click="useStarter(text)">
              {{ text }}
            </button>
          </div>
        </div>

        <div
          v-for="item in messages"
          :key="item.id"
          class="amsg"
          :class="item.role === 'USER' ? 'from-user' : 'from-ai'"
        >
          <div class="amsg-avatar" :class="item.role === 'USER' ? 'avatar-user' : 'avatar-ai'">
            {{ item.role === 'USER' ? '我' : 'AI' }}
          </div>
          <div class="amsg-body">
            <div class="abubble" :class="{ 'abubble-failed': item.status === 'FAILED' }">
              <div v-if="item.status === 'FAILED'" class="afailed">{{ item.errorMessage || 'AI 调用失败，请重试' }}</div>
              <div v-else class="abubble-text">{{ item.content }}</div>
            </div>
            <div v-if="item.operations && item.operations.length" class="aops">
              <div class="aops-title">已执行 {{ item.operations.length }} 项修改</div>
              <div v-for="(op, index) in item.operations" :key="index" class="aop" :class="op.success ? 'op-ok' : 'op-fail'">
                <span class="aop-dot" />
                <span class="aop-type">{{ labelOf(op.type) }}</span>
                <span v-if="op.target" class="aop-target">{{ op.target }}</span>
                <span v-if="!op.success && op.message" class="aop-msg">{{ op.message }}</span>
              </div>
            </div>
          </div>
        </div>

        <div v-if="sending" class="amsg from-ai">
          <div class="amsg-avatar avatar-ai">AI</div>
          <div class="amsg-body">
            <div class="abubble aprogress">
              <div class="aprogress-head">
                <span class="aprogress-spinner" />
                <span class="aprogress-label">{{ running?.stepLabel || '正在处理…' }}</span>
              </div>
              <div v-if="running && running.turnNo" class="aprogress-step">
                第 {{ running.turnNo }} / {{ running.turnTotal }} 轮
              </div>
              <div v-if="running && running.operations && running.operations.length" class="aops">
                <div class="aops-title">已执行 {{ running.operations.length }} 项修改</div>
                <div v-for="(op, index) in running.operations" :key="index" class="aop" :class="op.success ? 'op-ok' : 'op-fail'">
                  <span class="aop-dot" />
                  <span class="aop-type">{{ labelOf(op.type) }}</span>
                  <span v-if="op.target" class="aop-target">{{ op.target }}</span>
                  <span v-if="!op.success && op.message" class="aop-msg">{{ op.message }}</span>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>

      <footer class="acomposer">
        <el-input
          v-model="draft"
          type="textarea"
          :rows="3"
          resize="none"
          maxlength="8000"
          placeholder="说说你想改什么…（Enter 发送，Shift + Enter 换行）"
          @keydown="onKeydown"
        />
        <div class="acomposer-bar">
          <label class="acomposer-mode">
            <el-switch v-model="planMode" size="small" />
            <span>分步执行</span>
          </label>
          <el-button text :disabled="messages.length === 0" @click="onClear">清空会话</el-button>
          <div class="acomposer-actions">
            <el-button v-if="sending" type="danger" plain @click="onStop">停止</el-button>
            <el-button type="primary" :loading="sending" :disabled="!draft.trim() || sending" @click="onSend">
              发送
            </el-button>
          </div>
        </div>
      </footer>
      </div>
    </div>
  </el-drawer>
</template>

<script setup lang="ts">
import { computed, nextTick, onUnmounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  AGENT_OPERATION_LABELS,
  AGENT_PLAN_STATUS_LABELS,
  cancelAgentPlan,
  cancelAgentTask,
  clearAgentSession,
  getAgentPlan,
  getAgentSession,
  getAgentTask,
  getLatestAgentPlan,
  getRunningAgentTask,
  pauseAgentPlan,
  resumeAgentPlan,
  startAgentPlan,
  startAgentTask,
  type AgentMessage,
  type AgentOperation,
  type AgentPlan,
  type AgentPlanStatus,
  type AgentPlanStepStatus,
  type AgentTask
} from '@/api/agent'

const props = defineProps<{
  modelValue: boolean
  novelId: string
  novelTitle?: string
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'edited'): void
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value)
})

const novel = computed(() => ({ title: props.novelTitle || '' }))

const loading = ref(false)
const sending = ref(false)
const draft = ref('')
const messages = ref<AgentMessage[]>([])
const scrollRef = ref<HTMLElement | null>(null)

// 进行中的任务：实时展示当前轮次与已执行操作
const running = ref<AgentTask | null>(null)
let pollTimer: number | null = null
let currentTaskId: string | null = null

/**
 * 任务计划（分步执行）：
 * 关掉时走老的「一次指令 6 轮」路径，打开时先拆步骤再连续执行，进度可跟踪。
 * 默认打开——复杂指令（重建总纲、批量挂大纲）必须靠它才能跑完。
 */
const planMode = ref(true)
const plan = ref<AgentPlan | null>(null)
let planTimer: number | null = null

const planRunning = computed(() => plan.value?.status === 'RUNNING' || plan.value?.status === 'PLANNING')
const planCanResume = computed(() => plan.value?.status === 'PAUSED' || plan.value?.status === 'FAILED')

// 抽屉可拖拽拉宽
const drawerSize = ref('460px')
const MIN_WIDTH = 420
const MAX_WIDTH = 900
const resizeHandle = ref<HTMLElement | null>(null)
let resizeDragging = false

const starters = [
  '把世界观里主角的能力改得更克制，写明代价',
  '删掉「张三」这个角色',
  '把「修书刀」改为主角的传家宝',
  '让「沈默」和「林晚」从好友变成宿敌'
]

watch(
  () => props.modelValue,
  (open) => {
    if (open) {
      void loadSession()
    }
  }
)

async function loadSession() {
  loading.value = true
  try {
    const session = await getAgentSession(props.novelId)
    messages.value = session.messages ?? []
  } catch (error) {
    ElMessage.error((error as Error).message || '加载助手会话失败')
  } finally {
    loading.value = false
    await scrollToBottom()
    // 刷新后若有进行中的任务，恢复轮询（不阻塞会话加载，否则 loading 遮罩会一直转）
    void resumeRunningTask()
    void loadLatestPlan()
  }
}

/** 刷新后恢复：查询该小说是否有正在执行的任务，有则继续轮询。 */
async function resumeRunningTask() {
  if (sending.value) {
    return
  }
  try {
    const task = await getRunningAgentTask(props.novelId)
    if (!task || task.status !== 'RUNNING') {
      return
    }
    currentTaskId = task.taskId
    running.value = task
    sending.value = true
    await scrollToBottom()
    await pollUntilDone(task.taskId)
  } catch {
    // 无进行中任务或查询失败，静默忽略
  } finally {
    stopPolling()
    running.value = null
    currentTaskId = null
    sending.value = false
    await scrollToBottom()
  }
}

function useStarter(text: string) {
  draft.value = text
}

function labelOf(type: string) {
  return AGENT_OPERATION_LABELS[type] || type
}

function onKeydown(event: KeyboardEvent) {
  if (event.key !== 'Enter' || event.shiftKey || event.isComposing) {
    return
  }
  event.preventDefault()
  void onSend()
}

async function onSend() {
  const text = draft.value.trim()
  if (!text || sending.value) {
    return
  }
  draft.value = ''
  sending.value = true

  const tempId = `temp-${Date.now()}`
  messages.value.push({ id: tempId, role: 'USER', content: text, status: 'OK' })
  await scrollToBottom()

  try {
    if (planMode.value) {
      // 分步执行：先规划再连续跑，不需要用户反复说「继续」
      const started = await startAgentPlan(props.novelId, text)
      plan.value = started
      await pollPlan(started.id)
      return
    }
    // 老路径：启动后台多步任务，拿到 taskId 后轮询进度，实时感知中间过程
    const task = await startAgentTask(props.novelId, text)
    currentTaskId = task.taskId
    running.value = task
    await scrollToBottom()
    await pollUntilDone(task.taskId)
  } catch (error) {
    messages.value = messages.value.filter((item) => item.id !== tempId)
    draft.value = text
    ElMessage.error((error as Error).message || '发送失败')
  } finally {
    stopPolling()
    running.value = null
    currentTaskId = null
    sending.value = false
    await scrollToBottom()
  }
}

/* ---------------- 任务计划 ---------------- */

function planStatusLabel(status: AgentPlanStatus) {
  return AGENT_PLAN_STATUS_LABELS[status] || status
}

function stepMark(status: AgentPlanStepStatus) {
  switch (status) {
    case 'DONE': return '✓'
    case 'RUNNING': return '●'
    case 'FAILED': return '✕'
    case 'SKIPPED': return '—'
    default: return '○'
  }
}

/** 打开抽屉时恢复最近一次计划：进行中的继续轮询，已结束的留作记录。 */
async function loadLatestPlan() {
  try {
    const latest = await getLatestAgentPlan(props.novelId)
    if (!latest) {
      return
    }
    plan.value = latest
    if (latest.status === 'RUNNING' || latest.status === 'PLANNING') {
      sending.value = true
      await pollPlan(latest.id)
      sending.value = false
    }
  } catch {
    // 没有计划或查询失败，静默忽略
  }
}

/** 轮询计划直到进入终态（DONE / FAILED / CANCELLED）或暂停。 */
async function pollPlan(planId: string) {
  return new Promise<void>((resolve) => {
    stopPlanPolling()
    planTimer = window.setInterval(async () => {
      try {
        const latest = await getAgentPlan(props.novelId, planId)
        plan.value = latest
        if (!planRunning.value) {
          stopPlanPolling()
          if (latest.status === 'DONE') {
            ElMessage.success(`任务已完成：${latest.stepDone}/${latest.stepTotal} 步`)
          } else if (latest.status === 'PAUSED') {
            ElMessage.warning(latest.pausedReason || '任务已暂停')
          } else if (latest.status === 'FAILED') {
            ElMessage.error(latest.errorMessage || '任务失败')
          }
          await loadSession()
          emit('edited')
          resolve()
        }
      } catch (error) {
        stopPlanPolling()
        ElMessage.error((error as Error).message || '查询任务进度失败')
        resolve()
      }
    }, 1500)
  })
}

function stopPlanPolling() {
  if (planTimer !== null) {
    window.clearInterval(planTimer)
    planTimer = null
  }
}

async function onPausePlan() {
  if (!plan.value) {
    return
  }
  try {
    plan.value = await pauseAgentPlan(props.novelId, plan.value.id)
    ElMessage.info('已请求暂停，当前步骤做完后停下')
  } catch (error) {
    ElMessage.error((error as Error).message || '暂停失败')
  }
}

async function onResumePlan() {
  if (!plan.value) {
    return
  }
  try {
    plan.value = await resumeAgentPlan(props.novelId, plan.value.id)
    sending.value = true
    await pollPlan(plan.value.id)
    sending.value = false
  } catch (error) {
    ElMessage.error((error as Error).message || '继续失败')
  }
}

async function onCancelPlan() {
  if (!plan.value) {
    return
  }
  try {
    await ElMessageBox.confirm('取消后当前步骤会立刻停下，已经改过的内容不会回滚。', '取消任务', {
      type: 'warning',
      confirmButtonText: '取消任务',
      cancelButtonText: '继续执行'
    })
  } catch {
    return
  }
  try {
    plan.value = await cancelAgentPlan(props.novelId, plan.value.id)
    stopPlanPolling()
    sending.value = false
    ElMessage.info('任务已取消')
  } catch (error) {
    ElMessage.error((error as Error).message || '取消失败')
  }
}

async function onStop() {
  if (!sending.value) {
    return
  }
  // 分步执行时「停止」= 取消计划，当前步结束后不再继续
  if (plan.value && planRunning.value) {
    try {
      await cancelAgentPlan(props.novelId, plan.value.id)
      ElMessage.info('已请求取消，当前步骤结束后停止')
    } catch (error) {
      ElMessage.error((error as Error).message || '取消失败')
    }
    return
  }
  if (!currentTaskId) {
    return
  }
  const taskId = currentTaskId
  try {
    // 停止后台任务；轮询回调里会识别 CANCELLED 状态结束
    await cancelAgentTask(props.novelId, taskId)
    ElMessage.info('已请求停止')
  } catch (error) {
    ElMessage.error((error as Error).message || '停止失败')
  }
}

async function pollUntilDone(taskId: string) {
  return new Promise<void>((resolve) => {
    stopPolling()
    pollTimer = window.setInterval(async () => {
      try {
        const task = await getAgentTask(props.novelId, taskId)
        running.value = task
        await scrollToBottom()
        if (task.status === 'SUCCEEDED') {
          stopPolling()
          applyResult(task)
          resolve()
        } else if (task.status === 'FAILED') {
          stopPolling()
          applyFailure(task)
          resolve()
        } else if (task.status === 'CANCELLED') {
          stopPolling()
          applyCancelled()
          resolve()
        }
      } catch (error) {
        stopPolling()
        ElMessage.error((error as Error).message || '查询执行进度失败')
        resolve()
      }
    }, 800)
  })
}

function applyResult(task: AgentTask) {
  const result = task.result
  if (!result) {
    return
  }
  // 移除临时气泡，并按 id 去重（刷新后恢复轮询时，用户消息可能已从后端读入）
  messages.value = messages.value.filter((item) => !item.id.startsWith('temp-'))
  const existingIds = new Set(messages.value.map((item) => item.id))
  const toPush = [result.userMessage, result.assistantMessage].filter(
    (item) => item && !existingIds.has(item.id)
  )
  messages.value.push(...toPush)
  const changed = result.assistantMessage.operations?.some((op) => op.success)
  if (changed) {
    emit('edited')
  }
  if (result.assistantMessage.status === 'FAILED') {
    ElMessage.warning(result.assistantMessage.errorMessage || 'AI 调用失败')
  }
}

function applyFailure(task: AgentTask) {
  ElMessage.error(task.errorMessage || '执行失败')
}

function applyCancelled() {
  // 取消时后端未落库，移除乐观插入的临时用户气泡
  messages.value = messages.value.filter((item) => !item.id.startsWith('temp-'))
  ElMessage.info('已停止处理')
}

function stopPolling() {
  if (pollTimer !== null) {
    window.clearInterval(pollTimer)
    pollTimer = null
  }
}

async function onClear() {
  try {
    await ElMessageBox.confirm('清空后本小说的助手对话记录会被删除，但已修改的内容不受影响。', '清空会话', {
      type: 'warning',
      confirmButtonText: '清空',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  try {
    await clearAgentSession(props.novelId)
    messages.value = []
    ElMessage.success('会话已清空')
  } catch (error) {
    ElMessage.error((error as Error).message || '清空失败')
  }
}

async function scrollToBottom() {
  await nextTick()
  const el = scrollRef.value
  if (el) {
    el.scrollTop = el.scrollHeight
  }
}

function onResizeStart(event: MouseEvent) {
  event.preventDefault()
  resizeDragging = true
  document.body.style.userSelect = 'none'
  document.body.style.cursor = 'col-resize'
  const onMove = (moveEvent: MouseEvent) => {
    if (!resizeDragging) {
      return
    }
    // direction=rtl 抽屉从右往左展开，宽度 = 视口宽度 - 鼠标 x
    const width = window.innerWidth - moveEvent.clientX
    const clamped = Math.min(MAX_WIDTH, Math.max(MIN_WIDTH, width))
    drawerSize.value = `${clamped}px`
  }
  const onUp = () => {
    resizeDragging = false
    document.body.style.userSelect = ''
    document.body.style.cursor = ''
    document.removeEventListener('mousemove', onMove)
    document.removeEventListener('mouseup', onUp)
  }
  document.addEventListener('mousemove', onMove)
  document.addEventListener('mouseup', onUp)
}

onUnmounted(() => {
  stopPolling()
  stopPlanPolling()
})
</script>

<style scoped>
.resize-handle {
  flex: none;
  width: 10px;
  align-self: stretch;
  cursor: col-resize;
  z-index: 10;
  transition: background 0.16s ease;
}

.resize-handle:hover,
.resize-handle:active {
  background: rgba(59, 110, 246, 0.25);
}

.agent-shell {
  height: 100%;
  display: flex;
  flex-direction: row;
}

.agent-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

/* ── 任务计划卡：规划 / 执行 / 进度 ── */
.aplan {
  flex: none;
  margin: 8px 6px 0;
  padding: 10px 12px;
  border: 1px solid var(--sf-border);
  border-left: 3px solid var(--sf-brand);
  border-radius: 8px;
  background: var(--sf-bg-card);
}

.aplan-done {
  border-left-color: #16a34a;
}

.aplan-paused {
  border-left-color: #d97706;
}

.aplan-failed,
.aplan-cancelled {
  border-left-color: #dc2626;
}

.aplan-head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.aplan-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--sf-text-primary);
}

.aplan-status {
  flex: none;
  padding: 1px 7px;
  border-radius: 999px;
  background: rgba(59, 110, 246, 0.12);
  color: var(--sf-brand);
  font-size: 11px;
  line-height: 1.7;
}

.aplan-running .aplan-status {
  background: rgba(59, 110, 246, 0.12);
  color: var(--sf-brand);
}

.aplan-done .aplan-status {
  background: rgba(22, 163, 74, 0.12);
  color: #16a34a;
}

.aplan-paused .aplan-status {
  background: rgba(217, 119, 6, 0.12);
  color: #d97706;
}

.aplan-failed .aplan-status,
.aplan-cancelled .aplan-status {
  background: rgba(220, 38, 38, 0.1);
  color: #dc2626;
}

.aplan-spacer {
  flex: 1;
}

.aplan-goal {
  margin-top: 6px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--sf-text-regular);
  word-break: break-word;
}

.aplan-progress {
  margin-top: 8px;
  display: flex;
  align-items: center;
  gap: 8px;
}

.aplan-bar {
  flex: 1;
  height: 5px;
  border-radius: 999px;
  background: var(--sf-border);
  overflow: hidden;
}

.aplan-bar-fill {
  height: 100%;
  border-radius: 999px;
  background: var(--sf-brand);
  transition: width 0.3s ease;
}

.aplan-done .aplan-bar-fill {
  background: #16a34a;
}

.aplan-paused .aplan-bar-fill {
  background: #d97706;
}

.aplan-count {
  flex: none;
  font-size: 11px;
  color: var(--sf-text-muted);
  font-variant-numeric: tabular-nums;
}

.aplan-note {
  margin-top: 7px;
  padding: 5px 8px;
  border-radius: 6px;
  background: rgba(217, 119, 6, 0.1);
  color: #b45309;
  font-size: 12px;
  line-height: 1.6;
}

.aplan-note.is-error {
  background: rgba(220, 38, 38, 0.08);
  color: #dc2626;
}

.aplan-steps {
  margin: 8px 0 0;
  padding: 0;
  list-style: none;
  max-height: 32vh;
  overflow-y: auto;
}

.aplan-step {
  display: flex;
  gap: 7px;
  padding: 4px 0;
  border-top: 1px dashed var(--sf-border);
}

.aplan-step:first-child {
  border-top: none;
}

.aplan-mark {
  flex: none;
  width: 14px;
  text-align: center;
  font-size: 11px;
  line-height: 1.7;
  color: var(--sf-text-muted);
}

.aplan-step.is-done .aplan-mark {
  color: #16a34a;
}

.aplan-step.is-running .aplan-mark {
  color: var(--sf-brand);
}

.aplan-step.is-failed .aplan-mark {
  color: #dc2626;
}

.aplan-step-body {
  min-width: 0;
  flex: 1;
}

.aplan-step-title {
  font-size: 12px;
  line-height: 1.7;
  color: var(--sf-text-regular);
  word-break: break-word;
}

.aplan-step.is-done .aplan-step-title {
  color: var(--sf-text-muted);
}

.aplan-step.is-running .aplan-step-title {
  color: var(--sf-text-primary);
  font-weight: 500;
}

.aplan-step-reply {
  margin-top: 2px;
  font-size: 11px;
  line-height: 1.6;
  color: var(--sf-text-muted);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.aplan-step-error {
  margin-top: 2px;
  font-size: 11px;
  line-height: 1.6;
  color: #dc2626;
}

.agent-list {
  flex: 1;
  overflow-y: auto;
  padding: 8px 6px;
}

.agent-empty {
  text-align: center;
  margin-top: 12vh;
  padding: 0 12px;
}

.agent-empty-mark {
  width: 48px;
  height: 48px;
  margin: 0 auto 14px;
  border-radius: 12px;
  background: rgba(59, 110, 246, 0.1);
  color: var(--sf-brand);
  font-weight: 700;
  font-size: 13px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.agent-empty-title {
  margin: 0 0 8px;
  font-size: 17px;
  font-weight: 600;
  color: var(--sf-text-primary);
}

.agent-empty-desc {
  margin: 0 auto 18px;
  font-size: 13px;
  line-height: 1.8;
  color: var(--sf-text-muted);
}

.agent-starters {
  display: grid;
  gap: 8px;
}

.agent-starter {
  text-align: left;
  padding: 10px 12px;
  border: 1px solid var(--sf-border);
  border-radius: 8px;
  background: var(--sf-bg-card);
  color: var(--sf-text-regular);
  font-size: 13px;
  line-height: 1.5;
  cursor: pointer;
  transition: all 0.16s ease;
}

.agent-starter:hover {
  border-color: var(--sf-brand);
  color: var(--sf-brand);
}

.amsg {
  display: flex;
  gap: 8px;
  margin-bottom: 14px;
}

.amsg.from-user {
  flex-direction: row-reverse;
}

.amsg-avatar {
  flex: none;
  width: 28px;
  height: 28px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 11px;
  font-weight: 600;
}

.avatar-user {
  background: rgba(59, 110, 246, 0.12);
  color: var(--sf-brand);
}

.avatar-ai {
  background: #1f2733;
  color: #fff;
}

.amsg-body {
  max-width: 82%;
}

.from-user .amsg-body {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
}

.abubble {
  padding: 9px 12px;
  border-radius: 9px;
  font-size: 13px;
  line-height: 1.7;
  word-break: break-word;
}

.from-ai .abubble {
  background: var(--sf-bg-tint);
  color: var(--sf-text-primary);
  border-top-left-radius: 3px;
}

.from-user .abubble {
  background: var(--sf-brand);
  color: #fff;
  border-top-right-radius: 3px;
}

.abubble-text {
  white-space: pre-wrap;
}

.aprogress {
  min-width: 200px;
}

.aprogress-head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.aprogress-spinner {
  flex: none;
  width: 14px;
  height: 14px;
  border-radius: 50%;
  border: 2px solid var(--sf-border);
  border-top-color: var(--sf-brand);
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

.aprogress-label {
  font-size: 13px;
  color: var(--sf-text-regular);
}

.aprogress-step {
  margin-top: 6px;
  font-size: 12px;
  color: var(--sf-text-muted);
}

.abubble-failed {
  background: var(--sf-bg-danger) !important;
  color: var(--sf-text-danger);
}

.afailed {
  font-size: 12px;
  line-height: 1.6;
}

.aops {
  margin-top: 6px;
  padding: 8px 10px;
  border: 1px solid var(--sf-border);
  border-radius: 8px;
  background: var(--sf-bg-card);
}

.aops-title {
  font-size: 11px;
  color: var(--sf-text-muted);
  margin-bottom: 6px;
}

.aop {
  display: flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;
  line-height: 1.6;
}

.aop-dot {
  flex: none;
  width: 5px;
  height: 5px;
  border-radius: 50%;
}

.op-ok .aop-dot {
  background: #16a34a;
}

.op-fail .aop-dot {
  background: #dc2626;
}

.aop-type {
  flex: none;
  color: var(--sf-text-regular);
}

.aop-target {
  color: var(--sf-text-primary);
  font-weight: 500;
}

.aop-msg {
  color: #dc2626;
}

.acomposer {
  flex: none;
  padding: 10px 6px 6px;
  border-top: 1px solid var(--sf-border);
}

.acomposer-bar {
  margin-top: 8px;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.acomposer-mode {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin-right: auto;
  font-size: 12px;
  color: var(--sf-text-muted);
  cursor: pointer;
  user-select: none;
}

.acomposer-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
</style>

<style>
/* 抽屉 teleport 到 body 下渲染，scoped 样式作用不到；用全局类精准定制本抽屉，
   去掉 body 默认内边距，让拖拽手柄贴住抽屉外边缘 */
.agent-drawer .el-drawer__body {
  padding: 0;
  overflow: hidden;
}
</style>
