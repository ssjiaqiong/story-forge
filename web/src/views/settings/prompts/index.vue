<template>
  <div class="page-container">
    <el-card shadow="never" class="prompt-card">
      <div class="prompt-layout">
        <!-- ==================== 左：场景列表 ==================== -->
        <aside class="scene-aside">
          <div class="aside-head">
            <span class="aside-title">生成场景</span>
            <el-tag size="small" effect="plain" round>{{ countLabel }}</el-tag>
          </div>
          <el-input v-model="keyword" class="aside-search" placeholder="搜索场景" clearable :prefix-icon="Search" />
          <div v-loading="loading" class="scene-scroll">
            <div v-for="group in groups" :key="group.name" class="scene-group">
              <div class="group-title">{{ group.name }}</div>
              <button
                v-for="item in group.items"
                :key="item.scene"
                type="button"
                class="scene-item"
                :class="{ active: current?.scene === item.scene, readonly: !item.editable }"
                @click="selectScene(item.scene)"
              >
                <span class="scene-name">{{ item.displayName }}</span>
                <span class="scene-flags">
                  <el-tag v-if="item.overridden" size="small" type="warning" effect="plain">已改</el-tag>
                  <el-icon v-if="!item.editable" class="lock-icon"><Lock /></el-icon>
                </span>
              </button>
            </div>
            <el-empty v-if="!loading && !groups.length" description="没有匹配的场景" :image-size="60" />
          </div>
        </aside>

        <!-- ==================== 右：编辑器 ==================== -->
        <section class="editor-pane">
          <template v-if="current">
            <header class="editor-head">
              <div class="head-main">
                <h3 class="scene-title">{{ current.displayName }}</h3>
                <div class="head-tags">
                  <el-tag size="small" effect="plain">{{ current.group }}</el-tag>
                  <el-tag v-if="current.overridden" size="small" type="warning" effect="plain">已自定义</el-tag>
                  <el-tag v-else size="small" type="info" effect="plain">系统默认</el-tag>
                  <span class="head-meta">{{ current.scene }}</span>
                </div>
              </div>
              <div class="head-actions">
                <el-button :icon="View" :loading="previewing" @click="onPreview">预览</el-button>
                <el-button :icon="RefreshLeft" :disabled="!current.overridden || !current.editable" @click="onReset">
                  恢复默认
                </el-button>
                <el-button type="primary" :icon="Check" :loading="saving" :disabled="!current.editable" @click="onSave">
                  保存
                </el-button>
              </div>
            </header>

            <el-alert
              v-if="current.note"
              class="mb12"
              type="info"
              :closable="false"
              show-icon
              :title="current.note"
            />

            <template v-if="current.editable">
              <div class="hint-line">
                <span>你只能改「指令段」——告诉 AI 怎么写。段落结构、JSON 字段等输出格式由系统统一追加，确保解析稳定。</span>
              </div>

              <div class="var-bar">
                <span class="var-label">可用变量</span>
                <button
                  v-for="v in current.variables"
                  :key="v.name"
                  type="button"
                  class="var-chip"
                  :title="`插入 ${varToken(v.name)}（示例：${v.sample}）`"
                  @click="insertVariable(v.name)"
                >
                  <span class="var-name">{{ varToken(v.name) }}</span>
                  <span class="var-label-text">{{ v.label }}</span>
                </button>
                <span v-if="!current.variables.length" class="text-muted">该场景没有可用变量</span>
              </div>

              <div class="editor-wrap">
                <el-input
                  ref="editorRef"
                  v-model="content"
                  type="textarea"
                  :rows="16"
                  :maxlength="8000"
                  resize="vertical"
                  class="prompt-textarea"
                  placeholder="在这里写这个场景的创作指令"
                />
                <div class="editor-foot">
                  <span v-if="dirty" class="dirty-tip">有未保存的修改</span>
                  <span v-else class="text-muted">字数 {{ content.length }} / 8000</span>
                  <el-button link type="primary" size="small" :disabled="content === current.defaultContent" @click="fillDefault">
                    覆盖为默认模板
                  </el-button>
                </div>
              </div>
            </template>

            <!-- 锁定区 -->
            <div v-if="current.editable" class="locked-block">
              <div class="locked-head">
                <el-icon class="lock-icon"><Lock /></el-icon>
                <span class="locked-title">输出格式（系统统一封装，不可修改）</span>
                <span class="locked-hint">保存时自动追加在这段指令之后</span>
              </div>
              <pre class="locked-body">{{ current.outputContract }}</pre>
            </div>

            <div v-if="current.editable && current.overridden && current.updatedAt" class="updated-at">
              上次保存：{{ formatTime(current.updatedAt) }}
            </div>
          </template>
          <el-empty v-else description="从左侧选择一个生成场景" />
        </section>
      </div>
    </el-card>

    <!-- 预览 -->
    <el-dialog v-model="previewVisible" :title="`预览 · ${current?.displayName ?? ''}`" width="760px" destroy-on-close>
      <el-alert
        class="mb12"
        type="info"
        :closable="false"
        show-icon
        title="以下是用示例变量渲染后的实际提示词，不会调用 AI、不会消耗额度。"
      />
      <el-tabs v-model="previewTab">
        <el-tab-pane label="完整提示词" name="full">
          <pre class="preview-pre">{{ preview?.full || '' }}</pre>
        </el-tab-pane>
        <el-tab-pane label="指令段（可编辑）" name="content">
          <pre class="preview-pre">{{ preview?.content || '' }}</pre>
        </el-tab-pane>
        <el-tab-pane label="输出格式（锁定）" name="contract">
          <pre class="preview-pre">{{ preview?.outputContract || '' }}</pre>
        </el-tab-pane>
      </el-tabs>
      <template #footer>
        <el-button :icon="DocumentCopy" @click="copyFull">复制完整提示词</el-button>
        <el-button type="primary" @click="previewVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Check, DocumentCopy, Lock, RefreshLeft, Search, View } from '@element-plus/icons-vue'
