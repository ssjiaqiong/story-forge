<template>
  <div class="page-container">
    <el-card shadow="never" class="prompt-card">
      <div class="io-toolbar">
        <div class="io-toolbar-title">提示词模板</div>
        <div class="io-toolbar-actions">
          <el-button size="small" :icon="Download" @click="exportVisible = true">导出</el-button>
          <el-button size="small" :icon="Upload" :loading="importing" @click="pickImportFile">导入</el-button>
          <input
            ref="importInputRef"
            type="file"
            accept="application/json,.json"
            class="io-hidden-input"
            @change="onImportFileChange"
          />
        </div>
      </div>
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

    <!-- 导出 -->
    <el-dialog v-model="exportVisible" title="导出提示词模板" width="520px" destroy-on-close>
      <p class="io-tip">导出为 JSON 文件，可在另一台机器 / 另一个账号导入。</p>
      <el-checkbox v-model="exportOnlyOverridden">
        只导出我改过的场景
        <span class="io-sub">（推荐；系统默认模板随版本升级会变，导回去没有意义）</span>
      </el-checkbox>
      <template #footer>
        <el-button @click="exportVisible = false">取消</el-button>
        <el-button type="primary" :loading="exporting" @click="doExport">导出</el-button>
      </template>
    </el-dialog>

    <!-- 导入：先预览，再确认 -->
    <el-dialog v-model="importVisible" title="导入提示词模板" width="720px" destroy-on-close>
      <!-- 结果态 -->
      <template v-if="importResult">
        <el-alert
          :type="importResult.imported > 0 ? 'success' : 'warning'"
          :closable="false"
          show-icon
          :title="`共 ${importResult.total} 条，成功导入 ${importResult.imported} 条，跳过 ${importResult.skipped} 条（${modeLabel(importResult.mode)}）`"
        />
        <ul class="io-list">
          <li v-for="(it, i) in importResult.items" :key="`r-${i}`">
            <span :class="it.status === 'IMPORTED' ? 'io-ok' : 'io-skip'">
              {{ it.status === 'IMPORTED' ? '导入' : '跳过' }}
            </span>
            {{ it.displayName || it.scene }}
            <span v-if="it.message" class="io-msg">— {{ it.message }}</span>
          </li>
        </ul>
      </template>
      <!-- 预览态 -->
      <template v-else>
        <el-alert
          v-if="previewPackage"
          class="mb12"
          type="info"
          :closable="false"
          show-icon
          :title="`文件含 ${previewPackage.scenes.length} 条模板`"
        />
        <el-table :data="previewRows" size="small" max-height="320">
          <el-table-column prop="displayName" label="场景" min-width="140" show-overflow-tooltip />
          <el-table-column prop="scene" label="编码" min-width="150" show-overflow-tooltip />
          <el-table-column label="当前" width="80">
            <template #default="{ row }">
              <el-tag v-if="row.overridden" size="small" type="warning" effect="plain">已改</el-tag>
              <span v-else class="io-muted">默认</span>
            </template>
          </el-table-column>
          <el-table-column label="动作" width="80">
            <template #default="{ row }">
              <span :class="row.valid ? 'io-ok' : 'io-skip'">{{ row.action }}</span>
            </template>
          </el-table-column>
        </el-table>
        <el-alert
          v-if="previewInvalid > 0"
          class="mt12"
          type="warning"
          :closable="false"
          :title="`其中 ${previewInvalid} 条会被跳过（未知场景 / 不可编辑 / 内容为空）`"
        />
        <div class="io-mode">
          <span class="io-mode-label">导入方式</span>
          <el-radio-group v-model="importMode">
            <el-radio value="MERGE">覆盖同名</el-radio>
            <el-radio value="SKIP_EXISTING">只填空缺</el-radio>
            <el-radio value="REPLACE">清空后导入</el-radio>
          </el-radio-group>
        </div>
        <el-alert
          v-if="importMode === 'REPLACE'"
          class="mt12"
          type="error"
          :closable="false"
          show-icon
          title="「清空后导入」会先删除当前账号的全部自定义模板，只剩这个包里的内容，请谨慎使用。"
        />
      </template>
      <template #footer>
        <template v-if="importResult">
          <el-button type="primary" @click="closeImport">知道了</el-button>
        </template>
        <template v-else>
          <el-button @click="closeImport">取消</el-button>
          <el-button type="primary" :loading="importing" @click="doImport">确认导入</el-button>
        </template>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Check, DocumentCopy, Download, Lock, RefreshLeft, Search, Upload, View } from '@element-plus/icons-vue'
