<template>
  <div class="bp-editor">
    <section class="bp-block">
      <div class="bp-block-head">
        <span class="bp-block-title">基本信息</span>
        <span class="bp-block-hint">可逐项修改，保存后即为小说最终设定</span>
      </div>

      <el-row :gutter="12">
        <el-col :span="10">
          <div class="field-label">书名</div>
          <el-input v-model="blueprint.title" maxlength="100" placeholder="给这本书起个名字" />
        </el-col>
        <el-col :span="7">
          <div class="field-label">题材</div>
          <el-select v-model="blueprint.genre" class="w-full" filterable allow-create clearable placeholder="选择或输入">
            <el-option v-for="item in GENRES" :key="item" :label="item" :value="item" />
          </el-select>
        </el-col>
        <el-col :span="7">
          <div class="field-label">文风基调</div>
          <el-input v-model="blueprint.style" maxlength="80" placeholder="人称＋语言质感＋笑点来源，如：第三人称限知，市井口语，诙谐，笑点靠人物嘴硬与误会" />
        </el-col>
      </el-row>

      <div class="field-label">标签</div>
      <el-select
        v-model="blueprint.tags"
        class="w-full"
        multiple
        filterable
        allow-create
        default-first-option
        :multiple-limit="8"
        placeholder="回车创建标签，最多 8 个"
      >
        <el-option v-for="item in TAG_SUGGESTIONS" :key="item" :label="item" :value="item" />
      </el-select>

      <div class="field-label">简介</div>
      <el-input
        v-model="blueprint.synopsis"
        type="textarea"
        :rows="4"
        maxlength="4000"
        show-word-limit
        placeholder="讲清主角是谁、遭遇什么、要做什么"
      />
    </section>

    <section class="bp-block">
      <div class="bp-block-head">
        <span class="bp-block-title">创作约束</span>
        <span class="bp-block-hint">作为硬要求注入此后所有 AI 生成</span>
      </div>
      <el-input
        v-model="constraintText"
        type="textarea"
        :rows="4"
        maxlength="2000"
        show-word-limit
        placeholder="每行一条，AI 会当成硬要求遵守。如：&#10;主角设定：被逐出宗门的杂役，背着师门旧案的嫌疑&#10;主角目标：找回失传的残卷，替师父翻案"
      />
    </section>

    <section class="bp-block">
      <div class="bp-block-head">
        <span class="bp-block-title">世界与故事</span>
        <span class="bp-block-hint">世界观与背景会作为后续大纲、正文生成的上下文</span>
      </div>

      <div class="field-label">世界观</div>
      <el-input
        v-model="blueprint.worldview"
        type="textarea"
        :rows="8"
        maxlength="8000"
        show-word-limit
        placeholder="世界基本规则、力量体系、地理格局、主要势力"
      />

      <div class="field-label">背景设定</div>
      <el-input
        v-model="blueprint.background"
        type="textarea"
        :rows="6"
        maxlength="6000"
        show-word-limit
        placeholder="时代背景、关键历史事件、故事开始时的局势"
      />

      <div class="field-label">核心冲突</div>
      <el-input v-model="blueprint.coreConflict" type="textarea" :rows="3" maxlength="3000" show-word-limit />

      <div class="field-label">核心看点（每行一条）</div>
      <el-input
        :model-value="highlightText"
        type="textarea"
        :rows="4"
        placeholder="一条一行，如：&#10;废柴逆袭，节奏快不憋屈&#10;旧书里的师父藏着惊天身份"
        @update:model-value="onHighlightsChange"
      />
    </section>

    <section class="bp-block">
      <div class="bp-block-head">
        <span class="bp-block-title">主角</span>
      </div>

      <el-row :gutter="12">
        <el-col :span="6">
          <div class="field-label">姓名</div>
          <el-input v-model="protagonist.name" maxlength="64" placeholder="主角姓名" />
        </el-col>
        <el-col :span="4">
          <div class="field-label">性别</div>
          <el-input v-model="protagonist.gender" maxlength="16" placeholder="男 / 女" />
        </el-col>
        <el-col :span="5">
          <div class="field-label">年龄</div>
          <el-input v-model="protagonist.ageDesc" maxlength="32" placeholder="如 十八岁" />
        </el-col>
        <el-col :span="9">
          <div class="field-label">身份职位</div>
          <el-input v-model="protagonist.identity" maxlength="255" placeholder="如 青云宗外门弟子" />
        </el-col>
      </el-row>

      <el-row :gutter="12">
        <el-col :span="12">
          <div class="field-label">外貌特征</div>
          <el-input v-model="protagonist.appearance" type="textarea" :rows="2" maxlength="2000" />
        </el-col>
        <el-col :span="12">
          <div class="field-label">性格</div>
          <el-input v-model="protagonist.personality" type="textarea" :rows="2" maxlength="2000" />
        </el-col>
      </el-row>

      <el-row :gutter="12">
        <el-col :span="12">
          <div class="field-label">核心动机</div>
          <el-input v-model="protagonist.motivation" type="textarea" :rows="2" maxlength="2000" />
        </el-col>
        <el-col :span="12">
          <div class="field-label">金手指 / 独有优势</div>
          <el-input v-model="protagonist.goldenFinger" type="textarea" :rows="2" maxlength="2000" />
        </el-col>
      </el-row>

      <div class="field-label">成长弧线</div>
      <el-input v-model="protagonist.arc" type="textarea" :rows="2" maxlength="2000" />
    </section>

    <section class="bp-block">
      <div class="bp-block-head">
        <span class="bp-block-title">配角与反派</span>
        <el-button size="small" plain :disabled="characters.length >= 12" @click="addCharacter">
          添加角色
        </el-button>
      </div>

      <div v-if="characters.length === 0" class="char-empty">
        暂无配角。可以点右上角「添加角色」，后续在设定库里也能补。
      </div>

      <div v-for="(item, index) in characters" :key="index" class="char-card">
        <div class="char-head">
          <span class="char-index">角色 {{ index + 1 }}</span>
          <el-button size="small" text type="danger" @click="removeCharacter(index)">移除</el-button>
        </div>

        <el-row :gutter="12">
          <el-col :span="6">
            <div class="field-label">姓名</div>
            <el-input v-model="item.name" maxlength="64" />
          </el-col>
          <el-col :span="5">
            <div class="field-label">定位</div>
            <el-select v-model="item.rolePos" class="w-full">
              <el-option label="配角" value="SUPPORTING" />
              <el-option label="反派" value="ANTAGONIST" />
              <el-option label="龙套" value="MINOR" />
            </el-select>
          </el-col>
          <el-col :span="5">
            <div class="field-label">性别</div>
            <el-input v-model="item.gender" maxlength="16" />
          </el-col>
          <el-col :span="8">
            <div class="field-label">身份职位</div>
            <el-input v-model="item.identity" maxlength="255" />
          </el-col>
        </el-row>

        <el-row :gutter="12">
          <el-col :span="12">
            <div class="field-label">性格</div>
            <el-input v-model="item.personality" type="textarea" :rows="2" maxlength="2000" />
          </el-col>
          <el-col :span="12">
            <div class="field-label">动机</div>
            <el-input v-model="item.motivation" type="textarea" :rows="2" maxlength="2000" />
          </el-col>
        </el-row>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { CharacterDraft, NovelBlueprint } from '@/api/bookshelf'

