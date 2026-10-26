<template>
  <div v-loading="loading" class="page-container dashboard-page">
    <!-- 欢迎条 -->
    <section class="hero">
      <div class="hero-text">
        <p class="hero-eyebrow">{{ greeting }} · {{ todayLabel }}</p>
        <h1 class="hero-title">{{ d.profile.nickname || d.profile.username }}，{{ greetingTail }}</h1>
        <p class="hero-sub">
          <span class="hero-chip">上次登录：{{ lastLoginText }}</span>
          <span v-if="d.profile.prevLoginIp" class="hero-chip">IP {{ d.profile.prevLoginIp }}</span>
          <span class="hero-chip">已使用 {{ d.profile.usedDays }} 天</span>
        </p>
      </div>
      <div class="hero-actions">
        <el-button type="primary" :icon="Notebook" @click="router.push('/bookshelf')">进入书架</el-button>
        <el-button plain :icon="MagicStick" @click="router.push('/inspiration')">灵感模式</el-button>
      </div>
    </section>

    <!-- 指标卡 -->
    <section class="metric-grid">
      <article class="metric">
        <header class="metric-head">
          <span class="metric-label">小说总数</span>
          <el-icon class="metric-icon"><Notebook /></el-icon>
        </header>
        <div class="metric-value">{{ fmt(d.novel.total) }}<span class="metric-unit">本</span></div>
        <p class="metric-sub">
          <span class="pet pet--writing">连载 {{ d.novel.writing }}</span>
          <span class="pet pet--done">完结 {{ d.novel.finished }}</span>
        </p>
        <p class="metric-foot">草稿 {{ d.novel.draft }} 本 · 归档 {{ d.novel.archived }} 本</p>
      </article>

      <article class="metric">
        <header class="metric-head">
          <span class="metric-label">累计字数</span>
          <el-icon class="metric-icon"><EditPen /></el-icon>
        </header>
        <div class="metric-value">{{ fmt(d.novel.wordCount) }}<span class="metric-unit">字</span></div>
        <p class="metric-sub">
          <span class="pet pet--soft">今日更新 {{ fmt(todayWords) }} 字</span>
        </p>
        <p class="metric-foot">平均每章 {{ fmt(d.novel.avgWordsPerChapter) }} 字</p>
      </article>

      <article class="metric">
        <header class="metric-head">
          <span class="metric-label">章节数</span>
          <el-icon class="metric-icon"><Files /></el-icon>
        </header>
        <div class="metric-value">{{ fmt(d.chapter.total) }}<span class="metric-unit">章</span></div>
        <p class="metric-sub">
          <span class="pet pet--done">已完成 {{ d.chapter.done }}</span>
          <span class="pet pet--soft">写作中 {{ d.chapter.writing }}</span>
        </p>
        <p class="metric-foot">草稿 {{ d.chapter.draft }} 章 · 完成度 {{ finishPercent }}%</p>
      </article>

      <article class="metric">
        <header class="metric-head">
          <span class="metric-label">今日 Token</span>
          <el-icon class="metric-icon"><DataLine /></el-icon>
        </header>
        <div class="metric-value">{{ fmt(d.ai.todayTokens) }}</div>
        <p class="metric-sub">
          <span class="pet pet--soft">调用 {{ fmt(d.ai.todayCalls) }} 次</span>
          <span v-if="d.ai.todayFailed" class="pet pet--warn">失败 {{ d.ai.todayFailed }} 次</span>
        </p>
        <p class="metric-foot">本月 {{ fmt(d.ai.monthTokens) }} · 累计 {{ fmt(d.ai.totalTokens) }}</p>
      </article>
    </section>

    <el-row :gutter="16">
      <!-- 趋势 -->
      <el-col :xs="24" :lg="16">
        <el-card shadow="never" class="panel">
          <template #header>
            <div class="panel-head">
              <div>
                <span class="panel-title">近 14 天趋势</span>
                <span class="panel-desc">{{ trendMetric === 'words' ? '按章节最后编辑日汇总' : '按 AI 调用日期汇总' }}</span>
              </div>
              <el-radio-group v-model="trendMetric" size="small">
                <el-radio-button value="words">字数</el-radio-button>
                <el-radio-button value="tokens">Token</el-radio-button>
              </el-radio-group>
            </div>
          </template>

          <div class="trend-summary">
            <div class="trend-stat">
              <span class="trend-stat-label">14 天合计</span>
              <span class="trend-stat-value">{{ fmt(chartTotal) }}</span>
            </div>
            <div class="trend-stat">
              <span class="trend-stat-label">有记录的天数</span>
              <span class="trend-stat-value">{{ activeDays }}<span class="trend-stat-unit">天</span></span>
            </div>
            <div class="trend-stat">
              <span class="trend-stat-label">日均</span>
              <span class="trend-stat-value">{{ fmt(Math.round(chartTotal / 14)) }}</span>
            </div>
          </div>

          <div class="chart" :class="{ 'is-empty': !chartTotal }">
            <p v-if="!chartTotal" class="chart-empty">近 14 天还没有记录，写点东西就会出现在这里</p>
            <div v-for="point in chart" :key="point.label" class="chart-col">
              <el-tooltip placement="top" :show-after="80">
                <template #content>
                  <div class="tip-title">{{ point.label }}</div>
                  <div class="tip-line">字数 {{ fmt(point.words) }} · 章节 {{ point.chapters }}</div>
                  <div class="tip-line">Token {{ fmt(point.tokens) }} · 调用 {{ point.calls }}</div>
                </template>
                <div class="chart-bar-wrap">
                  <span class="chart-value">{{ point.value ? shortNum(point.value) : '' }}</span>
                  <div class="chart-bar" :class="{ 'is-zero': !point.value }" :style="{ height: point.height + '%' }" />
                </div>
              </el-tooltip>
              <span class="chart-label">{{ point.label }}</span>
            </div>
          </div>
        </el-card>
      </el-col>

      <!-- 场景用量 -->
      <el-col :xs="24" :lg="8">
        <el-card shadow="never" class="panel scene-panel">
          <template #header>
            <div class="panel-head">
              <div>
                <span class="panel-title">AI 消耗分布</span>
                <span class="panel-desc">近 30 天 · 按场景</span>
              </div>
              <el-tooltip content="Token 数与模型服务商返回的 usage 一致；未返回时按调用次数占比" placement="top">
                <el-icon class="panel-hint"><InfoFilled /></el-icon>
              </el-tooltip>
            </div>
          </template>

          <div v-if="d.scenes.length" class="scene-list">
            <div v-for="scene in sceneRows" :key="scene.scene" class="scene-row">
              <div class="scene-row-head">
                <span class="scene-name">{{ scene.label }}</span>
                <span class="scene-num">{{ scene.tokens ? fmt(scene.tokens) + ' token' : fmt(scene.calls) + ' 次' }}</span>
              </div>
              <div class="scene-track">
                <div class="scene-fill" :style="{ width: Math.max(scene.percent, 2) + '%' }" />
              </div>
              <div class="scene-row-foot">
                <span>{{ scene.group }}</span>
                <span>{{ scene.percent }}% · {{ scene.calls }} 次</span>
              </div>
            </div>
          </div>
          <div v-else class="panel-empty">
            <p class="empty-title">还没有 AI 调用记录</p>
            <p class="empty-desc">生成一次大纲或正文后，这里会按场景统计消耗。</p>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="16">
      <!-- 最近编辑 -->
      <el-col :xs="24" :lg="16">
        <el-card shadow="never" class="panel">
          <template #header>
            <div class="panel-head">
              <span class="panel-title">最近编辑</span>
              <el-button text size="small" @click="router.push('/bookshelf')">全部作品</el-button>
            </div>
          </template>

          <div v-if="d.recentNovels.length" class="recent-list">
            <div
              v-for="book in d.recentNovels"
              :key="book.id"
              class="recent-row"
              @click="router.push(`/novels/${book.id}`)"
            >
              <div class="recent-cover">{{ book.title.slice(0, 1) }}</div>
              <div class="recent-main">
                <div class="recent-line">
                  <span class="recent-title">{{ book.title }}</span>
                  <el-tag size="small" effect="plain" :type="statusType(book.status)">{{ book.statusLabel }}</el-tag>
                  <span v-if="book.genre" class="recent-genre">{{ book.genre }}</span>
                </div>
                <p class="recent-meta">
                  {{ book.chapterCount }} 章 · {{ fmt(book.wordCount) }} 字 · 更新于 {{ relativeTime(book.updatedAt) }}
                </p>
              </div>
              <el-icon class="recent-arrow"><ArrowRight /></el-icon>
            </div>
          </div>

          <div v-else class="panel-empty">
            <p class="empty-title">书架还是空的</p>
            <p class="empty-desc">写下一句核心创意，AI 会补齐世界观、背景与人物档案。</p>
            <el-button type="primary" size="small" @click="router.push('/bookshelf')">创建第一本小说</el-button>
          </div>
        </el-card>
      </el-col>

      <!-- 账号信息 + 快捷入口 -->
      <el-col :xs="24" :lg="8">
        <el-card shadow="never" class="panel">
          <template #header>
            <span class="panel-title">账号与用量</span>
          </template>

          <dl class="account-list">
            <div class="account-item">
              <dt>登录名</dt>
              <dd>{{ d.profile.username }}</dd>
            </div>
            <div class="account-item">
              <dt>角色</dt>
              <dd>{{ d.profile.roleLabel }}</dd>
            </div>
            <div class="account-item">
              <dt>注册时间</dt>
              <dd>{{ formatDate(d.profile.registeredAt) }}</dd>
            </div>
            <div class="account-item">
              <dt>本次登录</dt>
              <dd>{{ formatDateTime(d.profile.lastLoginAt) }}</dd>
            </div>
            <div class="account-item">
              <dt>上次登录</dt>
              <dd>{{ formatDateTime(d.profile.prevLoginAt) }}</dd>
            </div>
            <div class="account-item">
              <dt>今日输入 / 输出</dt>
              <dd>{{ fmt(d.ai.todayPromptTokens) }} / {{ fmt(d.ai.todayCompletionTokens) }}</dd>
            </div>
            <div class="account-item">
              <dt>累计调用</dt>
              <dd>{{ fmt(d.ai.totalCalls) }} 次</dd>
            </div>
            <div class="account-item">
              <dt>最近调用</dt>
              <dd>{{ relativeTime(d.ai.lastCalledAt) }}</dd>
            </div>
          </dl>

          <div class="quick">
            <el-button size="small" @click="router.push('/settings/prompts')">提示词模板</el-button>
            <el-button size="small" @click="router.push('/settings/ai')">AI 接口</el-button>
            <el-button size="small" @click="router.push('/change-password')">修改口令</el-button>
            <el-button v-if="auth.isSuperAdmin" size="small" @click="router.push('/admin/users')">用户管理</el-button>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  ArrowRight,
  DataLine,
  EditPen,
  Files,
  InfoFilled,
  MagicStick,
  Notebook
} from '@element-plus/icons-vue'
import { getDashboardOverview, type DashboardOverviewVO } from '@/api/dashboard'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const auth = useAuthStore()

