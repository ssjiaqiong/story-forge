<template>
  <el-drawer
    :model-value="modelValue"
    :title="drawerTitle"
    size="640px"
    @update:model-value="onVisibleChange"
  >
    <div v-if="chapter" v-loading="loading" class="cod-body">
      <div class="cod-tags">
        <el-tag size="small" :type="statusType(chapter.status)" effect="plain">
          {{ statusLabel(chapter.status) }}
        </el-tag>
        <el-tag v-if="chapter.volumeTitle || chapter.volumeNo" size="small" effect="plain">
          {{ chapter.volumeTitle || `第 ${chapter.volumeNo} 卷` }}
        </el-tag>
        <el-tag v-if="masterTitle" size="small" type="success" effect="plain">
          总纲：{{ masterTitle }}
        </el-tag>
        <span class="cod-meta">{{ chapter.wordCount > 0 ? `正文 ${chapter.wordCount} 字` : '正文未写' }}</span>
        <span v-if="chapter.updatedAt" class="cod-meta">更新于 {{ formatTime(chapter.updatedAt) }}</span>
      </div>

      <section class="cod-section">
        <h4 class="cod-section-title">章节摘要</h4>
        <div class="cod-block">{{ chapter.summary || '（这一章还没有摘要）' }}</div>
      </section>

      <!-- 本章实际：正文到底写了什么。下一章生成时读的就是这一段，不是上面的摘要 -->
      <section class="cod-section">
        <h4 class="cod-section-title">
          本章实际
          <span class="cod-section-count">{{ factSourceLabel }}</span>
        </h4>
        <div v-if="editingFact" class="cod-fact-edit">
          <el-input
            v-model="factDraft"
            type="textarea"
            :rows="7"
            maxlength="1200"
            show-word-limit
            placeholder="只写已发生的事实：谁做了什么、结果如何、留下什么没解决的问题。不要写「本章将……」这类计划语气。"
          />
          <div class="cod-fact-actions">
            <span class="cod-meta">保存后来源变为「作者手改」，重新分析不会再覆盖</span>
            <div>
              <el-button size="small" @click="editingFact = false">取消</el-button>
              <el-button size="small" type="primary" :loading="savingFact" @click="saveFact">保存</el-button>
            </div>
          </div>
        </div>
        <template v-else>
          <div v-if="facts?.factSummary" class="cod-block">{{ facts.factSummary }}</div>
          <el-empty
            v-else-if="!factsLoading"
            description="还没有事实摘要，到「分析」页跑一次分析即可自动生成"
            :image-size="48"
          />
          <div>
            <el-button v-if="!locked" text size="small" :icon="Edit" @click="startFactEdit">
              {{ facts?.factSummary ? '编辑事实摘要' : '手工补一段' }}
            </el-button>
          </div>
        </template>

        <div v-if="facts?.planted?.length" class="cod-fs-group">
          <span class="cod-fs-label">本章新埋</span>
          <el-tag v-for="item in facts.planted" :key="'fp-' + item.id" size="small" type="warning" effect="plain">
            {{ item.title }}
          </el-tag>
        </div>
        <div v-if="facts?.paid?.length" class="cod-fs-group">
          <span class="cod-fs-label">本章回收</span>
          <el-tag v-for="item in facts.paid" :key="'fd-' + item.id" size="small" type="success" effect="plain">
            {{ item.title }}
          </el-tag>
        </div>

        <div v-if="facts?.states?.length" class="cod-states">
          <span class="cod-fs-label">角色状态变化</span>
          <div v-for="state in facts.states" :key="'st-' + state.characterId" class="cod-state">
            <span class="cod-state-name">{{ state.characterName }}</span>
            <span v-if="state.location" class="cod-state-item">位置：{{ state.location }}</span>
            <span v-if="state.condition" class="cod-state-item">状态：{{ state.condition }}</span>
            <span v-if="state.mood" class="cod-state-item">情绪：{{ state.mood }}</span>
            <span v-for="(know, index) in state.knowsAdded" :key="'ka-' + index" class="cod-state-item know">
              已知：{{ know }}
            </span>
            <span v-for="(hidden, index) in state.knowsHidden" :key="'kh-' + index" class="cod-state-item hidden">
              蒙在鼓里：{{ hidden }}
            </span>
          </div>
        </div>
      </section>

      <section v-if="chapter.participants?.length || chapter.events?.length" class="cod-section">
        <h4 class="cod-section-title">出场与事件</h4>
        <div class="cod-tag-list">
          <el-tag
            v-for="person in chapter.participants"
            :key="'p-' + person.id"
            size="small"
            effect="plain"
          >{{ person.name }}</el-tag>
          <el-tag
            v-for="event in chapter.events"
            :key="'e-' + event.id"
            size="small"
            type="warning"
            effect="plain"
          >{{ event.title }}</el-tag>
        </div>
      </section>

      <section v-if="chapter.content" class="cod-section">
        <h4 class="cod-section-title">
          正文
          <span class="cod-section-count">{{ chapter.wordCount || 0 }} 字</span>
        </h4>
        <div class="cod-content">{{ chapter.content }}</div>
      </section>
      <el-empty
        v-else-if="!loading"
        description="这一章还没写正文，可到「正文」菜单生成"
        :image-size="64"
      />
    </div>
    <template #footer>
      <el-button @click="onVisibleChange(false)">关闭</el-button>
    </template>
  </el-drawer>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Edit } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { getChapter, type ChapterVO } from '@/api/novel-asset'
