<template>
  <el-drawer v-model="visible" :title="title" size="560px" destroy-on-close>
    <div v-loading="loading" class="version-body">
      <p class="version-hint">
        每次正文发生变化前，系统都会把当时的内容存一版。回滚时当前稿会先存为新版本，所以随时可以再滚回来。
      </p>

      <div v-if="!items.length" class="empty-block small">
        <p>还没有历史版本。</p>
        <p class="version-hint">正文第一次被 AI 覆盖或再次保存后，就会出现第一版。</p>
      </div>

      <div v-else class="version-list">
        <div v-for="item in items" :key="item.id" class="version-item">
          <div class="version-main">
            <div class="version-head">
              <span class="version-no">第 {{ item.versionNo }} 版</span>
              <el-tag size="small" effect="plain" :type="tagType(item.source)">{{ item.sourceLabel }}</el-tag>
              <span class="version-words">{{ item.wordCount || 0 }} 字</span>
            </div>
            <p class="version-note">{{ item.note || '—' }}</p>
            <p class="version-time">{{ formatTime(item.createdAt) }}</p>
          </div>
          <div class="version-actions">
            <el-button text size="small" @click="viewVersion(item)">查看</el-button>
            <el-button text size="small" :loading="comparingId === item.id" @click="compareVersion(item)">对比当前</el-button>
            <el-button text size="small" type="primary" :disabled="restoring" @click="restore(item)">回滚</el-button>
            <el-button text size="small" type="danger" @click="remove(item)">删除</el-button>
          </div>
        </div>
      </div>

      <div v-if="total > items.length" class="version-more">
        <el-button text :loading="loading" @click="loadMore">加载更早的版本（共 {{ total }} 版）</el-button>
      </div>
    </div>

    <!-- 查看全文 -->
    <el-dialog v-model="viewVisible" :title="`第 ${viewItem?.versionNo ?? ''} 版全文`" width="720px" append-to-body>
      <div class="view-meta">
        <span>{{ viewItem?.sourceLabel }}</span>
        <span>{{ viewItem?.wordCount || 0 }} 字</span>
        <span>{{ formatTime(viewItem?.createdAt) }}</span>
      </div>
      <pre class="view-content">{{ viewContent || '（这一版没有正文）' }}</pre>
      <template #footer>
        <el-button @click="viewVisible = false">关闭</el-button>
        <el-button type="primary" :disabled="restoring" @click="restore(viewItem!)">回滚到这一版</el-button>
      </template>
    </el-dialog>

    <!-- 对比当前 -->
    <el-dialog v-model="diffVisible" title="与当前正文对比" width="860px" append-to-body>
      <div class="diff-head">
        <span>第 {{ diffItem?.versionNo }} 版（左） ↔ 当前正文（右）</span>
        <span class="diff-stat">
          新增 {{ diffStats.added }} 字 · 删除 {{ diffStats.removed }} 字 · 改动 {{ diffStats.changed }} 段
        </span>
      </div>
      <div v-if="!diffStats.changed" class="diff-empty">这一版与当前正文完全一致。</div>
      <div v-else class="diff-scroll">
        <div v-for="(block, index) in diffBlocks" :key="index" class="diff-row" :class="`is-${block.kind}`">
          <div class="diff-cell">
            <span class="diff-no">{{ block.leftNo ?? '' }}</span>
            <p class="diff-text">
              <span
                v-for="(segment, segIndex) in block.left"
                :key="segIndex"
                :class="segment.type === 'remove' ? 'seg-remove' : ''"
              >{{ segment.text }}</span>
            </p>
          </div>
          <div class="diff-cell">
            <span class="diff-no">{{ block.rightNo ?? '' }}</span>
            <p class="diff-text">
              <span
                v-for="(segment, segIndex) in block.right"
                :key="segIndex"
                :class="segment.type === 'add' ? 'seg-add' : ''"
              >{{ segment.text }}</span>
            </p>
          </div>
        </div>
      </div>
    </el-dialog>
  </el-drawer>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  countChapterVersions,
  deleteChapterVersion,
  getChapterVersion,
  listChapterVersions,
  restoreChapterVersion,
  type ChapterVO,
  type ChapterVersionVO
} from '@/api/novel-asset'
import { diffTexts, type DiffBlock, type DiffResult } from '@/utils/text-diff'

const props = defineProps<{ chapterId: string }>()
const emit = defineEmits<{ (e: 'restored', chapter: ChapterVO): void }>()

const PAGE_SIZE = 20

const visible = ref(false)
const loading = ref(false)
const items = ref<ChapterVersionVO[]>([])
const total = ref(0)
const page = ref(1)
const restoring = ref(false)
const comparingId = ref('')

const viewVisible = ref(false)
const viewItem = ref<ChapterVersionVO | null>(null)
const viewContent = ref('')

const diffVisible = ref(false)
const diffItem = ref<ChapterVersionVO | null>(null)
const currentContent = ref('')
const diffResult = ref<DiffResult | null>(null)

const title = computed(() => `正文历史（${total.value} 版）`)

const diffBlocks = computed<DiffBlock[]>(() => {
  const blocks = diffResult.value?.blocks ?? []
  // 只看有改动的块，和「逐段翻整章」相比效率高得多
  return blocks.filter((block) => block.kind !== 'equal')
})

const diffStats = computed(() => ({
  added: diffResult.value?.added ?? 0,
  removed: diffResult.value?.removed ?? 0,
  changed: diffResult.value?.changed ?? 0
}))

