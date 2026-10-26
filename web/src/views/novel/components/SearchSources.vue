<template>
  <div v-if="bundle" class="search-sources" :class="{ 'is-warn': !bundle.used || bundle.error }">
    <div class="source-head">
      <span class="source-title">{{ title }}</span>
      <el-tag v-if="bundle.used && !bundle.error" size="small" type="success" effect="plain">
        {{ bundle.providerLabel || bundle.provider }}
      </el-tag>
      <el-tag v-else size="small" type="warning" effect="plain">未使用资料</el-tag>
      <span v-if="bundle.query" class="source-query">检索：{{ bundle.query }}</span>
      <span v-if="bundle.used" class="source-elapsed">{{ bundle.elapsedMs }}ms</span>
      <el-button text size="small" class="source-close" @click="$emit('close')">收起</el-button>
    </div>
    <p v-if="bundle.error" class="source-error">{{ bundle.error }}</p>
    <ol v-if="bundle.results.length" class="source-list">
      <li v-for="item in bundle.results" :key="item.url">
        <a :href="item.url" target="_blank" rel="noopener noreferrer">{{ item.title }}</a>
        <span class="source-host">{{ item.host }}</span>
        <p v-if="item.snippet" class="source-snippet">{{ item.snippet }}</p>
      </li>
    </ol>
  </div>
</template>

<script setup lang="ts">
import type { SearchBundleVO } from '@/api/search'

defineProps<{
  bundle: SearchBundleVO | null
  title?: string
}>()

defineEmits<{ (event: 'close'): void }>()
</script>

<style scoped>
.search-sources {
  margin: 0 0 14px;
  padding: 12px 14px;
  border: 1px solid rgba(59, 110, 246, 0.18);
  background: rgba(59, 110, 246, 0.04);
  border-radius: 10px;
}

.search-sources.is-warn {
  border-color: rgba(230, 162, 60, 0.35);
  background: rgba(230, 162, 60, 0.06);
}

.source-head {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}

.source-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--sf-text-primary, #1f2733);
}

.source-query {
  font-size: 11px;
  color: var(--sf-text-muted);
}

.source-elapsed {
  font-size: 11px;
  color: var(--sf-text-muted);
}

.source-close {
  margin-left: auto;
}

.source-error {
  margin: 8px 0 0;
  font-size: 12px;
  color: var(--sf-text-warning);
}

.source-list {
  margin: 8px 0 0;
  padding-left: 18px;
}

.source-list li {
  margin-bottom: 8px;
  font-size: 12px;
  color: var(--sf-text-regular);
}

.source-list a {
  color: var(--sf-brand, #3b6ef6);
  text-decoration: none;
}

.source-list a:hover {
  text-decoration: underline;
}

.source-host {
  margin-left: 6px;
  font-size: 11px;
  color: var(--sf-text-muted);
}

.source-snippet {
  margin: 2px 0 0;
  line-height: 1.6;
  color: var(--sf-text-muted);
}
</style>
