<template>
  <div class="page-container">
    <el-card shadow="never">
      <el-tabs v-model="activeTab">
        <!-- ==================== 接口端点 ==================== -->
        <el-tab-pane label="接口端点" name="configs">
          <el-alert
            class="mb12"
            type="info"
            :closable="false"
            show-icon
            title="此处配置两类接口：文本接口（OpenAI 协议，负责写作与分析）与图像接口（文生图，负责生成小说封面）。API Key 加密存储，界面只显示掩码；两类接口各自有独立的默认端点。"
          />

          <div class="toolbar">
            <el-button type="primary" :icon="Plus" @click="openCreate">新增接口</el-button>
            <el-button :icon="Refresh" @click="loadConfigs">刷新</el-button>
            <div class="toolbar-right">
              <span class="text-muted">共 {{ configs.length }} 个接口，默认端点：{{ defaultConfigName }}</span>
            </div>
          </div>

          <el-table v-loading="loadingConfigs" :data="configs" border stripe>
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

            <el-table-column label="类型 / 平台" width="180">
              <template #default="{ row }">
                <div class="name-cell">
                  <el-tag :type="row.kind === 'IMAGE' ? 'warning' : 'info'" size="small" effect="plain">
                    {{ row.kind === 'IMAGE' ? '图像' : '文本' }}
                  </el-tag>
                  <span v-if="row.kind === 'IMAGE'" class="sub-text">{{ providerLabel(row.provider) }}</span>
                </div>
              </template>
            </el-table-column>

            <el-table-column label="接口地址" min-width="230" show-overflow-tooltip>
              <template #default="{ row }">
                <span class="mono">{{ row.baseUrl }}</span>
              </template>
            </el-table-column>

            <el-table-column label="默认模型" min-width="170" show-overflow-tooltip>
              <template #default="{ row }">
                <span class="mono">{{ row.defaultModel || '-' }}</span>
              </template>
            </el-table-column>

            <el-table-column label="默认参数" min-width="200">
              <template #default="{ row }">
                <span v-if="row.kind === 'IMAGE'" class="sub-text">
                  尺寸 {{ row.imageSize || '平台默认' }} · 超时 {{ (row.timeoutMs / 1000).toFixed(0) }}s
                </span>
                <span v-else class="sub-text">
                  温度 {{ formatNumber(row.temperature) }} · max_tokens {{ row.maxTokens }} · 超时
                  {{ (row.timeoutMs / 1000).toFixed(0) }}s
                </span>
              </template>
            </el-table-column>

            <el-table-column label="密钥" width="150">
              <template #default="{ row }">
                <span v-if="row.apiKeyConfigured" class="mono">{{ row.apiKeyMask }}</span>
                <el-tag v-else type="danger" size="small" effect="plain">未配置</el-tag>
              </template>
            </el-table-column>

            <el-table-column label="最近测试" width="150">
              <template #default="{ row }">
                <template v-if="row.lastTestAt">
                  <el-tag :type="row.lastTestOk ? 'success' : 'danger'" size="small" effect="plain">
                    {{ row.lastTestOk ? '成功' : '失败' }}
                  </el-tag>
                  <span class="sub-text"> {{ row.lastTestLatencyMs }}ms</span>
                </template>
                <span v-else class="text-muted">未测试</span>
              </template>
            </el-table-column>

            <el-table-column label="操作" width="260" fixed="right">
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
                <el-button
                  link
                  :type="row.enabled ? 'warning' : 'success'"
                  size="small"
                  @click="onToggleEnabled(row)"
                >
                  {{ row.enabled ? '停用' : '启用' }}
                </el-button>
                <el-button link type="danger" size="small" @click="onDelete(row)">删除</el-button>
              </template>
            </el-table-column>

            <template #empty>
              <el-empty description="还没有配置 AI 接口，点击「新增接口」开始" />
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
            title="为不同 AI 功能指定专用端点与参数。留空表示继承默认端点；模型留空则使用端点的默认模型。"
          />

          <div class="toolbar">
            <el-button type="primary" :icon="Check" :loading="savingScenes" @click="onSaveScenes">
              保存绑定
            </el-button>
            <el-button :icon="Refresh" @click="loadScenes">重置</el-button>
            <div class="toolbar-right">
              <span class="text-muted">共 {{ scenes.length }} 个 AI 场景</span>
            </div>
          </div>

          <el-table v-loading="loadingScenes" :data="scenes" border stripe>
            <el-table-column label="AI 场景" min-width="180">
              <template #default="{ row }">
                <div class="name-cell">
                  <span>{{ row.label }}</span>
                  <el-tag v-if="row.kind === 'IMAGE'" type="warning" size="small" effect="plain">图像</el-tag>
                </div>
                <div class="sub-text mono">{{ row.code }}</div>
              </template>
            </el-table-column>

            <el-table-column prop="group" label="分组" width="120" />

            <el-table-column label="接口配置" width="230">
              <template #default="{ row }">
                <el-select
                  v-model="row.aiConfigId"
                  clearable
                  :placeholder="row.kind === 'IMAGE' ? '继承默认图像接口' : '继承默认端点'"
                  style="width: 100%"
                >
                  <el-option
                    v-for="config in configsForScene(row)"
                    :key="config.id"
                    :label="config.enabled ? config.name : `${config.name}（已停用）`"
                    :value="config.id"
                    :disabled="!config.enabled"
                  />
                </el-select>
              </template>
            </el-table-column>

            <el-table-column label="模型" width="210">
              <template #default="{ row }">
                <el-input
                  v-model="row.model"
                  :disabled="!row.aiConfigId"
                  :placeholder="modelPlaceholder(row)"
                  clearable
                />
              </template>
            </el-table-column>

            <el-table-column label="温度" width="130">
              <template #default="{ row }">
                <el-input-number
                  v-model="row.temperature"
                  :min="0"
                  :max="2"
                  :step="0.1"
                  :precision="2"
                  :controls="false"
                  :disabled="!row.aiConfigId || row.kind === 'IMAGE'"
                  placeholder="默认"
                  style="width: 100%"
                />
              </template>
            </el-table-column>

            <el-table-column label="max_tokens" width="140">
              <template #default="{ row }">
                <el-input-number
                  v-model="row.maxTokens"
                  :min="1"
                  :max="200000"
                  :step="256"
                  :controls="false"
                  :disabled="!row.aiConfigId || row.kind === 'IMAGE'"
                  placeholder="默认"
                  style="width: 100%"
                />
              </template>
            </el-table-column>

            <el-table-column label="操作" width="90" fixed="right">
              <template #default="{ row }">
                <el-button link type="danger" size="small" :disabled="!row.aiConfigId" @click="clearScene(row)">
                  清除
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <!-- ==================== 新增 / 编辑 ==================== -->
    <el-dialog
      v-model="dialogVisible"
      :title="dialogMode === 'create' ? '新增 AI 接口' : '编辑 AI 接口'"
      width="640px"
      :close-on-click-modal="false"
    >
      <el-form v-loading="formLoading" ref="formRef" :model="form" :rules="formRules" label-width="110px">
        <el-form-item label="配置名称" prop="name">
          <el-input v-model="form.name" placeholder="如：OpenAI 官方 / 硅基流动生图" maxlength="64" />
        </el-form-item>

        <el-form-item label="接口类型">
          <el-radio-group v-model="form.kind" :disabled="dialogMode === 'edit'" @change="onKindChange">
            <el-radio-button value="TEXT">文本接口（写作 / 分析）</el-radio-button>
            <el-radio-button value="IMAGE">图像接口（文生图）</el-radio-button>
          </el-radio-group>
          <div class="field-tip">
            创建后不可修改：两种接口的调用协议不同，各自有独立的默认端点。
          </div>
        </el-form-item>

        <el-form-item v-if="form.kind === 'IMAGE'" label="平台">
          <el-select v-model="form.provider" style="width: 100%" @change="onProviderChange">
            <el-option v-for="item in presets" :key="item.provider" :label="item.label" :value="item.provider" />
          </el-select>
          <div class="field-tip">{{ currentPreset?.hint || '选择平台后会自动填入地址、推荐模型与尺寸。' }}</div>
        </el-form-item>

        <el-form-item label="接口地址" prop="baseUrl">
          <el-input v-model="form.baseUrl" placeholder="https://api.openai.com/v1" class="mono" />
          <div class="field-tip">
            {{
              form.kind === 'IMAGE'
                ? '需包含版本段；系统会在其后追加各平台的文生图路径（如 /images/generations，百炼为 /services/aigc/text2image/image-synthesis）。'
                : '需包含版本段，系统会在其后追加 /chat/completions 与 /models。'
            }}
          </div>
        </el-form-item>

        <el-form-item label="API Key" prop="apiKey">
          <el-input
            v-model="form.apiKey"
            type="password"
            show-password
            :placeholder="dialogMode === 'edit' ? '留空表示保留原密钥' : 'sk-...'"
          />
          <div class="field-tip">AES-256-GCM 密文入库；点击右侧眼睛可查看明文，清空保存则保留原密钥。</div>
        </el-form-item>

        <el-form-item label="默认模型" prop="defaultModel">
          <div class="model-row">
            <el-select
              v-model="form.defaultModel"
              filterable
              allow-create
              default-first-option
              placeholder="选择或直接输入模型名"
              style="flex: 1"
            >
              <el-option v-for="model in formModelList" :key="model" :label="model" :value="model" />
            </el-select>
            <el-button
              :loading="fetchingModels"
              :disabled="modelListUnsupported"
              :title="modelListUnsupported ? '该平台不提供模型列表接口，请手动填写模型名' : ''"
              @click="onFetchModels"
            >
              拉取模型
            </el-button>
          </div>
        </el-form-item>

        <el-row v-if="form.kind === 'TEXT'" :gutter="12">
          <el-col :span="12">
            <el-form-item label="温度" prop="temperature">
              <el-input-number v-model="form.temperature" :min="0" :max="2" :step="0.1" :precision="2" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="max_tokens" prop="maxTokens">
              <el-input-number v-model="form.maxTokens" :min="1" :max="200000" :step="256" style="width: 100%" />
            </el-form-item>
          </el-col>
        </el-row>

        <el-row :gutter="12">
          <el-col v-if="form.kind === 'TEXT'" :span="12">
            <el-form-item label="top_p" prop="topP">
              <el-input-number v-model="form.topP" :min="0" :max="1" :step="0.05" :precision="2" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col v-else :span="12">
            <el-form-item label="出图尺寸">
              <el-input v-model="form.imageSize" placeholder="1024x1536" class="mono" />
              <div class="field-tip">格式「宽x高」，竖版封面建议 9:16 或 3:4。</div>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="超时(ms)" prop="timeoutMs">
              <el-input-number v-model="form.timeoutMs" :min="1000" :max="600000" :step="1000" style="width: 100%" />
            </el-form-item>
          </el-col>
        </el-row>

        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="启用">
              <el-switch v-model="form.enabled" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item v-if="dialogMode === 'create'" label="设为默认">
              <el-switch v-model="form.isDefault" />
            </el-form-item>
          </el-col>
        </el-row>

        <el-form-item label="附加请求头" prop="extraHeaders">
          <el-input
            v-model="form.extraHeaders"
            type="textarea"
            :rows="2"
            placeholder='选填，JSON 对象，如 {"X-Org-Id":"org-xxx"}'
          />
        </el-form-item>

        <el-form-item label="备注" prop="remark">
          <el-input v-model="form.remark" type="textarea" :rows="2" maxlength="255" show-word-limit />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="onSubmit">保存</el-button>
      </template>
    </el-dialog>

    <!-- ==================== 测试结果 ==================== -->
    <el-dialog v-model="testVisible" title="连通性测试" width="560px">
      <el-result
        :icon="testResult?.ok ? 'success' : 'error'"
        :title="testResult?.ok ? '连接正常' : '连接失败'"
        :sub-title="
          testResult?.ok
            ? testedKind === 'IMAGE'
              ? '接口地址与密钥校验通过'
              : '已成功收到模型响应'
            : testResult?.error || '未知错误'
        "
      >
        <template #extra>
          <el-descriptions :column="1" border size="small">
            <el-descriptions-item label="耗时">{{ testResult?.latencyMs }} ms</el-descriptions-item>
            <el-descriptions-item v-if="testedKind === 'TEXT'" label="模型回显">
              {{ testResult?.model || '-' }}
            </el-descriptions-item>
            <el-descriptions-item v-if="testResult?.ok" :label="testedKind === 'IMAGE' ? '探测结果' : '回复片段'">
              {{ testResult?.reply || '（空）' }}
            </el-descriptions-item>
            <el-descriptions-item v-if="testResult?.totalTokens != null" label="消耗 token">
              {{ testResult?.totalTokens }}
            </el-descriptions-item>
          </el-descriptions>
          <div v-if="testedKind === 'IMAGE'" class="test-tip">
            图像接口没有免费的探测请求：这里只校验地址与密钥（能列出模型即通过）。部分平台（如阿里云百炼）不提供模型列表接口，可直接生成封面验证。
          </div>
        </template>
      </el-result>
      <template #footer>
        <el-button type="primary" @click="testVisible = false">知道了</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormItemRule, type FormRules } from 'element-plus'
