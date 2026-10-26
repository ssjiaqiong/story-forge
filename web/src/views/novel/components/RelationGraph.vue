<template>
  <div class="relation-graph">
    <div class="graph-bar">
      <div class="graph-legend">
        <span v-for="item in LEGEND" :key="item.role" class="legend-item">
          <i class="dot" :style="{ background: item.color }" />{{ item.label }}
        </span>
      </div>
      <el-button text size="small" :icon="Refresh" @click="relayout">重新布局</el-button>
    </div>

    <svg ref="svgRef" class="graph-svg" :viewBox="`0 0 ${WIDTH} ${HEIGHT}`" preserveAspectRatio="xMidYMid meet">
      <g class="edges">
        <g v-for="edge in edges" :key="edge.key" :class="{ dim: isEdgeDimmed(edge) }">
          <path :d="edge.path" class="edge-line" />
          <text :x="edge.labelX" :y="edge.labelY" class="edge-label">{{ edge.label }}</text>
        </g>
      </g>
      <g class="nodes">
        <g
          v-for="node in nodes"
          :key="node.id"
          class="node"
          :class="{ active: selectedId === node.id, dim: isNodeDimmed(node.id) }"
          :transform="`translate(${node.x.toFixed(1)}, ${node.y.toFixed(1)})`"
          @pointerdown="startDrag($event, node)"
          @click="selectNode(node.id)"
        >
          <rect
            :x="-node.width / 2"
            y="-17"
            :width="node.width"
            height="34"
            rx="17"
            :fill="node.fill"
            :stroke="node.stroke"
            stroke-width="1.5"
          />
          <text text-anchor="middle" y="5" class="node-name" :fill="node.text">{{ node.name }}</text>
          <title>{{ node.name }} · {{ roleLabel(node.rolePos) }}{{ node.identity ? ' · ' + node.identity : '' }}</title>
        </g>
      </g>
    </svg>

    <p class="graph-hint">
      {{ nodes.length }} 个角色 · {{ edges.length }} 条关系 · 拖动节点可调整位置，点击节点高亮其关系
    </p>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import type { CharacterVO, RelationVO } from '@/api/novel-asset'
import { useThemeStore } from '@/stores/theme'

const props = defineProps<{
  characters: CharacterVO[]
  relations: RelationVO[]
}>()

const WIDTH = 900
const HEIGHT = 520
const PAD = 60

const theme = useThemeStore()

/** 深浅两套角色配色：深色下用低明度底 + 提亮描边，保证节点文字可读 */
const ROLE_STYLE_DARK: Record<string, { fill: string; stroke: string; text: string; color: string }> = {
  PROTAGONIST: { fill: '#1e2a4a', stroke: '#5b8bff', text: '#c7d8ff', color: '#5b8bff' },
  ANTAGONIST: { fill: '#3a1f24', stroke: '#ff7b81', text: '#ffc9cc', color: '#ff7b81' },
  SUPPORTING: { fill: '#262a31', stroke: '#8b95a5', text: '#d5dbe4', color: '#8b95a5' },
  MINOR: { fill: '#23262d', stroke: '#6f757e', text: '#a8adb5', color: '#6f757e' }
}

const ROLE_STYLE_LIGHT: Record<string, { fill: string; stroke: string; text: string; color: string }> = {
  PROTAGONIST: { fill: '#e8f0ff', stroke: '#3b6ef6', text: '#1f4bd8', color: '#3b6ef6' },
  ANTAGONIST: { fill: '#ffecef', stroke: '#e5484d', text: '#c0262b', color: '#e5484d' },
  SUPPORTING: { fill: '#eef2f7', stroke: '#7a8699', text: '#3f4a5a', color: '#7a8699' },
  MINOR: { fill: '#f3f4f6', stroke: '#a3aab6', text: '#6b7280', color: '#a3aab6' }
}

const ROLE_STYLE = computed(() => (theme.isDark ? ROLE_STYLE_DARK : ROLE_STYLE_LIGHT))

