<template>
  <div class="book-cover" :class="{ 'has-image': !!activeUrl }">
    <img v-if="activeUrl" :src="activeUrl" class="cover-img" :alt="title" />
    <div v-else class="cover-fallback" :style="fallbackStyle">
      <span class="fallback-word">{{ coverWord }}</span>
      <span v-if="covers.length && loading" class="cover-loading">载入中</span>
    </div>

    <template v-if="covers.length > 1">
      <button type="button" class="nav prev" title="上一张" @click.stop="prev">‹</button>
      <button type="button" class="nav next" title="下一张" @click.stop="next">›</button>
      <span class="dots">
        <i v-for="(item, i) in covers" :key="item.id" :class="{ on: i === index }" />
      </span>
      <span class="counter">{{ index + 1 }}/{{ covers.length }}</span>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { loadCoverUrl, type NovelCoverVO } from '@/api/cover'

const props = defineProps<{
  covers: NovelCoverVO[]
  title: string
}>()

const COVERS = [
  'linear-gradient(135deg, #3b6ef6, #7aa2ff)',
  'linear-gradient(135deg, #0f766e, #2dd4bf)',
  'linear-gradient(135deg, #7c3aed, #c084fc)',
  'linear-gradient(135deg, #d97706, #fbbf24)',
  'linear-gradient(135deg, #be123c, #fb7185)',
  'linear-gradient(135deg, #334155, #64748b)'
]

/** 当前展示第几张（后端已把主封面排在第 0 位） */
const index = ref(0)
const urls = ref<Record<string, string>>({})
const failed = ref<Record<string, boolean>>({})
const loading = ref(false)

const active = computed(() => props.covers[index.value])
const activeUrl = computed(() => {
  const cover = active.value
  if (!cover || failed.value[cover.id]) {
    return ''
  }
  return urls.value[cover.id] || ''
})

const coverWord = computed(() => (props.title.length <= 2 ? props.title : props.title.slice(0, 2)))

const fallbackStyle = computed(() => {
  let hash = 0
  for (const ch of props.title) {
    hash = (hash * 31 + ch.charCodeAt(0)) % 9973
  }
  return { background: COVERS[hash % COVERS.length] }
})

/** 封面列表变化（重新加载书架 / 生成新封面）时回到主封面 */
watch(
  () => props.covers.map((item) => item.id).join(','),
  () => {
    index.value = 0
    void ensureActive()
  },
  { immediate: true }
)

async function ensureActive() {
  const cover = active.value
  if (!cover || urls.value[cover.id] || failed.value[cover.id]) {
    return
  }
  loading.value = true
  try {
    urls.value[cover.id] = await loadCoverUrl(cover.id)
  } catch {
    failed.value[cover.id] = true
  } finally {
    loading.value = false
  }
}

async function prev() {
  const size = props.covers.length
  index.value = (index.value - 1 + size) % size
  await ensureActive()
}

async function next() {
  const size = props.covers.length
  index.value = (index.value + 1) % size
  await ensureActive()
}
</script>

<style scoped>
.book-cover {
  position: relative;
  width: 100%;
  /* 竖版书籍封面比例 3:4，高度由宽度撑起 */
  aspect-ratio: 3 / 4;
  flex: none;
  border-radius: 6px;
  overflow: hidden;
  box-shadow: 0 2px 8px rgba(31, 41, 55, 0.14);
  background: var(--sf-bg-page);
}

.cover-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.cover-fallback {
  width: 100%;
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 4px;
  color: #fff;
}

.fallback-word {
  font-size: 22px;
  font-weight: 700;
  letter-spacing: 2px;
  opacity: 0.94;
}

.cover-loading {
  font-size: 10px;
  opacity: 0.8;
}

.nav {
  position: absolute;
  top: 50%;
  transform: translateY(-50%);
  width: 18px;
  height: 34px;
  padding: 0;
  border: none;
  border-radius: 4px;
  background: rgba(15, 23, 42, 0.42);
  color: #fff;
  font-size: 16px;
  line-height: 1;
  cursor: pointer;
  opacity: 0;
  transition: opacity 0.15s ease, background 0.15s ease;
}

.prev {
  left: 2px;
}

.next {
  right: 2px;
}

.book-cover:hover .nav {
  opacity: 1;
}

.nav:hover {
  background: rgba(15, 23, 42, 0.7);
}

.dots {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 4px;
  display: flex;
  justify-content: center;
  gap: 3px;
  pointer-events: none;
}

.dots i {
  width: 4px;
  height: 4px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.5);
}

.dots i.on {
  background: #fff;
  box-shadow: 0 0 0 1px rgba(15, 23, 42, 0.25);
}

.counter {
  position: absolute;
  right: 3px;
  top: 3px;
  padding: 0 4px;
  font-size: 10px;
  border-radius: 6px;
  color: #fff;
  background: rgba(15, 23, 42, 0.45);
}
</style>