const loading = ref(false)
const data = ref<DashboardOverviewVO | null>(null)
const trendMetric = ref<'words' | 'tokens'>('words')

/** 首屏未加载时的空壳，避免模板里满屏可选链 */
const EMPTY: DashboardOverviewVO = {
  profile: { username: '', roleLabel: '', usedDays: 0 },
  novel: { total: 0, draft: 0, writing: 0, finished: 0, archived: 0, wordCount: 0, chapterCount: 0, avgWordsPerChapter: 0 },
  chapter: { total: 0, done: 0, writing: 0, draft: 0 },
  ai: {
    todayTokens: 0,
    todayCalls: 0,
    todayPromptTokens: 0,
    todayCompletionTokens: 0,
    todayFailed: 0,
    monthTokens: 0,
    monthCalls: 0,
    totalTokens: 0,
    totalCalls: 0
  },
  trend: [],
  scenes: [],
  recentNovels: []
}

const d = computed(() => data.value ?? EMPTY)

/* ---------------- 顶部问候 ---------------- */

const hour = new Date().getHours()
const greeting = hour < 6 ? '凌晨好' : hour < 12 ? '早上好' : hour < 14 ? '中午好' : hour < 18 ? '下午好' : '晚上好'
const greetingTail = hour < 6 ? '夜深了，注意休息' : hour < 12 ? '今天写点什么' : hour < 18 ? '继续推进你的故事' : '今天也辛苦了'

