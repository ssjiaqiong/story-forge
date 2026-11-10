<template>
  <el-dialog
    v-model="visible"
    :width="step === 'diff' ? 'min(1320px, 95vw)' : '880px'"
    :title="dialogTitle"
    :close-on-click-modal="!generating && !saving"
    :close-on-press-escape="!generating && !saving"
    :show-close="!generating && !saving"
    top="5vh"
    destroy-on-close
    class="revise-dialog"
    @closed="reset"
  >
    <div v-loading="chapterLoading" class="revise-body">
      <!-- 生成中 -->
      <div v-if="generating" class="revise-generating">
        <el-icon class="spin"><Loading /></el-icon>
        <p class="gen-title">AI 正在按审稿意见改稿</p>
        <p class="gen-desc">
          已选 {{ pickedItems.length }} 条问题{{ instruction.trim() ? '，外加你的自定义要求' : '' }} · 通常 40~120 秒，最长 10 分钟
        </p>
        <p class="gen-elapsed">已用 {{ elapsed }} 秒</p>
      </div>

      <!-- 第一步：选要改的问题 -->
      <template v-else-if="step === 'select'">
        <div class="revise-head">
          <div class="head-main">
            <p class="revise-chapter">第 {{ chapterNo }} 章 {{ chapterTitle || '未命名' }}</p>
            <p class="revise-sub">
              依据 {{ formatDateTime(analysis?.createdAt) }} 的分析结果改稿，AI 只动你勾选的地方
            </p>
          </div>
          <div class="revise-scores">
            <el-tag effect="plain">大纲贴合 {{ analysis?.outlineScore ?? '—' }}</el-tag>
            <el-tag effect="plain">AI 率 {{ analysis?.aiScore ?? '—' }}</el-tag>
            <el-tag effect="plain" type="info">{{ wordCount }} 字</el-tag>
            <el-tag v-if="chapterDone" effect="plain" type="warning">已完成</el-tag>
          </div>
        </div>

        <p v-if="chapterDone" class="revise-done-hint">
          本章已标记为「已完成」。保存改稿时会先确认再覆盖，覆盖前的正文会存为历史版本。
        </p>

        <p v-if="!analysis" class="revise-warning">
          没读到本章的分析报告，先展开这一章、等报告加载完再试。
        </p>

        <div class="revise-toolbar">
          <span class="muted">已选 {{ pickedItems.length }} 条 · 默认勾选了大纲偏离与审稿建议{{ lowAiScore ? '，以及 AI 味问题' : '' }}</span>
          <span class="toolbar-actions">
            <el-button link type="primary" @click="selectAll">全选</el-button>
            <el-button link @click="clearAll">清空</el-button>
          </span>
        </div>

        <div class="issue-groups">
          <section v-for="group in groups" :key="group.key" class="issue-group">
            <div class="issue-group-head">
              <el-checkbox
                :model-value="groupChecked(group)"
                :indeterminate="groupIndeterminate(group)"
                :disabled="!group.items.length"
                @change="(value: string | number | boolean) => toggleGroup(group, value)"
              />
              <span class="issue-group-title">{{ group.label }}</span>
              <el-tag size="small" effect="plain" :type="group.tagType">{{ group.items.length }}</el-tag>
              <span class="issue-group-hint">{{ group.hint }}</span>
            </div>
            <div v-if="group.items.length" class="issue-list">
              <el-checkbox
                v-for="item in group.items"
                :key="item.key"
                :model-value="selected.has(item.key)"
                class="issue-item"
                @change="(value: string | number | boolean) => toggle(item.key, value)"
              >
                <span class="issue-text">{{ item.text }}</span>
                <span v-if="item.detail" class="issue-detail">{{ item.detail }}</span>
              </el-checkbox>
            </div>
            <p v-else class="muted issue-empty">{{ group.empty }}</p>
          </section>
        </div>

        <div class="revise-extra">
          <div class="extra-row">
            <span class="extra-label">修改幅度</span>
            <el-radio-group v-model="intensity" size="small">
              <el-radio-button value="LIGHT">轻度</el-radio-button>
              <el-radio-button value="MEDIUM">中度</el-radio-button>
              <el-radio-button value="HEAVY">重度</el-radio-button>
            </el-radio-group>
            <span class="muted intensity-hint">{{ intensityHint }}</span>
          </div>
          <el-input
            v-model="instruction"
            type="textarea"
            :rows="3"
            maxlength="2000"
            show-word-limit
            placeholder="也可以直接写要求，例如：结尾改成主角没赶上，留一个悬念；林小满的台词太文了，改成市井口吻。"
          />
        </div>
      </template>

      <!-- 第二步：左右对比 -->
      <template v-else-if="result">
        <div class="diff-head">
          <div class="diff-stat">
            <span class="stat-item">
              字数 <strong>{{ result.wordCountBefore }}</strong> → <strong>{{ result.wordCountAfter }}</strong>
            </span>
            <span class="stat-item add">+{{ diffStats.added }} 字</span>
            <span class="stat-item remove">-{{ diffStats.removed }} 字</span>
            <span class="stat-item">改动 {{ diffStats.changed }} 段 · 未变 {{ diffStats.unchanged }} 段</span>
          </div>
          <el-checkbox v-model="onlyChanges">只看改动</el-checkbox>
        </div>

        <div v-if="result.notes?.length" class="diff-notes">
          <span class="notes-label">改动说明</span>
          <span v-for="note in result.notes" :key="note" class="note-chip">{{ note }}</span>
        </div>

        <div v-if="!diffStats.changed" class="diff-empty">
          <p>AI 没有改动正文。</p>
          <p class="muted">可能这些问题在正文里已经处理过了，返回上一步换个要求再试。</p>
        </div>

        <div v-else class="diff-panel">
          <div class="diff-columns">
            <div class="diff-column-title">
              <span>原文</span>
              <span class="column-note">左侧带底色的字为被删掉的内容</span>
            </div>
            <div class="diff-column-title">
              <span>修改后</span>
              <span class="column-note">右侧带底色的字为新增的内容</span>
            </div>
          </div>
          <div class="diff-scroll">
            <div v-for="(block, index) in visibleBlocks" :key="index" class="diff-row" :class="`is-${block.kind}`">
              <div class="diff-cell">
                <span class="diff-no">{{ block.leftNo ?? '' }}</span>
                <p class="diff-text">
                  <span v-for="(segment, segIndex) in block.left" :key="segIndex" :class="segClass(segment, 'left')">{{
                    segment.text
                  }}</span>
                </p>
              </div>
              <div class="diff-cell">
                <span class="diff-no">{{ block.rightNo ?? '' }}</span>
                <p class="diff-text">
                  <span v-for="(segment, segIndex) in block.right" :key="segIndex" :class="segClass(segment, 'right')">{{
                    segment.text
                  }}</span>
                </p>
              </div>
            </div>
          </div>
        </div>

        <p class="diff-meta muted">
          模型 {{ result.model || '—' }} · 用时 {{ (result.latencyMs / 1000).toFixed(1) }} 秒 · 幅度 {{ intensityLabel }} ·
          保存后会覆盖正文并自动重新分析本章
        </p>
      </template>
    </div>

    <template v-if="!generating" #footer>
      <div v-if="step === 'select'" class="revise-footer">
        <el-button @click="visible = false">取消</el-button>
        <el-button type="primary" :disabled="!canRevise" @click="generate">开始修改</el-button>
      </div>
      <div v-else class="revise-footer">
        <el-button @click="step = 'select'">返回调整</el-button>
        <el-button :loading="saving" @click="generate">重新生成</el-button>
        <el-button type="primary" :loading="saving" :disabled="!diffStats.changed" @click="save">
          {{ chapterDone ? '覆盖保存并重新分析' : '保存并重新分析' }}
        </el-button>
      </div>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Loading } from '@element-plus/icons-vue'
