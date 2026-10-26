<template>
  <el-dialog
    v-model="visible"
    width="1020px"
    top="4vh"
    :close-on-click-modal="false"
    :close-on-press-escape="step !== 'running'"
    :show-close="!saving"
    destroy-on-close
  >
    <template #header>
      <div class="dc-head">
        <span class="dc-title">拆书改编</span>
        <span class="dc-sub">
          {{
            step === 'upload'
              ? '上传一本完成的小说，AI 拆出主线脉络，脱敏改编成一部新书'
              : step === 'running'
                ? '正在拆解与重建，篇幅越长耗时越久'
                : '核对改编结果，确认无误后保存为新小说'
          }}
        </span>
      </div>
    </template>

    <!-- 一、上传与设置 -->
    <div v-if="step === 'upload'" class="dc-body">
      <el-upload
        drag
        class="dc-drop"
        :auto-upload="false"
        :show-file-list="false"
        accept=".txt,.md,.markdown,text/plain"
        :on-change="onFileChange"
      >
        <template v-if="!file">
          <el-icon class="dc-drop-icon"><UploadFilled /></el-icon>
          <div class="dc-drop-title">把小说文本拖到这里，或点击选择文件</div>
          <div class="dc-drop-desc">
            支持 txt / md，单文件不超过 10MB，至少 3000 汉字；原文只用于当次分析，不会保存到服务器
          </div>
        </template>
        <template v-else>
          <div class="dc-file">
            <el-icon class="dc-file-icon"><Document /></el-icon>
            <div class="dc-file-name">{{ file.name }}</div>
            <div class="dc-file-size">{{ fileSizeLabel }}</div>
            <div class="dc-file-tip">点击可重新选择</div>
          </div>
        </template>
      </el-upload>

      <div class="dc-block">
        <div class="dc-label">
          改编程度
          <span class="dc-label-hint">决定保留原作多少骨架</span>
        </div>
        <div class="dc-levels">
          <button
            v-for="item in LEVELS"
            :key="item.value"
            type="button"
            class="dc-level"
            :class="{ active: form.adaptationLevel === item.value }"
            @click="form.adaptationLevel = item.value"
          >
            <span class="dc-level-name">{{ item.label }}</span>
            <span class="dc-level-desc">{{ item.desc }}</span>
          </button>
        </div>
      </div>

      <div class="dc-row">
        <div class="dc-col">
          <div class="dc-label">
            背景改编方向
            <span class="dc-label-hint">选填，写明希望改成什么背景</span>
          </div>
          <el-input
            v-model="form.backgroundDirection"
            type="textarea"
            :rows="3"
            maxlength="500"
            show-word-limit
            placeholder="如：改成近未来的沿海都市，职场与家族并行；或：把宗门换成跨国制药公司"
          />
          <div class="dc-chips">
            <button
              v-for="item in DIRECTION_SAMPLES"
              :key="item"
              type="button"
              class="dc-chip"
              @click="form.backgroundDirection = item"
            >
              {{ item }}
            </button>
          </div>
        </div>
        <div class="dc-col dc-col-narrow">
          <div class="dc-label">大纲章节数</div>
          <el-input-number v-model="form.chapterTarget" :min="8" :max="24" :step="2" controls-position="right" class="w-full" />
          <p class="dc-tip">只生成章节大纲与事件，不生成正文</p>
          <div class="dc-label mt16">历史拆书</div>
          <div v-if="history.length" class="dc-history">
            <button
              v-for="item in history"
              :key="item.taskId"
              type="button"
              class="dc-history-item"
              @click="openHistory(item)"
            >
              <span class="hi-name">{{ item.title || item.sourceName }}</span>
              <span class="hi-meta">
                {{ item.adaptationLabel }} · {{ item.chapterTarget }} 章 ·
                {{ item.status === 'SUCCEEDED' ? '已完成' : item.status === 'RUNNING' ? '分析中' : '失败' }}
              </span>
            </button>
          </div>
          <p v-else class="dc-tip">暂无拆书记录</p>
        </div>
      </div>

      <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon class="mt12" />
    </div>

    <!-- 二、分析中 -->
    <div v-else-if="step === 'running'" class="dc-running">
      <el-icon class="spin dc-spin"><Loading /></el-icon>
      <p class="dc-run-title">{{ task?.stepLabel || '正在准备' }}</p>
      <p class="dc-run-percent">{{ task?.percent ?? 0 }}%</p>
      <el-progress
        class="dc-progress"
        :percentage="task?.percent ?? 0"
        :stroke-width="10"
        :show-text="false"
      />
      <div class="dc-steps">
        <span
          v-for="(item, index) in STEPS"
          :key="item.label"
          class="dc-step"
          :class="{ done: (task?.percent ?? 0) >= item.percent, active: task?.stepNo === index + 1 }"
        >
          {{ item.label }}
        </span>
      </div>
      <p class="dc-run-meta">
        原文 {{ task?.sourceChars?.toLocaleString() || '-' }} 汉字
        <span v-if="task?.sourceChapters"> · {{ task.sourceChapters }} 章</span>
        · 共 6 段，每段最长 5 分钟 · 已用 {{ elapsed }} 秒
      </p>
      <el-alert v-if="task?.message" :title="task.message" type="warning" :closable="false" show-icon class="mt12" />
      <p class="dc-run-tip">
        这一步会逐段通读原文、梳理主线，再按「{{ levelLabel }}」重建世界观、人物、势力与大纲。长篇小说通常需要 3~8 分钟。
      </p>
    </div>

    <!-- 三、结果预览 -->
    <div v-else class="dc-preview">
      <div class="dc-headline">
        <div class="dc-headline-main">
          <h3 class="dc-book-title">{{ result?.title || '未命名改编' }}</h3>
          <div class="dc-book-meta">
            <el-tag v-if="result?.genre" size="small">{{ result.genre }}</el-tag>
            <el-tag v-for="tag in result?.tags || []" :key="tag" size="small" type="info" effect="plain">{{ tag }}</el-tag>
            <span class="dc-level-badge">{{ result?.adaptationLabel }}改编</span>
            <span v-if="result?.sampled" class="dc-sampled">原文较长，按头/中/尾抽样通读</span>
          </div>
        </div>
        <div class="dc-headline-side">
          <span>{{ result?.chapters?.length || 0 }} 章大纲</span>
          <span>{{ result?.events?.length || 0 }} 个事件</span>
          <span>{{ 1 + (result?.characters?.length || 0) }} 个人物</span>
          <span>{{ result?.factions?.length || 0 }} 个势力</span>
        </div>
      </div>

      <el-tabs v-model="activeTab" class="dc-tabs">
        <el-tab-pane label="主线脉络" name="spine">
          <div class="dc-section">
            <div class="dc-logline">{{ result?.spine?.logline || '—' }}</div>
            <div class="dc-kv">
              <span class="dc-k">主题命题</span>
              <span class="dc-v">{{ result?.spine?.theme || '—' }}</span>
            </div>
            <div class="dc-kv">
              <span class="dc-k">主线走向</span>
              <span class="dc-v dc-pre">{{ result?.spine?.mainline || '—' }}</span>
            </div>
            <div v-if="result?.spine?.acts?.length" class="dc-kv">
              <span class="dc-k">分幕</span>
              <div class="dc-v dc-acts">
                <div v-for="(act, index) in result.spine.acts" :key="index" class="dc-act">
                  <div class="dc-act-head">
                    <b>{{ act.name || '第 ' + (index + 1) + ' 幕' }}</b>
                    <span v-if="act.range" class="dc-act-range">{{ act.range }}</span>
                  </div>
                  <p>{{ act.content }}</p>
                </div>
              </div>
            </div>
            <div v-if="result?.spine?.turns?.length" class="dc-kv">
              <span class="dc-k">关键转折</span>
              <ul class="dc-list dc-v">
                <li v-for="(item, index) in result.spine.turns" :key="index">{{ item }}</li>
              </ul>
            </div>
            <div v-if="result?.spine?.characterFunctions?.length" class="dc-kv">
              <span class="dc-k">人物功能</span>
              <div class="dc-v">
                <div v-for="(item, index) in result.spine.characterFunctions" :key="index" class="dc-fn">
                  <el-tag size="small" effect="plain">{{ item.function }}</el-tag>
                  <span class="dc-fn-name">{{ item.originalRole }}</span>
                  <span class="dc-fn-role">{{ item.role }}</span>
                </div>
              </div>
            </div>
            <div class="dc-kv">
              <span class="dc-k">结局</span>
              <span class="dc-v dc-pre">{{ result?.spine?.ending || '—' }}</span>
            </div>
          </div>
        </el-tab-pane>

        <el-tab-pane label="设定" name="setting">
          <div class="dc-section">
            <div class="dc-kv">
              <span class="dc-k">简介</span>
              <span class="dc-v dc-pre">{{ result?.synopsis || '—' }}</span>
            </div>
            <div class="dc-kv">
              <span class="dc-k">世界观</span>
              <span class="dc-v dc-pre">{{ result?.worldview || '—' }}</span>
            </div>
            <div class="dc-kv">
              <span class="dc-k">背景</span>
              <span class="dc-v dc-pre">{{ result?.background || '—' }}</span>
            </div>
            <div class="dc-kv">
              <span class="dc-k">核心冲突</span>
              <span class="dc-v dc-pre">{{ result?.coreConflict || '—' }}</span>
            </div>
            <div v-if="result?.highlights?.length" class="dc-kv">
              <span class="dc-k">核心看点</span>
              <div class="dc-v dc-tagline">
                <el-tag v-for="item in result.highlights" :key="item" size="small" effect="plain">{{ item }}</el-tag>
              </div>
            </div>
            <div class="dc-kv">
              <span class="dc-k">文风</span>
              <span class="dc-v">{{ result?.style || '—' }}</span>
            </div>
            <div v-if="result?.backgroundDirection" class="dc-kv">
              <span class="dc-k">改编方向</span>
              <span class="dc-v">{{ result.backgroundDirection }}</span>
            </div>
          </div>
        </el-tab-pane>

        <el-tab-pane label="人物与势力" name="cast">
          <div class="dc-section">
            <div v-if="result?.protagonist" class="dc-proto">
              <div class="dc-proto-head">
                <span class="dc-proto-name">{{ result.protagonist.name }}</span>
                <el-tag size="small" type="success">主角</el-tag>
                <span class="dc-proto-identity">{{ result.protagonist.identity }}</span>
                <span v-if="result.protagonist.gender || result.protagonist.ageDesc" class="dc-proto-meta">
                  {{ [result.protagonist.gender, result.protagonist.ageDesc].filter(Boolean).join(' · ') }}
                </span>
              </div>
              <p v-if="result.protagonist.personality" class="dc-proto-line">
                <b>性格</b>{{ result.protagonist.personality }}
              </p>
              <p v-if="result.protagonist.motivation" class="dc-proto-line">
                <b>动机</b>{{ result.protagonist.motivation }}
              </p>
              <p v-if="result.protagonist.goldenFinger" class="dc-proto-line">
                <b>优势</b>{{ result.protagonist.goldenFinger }}
              </p>
              <p v-if="result.protagonist.arc" class="dc-proto-line">
                <b>弧线</b>{{ result.protagonist.arc }}
              </p>
            </div>

            <el-table v-if="result?.characters?.length" :data="result.characters" size="small" class="dc-table">
              <el-table-column prop="name" label="姓名" width="110" />
              <el-table-column label="定位" width="80">
                <template #default="{ row }">
                  <el-tag size="small" :type="row.rolePos === 'ANTAGONIST' ? 'danger' : 'info'" effect="plain">
                    {{ ROLE_LABELS[row.rolePos] || '配角' }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="identity" label="身份" width="180" show-overflow-tooltip />
              <el-table-column prop="personality" label="性格" show-overflow-tooltip />
              <el-table-column prop="motivation" label="动机" show-overflow-tooltip />
            </el-table>

            <template v-if="result?.relations?.length">
              <div class="dc-sub-title">人物关系</div>
              <el-table :data="result.relations" size="small" class="dc-table">
                <el-table-column prop="from" label="一方" width="110" />
                <el-table-column prop="to" label="另一方" width="110" />
                <el-table-column label="关系" width="90">
                  <template #default="{ row }">{{ RELATION_LABELS[row.type] || '其他' }}</template>
                </el-table-column>
                <el-table-column prop="description" label="说明" show-overflow-tooltip />
              </el-table>
            </template>

            <template v-if="result?.factions?.length">
              <div class="dc-sub-title">势力</div>
              <div class="dc-factions">
                <div v-for="faction in result.factions" :key="faction.name" class="dc-faction">
                  <div class="dc-faction-head">
                    <b>{{ faction.name }}</b>
                    <el-tag size="small" effect="plain">{{ faction.category || '组织' }}</el-tag>
                    <el-tag size="small" :type="faction.status === 'DESTROYED' ? 'danger' : 'info'" effect="plain">
                      {{ FACTION_LABELS[faction.status || 'ACTIVE'] }}
                    </el-tag>
                  </div>
                  <p v-if="faction.scope" class="dc-faction-line"><b>范围</b>{{ faction.scope }}</p>
                  <p v-if="faction.remark" class="dc-faction-line"><b>备注</b>{{ faction.remark }}</p>
                  <div class="dc-members">
                    <span v-for="member in faction.members" :key="member.character" class="dc-member">
                      {{ member.character }}<i v-if="member.position">· {{ member.position }}</i>
                    </span>
                  </div>
                </div>
              </div>
            </template>
          </div>
        </el-tab-pane>

        <el-tab-pane :label="`大纲（${result?.chapters?.length || 0}）`" name="outline">
          <div class="dc-section dc-outline">
            <div v-for="group in volumeGroups" :key="group.volumeNo" class="dc-volume">
              <div class="dc-volume-head">
                第 {{ group.volumeNo }} 卷
                <span v-if="group.volumeTitle">· {{ group.volumeTitle }}</span>
              </div>
              <div v-for="chapter in group.chapters" :key="chapter.chapterNo" class="dc-chapter">
                <div class="dc-chapter-head">
                  <span class="dc-chapter-no">第 {{ chapter.chapterNo }} 章</span>
                  <span class="dc-chapter-title">{{ chapter.title }}</span>
                </div>
                <p class="dc-chapter-summary">{{ chapter.summary }}</p>
                <div class="dc-chapter-tags">
                  <el-tag v-for="name in chapter.characters" :key="name" size="small" effect="plain">{{ name }}</el-tag>
                  <el-tag v-for="title in chapter.events" :key="title" size="small" type="warning" effect="plain">
                    {{ title }}
                  </el-tag>
                </div>
              </div>
            </div>
          </div>
        </el-tab-pane>

        <el-tab-pane :label="`事件（${result?.events?.length || 0}）`" name="events">
          <div class="dc-section">
            <el-table :data="result?.events || []" size="small" class="dc-table">
              <el-table-column prop="title" label="事件" width="180" show-overflow-tooltip />
              <el-table-column prop="eventTime" label="时间" width="110" />
              <el-table-column prop="description" label="描述" show-overflow-tooltip />
              <el-table-column label="参与角色" width="200">
                <template #default="{ row }">
                  <el-tag v-for="name in row.participants" :key="name" size="small" effect="plain" class="mr4">
                    {{ name }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="状态" width="90">
                <template #default="{ row }">{{ EVENT_LABELS[row.status] || '未使用' }}</template>
              </el-table-column>
            </el-table>
          </div>
        </el-tab-pane>
      </el-tabs>
    </div>

    <template #footer>
      <div class="dc-footer">
        <div class="dc-footer-left">
          <span v-if="step === 'preview' && result?.model" class="dc-model">
            由 {{ result.model }} 完成，耗时 {{ ((result.latencyMs || 0) / 1000).toFixed(0) }} 秒
          </span>
        </div>
        <div class="dc-footer-right">
          <el-button :disabled="saving" @click="visible = false">{{ step === 'preview' ? '关闭' : '取消' }}</el-button>
          <el-button v-if="step === 'upload'" type="primary" :loading="starting" :disabled="!file" @click="onStart">
            开始拆书
          </el-button>
          <template v-else-if="step === 'preview'">
            <el-button :disabled="saving" @click="restart">重新拆解</el-button>
            <el-button v-if="task?.generatedNovelId" type="primary" @click="openNovel">打开工作台</el-button>
            <el-button v-else type="primary" :loading="saving" @click="onSave">保存到书架</el-button>
          </template>
        </div>
      </div>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Document, Loading, UploadFilled } from '@element-plus/icons-vue'
import {
  getDeconstructTask,
  listDeconstruct,
  saveDeconstruct,
  startDeconstruct,
  type DeconstructChapter,
  type DeconstructSummaryVO,
  type DeconstructTaskVO
} from '@/api/deconstruct'

const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'saved', novelId: string): void
}>()

