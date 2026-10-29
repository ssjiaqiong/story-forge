<template>
  <el-drawer
    :model-value="modelValue"
    :title="drawerTitle"
    size="640px"
    @update:model-value="onVisibleChange"
  >
    <div v-if="master" v-loading="loading" class="mod-body">
      <div class="mod-tags">
        <el-tag size="small" :type="statusType(master.status)" effect="plain">
          {{ statusLabel(master.status) }}
        </el-tag>
        <span class="mod-meta">预计 {{ master.plannedOutlines || '未规划' }} 条大纲</span>
        <span class="mod-meta">已排 {{ outlines.length }} 条</span>
        <span v-if="master.updatedAt" class="mod-meta">更新于 {{ formatTime(master.updatedAt) }}</span>
      </div>

      <section class="mod-section">
        <h4 class="mod-section-title">总纲内容</h4>
        <div class="mod-content">{{ master.content || '（这条总纲还没有内容）' }}</div>
      </section>

      <section class="mod-section">
        <h4 class="mod-section-title">
          章节大纲
          <span class="mod-section-count">{{ outlines.length }} 条</span>
        </h4>
        <div v-for="chapter in outlines" :key="chapter.id" class="mod-outline">
          <div class="mod-outline-head">
            <span class="mod-outline-no">第 {{ chapter.chapterNo }} 章</span>
            <strong class="mod-outline-title">{{ chapter.title }}</strong>
            <el-tag size="small" :type="chapterTagType(chapter)" effect="plain">
              {{ chapterTagLabel(chapter) }}
            </el-tag>
          </div>
          <p v-if="chapter.summary" class="mod-outline-summary">{{ chapter.summary }}</p>
          <div v-if="chapter.participants?.length || chapter.events?.length" class="mod-outline-tags">
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
        </div>
        <el-empty
          v-if="!loading && !outlines.length"
          description="这条总纲还没有章节大纲"
          :image-size="64"
        />
      </section>
    </div>
    <template #footer>
      <el-button @click="onVisibleChange(false)">关闭</el-button>
    </template>
  </el-drawer>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { listChapters, type ChapterVO, type MasterOutlineVO } from '@/api/novel-asset'

/**
 * 总纲只读详情抽屉：总纲全文 + 它下面已经排出来的章节大纲。
 *
 * 传了 `chapters` 就直接用（页面上已有全量章节时省一次请求），
 * 没传则打开时自己拉一遍。
 */
const props = withDefaults(
  defineProps<{
    modelValue: boolean
    novelId?: string
    master: MasterOutlineVO | null
    chapters?: ChapterVO[]
  }>(),
  { novelId: '', chapters: undefined }
)

const emit = defineEmits<{ 'update:modelValue': [boolean] }>()

const MASTER_STATUS: Record<string, string> = {
  PENDING: '未开始',
  RUNNING: '进行中',
  DONE: '已完成'
}

const loading = ref(false)
const fetched = ref<ChapterVO[]>([])

const drawerTitle = computed(() =>
  props.master ? `总纲 ${props.master.sortNo} · ${props.master.title}` : '总纲详情'
)

const sourceChapters = computed(() => props.chapters ?? fetched.value)

const outlines = computed(() => {
  const current = props.master
  if (!current) {
    return []
  }
  return sourceChapters.value
    .filter((item) => item.masterOutlineId === current.id)
    .slice()
    .sort((a, b) => a.chapterNo - b.chapterNo)
})

function statusLabel(status?: string) {
  if (!status) {
    return MASTER_STATUS.PENDING
  }
  return MASTER_STATUS[status] ?? status
}

function statusType(status?: string) {
  if (status === 'DONE') {
    return 'success'
  }
  if (status === 'RUNNING') {
    return 'warning'
  }
  return 'info'
}

function chapterTagLabel(chapter: ChapterVO) {
  if (chapter.wordCount > 0) {
    return `已写 ${chapter.wordCount} 字`
  }
  return '待写'
}

function chapterTagType(chapter: ChapterVO) {
  return chapter.wordCount > 0 ? 'success' : 'info'
}

function formatTime(value: string) {
  return value.replace('T', ' ').slice(0, 16)
}

async function ensureChapters() {
  if (props.chapters || !props.novelId) {
    return
  }
  loading.value = true
  try {
    fetched.value = await listChapters(props.novelId)
  } catch {
    fetched.value = []
  } finally {
    loading.value = false
  }
}

function onVisibleChange(visible: boolean) {
  emit('update:modelValue', visible)
}

watch(
  () => props.modelValue,
  (visible) => {
    if (visible) {
      void ensureChapters()
    }
  },
  { immediate: true }
)

watch(
  () => props.novelId,
  () => {
    fetched.value = []
    if (props.modelValue) {
      void ensureChapters()
    }
  }
)
</script>

<style scoped>
.mod-body {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.mod-tags {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
}

.mod-meta {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.mod-section {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.mod-section-title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0;
  font-size: 14px;
  font-weight: 600;
}

.mod-section-count {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  font-weight: 400;
}

.mod-content {
  padding: 12px 14px;
  border-radius: 8px;
  background: var(--el-fill-color-light);
  color: var(--el-text-color-regular);
  font-size: 13px;
  line-height: 1.85;
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 42vh;
  overflow-y: auto;
}

.mod-outline {
  padding: 10px 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.mod-outline-head {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}

.mod-outline-no {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.mod-outline-title {
  font-size: 13px;
  color: var(--el-text-color-primary);
}

.mod-outline-summary {
  margin: 0;
  color: var(--el-text-color-regular);
  font-size: 12px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}

.mod-outline-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
</style>