const LEGEND = computed(() => [
  { role: 'PROTAGONIST', label: '主角', color: ROLE_STYLE.value.PROTAGONIST.color },
  { role: 'ANTAGONIST', label: '反派', color: ROLE_STYLE.value.ANTAGONIST.color },
  { role: 'SUPPORTING', label: '配角', color: ROLE_STYLE.value.SUPPORTING.color },
  { role: 'MINOR', label: '龙套', color: ROLE_STYLE.value.MINOR.color }
])

interface GraphNode {
  id: string
  name: string
  rolePos: string
  identity?: string
  width: number
  x: number
  y: number
  vx: number
  vy: number
  fill: string
  stroke: string
  text: string
}

const svgRef = ref<SVGSVGElement | null>(null)
const nodes = ref<GraphNode[]>([])
const selectedId = ref('')

const edges = computed(() => {
  const nodeMap = new Map(nodes.value.map((node) => [node.id, node]))
  const groups = new Map<string, RelationVO[]>()
  for (const relation of props.relations) {
    const key = [relation.fromCharacterId, relation.toCharacterId].sort().join('~')
    if (!groups.has(key)) {
      groups.set(key, [])
    }
    groups.get(key)!.push(relation)
  }

  const result: Array<{ key: string; path: string; label: string; labelX: number; labelY: number; from: string; to: string }> = []
  for (const list of groups.values()) {
    list.forEach((relation, index) => {
      const from = nodeMap.get(relation.fromCharacterId)
      const to = nodeMap.get(relation.toCharacterId)
      if (!from || !to) {
        return
      }
      // 同一对角色有多条关系时，沿垂直方向错开，避免标签叠在一起
      const offset = list.length > 1 ? (index - (list.length - 1) / 2) * 42 : 0
      const dx = to.x - from.x
      const dy = to.y - from.y
      const length = Math.hypot(dx, dy) || 1
      const ux = dx / length
      const uy = dy / length
      const startX = from.x + ux * (from.width / 2)
      const startY = from.y + uy * 16
      const endX = to.x - ux * (to.width / 2)
      const endY = to.y - uy * 16
      const midX = (startX + endX) / 2 - uy * offset
      const midY = (startY + endY) / 2 + ux * offset
      const labelX = 0.25 * startX + 0.5 * midX + 0.25 * endX
      const labelY = 0.25 * startY + 0.5 * midY + 0.25 * endY + 4
      result.push({
        key: relation.id,
        path: offset === 0
          ? `M ${startX.toFixed(1)} ${startY.toFixed(1)} L ${endX.toFixed(1)} ${endY.toFixed(1)}`
          : `M ${startX.toFixed(1)} ${startY.toFixed(1)} Q ${midX.toFixed(1)} ${midY.toFixed(1)} ${endX.toFixed(1)} ${endY.toFixed(1)}`,
        label: relation.relationLabel,
        labelX,
        labelY,
        from: relation.fromCharacterId,
        to: relation.toCharacterId
      })
    })
  }
  return result
})

const neighbors = computed(() => {
  const set = new Set<string>()
  if (!selectedId.value) {
    return set
  }
  for (const relation of props.relations) {
    if (relation.fromCharacterId === selectedId.value) {
      set.add(relation.toCharacterId)
    }
    if (relation.toCharacterId === selectedId.value) {
      set.add(relation.fromCharacterId)
    }
  }
  return set
})

watch(
  () => [props.characters, props.relations],
  () => rebuild(),
  { deep: true }
)

// 切换主题时角色配色要跟着换（SVG 是内联属性，不走 CSS 变量）
watch(
  () => theme.isDark,
  () => rebuild()
)

onMounted(() => {
  rebuild()
  window.addEventListener('pointermove', onPointerMove)
  window.addEventListener('pointerup', stopDrag)
})

onBeforeUnmount(() => {
  window.removeEventListener('pointermove', onPointerMove)
  window.removeEventListener('pointerup', stopDrag)
})

