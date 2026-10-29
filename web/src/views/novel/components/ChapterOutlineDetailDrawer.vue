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
import { getChapter, type ChapterVO } from '@/api/novel-asset'

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
  }>(),
  { chapterId: '', chapter: null, masterTitle: '' }
)

const emit = defineEmits<{ 'update:modelValue': [boolean] }>()

const CHAPTER_STATUS: Record<string, string> = {
  DRAFT: '草稿',
  WRITING: '写作中',
  DONE: '已完成'
}

const loading = ref(false)
const fetched = ref<ChapterVO | null>(null)

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
