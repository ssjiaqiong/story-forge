<template>
  <el-dialog
    v-model="visible"
    width="900px"
    top="6vh"
    destroy-on-close
    :close-on-click-modal="false"
    :close-on-press-escape="!generating"
  >
    <template #header>
      <div class="dialog-head">
        <span class="dialog-title">《{{ novel?.title }}》的封面</span>
        <span class="dialog-hint">
          已有 {{ covers.length }} / {{ MAX_COVERS }} 张{{
            covers.length > 1 ? ' · 书架卡片上左右切换' : ''
          }}
        </span>
      </div>
    </template>

    <div v-loading="loading" class="cover-body">
      <!-- 已有封面 -->
      <section class="existing">
        <div class="section-label">已有封面</div>
        <div v-if="!covers.length" class="empty-block">
          <div class="empty-mark">封</div>
          <p>还没有封面。右侧写一句画面描述，让 AI 画一张。</p>
        </div>
        <div v-else class="cover-grid">
          <div v-for="cover in covers" :key="cover.id" class="cover-cell">
            <img
              v-if="urls[cover.id]"
              :src="urls[cover.id]"
              class="cell-img"
              alt=""
              @click.stop="openPreview(cover)"
            />
            <div v-else class="cell-img placeholder">{{ loadFailed[cover.id] ? '加载失败' : '载入中' }}</div>
            <span v-if="cover.primary" class="primary-badge">主封面</span>
            <span v-else-if="cover.style" class="style-badge">{{ STYLE_LABELS[cover.style] || cover.style }}</span>
            <div class="cell-actions">
              <el-button link size="small" :disabled="cover.primary" @click="onSetPrimary(cover)">设为主封面</el-button>
              <el-button link type="danger" size="small" @click="onDelete(cover)">删除</el-button>
            </div>
            <div class="cell-meta">{{ metaLabel(cover) }}</div>
          </div>
        </div>
      </section>

      <!-- 生成 -->
      <section class="generate">
        <div class="section-label">生成新封面</div>

        <div class="field-label">画面描述（可留空）</div>
        <el-input
          v-model="prompt"
          type="textarea"
          :rows="4"
          maxlength="1000"
          show-word-limit
          placeholder="留空则由系统按书名、题材、简介自动拼装；也可以写：雪山上的一袭红衣，背影，冷色调"
        />

        <div class="field-label">封面风格</div>
        <el-select v-model="style" style="width: 100%">
          <el-option v-for="opt in STYLE_OPTIONS" :key="opt.value" :label="opt.label" :value="opt.value" />
        </el-select>

        <div class="field-label">封面文字</div>
        <div class="text-toggles">
          <el-switch v-model="withTitle" active-text="书名" />
          <el-switch v-model="withAuthor" active-text="作者名" />
        </div>
        <template v-if="withTitle">
          <el-input v-model="titleText" maxlength="120" placeholder="留空用小说现有书名" class="mt8" />
        </template>
        <template v-if="withAuthor">
          <el-input v-model="authorText" maxlength="80" placeholder="留空用小说笔名，仍无则由 AI 起一个" class="mt8" />
        </template>
        <template v-if="withTitle || withAuthor">
          <div class="field-label">文字方式</div>
          <el-radio-group v-model="textRenderMode">
            <el-radio value="OVERLAY">后期叠加文字（推荐）</el-radio>
            <el-radio value="AI_NATIVE">AI 直接画出文字</el-radio>
          </el-radio-group>
          <div class="field-tip" v-if="textRenderMode === 'OVERLAY'">
            画面内不出现任何文字（也不会把简介画进封面），书名/作者用本地字体清晰渲染在下方，无错别字。
          </div>
          <div class="field-tip" v-else>
            由模型把书名/作者画成封面标题字，整体感更强，但中文笔画偶尔会出错；简介等其余文字仍会被禁止。
          </div>
        </template>
        <div v-else class="field-tip">
          不勾选文字时，封面画面内不会出现任何文字（含简介、标签、水印）。
        </div>

        <el-collapse class="advanced">
          <el-collapse-item title="高级选项" name="advanced">
            <div class="field-label">负面提示词</div>
            <el-input
              v-model="negativePrompt"
              type="textarea"
              :rows="2"
              maxlength="500"
              show-word-limit
              placeholder="不想出现的元素，如：文字、水印、多人"
            />
            <div class="field-label">出图尺寸</div>
            <el-input v-model="size" placeholder="留空用接口默认尺寸，如 1024x1536" class="mono-input" />
            <div class="field-tip">各平台支持的比例不同，尺寸被平台拒绝时会直接报错，换一个再试。</div>
          </el-collapse-item>
        </el-collapse>

        <el-alert
          v-if="limitReached"
          class="mt12"
          type="warning"
          :closable="false"
          show-icon
          title="已达上限：一部小说最多保留 5 张封面，请先删除不用的再生成"
        />

        <div class="gen-actions">
          <el-button type="primary" :loading="generating" :disabled="limitReached" @click="onGenerate">
            {{ generating ? '正在生成…' : '生成封面' }}
          </el-button>
          <span v-if="generating" class="gen-hint">出图通常 10~60 秒，异步平台可能更久，请勿关闭窗口</span>
        </div>

        <p class="tip">
          提示词会自动加上封面硬性要求；生成结果会立即下载并保存到服务器，不依赖平台的有效期。
        </p>
      </section>
    </div>
  </el-dialog>

  <!-- 图片放大预览：点击空白处关闭 -->
  <teleport to="body">
    <transition name="preview-fade">
      <div v-if="previewUrl" class="cover-preview" @click="closePreview">
        <img :src="previewUrl" class="preview-img" alt="" @click.stop />
      </div>
    </transition>
  </teleport>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { NovelVO } from '@/api/novel'