import {
  PROMPT_GROUP_ORDER,
  exportPromptTemplates,
  getPromptTemplate,
  importPromptTemplates,
  listPromptTemplates,
  previewPromptTemplate,
  resetPromptTemplate,
  savePromptTemplate,
  type PromptExportPackageVO,
  type PromptImportMode,
  type PromptImportResultVO,
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

/* ---------------- 导入 / 导出 ---------------- */

const exporting = ref(false)
const importing = ref(false)
const exportVisible = ref(false)
const exportOnlyOverridden = ref(true)
const importVisible = ref(false)
const importInputRef = ref<HTMLInputElement | null>(null)
const previewPackage = ref<PromptExportPackageVO | null>(null)
const importMode = ref<PromptImportMode>('MERGE')
const importResult = ref<PromptImportResultVO | null>(null)

/** 预览表：把包里的场景对照当前状态，标出能否导入与将要执行的动作 */
const previewRows = computed(() => {
  const pkg = previewPackage.value
  if (!pkg) {
    return []
  }
  return (pkg.scenes || []).map((item) => {
    const known = items.value.find((row) => row.scene === item.scene)
    const valid = Boolean(known && known.editable && String(item.content || '').trim())
    let action: string
    if (!valid) {
      action = '跳过'
    } else if (importMode.value === 'SKIP_EXISTING') {
      action = known?.overridden ? '跳过' : '新建'
    } else {
      action = known?.overridden ? '覆盖' : '新建'
    }
    return {
      scene: item.scene,
      displayName: known?.displayName || item.displayName || item.scene,
      overridden: Boolean(known?.overridden),
      valid,
      action
    }
  })
})

const previewInvalid = computed(() => previewRows.value.filter((row) => !row.valid).length)

function modeLabel(mode: string) {
  return mode === 'REPLACE' ? '清空后导入' : mode === 'SKIP_EXISTING' ? '只填空缺' : '覆盖同名'
}

/** blob 响应里的错误体是 JSON 文件流，需要自己读出来才能拿到后端提示 */
async function readBlobError(error: unknown): Promise<string> {
  const data = (error as { response?: { data?: unknown } })?.response?.data
  if (data instanceof Blob) {
    try {
      const parsed = JSON.parse(await data.text()) as { message?: string }
      if (parsed?.message) {
        return parsed.message
      }
    } catch {
      // 不是 JSON 就退回通用文案
    }
  }
  return (error as Error)?.message || '导出失败'
}

async function doExport() {
  exporting.value = true
  try {
    const blob = await exportPromptTemplates(exportOnlyOverridden.value)
    const now = new Date()
    const stamp = [
      now.getFullYear(),
      String(now.getMonth() + 1).padStart(2, '0'),
      String(now.getDate()).padStart(2, '0'),
      '-',
      String(now.getHours()).padStart(2, '0'),
      String(now.getMinutes()).padStart(2, '0'),
      String(now.getSeconds()).padStart(2, '0')
    ].join('')
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `storyforge-prompts-${stamp}.json`
    document.body.appendChild(link)
    link.click()
    document.body.removeChild(link)
    URL.revokeObjectURL(url)
    const count = exportOnlyOverridden.value
      ? items.value.filter((item) => item.overridden).length
      : items.value.filter((item) => item.editable).length
    if (count === 0) {
      ElMessage.info('当前没有自定义模板，已导出空包')
    } else {
      ElMessage.success(`已导出 ${count} 条模板`)
    }
    exportVisible.value = false
  } catch (error) {
    ElMessage.error(await readBlobError(error))
  } finally {
    exporting.value = false
  }
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
  if (file.size > 2 * 1024 * 1024) {
    ElMessage.error('文件超过 2MB，请确认选择的是提示词模板导出的 JSON')
    return
  }
  try {
    const pkg = JSON.parse(await file.text()) as PromptExportPackageVO
    if (!pkg || !Array.isArray(pkg.scenes)) {
      ElMessage.error('文件结构不对，缺少 scenes 数组')
      return
    }
    if (pkg.format !== 'storyforge-prompt-export') {
      ElMessage.error('这不是提示词模板包（format 不匹配）')
      return
    }
    previewPackage.value = pkg
    importResult.value = null
    importMode.value = 'MERGE'
    importVisible.value = true
  } catch {
    ElMessage.error('文件不是合法的 JSON')
  }
}

async function doImport() {
  const pkg = previewPackage.value
  if (!pkg) {
    return
  }
  if (importMode.value === 'REPLACE') {
    try {
      await ElMessageBox.confirm(
        '「清空后导入」会先删除当前账号的全部自定义模板，再写入这个包的内容。确定继续吗？',
        '清空后导入',
        { type: 'warning', confirmButtonText: '确认清空并导入', cancelButtonText: '取消' }
      )
    } catch {
      return
    }
  }
  importing.value = true
  try {
    const result = await importPromptTemplates(pkg, importMode.value)
    importResult.value = result
    // 重新拉列表并选中第一个导入成功的场景
    const firstImported = result.items.find((item) => item.status === 'IMPORTED')?.scene
    await loadList()
    if (firstImported) {
      await selectScene(firstImported, true)
    }
    if (result.imported > 0) {
      ElMessage.success(`已导入 ${result.imported} 条模板`)
    } else {
      ElMessage.warning('没有导入任何模板，请查看跳过原因')
    }
  } catch (error) {
    ElMessage.error((error as Error).message || '导入失败')
  } finally {
    importing.value = false
  }
}

function closeImport() {
  importVisible.value = false
  previewPackage.value = null
  importResult.value = null
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

/* ---------- 顶部工具条 ---------- */
.io-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 20px;
  border-bottom: 1px solid var(--sf-border);
}

.io-toolbar-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--sf-text);
}

.io-toolbar-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.io-hidden-input {
  display: none;
}

/* ---------- 导入 / 导出弹窗 ---------- */
.io-tip {
  margin: 0 0 12px;
  font-size: 13px;
  color: var(--sf-text-muted);
}

.io-sub {
  margin-left: 4px;
  font-size: 12px;
  color: var(--sf-text-muted);
}

.io-list {
  margin: 12px 0 0;
  padding-left: 0;
  list-style: none;
  max-height: 320px;
  overflow: auto;
}

.io-list li {
  padding: 4px 0;
  font-size: 13px;
  color: var(--sf-text);
  border-bottom: 1px dashed var(--sf-border);
}

.io-ok {
  color: var(--sf-success, #67c23a);
  margin-right: 6px;
}

.io-skip {
  color: var(--sf-warning, #e6a23c);
  margin-right: 6px;
}

.io-msg {
  color: var(--sf-text-muted);
}

.io-muted {
  font-size: 12px;
  color: var(--sf-text-muted);
}

.io-mode {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 16px;
}

.io-mode-label {
  font-size: 13px;
  color: var(--sf-text-muted);
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