import { Check, Plus, Refresh } from '@element-plus/icons-vue'
import {
  changeAiConfigEnabled,
  createAiConfig,
  deleteAiConfig,
  getAiConfig,
  listAiConfigs,
  listImageProviderPresets,
  listSceneBindings,
  probeAiModels,
  saveSceneBindings,
  setDefaultAiConfig,
  testAiConfig,
  updateAiConfig,
  type AiConfigPayload,
  type AiConfigVO,
  type AiTestResult,
  type ImageProviderPresetVO,
  type SceneBindingVO
} from '@/api/ai-config'

const activeTab = ref<'configs' | 'scenes'>('configs')

const configs = ref<AiConfigVO[]>([])
const loadingConfigs = ref(false)
const testingId = ref<string | null>(null)

/** 文生图平台预设：新增图像接口时用来预填地址 / 模型 / 尺寸 */
const presets = ref<ImageProviderPresetVO[]>([])

const scenes = ref<SceneBindingVO[]>([])
const loadingScenes = ref(false)
const savingScenes = ref(false)

const defaultConfigName = computed(() => {
  const text = configs.value.find((item) => item.isDefault && item.kind !== 'IMAGE')
  const image = configs.value.find((item) => item.isDefault && item.kind === 'IMAGE')
  const parts: string[] = []
  if (text) {
    parts.push(`文本 ${text.name}`)
  }
  if (image) {
    parts.push(`图像 ${image.name}`)
  }
  return parts.length ? parts.join(' · ') : '未设置'
})