import {
  deleteCover,
  forgetCoverUrl,
  generateCover,
  listCovers,
  loadCoverUrl,
  setPrimaryCover,
  type NovelCoverVO
} from '@/api/cover'

const MAX_COVERS = 5

const props = defineProps<{
  modelValue: boolean
  novel: NovelVO | null
}>()

const emit = defineEmits<{
  (event: 'update:modelValue', value: boolean): void
  (event: 'updated'): void
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value)
})

const loading = ref(false)
const generating = ref(false)
const covers = ref<NovelCoverVO[]>([])
const urls = reactive<Record<string, string>>({})
const loadFailed = reactive<Record<string, boolean>>({})
const prompt = ref('')
const negativePrompt = ref('')
const size = ref('')
const style = ref('')
const withTitle = ref(true)
const withAuthor = ref(false)
const textRenderMode = ref<'OVERLAY' | 'AI_NATIVE'>('OVERLAY')
const titleText = ref('')
const authorText = ref('')

const STYLE_OPTIONS = [
  { value: '', label: '不指定（模型自由发挥）' },
  { value: 'ANIME', label: '动漫' },
  { value: 'REAL', label: '真人写实' },
  { value: 'CG', label: 'CG 3D' },
  { value: 'INK', label: '水墨国风' },
  { value: 'OIL', label: '油画' },
  { value: 'FLAT', label: '扁平插画' }
]

const STYLE_LABELS: Record<string, string> = Object.fromEntries(
  STYLE_OPTIONS.filter((item) => item.value).map((item) => [item.value, item.label])
)

const limitReached = computed(() => covers.value.length >= MAX_COVERS)

/* ---------- 图片放大预览 ---------- */
const previewUrl = ref('')

function openPreview(cover: NovelCoverVO) {
  if (urls[cover.id]) {
    previewUrl.value = urls[cover.id]
  }
}

function closePreview() {
  previewUrl.value = ''
}

watch(previewUrl, (url) => {
  if (url) {
    document.body.style.overflow = 'hidden'
  } else {
    document.body.style.overflow = ''
  }
})

watch(
  () => props.modelValue,
  (open) => {
    if (open) {
      prompt.value = ''
      negativePrompt.value = ''
      size.value = ''
      style.value = ''
      withTitle.value = true
      withAuthor.value = false
      textRenderMode.value = 'OVERLAY'
      titleText.value = ''
      authorText.value = ''
      void reload()
    }
  }
)

async function reload() {
  if (!props.novel) {
    return
  }
  loading.value = true
  try {
    covers.value = await listCovers(props.novel.id)
    await loadImages()
  } catch (error) {
    ElMessage.error((error as Error).message || '加载封面失败')
  } finally {
    loading.value = false
  }
}

async function loadImages() {
  await Promise.all(
    covers.value.map(async (cover) => {
      if (urls[cover.id]) {
        return
      }
      try {
        urls[cover.id] = await loadCoverUrl(cover.id)
      } catch {
        loadFailed[cover.id] = true
      }
    })
  )
}

async function onGenerate() {
  if (!props.novel || generating.value) {
    return
  }
  generating.value = true
  try {
    const created = await generateCover(props.novel.id, {
      prompt: prompt.value.trim() || undefined,
      negativePrompt: negativePrompt.value.trim() || undefined,
      size: size.value.trim() || undefined,
      style: style.value || undefined,
      withTitle: withTitle.value,
      withAuthor: withAuthor.value,
      textRenderMode: textRenderMode.value,
      titleText: titleText.value.trim() || undefined,
      authorText: authorText.value.trim() || undefined
    })
    ElMessage.success('封面已生成')
    urls[created.id] = await loadCoverUrl(created.id).catch(() => '')
    covers.value = await listCovers(props.novel.id)
    await loadImages()
    emit('updated')
  } catch (error) {
    ElMessage.error((error as Error).message || '生成失败')
  } finally {
    generating.value = false
  }
}