const weekdays = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']
const now = new Date()
const todayLabel = `${now.getFullYear()} 年 ${now.getMonth() + 1} 月 ${now.getDate()} 日 ${weekdays[now.getDay()]}`

/* ---------------- 派生数据 ---------------- */

const todayWords = computed(() => {
  const trend = d.value.trend
  return trend.length ? trend[trend.length - 1].words : 0
})

const finishPercent = computed(() => {
  const total = d.value.chapter.total
  return total ? Math.round((d.value.chapter.done * 100) / total) : 0
})

/** 图表列：高度按当前指标归一化，0 值也留一条底边 */
const chart = computed(() => {
  const points = d.value.trend
  const values = points.map((point) => (trendMetric.value === 'words' ? point.words : point.tokens))
  const max = Math.max(1, ...values)
  return points.map((point, index) => ({
    label: point.label,
    words: point.words,
    tokens: point.tokens,
    calls: point.calls,
    chapters: point.chapters,
    value: values[index],
    height: values[index] > 0 ? Math.max(4, Math.round((values[index] * 100) / max)) : 2
  }))
})

const chartTotal = computed(() => chart.value.reduce((sum, point) => sum + point.value, 0))
const activeDays = computed(() => chart.value.filter((point) => point.value > 0).length)

/** 场景列表：超过 6 个时把尾部合并成一行，避免卡片被撑高或被裁切 */
const sceneRows = computed(() => {
  const rows = d.value.scenes
  if (rows.length <= 6) {
    return rows
  }
  const top = rows.slice(0, 5)
  const rest = rows.slice(5)
  return [
    ...top,
    {
      scene: '__other__',
      label: `其他 ${rest.length} 个场景`,
      group: '合并显示',
      calls: rest.reduce((sum, row) => sum + row.calls, 0),
      tokens: rest.reduce((sum, row) => sum + row.tokens, 0),
      percent: Math.round(rest.reduce((sum, row) => sum + row.percent, 0) * 10) / 10
    }
  ]
})