function providerLabel(provider?: string) {
  return presets.value.find((item) => item.provider === provider)?.label ?? provider ?? 'OpenAI 兼容'
}

/** 场景绑定的可选项按接口类型过滤：封面场景只列图像接口 */
function configsForScene(row: SceneBindingVO) {
  return configs.value.filter((item) => (row.kind === 'IMAGE' ? item.kind === 'IMAGE' : item.kind !== 'IMAGE'))
}

/* ---------------- 列表 ---------------- */

async function loadConfigs() {
  loadingConfigs.value = true
  try {
    configs.value = await listAiConfigs()
  } catch (error) {
    ElMessage.error((error as Error).message || '加载接口配置失败')
  } finally {
    loadingConfigs.value = false
  }
}

async function loadPresets() {
  try {
    presets.value = await listImageProviderPresets()
  } catch {
    // 预设只用于预填表单，失败不影响主流程
    presets.value = []
  }
}

async function loadScenes() {
  loadingScenes.value = true
  try {
    const [sceneList, configList] = await Promise.all([listSceneBindings(), listAiConfigs()])
    scenes.value = sceneList.map((item) => ({ ...item }))
    configs.value = configList
  } catch (error) {
    ElMessage.error((error as Error).message || '加载场景绑定失败')
  } finally {
    loadingScenes.value = false
  }
}