const router = useRouter()

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value)
})

const LEVELS = [
  { value: 'LIGHT', label: '轻度', desc: '换名字不换骨架：世界规则与主线走向保留，专有名词全部替换' },
  { value: 'MEDIUM', label: '中度', desc: '换背景守骨架：时代/地域/行业换一层外壳，主线与结局方向保住' },
  { value: 'HEAVY', label: '重度', desc: '换骨留内核：重新设计世界观与人物，转折重新发明' },
  { value: 'REBUILD', label: '完全重构', desc: '只借结构手法：题材都可更换，读者认不出原作' }
]

const DIRECTION_SAMPLES = [
  '改成近未来沿海都市，资本与家族并行',
  '把宗门换成跨国制药公司',
  '改成民国铁路小城，加入悬疑调查线',
  '换成星际殖民地的拓荒时代'
]

const STEPS = [
  { label: '通读全文', percent: 8 },
  { label: '主线脉络', percent: 44 },
  { label: '世界观与背景', percent: 58 },
  { label: '人物与势力', percent: 74 },
  { label: '大纲与事件', percent: 90 },
  { label: '整理结果', percent: 96 }
]

const RELATION_LABELS: Record<string, string> = {
  MASTER_APPRENTICE: '师徒',
  FAMILY: '亲属',
  SPOUSE: '夫妻',
  LOVER: '恋人',
  FRIEND: '好友',
  ALLY: '盟友',
  ENEMY: '仇敌',
  RIVAL: '对手',
  COLLEAGUE: '同门',
  OTHER: '其他'
}

