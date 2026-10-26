<template>
  <div class="inspiration-page">
    <section class="chat-shell">
      <header class="chat-head">
        <div class="head-left">
          <div class="session-title">{{ session?.title || '新的灵感' }}</div>
          <div class="session-meta">
            <span class="meta-skill">
              <el-icon><MagicStick /></el-icon>
              {{ activeSkillName }}
            </span>
            <span class="meta-sep">·</span>
            <span>{{ messageCount }} 条上下文</span>
            <template v-if="session?.lastMessageAt">
              <span class="meta-sep">·</span>
              <span>最后对话 {{ formatTime(session.lastMessageAt) }}</span>
            </template>
          </div>
        </div>

        <div class="head-actions">
          <el-select
            v-model="skillCode"
            size="small"
            class="skill-select"
            :disabled="sending || !skills.length"
            @change="onSkillChange"
          >
            <el-option v-for="item in skills" :key="item.code" :label="item.name" :value="item.code" />
          </el-select>
          <el-button size="small" plain :disabled="!activeSkill" @click="openSkillDialog(activeSkill)">
            编辑技能
          </el-button>
          <el-button size="small" plain :disabled="messageCount === 0" @click="onClearContext">
            清空上下文
          </el-button>
          <el-button
            size="small"
            type="primary"
            :loading="creating"
            :disabled="!canCreate || isCreating"
            @click="onCreateNovel"
          >
            一键创建小说
          </el-button>
          <el-dropdown trigger="click" @command="onMoreCommand">
            <el-button size="small" text class="more-btn">
              <el-icon><MoreFilled /></el-icon>
            </el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="novels">书架</el-dropdown-item>
                <el-dropdown-item command="delete" :disabled="!session" divided>删除会话</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </header>

      <div ref="scrollRef" v-loading="loading" class="message-list">
        <div v-if="!loading && messages.length === 0" class="empty-state">
          <div class="empty-mark">灵感</div>
          <p class="empty-title">先聊聊你想写的故事</p>
          <p class="empty-desc">
            AI 会顺着你的想法往下追问，帮你把题材、主角、核心冲突和篇幅想清楚。
            聊到满意，点右上角「一键创建小说」就能直接开书。
          </p>
          <div class="starters">
            <button v-for="text in starters" :key="text" type="button" class="starter" @click="useStarter(text)">
              {{ text }}
            </button>
          </div>
        </div>

        <div
          v-for="item in messages"
          :key="item.id"
          class="msg"
          :class="item.role === 'USER' ? 'from-user' : 'from-ai'"
        >
          <div class="avatar" :class="item.role === 'USER' ? 'avatar-user' : 'avatar-ai'">
            {{ item.role === 'USER' ? '我' : 'AI' }}
          </div>
          <div class="msg-body">
            <div class="bubble" :class="{ 'bubble-failed': item.status === 'FAILED' }">
              <template v-if="item.status === 'FAILED'">
                <div class="failed-title">这条回复没生成出来</div>
                <div class="failed-detail">{{ item.errorMessage || 'AI 调用失败，请稍后重试' }}</div>
              </template>
              <div v-else class="bubble-text">{{ item.content }}</div>
            </div>
            <div class="msg-meta">
              <span>{{ formatTime(item.createdAt) }}</span>
              <span v-if="item.model"> · {{ item.model }}</span>
            </div>
          </div>
        </div>

        <div v-if="sending" class="msg from-ai">
          <div class="avatar avatar-ai">AI</div>
          <div class="msg-body">
            <div class="bubble typing">
              <i></i><i></i><i></i>
            </div>
          </div>
        </div>
      </div>

      <footer class="composer">
        <el-input
          v-model="draft"
          type="textarea"
          :rows="3"
          resize="none"
          maxlength="8000"
          show-word-limit
          placeholder="说说你的想法…（Enter 发送，Shift + Enter 换行）"
          @keydown="onKeydown"
        />
        <div class="composer-bar">
          <span class="composer-hint">对话会保留上下文，AI 记得前面聊过的设定</span>
          <div class="composer-actions">
            <el-button v-if="sending" type="danger" plain @click="onStop">停止</el-button>
            <el-button type="primary" :loading="sending" :disabled="!draft.trim() || sending" @click="onSend">
              发送
            </el-button>
          </div>
        </div>
      </footer>
    </section>

    <!-- 技能编辑 -->
    <el-dialog
      v-model="skillDialog"
      :title="skillForm.id ? '编辑技能' : '新建技能'"
      width="720px"
      :close-on-click-modal="false"
    >
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="修改只对当前账号生效，不会影响其他用户；切换技能后，下一轮对话立即使用新的提示词。"
        class="skill-alert"
      />
      <el-form :model="skillForm" label-width="88px" class="skill-form">
        <el-form-item label="技能名称">
          <el-input v-model="skillForm.name" maxlength="64" placeholder="如：创建小说 / 都市悬疑策划" />
        </el-form-item>
        <el-form-item label="技能说明">
          <el-input v-model="skillForm.description" maxlength="500" placeholder="一句话说明这个技能负责什么" />
        </el-form-item>
        <el-form-item label="系统提示词">
          <el-input
            v-model="skillForm.systemPrompt"
            type="textarea"
            :rows="15"
            resize="vertical"
            placeholder="这段提示词会作为每轮对话的 system 消息发送给模型"
          />
          <div class="prompt-tip">
            建议写清：角色、工作步骤、每次回复的语气与长度、什么时候交付《小说方案》。
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <div class="dialog-footer">
          <div class="footer-left">
            <el-button v-if="skillForm.id" text @click="startCreateSkill">+ 新建技能</el-button>
            <el-button
              v-if="skillForm.builtin"
              text
              type="warning"
              @click="onResetSkill"
            >
              恢复默认
            </el-button>
            <el-button v-if="skillForm.id && !skillForm.builtin" text type="danger" @click="onDeleteSkill">
              删除
            </el-button>
          </div>
          <div>
            <el-button @click="skillDialog = false">取消</el-button>
            <el-button type="primary" :loading="skillSaving" @click="onSaveSkill">保存</el-button>
          </div>
        </div>
      </template>
    </el-dialog>

    <!-- 一键建书：进度 + 结果 -->
    <el-dialog
      v-model="createdDialog"
      :title="dialogTitle"
      width="560px"
      :close-on-click-modal="false"
      :close-on-press-escape="false"
      :show-close="creationTask?.status !== 'RUNNING'"
    >
      <!-- 进行中：进度条 -->
      <template v-if="creationTask?.status === 'RUNNING'">
        <div class="progress-wrap">
          <el-progress :percentage="creationTask.percent" :stroke-width="12" :show-text="true" />
          <div class="progress-label">{{ creationTask.stepLabel }}</div>
          <div class="progress-step">第 {{ creationTask.stepNo }} / {{ creationTask.stepTotal }} 步</div>
          <p class="progress-hint">
            正在为你生成完整设定：世界观、关键人物与关系、5 章大纲，以及事件、物品、势力等，请稍候。
          </p>
        </div>
      </template>

      <!-- 成功 -->
      <template v-else-if="creationTask?.status === 'SUCCEEDED'">
        <div class="created">
          <div class="created-title">《{{ creationTask.novelTitle }}》</div>
          <div class="created-meta">
            <el-tag size="small" type="success" effect="plain">草稿</el-tag>
          </div>
          <el-alert
            v-if="creationTask.message"
            type="warning"
            :closable="false"
            show-icon
            :title="creationTask.message"
            class="created-note"
          />
          <p class="created-desc">
            已为你生成：世界观、背景、核心冲突、关键人物与关系、5 章大纲，以及事件、物品、势力等设定。
          </p>
          <div class="created-question">是否清除当前灵感会话？</div>
          <p class="created-hint">
            清除后本页的对话记录会被删除，AI 也不会再记得这次聊过的设定；已创建的小说不受影响。
          </p>
        </div>
      </template>

      <!-- 失败 -->
      <template v-else-if="creationTask?.status === 'FAILED'">
        <el-alert type="error" :closable="false" show-icon :title="creationTask.errorMessage || '创建失败'" />
        <p class="created-hint">可关闭后重试；已经创建的部分（如小说基本信息）不会重复。</p>
      </template>

      <template #footer>
        <div class="dialog-footer">
          <div class="footer-left">
            <el-button v-if="creationTask?.status === 'SUCCEEDED'" text @click="goNovels">去书架看看</el-button>
          </div>
          <div v-if="creationTask?.status !== 'RUNNING'">
            <el-button v-if="creationTask?.status === 'SUCCEEDED'" @click="keepSession">保留会话</el-button>
            <el-button
              v-if="creationTask?.status === 'SUCCEEDED'"
              type="primary"
              :loading="clearingAfterCreate"
              @click="clearAfterCreate"
            >
              确定，清除会话
            </el-button>
            <el-button v-if="creationTask?.status === 'FAILED'" type="primary" @click="closeDialog">关闭</el-button>
          </div>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { MagicStick, MoreFilled } from '@element-plus/icons-vue'
