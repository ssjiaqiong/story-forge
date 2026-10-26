<template>
  <el-drawer v-model="visible" :title="title" size="460px" destroy-on-close>
    <div v-loading="loading" class="history-body">
      <div v-if="!items.length" class="empty-block small">
        <p>还没有变更履历。</p>
        <p class="history-hint">按章节顺序执行「分析」后，人物、属性、背包、物品、势力的状态变化会记在这里。</p>
      </div>
      <el-timeline v-else>
        <el-timeline-item
          v-for="item in items"
          :key="item.id"
          :timestamp="`第 ${item.chapterNo} 章 · ${item.chapterTitle || '未命名'}`"
          placement="top"
          :type="dotType(item.entityType)"
        >
          <div class="history-card">
            <div class="history-head">
              <span class="history-field">{{ item.fieldLabel }}</span>
              <span class="history-change">
                <span class="state before">{{ item.beforeState || '—' }}</span>
                <el-icon class="arrow"><Right /></el-icon>
                <span class="state after">{{ item.afterState || '—' }}</span>
              </span>
            </div>
            <p v-if="item.reason" class="history-reason">{{ item.reason }}</p>
            <p v-if="item.evidence" class="history-evidence">「{{ item.evidence }}」</p>
            <p class="history-time">{{ formatTime(item.createdAt) }}</p>
          </div>
        </el-timeline-item>
      </el-timeline>
    </div>
  </el-drawer>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Right } from '@element-plus/icons-vue'
import { listChangeHistories, type ChangeHistoryVO, type EntityType } from '@/api/analysis'

const props = defineProps<{ novelId: string }>()

const visible = ref(false)
const loading = ref(false)
const items = ref<ChangeHistoryVO[]>([])
const title = ref('变更履历')

async function open(entityType: EntityType, entityId: string, entityName: string) {
  title.value = `${entityName} · 变更履历`
  visible.value = true
  loading.value = true
  items.value = []
  try {
    // 人物履历要连带属性与背包：它们共用同一个 entityId（角色 ID），只是 entityType 不同
    const types: EntityType[] =
      entityType === 'CHARACTER' ? ['CHARACTER', 'CHARACTER_ATTRIBUTE', 'INVENTORY'] : [entityType]
    const results = await Promise.all(types.map((type) => listChangeHistories(props.novelId, type, entityId)))
    items.value = results
      .flat()
      .sort((a, b) => (b.chapterNo || 0) - (a.chapterNo || 0) || String(b.id).localeCompare(String(a.id)))
  } catch (error) {
    ElMessage.error((error as Error).message || '加载履历失败')
  } finally {
    loading.value = false
  }
}

function dotType(entityType: EntityType) {
  if (entityType === 'ITEM' || entityType === 'INVENTORY') {
    return 'warning'
  }
  if (entityType === 'FACTION') {
    return 'success'
  }
  if (entityType === 'CHARACTER_ATTRIBUTE') {
    return 'info'
  }
  return 'primary'
}

function formatTime(value?: string) {
  if (!value) {
    return ''
  }
  return value.replace('T', ' ').slice(0, 16)
}

defineExpose({ open })
</script>

<style scoped>
.history-body {
  min-height: 180px;
}

.history-hint {
  margin-top: 6px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.history-card {
  padding: 10px 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  background: var(--el-fill-color-blank);
}

.history-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.history-field {
  font-size: 13px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.history-change {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
}

.state.before {
  color: var(--el-text-color-secondary);
  text-decoration: line-through;
}

.state.after {
  color: var(--el-color-primary);
  font-weight: 600;
}

.arrow {
  color: var(--el-text-color-placeholder);
  font-size: 12px;
}

.history-reason {
  margin: 6px 0 0;
  font-size: 13px;
  line-height: 1.6;
  color: var(--el-text-color-regular);
}

.history-evidence {
  margin: 4px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}

.history-time {
  margin: 6px 0 0;
  font-size: 12px;
  color: var(--el-text-color-placeholder);
}
</style>