const FACTION_LABELS: Record<string, string> = {
  ACTIVE: '活跃',
  DORMANT: '蛰伏',
  DESTROYED: '覆灭',
  DISBANDED: '解散'
}

const EVENT_LABELS: Record<string, string> = {
  UNUSED: '未使用',
  ONGOING: '进行中',
  DONE: '已完成',
  ABANDONED: '废弃'
}

const ROLE_LABELS: Record<string, string> = {
  PROTAGONIST: '主角',
  SUPPORTING: '配角',
  ANTAGONIST: '反派',
  MINOR: '龙套'
}

const step = ref<'upload' | 'running' | 'preview'>('upload')
const activeTab = ref('spine')
const file = ref<File | null>(null)
const starting = ref(false)
const saving = ref(false)
const error = ref('')
const task = ref<DeconstructTaskVO | null>(null)
const history = ref<DeconstructSummaryVO[]>([])
const elapsed = ref(0)

const form = reactive({
  adaptationLevel: 'MEDIUM',
  backgroundDirection: '',
  chapterTarget: 16
})

let pollTimer: number | undefined
let elapsedTimer: number | undefined

const result = computed(() => task.value?.result)
const levelLabel = computed(() => LEVELS.find((item) => item.value === form.adaptationLevel)?.label || '中度')
const fileSizeLabel = computed(() => {
  if (!file.value) {
    return ''
  }
  const mb = file.value.size / 1024 / 1024
  return mb >= 1 ? `${mb.toFixed(2)} MB` : `${Math.max(1, Math.round(file.value.size / 1024))} KB`
})

