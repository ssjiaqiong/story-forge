<template>
  <el-container class="layout">
    <el-aside :width="collapsed ? '64px' : '216px'" class="layout-aside" :class="{ 'is-collapsed': collapsed }">
      <div class="logo">
        <span class="logo-mark">SF</span>
        <span v-show="!collapsed" class="logo-text">StoryForge</span>
      </div>
      <el-menu
        :default-active="activeMenu"
        :collapse="collapsed"
        :collapse-transition="false"
        router
        class="layout-menu"
        :background-color="menuBg"
        :text-color="menuTextColor"
        active-text-color="#ffffff"
      >
        <el-menu-item v-for="item in menus" :key="item.path" :index="item.path">
          <el-icon><component :is="item.icon" /></el-icon>
          <template #title>{{ item.title }}</template>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="layout-header">
        <div class="header-left">
          <el-icon class="collapse-btn" :title="collapsed ? '展开菜单' : '收起菜单'" @click="toggleMenu">
            <component :is="collapsed ? Expand : Fold" />
          </el-icon>
          <div class="header-title">{{ currentTitle }}</div>
        </div>
        <div class="header-right">
          <el-tag v-if="auth.isSuperAdmin" type="warning" effect="plain" size="small">超级管理员</el-tag>
          <el-tooltip :content="`主题：${theme.themeLabel}`" placement="bottom">
            <el-dropdown trigger="click" @command="onThemeCommand">
              <span class="theme-trigger">
                <el-icon><component :is="theme.isDark ? Moon : Sunny" /></el-icon>
              </span>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="light" :class="{ 'is-active-theme': theme.mode === 'light' }">
                    浅色
                  </el-dropdown-item>
                  <el-dropdown-item command="dark" :class="{ 'is-active-theme': theme.mode === 'dark' }">
                    深色
                  </el-dropdown-item>
                  <el-dropdown-item command="system" :class="{ 'is-active-theme': theme.mode === 'system' }">
                    跟随系统
                  </el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </el-tooltip>
          <el-dropdown @command="onCommand">
            <span class="user-trigger">
              <el-icon><UserFilled /></el-icon>
              <span class="user-name">{{ auth.displayName }}</span>
              <el-icon><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="password">修改口令</el-dropdown-item>
                <el-dropdown-item command="source" divided>获取源码</el-dropdown-item>
                <el-dropdown-item command="license">开源许可（AGPL-3.0）</el-dropdown-item>
                <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>

      <el-main class="layout-main">
        <router-view />
      </el-main>

      <el-footer class="layout-footer" height="auto">
        <a :href="ICP_URL" target="_blank" rel="noopener noreferrer">{{ ICP_NUMBER }}</a>
        <span class="footer-sep">·</span>
        <span>开源仓库</span>
        <a :href="SOURCE_REPO_URL" target="_blank" rel="noopener noreferrer">{{ SOURCE_REPO_URL }}</a>
        <template v-if="systemStore.version">
          <span class="footer-sep">·</span>
          <span class="footer-version" :title="systemStore.buildTime ? `构建于 ${systemStore.buildTime}` : '当前部署的代码版本'">
            v{{ systemStore.version }}
          </span>
        </template>
      </el-footer>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch, type Component } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { ArrowDown, Collection, Connection, EditPen, Expand, Fold, MagicStick, Moon, Notebook, Odometer, Reading, Search, Sunny, User, UserFilled } from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'
import { useSystemStore } from '@/stores/system'
import { useThemeStore, type ThemeMode } from '@/stores/theme'
import { ICP_NUMBER, ICP_URL, LICENSE_URL, openExternal, SOURCE_REPO_URL } from '@/config/about'

interface MenuItem {
  path: string
  title: string
  icon: Component
}

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const theme = useThemeStore()
const systemStore = useSystemStore()

/* 页脚版本号只在首次进入主布局时拉一次 */
onMounted(() => {
  systemStore.load()
})

/* 侧栏固定深底：深色主题下再压暗一档 */
const menuBg = computed(() => (theme.isDark ? '#111318' : '#1f2733'))
const menuTextColor = computed(() => (theme.isDark ? '#9aa3b0' : '#c8d0dc'))

function onThemeCommand(command: string) {
  theme.setMode(command as ThemeMode)
}

const iconMap: Record<string, Component> = {
  Odometer,
  Connection,
  User,
  Notebook,
  MagicStick,
  Reading,
  Collection,
  Search,
  EditPen
}