const lastLoginText = computed(() => {
  const prev = d.value.profile.prevLoginAt
  return prev ? relativeTime(prev) : '本次为首次登录'
})

/* ---------------- 格式化 ---------------- */

function fmt(value?: number | null): string {
  return (value ?? 0).toLocaleString('zh-CN')
}

/** 柱子上方的小字，超过一万折算成「万」 */
function shortNum(value: number): string {
  if (value >= 10000) {
    return (value / 10000).toFixed(1).replace(/\.0$/, '') + '万'
  }
  return String(value)
}

function parseDate(value?: string): Date | null {
  if (!value) {
    return null
  }
  const parsed = new Date(value.replace(' ', 'T'))
  return Number.isNaN(parsed.getTime()) ? null : parsed
}

function pad(value: number): string {
  return value < 10 ? `0${value}` : String(value)
}

function formatDate(value?: string): string {
  const date = parseDate(value)
  return date ? `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}` : '—'
}

function formatDateTime(value?: string): string {
  const date = parseDate(value)
  if (!date) {
    return '—'
  }
  return `${formatDate(value)} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}

/** 「今天 14:20」「昨天 09:05」「10-19 20:03」 */
function relativeTime(value?: string): string {
  const date = parseDate(value)
  if (!date) {
    return '还没有记录'
  }
  const today = new Date()
  const startOfToday = new Date(today.getFullYear(), today.getMonth(), today.getDate()).getTime()
  const diffDays = Math.floor((startOfToday - new Date(date.getFullYear(), date.getMonth(), date.getDate()).getTime()) / 86400000)
  const time = `${pad(date.getHours())}:${pad(date.getMinutes())}`
  if (diffDays === 0) {
    return `今天 ${time}`
  }
  if (diffDays === 1) {
    return `昨天 ${time}`
  }
  if (diffDays < 7 && diffDays > 0) {
    return `${diffDays} 天前 ${time}`
  }
  return `${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${time}`
}

function statusType(status: string): 'success' | 'warning' | 'info' | 'primary' {
  switch (status) {
    case 'WRITING':
      return 'primary'
    case 'FINISHED':
      return 'success'
    case 'ARCHIVED':
      return 'info'
    default:
      return 'warning'
  }
}

/* ---------------- 加载 ---------------- */

async function load() {
  loading.value = true
  try {
    data.value = await getDashboardOverview()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '仪表盘加载失败')
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.dashboard-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

/* ---------- 欢迎条 ---------- */
.hero {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  align-items: center;
  justify-content: space-between;
  padding: 20px 24px;
  border-radius: 10px;
  border: 1px solid var(--sf-border);
  background: linear-gradient(120deg, var(--sf-bg-tint-strong), var(--sf-bg-card) 70%);
}

.hero-eyebrow {
  margin: 0 0 6px;
  font-size: 12px;
  letter-spacing: 0.08em;
  color: var(--sf-text-muted);
}

.hero-title {
  margin: 0 0 8px;
  font-size: 22px;
  font-weight: 600;
  color: var(--sf-text-primary);
}

.hero-sub {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin: 0;
}

.hero-chip {
  padding: 2px 10px;
  border-radius: 20px;
  font-size: 12px;
  color: var(--sf-text-regular);
  background: var(--sf-bg-card);
  border: 1px solid var(--sf-border);
}

.hero-actions {
  display: flex;
  gap: 8px;
}

/* ---------- 指标卡 ---------- */
.metric-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(210px, 1fr));
  gap: 12px;
}

.metric {
  padding: 16px 18px;
  border-radius: 10px;
  border: 1px solid var(--sf-border);
  background: var(--sf-bg-card);
  box-shadow: 0 1px 4px var(--sf-shadow);
}

.metric-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.metric-label {
  font-size: 13px;
  color: var(--sf-text-regular);
}

.metric-icon {
  color: var(--sf-brand);
  font-size: 16px;
}

.metric-value {
  margin: 10px 0 8px;
  font-size: 28px;
  font-weight: 600;
  line-height: 1.1;
  color: var(--sf-text-primary);
  font-variant-numeric: tabular-nums;
}

.metric-unit {
  margin-left: 4px;
  font-size: 13px;
  font-weight: 400;
  color: var(--sf-text-muted);
}

.metric-sub {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin: 0 0 6px;
}

.pet {
  padding: 1px 8px;
  border-radius: 4px;
  font-size: 12px;
  line-height: 18px;
}

.pet--writing {
  color: var(--sf-brand);
  background: var(--sf-brand-weak);
}

.pet--done {
  color: var(--sf-text-success);
  background: var(--sf-bg-success);
}

.pet--warn {
  color: var(--sf-text-danger);
  background: var(--sf-bg-danger);
}

.pet--soft {
  color: var(--sf-text-regular);
  background: var(--sf-bg-subtle);
}

.metric-foot {
  margin: 0;
  font-size: 12px;
  color: var(--sf-text-muted);
}

/* ---------- 面板 ---------- */
.panel {
  height: 100%;
}

.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.panel-title {
  font-weight: 600;
  color: var(--sf-text-primary);
}

.panel-desc {
  margin-left: 8px;
  font-size: 12px;
  color: var(--sf-text-muted);
}

.panel-hint {
  color: var(--sf-text-muted);
  cursor: help;
}

.panel-empty {
  padding: 28px 8px;
  text-align: center;
}

.empty-title {
  margin: 0 0 6px;
  font-size: 14px;
  color: var(--sf-text-primary);
}

.empty-desc {
  margin: 0 0 12px;
  font-size: 12px;
  color: var(--sf-text-muted);
}

/* ---------- 趋势图 ---------- */
.trend-summary {
  display: flex;
  gap: 28px;
  margin-bottom: 16px;
}

.trend-stat {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.trend-stat-label {
  font-size: 12px;
  color: var(--sf-text-muted);
}

.trend-stat-value {
  font-size: 18px;
  font-weight: 600;
  color: var(--sf-text-primary);
  font-variant-numeric: tabular-nums;
}

.trend-stat-unit {
  margin-left: 2px;
  font-size: 12px;
  font-weight: 400;
  color: var(--sf-text-muted);
}

.chart {
  position: relative;
  display: flex;
  align-items: flex-end;
  gap: 6px;
  height: 196px;
  padding-top: 8px;
  border-bottom: 1px solid var(--sf-border);
}

.chart-col {
  display: flex;
  flex: 1;
  min-width: 0;
  flex-direction: column;
  align-items: center;
  justify-content: flex-end;
  height: 100%;
}

.chart-bar-wrap {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: flex-end;
  width: 100%;
  height: 100%;
  cursor: default;
}

.chart-value {
  height: 16px;
  font-size: 10px;
  color: var(--sf-text-muted);
  white-space: nowrap;
}

.chart-bar {
  width: 68%;
  max-width: 34px;
  min-height: 2px;
  border-radius: 4px 4px 0 0;
  background: linear-gradient(180deg, var(--sf-brand), var(--sf-brand-weak-strong));
  transition: height 0.25s ease;
}

.chart-bar.is-zero {
  background: var(--sf-border-strong);
}

.chart-empty {
  position: absolute;
  inset: 0;
  margin: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  color: var(--sf-text-muted);
  pointer-events: none;
}

.chart.is-empty .chart-col {
  opacity: 0.55;
}

.chart-bar-wrap:hover .chart-bar {
  background: var(--sf-brand-strong);
}

.chart-label {
  margin-top: 6px;
  font-size: 10px;
  color: var(--sf-text-muted);
  white-space: nowrap;
}

.tip-title {
  margin-bottom: 4px;
  font-weight: 600;
}

.tip-line {
  font-size: 12px;
  line-height: 1.6;
}

/* ---------- 场景用量 ---------- */
.scene-list {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding-right: 4px;
}

.scene-row-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
}

.scene-name {
  font-size: 13px;
  color: var(--sf-text-primary);
}

.scene-num {
  font-size: 12px;
  color: var(--sf-text-regular);
  font-variant-numeric: tabular-nums;
}

.scene-track {
  height: 6px;
  margin: 6px 0 4px;
  border-radius: 3px;
  background: var(--sf-bg-subtle);
  overflow: hidden;
}

.scene-fill {
  height: 100%;
  border-radius: 3px;
  background: linear-gradient(90deg, var(--sf-brand), var(--sf-brand-weak-strong));
}

.scene-row-foot {
  display: flex;
  justify-content: space-between;
  font-size: 11px;
  color: var(--sf-text-muted);
}

/* ---------- 最近编辑 ---------- */
.recent-list {
  display: flex;
  flex-direction: column;
}

.recent-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 8px;
  border-radius: 8px;
  cursor: pointer;
  transition: background-color 0.15s ease;
}

.recent-row + .recent-row {
  border-top: 1px solid var(--sf-border);
}

.recent-row:hover {
  background: var(--sf-bg-hover);
}

.recent-cover {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border-radius: 8px;
  font-size: 16px;
  font-weight: 600;
  color: var(--sf-brand);
  background: var(--sf-brand-weak);
  flex-shrink: 0;
}

.recent-main {
  flex: 1;
  min-width: 0;
}

.recent-line {
  display: flex;
  align-items: center;
  gap: 8px;
}

.recent-title {
  max-width: 320px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 14px;
  color: var(--sf-text-primary);
}

.recent-genre {
  font-size: 12px;
  color: var(--sf-text-muted);
}

.recent-meta {
  margin: 2px 0 0;
  font-size: 12px;
  color: var(--sf-text-muted);
}

.recent-arrow {
  color: var(--sf-text-muted);
}

/* ---------- 账号 ---------- */
.account-list {
  margin: 0 0 14px;
}

.account-item {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  padding: 7px 0;
  border-bottom: 1px dashed var(--sf-border);
}

.account-item:last-child {
  border-bottom: none;
}

.account-item dt {
  font-size: 12px;
  color: var(--sf-text-muted);
}

.account-item dd {
  margin: 0;
  font-size: 13px;
  color: var(--sf-text-primary);
  text-align: right;
}

.quick {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding-top: 12px;
  border-top: 1px solid var(--sf-border);
}
</style>