async function onSetPrimary(cover: NovelCoverVO) {
  try {
    await setPrimaryCover(cover.id)
    ElMessage.success('已设为书架展示的封面')
    covers.value = await listCovers(cover.novelId)
    emit('updated')
  } catch (error) {
    ElMessage.error((error as Error).message || '设置失败')
  }
}

async function onDelete(cover: NovelCoverVO) {
  try {
    await ElMessageBox.confirm('删除后这张封面会从服务器移除，不可恢复。确定删除吗？', '删除封面', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  try {
    await deleteCover(cover.id)
    forgetCoverUrl(cover.id)
    delete urls[cover.id]
    ElMessage.success('已删除')
    covers.value = await listCovers(cover.novelId)
    await loadImages()
    emit('updated')
  } catch (error) {
    ElMessage.error((error as Error).message || '删除失败')
  }
}

function metaLabel(cover: NovelCoverVO) {
  const parts: string[] = []
  if (cover.width && cover.height) {
    parts.push(`${cover.width}×${cover.height}`)
  }
  if (cover.hasText) {
    parts.push(cover.textMode === 'AI_NATIVE' ? 'AI直出文字' : '含文字')
  }
  if (cover.fileSize) {
    parts.push(`${Math.round(cover.fileSize / 1024)}KB`)
  }
  if (cover.createdAt) {
    parts.push(formatDate(cover.createdAt))
  }
  return parts.join(' · ')
}

function formatDate(value: string) {
  return value.replace('T', ' ').slice(5, 16)
}
</script>

<style scoped>
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

.cover-body {
  display: grid;
  grid-template-columns: minmax(0, 1.25fr) minmax(300px, 1fr);
  gap: 20px;
  min-height: 320px;
}

.section-label {
  margin-bottom: 10px;
  font-size: 13px;
  font-weight: 600;
  color: var(--sf-text-primary);
}

.existing {
  border-right: 1px solid var(--sf-border);
  padding-right: 20px;
}

.empty-block {
  padding: 60px 20px;
  text-align: center;
  color: var(--sf-text-muted);
  font-size: 13px;
  line-height: 1.7;
}

.empty-mark {
  width: 46px;
  height: 46px;
  margin: 0 auto 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 12px;
  background: var(--sf-bg-tint-strong, #eef2ff);
  color: var(--sf-brand);
  font-weight: 700;
}

.cover-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, 124px);
  gap: 14px;
}

.cover-cell {
  position: relative;
  width: 124px;
}

.cell-img {
  width: 124px;
  height: 170px;
  border-radius: 8px;
  object-fit: cover;
  display: block;
  background: var(--sf-bg-page);
  box-shadow: 0 2px 10px rgba(31, 41, 55, 0.12);
  cursor: zoom-in;
}

.cell-img.placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  color: var(--sf-text-muted);
}

.primary-badge {
  position: absolute;
  top: 6px;
  left: 6px;
  padding: 1px 6px;
  font-size: 10px;
  border-radius: 8px;
  color: #fff;
  background: var(--sf-brand);
}

.style-badge {
  position: absolute;
  top: 6px;
  left: 6px;
  padding: 1px 6px;
  font-size: 10px;
  border-radius: 8px;
  color: #fff;
  background: rgba(15, 23, 42, 0.55);
}

.text-toggles {
  display: flex;
  gap: 20px;
  align-items: center;
}

.mt8 {
  margin-top: 8px;
}

.cell-actions {
  display: flex;
  justify-content: space-between;
  margin-top: 4px;
}

.cell-meta {
  font-size: 11px;
  color: var(--sf-text-muted);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.field-label {
  margin: 12px 0 6px;
  font-size: 12px;
  font-weight: 500;
  color: var(--sf-text-regular);
}

.field-tip {
  margin-top: 4px;
  font-size: 12px;
  color: var(--sf-text-muted);
  line-height: 1.6;
}

.advanced {
  margin-top: 10px;
}

.gen-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 16px;
}

.gen-hint {
  font-size: 12px;
  color: var(--sf-text-muted);
}

.tip {
  margin: 14px 0 0;
  font-size: 12px;
  line-height: 1.7;
  color: var(--sf-text-muted);
}

.mono-input :deep(input) {
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
}

.mt12 {
  margin-top: 12px;
}

/* ---------- 图片放大预览 ---------- */
.cover-preview {
  position: fixed;
  inset: 0;
  z-index: 3000;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(0, 0, 0, 0.72);
  cursor: zoom-out;
}

.preview-img {
  max-width: 86vw;
  max-height: 88vh;
  border-radius: 8px;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.5);
  cursor: default;
}

.preview-fade-enter-active,
.preview-fade-leave-active {
  transition: opacity 0.2s ease;
}

.preview-fade-enter-from,
.preview-fade-leave-to {
  opacity: 0;
}
</style>
