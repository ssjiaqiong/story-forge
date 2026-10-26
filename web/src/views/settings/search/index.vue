<template>
  <div class="page-container">
    <el-card shadow="never">
      <el-tabs v-model="activeTab">
        <!-- ==================== 搜索源 ==================== -->
        <el-tab-pane label="搜索源" name="configs">
          <el-alert
            class="mb12"
            type="info"
            :closable="false"
            show-icon
            title="默认已集成免密钥方案：必应（默认）、DuckDuckGo、维基百科。也可以新增 SearXNG 实例或填写 Key 使用 Tavily / Serper。"
          />

          <div class="toolbar">
            <el-button type="primary" :icon="Plus" @click="openCreate">新增搜索源</el-button>
            <el-button :icon="Refresh" @click="loadAll">刷新</el-button>
            <div class="toolbar-right">
              <el-input
                v-model="previewQuery"
                class="preview-input"
                placeholder="输入关键词试搜"
                clearable
                @keyup.enter="onPreview"
              />
              <el-button :icon="Search" :loading="previewing" @click="onPreview">试搜</el-button>
            </div>
          </div>

          <el-table v-loading="loading" :data="configs" border stripe>
            <el-table-column label="名称" min-width="170">
              <template #default="{ row }">
                <div class="name-cell">
                  <span class="name-text">{{ row.name }}</span>
                  <el-tag v-if="row.isDefault" type="success" size="small" effect="dark">默认</el-tag>
                  <el-tag v-if="!row.enabled" type="info" size="small">已停用</el-tag>
                </div>
                <div v-if="row.remark" class="sub-text">{{ row.remark }}</div>
              </template>
            </el-table-column>

            <el-table-column label="类型" width="132">
              <template #default="{ row }">
                <div class="provider-name">{{ row.providerLabel }}</div>
                <div class="provider-meta">
                  <el-tag :type="row.requiresKey ? 'warning' : 'success'" size="small" effect="plain">
                    {{ row.requiresKey ? '需密钥' : '免密钥' }}
                  </el-tag>
                  <span v-if="row.requiresKey" class="sub-text">
                    {{ row.apiKeyConfigured ? row.apiKeyMask : '未配置' }}
                  </span>
                </div>
              </template>
            </el-table-column>

            <el-table-column label="接口地址" min-width="210" show-overflow-tooltip>
              <template #default="{ row }">
                <span class="mono">{{ row.baseUrl }}</span>
                <div class="sub-text">返回 {{ row.defaultTopK }} 条 · 超时 {{ Math.round(row.timeoutMs / 1000) }}s</div>
              </template>
            </el-table-column>

            <el-table-column label="最近测试" width="160">
              <template #default="{ row }">
                <template v-if="row.lastTestAt">
                  <el-tag :type="row.lastTestOk ? 'success' : 'danger'" size="small" effect="plain">
                    {{ row.lastTestOk ? '成功' : '失败' }}
                  </el-tag>
                  <span class="sub-text"> {{ row.lastTestLatencyMs }}ms</span>
                  <div class="sub-text ellipsis" :title="row.lastTestMessage">{{ row.lastTestMessage }}</div>
                </template>
                <span v-else class="text-muted">未测试</span>
              </template>
            </el-table-column>

            <el-table-column label="操作" width="250" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" size="small" :loading="testingId === row.id" @click="onTest(row)">
                  测试
                </el-button>
                <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
                <el-button
                  link
                  type="primary"
                  size="small"
                  :disabled="row.isDefault || !row.enabled"
                  @click="onSetDefault(row)"
                >
                  设为默认
                </el-button>
                <el-button link :type="row.enabled ? 'warning' : 'success'" size="small" @click="onToggle(row)">
                  {{ row.enabled ? '停用' : '启用' }}
                </el-button>
                <el-button link type="danger" size="small" @click="onDelete(row)">删除</el-button>
              </template>
            </el-table-column>

            <template #empty>
              <el-empty description="还没有搜索源，点击「新增搜索源」或刷新以初始化默认方案" />
            </template>
          </el-table>
        </el-tab-pane>

        <!-- ==================== 场景绑定 ==================== -->
        <el-tab-pane label="场景绑定" name="scenes">
          <el-alert
            class="mb12"
            type="info"
            :closable="false"
            show-icon
            title="为「事件生成 / 大纲生成 / 正文生成」分别指定搜索源。留空表示沿用默认搜索源；关闭后该功能不再联网检索。"
          />

          <div class="toolbar">
            <el-button type="primary" :icon="Check" :loading="scenesSaving" @click="saveScenes">保存绑定</el-button>
            <div class="toolbar-right">
              <span class="text-muted">当前默认搜索源：{{ defaultConfigName }}</span>
            </div>
          </div>

          <el-table v-loading="loading" :data="scenes" border>
            <el-table-column label="生成场景" min-width="180">
              <template #default="{ row }">
                <div class="name-text">{{ row.label }}</div>
                <div class="sub-text">{{ row.group }} · {{ sceneHint(row.code) }}</div>
              </template>
            </el-table-column>
            <el-table-column label="启用联网检索" width="130" align="center">
              <template #default="{ row }">
                <el-switch v-model="row.enabled" />
              </template>
            </el-table-column>
            <el-table-column label="搜索源" min-width="220">
              <template #default="{ row }">
                <el-select
                  v-model="row.configId"
                  class="w-full"
                  clearable
                  :disabled="!row.enabled"
                  :placeholder="`默认（${defaultConfigName}）`"
                >
                  <el-option
                    v-for="item in enabledConfigs"
                    :key="item.id"
                    :label="`${item.name}（${item.providerLabel}）`"
                    :value="item.id"
                  />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="返回条数" width="150">
              <template #default="{ row }">
                <el-select v-model="row.topK" class="w-full" clearable :disabled="!row.enabled" placeholder="默认">
                  <el-option v-for="n in [3, 5, 6, 8, 10]" :key="n" :label="`${n} 条`" :value="n" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="实际使用" min-width="160">
              <template #default="{ row }">
                <el-tag v-if="row.enabled" size="small" effect="plain">
                  {{ row.configName || row.effectiveConfigName || defaultConfigName }}
                </el-tag>
                <span v-else class="text-muted">已关闭</span>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <!-- 搜索源弹窗 -->
    <el-dialog v-model="dialog.visible" :title="dialog.id ? '编辑搜索源' : '新增搜索源'" width="600px" destroy-on-close>
      <el-form label-width="96px">
        <el-form-item label="搜索方式" required>
          <el-select v-model="dialog.provider" class="w-full" @change="onProviderChange">
            <el-option v-for="item in SEARCH_PROVIDERS" :key="item.value" :label="item.label" :value="item.value">
              <span class="provider-option">
                <span>{{ item.label }}</span>
                <el-tag :type="item.requiresKey ? 'warning' : 'success'" size="small" effect="plain">
                  {{ item.requiresKey ? '需密钥' : '免密钥' }}
                </el-tag>
              </span>
            </el-option>
          </el-select>
          <div class="form-hint">{{ providerMeta.description }}</div>
        </el-form-item>
        <el-form-item label="名称" required>
          <el-input v-model="dialog.name" maxlength="100" placeholder="如：必应（主用）" />
        </el-form-item>
        <el-form-item label="接口地址">
          <el-input v-model="dialog.baseUrl" maxlength="500" :placeholder="providerMeta.baseUrl" />
          <div class="form-hint">留空使用默认地址；SearXNG 请填写自建实例地址，维基百科可填镜像地址。</div>
        </el-form-item>
        <el-form-item label="API Key" :required="providerMeta.requiresKey">
          <el-input
            v-model="dialog.apiKey"
            type="password"
            show-password
            maxlength="500"
            :placeholder="providerMeta.requiresKey ? '填写服务商提供的 Key' : '免密钥方案无需填写'"
          />
          <div v-if="dialog.id && dialog.apiKeyConfigured" class="form-hint">
            已保存密钥 {{ dialog.apiKeyMask }}，留空表示不修改。
          </div>
        </el-form-item>
        <el-row :gutter="12">
          <el-col :span="8">
            <el-form-item label="返回条数">
              <el-input-number v-model="dialog.defaultTopK" :min="1" :max="10" controls-position="right" class="w-full" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="超时(秒)">
              <el-input-number
                v-model="dialog.timeoutSeconds"
                :min="3"
                :max="60"
                controls-position="right"
                class="w-full"
              />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="语言">
              <el-input v-model="dialog.language" maxlength="32" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="备注">
          <el-input v-model="dialog.remark" maxlength="500" placeholder="可选，比如用途或额度提醒" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="dialog.enabled" />
          <span class="inline-hint">停用后不再参与检索</span>
        </el-form-item>
        <el-form-item label="设为默认">
          <el-switch v-model="dialog.isDefault" :disabled="!dialog.enabled" />
          <span class="inline-hint">同一时间只有一个默认搜索源</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :loading="testingForm" @click="onTestForm">测试连接</el-button>
        <el-button @click="dialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onSubmit">保存</el-button>
      </template>
    </el-dialog>

    <!-- 测试 / 试搜结果 -->
    <el-dialog v-model="resultVisible" :title="resultTitle" width="680px">
      <el-alert
        v-if="testResult"
        :type="testResult.ok ? 'success' : 'error'"
        :closable="false"
        show-icon
        :title="`${testResult.providerLabel || ''} · ${testResult.latencyMs}ms · ${testResult.message}`"
      />
      <p v-else-if="previewBundle" class="preview-meta">
        {{ previewBundle.providerLabel || previewBundle.provider || '默认搜索源' }}
        <span v-if="previewBundle.query"> · 检索「{{ previewBundle.query }}」</span>
        <span> · {{ previewBundle.elapsedMs }}ms</span>
      </p>
      <el-alert
        v-if="previewBundle && previewBundle.error && !previewBundle.results.length"
        class="mt8"
        type="warning"
        :closable="false"
        show-icon
        :title="previewBundle.error"
      />
      <ol v-if="resultItems.length" class="result-list">
        <li v-for="item in resultItems" :key="item.url">
          <a :href="item.url" target="_blank" rel="noopener noreferrer">{{ item.title }}</a>
          <span class="result-host">{{ item.host }}</span>
          <p v-if="item.snippet">{{ item.snippet }}</p>
        </li>
      </ol>
      <el-empty v-else description="没有检索到结果" :image-size="80" />
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Check, Plus, Refresh, Search } from '@element-plus/icons-vue'
import {
  SEARCH_PROVIDERS,
  createSearchConfig,
  deleteSearchConfig,
  getSearchConfig,
  listSearchConfigs,
  listSearchScenes,
  previewSearch,
  probeSearchConfig,
  saveSearchScenes,
  setDefaultSearchConfig,
  testSearchConfig,
  toggleSearchConfig,
  updateSearchConfig,
  type SearchBundleVO,
  type SearchConfigVO,
  type SearchResultVO,
  type SearchSceneVO,
  type SearchTestResultVO
} from '@/api/search'