const volumeGroups = computed(() => {
  const chapters = result.value?.chapters || []
  const groups: Array<{ volumeNo: number; volumeTitle?: string; chapters: DeconstructChapter[] }> = []
  for (const chapter of chapters) {
    const volumeNo = chapter.volumeNo || 1
    let group = groups.find((item) => item.volumeNo === volumeNo)
    if (!group) {
      group = { volumeNo, volumeTitle: chapter.volumeTitle, chapters: [] }
      groups.push(group)
    }
    if (!group.volumeTitle && chapter.volumeTitle) {
      group.volumeTitle = chapter.volumeTitle
    }
    group.chapters.push(chapter)
  }
  return groups
})

watch(
  () => props.modelValue,
  (value) => {
    if (value) {
      reset()
      void loadHistory()
    } else {
      stopTimers()
    }
  }
)

function reset() {
  stopTimers()
  step.value = 'upload'
  activeTab.value = 'spine'
  file.value = null
  task.value = null
  error.value = ''
  elapsed.value = 0
  starting.value = false
  saving.value = false
  form.adaptationLevel = 'MEDIUM'
  form.backgroundDirection = ''
  form.chapterTarget = 16
}

function stopTimers() {
  if (pollTimer) {
    window.clearInterval(pollTimer)
    pollTimer = undefined
  }
  if (elapsedTimer) {
    window.clearInterval(elapsedTimer)
    elapsedTimer = undefined
  }
}

