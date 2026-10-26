<template>
  <div class="page-container">
    <el-card shadow="never">
      <!-- 查询条件 -->
      <div class="toolbar">
        <el-input
          v-model="query.keyword"
          placeholder="书名 / 创建账号 / 昵称"
          clearable
          style="width: 230px"
          @keyup.enter="onSearch"
        />
        <el-select v-model="query.state" placeholder="状态" clearable style="width: 120px">
          <el-option label="创作中" value="CREATING" />
          <el-option label="完结" value="COMPLETED" />
          <el-option label="废弃" value="ABANDONED" />
        </el-select>
        <el-select v-model="query.scope" style="width: 130px" @change="onSearch">
          <el-option label="正常数据" value="NORMAL" />
          <el-option label="已删除" value="DELETED" />
          <el-option label="全部" value="ALL" />
        </el-select>
        <el-date-picker
          v-model="dateRange"
          type="daterange"
          unlink-panels
          range-separator="至"
          start-placeholder="创建时间起"
          end-placeholder="创建时间止"
          value-format="YYYY-MM-DD"
          style="width: 250px"
        />
        <el-select v-model="query.sort" style="width: 158px">
          <el-option label="创建时间 新→旧" value="CREATED_DESC" />
          <el-option label="更新时间 新→旧" value="UPDATED_DESC" />
          <el-option label="字数 多→少" value="WORDS_DESC" />
          <el-option label="章节数 多→少" value="CHAPTERS_DESC" />
        </el-select>
        <el-button type="primary" :icon="Search" @click="onSearch">查询</el-button>
        <el-button :icon="Refresh" @click="onReset">重置</el-button>
        <div class="toolbar-right">
          <el-button
            type="danger"
            plain
            :icon="Delete"
            :loading="purging"
            :disabled="!selectedDeleted.length"
            @click="onBatchPurge"
          >
            批量彻底清除<template v-if="selectedDeleted.length">（{{ selectedDeleted.length }}）</template>
          </el-button>
        </div>
      </div>

      <el-alert
        class="mb12"
        type="info"
        :closable="false"
        show-icon
      >
        <template #title>
          超管视角可查看全平台用户的小说、目录与正文；查看类操作不会修改用户创作内容。
          「彻底清除」仅对用户已删除的小说可用，会连同章节、人物、关系、事件、势力、物品、总纲、大纲、
          分析、封面图片与创作助手会话一并物理删除，<b>不可恢复</b>。
        </template>
      </el-alert>

      <!-- 列表 -->
      <el-table
        :key="`novel-table-${query.scope}`"
        v-loading="loading"
        :data="records"
        border
        stripe
        size="default"
        row-key="id"
        @selection-change="onSelectionChange"
      >
        <el-table-column v-if="query.scope !== 'NORMAL'" type="selection" width="46" :selectable="canPurge" />
        <el-table-column label="小说" min-width="230">
          <template #default="{ row }">
            <div class="novel-cell">
              <div class="novel-title-row">
                <span class="novel-title" :title="row.title">{{ row.title }}</span>
                <el-tag v-if="row.deleted === 1" type="danger" size="small" effect="dark">已删除</el-tag>
              </div>
              <div class="novel-meta">
                <el-tag v-if="row.genre" size="small" effect="plain">{{ row.genre }}</el-tag>
                <span v-if="row.penName" class="text-muted">笔名：{{ row.penName }}</span>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="创建用户" min-width="170">
          <template #default="{ row }">
            <div class="user-cell">
              <span class="user-name">{{ row.nickname || row.username || '-' }}</span>
              <span class="text-muted">{{ row.username }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="stateTagType(row.state)" size="small" effect="light">
              {{ stateLabel(row.state) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="chapterCount" label="章节数" width="92" align="right" />
        <el-table-column label="字数" width="110" align="right">
          <template #default="{ row }">{{ formatNumber(row.wordCount) }}</template>
        </el-table-column>
        <el-table-column label="创建时间" width="150">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="最后更新" width="150">
          <template #default="{ row }">{{ formatTime(row.updatedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="168" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openCatalog(row)">查看目录</el-button>
            <el-button
              v-if="row.deleted === 1"
              link
              type="danger"
              size="small"
              @click="onPurge(row)"
            >
              彻底清除
            </el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty :image-size="72" description="没有符合条件的小说" />
        </template>
      </el-table>

      <div class="pagination-bar">
        <el-pagination
          v-model:current-page="pagination.page"
          v-model:page-size="pagination.size"
          :total="total"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next, jumper"
          background
          @current-change="load"
          @size-change="onSizeChange"
        />
      </div>
    </el-card>

    <!-- 目录 + 正文 -->
    <el-drawer v-model="drawer.visible" size="74%" :destroy-on-close="true">
      <template #header>
        <div class="drawer-head">
          <div class="drawer-title">{{ drawer.novel?.title || '小说目录' }}</div>
          <div v-if="drawer.novel" class="drawer-meta">
            <el-tag :type="stateTagType(drawer.novel.state)" size="small" effect="light">
              {{ stateLabel(drawer.novel.state) }}
            </el-tag>
            <el-tag v-if="drawer.novel.deleted === 1" type="danger" size="small" effect="dark">已删除</el-tag>
            <span>{{ drawer.novel.genre || '未设置题材' }}</span>
            <span>作者：{{ drawer.novel.nickname || drawer.novel.username }}</span>
            <span>共 {{ drawer.novel.chapterCount }} 章</span>
            <span>{{ formatNumber(drawer.novel.wordCount) }} 字</span>
          </div>
        </div>
      </template>

      <div v-loading="drawer.loading" class="catalog-layout">
        <aside class="catalog-side">
          <template v-if="volumeGroups.length">
            <div v-for="group in volumeGroups" :key="group.volumeNo" class="volume">
              <div class="volume-head">{{ group.title }}</div>
              <div
                v-for="chapter in group.chapters"
                :key="chapter.id"
                class="catalog-item"
                :class="{ active: chapter.id === activeChapterId }"
                @click="openChapter(chapter)"
              >
                <span class="catalog-no">{{ chapter.chapterNo }}</span>
                <span class="catalog-title" :title="chapter.title">{{ chapter.title }}</span>
                <el-tag v-if="chapter.deleted === 1" type="danger" size="small" effect="plain">已删</el-tag>
                <span class="catalog-words">{{ chapter.wordCount }}</span>
              </div>
            </div>
          </template>
          <el-empty v-else-if="!drawer.loading" :image-size="64" description="这本小说还没有章节" />
        </aside>

        <main v-loading="contentLoading" class="content-pane">
          <template v-if="activeChapter">
            <div class="content-head">
              <div class="content-title">第 {{ activeChapter.chapterNo }} 章　{{ activeChapter.title }}</div>
              <div class="content-meta">
                <el-tag size="small" effect="plain">{{ chapterStatusLabel(activeChapter.status) }}</el-tag>
                <span>{{ formatNumber(activeChapter.wordCount) }} 字（仅汉字）</span>
                <span v-if="activeChapter.updatedAt">更新于 {{ formatTime(activeChapter.updatedAt) }}</span>
              </div>
            </div>
            <div v-if="activeChapter.summary" class="content-summary">
              <span class="summary-label">本章大纲</span>
              <p>{{ activeChapter.summary }}</p>
            </div>
            <div v-if="activeChapter.content" class="content-body">{{ activeChapter.content }}</div>
            <el-empty v-else :image-size="76" description="本章还没有正文" />
          </template>
          <el-empty v-else-if="!drawer.loading" :image-size="76" description="从左侧目录选择一章查看正文" />
        </main>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Delete, Refresh, Search } from '@element-plus/icons-vue'
import {
  getAdminChapter,
  getAdminNovel,
  pageAdminNovels,
  purgeAdminNovel,
  purgeAdminNovels,
  type AdminChapterVO,
  type AdminNovelVO,
  type AdminPurgeResultVO,
  type NovelScope,
  type NovelState
} from '@/api/admin-novel'

const loading = ref(false)
const records = ref<AdminNovelVO[]>([])
const total = ref(0)
const selected = ref<AdminNovelVO[]>([])
const purging = ref(false)

const query = reactive({
  keyword: '',
  state: '' as NovelState | '',
  scope: 'NORMAL' as NovelScope,
  sort: 'CREATED_DESC'
})

/** 只允许勾选已逻辑删除的小说，避免误清正常数据 */
const selectedDeleted = computed(() => selected.value.filter((row) => row.deleted === 1))
function canPurge(row: AdminNovelVO) {
  return row.deleted === 1
}
const dateRange = ref<[string, string] | null>(null)
const pagination = reactive({ page: 1, size: 20 })

const drawer = reactive({
  visible: false,
  loading: false,
  novel: null as AdminNovelVO | null
})
const chapters = ref<AdminChapterVO[]>([])
const activeChapterId = ref('')
const activeChapter = ref<AdminChapterVO | null>(null)
const contentLoading = ref(false)

const volumeGroups = computed(() => {
  const groups: Array<{ volumeNo: number; title: string; chapters: AdminChapterVO[] }> = []
  chapters.value.forEach((chapter) => {
    const volumeNo = chapter.volumeNo ?? 1
    let group = groups.find((item) => item.volumeNo === volumeNo)
    if (!group) {
      group = { volumeNo, title: chapter.volumeTitle || `第 ${volumeNo} 卷`, chapters: [] }
      groups.push(group)
    }
    group.chapters.push(chapter)
  })
  return groups
})

async function load() {
  loading.value = true
  try {
    const result = await pageAdminNovels({
      page: pagination.page,
      size: pagination.size,
      keyword: query.keyword.trim() || undefined,
      state: query.state || undefined,
      scope: query.scope,
      sort: query.sort,
      createdFrom: dateRange.value?.[0],
      createdTo: dateRange.value?.[1]
    })
    records.value = result.records
    total.value = result.total
  } catch (error) {
    ElMessage.error((error as Error).message)
  } finally {
    loading.value = false
  }
}

function onSearch() {
  pagination.page = 1
  load()
}

function onReset() {
  query.keyword = ''
  query.state = ''
  query.scope = 'NORMAL'
  query.sort = 'CREATED_DESC'
  dateRange.value = null
  pagination.page = 1
  load()
}

function onSelectionChange(rows: AdminNovelVO[]) {
  selected.value = rows
}

/* ---------------------- 物理清除（不可恢复） ---------------------- */

/** 弹窗内容走 HTML 换行，插入的动态文本必须转义，避免书名里的尖括号被当成标签 */
function esc(text: string) {
  return String(text).replace(/[&<>"']/g, (char) => {
    const map: Record<string, string> = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }
    return map[char]
  })
}

function purgeSummary(result: AdminPurgeResultVO) {
  const lines = [
    `《${esc(result.title || result.novelId)}》已彻底清除`,
    `共删除 ${result.totalRows} 行数据${result.coversRemoved ? '，封面图片目录已删除' : ''}`
  ]
  const parts = Object.entries(result.tableRows).map(([table, rows]) => `${tableLabel(table)} ${rows}`)
  if (parts.length) {
    lines.push(`明细：${parts.join('、')}`)
  }
  return lines.join('<br/>')
}

function tableLabel(table: string) {
  const labels: Record<string, string> = {
    t_chapter: '章节',
    t_chapter_character: '章节-人物',
    t_chapter_event: '章节-事件',
    t_chapter_analysis: '章节分析',
    t_change_history: '修改记录',
    t_character: '人物',
    t_character_attribute: '人物属性',
    t_character_inventory: '人物物品',
    t_character_relation: '人物关系',
    t_setting_doc: '设定',
    t_timeline_event: '事件',
    t_event_participant: '事件参与人',
    t_faction: '势力',
    t_faction_member: '势力成员',
    t_item: '物品',
    t_master_outline: '总纲',
    t_novel_cover: '封面',
    t_novel_agent_session: '助手会话',
    t_novel_agent_message: '助手消息',
    t_book_digest: '拆书引用',
    t_novel: '小说主表'
  }
  return labels[table] || table
}

async function onPurge(row: AdminNovelVO) {
  try {
    await ElMessageBox.confirm(
      `将彻底删除《${esc(row.title)}》及其全部关联数据：<br/>章节、人物、人物关系、事件、势力、物品、总纲、大纲、`
        + `章节分析、修改记录、封面图片、创作助手会话。<br/><br/><b>此操作不可恢复</b>，请先确认用户确实不再需要这本小说。`,
      '危险操作：彻底清除',
      {
        type: 'warning',
        dangerouslyUseHTMLString: true,
        confirmButtonText: '确认彻底清除',
        cancelButtonText: '取消',
        confirmButtonClass: 'el-button--danger'
      }
    )
  } catch {
    return
  }
  purging.value = true
  try {
    const result = await purgeAdminNovel(row.id)
    await ElMessageBox.alert(purgeSummary(result), '清除完成', {
      type: 'success',
      dangerouslyUseHTMLString: true,
      confirmButtonText: '知道了'
    })
    await load()
  } catch (error) {
    ElMessage.error((error as Error).message)
  } finally {
    purging.value = false
  }
}

async function onBatchPurge() {
  const targets = selectedDeleted.value
  if (!targets.length) {
    ElMessage.warning('请先勾选已删除的小说')
    return
  }
  if (targets.length > 50) {
    ElMessage.warning('单次最多清除 50 本，请分批操作')
    return
  }
  try {
    await ElMessageBox.confirm(
      `将彻底删除选中的 <b>${targets.length}</b> 本小说及其全部关联数据与封面图片。<br/><br/><b>此操作不可恢复。</b>`,
      '危险操作：批量彻底清除',
      {
        type: 'warning',
        dangerouslyUseHTMLString: true,
        confirmButtonText: '确认彻底清除',
        cancelButtonText: '取消',
        confirmButtonClass: 'el-button--danger'
      }
    )
  } catch {
    return
  }
  purging.value = true
  try {
    const results = await purgeAdminNovels(targets.map((row) => row.id))
    const ok = results.filter((item) => item.success)
    const failed = results.filter((item) => !item.success)
    const rows = ok.reduce((sum, item) => sum + item.totalRows, 0)
    ElMessage.success(`清除完成：成功 ${ok.length} 本（共 ${rows} 行），失败 ${failed.length} 本`)
    if (failed.length) {
      ElMessageBox.alert(
        failed
          .map((item) => `《${esc(item.title || item.novelId)}》：${esc(item.message || '清除失败')}`)
          .join('<br/>'),
        '部分小说未能清除',
        { type: 'warning', dangerouslyUseHTMLString: true, confirmButtonText: '知道了' }
      )
    }
    await load()
  } catch (error) {
    ElMessage.error((error as Error).message)
  } finally {
    purging.value = false
  }
}

function onSizeChange() {
  pagination.page = 1
  load()
}

async function openCatalog(row: AdminNovelVO) {
  drawer.visible = true
  drawer.loading = true
  drawer.novel = row
  chapters.value = []
  activeChapter.value = null
  activeChapterId.value = ''
  try {
    const detail = await getAdminNovel(row.id)
    drawer.novel = detail.novel
    chapters.value = detail.chapters ?? []
    if (chapters.value.length) {
      await openChapter(chapters.value[0])
    }
  } catch (error) {
    ElMessage.error((error as Error).message)
  } finally {
    drawer.loading = false
  }
}

async function openChapter(chapter: AdminChapterVO) {
  if (!drawer.novel) {
    return
  }
  activeChapterId.value = chapter.id
  contentLoading.value = true
  try {
    activeChapter.value = await getAdminChapter(drawer.novel.id, chapter.id)
  } catch (error) {
    activeChapter.value = null
    ElMessage.error((error as Error).message)
  } finally {
    contentLoading.value = false
  }
}

function stateLabel(state?: string) {
  if (state === 'COMPLETED') return '完结'
  if (state === 'ABANDONED') return '废弃'
  return '创作中'
}

function stateTagType(state?: string) {
  if (state === 'COMPLETED') return 'success'
  if (state === 'ABANDONED') return 'info'
  return 'warning'
}

function chapterStatusLabel(status?: string) {
  if (status === 'DONE') return '已完成'
  if (status === 'WRITING') return '写作中'
  return '草稿'
}

function formatNumber(value?: number) {
  return (value ?? 0).toLocaleString('zh-CN')
}

function formatTime(value?: string) {
  if (!value) {
    return '-'
  }
  return value.replace('T', ' ').slice(0, 16)
}

onMounted(load)
</script>

<style scoped>
.mb12 {
  margin-bottom: 12px;
}

.novel-cell {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.novel-title {
  min-width: 0;
  font-weight: 600;
  color: var(--sf-text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.novel-title-row {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.novel-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
}

.user-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
  font-size: 13px;
}

.user-name {
  font-weight: 500;
}

.user-cell .text-muted {
  font-size: 12px;
}

/* ---------- 目录抽屉 ---------- */
.drawer-head {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.drawer-title {
  font-size: 16px;
  font-weight: 600;
}

.drawer-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  font-size: 12px;
  color: var(--sf-text-regular);
}

.catalog-layout {
  display: grid;
  grid-template-columns: 268px 1fr;
  gap: 16px;
  height: 100%;
  min-height: 420px;
}

.catalog-side {
  overflow-y: auto;
  padding-right: 4px;
  border-right: 1px solid var(--sf-border);
}

.volume + .volume {
  margin-top: 14px;
}

.volume-head {
  padding: 4px 8px;
  font-size: 12px;
  font-weight: 600;
  color: var(--sf-text-regular);
  background: var(--sf-bg-page);
  border-radius: 4px;
}

.catalog-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 8px;
  border-radius: 4px;
  cursor: pointer;
  font-size: 13px;
  transition: background 0.15s ease;
}

.catalog-item:hover {
  background: var(--sf-bg-tint);
}

.catalog-item.active {
  background: var(--sf-bg-tint-strong);
  color: var(--sf-brand);
  font-weight: 500;
}

.catalog-no {
  flex: none;
  width: 26px;
  text-align: right;
  color: var(--sf-text-muted);
  font-size: 12px;
}

.catalog-item.active .catalog-no {
  color: var(--sf-brand);
}

.catalog-title {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.catalog-words {
  flex: none;
  font-size: 11px;
  color: var(--sf-text-muted);
}

.content-pane {
  overflow-y: auto;
  padding-right: 8px;
  min-height: 360px;
}

.content-head {
  padding-bottom: 10px;
  border-bottom: 1px solid var(--sf-border);
}

.content-title {
  font-size: 17px;
  font-weight: 600;
}

.content-meta {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 6px;
  font-size: 12px;
  color: var(--sf-text-regular);
}

.content-summary {
  margin: 14px 0;
  padding: 10px 12px;
  background: var(--sf-bg-subtle);
  border-left: 3px solid var(--sf-brand);
  border-radius: 4px;
  font-size: 13px;
  line-height: 1.8;
  color: var(--sf-text-regular);
}

.summary-label {
  display: inline-block;
  margin-bottom: 4px;
  font-size: 12px;
  font-weight: 600;
  color: var(--sf-brand);
}

.content-summary p {
  margin: 0;
  white-space: pre-wrap;
}

.content-body {
  margin-top: 14px;
  font-size: 15px;
  line-height: 2;
  letter-spacing: 0.2px;
  color: var(--sf-text-primary);
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