function rebuild() {
  selectedId.value = ''
  nodes.value = props.characters.map((character, index) => {
    const style = ROLE_STYLE.value[character.rolePos || 'SUPPORTING'] ?? ROLE_STYLE.value.SUPPORTING
    const name = character.name || '未命名'
    const angle = (Math.PI * 2 * index) / Math.max(props.characters.length, 1) - Math.PI / 2
    const radius = Math.min(WIDTH, HEIGHT) * 0.28
    return reactive({
      id: String(character.id),
      name,
      rolePos: character.rolePos || 'SUPPORTING',
      identity: character.identity,
      width: Math.max(62, 28 + name.length * 16),
      x: WIDTH / 2 + Math.cos(angle) * radius + (Math.random() - 0.5) * 40,
      y: HEIGHT / 2 + Math.sin(angle) * radius + (Math.random() - 0.5) * 40,
      vx: 0,
      vy: 0,
      fill: style.fill,
      stroke: style.stroke,
      text: style.text
    })
  })
  relayout()
}

/** Fruchterman-Reingold 力导向布局：节点互斥、连线收紧、向心力防止飘出画面 */
function relayout() {
  const list = nodes.value
  if (!list.length) {
    return
  }
  const nodeMap = new Map(list.map((node) => [node.id, node]))
  const pairs = new Set<string>()
  const links: Array<{ a: GraphNode; b: GraphNode }> = []
  for (const relation of props.relations) {
    const a = nodeMap.get(relation.fromCharacterId)
    const b = nodeMap.get(relation.toCharacterId)
    if (!a || !b) {
      continue
    }
    const key = [a.id, b.id].sort().join('~')
    if (pairs.has(key)) {
      continue
    }
    pairs.add(key)
    links.push({ a, b })
  }

  const k = Math.sqrt((WIDTH * HEIGHT) / Math.max(list.length, 1)) * 0.75
  let temperature = WIDTH / 4
  const iterations = 480

  for (let step = 0; step < iterations; step++) {
    for (const node of list) {
      node.vx = 0
      node.vy = 0
    }

    // 节点互斥
    for (let i = 0; i < list.length; i++) {
      for (let j = i + 1; j < list.length; j++) {
        const a = list[i]
        const b = list[j]
        let dx = a.x - b.x
        let dy = a.y - b.y
        let distance = Math.hypot(dx, dy)
        if (distance < 1) {
          dx = Math.random() - 0.5
          dy = Math.random() - 0.5
          distance = Math.hypot(dx, dy) || 1
        }
        const force = (k * k) / distance
        const fx = (dx / distance) * force
        const fy = (dy / distance) * force
        a.vx += fx
        a.vy += fy
        b.vx -= fx
        b.vy -= fy
      }
    }

    // 有关系的角色互相靠近
    for (const link of links) {
      const dx = link.a.x - link.b.x
      const dy = link.a.y - link.b.y
      const distance = Math.hypot(dx, dy) || 1
      const force = (distance * distance) / k
      const fx = (dx / distance) * force
      const fy = (dy / distance) * force
      link.a.vx -= fx
      link.a.vy -= fy
      link.b.vx += fx
      link.b.vy += fy
    }

    for (const node of list) {
      // 向心力，避免整体漂移
      node.vx += (WIDTH / 2 - node.x) * 0.02
      node.vy += (HEIGHT / 2 - node.y) * 0.02
      const moveX = node.vx
      const moveY = node.vy
      const moveLength = Math.hypot(moveX, moveY) || 1
      const limited = Math.min(moveLength, temperature)
      node.x += (moveX / moveLength) * limited
      node.y += (moveY / moveLength) * limited
      node.x = clamp(node.x, PAD, WIDTH - PAD)
      node.y = clamp(node.y, PAD, HEIGHT - PAD)
    }
    temperature *= 0.985
  }

  // 力导向只会给出相对位置，这里按包围盒等比放大，尽量铺满画布，
  // 避免节点挤在中间一小块、四周全是空白。
  const minX = Math.min(...list.map((node) => node.x))
  const maxX = Math.max(...list.map((node) => node.x))
  const minY = Math.min(...list.map((node) => node.y))
  const maxY = Math.max(...list.map((node) => node.y))
  const centerX = (minX + maxX) / 2
  const centerY = (minY + maxY) / 2
  const spanX = Math.max(maxX - minX, 1)
  const spanY = Math.max(maxY - minY, 1)
  const targetWidth = WIDTH - PAD * 2 - 80
  const targetHeight = HEIGHT - PAD * 2 - 40
  const scale = Math.min(targetWidth / spanX, targetHeight / spanY, 2.6)
  for (const node of list) {
    node.x = clamp(WIDTH / 2 + (node.x - centerX) * scale, PAD, WIDTH - PAD)
    node.y = clamp(HEIGHT / 2 + (node.y - centerY) * scale, PAD, HEIGHT - PAD)
  }
}