const activeTab = ref<'configs' | 'scenes'>('configs')
const loading = ref(false)
const configs = ref<SearchConfigVO[]>([])
const scenes = ref<SearchSceneVO[]>([])

const dialog = reactive({
  visible: false,
  id: '',
  name: '',
  provider: 'BING',
  baseUrl: '',
  apiKey: '',
  apiKeyMask: '',
  apiKeyConfigured: false,
  defaultTopK: 6,
  timeoutSeconds: 15,
  language: 'zh-CN',
  enabled: true,
  isDefault: false,
  remark: ''
})
const saving = ref(false)
const testingForm = ref(false)
const testingId = ref('')

const resultVisible = ref(false)
const resultTitle = ref('检索结果')
const testResult = ref<SearchTestResultVO | null>(null)
const previewBundle = ref<SearchBundleVO | null>(null)
const previewQuery = ref('')
const previewing = ref(false)

const scenesSaving = ref(false)

const providerMeta = computed(
  () => SEARCH_PROVIDERS.find((item) => item.value === dialog.provider) ?? SEARCH_PROVIDERS[0]
)
const enabledConfigs = computed(() => configs.value.filter((item) => item.enabled))
const defaultConfigName = computed(() => configs.value.find((item) => item.isDefault)?.name || '未设置')