import { getChapter, updateChapter } from '@/api/novel-asset'
import { ApiError, ErrorCodes } from '@/api/request'
import {
  reviseChapter,
  type ChapterAnalysisVO,
  type ChapterReviseVO,
  type ReviseIntensity,
  type ReviseItem
} from '@/api/analysis'
import { diffTexts, type DiffResult, type DiffSegment } from '@/utils/text-diff'

interface IssueItem {
  key: string
  type: string
  text: string
  detail?: string
}

interface IssueGroup {
  key: string
  label: string
  hint: string
  tagType: 'danger' | 'primary' | 'warning' | 'info' | 'success'
  empty: string
  /** 该组默认是否勾选 */
  selectByDefault: boolean
  items: IssueItem[]
}

const props = defineProps<{
  modelValue: boolean
  chapterId: string
  chapterNo: number
  chapterTitle: string
  analysis: ChapterAnalysisVO | null
}>()

const emit = defineEmits<{
  (event: 'update:modelValue', value: boolean): void
  (event: 'saved', payload: { chapterId: string; content: string }): void
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value)
})

const step = ref<'select' | 'diff'>('select')
const chapterLoading = ref(false)
const generating = ref(false)
const saving = ref(false)
const elapsed = ref(0)
const selected = ref<Set<string>>(new Set())
const instruction = ref('')
const intensity = ref<ReviseIntensity>('MEDIUM')
const originalContent = ref('')
/** 本章当前状态：DONE 时保存要二次确认（定稿正文默认锁定） */
const chapterStatus = ref('')
const result = ref<ChapterReviseVO | null>(null)
const onlyChanges = ref(false)
let timer: ReturnType<typeof setInterval> | null = null