import {
  PROMPT_GROUP_ORDER,
  getPromptTemplate,
  listPromptTemplates,
  previewPromptTemplate,
  resetPromptTemplate,
  savePromptTemplate,
  type PromptPreviewVO,
  type PromptTemplateDetailVO,
  type PromptTemplateItemVO
} from '@/api/prompt-template'

const loading = ref(false)
const items = ref<PromptTemplateItemVO[]>([])
const keyword = ref('')
const current = ref<PromptTemplateDetailVO | null>(null)
const content = ref('')
const saving = ref(false)
const previewing = ref(false)
const previewVisible = ref(false)
const previewTab = ref<'full' | 'content' | 'contract'>('full')
const preview = ref<PromptPreviewVO | null>(null)
const editorRef = ref<any>(null)

const editableCount = computed(() => items.value.filter((item) => item.editable).length)

/** 全部可编辑时只显示总数，避免出现「12 / 12」这种冗余 */
const countLabel = computed(() =>
  editableCount.value === items.value.length
    ? String(items.value.length)
    : `${editableCount.value} / ${items.value.length}`
)

const dirty = computed(() => !!current.value && current.value.editable && content.value !== current.value.content)

const groups = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  const filtered = kw
    ? items.value.filter(
        (item) =>
          item.displayName.toLowerCase().includes(kw) ||
          item.scene.toLowerCase().includes(kw) ||
          item.group.toLowerCase().includes(kw)
      )
    : items.value

  const map = new Map<string, PromptTemplateItemVO[]>()
  filtered.forEach((item) => {
    const list = map.get(item.group) ?? []
    list.push(item)
    map.set(item.group, list)
  })

  const order = (name: string) => {
    const idx = PROMPT_GROUP_ORDER.indexOf(name)
    return idx < 0 ? PROMPT_GROUP_ORDER.length : idx
  }

  return Array.from(map.entries())
    .sort((a, b) => order(a[0]) - order(b[0]))
    .map(([name, list]) => ({
      name,
      items: list.slice().sort((a, b) => Number(b.editable) - Number(a.editable))
    }))
})

async function loadList() {
  loading.value = true
  try {
    items.value = (await listPromptTemplates()) || []
    const first = items.value.find((item) => item.editable) ?? items.value[0]
    if (first && !current.value) {
      await selectScene(first.scene, true)
    }
  } catch (error) {
    ElMessage.error((error as Error).message || '加载提示词模板失败')
  } finally {
    loading.value = false
  }
}