function formatNumber(value?: number) {
  return value == null ? '-' : value.toFixed(2)
}

function modelPlaceholder(row: SceneBindingVO) {
  const config = configs.value.find((item) => item.id === row.aiConfigId)
  return config ? `默认 ${config.defaultModel}` : '沿用端点默认模型'
}

/* ---------------- 新增 / 编辑 ---------------- */

const dialogVisible = ref(false)
const dialogMode = ref<'create' | 'edit'>('create')
const editingId = ref<string | null>(null)
const currentApiKeyMask = ref('')
const originalApiKey = ref('')
const formLoading = ref(false)
const submitting = ref(false)
const fetchingModels = ref(false)
const formRef = ref<FormInstance>()
const formModelList = ref<string[]>([])

const form = reactive({
  name: '',
  kind: 'TEXT' as 'TEXT' | 'IMAGE',
  provider: 'OPENAI',
  imageSize: '',
  baseUrl: '',
  apiKey: '',
  defaultModel: '',
  timeoutMs: 60000,
  temperature: 0.8,
  maxTokens: 4096,
  topP: 1,
  extraHeaders: '',
  enabled: true,
  isDefault: false,
  remark: ''
})

/** 当前选中的图像平台预设 */
const currentPreset = computed(() => presets.value.find((item) => item.provider === form.provider))