const chapterDone = computed(() => chapterStatus.value === 'DONE')

const dialogTitle = computed(() =>
  step.value === 'diff' ? `对比修改 · 第 ${props.chapterNo} 章` : `按分析修改 · 第 ${props.chapterNo} 章`
)

/** 字数优先按刚读到的正文现算：分析之后作者可能又手改过 */
const wordCount = computed(() => countChinese(originalContent.value) || props.analysis?.wordCount || 0)

const lowAiScore = computed(() => (props.analysis?.aiScore ?? 100) < 55)

const groups = computed<IssueGroup[]>(() => {
  const report = props.analysis
  if (!report) {
    return []
  }
  const list: IssueGroup[] = [
    {
      key: 'miss',
      label: '大纲没做到 / 写歪了',
      hint: '按本章大纲补写缺失的情节',
      tagType: 'danger',
      empty: '没有明显偏离',
      selectByDefault: true,
      items: (report.outlineMisses || []).map((text, index) => ({
        key: `OUTLINE_MISS-${index}`,
        type: 'OUTLINE_MISS',
        text
      }))
    },
    {
      key: 'suggestion',
      label: '审稿建议',
      hint: '编辑给出的可执行改法',
      tagType: 'primary',
      empty: '模型没有给出修改建议',
      selectByDefault: true,
      items: (report.outlineSuggestions || []).map((text, index) => ({
        key: `OUTLINE_SUGGESTION-${index}`,
        type: 'OUTLINE_SUGGESTION',
        text
      }))
    },
    {
      key: 'signal',
      label: 'AI 味问题',
      hint: '按信号整类去 AI 味',
      tagType: 'warning',
      empty: '没有检测到明显的 AI 味信号',
      selectByDefault: lowAiScore.value,
      items: (report.aiSignals || []).map((signal, index) => ({
        key: `AI_SIGNAL-${index}`,
        type: 'AI_SIGNAL',
        text: signal.name,
        detail: signal.detail
      }))
    },
    {
      key: 'example',
      label: 'AI 味原句',
      hint: '只重写这几句',
      tagType: 'info',
      empty: '没有摘出问题原句',
      selectByDefault: lowAiScore.value,
      items: (report.aiExamples || []).map((text, index) => ({
        key: `AI_EXAMPLE-${index}`,
        type: 'AI_EXAMPLE',
        text
      }))
    },
    {
      key: 'length',
      label: '字数问题',
      hint: '按目标字数增删',
      tagType: 'success',
      empty: '字数在合理区间内',
      selectByDefault: !report.wordPass,
      items: report.wordComment
        ? [
            {
              key: 'LENGTH-0',
              type: 'LENGTH',
              text: `${report.wordLabel}：${report.wordComment}`,
              detail: `当前 ${report.wordCount} 字，目标 ${report.targetWords} 字（合理区间 ${report.minWords}~${report.maxWords}）`
            }
          ]
        : []
    }
  ]
  return list
})

const pickedItems = computed<ReviseItem[]>(() => {
  const picked: ReviseItem[] = []
  for (const group of groups.value) {
    for (const item of group.items) {
      if (selected.value.has(item.key)) {
        picked.push({ type: item.type, text: item.text, detail: item.detail })
      }
    }
  }
  return picked
})

const canRevise = computed(() => !!props.analysis && (pickedItems.value.length > 0 || instruction.value.trim().length > 0))

const intensityHint = computed(() => {
  if (intensity.value === 'LIGHT') {
    return '只改被点出的句子与段落，其余一字不动'
  }
  if (intensity.value === 'HEAVY') {
    return '允许重写段落、调整场景顺序，故事结论不变'
  }
  return '只改相关段落，可补写必要的过渡，场景顺序不动'
})