async function open(chapterContent = '') {
  currentContent.value = chapterContent
  visible.value = true
  page.value = 1
  items.value = []
  await load()
}

async function load() {
  loading.value = true
  try {
    const [count, result] = await Promise.all([
      countChapterVersions(props.chapterId),
      listChapterVersions(props.chapterId, page.value, PAGE_SIZE)
    ])
    total.value = count || 0
    items.value = page.value === 1 ? result.records : [...items.value, ...result.records]
  } catch (error) {
    ElMessage.error((error as Error).message || '加载历史版本失败')
  } finally {
    loading.value = false
  }
}

async function loadMore() {
  page.value += 1
  await load()
}

async function viewVersion(item: ChapterVersionVO) {
  viewItem.value = item
  viewVisible.value = true
  viewContent.value = ''
  try {
    const detail = await getChapterVersion(props.chapterId, item.id)
    viewContent.value = detail.content || ''
  } catch (error) {
    ElMessage.error((error as Error).message || '读取版本失败')
  }
}

async function compareVersion(item: ChapterVersionVO) {
  comparingId.value = item.id
  try {
    const detail = await getChapterVersion(props.chapterId, item.id)
    diffItem.value = item
    diffResult.value = diffTexts(detail.content || '', currentContent.value)
    diffVisible.value = true
  } catch (error) {
    ElMessage.error((error as Error).message || '读取版本失败')
  } finally {
    comparingId.value = ''
  }
}

async function restore(item: ChapterVersionVO) {
  try {
    await ElMessageBox.confirm(
      `把正文恢复到第 ${item.versionNo} 版（${item.wordCount || 0} 字）？当前稿会先存为一版历史，随时可以再回滚回来。`,
      '回滚正文',
      { type: 'warning', confirmButtonText: '回滚', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  restoring.value = true
  try {
    const chapter = await restoreChapterVersion(props.chapterId, item.id)
    ElMessage.success(`已回滚到第 ${item.versionNo} 版`)
    viewVisible.value = false
    diffVisible.value = false
    currentContent.value = chapter.content || ''
    emit('restored', chapter)
    page.value = 1
    await load()
  } catch (error) {
    ElMessage.error((error as Error).message || '回滚失败')
  } finally {
    restoring.value = false
  }
}

async function remove(item: ChapterVersionVO) {
  try {
    await ElMessageBox.confirm(
      `删除第 ${item.versionNo} 版历史？只删这条记录，当前正文不受影响。`,
      '删除历史版本',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await deleteChapterVersion(props.chapterId, item.id)
    ElMessage.success('已删除该历史版本')
    items.value = items.value.filter((row) => row.id !== item.id)
    total.value = Math.max(total.value - 1, 0)
  } catch (error) {
    ElMessage.error((error as Error).message || '删除失败')
  }
}

function tagType(source: string) {
  if (source === 'AI_BODY' || source === 'AI_REVISE') {
    return 'primary'
  }
  if (source === 'RESTORE') {
    return 'warning'
  }
  if (source === 'AUTO') {
    return 'info'
  }
  return 'success'
}

function formatTime(value?: string) {
  if (!value) {
    return ''
  }
  return value.replace('T', ' ').slice(0, 16)
}

defineExpose({ open })
</script>

<style scoped>
.version-body {
  min-height: 200px;
}

.version-hint {
  margin: 0 0 12px;
  font-size: 12px;
  line-height: 1.7;
  color: var(--el-text-color-secondary);
}

.version-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.version-item {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
}

.version-main {
  min-width: 0;
}

.version-head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.version-no {
  font-size: 13px;
  font-weight: 600;
}

.version-words {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.version-note {
  margin: 6px 0 0;
  font-size: 12px;
  color: var(--el-text-color-regular);
}

.version-time {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.version-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 2px;
  flex-shrink: 0;
}

.version-more {
  margin-top: 12px;
  text-align: center;
}

.view-meta {
  display: flex;
  gap: 14px;
  margin-bottom: 10px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.view-content {
  max-height: 56vh;
  margin: 0;
  padding: 12px 14px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-word;
  font-family: inherit;
  font-size: 13px;
  line-height: 1.9;
  background: var(--el-fill-color-light);
  border-radius: 8px;
}

.diff-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.diff-stat {
  font-variant-numeric: tabular-nums;
}

.diff-empty {
  padding: 30px 0;
  text-align: center;
  color: var(--el-text-color-secondary);
}

.diff-scroll {
  max-height: 56vh;
  overflow: auto;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
}

.diff-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.diff-row:last-child {
  border-bottom: none;
}

.diff-cell {
  display: flex;
  gap: 8px;
  padding: 8px 10px;
  font-size: 13px;
  line-height: 1.8;
}

.diff-cell + .diff-cell {
  border-left: 1px solid var(--el-border-color-lighter);
}

.diff-no {
  flex-shrink: 0;
  width: 20px;
  color: var(--el-text-color-placeholder);
  font-variant-numeric: tabular-nums;
}

.diff-text {
  margin: 0;
  word-break: break-word;
}

.is-remove .diff-cell:first-child,
.is-change .diff-cell:first-child {
  background: var(--el-color-danger-light-9);
}

.is-add .diff-cell:last-child,
.is-change .diff-cell:last-child {
  background: var(--el-color-success-light-9);
}

.seg-remove {
  background: var(--el-color-danger-light-7);
  text-decoration: line-through;
}

.seg-add {
  background: var(--el-color-success-light-7);
}
</style>