/** 平台不提供 /models 时禁用「拉取模型」 */
const modelListUnsupported = computed(
  () => form.kind === 'IMAGE' && currentPreset.value != null && !currentPreset.value.supportsModelList
)

/** 切换类型：图像接口预填平台推荐值，文本接口回到 OpenAI 通用地址 */
function onKindChange(value: string | number | boolean) {
  form.defaultModel = ''
  formModelList.value = []
  if (value === 'IMAGE') {
    form.provider = 'OPENAI'
    form.timeoutMs = 180000
    applyPreset()
    return
  }
  form.provider = 'OPENAI'
  form.imageSize = ''
  form.baseUrl = 'https://api.openai.com/v1'
  form.timeoutMs = 60000
}

/** 切换平台：地址 / 推荐模型 / 尺寸一并覆盖，避免留下上一个平台的值 */
function onProviderChange() {
  form.defaultModel = ''
  formModelList.value = []
  applyPreset()
}

function applyPreset() {
  const preset = currentPreset.value
  if (!preset) {
    return
  }
  form.baseUrl = preset.baseUrl
  form.defaultModel = preset.model
  form.imageSize = preset.imageSize
}

const formRules: FormRules = {
  name: [
    { required: true, message: '请填写配置名称', trigger: 'blur' },
    { max: 64, message: '最长 64 个字符', trigger: 'blur' }
  ],
  baseUrl: [
    { required: true, message: '请填写接口地址', trigger: 'blur' },
    { pattern: /^https?:\/\/.+/, message: '必须以 http:// 或 https:// 开头', trigger: 'blur' }
  ],
  apiKey: [
    {
      validator: (_rule: unknown, value: unknown, callback: (error?: Error) => void) => {
        if (dialogMode.value === 'edit') {
          callback()
          return
        }
        if (!value || String(value).trim() === '') {
          callback(new Error('请填写 API Key'))
          return
        }
        callback()
      },
      trigger: 'blur'
    } as FormItemRule
  ],
  defaultModel: [{ required: true, message: '请选择或输入模型', trigger: 'change' }]
}

function resetForm() {
  form.name = ''
  form.kind = 'TEXT'
  form.provider = 'OPENAI'
  form.imageSize = ''
  form.baseUrl = 'https://api.openai.com/v1'
  form.apiKey = ''
  form.defaultModel = ''
  form.timeoutMs = 60000
  form.temperature = 0.8
  form.maxTokens = 4096
  form.topP = 1
  form.extraHeaders = ''
  form.enabled = true
  form.isDefault = false
  form.remark = ''
  formModelList.value = []
  currentApiKeyMask.value = ''
  originalApiKey.value = ''
}

function openCreate() {
  dialogMode.value = 'create'
  editingId.value = null
  resetForm()
  dialogVisible.value = true
}

function openEdit(row: AiConfigVO) {
  dialogMode.value = 'edit'
  editingId.value = row.id
  resetForm()
  fillFromRow(row)
  dialogVisible.value = true
  void loadApiKey(row.id)
}

function fillFromRow(row: AiConfigVO) {
  form.name = row.name
  form.kind = row.kind === 'IMAGE' ? 'IMAGE' : 'TEXT'
  form.provider = row.provider || 'OPENAI'
  form.imageSize = row.imageSize ?? ''
  form.baseUrl = row.baseUrl
  form.defaultModel = row.defaultModel
  form.timeoutMs = row.timeoutMs
  form.temperature = row.temperature
  form.maxTokens = row.maxTokens
  form.topP = row.topP
  form.extraHeaders = row.extraHeaders ?? ''
  form.enabled = row.enabled
  form.remark = row.remark ?? ''
  formModelList.value = row.modelList ?? []
  currentApiKeyMask.value = row.apiKeyMask ?? ''
}