async function loadHistory() {
  try {
    const data = await listDeconstruct(1, 5)
    history.value = data.records || []
  } catch {
    history.value = []
  }
}

function onFileChange(uploadFile: { raw?: File; name?: string; size?: number }) {
  const raw = uploadFile.raw
  if (!raw) {
    return
  }
  const name = (raw.name || '').toLowerCase()
  const dot = name.lastIndexOf('.')
  const ext = dot >= 0 ? name.slice(dot + 1) : ''
  if (ext && !['txt', 'text', 'md', 'markdown'].includes(ext)) {
    ElMessage.error('只支持 txt / md 纯文本')
    return
  }
  if (raw.size > 10 * 1024 * 1024) {
    ElMessage.error('文件超过 10MB，请拆分后再上传')
    return
  }
  file.value = raw
  error.value = ''
}

async function onStart() {
  if (!file.value) {
    ElMessage.warning('请先选择小说文件')
    return
  }
  starting.value = true
  error.value = ''
  try {
    const data = await startDeconstruct({
      file: file.value,
      adaptationLevel: form.adaptationLevel,
      backgroundDirection: form.backgroundDirection.trim() || undefined,
      chapterTarget: form.chapterTarget
    })
    task.value = data
    step.value = 'running'
    beginPolling(data.taskId)
  } catch (ex) {
    error.value = (ex as Error).message || '启动拆书失败'
  } finally {
    starting.value = false
  }
}

