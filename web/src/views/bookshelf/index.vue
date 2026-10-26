<template>
  <div class="page-container bookshelf-page">
    <div class="shelf-head">
      <div class="head-text">
        <h2 class="shelf-title">书架</h2>
        <p class="shelf-sub">
          共 {{ total }} 本小说<span v-if="records.length"> · 本页 {{ records.length }} 本</span>
        </p>
      </div>
      <div class="head-actions">
        <el-input
          v-model="keyword"
          size="small"
          clearable
          placeholder="搜索书名或简介"
          class="search-input"
          @keyup.enter="reload"
          @clear="reload"
        />
        <el-select v-model="status" size="small" clearable placeholder="全部状态" class="status-select" @change="reload">
          <el-option v-for="item in statusOptions" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
        <el-button size="small" :loading="loading" @click="reload">刷新</el-button>
        <el-button size="small" @click="openDeconstruct">拆书改编</el-button>
        <el-button size="small" :loading="importing" @click="pickImportFile">导入</el-button>
        <el-button size="small" type="primary" @click="openCreate">创建小说</el-button>
        <input
          ref="importInputRef"
          type="file"
          accept="application/json,.json"
          style="display: none"
          @change="onImportFileChange"
        />
      </div>
    </div>

    <div v-loading="loading" class="shelf-body">
      <div v-if="records.length" class="book-grid">
        <article v-for="book in records" :key="book.id" class="book-card" @click="openDetail(book)">
          <div class="book-top">
            <h3 class="book-title" :title="book.title">{{ book.title }}</h3>
            <el-dropdown trigger="click" @command="(cmd: string) => onCardCommand(cmd, book)" @click.stop>
              <el-button size="small" text class="more-btn" @click.stop>
                <el-icon><MoreFilled /></el-icon>
              </el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="open">打开工作台</el-dropdown-item>
                  <el-dropdown-item command="write">大纲与正文</el-dropdown-item>
                  <el-dropdown-item command="cover" divided>生成封面</el-dropdown-item>
                  <el-dropdown-item command="export">导出 JSON（备份）</el-dropdown-item>
                  <el-dropdown-item command="export-txt">导出 TXT（投稿）</el-dropdown-item>
                  <el-dropdown-item command="delete" divided>删除</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </div>

          <BookCover :covers="book.covers || []" :title="book.title" class="card-cover" />

          <div class="book-bottom">
            <div class="book-meta">
              <el-tag v-if="book.genre" size="small" effect="plain">{{ book.genre }}</el-tag>
              <span class="book-badge">{{ statusLabel(book.status) }}</span>
              <span class="book-source">{{ sourceLabel(book.source) }}</span>
            </div>
            <p class="book-intro">{{ book.intro || '还没有简介，点开补充设定' }}</p>
          </div>
        </article>
      </div>

      <div v-else-if="!loading" class="empty-shelf">
        <div class="empty-mark">书架</div>
        <p class="empty-title">{{ keyword || status ? '没有符合条件的作品' : '书架还是空的' }}</p>
        <p class="empty-desc">
          写下一句核心创意，AI 会帮你补齐世界观、背景、简介和主角档案，直接开书。
        </p>
        <el-button type="primary" @click="openCreate">创建第一本小说</el-button>
      </div>
    </div>

    <div v-if="total > 0" class="pager">
      <el-pagination
        v-model:current-page="page"
        v-model:page-size="size"
        :total="total"
        :page-sizes="[8, 16, 32]"
        layout="total, sizes, prev, pager, next"
        background
        @current-change="load"
        @size-change="reload"
      />
    </div>

    <!-- 创建小说向导 -->
    <el-dialog
      v-model="createVisible"
      width="920px"
      top="5vh"
      :close-on-click-modal="false"
      :close-on-press-escape="!generating"
      :show-close="!generating"
      destroy-on-close
    >
      <template #header>
        <div class="dialog-head">
          <span class="dialog-title">创建小说</span>
          <span class="dialog-hint">{{
            asking
              ? 'AI 还想知道几件事，答完生成会更贴合你的想法'
              : step === 0
                ? '告诉我你想写什么，剩下的交给 AI'
                : '核对并修改 AI 的设定，保存后即可开写'
          }}</span>
        </div>
      </template>

      <el-steps :active="stepActive" simple class="create-steps">
        <el-step title="填写创意" />
        <el-step title="补充关键信息" />
        <el-step title="核对设定" />
      </el-steps>

      <div v-if="askingLoading" class="gen-state">
        <el-icon class="spin"><Loading /></el-icon>
        <p class="gen-title">正在判断还缺哪些信息…</p>
        <p class="gen-desc">大概十几秒，想跳过可以直接点「跳过，直接生成」</p>
      </div>

      <div v-else-if="generating" class="gen-state">
        <el-icon class="spin"><Loading /></el-icon>
        <p class="gen-title">{{ taskLabel || 'AI 正在搭建这个世界' }}</p>
        <p class="gen-percent">{{ taskPercent }}%</p>
        <el-progress
          class="gen-progress"
          :percentage="taskPercent"
          :stroke-width="10"
          :show-text="false"
          :status="taskStatus === 'FAILED' ? 'exception' : undefined"
        />
        <el-alert
          v-if="taskMessage"
          class="gen-alert"
          :title="taskMessage"
          type="warning"
          :closable="false"
          show-icon
        />
        <div class="gen-steps">
          <span
            v-for="(item, index) in GEN_STEPS"
            :key="item.label"
            class="gen-step"
            :class="{ done: taskPercent >= item.percent, active: taskStepNo === index + 1 }"
          >
            {{ item.label }}
          </span>
        </div>
        <p class="gen-desc">共 {{ GEN_STEPS.length }} 段，每段最长 5 分钟 · 已用 {{ elapsedLabel }}</p>
      </div>

      <div v-else class="dialog-body">
        <div v-if="asking" class="ask-form">
          <p class="ask-intro">
            AI 读了你的创意，还想确认几件事。答一句就够；留空的项由 AI 自行决定，不影响生成。
          </p>
          <div v-for="item in questions" :key="item.key" class="ask-item">
            <div class="field-label">
              <span class="ask-tag">{{ item.label }}</span>{{ item.question }}
            </div>
            <el-input
              v-model="answers[item.key]"
              type="textarea"
              :rows="2"
              maxlength="300"
              show-word-limit
              :placeholder="item.hint || '一句话说明即可'"
            />
          </div>
        </div>

        <div v-else-if="step === 0" class="idea-form">
          <div class="field-label">核心创意 <span class="required">*</span></div>
          <el-input
            v-model="form.idea"
            type="textarea"
            :rows="6"
            maxlength="2000"
            show-word-limit
            placeholder="用一两句话说说你想写的故事。比如：一个被宗门逐出的废柴，靠一本会说话的旧书重新踏上修行路。"
          />
          <div class="idea-samples">
            <span class="samples-label">没想好？试试：</span>
            <button v-for="item in IDEA_SAMPLES" :key="item" type="button" class="sample-chip" @click="form.idea = item">
              {{ item }}
            </button>
          </div>

          <el-row :gutter="12">
            <el-col :span="8">
              <div class="field-label">书名（可留空）</div>
              <el-input v-model="form.title" maxlength="100" placeholder="留空让 AI 起名" />
            </el-col>
            <el-col :span="8">
              <div class="field-label">题材</div>
              <el-select v-model="form.genre" class="w-full" filterable allow-create clearable placeholder="选择或输入">
                <el-option v-for="item in GENRES" :key="item" :label="item" :value="item" />
              </el-select>
            </el-col>
            <el-col :span="8">
              <div class="field-label">笔名（可留空）</div>
              <el-input v-model="form.penName" maxlength="64" placeholder="署名" />
            </el-col>
          </el-row>

          <el-row :gutter="12">
            <el-col :span="8">
              <div class="field-label">标签</div>
              <el-select
                v-model="form.tags"
                class="w-full"
                multiple
                filterable
                allow-create
                default-first-option
                :multiple-limit="8"
                placeholder="最多 8 个"
              >
                <el-option v-for="item in TAG_SUGGESTIONS" :key="item" :label="item" :value="item" />
              </el-select>
            </el-col>
            <el-col :span="8">
              <div class="field-label">风格偏好</div>
              <el-select v-model="form.style" class="w-full" filterable allow-create clearable placeholder="选择或输入">
                <el-option v-for="item in STYLES" :key="item" :label="item" :value="item" />
              </el-select>
            </el-col>
            <el-col :span="8">
              <div class="field-label">目标篇幅</div>
              <el-select v-model="form.targetLength" class="w-full" clearable placeholder="选择篇幅">
                <el-option v-for="item in TARGET_LENGTHS" :key="item" :label="item" :value="item" />
              </el-select>
            </el-col>
          </el-row>

          <div class="field-label">主角偏好（可留空）</div>
          <el-input v-model="form.protagonistHint" maxlength="500" placeholder="如：女主，性格冷静克制，不要恋爱脑" />
        </div>

        <div v-else class="preview-form">
          <BlueprintEditor v-if="blueprint" v-model="blueprint" />
          <div v-if="generateMeta" class="gen-meta">
            由 {{ generateMeta.model || 'AI' }} 生成，耗时 {{ ((generateMeta.latencyMs || 0) / 1000).toFixed(1) }} 秒
          </div>
        </div>
      </div>

      <template #footer>
        <div class="dialog-footer">
          <div class="footer-left">
            <el-button
              v-if="asking"
              text
              :disabled="askingLoading"
              @click="onSkipAnswers"
            >
              跳过，直接生成
            </el-button>
            <el-button
              v-else-if="step === 0 && !generating"
              text
              :disabled="saving || askingLoading"
              @click="createWithoutAi"
            >
              不生成设定，只建草稿
            </el-button>
          </div>
          <div class="footer-right">
            <el-button :disabled="generating || askingLoading" @click="closeCreate">取消</el-button>
            <template v-if="asking">
              <el-button :disabled="saving" @click="asking = false; step = 0">上一步</el-button>
              <el-button type="primary" :loading="generating" @click="onConfirmAnswers">开始生成</el-button>
            </template>
            <template v-else-if="step === 0">
              <el-button type="primary" :loading="generating || askingLoading" @click="onGenerate">
                {{ askingLoading ? '正在判断…' : 'AI 生成设定' }}
              </el-button>
            </template>
            <template v-else>
              <el-button :disabled="saving" @click="onRegenerate">重新生成</el-button>
              <el-button :disabled="saving" @click="step = 0">上一步</el-button>
              <el-button type="primary" :loading="saving" @click="onSave">保存到书架</el-button>
            </template>
          </div>
        </div>
      </template>
    </el-dialog>

    <!-- 拆书改编 -->
    <DeconstructDialog v-model="deconstructVisible" @saved="onDeconstructSaved" />

    <!-- 封面管理 -->
    <CoverDialog v-model="coverVisible" :novel="coverNovel" @updated="onCoverUpdated" />
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Loading, MoreFilled } from '@element-plus/icons-vue'
import {
  assembleConstraints,
  blueprintQuestions,
  createNovel,
  deleteNovel,
  emptyBlueprint,
  exportNovel,
  exportNovelTxt,
  getBlueprintTask,
  importNovel,
  normalizeBlueprint,
  startBlueprintTask,
  type BlueprintQuestion,
  type BlueprintResultVO,
  type BlueprintTaskVO,
  type NovelBlueprint
} from '@/api/bookshelf'
import { listNovels, type NovelVO } from '@/api/novel'
import BlueprintEditor from './components/BlueprintEditor.vue'
import DeconstructDialog from './components/DeconstructDialog.vue'
import BookCover from './components/BookCover.vue'
import CoverDialog from './components/CoverDialog.vue'