const menus = computed<MenuItem[]>(() => {
  const layoutRoute = router.getRoutes().find((item) => item.path === '/')
  const children = layoutRoute?.children ?? []
  return children
    .filter((child) => child.meta?.menu === true)
    .filter((child) => {
      const roles = child.meta?.roles as string[] | undefined
      return !roles || roles.includes(auth.role ?? '')
    })
    .map((child) => ({
      path: `/${String(child.path).replace(/^\//, '')}`,
      title: (child.meta?.title as string) ?? child.path,
      icon: iconMap[(child.meta?.icon as string) ?? ''] ?? Odometer
    }))
})

const activeMenu = computed(() => route.path)
const currentTitle = computed(() => (route.meta?.title as string) ?? 'StoryForge')

/* ---------- 侧栏折叠 ---------- */
const COLLAPSE_KEY = 'storyforge.menuCollapsed'
/** 用户手动选择的状态（离开小说编辑页后恢复到这个状态） */
const preferredCollapsed = ref(localStorage.getItem(COLLAPSE_KEY) === '1')
const collapsed = ref(preferredCollapsed.value)
/** 小说编辑页：工作台需要更宽的画布，进入即自动收起 */
const isNovelEditor = computed(() => route.name === 'novel-detail')

if (isNovelEditor.value) {
  collapsed.value = true
}

watch(
  () => route.name,
  () => {
    // 进入工作台自动折叠；离开时恢复用户自己的偏好
    collapsed.value = isNovelEditor.value ? true : preferredCollapsed.value
  }
)

function toggleMenu() {
  collapsed.value = !collapsed.value
  preferredCollapsed.value = collapsed.value
  localStorage.setItem(COLLAPSE_KEY, collapsed.value ? '1' : '0')
}

async function onCommand(command: string) {
  if (command === 'password') {
    router.push({ name: 'change-password' })
    return
  }
  /* AGPL-3.0 §13：登录后同样保留源码与协议的获取入口 */
  if (command === 'source') {
    openExternal(SOURCE_REPO_URL)
    return
  }
  if (command === 'license') {
    openExternal(LICENSE_URL)
    return
  }
  if (command === 'logout') {
    await ElMessageBox.confirm('确认退出登录？', '提示', { type: 'warning' })
    await auth.logout()
    router.push({ name: 'login' })
  }
}
</script>

<style scoped>
.layout {
  height: 100vh;
}

.layout-aside {
  background: var(--sf-aside-bg);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  transition: width 0.2s ease;
}

.logo {
  height: 56px;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 18px;
  color: #fff;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
}

.logo-mark {
  width: 28px;
  height: 28px;
  border-radius: 6px;
  background: var(--sf-brand);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  font-weight: 700;
}

.logo-text {
  font-size: 16px;
  font-weight: 600;
  letter-spacing: 0.5px;
  white-space: nowrap;
}

.layout-aside.is-collapsed .logo {
  padding: 0;
  justify-content: center;
}

.layout-aside.is-collapsed .layout-menu {
  width: 64px;
}

.layout-menu {
  border-right: none;
  flex: 1;
}

.layout-header {
  height: 56px;
  background: var(--sf-bg-card);
  border-bottom: 1px solid var(--sf-border);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 20px;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}

.collapse-btn {
  font-size: 18px;
  color: var(--sf-text-regular);
  cursor: pointer;
  border-radius: 4px;
  padding: 4px;
  transition: background 0.15s ease, color 0.15s ease;
}

.collapse-btn:hover {
  color: var(--sf-brand);
  background: var(--sf-brand-weak);
}

.theme-trigger {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  border-radius: 6px;
  cursor: pointer;
  color: var(--sf-text-regular);
  transition: background 0.15s ease, color 0.15s ease;
  outline: none;
}

.theme-trigger:hover {
  color: var(--sf-brand);
  background: var(--sf-brand-weak);
}

.header-title {
  font-size: 16px;
  font-weight: 600;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 14px;
}

.user-trigger {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
  color: var(--sf-text-primary);
  outline: none;
}

.user-name {
  font-size: 14px;
}

.layout-main {
  background: var(--sf-bg-page);
  padding: 16px;
  overflow-y: auto;
}

.layout-footer {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-wrap: wrap;
  gap: 4px;
  padding: 10px 16px;
  font-size: 12px;
  color: var(--sf-text-muted);
  background: var(--sf-bg-page);
  border-top: 1px solid var(--sf-border);
}

.layout-footer a {
  color: var(--sf-text-muted);
  text-decoration: none;
}

.layout-footer a:hover {
  color: var(--sf-brand);
  text-decoration: underline;
}

.footer-sep {
  margin: 0 4px;
}

/* 部署版本号：弱化处理，不抢备案与仓库链接的注意力 */
.footer-version {
  font-family: var(--el-font-family);
  font-variant-numeric: tabular-nums;
  opacity: 0.85;
}

.is-active-theme {
  color: var(--sf-brand);
  font-weight: 600;
}
</style>