function beginPolling(taskId: string) {
  stopTimers()
  elapsed.value = 0
  elapsedTimer = window.setInterval(() => {
    elapsed.value += 1
  }, 1000)
  pollTimer = window.setInterval(() => {
    void poll(taskId)
  }, 1400)
  void poll(taskId)
}

async function poll(taskId: string) {
  try {
    const data = await getDeconstructTask(taskId)
    task.value = data
    if (data.status === 'SUCCEEDED') {
      stopTimers()
      step.value = 'preview'
      activeTab.value = 'spine'
      void loadHistory()
    } else if (data.status === 'FAILED') {
      stopTimers()
      error.value = data.errorMessage || '拆书失败，请重试'
      step.value = 'upload'
    }
  } catch (ex) {
    stopTimers()
    error.value = (ex as Error).message || '查询拆书进度失败'
    step.value = 'upload'
  }
}

async function openHistory(item: DeconstructSummaryVO) {
  error.value = ''
  try {
    const data = await getDeconstructTask(item.taskId)
    task.value = data
    if (data.status === 'SUCCEEDED') {
      step.value = 'preview'
      activeTab.value = 'spine'
    } else if (data.status === 'RUNNING') {
      step.value = 'running'
      beginPolling(data.taskId)
    } else {
      error.value = data.errorMessage || '这次拆书没有完成，可以重新上传'
      step.value = 'upload'
    }
  } catch (ex) {
    error.value = (ex as Error).message || '加载拆书记录失败'
  }
}

function restart() {
  stopTimers()
  task.value = null
  error.value = ''
  step.value = 'upload'
  void loadHistory()
}

async function onSave() {
  if (!task.value) {
    return
  }
  saving.value = true
  try {
    const novelId = await saveDeconstruct(task.value.taskId)
    task.value.generatedNovelId = novelId
    emit('saved', novelId)
    ElMessage.success(`已保存《${result.value?.title || '新书'}》，设定、人物、势力、事件与大纲均已写入`)
    void loadHistory()
  } catch (ex) {
    ElMessage.error((ex as Error).message || '保存失败')
  } finally {
    saving.value = false
  }
}

function openNovel() {
  const novelId = task.value?.generatedNovelId
  if (!novelId) {
    return
  }
  visible.value = false
  router.push(`/novels/${novelId}`)
}
</script>

<style scoped>
.dc-head {
  display: flex;
  align-items: baseline;
  gap: 12px;
}

.dc-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--sf-text-primary);
}

.dc-sub {
  font-size: 12px;
  color: var(--sf-text-muted);
}

.dc-body {
  padding: 4px 2px;
}

.dc-drop :deep(.el-upload-dragger) {
  padding: 26px 16px;
  border-radius: 10px;
  border: 1px dashed var(--sf-border-strong);
  background: var(--sf-bg-subtle);
}

.dc-drop-icon {
  font-size: 34px;
  color: var(--sf-brand);
}

.dc-drop-title {
  margin-top: 8px;
  font-size: 14px;
  color: var(--sf-text-primary);
}

.dc-drop-desc {
  margin-top: 4px;
  font-size: 12px;
  color: var(--sf-text-muted);
}

.dc-file {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
}

.dc-file-icon {
  font-size: 28px;
  color: var(--sf-brand);
}

.dc-file-name {
  font-size: 14px;
  font-weight: 600;
}

.dc-file-size,
.dc-file-tip {
  font-size: 12px;
  color: var(--sf-text-muted);
}

.dc-block {
  margin-top: 16px;
}

.dc-label {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin-bottom: 8px;
  font-size: 13px;
  font-weight: 600;
  color: var(--sf-text-primary);
}

.dc-label-hint {
  font-size: 12px;
  font-weight: 400;
  color: var(--sf-text-muted);
}