const router = useRouter()

const deconstructVisible = ref(false)

function openDeconstruct() {
  deconstructVisible.value = true
}

function onDeconstructSaved() {
  void load()
}

/* ---------- 封面 ---------- */
const coverVisible = ref(false)
const coverNovel = ref<NovelVO | null>(null)

function openCover(book: NovelVO) {
  coverNovel.value = book
  coverVisible.value = true
}

function onCoverUpdated() {
  void load()
}

function sourceLabel(source?: string) {
  if (source === 'INSPIRATION') {
    return '灵感模式'
  }
  if (source === 'DECONSTRUCT') {
    return '拆书改编'
  }
  return '手动创建'
}

const GENRES = ['玄幻', '仙侠', '都市', '悬疑', '科幻', '历史', '游戏', '轻小说', '武侠', '现实']
const TAG_SUGGESTIONS = ['升级流', '群像', '轻松', '无敌流', '单女主', '无女主', '复仇', '种田', '克苏鲁', '无限流']
const STYLES = ['热血爽文', '轻松诙谐', '冷硬写实', '温情治愈', '黑暗向', '快节奏']
const TARGET_LENGTHS = ['短篇 10 万字', '中篇 40 万字', '长篇 120 万字', '超长篇 300 万字']
const IDEA_SAMPLES = [
  '一个被宗门逐出的废柴，靠一本会说话的旧书重新踏上修行路',
  '都市里唯一能看见鬼的律师，靠打阴间官司翻身',
  '末世第七年，人类最后的城市靠一台会做梦的 AI 维持运转'
]

