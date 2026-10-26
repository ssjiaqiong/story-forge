/**
 * 中文正文的差异对比。
 *
 * 粒度分两层：先把两稿按段落对齐（段落级 LCS），对不上的段落再逐字对齐（字符级 LCS）。
 * 只做字符级会把整章变成一个巨大的表格，只做段落级又看不出段落里改了哪几句，
 * 两层合起来才既能定位到段，又能看见「删了哪句、加了哪句」。
 *
 * 纯前端实现，不引第三方 diff 库：中文正文段落短、结构规整，自己算足够快，
 * 也便于按写作习惯控制合并粒度（连续同类型片段合并成一个 span，避免逐字渲染）。
 */

export type DiffSegmentType = 'equal' | 'add' | 'remove'

export interface DiffSegment {
  text: string
  type: DiffSegmentType
}

export type DiffBlockKind = 'equal' | 'change' | 'add' | 'remove'

export interface DiffBlock {
  kind: DiffBlockKind
  /** 原文段落序号，从 1 开始；新增块为 null */
  leftNo: number | null
  /** 新稿段落序号，从 1 开始；删除块为 null */
  rightNo: number | null
  left: DiffSegment[]
  right: DiffSegment[]
}

export interface DiffResult {
  blocks: DiffBlock[]
  /** 新增的字符数 */
  added: number
  /** 删除的字符数 */
  removed: number
  /** 有改动的块数 */
  changed: number
  /** 未改动的段落数 */
  unchanged: number
}

interface Op {
  type: DiffSegmentType
  text: string
}

/** 超过这个规模就不做逐字 LCS，直接整段替换，避免卡住主线程 */
const MAX_CHAR_PRODUCT = 4_000_000
/** 段落数超过这个值也不用段落级 LCS，按顺序一一对应更稳 */
const MAX_PARAGRAPH_PRODUCT = 250_000

export function splitParagraphs(text: string): string[] {
  return (text || '')
    .replace(/\r\n?/g, '\n')
    .split('\n')
    .map((line) => line.trim())
    .filter((line) => line.length > 0)
}

export function diffTexts(before: string, after: string): DiffResult {
  const left = splitParagraphs(before)
  const right = splitParagraphs(after)
  const blocks: DiffBlock[] = []
  let added = 0
  let removed = 0
  let changed = 0
  let unchanged = 0

  const pushEqual = (text: string, leftNo: number, rightNo: number) => {
    blocks.push({
      kind: 'equal',
      leftNo,
      rightNo,
      left: [{ text, type: 'equal' }],
      right: [{ text, type: 'equal' }]
    })
    unchanged += 1
  }

  const pushChange = (leftText: string, rightText: string, leftNo: number, rightNo: number) => {
    const result = diffParagraph(leftText, rightText)
    blocks.push({ kind: 'change', leftNo, rightNo, left: result.left, right: result.right })
    added += sumLength(result.right.filter((seg) => seg.type === 'add'))
    removed += sumLength(result.left.filter((seg) => seg.type === 'remove'))
    changed += 1
  }

  const pushRemove = (leftText: string, leftNo: number) => {
    blocks.push({ kind: 'remove', leftNo, rightNo: null, left: [{ text: leftText, type: 'remove' }], right: [] })
    removed += leftText.length
    changed += 1
  }

  const pushAdd = (rightText: string, rightNo: number) => {
    blocks.push({ kind: 'add', leftNo: null, rightNo, left: [], right: [{ text: rightText, type: 'add' }] })
    added += rightText.length
    changed += 1
  }

  const pairs = lcsPairs(left, right)
  let i = 0
  let j = 0
  for (const [pi, pj] of pairs) {
    // 两个匹配段落之间的空白区：按顺序一一配对成「改写」，多出来的算删 / 增
    const gapLeft = left.slice(i, pi)
    const gapRight = right.slice(j, pj)
    const shared = Math.min(gapLeft.length, gapRight.length)
    for (let k = 0; k < shared; k += 1) {
      pushChange(gapLeft[k], gapRight[k], i + k + 1, j + k + 1)
    }
    for (let k = shared; k < gapLeft.length; k += 1) {
      pushRemove(gapLeft[k], i + k + 1)
    }
    for (let k = shared; k < gapRight.length; k += 1) {
      pushAdd(gapRight[k], j + k + 1)
    }
    pushEqual(left[pi], pi + 1, pj + 1)
    i = pi + 1
    j = pj + 1
  }

  const restLeft = left.slice(i)
  const restRight = right.slice(j)
  const restShared = Math.min(restLeft.length, restRight.length)
  for (let k = 0; k < restShared; k += 1) {
    pushChange(restLeft[k], restRight[k], i + k + 1, j + k + 1)
  }
  for (let k = restShared; k < restLeft.length; k += 1) {
    pushRemove(restLeft[k], i + k + 1)
  }
  for (let k = restShared; k < restRight.length; k += 1) {
    pushAdd(restRight[k], j + k + 1)
  }

  return { blocks, added, removed, changed, unchanged }
}

