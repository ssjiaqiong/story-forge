/**
 * StoryForge 前端入口
 * Copyright (C) 2026 Jiaqiong
 * SPDX-License-Identifier: AGPL-3.0-or-later（协议全文见仓库根目录 LICENSE）
 */
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
// 暗色变量：html.dark 生效
import 'element-plus/theme-chalk/dark/css-vars.css'

import App from './App.vue'
import router from './router'
import './styles/theme.css'
import './styles/index.css'
import { useThemeStore } from './stores/theme'

const app = createApp(App)

app.use(createPinia())
app.use(router)
app.use(ElementPlus, { locale: zhCn })

// 主题需要在挂载前生效，避免首帧闪烁（index.html 已做首次兜底）
useThemeStore().init()

app.mount('#app')