/* ---------------- 拖拽 ---------------- */
interface DragState {
  node: GraphNode
  offsetX: number
  offsetY: number
  moved: boolean
}

let dragState: DragState | null = null
let suppressClick = false

function startDrag(event: PointerEvent, node: GraphNode) {
  event.preventDefault()
  suppressClick = false
  const point = toGraphPoint(event)
  if (!point) {
    return
  }
  dragState = { node, offsetX: node.x - point.x, offsetY: node.y - point.y, moved: false }
}

function onPointerMove(event: PointerEvent) {
  if (!dragState) {
    return
  }
  const point = toGraphPoint(event)
  if (!point) {
    return
  }
  dragState.node.x = clamp(point.x + dragState.offsetX, PAD / 2, WIDTH - PAD / 2)
  dragState.node.y = clamp(point.y + dragState.offsetY, PAD / 2, HEIGHT - PAD / 2)
  dragState.moved = true
}

function stopDrag() {
  if (dragState?.moved) {
    suppressClick = true
  }
  dragState = null
}

function toGraphPoint(event: PointerEvent) {
  const svg = svgRef.value
  if (!svg) {
    return null
  }
  const rect = svg.getBoundingClientRect()
  if (!rect.width || !rect.height) {
    return null
  }
  return {
    x: ((event.clientX - rect.left) / rect.width) * WIDTH,
    y: ((event.clientY - rect.top) / rect.height) * HEIGHT
  }
}

/* ---------------- 高亮 ---------------- */
function selectNode(id: string) {
  if (suppressClick) {
    suppressClick = false
    return
  }
  selectedId.value = selectedId.value === id ? '' : id
}

function isNodeDimmed(id: string) {
  if (!selectedId.value) {
    return false
  }
  return id !== selectedId.value && !neighbors.value.has(id)
}

function isEdgeDimmed(edge: { from: string; to: string }) {
  if (!selectedId.value) {
    return false
  }
  return edge.from !== selectedId.value && edge.to !== selectedId.value
}

function roleLabel(rolePos: string) {
  return { PROTAGONIST: '主角', ANTAGONIST: '反派', MINOR: '龙套' }[rolePos] ?? '配角'
}

function clamp(value: number, min: number, max: number) {
  return Math.min(max, Math.max(min, value))
}
</script>

<style scoped>
.relation-graph {
  display: flex;
  flex-direction: column;
}

.graph-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 4px;
}

.graph-legend {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}

.legend-item {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 11px;
  color: var(--sf-text-regular);
}

.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  display: inline-block;
}

.graph-svg {
  width: 100%;
  height: 440px;
  display: block;
  overflow: visible;
  touch-action: none;
}

.edge-line {
  stroke: var(--sf-graph-edge);
  stroke-width: 1.6;
  fill: none;
}

.edge-label {
  font-size: 12px;
  fill: var(--sf-text-regular);
  text-anchor: middle;
  paint-order: stroke;
  stroke: var(--sf-graph-label-bg);
  stroke-width: 4px;
  stroke-linejoin: round;
  pointer-events: none;
}

.node {
  cursor: grab;
}

.node rect {
  transition: filter 0.15s ease;
}

.node:hover rect {
  filter: drop-shadow(0 2px 6px rgba(31, 45, 61, 0.18));
}

.node-name {
  font-size: 13.5px;
  font-weight: 600;
  pointer-events: none;
  user-select: none;
}

.node.active rect {
  stroke-width: 2.5;
  filter: drop-shadow(0 2px 8px rgba(59, 110, 246, 0.35));
}

.node.dim,
.edges .dim {
  opacity: 0.25;
  transition: opacity 0.15s ease;
}

.graph-hint {
  margin: 2px 0 0;
  font-size: 11px;
  color: var(--sf-text-muted);
  text-align: center;
}
</style>