async function selectScene(scene: string, force = false) {
  if (!force && current.value?.scene === scene) {
    return
  }
  if (!force && dirty.value) {
    try {
      await ElMessageBox.confirm('当前场景有未保存的修改，切换后会丢失。确定切换吗？', '未保存的修改', {
        type: 'warning',
        confirmButtonText: '放弃修改',
        cancelButtonText: '继续编辑'
      })
    } catch {
      return
    }
  }
  try {
    const detail = await getPromptTemplate(scene)
    current.value = detail
    content.value = detail.content || ''
  } catch (error) {
    ElMessage.error((error as Error).message || '加载模板详情失败')
  }
}

async function onSave() {
  if (!current.value) {
    return
  }
  if (!content.value.trim()) {
    ElMessage.warning('指令段不能为空')
    return
  }
  saving.value = true
  try {
    const detail = await savePromptTemplate(current.value.scene, content.value)
    current.value = detail
    content.value = detail.content || ''
    // 同步列表里的「已改」标记
    const row = items.value.find((item) => item.scene === detail.scene)
    if (row) {
      row.overridden = detail.overridden
      row.updatedAt = detail.updatedAt
    }
    ElMessage.success('模板已保存，下次生成立即生效')
  } catch (error) {
    ElMessage.error((error as Error).message || '保存失败')
  } finally {
    saving.value = false
  }
}

async function onReset() {
  if (!current.value) {
    return
  }
  try {
    await ElMessageBox.confirm(
      `「${current.value.displayName}」会恢复成系统默认模板，你的自定义内容将被删除。确定恢复吗？`,
      '恢复默认模板',
      { type: 'warning', confirmButtonText: '恢复默认', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await resetPromptTemplate(current.value.scene)
    const detail = await getPromptTemplate(current.value.scene)
    current.value = detail
    content.value = detail.content || ''
    const row = items.value.find((item) => item.scene === detail.scene)
    if (row) {
      row.overridden = detail.overridden
      row.updatedAt = detail.updatedAt
    }
    ElMessage.success('已恢复默认模板')
  } catch (error) {
    ElMessage.error((error as Error).message || '恢复失败')
  }
}

function fillDefault() {
  if (!current.value) {
    return
  }
  content.value = current.value.defaultContent
}

/** 变量占位符：拼接生成，避免模板里出现字面双花括号被 Vue 当成插值 */
function varToken(name: string) {
  return '{' + '{' + name + '}' + '}'
}

function insertVariable(name: string) {
  const token = varToken(name)
  const textarea = editorRef.value?.textarea
  if (!textarea) {
    content.value += token
    return
  }
  const start = textarea.selectionStart ?? content.value.length
  const end = textarea.selectionEnd ?? content.value.length
  content.value = content.value.slice(0, start) + token + content.value.slice(end)
  requestAnimationFrame(() => {
    textarea.focus()
    const pos = start + token.length
    textarea.setSelectionRange(pos, pos)
  })
}

async function onPreview() {
  if (!current.value) {
    return
  }
  previewing.value = true
  try {
    // 预览用的是已保存的内容，避免用户以为没保存也生效
    if (dirty.value) {
      ElMessage.warning('预览基于已保存的内容，当前修改尚未保存')
    }
    preview.value = await previewPromptTemplate(current.value.scene)
    previewTab.value = 'full'
    previewVisible.value = true
  } catch (error) {
    ElMessage.error((error as Error).message || '预览失败')
  } finally {
    previewing.value = false
  }
}

async function copyFull() {
  const text = preview.value?.full
  if (!text) {
    return
  }
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('已复制到剪贴板')
  } catch {
    ElMessage.warning('当前环境不支持自动复制，请手动选中文本复制')
  }
}

function formatTime(value?: string) {
  if (!value) {
    return ''
  }
  return value.replace('T', ' ').slice(0, 16)
}

onMounted(loadList)
</script>

<style scoped>
.prompt-card :deep(.el-card__body) {
  padding: 0;
}

.prompt-layout {
  display: flex;
  min-height: calc(100vh - 168px);
}

/* ---------- 左侧 ---------- */
.scene-aside {
  width: 244px;
  flex: 0 0 244px;
  padding: 14px 10px 14px 14px;
  border-right: 1px solid var(--sf-border);
  background: var(--sf-bg-subtle);
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.aside-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-right: 4px;
}

.aside-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--sf-text-primary);
}