const intensityLabel = computed(() => {
  if (result.value?.intensity === 'LIGHT') {
    return '轻度'
  }
  if (result.value?.intensity === 'HEAVY') {
    return '重度'
  }
  return '中度'
})

const diff = computed(() =>
  result.value ? diffTexts(result.value.originalContent, result.value.revisedContent) : null
)

const EMPTY_DIFF: DiffResult = { blocks: [], added: 0, removed: 0, changed: 0, unchanged: 0 }

/** 模板里不必到处判空：没有结果时给一份空差异 */
const diffStats = computed<DiffResult>(() => diff.value ?? EMPTY_DIFF)

const visibleBlocks = computed(() => {
  const blocks = diffStats.value.blocks
  return onlyChanges.value ? blocks.filter((block) => block.kind !== 'equal') : blocks
})

watch(
  () => props.modelValue,
  (open) => {
    if (open) {
      void openDialog()
    }
  }
)

watch(
  () => props.chapterId,
  () => {
    if (props.modelValue) {
      void openDialog()
    }
  }
)

async function openDialog() {
  step.value = 'select'
  result.value = null
  instruction.value = ''
  intensity.value = 'MEDIUM'
  onlyChanges.value = false
  selected.value = new Set(buildDefaultKeys())
  chapterLoading.value = true
  try {
    const detail = await getChapter(props.chapterId)
    originalContent.value = detail.content || ''
    chapterStatus.value = detail.status || ''
  } catch (error) {
    ElMessage.error((error as Error).message || '读取本章正文失败')
  } finally {
    chapterLoading.value = false
  }
}

function buildDefaultKeys(): string[] {
  const keys: string[] = []
  for (const group of groups.value) {
    if (!group.selectByDefault) {
      continue
    }
    for (const item of group.items) {
      keys.push(item.key)
    }
  }
  return keys
}

function reset() {
  stopTimer()
  step.value = 'select'
  saving.value = false
  generating.value = false
  result.value = null
  selected.value = new Set()
  originalContent.value = ''
  instruction.value = ''
  onlyChanges.value = false
  chapterStatus.value = ''
}

function selectAll() {
  selected.value = new Set(groups.value.flatMap((group) => group.items.map((item) => item.key)))
}

function clearAll() {
  selected.value = new Set()
}

function toggle(key: string, value: boolean | string | number) {
  const next = new Set(selected.value)
  if (value) {
    next.add(key)
  } else {
    next.delete(key)
  }
  selected.value = next
}

function toggleGroup(group: IssueGroup, value: boolean | string | number) {
  const next = new Set(selected.value)
  for (const item of group.items) {
    if (value) {
      next.add(item.key)
    } else {
      next.delete(item.key)
    }
  }
  selected.value = next
}

function groupChecked(group: IssueGroup) {
  return group.items.length > 0 && group.items.every((item) => selected.value.has(item.key))
}

function groupIndeterminate(group: IssueGroup) {
  const hit = group.items.filter((item) => selected.value.has(item.key)).length
  return hit > 0 && hit < group.items.length
}

async function generate() {
  if (!canRevise.value) {
    ElMessage.warning('至少勾选一条问题，或写一句修改要求')
    return
  }
  generating.value = true
  elapsed.value = 0
  stopTimer()
  timer = setInterval(() => {
    elapsed.value += 1
  }, 1000)
  try {
    result.value = await reviseChapter(props.chapterId, {
      analysisId: props.analysis?.id,
      items: pickedItems.value,
      instruction: instruction.value.trim() || undefined,
      intensity: intensity.value,
      targetWords: props.analysis?.targetWords
    })
    step.value = 'diff'
  } catch (error) {
    ElMessage.error((error as Error).message || '改稿失败')
  } finally {
    stopTimer()
    generating.value = false
  }
}

/**
 * 保存改稿。
 *
 * <p>定稿（DONE）章节的正文默认锁定，直接保存会被服务端拦（40061），所以这里先问一次：
 * 用户点「覆盖保存」才带 {@code overwriteDone} 重发，服务端只放行这一次请求，章节状态不变；
 * 未定稿的章节直接写，不打扰作者。</p>
 */