/** 分段生成的四段，percent 为该段开始时对应的进度，用于步骤条着色 */
const GEN_STEPS = [
  { label: '世界观与力量体系', percent: 12 },
  { label: '时代背景与势力格局', percent: 36 },
  { label: '简介与核心冲突', percent: 60 },
  { label: '人物档案', percent: 84 }
]

const statusOptions = [
  { label: '草稿', value: 'DRAFT' },
  { label: '连载中', value: 'WRITING' },
  { label: '已完结', value: 'FINISHED' },
  { label: '已归档', value: 'ARCHIVED' }
]

/* ---------- 列表 ---------- */
const loading = ref(false)
const records = ref<NovelVO[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(8)
const keyword = ref('')
const status = ref('')

/* ---------- 创建向导 ---------- */
const createVisible = ref(false)
const step = ref(0)
const generating = ref(false)
const saving = ref(false)
const blueprint = ref<NovelBlueprint | null>(null)
const generateMeta = ref<BlueprintResultVO | null>(null)

/* ---------- 建书前的追问 ---------- */
const asking = ref(false)
const askingLoading = ref(false)
const questions = ref<BlueprintQuestion[]>([])
const answers = reactive<Record<string, string>>({})
/** 已确认的创作约束：重新生成时沿用，不必再问一遍 */
const constraintDraft = ref('')

const stepActive = computed(() => (asking.value ? 2 : step.value === 0 ? 1 : 3))

/* ---------- 生成进度 ---------- */
const taskPercent = ref(0)
const taskLabel = ref('')
const taskMessage = ref('')
const taskStepNo = ref(0)
const taskStatus = ref<'RUNNING' | 'SUCCEEDED' | 'FAILED'>('RUNNING')
const elapsed = ref(0)
const elapsedLabel = computed(() => `${elapsed.value} 秒`)
let pollTimer: number | undefined
let elapsedTimer: number | undefined
const form = reactive({
  idea: '',
  title: '',
  penName: '',
  genre: '',
  tags: [] as string[],
  style: '',
  targetLength: '',
  protagonistHint: ''
})

onMounted(load)

async function load() {
  loading.value = true
  try {
    const result = await listNovels({
      page: page.value,
      size: size.value,
      keyword: keyword.value.trim() || undefined,
      status: status.value || undefined
    })
    records.value = result.records ?? []
    total.value = result.total ?? 0
  } catch (error) {
    ElMessage.error((error as Error).message || '加载书架失败')
  } finally {
    loading.value = false
  }
}

function reload() {
  page.value = 1
  void load()
}

function openDetail(book: NovelVO) {
  router.push(`/novels/${book.id}`)
}

function onCardCommand(command: string, book: NovelVO) {
  if (command === 'open') {
    router.push(`/novels/${book.id}`)
    return
  }
  if (command === 'write') {
    router.push({ path: `/novels/${book.id}`, query: { tab: 'content' } })
    return
  }
  if (command === 'cover') {
    openCover(book)
    return
  }
  if (command === 'export') {
    void exportBook(book)
    return
  }
  if (command === 'export-txt') {
    void exportBookTxt(book)
    return
  }
  if (command === 'delete') {
    void removeBook(book)
  }
}

/* ---------- 导出 / 导入 ---------- */
const importing = ref(false)
const importInputRef = ref<HTMLInputElement | null>(null)

async function exportBook(book: NovelVO) {
  try {
    const blob = await exportNovel(book.id)
    saveBlob(blob, `${book.title || 'novel'}-export.json`)
    ElMessage.success('已导出 JSON')
  } catch (error) {
    ElMessage.error((error as Error).message || '导出失败')
  }
}

async function exportBookTxt(book: NovelVO) {
  try {
    const blob = await exportNovelTxt(book.id, { scope: 'all' })
    saveBlob(blob, `${book.title || 'novel'}.txt`)
    ElMessage.success('已导出 TXT')
  } catch (error) {
    ElMessage.error((error as Error).message || '导出失败')
  }
}

/** 统一的浏览器下载，Blob 用完即释放。 */
function saveBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  URL.revokeObjectURL(url)
}