import {
  clearContext,
  createSkill,
  deleteSession,
  deleteSkill,
  getNovelCreationTask,
  getSession,
  listSkills,
  resetSkill,
  sendMessage,
  startNovelCreation,
  switchSkill,
  updateSkill,
  type InspirationMessage,
  type InspirationSession,
  type InspirationSkill,
  type NovelCreationTask
} from '@/api/inspiration'

const router = useRouter()

const loading = ref(false)
const sending = ref(false)
const creating = ref(false)
const clearingAfterCreate = ref(false)
const skillSaving = ref(false)

const session = ref<InspirationSession | null>(null)
const skills = ref<InspirationSkill[]>([])
const skillCode = ref('')
const draft = ref('')
const scrollRef = ref<HTMLElement | null>(null)

const skillDialog = ref(false)
const skillForm = reactive({
  id: '',
  name: '',
  description: '',
  systemPrompt: '',
  builtin: false
})

const createdDialog = ref(false)
const creationTask = ref<NovelCreationTask | null>(null)
let pollTimer: number | null = null

// 停止当前请求
let abortController: AbortController | null = null

const starters = [
  '我想写一个末日背景下的悬疑故事',
  '帮我设计一个反套路的修仙主角',
  '都市职场 + 异能，主角是个律师',
  '历史穿越，主角成了亡国太子'
]