.dc-levels {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 10px;
}

.dc-level {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 12px;
  text-align: left;
  cursor: pointer;
  background: var(--sf-bg-card);
  border: 1px solid var(--sf-border);
  border-radius: 8px;
  transition: all 0.15s;
}

.dc-level:hover {
  border-color: var(--sf-brand);
}

.dc-level.active {
  border-color: var(--sf-brand);
  box-shadow: 0 0 0 3px rgba(59, 110, 246, 0.12);
}

.dc-level-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--sf-text-primary);
}

.dc-level-desc {
  font-size: 11px;
  line-height: 1.5;
  color: var(--sf-text-muted);
}

.dc-row {
  display: grid;
  grid-template-columns: 1.6fr 1fr;
  gap: 18px;
  margin-top: 16px;
}

.dc-col-narrow {
  border-left: 1px solid var(--sf-border);
  padding-left: 18px;
}

.dc-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 8px;
}

.dc-chip {
  padding: 3px 8px;
  font-size: 11px;
  color: var(--sf-text-regular);
  cursor: pointer;
  background: var(--sf-bg-tint);
  border: none;
  border-radius: 12px;
}

.dc-chip:hover {
  color: var(--sf-brand);
  background: var(--sf-bg-tint);
}

.dc-tip {
  margin: 8px 0 0;
  font-size: 11px;
  line-height: 1.5;
  color: var(--sf-text-muted);
}

.mt12 {
  margin-top: 12px;
}

.mt16 {
  margin-top: 16px;
}

.mr4 {
  margin-right: 4px;
}

.dc-history {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.dc-history-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 7px 9px;
  text-align: left;
  cursor: pointer;
  background: var(--sf-bg-subtle);
  border: 1px solid var(--sf-border);
  border-radius: 6px;
}

.dc-history-item:hover {
  border-color: var(--sf-brand);
}

.hi-name {
  font-size: 12px;
  color: var(--sf-text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.hi-meta {
  font-size: 11px;
  color: var(--sf-text-muted);
}

.dc-running {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 26px 40px 18px;
  text-align: center;
}

.dc-spin {
  font-size: 30px;
  color: var(--sf-brand);
}

.spin {
  animation: dc-spin 1s linear infinite;
}

@keyframes dc-spin {
  to {
    transform: rotate(360deg);
  }
}

.dc-run-title {
  margin: 12px 0 4px;
  font-size: 15px;
  font-weight: 600;
}

.dc-run-percent {
  margin: 0;
  font-size: 26px;
  font-weight: 700;
  color: var(--sf-brand);
}

.dc-progress {
  width: 100%;
  margin: 12px 0 16px;
}

.dc-steps {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 8px;
}

.dc-step {
  padding: 3px 10px;
  font-size: 12px;
  color: var(--sf-text-muted);
  background: var(--sf-bg-tint);
  border-radius: 12px;
}

.dc-step.done {
  color: var(--sf-text-success);
  background: var(--sf-bg-success);
}

.dc-step.active {
  color: #fff;
  background: var(--sf-brand);
}

.dc-run-meta {
  margin: 16px 0 0;
  font-size: 12px;
  color: var(--sf-text-muted);
}

.dc-run-tip {
  margin: 10px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--sf-text-muted);
}

.dc-preview {
  padding: 0 2px;
}

.dc-headline {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding-bottom: 10px;
  border-bottom: 1px solid var(--sf-border);
}

.dc-book-title {
  margin: 0 0 6px;
  font-size: 20px;
  font-weight: 700;
}

.dc-book-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}

.dc-level-badge {
  font-size: 12px;
  color: var(--sf-brand);
  background: var(--sf-bg-tint);
  border-radius: 10px;
  padding: 1px 8px;
}

.dc-sampled {
  font-size: 11px;
  color: var(--sf-text-warning);
}

.dc-headline-side {
  display: flex;
  flex-direction: column;
  gap: 2px;
  font-size: 12px;
  color: var(--sf-text-muted);
  text-align: right;
  white-space: nowrap;
}

.dc-tabs {
  margin-top: 6px;
}

.dc-section {
  max-height: 58vh;
  overflow-y: auto;
  padding: 2px 4px 8px;
}

.dc-logline {
  padding: 12px 14px;
  margin-bottom: 14px;
  font-size: 15px;
  line-height: 1.6;
  font-weight: 600;
  color: var(--sf-brand-strong);
  background: var(--sf-bg-subtle);
  border-left: 3px solid var(--sf-brand);
  border-radius: 6px;
}