/** 详情接口按需解密，把明文密钥回填到密码框（可点眼睛查看）。 */
async function loadApiKey(id: string) {
  formLoading.value = true
  try {
    const detail = await getAiConfig(id)
    form.apiKey = detail.apiKey ?? ''
    originalApiKey.value = form.apiKey
    currentApiKeyMask.value = detail.apiKeyMask ?? currentApiKeyMask.value
    if (detail.modelList?.length) {
      formModelList.value = detail.modelList
    }
  } catch {
    // 主密钥更换等异常：保持空值，留空保存即沿用原密钥
    ElMessage.warning('未能读取原密钥，留空保存将沿用原密钥')
  } finally {
    formLoading.value = false
  }
}

async function onSubmit() {
  if (!formRef.value) {
    return
  }
  if (formLoading.value) {
    ElMessage.warning('正在读取原密钥，请稍候')
    return
  }
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) {
    return
  }
  if (form.extraHeaders && form.extraHeaders.trim() !== '') {
    try {
      const parsed = JSON.parse(form.extraHeaders)
      if (typeof parsed !== 'object' || Array.isArray(parsed) || parsed === null) {
        throw new Error('not object')
      }
    } catch {
      ElMessage.error('附加请求头必须是合法的 JSON 对象')
      return
    }
  }
  if (form.kind === 'IMAGE' && form.imageSize.trim() && !/^\d{3,4}x\d{3,4}$/.test(form.imageSize.trim())) {
    ElMessage.error('出图尺寸格式应为「宽x高」，如 1024x1536')
    return
  }

  submitting.value = true
  try {
    if (dialogMode.value === 'create') {
      await createAiConfig({
        name: form.name.trim(),
        kind: form.kind,
        provider: form.kind === 'IMAGE' ? form.provider : 'OPENAI',
        imageSize: form.kind === 'IMAGE' ? form.imageSize.trim() || undefined : undefined,
        baseUrl: form.baseUrl.trim(),
        apiKey: form.apiKey.trim(),
        defaultModel: form.defaultModel.trim(),
        timeoutMs: form.timeoutMs,
        temperature: form.temperature,
        maxTokens: form.maxTokens,
        topP: form.topP,
        extraHeaders: form.extraHeaders.trim() || undefined,
        enabled: form.enabled,
        isDefault: form.isDefault,
        remark: form.remark || undefined
      })
      ElMessage.success('接口已创建')
    } else if (editingId.value != null) {
      const patch: AiConfigPayload = {
        name: form.name.trim(),
        baseUrl: form.baseUrl.trim(),
        defaultModel: form.defaultModel.trim(),
        timeoutMs: form.timeoutMs,
        temperature: form.temperature,
        maxTokens: form.maxTokens,
        topP: form.topP,
        extraHeaders: form.extraHeaders,
        enabled: form.enabled,
        remark: form.remark
      }
      if (form.kind === 'IMAGE') {
        patch.provider = form.provider
        patch.imageSize = form.imageSize.trim()
      }
      const apiKey = form.apiKey.trim()
      if (apiKey && apiKey !== originalApiKey.value) {
        patch.apiKey = apiKey
      }
      await updateAiConfig(editingId.value, patch)
      ElMessage.success('接口已保存')
    }
    dialogVisible.value = false
    await loadConfigs()
  } catch (error) {
    ElMessage.error((error as Error).message || '保存失败')
  } finally {
    submitting.value = false
  }
}

async function onFetchModels() {
  if (!form.baseUrl.trim()) {
    ElMessage.warning('请先填写接口地址')
    return
  }
  if (!form.apiKey.trim() && editingId.value == null) {
    ElMessage.warning('请先填写 API Key')
    return
  }
  fetchingModels.value = true
  try {
    const models = await probeAiModels({
      id: editingId.value ?? undefined,
      kind: form.kind,
      provider: form.kind === 'IMAGE' ? form.provider : undefined,
      baseUrl: form.baseUrl.trim(),
      apiKey: form.apiKey.trim() || undefined,
      extraHeaders: form.extraHeaders.trim() || undefined,
      timeoutMs: form.timeoutMs
    })
    formModelList.value = models
    ElMessage.success(
      dialogMode.value === 'create'
        ? `已拉取 ${models.length} 个模型，保存后可下拉选择`
        : `已拉取 ${models.length} 个模型`
    )
  } catch (error) {
    ElMessage.error((error as Error).message || '拉取模型失败')
  } finally {
    fetchingModels.value = false
  }
}