import { getChapterFacts, updateFactSummary, type ChapterFactsVO } from '@/api/fact'

/**
 * 章节大纲只读详情抽屉。
 *
 * 列表接口不回正文，所以这里按 id 再拉一次详情；已经手上有完整数据时
 * 可以直接传 `chapter`，传入时不再发请求。
 */
const props = withDefaults(
  defineProps<{
    modelValue: boolean
    chapterId?: string
    chapter?: ChapterVO | null
    masterTitle?: string
    /** 自动写书运行中时写操作被锁 */
    locked?: boolean
  }>(),
  { chapterId: '', chapter: null, masterTitle: '', locked: false }
)

const emit = defineEmits<{ 'update:modelValue': [boolean] }>()

const CHAPTER_STATUS: Record<string, string> = {
  DRAFT: '草稿',
  WRITING: '写作中',
  DONE: '已完成'
}

const loading = ref(false)
const fetched = ref<ChapterVO | null>(null)

/* ---- 事实层：这一章实际发生了什么 ---- */
const facts = ref<ChapterFactsVO | null>(null)
const factsLoading = ref(false)
const editingFact = ref(false)
const savingFact = ref(false)
const factDraft = ref('')

const factSourceLabel = computed(() => {
  const source = facts.value?.factSource
  if (source === 'MANUAL') return '作者手改'
  if (source === 'BACKFILL') return '回填生成'
  if (source === 'AI') return '分析产出'
  return '尚未生成'
})

function currentChapterId(): string {
  return String(props.chapterId || props.chapter?.id || '')
}

async function ensureFacts() {
  const id = currentChapterId()
  if (!id) {
    return
  }
  factsLoading.value = true
  try {
    facts.value = await getChapterFacts(id)
  } catch {
    facts.value = null
  } finally {
    factsLoading.value = false
  }
}

function startFactEdit() {
  factDraft.value = facts.value?.factSummary || ''
  editingFact.value = true
}

async function saveFact() {
  const id = currentChapterId()
  if (!id) {
    return
  }
  savingFact.value = true
  try {
    facts.value = await updateFactSummary(id, factDraft.value.trim())
    editingFact.value = false
    ElMessage.success('事实摘要已保存，后续章节生成时会读到这一段')
  } catch (error) {
    ElMessage.error((error as Error).message || '保存失败')
  } finally {
    savingFact.value = false
  }
}

const chapter = computed(() => props.chapter ?? fetched.value)

const drawerTitle = computed(() =>
  chapter.value ? `第 ${chapter.value.chapterNo} 章 · ${chapter.value.title}` : '章节大纲详情'
)

function statusLabel(status?: string) {
  if (!status) {
    return CHAPTER_STATUS.DRAFT
  }
  return CHAPTER_STATUS[status] ?? status
}

function statusType(status?: string) {
  if (status === 'DONE') {
    return 'success'
  }
  if (status === 'WRITING') {
    return 'warning'
  }
  return 'info'
}

function formatTime(value: string) {
  return value.replace('T', ' ').slice(0, 16)
}

function onVisibleChange(visible: boolean) {
  emit('update:modelValue', visible)
}

async function ensureChapter() {
  if (props.chapter || !props.chapterId) {
    return
  }
  loading.value = true
  try {
    fetched.value = await getChapter(props.chapterId)
  } catch {
    fetched.value = null
  } finally {
    loading.value = false
  }
}

watch(
  () => [props.modelValue, props.chapterId] as const,
  ([visible]) => {
    if (visible) {
      void ensureChapter()
      void ensureFacts()
    }
  },
  { immediate: true }
)

watch(
  () => props.chapterId,
  () => {
    fetched.value = null
  }
)
</script>

<style scoped>
.cod-body {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.cod-tags {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
}

.cod-meta {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.cod-section {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.cod-section-title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0;
  font-size: 14px;
  font-weight: 600;
}

.cod-section-count {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  font-weight: 400;
}

.cod-block {
  padding: 12px 14px;
  border-radius: 8px;
  background: var(--el-fill-color-light);
  color: var(--el-text-color-regular);
  font-size: 13px;
  line-height: 1.85;
  white-space: pre-wrap;
  word-break: break-word;
}

.cod-tag-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.cod-fact-edit {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.cod-fact-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.cod-fs-group {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
}

.cod-fs-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-right: 2px;
}

.cod-states {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.cod-state {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 10px;
  padding: 8px 12px;
  border-radius: 6px;
  background: var(--el-fill-color-lighter);
  font-size: 12px;
  line-height: 1.7;
}

.cod-state-name {
  font-weight: 600;
  font-size: 13px;
  color: var(--el-text-color-primary);
}

.cod-state-item {
  color: var(--el-text-color-regular);
}

.cod-state-item.know {
  color: var(--el-color-primary);
}

.cod-state-item.hidden {
  color: var(--el-color-warning);
}

.cod-content {
  padding: 12px 14px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  color: var(--el-text-color-regular);
  font-size: 13px;
  line-height: 1.9;
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 46vh;
  overflow-y: auto;
}
</style>