function pickImportFile() {
  importInputRef.value?.click()
}
async function onImportFileChange(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) {
    return
  }
  importing.value = true
  try {
    const text = await file.text()
    let pkg: unknown
    try {
      pkg = JSON.parse(text)
    } catch {
      ElMessage.error('文件不是合法的 JSON')
      return
    }
    const novel = await importNovel(pkg)
    ElMessage.success(`已导入《${novel.title}》`)
    await load()
  } catch (error) {
    ElMessage.error((error as Error).message || '导入失败')
  } finally {
    importing.value = false
  }
}

async function removeBook(book: NovelVO) {
  try {
    await ElMessageBox.confirm(
      `删除后《${book.title}》的设定与角色也会一并删除，且不可恢复。确定删除吗？`,
      '删除小说',
      { type: 'warning', confirmButtonText: '确定删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await deleteNovel(book.id)
    ElMessage.success('已删除')
    if (records.value.length === 1 && page.value > 1) {
      page.value -= 1
    }
    await load()
  } catch (error) {
    ElMessage.error((error as Error).message || '删除失败')
  }
}

/* ---------- 创建 ---------- */
function openCreate() {
  resetCreate()
  createVisible.value = true
}

function resetCreate() {
  step.value = 0
  generating.value = false
  saving.value = false
  blueprint.value = null
  generateMeta.value = null
  asking.value = false
  askingLoading.value = false
  questions.value = []
  constraintDraft.value = ''
  Object.keys(answers).forEach((key) => delete answers[key])
  resetTaskState()
  form.idea = ''
  form.title = ''
  form.penName = ''
  form.genre = ''
  form.tags = []
  form.style = ''
  form.targetLength = ''
  form.protagonistHint = ''
}

function resetTaskState() {
  stopTimers()
  taskPercent.value = 0
  taskLabel.value = ''
  taskMessage.value = ''
  taskStepNo.value = 0
  taskStatus.value = 'RUNNING'
  elapsed.value = 0
}

function closeCreate() {
  if (generating.value || saving.value) {
    return
  }
  createVisible.value = false
}

/** 组装请求：追问与生成两处共用，保证 AI 在两步看到的是同一份作者信息 */
function buildRequest(constraintText?: string) {
  return {
    title: form.title.trim() || undefined,
    genre: form.genre || undefined,
    tags: form.tags,
    idea: form.idea.trim(),
    style: form.style || undefined,
    targetLength: form.targetLength || undefined,
    protagonistHint: form.protagonistHint.trim() || undefined,
    constraintText: constraintText || undefined
  }
}

/**
 * 第一步：让 AI 判断这个创意还缺哪些关键信息。
 *
 * <p>追问只是锦上添花——接口失败或没问到问题，都直接进生成，绝不把建书卡在这一步。</p>
 */
async function onGenerate() {
  if (!form.idea.trim()) {
    ElMessage.warning('请先写下核心创意')
    return
  }
  if (form.idea.trim().length < 6) {
    ElMessage.warning('核心创意太短了，再多写两句')
    return
  }
  asking.value = false
  questions.value = []
  constraintDraft.value = ''
  Object.keys(answers).forEach((key) => delete answers[key])
  askingLoading.value = true
  try {
    const result = await blueprintQuestions(buildRequest())
    questions.value = result.questions ?? []
  } catch (error) {
    console.warn('[bookshelf] 追问失败，直接生成', error)
    questions.value = []
  } finally {
    askingLoading.value = false
  }
  if (questions.value.length === 0) {
    await doGenerate('')
    return
  }
  asking.value = true
}

/** 第二步：作者答完 → 组装成落库用的约束文本 → 开始四段生成 */
async function onConfirmAnswers() {
  const filled = questions.value
    .map((item) => ({ key: item.key, label: item.label, value: (answers[item.key] || '').trim() }))
    .filter((item) => item.value.length > 0)
  let text = ''
  if (filled.length > 0) {
    try {
      const result = await assembleConstraints(filled.map(({ key, value }) => ({ key, value })))
      text = result.constraintText || ''
    } catch (error) {
      // 组装接口挂了就用本地拼法，格式与后端一致：「中文名：答案」逐行
      console.warn('[bookshelf] 约束组装失败，改用本地拼装', error)
      text = filled.map((item) => `${item.label}：${item.value}`).join('\n')
    }
  }
  asking.value = false
  await doGenerate(text)
}

/** 不想逐条回答：直接用已填信息开工 */
async function onSkipAnswers() {
  asking.value = false
  await doGenerate('')
}

async function doGenerate(constraintText: string) {
  constraintDraft.value = constraintText
  resetTaskState()
  generating.value = true
  taskStatus.value = 'RUNNING'
  taskPercent.value = 3
  taskLabel.value = '正在准备生成任务'
  elapsedTimer = window.setInterval(() => {
    elapsed.value += 1
  }, 1000)
  try {
    const started = await startBlueprintTask(buildRequest(constraintText))
    taskPercent.value = started.percent || 4
    taskLabel.value = started.stepLabel || '正在生成世界观'
    const final = await pollBlueprintTask(started.taskId)
    const result = final.result
    if (!result) {
      throw new Error('生成结果为空，请重试')
    }
    blueprint.value = normalizeBlueprint(result.blueprint)
    generateMeta.value = result
    step.value = 1
    ElMessage.success(`设定生成完成，用时 ${Math.round((result.latencyMs || 0) / 1000)} 秒`)
  } catch (error) {
    taskStatus.value = 'FAILED'
    ElMessage.error((error as Error).message || 'AI 生成失败，请稍后重试')
  } finally {
    generating.value = false
    stopTimers()
  }
}

/** 轮询任务进度：800ms 一次，直到成功或失败 */
function pollBlueprintTask(taskId: string): Promise<BlueprintTaskVO> {
  return new Promise((resolve, reject) => {
    const tick = async () => {
      try {
        const snapshot = await getBlueprintTask(taskId)
        taskPercent.value = snapshot.percent
        taskLabel.value = snapshot.stepLabel
        taskMessage.value = snapshot.message || ''
        taskStepNo.value = snapshot.stepNo
        taskStatus.value = snapshot.status
        if (snapshot.status === 'SUCCEEDED') {
          resolve(snapshot)
          return
        }
        if (snapshot.status === 'FAILED') {
          reject(new Error(snapshot.errorMessage || 'AI 生成失败，请重试'))
          return
        }
        pollTimer = window.setTimeout(tick, 800)
      } catch (error) {
        reject(error)
      }
    }
    void tick()
  })
}

function stopTimers() {
  if (pollTimer) {
    window.clearTimeout(pollTimer)
    pollTimer = undefined
  }
  if (elapsedTimer) {
    window.clearInterval(elapsedTimer)
    elapsedTimer = undefined
  }
}

onBeforeUnmount(stopTimers)

async function onRegenerate() {
  step.value = 0
  // 已经确认过的约束不再重问，直接沿用
  await doGenerate(constraintDraft.value)
}

async function onSave() {
  if (!blueprint.value) {
    return
  }
  const title = (blueprint.value.title || form.title).trim()
  if (!title) {
    ElMessage.warning('请填写书名')
    return
  }
  saving.value = true
  try {
    const novel = await createNovel({
      title,
      penName: form.penName.trim() || undefined,
      genre: blueprint.value.genre || form.genre || undefined,
      tags: blueprint.value.tags ?? [],
      intro: blueprint.value.synopsis,
      status: 'DRAFT',
      blueprint: blueprint.value
    })
    createVisible.value = false
    ElMessage.success(`《${novel.title}》已加入书架`)
    await load()
    void askOpenDetail(novel)
  } catch (error) {
    ElMessage.error((error as Error).message || '保存失败')
  } finally {
    saving.value = false
  }
}

/** 只建草稿：不调用 AI，设定留待后续补充 */
async function createWithoutAi() {
  const title = form.title.trim()
  if (!title) {
    ElMessage.warning('只建草稿需要先填书名')
    return
  }
  saving.value = true
  try {
    const novel = await createNovel({
      title,
      penName: form.penName.trim() || undefined,
      genre: form.genre || undefined,
      tags: form.tags,
      intro: form.idea.trim() || undefined,
      status: 'DRAFT'
    })
    createVisible.value = false
    ElMessage.success(`《${novel.title}》已加入书架`)
    await load()
    void askOpenDetail(novel)
  } catch (error) {
    ElMessage.error((error as Error).message || '创建失败')
  } finally {
    saving.value = false
  }
}

async function askOpenDetail(novel: NovelVO) {
  try {
    await ElMessageBox.confirm(`已创建《${novel.title}》，是否现在查看并补全设定？`, '创建成功', {
      confirmButtonText: '去查看',
      cancelButtonText: '留在书架',
      type: 'success'
    })
    router.push(`/novels/${novel.id}`)
  } catch {
    // 留在书架
  }
}

/* ---------- 展示工具 ---------- */
function statusLabel(value: string) {
  return statusOptions.find((item) => item.value === value)?.label ?? value
}
</script>

<style scoped>
.bookshelf-page {
  min-height: calc(100vh - 88px);
}

.shelf-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}

.shelf-title {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
  color: var(--sf-text-primary);
}

.shelf-sub {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--sf-text-muted);
}