const messages = computed<InspirationMessage[]>(() => session.value?.messages ?? [])
const messageCount = computed(() => session.value?.messageCount ?? 0)
const activeSkill = computed(() => skills.value.find((item) => item.code === skillCode.value))
const activeSkillName = computed(() => activeSkill.value?.name || '创建小说')
const canCreate = computed(() => messages.value.some((item) => item.role === 'USER' && item.status === 'OK'))
const isCreating = computed(() => creating.value || creationTask.value?.status === 'RUNNING')
const dialogTitle = computed(() => {
  const status = creationTask.value?.status
  if (status === 'RUNNING') {
    return '正在创建小说'
  }
  if (status === 'SUCCEEDED') {
    return '小说已创建'
  }
  if (status === 'FAILED') {
    return '创建失败'
  }
  return '创建小说'
})

onMounted(async () => {
  await loadSession()
})

async function loadSession() {
  loading.value = true
  try {
    const data = await getSession()
    session.value = data
    skillCode.value = data.skillCode
    await loadSkills()
  } catch (error) {
    ElMessage.error((error as Error).message || '加载灵感会话失败')
  } finally {
    loading.value = false
    await scrollToBottom()
  }
}

async function loadSkills() {
  try {
    skills.value = await listSkills()
    if (!skillCode.value && skills.value.length) {
      skillCode.value = skills.value[0].code
    }
  } catch {
    // 技能列表失败不阻断聊天
  }
}

function useStarter(text: string) {
  draft.value = text
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

  // 乐观插入用户气泡，接口失败时再撤回
  const tempId = `temp-${Date.now()}`
  ensureSession().messages.push({
    id: tempId,
    role: 'USER',
    content: text,
    status: 'OK'
  })
  await scrollToBottom()

  try {
    abortController = new AbortController()
    const result = await sendMessage(text, abortController.signal)
    const target = ensureSession()
    target.messages = target.messages.filter((item) => item.id !== tempId)
    target.messages.push(result.userMessage, result.assistantMessage)
    target.messageCount = result.session?.messageCount ?? target.messageCount
    target.title = result.session?.title ?? target.title
    target.lastMessageAt = result.session?.lastMessageAt ?? target.lastMessageAt
    if (result.errorMessage) {
      ElMessage.warning(result.errorMessage)
    }
  } catch (error) {
    const target = ensureSession()
    target.messages = target.messages.filter((item) => item.id !== tempId)
    // 用户主动停止时不回填草稿、不弹错误
    if (abortController?.signal.aborted) {
      ElMessage.info('已停止')
    } else {
      draft.value = text
      ElMessage.error((error as Error).message || '发送失败')
    }
  } finally {
    abortController = null
    sending.value = false
    await scrollToBottom()
  }
}