.aside-search :deep(.el-input__wrapper) {
  background: var(--sf-bg-input);
}

.scene-scroll {
  flex: 1;
  overflow-y: auto;
  margin-right: -4px;
  padding-right: 4px;
}

.scene-group + .scene-group {
  margin-top: 10px;
}

.group-title {
  margin: 4px 0 4px 6px;
  font-size: 11px;
  letter-spacing: 0.4px;
  color: var(--sf-text-muted);
}

.scene-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
  width: 100%;
  padding: 7px 10px;
  border: 1px solid transparent;
  border-radius: 8px;
  background: transparent;
  font-size: 13px;
  color: var(--sf-text-regular);
  text-align: left;
  cursor: pointer;
  transition: background-color 0.15s, color 0.15s, border-color 0.15s;
}

.scene-item:hover {
  background: var(--sf-bg-hover);
  color: var(--sf-text-primary);
}

.scene-item.active {
  background: var(--sf-bg-tint-strong);
  border-color: var(--sf-brand);
  color: var(--sf-brand);
  font-weight: 600;
}

.scene-item.readonly .scene-name {
  color: var(--sf-text-muted);
}

.scene-flags {
  display: flex;
  align-items: center;
  gap: 4px;
}

.lock-icon {
  font-size: 12px;
  color: var(--sf-text-muted);
}

/* ---------- 右侧 ---------- */
.editor-pane {
  flex: 1;
  min-width: 0;
  padding: 16px 20px 20px;
  display: flex;
  flex-direction: column;
}

.editor-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.scene-title {
  margin: 0 0 6px;
  font-size: 17px;
  font-weight: 600;
  color: var(--sf-text-primary);
}

.head-tags {
  display: flex;
  align-items: center;
  gap: 6px;
}

.head-meta {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 11px;
  color: var(--sf-text-muted);
}

.head-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

.hint-line {
  margin-bottom: 10px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--sf-text-muted);
}

.var-bar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 8px;
}

.var-label {
  font-size: 12px;
  color: var(--sf-text-muted);
}

.var-chip {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 2px 8px;
  border: 1px dashed var(--sf-border-strong);
  border-radius: 999px;
  background: var(--sf-bg-card);
  cursor: pointer;
  transition: border-color 0.15s, background-color 0.15s;
}

.var-chip:hover {
  border-color: var(--sf-brand);
  background: var(--sf-bg-tint);
}

.var-name {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 11px;
  color: var(--sf-brand);
}

.var-label-text {
  font-size: 11px;
  color: var(--sf-text-muted);
}

.editor-wrap {
  display: flex;
  flex-direction: column;
}

.prompt-textarea :deep(.el-textarea__inner) {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 13px;
  line-height: 1.75;
  background: var(--sf-bg-input);
  color: var(--sf-text-primary);
}

.editor-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 4px;
}

.dirty-tip {
  font-size: 12px;
  color: var(--sf-text-warning);
}

/* ---------- 锁定区 ---------- */
.locked-block {
  margin-top: 16px;
  border: 1px solid var(--sf-border);
  border-radius: 8px;
  background: var(--sf-bg-subtle);
  overflow: hidden;
}

.locked-head {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 12px;
  border-bottom: 1px solid var(--sf-border);
  background: var(--sf-bg-tint);
}

.locked-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--sf-text-primary);
}

.locked-hint {
  margin-left: auto;
  font-size: 11px;
  color: var(--sf-text-muted);
}

.locked-body {
  margin: 0;
  padding: 12px;
  max-height: 240px;
  overflow: auto;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  line-height: 1.7;
  color: var(--sf-text-regular);
  white-space: pre-wrap;
  word-break: break-word;
}

.updated-at {
  margin-top: 8px;
  font-size: 11px;
  color: var(--sf-text-muted);
}

/* ---------- 预览 ---------- */
.preview-pre {
  margin: 0;
  padding: 12px;
  max-height: 52vh;
  overflow: auto;
  border: 1px solid var(--sf-border);
  border-radius: 8px;
  background: var(--sf-bg-subtle);
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  line-height: 1.75;
  color: var(--sf-text-regular);
  white-space: pre-wrap;
  word-break: break-word;
}

.text-muted {
  font-size: 12px;
  color: var(--sf-text-muted);
}

.mb12 {
  margin-bottom: 12px;
}
</style>