const blueprint = defineModel<NovelBlueprint>({ required: true })

const GENRES = ['玄幻', '仙侠', '都市', '悬疑', '科幻', '历史', '游戏', '轻小说', '武侠', '现实']
const TAG_SUGGESTIONS = ['升级流', '群像', '轻松', '无敌流', '单女主', '无女主', '复仇', '种田', '克苏鲁', '无限流']

const protagonist = computed<CharacterDraft>(() => blueprint.value.protagonist as CharacterDraft)

const characters = computed(() => blueprint.value.characters ?? [])

/** 创作约束：没填过时为 undefined，只有作者改过才写回蓝图（避免把已有约束清空） */
const constraintText = computed({
  get: () => blueprint.value.constraintText ?? '',
  set: (value: string) => {
    blueprint.value.constraintText = value
  }
})

const highlightText = computed(() => (blueprint.value.highlights ?? []).join('\n'))

function onHighlightsChange(value: string) {
  blueprint.value.highlights = value
    .split('\n')
    .map((item) => item.trim())
    .filter((item) => item.length > 0)
}

function addCharacter() {
  const item: CharacterDraft = {
    name: '',
    rolePos: 'SUPPORTING',
    identity: '',
    gender: '',
    personality: '',
    motivation: ''
  }
  blueprint.value.characters = [...(blueprint.value.characters ?? []), item]
}

function removeCharacter(index: number) {
  const next = [...(blueprint.value.characters ?? [])]
  next.splice(index, 1)
  blueprint.value.characters = next
}
</script>

<style scoped>
.bp-editor {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.bp-block {
  padding: 14px 16px;
  border: 1px solid var(--sf-border);
  border-radius: 8px;
  background: var(--sf-bg-subtle);
}

.bp-block-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
}

.bp-block-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--sf-text-primary);
}

.bp-block-hint {
  font-size: 12px;
  color: var(--sf-text-muted);
}

.field-label {
  margin: 10px 0 4px;
  font-size: 12px;
  color: var(--sf-text-regular);
}

.bp-block-head + .field-label,
.bp-block-head + .el-row .field-label {
  margin-top: 0;
}

.w-full {
  width: 100%;
}

.char-empty {
  padding: 10px 0;
  font-size: 12px;
  color: var(--sf-text-muted);
}

.char-card {
  padding: 10px 12px;
  margin-bottom: 10px;
  border: 1px dashed var(--sf-border);
  border-radius: 6px;
  background: var(--sf-bg-card);
}

.char-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.char-index {
  font-size: 12px;
  font-weight: 600;
  color: var(--sf-text-regular);
}
</style>