.head-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.search-input {
  width: 200px;
}

.status-select {
  width: 128px;
}

.shelf-body {
  min-height: 320px;
}

.book-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(168px, 1fr));
  gap: 18px 16px;
}

.book-card {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 12px 12px 14px;
  background: var(--sf-bg-card);
  border: 1px solid var(--sf-border);
  border-radius: 10px;
  cursor: pointer;
  transition: transform 0.16s ease, box-shadow 0.16s ease, border-color 0.16s ease;
}

.book-card:hover {
  transform: translateY(-2px);
  border-color: var(--sf-brand);
  box-shadow: 0 8px 20px rgba(31, 41, 55, 0.08);
}

.book-top {
  display: flex;
  align-items: center;
  gap: 4px;
  min-height: 22px;
}

.card-cover {
  width: 100%;
}

.book-bottom {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.book-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  min-height: 22px;
}

.book-badge {
  flex: none;
  font-size: 11px;
  color: var(--sf-text-muted);
}

.book-source {
  font-size: 11px;
  color: var(--sf-text-muted);
}

.more-btn {
  margin-left: auto;
  flex: none;
  padding: 2px;
  color: var(--sf-text-muted);
}

.book-title {
  margin: 0;
  flex: 1;
  min-width: 0;
  font-size: 15px;
  font-weight: 600;
  color: var(--sf-text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.book-intro {
  margin: 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--sf-text-regular);
  height: 38px;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.empty-shelf {
  padding: 60px 0 80px;
  text-align: center;
}

.empty-mark {
  width: 66px;
  height: 66px;
  margin: 0 auto 18px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 18px;
  background: linear-gradient(135deg, #eef2ff, #e0e7ff);
  color: var(--sf-brand);
  font-size: 18px;
  font-weight: 700;
}

.empty-title {
  margin: 0 0 8px;
  font-size: 16px;
  font-weight: 600;
  color: var(--sf-text-primary);
}

.empty-desc {
  margin: 0 auto 20px;
  max-width: 420px;
  font-size: 13px;
  line-height: 1.7;
  color: var(--sf-text-muted);
}

.pager {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
}

/* ---------- 创建弹窗 ---------- */
.dialog-head {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.dialog-title {
  font-size: 16px;
  font-weight: 600;
}

.dialog-hint {
  font-size: 12px;
  color: var(--sf-text-muted);
}

.create-steps {
  margin-bottom: 14px;
}

.dialog-body {
  max-height: 62vh;
  overflow-y: auto;
  padding-right: 4px;
}

.field-label {
  margin: 14px 0 6px;
  font-size: 12px;
  font-weight: 500;
  color: var(--sf-text-regular);
}

.field-label:first-child {
  margin-top: 0;
}

.required {
  color: var(--sf-text-danger);
}

/* 建书前的追问 */
.ask-intro {
  margin: 0 0 4px;
  font-size: 12px;
  line-height: 1.7;
  color: var(--sf-text-muted);
}

.ask-item {
  padding: 12px 14px;
  margin-top: 12px;
  border: 1px solid var(--sf-border);
  border-radius: 8px;
  background: var(--sf-bg-subtle);
}

.ask-item .field-label {
  margin: 0 0 8px;
  line-height: 1.6;
  font-weight: 500;
  color: var(--sf-text-primary);
}

.ask-tag {
  display: inline-block;
  padding: 1px 6px;
  margin-right: 8px;
  font-size: 11px;
  font-weight: 600;
  color: var(--sf-primary);
  background: var(--sf-primary-soft, rgba(64, 158, 255, 0.12));
  border-radius: 4px;
  vertical-align: 1px;
}

.w-full {
  width: 100%;
}

.idea-samples {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  margin-top: 8px;
}

.samples-label {
  font-size: 12px;
  color: var(--sf-text-muted);
}

.sample-chip {
  padding: 3px 10px;
  font-size: 12px;
  color: var(--sf-text-regular);
  background: var(--sf-bg-page);
  border: 1px solid var(--sf-border);
  border-radius: 12px;
  cursor: pointer;
  transition: all 0.15s ease;
}

.sample-chip:hover {
  color: var(--sf-brand);
  border-color: var(--sf-brand);
  background: var(--sf-bg-tint-strong);
}

.preview-form {
  padding-bottom: 6px;
}

.gen-meta {
  margin-top: 10px;
  font-size: 12px;
  color: var(--sf-text-muted);
  text-align: right;
}

.gen-state {
  padding: 46px 20px 56px;
  text-align: center;
}

.spin {
  font-size: 30px;
  color: var(--sf-brand);
  animation: rotating 1.2s linear infinite;
}

@keyframes rotating {
  to {
    transform: rotate(360deg);
  }
}

.gen-title {
  margin: 16px 0 6px;
  font-size: 16px;
  font-weight: 600;
  color: var(--sf-text-primary);
}

.gen-desc {
  margin: 0;
  font-size: 13px;
  color: var(--sf-text-muted);
}

.gen-percent {
  margin: 14px 0 8px;
  font-size: 28px;
  font-weight: 700;
  color: var(--sf-brand);
  font-variant-numeric: tabular-nums;
}

.gen-progress {
  max-width: 520px;
  margin: 0 auto;
}

.gen-alert {
  max-width: 520px;
  margin: 12px auto 0;
  text-align: left;
}

.gen-steps {
  margin-top: 18px;
  display: flex;
  justify-content: center;
  gap: 8px;
  flex-wrap: wrap;
}

.gen-step {
  padding: 5px 12px;
  font-size: 12px;
  color: var(--sf-text-muted);
  background: var(--sf-bg-page);
  border: 1px solid transparent;
  border-radius: 14px;
  transition: all 0.2s ease;
}

.gen-step.done {
  color: var(--sf-brand);
  background: rgba(59, 110, 246, 0.1);
}

.gen-step.active {
  color: #fff;
  background: var(--sf-brand);
  border-color: var(--sf-brand);
}

.gen-lines {
  margin-top: 20px;
  display: flex;
  justify-content: center;
  gap: 10px;
  flex-wrap: wrap;
}

.gen-lines span {
  padding: 5px 12px;
  font-size: 12px;
  color: var(--sf-text-regular);
  background: var(--sf-bg-page);
  border-radius: 14px;
}

.dialog-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.footer-right {
  display: flex;
  gap: 8px;
}
</style>