const resultItems = computed<SearchResultVO[]>(() =>
  testResult.value ? testResult.value.results : previewBundle.value ? previewBundle.value.results : []
)

const SCENE_HINTS: Record<string, string> = {
  EVENT_SUPPLY: '补事件时检索真实背景',
  OUTLINE_GEN: '章节大纲的事实与细节参考',
  BODY_GEN: '写正文时的行业、地理、年代细节'
}

function sceneHint(code: string) {
  return SCENE_HINTS[code] ?? '生成时检索资料'
}

async function loadAll() {
  loading.value = true
  try {
    const [configList, sceneList] = await Promise.all([listSearchConfigs(), listSearchScenes()])
    configs.value = configList || []
    scenes.value = (sceneList || []).map((item) => ({ ...item, topK: item.topK ?? undefined }))
  } catch (error) {
    ElMessage.error((error as Error).message || '加载联网搜索配置失败')
  } finally {
    loading.value = false
  }
}

function openCreate() {
  dialog.id = ''
  dialog.provider = 'BING'
  dialog.name = '必应'
  dialog.baseUrl = SEARCH_PROVIDERS[0].baseUrl
  dialog.apiKey = ''
  dialog.apiKeyMask = ''
  dialog.apiKeyConfigured = false
  dialog.defaultTopK = 6
  dialog.timeoutSeconds = 15
  dialog.language = 'zh-CN'
  dialog.enabled = true
  dialog.isDefault = configs.value.length === 0
  dialog.remark = ''
  dialog.visible = true
}