.dc-kv {
  display: flex;
  gap: 12px;
  padding: 8px 0;
  border-bottom: 1px dashed var(--sf-border);
}

.dc-k {
  flex: 0 0 68px;
  font-size: 12px;
  color: var(--sf-text-muted);
}

.dc-v {
  flex: 1;
  min-width: 0;
  font-size: 13px;
  line-height: 1.7;
  color: var(--sf-text-regular);
}

.dc-pre {
  white-space: pre-wrap;
}

.dc-acts {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.dc-act {
  padding: 8px 10px;
  background: var(--sf-bg-subtle);
  border-radius: 6px;
}

.dc-act-head {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
}

.dc-act-range {
  font-size: 11px;
  color: var(--sf-text-muted);
}

.dc-act p {
  margin: 4px 0 0;
  font-size: 12px;
  line-height: 1.7;
}

.dc-list {
  margin: 0;
  padding-left: 18px;
}

.dc-list li {
  margin-bottom: 4px;
}

.dc-fn {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 8px;
  padding: 4px 0;
}

.dc-fn-name {
  font-size: 12px;
  font-weight: 600;
}

.dc-fn-role {
  font-size: 12px;
  color: var(--sf-text-muted);
}

.dc-tagline {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.dc-proto {
  padding: 12px 14px;
  margin-bottom: 14px;
  background: var(--sf-bg-subtle);
  border: 1px solid var(--sf-border);
  border-radius: 8px;
}

.dc-proto-head {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 8px;
}

.dc-proto-name {
  font-size: 16px;
  font-weight: 700;
}

.dc-proto-identity {
  font-size: 12px;
  color: var(--sf-text-muted);
}

.dc-proto-meta {
  font-size: 12px;
  color: var(--sf-text-muted);
}

.dc-proto-line {
  margin: 8px 0 0;
  font-size: 12px;
  line-height: 1.7;
  color: var(--sf-text-regular);
}

.dc-proto-line b {
  margin-right: 6px;
  font-size: 11px;
  font-weight: 600;
  color: var(--sf-text-muted);
}

.dc-table {
  width: 100%;
  margin-bottom: 14px;
}

.dc-sub-title {
  margin: 6px 0 8px;
  font-size: 13px;
  font-weight: 600;
}

.dc-factions {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 10px;
}

.dc-faction {
  padding: 10px 12px;
  background: var(--sf-bg-subtle);
  border: 1px solid var(--sf-border);
  border-radius: 8px;
}

.dc-faction-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  font-size: 13px;
}

.dc-faction-line {
  margin: 5px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--sf-text-regular);
}

.dc-faction-line b {
  margin-right: 5px;
  font-size: 11px;
  color: var(--sf-text-muted);
}

.dc-members {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
  margin-top: 7px;
}

.dc-member {
  padding: 1px 7px;
  font-size: 11px;
  color: var(--sf-text-regular);
  background: var(--sf-bg-tint);
  border-radius: 10px;
}

.dc-member i {
  font-style: normal;
  color: var(--sf-text-muted);
}

.dc-outline {
  padding-right: 10px;
}

.dc-volume {
  margin-bottom: 14px;
}

.dc-volume-head {
  padding: 5px 0;
  margin-bottom: 6px;
  font-size: 13px;
  font-weight: 600;
  color: var(--sf-brand);
  border-bottom: 1px solid var(--sf-border);
}

.dc-chapter {
  padding: 8px 10px;
  margin-bottom: 6px;
  background: var(--sf-bg-subtle);
  border-radius: 6px;
}

.dc-chapter-head {
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.dc-chapter-no {
  font-size: 11px;
  color: var(--sf-text-muted);
}

.dc-chapter-title {
  font-size: 13px;
  font-weight: 600;
}

.dc-chapter-summary {
  margin: 4px 0 6px;
  font-size: 12px;
  line-height: 1.7;
  color: var(--sf-text-regular);
}

.dc-chapter-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
}

.dc-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.dc-model {
  font-size: 12px;
  color: var(--sf-text-muted);
}

.dc-footer-right {
  display: flex;
  gap: 8px;
}

.w-full {
  width: 100%;
}

@media (max-width: 1080px) {
  .dc-levels {
    grid-template-columns: repeat(2, 1fr);
  }

  .dc-row {
    grid-template-columns: 1fr;
  }

  .dc-col-narrow {
    padding-left: 0;
    border-left: none;
  }

  .dc-factions {
    grid-template-columns: 1fr;
  }
}
</style>