function onStop() {
  if (abortController) {
    abortController.abort()
  }
}

function ensureSession(): InspirationSession {
  if (!session.value) {
    session.value = {
      id: '',
      skillCode: skillCode.value,
      messageCount: 0,
      messages: []
    }
  }
  return session.value
}

async function onSkillChange(code: string) {
  const previous = session.value?.skillCode
  try {
    const data = await switchSkill(code)
    session.value = data
    const name = skills.value.find((item) => item.code === code)?.name ?? code
    ElMessage.success(`已切换到「${name}」，下一轮对话生效`)
  } catch (error) {
    if (previous) {
      skillCode.value = previous
    }
    ElMessage.error((error as Error).message || '切换技能失败')
  }
}

async function onClearContext() {
  try {
    await ElMessageBox.confirm(
      '清空后本页对话记录会被删除，AI 也不再记得之前聊过的设定。已创建的小说不受影响。',
      '清空上下文',
      { type: 'warning', confirmButtonText: '清空', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await clearContext()
    await loadSession()
    ElMessage.success('上下文已清空')
  } catch (error) {
    ElMessage.error((error as Error).message || '清空失败')
  }
}

async function onDeleteSession() {
  try {
    await ElMessageBox.confirm(
      '将删除整个灵感会话，包括全部对话记录。下次进入会重新开始。',
      '删除会话',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await deleteSession()
    await loadSession()
    ElMessage.success('会话已删除')
  } catch (error) {
    ElMessage.error((error as Error).message || '删除失败')
  }
}

async function onCreateNovel() {
  if (!canCreate.value) {
    ElMessage.warning('先和 AI 聊几句，再创建小说')
    return
  }
  creating.value = true
  try {
    const task = await startNovelCreation()
    creationTask.value = task
    createdDialog.value = true
    startPolling(task.taskId)
  } catch (error) {
    ElMessage.error((error as Error).message || '创建小说失败')
  } finally {
    creating.value = false
  }
}

function startPolling(taskId: string) {
  stopPolling()
  pollTimer = window.setInterval(async () => {
    try {
      const task = await getNovelCreationTask(taskId)
      creationTask.value = task
      if (task.status !== 'RUNNING') {
        stopPolling()
      }
    } catch {
      stopPolling()
      ElMessage.error('查询创建进度失败')
    }
  }, 800)
}

function stopPolling() {
  if (pollTimer !== null) {
    window.clearInterval(pollTimer)
    pollTimer = null
  }
}

onUnmounted(() => {
  stopPolling()
  if (abortController) {
    abortController.abort()
    abortController = null
  }
})

function keepSession() {
  createdDialog.value = false
  ElMessage.info('已保留会话，可以继续聊')
}

async function clearAfterCreate() {
  clearingAfterCreate.value = true
  try {
    await deleteSession()
    createdDialog.value = false
    await loadSession()
    ElMessage.success('会话已清除，可以开始新的灵感')
  } catch (error) {
    ElMessage.error((error as Error).message || '清除会话失败')
  } finally {
    clearingAfterCreate.value = false
  }
}

function goNovels() {
  createdDialog.value = false
  router.push('/bookshelf')
}

function closeDialog() {
  createdDialog.value = false
}

async function onMoreCommand(command: string) {
  if (command === 'novels') {
    router.push('/bookshelf')
    return
  }
  if (command === 'delete') {
    await onDeleteSession()
  }
}

function openSkillDialog(skill?: InspirationSkill) {
  if (!skill) {
    startCreateSkill()
    return
  }
  skillForm.id = skill.id
  skillForm.name = skill.name
  skillForm.description = skill.description ?? ''
  skillForm.systemPrompt = skill.systemPrompt
  skillForm.builtin = Boolean(skill.builtin)
  skillDialog.value = true
}

function startCreateSkill() {
  skillForm.id = ''
  skillForm.name = ''
  skillForm.description = ''
  skillForm.systemPrompt = ''
  skillForm.builtin = false
  skillDialog.value = true
}

async function onSaveSkill() {
  const name = skillForm.name.trim()
  const prompt = skillForm.systemPrompt.trim()
  if (!name) {
    ElMessage.warning('请填写技能名称')
    return
  }
  if (!prompt) {
    ElMessage.warning('请填写系统提示词')
    return
  }
  skillSaving.value = true
  try {
    if (skillForm.id) {
      const updated = await updateSkill(skillForm.id, {
        name,
        description: skillForm.description,
        systemPrompt: prompt
      })
      skills.value = skills.value.map((item) => (item.id === updated.id ? updated : item))
      ElMessage.success('技能已保存，对本账号生效')
    } else {
      const newSkill = await createSkill({ name, description: skillForm.description, systemPrompt: prompt })
      skills.value.push(newSkill)
      skillCode.value = newSkill.code
      await switchSkill(newSkill.code)
      session.value = { ...ensureSession(), skillCode: newSkill.code, skillName: newSkill.name }
      ElMessage.success('技能已创建并切换')
    }
    skillDialog.value = false
  } catch (error) {
    ElMessage.error((error as Error).message || '保存失败')
  } finally {
    skillSaving.value = false
  }
}

async function onResetSkill() {
  if (!skillForm.id) {
    return
  }
  try {
    await ElMessageBox.confirm('将把提示词恢复为平台默认内容，当前修改会丢失。', '恢复默认', {
      type: 'warning',
      confirmButtonText: '恢复',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  try {
    const restored = await resetSkill(skillForm.id)
    skillForm.name = restored.name
    skillForm.description = restored.description ?? ''
    skillForm.systemPrompt = restored.systemPrompt
    skills.value = skills.value.map((item) => (item.id === restored.id ? restored : item))
    ElMessage.success('已恢复默认提示词')
  } catch (error) {
    ElMessage.error((error as Error).message || '恢复失败')
  }
}

async function onDeleteSkill() {
  if (!skillForm.id || skillForm.builtin) {
    return
  }
  try {
    await ElMessageBox.confirm('删除后不可恢复，确认删除这个技能？', '删除技能', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  try {
    await deleteSkill(skillForm.id)
    skills.value = skills.value.filter((item) => item.id !== skillForm.id)
    skillDialog.value = false
    ElMessage.success('技能已删除')
  } catch (error) {
    ElMessage.error((error as Error).message || '删除失败')
  }
}

async function scrollToBottom() {
  await nextTick()
  const el = scrollRef.value
  if (el) {
    el.scrollTop = el.scrollHeight
  }
}

function formatTime(value?: string) {
  if (!value) {
    return ''
  }
  const text = value.replace('T', ' ')
  const today = new Date()
  const datePart = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}-${String(
    today.getDate()
  ).padStart(2, '0')}`
  if (text.startsWith(datePart)) {
    return text.slice(11, 16)
  }
  return text.slice(5, 16)
}
</script>

<style scoped>
.inspiration-page {
  height: calc(100vh - 88px);
}

.chat-shell {
  height: 100%;
  display: flex;
  flex-direction: column;
  background: var(--sf-bg-card);
  border-radius: 10px;
  border: 1px solid var(--sf-border);
  overflow: hidden;
}

.chat-head {
  flex: none;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 12px 18px;
  border-bottom: 1px solid var(--sf-border);
  background: var(--sf-bg-card);
}

.head-left {
  min-width: 0;
}

.session-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--sf-text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 420px;
}

.session-meta {
  margin-top: 4px;
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--sf-text-muted);
}

.meta-skill {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: var(--sf-brand);
}

.meta-sep {
  color: var(--sf-text-muted);
}

.head-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: none;
}

.skill-select {
  width: 138px;
}

.more-btn {
  padding: 6px;
}

.message-list {
  flex: 1;
  overflow-y: auto;
  padding: 22px 24px 8px;
  background: linear-gradient(180deg, var(--sf-bg-subtle) 0%, var(--sf-bg-card) 42%);
  scroll-behavior: smooth;
}

.empty-state {
  max-width: 620px;
  margin: 8vh auto 0;
  text-align: center;
}

.empty-mark {
  width: 54px;
  height: 54px;
  margin: 0 auto 16px;
  border-radius: 14px;
  background: rgba(59, 110, 246, 0.1);
  color: var(--sf-brand);
  font-weight: 700;
  font-size: 14px;
  display: flex;
  align-items: center;
  justify-content: center;
  letter-spacing: 1px;
}

.empty-title {
  margin: 0 0 8px;
  font-size: 18px;
  font-weight: 600;
  color: var(--sf-text-primary);
}

.empty-desc {
  margin: 0 auto 22px;
  max-width: 520px;
  font-size: 13px;
  line-height: 1.9;
  color: var(--sf-text-muted);
}

.starters {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.starter {
  text-align: left;
  padding: 12px 14px;
  border: 1px solid var(--sf-border);
  border-radius: 8px;
  background: var(--sf-bg-card);
  color: var(--sf-text-regular);
  font-size: 13px;
  line-height: 1.5;
  cursor: pointer;
  transition: all 0.16s ease;
}

.starter:hover {
  border-color: var(--sf-brand);
  color: var(--sf-brand);
  background: rgba(59, 110, 246, 0.04);
}

.msg {
  display: flex;
  gap: 10px;
  margin-bottom: 18px;
}

.msg.from-user {
  flex-direction: row-reverse;
}

.avatar {
  flex: none;
  width: 30px;
  height: 30px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
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

.msg-body {
  max-width: min(680px, 76%);
}

.from-user .msg-body {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
}

.bubble {
  padding: 11px 14px;
  border-radius: 10px;
  font-size: 14px;
  line-height: 1.78;
  word-break: break-word;
}

.from-ai .bubble {
  background: var(--sf-bg-tint);
  color: var(--sf-text-primary);
  border-top-left-radius: 3px;
}

.from-user .bubble {
  background: var(--sf-brand);
  color: #fff;
  border-top-right-radius: 3px;
}

.bubble-text {
  white-space: pre-wrap;
}

.bubble-failed {
  background: var(--sf-bg-danger) !important;
  color: var(--sf-text-danger);
  border: 1px solid var(--sf-border-danger);
}

.failed-title {
  font-weight: 600;
  margin-bottom: 4px;
}

.failed-detail {
  font-size: 12px;
  line-height: 1.6;
  color: var(--sf-text-danger);
}

.msg-meta {
  margin-top: 5px;
  font-size: 11px;
  color: var(--sf-text-muted);
}

.typing {
  display: flex;
  align-items: center;
  gap: 5px;
  padding: 14px;
}

.typing i {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--sf-text-muted);
  animation: blink 1.2s infinite ease-in-out;
}

.typing i:nth-child(2) {
  animation-delay: 0.18s;
}

.typing i:nth-child(3) {
  animation-delay: 0.36s;
}

@keyframes blink {
  0%,
  80%,
  100% {
    opacity: 0.25;
    transform: translateY(0);
  }
  40% {
    opacity: 1;
    transform: translateY(-2px);
  }
}

.composer {
  flex: none;
  padding: 12px 18px 14px;
  border-top: 1px solid var(--sf-border);
  background: var(--sf-bg-card);
}

.composer-bar {
  margin-top: 8px;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.composer-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.composer-hint {
  font-size: 12px;
  color: var(--sf-text-muted);
}

.skill-alert {
  margin-bottom: 14px;
}

.prompt-tip {
  font-size: 12px;
  color: var(--sf-text-muted);
  line-height: 1.6;
  margin-top: 4px;
}

.dialog-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.footer-left {
  display: flex;
  align-items: center;
  gap: 4px;
}

.created-title {
  font-size: 20px;
  font-weight: 700;
  color: var(--sf-text-primary);
}

.created-meta {
  margin-top: 10px;
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.created-intro {
  margin: 14px 0;
  padding: 12px 14px;
  background: var(--sf-bg-subtle);
  border-radius: 8px;
  font-size: 13px;
  line-height: 1.85;
  color: var(--sf-text-regular);
  max-height: 180px;
  overflow-y: auto;
}

.created-note {
  margin-bottom: 12px;
}

.created-desc {
  margin: 14px 0 4px;
  font-size: 13px;
  line-height: 1.85;
  color: var(--sf-text-regular);
}

.progress-wrap {
  padding: 6px 4px 4px;
}

.progress-label {
  margin-top: 18px;
  font-size: 15px;
  font-weight: 600;
  color: var(--sf-text-primary);
  text-align: center;
}

.progress-step {
  margin-top: 6px;
  font-size: 12px;
  color: var(--sf-text-muted);
  text-align: center;
}

.progress-hint {
  margin: 18px 0 4px;
  font-size: 13px;
  line-height: 1.8;
  color: var(--sf-text-muted);
}

.created-question {
  margin-top: 6px;
  font-size: 14px;
  font-weight: 600;
  color: var(--sf-text-primary);
}

.created-hint {
  margin: 6px 0 0;
  font-size: 12px;
  line-height: 1.7;
  color: var(--sf-text-muted);
}
</style>