async function openEdit(row: SearchConfigVO) {
  try {
    const detail = await getSearchConfig(row.id)
    dialog.id = detail.id
    dialog.provider = detail.provider
    dialog.name = detail.name
    dialog.baseUrl = detail.baseUrl || ''
    dialog.apiKey = detail.apiKey || ''
    dialog.apiKeyMask = detail.apiKeyMask || ''
    dialog.apiKeyConfigured = detail.apiKeyConfigured
    dialog.defaultTopK = detail.defaultTopK
    dialog.timeoutSeconds = Math.round(detail.timeoutMs / 1000)
    dialog.language = detail.language || 'zh-CN'
    dialog.enabled = detail.enabled
    dialog.isDefault = detail.isDefault
    dialog.remark = detail.remark || ''
    dialog.visible = true
  } catch (error) {
    ElMessage.error((error as Error).message || '加载搜索源详情失败')
  }
}

function onProviderChange() {
  const meta = providerMeta.value
  dialog.baseUrl = meta.baseUrl
  if (!dialog.name || SEARCH_PROVIDERS.some((item) => item.label === dialog.name)) {
    dialog.name = meta.label
  }
}

async function onSubmit() {
  if (!dialog.name.trim()) {
    ElMessage.warning('请填写搜索源名称')
    return
  }
  if (providerMeta.value.requiresKey && !dialog.apiKey.trim() && !dialog.apiKeyConfigured) {
    ElMessage.warning(`${providerMeta.value.label} 需要填写 API Key`)
    return
  }
  if (!dialog.enabled && dialog.isDefault) {
    ElMessage.warning('停用的搜索源不能设为默认')
    return
  }
  saving.value = true
  try {
    const payload = {
      name: dialog.name.trim(),
      provider: dialog.provider,
      baseUrl: dialog.baseUrl.trim(),
      apiKey: dialog.apiKey.trim(),
      defaultTopK: dialog.defaultTopK,
      language: dialog.language,
      timeoutMs: dialog.timeoutSeconds * 1000,
      enabled: dialog.enabled,
      isDefault: dialog.isDefault,
      remark: dialog.remark
    }
    if (dialog.id) {
      await updateSearchConfig(dialog.id, payload)
    } else {
      await createSearchConfig(payload)
    }
    dialog.visible = false
    ElMessage.success('搜索源已保存')
    await loadAll()
  } catch (error) {
    ElMessage.error((error as Error).message || '保存失败')
  } finally {
    saving.value = false
  }
}

async function onTest(row: SearchConfigVO) {
  testingId.value = row.id
  try {
    const result = await testSearchConfig(row.id)
    testResult.value = result
    previewBundle.value = null
    resultTitle.value = `${row.name} · 连通性测试`
    resultVisible.value = true
    await loadAll()
  } catch (error) {
    ElMessage.error((error as Error).message || '测试失败')
  } finally {
    testingId.value = ''
  }
}