async function save() {
  const revised = result.value?.revisedContent
  if (!revised || !diff.value?.changed) {
    return
  }
  if (chapterDone.value && !(await confirmOverwrite())) {
    return
  }
  saving.value = true
  try {
    try {
      await putRevisedContent(revised)
    } catch (error) {
      // 弹窗打开之后本章才在别处被标记为完成（例如另一个标签页点了「标记完成」）：
      // 再确认一次，用户同意就带着覆盖开关重发
      if (!(error instanceof ApiError) || error.code !== ErrorCodes.CHAPTER_LOCKED) {
        throw error
      }
      chapterStatus.value = 'DONE'
      if (!(await confirmOverwrite())) {
        return
      }
      await putRevisedContent(revised)
    }
    ElMessage.success('正文已保存，正在重新分析本章')
    emit('saved', { chapterId: props.chapterId, content: revised })
    visible.value = false
  } catch (error) {
    ElMessage.error((error as Error).message || '保存失败')
  } finally {
    saving.value = false
  }
}

function putRevisedContent(content: string) {
  return updateChapter(props.chapterId, {
    content,
    overwriteDone: chapterDone.value || undefined
  })
}

/** 覆盖前确认：返回 true 表示作者同意覆盖定稿正文 */
function confirmOverwrite() {
  return ElMessageBox.confirm(
    `第 ${props.chapterNo} 章当前是「已完成」状态，保存会用改稿覆盖定稿正文。` +
      '覆盖前的正文会存为历史版本，之后可在「历史版本」里回滚；章节状态保持「已完成」。是否覆盖？',
    '覆盖已完成章节',
    {
      type: 'warning',
      confirmButtonText: '覆盖保存',
      cancelButtonText: '取消'
    }
  )
    .then(() => true)
    .catch(() => false)
}

function segClass(segment: DiffSegment, side: 'left' | 'right') {
  if (segment.type === 'equal') {
    return 'seg-equal'
  }
  return side === 'left' ? 'seg-del' : 'seg-add'
}

function stopTimer() {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
}

function countChinese(text: string) {
  return (text.match(/[\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff]/g) || []).length
}

function formatDateTime(value?: string) {
  if (!value) {
    return '—'
  }
  return value.replace('T', ' ').slice(0, 16)
}

onBeforeUnmount(stopTimer)
</script>

<style scoped>
.revise-body {
  min-height: 120px;
}

/* ---------- 生成中 ---------- */
.revise-generating {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 56px 0 48px;
}

.revise-generating .spin {
  font-size: 30px;
  color: var(--sf-brand);
  animation: revise-spin 1s linear infinite;
}

@keyframes revise-spin {
  to {
    transform: rotate(360deg);
  }
}

.gen-title {
  margin: 6px 0 0;
  font-size: 15px;
  font-weight: 600;
  color: var(--sf-text-primary);
}

.gen-desc {
  margin: 0;
  font-size: 13px;
  color: var(--sf-text-muted);
}

.gen-elapsed {
  margin: 0;
  font-size: 12px;
  color: var(--sf-text-muted);
}

/* ---------- 第一步 ---------- */
.revise-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--sf-border);
}

.revise-chapter {
  margin: 0 0 4px;
  font-size: 15px;
  font-weight: 600;
  color: var(--sf-text-primary);
}

.revise-sub {
  margin: 0;
  font-size: 12px;
  color: var(--sf-text-muted);
}

.revise-scores {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.revise-warning {
  margin: 12px 0 0;
  padding: 8px 12px;
  border-radius: 6px;
  background: var(--sf-bg-danger);
  color: var(--sf-text-danger);
  font-size: 13px;
}

/* 定稿章节的提示：不阻断操作，只提前说明保存时会二次确认 */
.revise-done-hint {
  margin: 10px 0 0;
  padding: 8px 12px;
  border-radius: 6px;
  border-left: 3px solid #f59e0b;
  background: var(--sf-bg-tint);
  color: var(--sf-text-regular);
  font-size: 12px;
  line-height: 1.6;
}

.revise-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 14px;
  font-size: 12px;
}

.toolbar-actions :deep(.el-button + .el-button) {
  margin-left: 8px;
}

.issue-groups {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-top: 10px;
  max-height: 42vh;
  overflow-y: auto;
  padding-right: 6px;
}

.issue-group {
  border: 1px solid var(--sf-border);
  border-radius: 8px;
  background: var(--sf-bg-subtle);
}

.issue-group-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  border-bottom: 1px solid var(--sf-border);
}

.issue-group-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--sf-text-primary);
}

.issue-group-hint {
  font-size: 12px;
  color: var(--sf-text-muted);
}