/* ---------------- 行操作 ---------------- */

const testVisible = ref(false)
const testResult = ref<AiTestResult | null>(null)
/** 被测接口的类型：图像接口的测试结果文案不同 */
const testedKind = ref<'TEXT' | 'IMAGE'>('TEXT')

async function onTest(row: AiConfigVO) {
  testingId.value = row.id
  testedKind.value = row.kind === 'IMAGE' ? 'IMAGE' : 'TEXT'
  try {
    testResult.value = await testAiConfig(row.id)
    testVisible.value = true
    await loadConfigs()
  } catch (error) {
    testResult.value = { ok: false, latencyMs: 0, error: (error as Error).message }
    testVisible.value = true
  } finally {
    testingId.value = null
  }
}

async function onSetDefault(row: AiConfigVO) {
  try {
    await setDefaultAiConfig(row.id)
    ElMessage.success(`已将「${row.name}」设为默认端点`)
    await loadConfigs()
  } catch (error) {
    ElMessage.error((error as Error).message || '设置失败')
  }
}

async function onToggleEnabled(row: AiConfigVO) {
  const target = !row.enabled
  const text = target ? '启用' : '停用'
  await ElMessageBox.confirm(
    target
      ? `确认启用「${row.name}」？`
      : `确认停用「${row.name}」？停用后该接口不可再被生成流程选用${row.isDefault ? '，并会取消其默认标记' : ''}。`,
    `${text}接口`,
    { type: 'warning' }
  )
  try {
    await changeAiConfigEnabled(row.id, target)
    ElMessage.success(`${text}成功`)
    await loadConfigs()
  } catch (error) {
    ElMessage.error((error as Error).message || `${text}失败`)
  }
}

async function onDelete(row: AiConfigVO) {
  await ElMessageBox.confirm(
    `确认删除「${row.name}」？被场景绑定的接口需要先解除绑定。`,
    '删除接口',
    { type: 'warning', confirmButtonText: '删除', confirmButtonClass: 'el-button--danger' }
  )
  try {
    await deleteAiConfig(row.id)
    ElMessage.success('已删除')
    await loadConfigs()
  } catch (error) {
    ElMessage.error((error as Error).message || '删除失败')
  }
}

/* ---------------- 场景绑定 ---------------- */

function clearScene(row: SceneBindingVO) {
  row.aiConfigId = undefined
  row.model = undefined
  row.temperature = undefined
  row.maxTokens = undefined
}

async function onSaveScenes() {
  savingScenes.value = true
  try {
    await saveSceneBindings(
      scenes.value.map((row) => ({
        code: row.code,
        aiConfigId: row.aiConfigId || null,
        model: row.model?.trim() || null,
        temperature: row.temperature ?? null,
        maxTokens: row.maxTokens ?? null
      }))
    )
    ElMessage.success('场景绑定已保存')
    await loadScenes()
  } catch (error) {
    ElMessage.error((error as Error).message || '保存失败')
  } finally {
    savingScenes.value = false
  }
}

onMounted(async () => {
  await loadPresets()
  await loadConfigs()
  await loadScenes()
})
</script>

<style scoped>
.mb12 {
  margin-bottom: 12px;
}

.mono {
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  font-size: 12px;
}

.name-cell {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.name-text {
  font-weight: 600;
}

.sub-text {
  font-size: 12px;
  color: var(--sf-text-regular);
  line-height: 1.6;
}

.field-tip {
  font-size: 12px;
  color: var(--sf-text-regular);
  line-height: 1.6;
}

.model-row {
  display: flex;
  gap: 8px;
  width: 100%;
}

.test-tip {
  margin-top: 10px;
  font-size: 12px;
  line-height: 1.7;
  color: var(--sf-text-regular);
}

:deep(.el-result) {
  padding: 12px 0 0;
}
</style>