async function onTestForm() {
  testingForm.value = true
  try {
    const result = await probeSearchConfig({
      id: dialog.id || undefined,
      provider: dialog.provider,
      baseUrl: dialog.baseUrl.trim(),
      apiKey: dialog.apiKey.trim(),
      topK: dialog.defaultTopK
    })
    testResult.value = result
    previewBundle.value = null
    resultTitle.value = `${providerMeta.value.label} · 连通性测试`
    resultVisible.value = true
  } catch (error) {
    ElMessage.error((error as Error).message || '测试失败')
  } finally {
    testingForm.value = false
  }
}

async function onPreview() {
  const query = previewQuery.value.trim()
  if (!query) {
    ElMessage.warning('先输入试搜关键词')
    return
  }
  previewing.value = true
  try {
    previewBundle.value = await previewSearch({ query, topK: 6 })
    testResult.value = null
    resultTitle.value = `试搜 · ${query}`
    resultVisible.value = true
  } catch (error) {
    ElMessage.error((error as Error).message || '试搜失败')
  } finally {
    previewing.value = false
  }
}

async function onSetDefault(row: SearchConfigVO) {
  try {
    await setDefaultSearchConfig(row.id)
    ElMessage.success(`已把「${row.name}」设为默认搜索源`)
    await loadAll()
  } catch (error) {
    ElMessage.error((error as Error).message || '设置失败')
  }
}

async function onToggle(row: SearchConfigVO) {
  try {
    await toggleSearchConfig(row.id, !row.enabled)
    ElMessage.success(row.enabled ? '已停用' : '已启用')
    await loadAll()
  } catch (error) {
    ElMessage.error((error as Error).message || '操作失败')
  }
}

async function onDelete(row: SearchConfigVO) {
  try {
    await ElMessageBox.confirm(
      `删除「${row.name}」后，绑定它的场景会自动改回默认搜索源。确定删除吗？`,
      '删除搜索源',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await deleteSearchConfig(row.id)
    ElMessage.success('已删除')
    await loadAll()
  } catch (error) {
    ElMessage.error((error as Error).message || '删除失败')
  }
}

async function saveScenes() {
  scenesSaving.value = true
  try {
    await saveSearchScenes(
      scenes.value.map((item) => ({
        code: item.code,
        enabled: item.enabled,
        configId: item.configId || null,
        topK: typeof item.topK === 'number' ? item.topK : null
      }))
    )
    ElMessage.success('场景绑定已保存')
    await loadAll()
  } catch (error) {
    ElMessage.error((error as Error).message || '保存失败')
  } finally {
    scenesSaving.value = false
  }
}

onMounted(loadAll)
</script>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 14px;
}

.toolbar-right {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 8px;
}

.preview-input {
  width: 220px;
}

.name-cell {
  display: flex;
  align-items: center;
  gap: 6px;
}

.name-text {
  font-weight: 600;
  color: var(--sf-text-primary, #1f2733);
}

.sub-text {
  margin-top: 2px;
  font-size: 11px;
  line-height: 1.5;
  color: var(--sf-text-muted);
}

.provider-name {
  font-size: 13px;
  line-height: 1.5;
  color: var(--sf-text-primary, #1f2733);
}

.provider-meta {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-top: 2px;
}

.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
}

.text-muted {
  color: var(--sf-text-muted);
  font-size: 12px;
}

.ellipsis {
  max-width: 160px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.provider-option {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.form-hint {
  width: 100%;
  margin-top: 2px;
  font-size: 11px;
  line-height: 1.5;
  color: var(--sf-text-muted);
}

.inline-hint {
  margin-left: 10px;
  font-size: 11px;
  color: var(--sf-text-muted);
}

.mb12 {
  margin-bottom: 12px;
}

.mt8 {
  margin-top: 8px;
}

.preview-meta {
  margin: 0 0 10px;
  font-size: 12px;
  color: var(--sf-text-regular);
}

.result-list {
  margin: 12px 0 0;
  padding-left: 20px;
}

.result-list li {
  margin-bottom: 12px;
  font-size: 13px;
  color: var(--sf-text-regular);
}

.result-list a {
  color: var(--sf-brand, #3b6ef6);
  text-decoration: none;
  font-weight: 500;
}

.result-list a:hover {
  text-decoration: underline;
}

.result-host {
  margin-left: 8px;
  font-size: 11px;
  color: var(--sf-text-muted);
}

.result-list p {
  margin: 4px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--sf-text-muted);
}
</style>