/** 段内对比：先按字符算，超长段落退化成整段替换 */
function diffParagraph(left: string, right: string): { left: DiffSegment[]; right: DiffSegment[] } {
  if (left === right) {
    return { left: [{ text: left, type: 'equal' }], right: [{ text: right, type: 'equal' }] }
  }
  if (left.length * right.length > MAX_CHAR_PRODUCT) {
    return {
      left: [{ text: left, type: 'remove' }],
      right: [{ text: right, type: 'add' }]
    }
  }
  const ops = opsFromLcs(Array.from(left), Array.from(right))
  return { left: toSegments(ops, 'left'), right: toSegments(ops, 'right') }
}

/** 段落级 LCS，返回匹配到的下标对（升序） */
function lcsPairs(left: string[], right: string[]): Array<[number, number]> {
  if (!left.length || !right.length) {
    return []
  }
  if (left.length * right.length > MAX_PARAGRAPH_PRODUCT) {
    return sequentialPairs(left, right)
  }
  return pairsFromTable(left, right, (a, b) => a === b)
}

/** 规模过大时的兜底：按顺序配对，只比较值是否相同 */
function sequentialPairs(left: string[], right: string[]): Array<[number, number]> {
  const pairs: Array<[number, number]> = []
  const size = Math.min(left.length, right.length)
  for (let index = 0; index < size; index += 1) {
    if (left[index] === right[index]) {
      pairs.push([index, index])
    }
  }
  return pairs
}

function pairsFromTable<T>(left: T[], right: T[], same: (a: T, b: T) => boolean): Array<[number, number]> {
  const rows = left.length
  const cols = right.length
  const width = cols + 1
  const table = new Uint32Array((rows + 1) * width)
  for (let i = rows - 1; i >= 0; i -= 1) {
    for (let j = cols - 1; j >= 0; j -= 1) {
      table[i * width + j] = same(left[i], right[j])
        ? table[(i + 1) * width + j + 1] + 1
        : Math.max(table[(i + 1) * width + j], table[i * width + j + 1])
    }
  }
  const pairs: Array<[number, number]> = []
  let i = 0
  let j = 0
  while (i < rows && j < cols) {
    if (same(left[i], right[j])) {
      pairs.push([i, j])
      i += 1
      j += 1
    } else if (table[(i + 1) * width + j] >= table[i * width + j + 1]) {
      i += 1
    } else {
      j += 1
    }
  }
  return pairs
}

/** 字符级 LCS → 操作序列 */
function opsFromLcs(left: string[], right: string[]): Op[] {
  const pairs = pairsFromTable(left, right, (a, b) => a === b)
  const ops: Op[] = []
  let i = 0
  let j = 0
  for (const [pi, pj] of pairs) {
    while (i < pi) {
      push(ops, 'remove', left[i])
      i += 1
    }
    while (j < pj) {
      push(ops, 'add', right[j])
      j += 1
    }
    push(ops, 'equal', left[pi])
    i = pi + 1
    j = pj + 1
  }
  while (i < left.length) {
    push(ops, 'remove', left[i])
    i += 1
  }
  while (j < right.length) {
    push(ops, 'add', right[j])
    j += 1
  }
  return ops
}

function push(ops: Op[], type: DiffSegmentType, text: string) {
  const last = ops[ops.length - 1]
  if (last && last.type === type) {
    last.text += text
  } else {
    ops.push({ type, text })
  }
}

function toSegments(ops: Op[], side: 'left' | 'right'): DiffSegment[] {
  const segments: DiffSegment[] = []
  for (const op of ops) {
    if (side === 'left' && op.type === 'add') {
      continue
    }
    if (side === 'right' && op.type === 'remove') {
      continue
    }
    const last = segments[segments.length - 1]
    if (last && last.type === op.type) {
      last.text += op.text
    } else {
      segments.push({ text: op.text, type: op.type })
    }
  }
  return segments.length ? segments : [{ text: '', type: 'equal' }]
}

function sumLength(segments: DiffSegment[]): number {
  return segments.reduce((total, segment) => total + segment.text.length, 0)
}