.issue-list {
  display: flex;
  flex-direction: column;
  padding: 4px 12px 8px;
}

.issue-item {
  height: auto;
  align-items: flex-start;
  padding: 6px 0;
  white-space: normal;
}

.issue-item :deep(.el-checkbox__label) {
  display: flex;
  flex-direction: column;
  gap: 2px;
  line-height: 1.55;
  white-space: normal;
}

.issue-text {
  font-size: 13px;
  color: var(--sf-text-regular);
}

.issue-detail {
  font-size: 12px;
  color: var(--sf-text-muted);
}

.issue-empty {
  margin: 0;
  padding: 8px 12px 10px;
  font-size: 12px;
}

.revise-extra {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-top: 14px;
}

.extra-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
}

.extra-label {
  font-size: 13px;
  font-weight: 600;
  color: var(--sf-text-primary);
}

.intensity-hint {
  font-size: 12px;
}

.revise-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

/* ---------- 第二步 ---------- */
.diff-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding-bottom: 10px;
  border-bottom: 1px solid var(--sf-border);
}

.diff-stat {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
  font-size: 13px;
  color: var(--sf-text-regular);
}

.stat-item.add {
  color: #16a34a;
}

.stat-item.remove {
  color: #dc2626;
}

.diff-notes {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  margin: 10px 0;
}

.notes-label {
  font-size: 12px;
  font-weight: 600;
  color: var(--sf-text-primary);
}

.note-chip {
  padding: 2px 8px;
  border-radius: 10px;
  background: var(--sf-bg-tint);
  color: var(--sf-text-regular);
  font-size: 12px;
}

.diff-empty {
  padding: 48px 0;
  text-align: center;
}

.diff-empty p {
  margin: 4px 0;
  font-size: 13px;
}

.diff-panel {
  margin-top: 10px;
  border: 1px solid var(--sf-border);
  border-radius: 8px;
  overflow: hidden;
}

.diff-columns {
  display: grid;
  grid-template-columns: 1fr 1fr;
  background: var(--sf-bg-subtle);
  border-bottom: 1px solid var(--sf-border);
}

.diff-column-title {
  display: flex;
  align-items: baseline;
  gap: 8px;
  padding: 8px 12px 8px 34px;
  font-size: 13px;
  font-weight: 600;
  color: var(--sf-text-primary);
}

.diff-column-title:first-child {
  border-right: 1px solid var(--sf-border);
}

.column-note {
  font-size: 11px;
  font-weight: 400;
  color: var(--sf-text-muted);
}

.diff-scroll {
  max-height: 56vh;
  overflow-y: auto;
  background: var(--sf-bg-card);
}

.diff-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  border-bottom: 1px dashed var(--sf-border);
}

.diff-row:last-child {
  border-bottom: none;
}

.diff-row.is-change {
  background: var(--sf-bg-tint);
}

.diff-row.is-add {
  background: rgba(22, 163, 74, 0.06);
}

.diff-row.is-remove {
  background: rgba(220, 38, 38, 0.06);
}

.diff-cell {
  display: flex;
  gap: 8px;
  padding: 8px 12px 8px 8px;
  min-width: 0;
}

.diff-cell:first-child {
  border-right: 1px solid var(--sf-border);
}

.diff-no {
  flex: none;
  width: 22px;
  padding-top: 2px;
  text-align: right;
  font-size: 11px;
  color: var(--sf-text-muted);
  user-select: none;
}

.diff-text {
  flex: 1;
  margin: 0;
  font-size: 13px;
  line-height: 1.85;
  color: var(--sf-text-regular);
  word-break: break-word;
  white-space: pre-wrap;
}

.seg-equal {
  color: var(--sf-text-regular);
}

.seg-del {
  background: rgba(220, 38, 38, 0.16);
  color: #b91c1c;
  border-radius: 2px;
  text-decoration: line-through;
  text-decoration-color: rgba(185, 28, 28, 0.5);
}

.seg-add {
  background: rgba(22, 163, 74, 0.18);
  color: #15803d;
  border-radius: 2px;
}

.diff-meta {
  margin: 10px 0 0;
  font-size: 12px;
}

html.dark .seg-del {
  color: #fca5a5;
}

html.dark .seg-add {
  color: #86efac;
}

html.dark .stat-item.add {
  color: #4ade80;
}

html.dark .stat-item.remove {
  color: #f87171;
}
</style>
