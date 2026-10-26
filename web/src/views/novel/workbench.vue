<template>
  <div class="workbench" v-loading="loading">
    <aside class="wb-side">
      <div class="wb-book">
        <div class="wb-cover" :style="coverStyle">{{ coverWord }}</div>
        <div class="wb-book-meta">
          <h3 class="wb-title">{{ novel?.title || '加载中' }}</h3>
          <p class="wb-sub">{{ statusLabel }} · {{ novel?.wordCount ?? 0 }} 字 · {{ novel?.chapterCount ?? 0 }} 章</p>
        </div>
      </div>

      <el-menu :default-active="activeMenu" class="wb-menu" @select="onMenuSelect">
        <el-menu-item v-for="item in MENUS" :key="item.key" :index="item.key">
          <el-icon><component :is="item.icon" /></el-icon>
          <span>{{ item.label }}</span>
        </el-menu-item>
      </el-menu>

      <div class="wb-side-foot">
        <el-button text :icon="ChatDotRound" @click="agentOpen = true">智能助手</el-button>
        <el-button text :icon="ArrowLeft" @click="goBookshelf">返回书架</el-button>
      </div>
    </aside>

    <section class="wb-main">
      <!-- 基本信息 -->
      <div v-show="activeMenu === 'basic'" class="panel">
        <header class="panel-head">
          <div>
            <h2>基本信息</h2>
            <p>书籍资料与 AI 生成的世界观设定，可直接修改后保存。</p>
          </div>
          <div class="panel-actions">
            <el-button :loading="basicSaving" type="primary" @click="saveBasic">保存基本信息</el-button>
          </div>
        </header>

        <el-form label-width="88px" class="basic-form">
          <el-row :gutter="16">
            <el-col :span="12">
              <el-form-item label="书名" required>
                <el-input v-model="basic.title" maxlength="100" />
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="笔名">
                <el-input v-model="basic.penName" maxlength="64" />
              </el-form-item>
            </el-col>
          </el-row>
          <el-row :gutter="16">
            <el-col :span="8">
              <el-form-item label="题材">
                <el-select v-model="basic.genre" class="w-full" filterable allow-create clearable>
                  <el-option v-for="item in GENRES" :key="item" :label="item" :value="item" />
                </el-select>
              </el-form-item>
            </el-col>
            <el-col :span="8">
              <el-form-item label="状态">
                <el-select v-model="basic.status" class="w-full">
                  <el-option v-for="item in STATUS_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
                </el-select>
              </el-form-item>
            </el-col>
            <el-col :span="8">
              <el-form-item label="标签">
                <el-select v-model="basic.tags" class="w-full" multiple filterable allow-create :multiple-limit="8">
                  <el-option v-for="tag in basic.tags" :key="tag" :label="tag" :value="tag" />
                </el-select>
              </el-form-item>
            </el-col>
          </el-row>
          <el-form-item label="简介">
            <el-input v-model="basic.intro" type="textarea" :autosize="{ minRows: 3, maxRows: 8 }" maxlength="4000" show-word-limit />
          </el-form-item>
        </el-form>

        <div class="section-title">
          <span>设定</span>
          <el-button :loading="settingSaving" size="small" type="primary" plain @click="saveSettings">保存设定</el-button>
        </div>
        <el-form label-width="88px" class="basic-form">
          <el-form-item label="世界观">
            <el-input v-model="settings.WORLDVIEW" type="textarea" :autosize="{ minRows: 4, maxRows: 10 }" placeholder="世界规则、力量体系、势力格局" />
          </el-form-item>
          <el-form-item label="背景设定">
            <el-input v-model="settings.BACKGROUND" type="textarea" :autosize="{ minRows: 3, maxRows: 8 }" placeholder="时代背景、历史事件、当前局势" />
          </el-form-item>
          <el-form-item label="核心冲突">
            <el-input v-model="settings.CORE_CONFLICT" type="textarea" :autosize="{ minRows: 2, maxRows: 6 }" placeholder="贯穿全书的主要矛盾" />
          </el-form-item>
          <el-form-item label="核心看点">
            <el-input v-model="settings.HIGHLIGHT" type="textarea" :autosize="{ minRows: 2, maxRows: 6 }" placeholder="每行一条" />
          </el-form-item>
          <el-form-item label="文风基调">
            <el-input v-model="settings.STYLE" maxlength="200" />
          </el-form-item>
        </el-form>
      </div>

      <!-- 总纲 -->
      <div v-show="activeMenu === 'masterOutline'" class="panel">
        <header class="panel-head">
          <div>
            <h2>总纲</h2>
            <p>一段剧情走向对应多条章节大纲；生成章节大纲时可关联总纲，保证不脱离它的范围。</p>
          </div>
          <div class="panel-actions">
            <el-button :icon="Refresh" :loading="masterOutlineLoading" circle @click="loadMasterOutlines" />
            <el-button :icon="MagicStick" :loading="masterOutlineGenerating" @click="openMasterOutlineAiDialog">
              AI 生成总纲
            </el-button>
            <el-button type="primary" :icon="Plus" @click="openMasterOutlineDialog()">新增总纲</el-button>
          </div>
        </header>

        <div v-if="!masterOutlines.length" class="empty-block">
          <p>还没有总纲。总纲决定全书剧情如何分段推进，建议先写总纲，再按总纲生成章节大纲。</p>
        </div>
        <div v-else class="mo-list">
          <article v-for="item in masterOutlines" :key="item.id" class="mo-item">
            <div class="mo-head">
              <span class="mo-index">总纲 {{ item.sortNo }}</span>
              <span class="mo-title">{{ item.title }}</span>
              <el-tag size="small" effect="plain">{{ item.chapterCount }} 条大纲</el-tag>
            </div>
            <p class="mo-content">{{ item.content || '还没有内容' }}</p>
            <div class="mo-actions">
              <el-button text size="small" @click="generateOutlineForMaster(item)">按此生成大纲</el-button>
              <el-button text size="small" @click="openMasterOutlineDialog(item)">编辑</el-button>
              <el-button text size="small" type="danger" @click="removeMasterOutline(item)">删除</el-button>
            </div>
          </article>
        </div>
      </div>

      <!-- 大纲 -->
      <div v-show="activeMenu === 'outline'" class="panel">
        <header class="panel-head">
          <div>
            <h2>大纲</h2>
            <p>按卷 / 章维护故事骨架，正文在「正文」菜单里写。</p>
          </div>
          <div class="panel-actions">
            <el-button :icon="Refresh" :loading="chapterLoading" circle @click="loadChapters" />
            <el-button :icon="MagicStick" :disabled="aiOutlineGenerating" @click="openAiOutlineDialog">
              AI 生成大纲
            </el-button>
            <el-button type="primary" :icon="Plus" @click="openChapterDialog()">新增章节</el-button>
          </div>
        </header>

        <SearchSources
          v-if="outlineSearch"
          :bundle="outlineSearch"
          title="本次大纲生成的参考资料"
          @close="outlineSearch = null"
        />

        <div v-if="!chapters.length" class="empty-block">
          <p>还没有章节。可以让 AI 按设定生成一批大纲，也可以手动添加。</p>
        </div>
        <div v-for="group in volumeGroups" :key="group.volumeNo" class="volume-block">
          <div class="volume-head">
            <span class="volume-name">{{ group.volumeTitle || `第 ${group.volumeNo} 卷` }}</span>
            <span class="volume-count">{{ group.items.length }} 章</span>
          </div>
          <div v-for="chapter in group.items" :key="chapter.id" class="chapter-row">
            <span class="chapter-no">第 {{ chapter.chapterNo }} 章</span>
            <div class="chapter-main">
              <div class="chapter-title">
                <span class="chapter-title-text">{{ chapter.title }}</span>
                <el-tag
                  v-if="masterOutlineTitle(chapter.masterOutlineId)"
                  size="small"
                  type="success"
                  effect="plain"
                >{{ masterOutlineTitle(chapter.masterOutlineId) }}</el-tag>
              </div>
              <div class="chapter-summary">{{ chapter.summary || '还没有摘要' }}</div>
              <div v-if="chapter.participants?.length || chapter.events?.length" class="chapter-links">
                <el-tag
                  v-for="person in chapter.participants"
                  :key="'p-' + person.id"
                  size="small"
                  effect="plain"
                >{{ person.name }}</el-tag>
                <el-tag
                  v-for="event in chapter.events"
                  :key="'e-' + event.id"
                  size="small"
                  type="warning"
                  effect="plain"
                >{{ event.title }}</el-tag>
              </div>
            </div>
            <el-tag size="small" :type="chapterStatusType(chapter.status)" effect="plain">{{ chapterStatusLabel(chapter.status) }}</el-tag>
            <div class="row-actions">
              <el-button text size="small" @click="openChapterDialog(chapter)">编辑</el-button>
              <el-button text size="small" type="danger" @click="removeChapter(chapter)">删除</el-button>
            </div>
          </div>
        </div>
      </div>

      <!-- 人物 -->
      <div v-show="activeMenu === 'characters'" class="panel">
        <header class="panel-head">
          <div>
            <h2>人物</h2>
            <p>主角置顶显示；「背包」记录每个人身上的物品与数量，「关系视图」查看人物关系。背包数量随章节分析自动更新。</p>
          </div>
          <div class="panel-actions">
            <template v-if="characterTab === 'relations'">
              <el-button :icon="Refresh" :loading="relationLoading" circle @click="loadRelations" />
              <el-button type="primary" :icon="Connection" :disabled="characters.length < 2" @click="openRelationDialog()">
                新增关系
              </el-button>
            </template>
            <template v-else-if="characterTab === 'list'">
              <el-button :icon="MagicStick" :disabled="aiGenerating" @click="openAiCharacterDialog">AI 创建角色</el-button>
              <el-button type="primary" :icon="Plus" @click="openCharacterDialog()">新增角色</el-button>
            </template>
          </div>
        </header>

        <el-tabs v-model="characterTab" class="character-tabs" @tab-change="onCharacterTabChange">
          <el-tab-pane label="人物档案" name="list">
            <div v-if="!characters.length" class="empty-block">
              <p>还没有角色。可以用「创建小说」时 AI 生成的档案，也可以手动添加。</p>
            </div>
            <div v-else class="character-grid">
              <article v-for="(person, index) in characters" :key="person.id || index" class="character-card">
                <div class="character-head">
                  <span class="character-name">{{ person.name }}</span>
                  <el-tag size="small" :type="roleType(person.rolePos)" effect="plain">{{ roleLabel(person.rolePos) }}</el-tag>
                </div>
                <p class="character-line">{{ person.identity || '身份待补充' }}<span v-if="person.gender"> · {{ person.gender }}</span><span v-if="person.ageDesc"> · {{ person.ageDesc }}</span></p>
                <p class="character-desc">{{ person.personality || person.motivation || '还没有性格与动机描述' }}</p>
                <div v-if="bagSummary(person.id || '')" class="character-bag">
                  <span class="muted">背包：</span>{{ bagSummary(person.id || '') }}
                </div>
                <div class="character-foot">
                  <el-button text size="small" @click="openCharacterDialog(person)">编辑</el-button>
                  <el-button text size="small" @click="openBag(person)">背包</el-button>
                  <el-button text size="small" @click="openEntityHistory('CHARACTER', person)">履历</el-button>
                  <el-button text size="small" type="danger" @click="removeCharacter(person)">删除</el-button>
                </div>
              </article>
            </div>
          </el-tab-pane>

          <el-tab-pane label="关系视图" name="relations">
            <div v-if="characters.length < 2" class="empty-block">
              <p>至少需要两个角色才能建立关系，先到「人物档案」里补几个角色。</p>
            </div>
            <div v-else class="relation-layout">
              <div class="relation-panel relation-panel-graph">
                <div class="relation-panel-head">
                  <span>关系连线图</span>
                  <span class="muted">关系交叉也能看清，节点可拖动</span>
                </div>
                <RelationGraph :characters="characters" :relations="relations" />
              </div>

              <div class="relation-panel">
                <div class="relation-panel-head">
                  <span>关系列表</span>
                  <span class="muted">{{ relations.length }} 条</span>
                </div>
                <el-table v-if="relations.length" :data="relations" size="small" class="asset-table">
                  <el-table-column label="角色 A" min-width="92">
                    <template #default="{ row }">{{ row.fromCharacterName }}</template>
                  </el-table-column>
                  <el-table-column label="关系" width="86" align="center">
                    <template #default="{ row }">
                      <el-tag size="small" effect="plain">{{ row.relationLabel }}</el-tag>
                    </template>
                  </el-table-column>
                  <el-table-column label="角色 B" min-width="92">
                    <template #default="{ row }">{{ row.toCharacterName }}</template>
                  </el-table-column>
                  <el-table-column prop="description" label="说明" min-width="130" show-overflow-tooltip>
                    <template #default="{ row }">{{ row.description || '—' }}</template>
                  </el-table-column>
                  <el-table-column label="操作" width="110" align="right">
                    <template #default="{ row }">
                      <el-button text size="small" @click="openRelationDialog(row)">编辑</el-button>
                      <el-button text size="small" type="danger" @click="removeRelation(row)">删除</el-button>
                    </template>
                  </el-table-column>
                </el-table>
                <div v-else class="empty-block small">
                  <p>还没有关系，点右上角「新增关系」建立第一条。</p>
                </div>
              </div>

            </div>
          </el-tab-pane>

          <el-tab-pane label="背包" name="bag">
            <div v-if="!characters.length" class="empty-block">
              <p>还没有角色，先到「人物档案」建几个人再来看背包。</p>
            </div>
            <div v-else class="bag-layout">
              <aside class="bag-side">
                <div class="bag-side-head">
                  <span>人物</span>
                  <el-button text size="small" :icon="Refresh" :loading="bagLoading" @click="loadBagAll" />
                </div>
                <el-input
                  v-model="bagKeyword"
                  size="small"
                  placeholder="搜索姓名"
                  clearable
                  class="bag-search"
                />
                <div class="bag-person-list">
                  <button
                    v-for="person in bagPersons"
                    :key="person.id"
                    class="bag-person"
                    :class="{ active: bagCharacterId === person.id }"
                    type="button"
                    @click="bagCharacterId = person.id || ''"
                  >
                    <span class="bag-person-name">{{ person.name }}</span>
                    <span class="bag-person-count">{{ (bagMap[person.id || ''] || []).length }}</span>
                  </button>
                </div>
              </aside>

              <section class="bag-main">
                <template v-if="bagCharacter">
                  <div class="bag-main-head">
                    <div>
                      <h3>{{ bagCharacter.name }} 的背包</h3>
                      <p class="muted">
                        共 {{ bagRows.length }} 条；随章节分析自动更新数量，绑定物品档案的条目共享它的描述与来历。
                      </p>
                    </div>
                    <el-button text size="small" @click="openEntityHistory('CHARACTER', bagCharacter)">人物履历</el-button>
                  </div>

                  <div class="asset-form bag-form">
                    <el-select v-model="bagDraft.entryType" style="width: 96px">
                      <el-option label="物品档案" value="ITEM" />
                      <el-option label="货币" value="CURRENCY" />
                      <el-option label="其它" value="MISC" />
                    </el-select>
                    <el-select
                      v-if="bagDraft.entryType === 'ITEM'"
                      v-model="bagDraft.itemId"
                      filterable
                      placeholder="选择物品档案"
                      style="width: 220px"
                    >
                      <el-option v-for="item in bagAddableItems" :key="item.id" :label="item.name" :value="item.id" />
                    </el-select>
                    <el-input
                      v-else
                      v-model="bagDraft.name"
                      placeholder="名称，如 现金 / 能量块 / 歌曲版权"
                      maxlength="100"
                      style="width: 220px"
                    />
                    <el-input-number
                      v-model="bagDraft.quantity"
                      :min="0"
                      :controls="false"
                      placeholder="数量"
                      style="width: 100px"
                    />
                    <el-input v-model="bagDraft.unit" placeholder="单位" maxlength="16" style="width: 80px" />
                    <el-button type="primary" :loading="bagSaving" @click="addBagEntry">加入背包</el-button>
                  </div>

                  <el-table
                    :data="bagRows"
                    size="small"
                    class="asset-table"
                    empty-text="背包还是空的：可以从物品档案挂入，也可以只记名字和数量（钱、能量块、版权…）"
                  >
                    <el-table-column label="类型" width="86">
                      <template #default="{ row }">
                        <el-tag
                          size="small"
                          :type="row.entryType === 'ITEM' ? 'primary' : row.entryType === 'CURRENCY' ? 'warning' : 'info'"
                        >
                          {{ row.entryTypeLabel }}
                        </el-tag>
                      </template>
                    </el-table-column>
                    <el-table-column label="名称" min-width="180">
                      <template #default="{ row }">
                        <div class="bag-name">
                          <span>{{ row.name }}</span>
                          <el-tag v-if="row.itemId" size="small" effect="plain" type="success">已绑定物品</el-tag>
                          <span v-if="row.itemDescription" class="bag-item-desc">{{ row.itemDescription }}</span>
                        </div>
                      </template>
                    </el-table-column>
                    <el-table-column label="数量" width="100">
                      <template #default="{ row }">
                        <el-input-number v-model="row.quantity" :min="0" :controls="false" size="small" style="width: 100%" />
                      </template>
                    </el-table-column>
                    <el-table-column label="单位" width="76">
                      <template #default="{ row }"><el-input v-model="row.unit" size="small" maxlength="16" /></template>
                    </el-table-column>
                    <el-table-column label="状态" width="104">
                      <template #default="{ row }">
                        <el-select v-model="row.status" size="small">
                          <el-option
                            v-for="item in INVENTORY_STATUS_OPTIONS"
                            :key="item.value"
                            :label="item.label"
                            :value="item.value"
                          />
                        </el-select>
                      </template>
                    </el-table-column>
                    <el-table-column label="备注" min-width="130">
                      <template #default="{ row }"><el-input v-model="row.note" size="small" maxlength="1000" /></template>
                    </el-table-column>
                    <el-table-column label="操作" width="104" fixed="right">
                      <template #default="{ row }">
                        <el-button link type="primary" :loading="bagSaving" @click="saveBagRow(row)">保存</el-button>
                        <el-button link type="danger" @click="removeBagRow(row)">删除</el-button>
                      </template>
                    </el-table-column>
                  </el-table>
                </template>
                <div v-else class="empty-block small">
                  <p>左侧选一个人物查看背包。</p>
                </div>
              </section>
            </div>
          </el-tab-pane>
        </el-tabs>
      </div>
      <div v-show="activeMenu === 'factions'" class="panel">
        <header class="panel-head">
          <div>
            <h2>势力</h2>
            <p>公司、帮派、国家、组织都算势力；一个人可以在多个势力任职，职位自由填写。</p>
          </div>
          <div class="panel-actions">
            <el-button :icon="Refresh" :loading="factionLoading" circle @click="loadFactions" />
            <el-button type="primary" :icon="Plus" @click="openFactionDialog()">新增势力</el-button>
          </div>
        </header>

        <div v-if="!factions.length" class="empty-block">
          <p>还没有势力。先建一个公司、帮派或国家，再把角色安排进去。</p>
        </div>
        <div v-else class="faction-grid">
          <article v-for="faction in factions" :key="faction.id" class="faction-card">
            <div class="faction-head">
              <span class="faction-name">{{ faction.name }}</span>
              <el-tag size="small" :type="factionStatusType(faction.status)" effect="plain">
                {{ faction.statusLabel }}
              </el-tag>
            </div>
            <div class="faction-meta">
              <el-tag v-if="faction.category" size="small" type="info" effect="plain">{{ faction.category }}</el-tag>
              <span class="muted">{{ faction.memberCount }} 人</span>
            </div>
            <p class="faction-line"><span class="line-label">范围</span>{{ faction.scope || '未填写' }}</p>
            <p class="faction-line"><span class="line-label">备注</span>{{ faction.remark || '—' }}</p>
            <div class="faction-members">
              <el-tag
                v-for="member in faction.members.slice(0, 4)"
                :key="member.id"
                size="small"
                effect="plain"
                class="member-tag"
              >
                {{ member.name }} · {{ member.position }}
              </el-tag>
              <span v-if="faction.members.length > 4" class="muted">+{{ faction.members.length - 4 }}</span>
              <span v-if="!faction.members.length" class="muted">还没有成员</span>
            </div>
            <div class="faction-foot">
              <el-button text size="small" @click="openMemberDialog(faction)">成员</el-button>
              <el-button text size="small" @click="openEntityHistory('FACTION', faction)">履历</el-button>
              <el-button text size="small" @click="openFactionDialog(faction)">编辑</el-button>
              <el-button text size="small" type="danger" @click="removeFaction(faction)">删除</el-button>
            </div>
          </article>
        </div>
      </div>

      <!-- 事件 -->
      <div v-show="activeMenu === 'timeline'" class="panel">
        <header class="panel-head">
          <div>
            <h2>事件</h2>
            <p>事件只能由 AI 生成：给它一个方向，它会补齐可直接写进正文的事件卡；状态用于标记推进进度。</p>
          </div>
          <div class="panel-actions">
            <el-button :icon="Refresh" :loading="timelineLoading" circle @click="loadTimeline" />
            <el-button type="primary" :icon="MagicStick" :disabled="aiEventGenerating" @click="openAiEventDialog">
              AI 生成事件
            </el-button>
          </div>
        </header>

        <SearchSources
          v-if="eventSearch"
          :bundle="eventSearch"
          title="本次事件生成的参考资料"
          @close="eventSearch = null"
        />

        <el-table v-if="timelines.length" :data="timelines" class="asset-table">
          <el-table-column label="时间" width="130">
            <template #default="{ row }">{{ row.eventTime || '—' }}</template>
          </el-table-column>
          <el-table-column prop="title" label="事件" min-width="160" />
          <el-table-column label="参与角色" min-width="170">
            <template #default="{ row }">
              <span v-if="!(row.participants || []).length" class="muted">—</span>
              <template v-else>
                <el-tag
                  v-for="person in row.participants.slice(0, 3)"
                  :key="person.id"
                  size="small"
                  effect="plain"
                  class="participant-tag"
                >
                  {{ person.name }}
                </el-tag>
                <span v-if="row.participants.length > 3" class="muted">+{{ row.participants.length - 3 }}</span>
              </template>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="104">
            <template #default="{ row }">
              <el-tag size="small" :type="eventStatusType(row.status)" effect="plain">
                {{ row.statusLabel || eventStatusLabel(row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="description" label="描述" min-width="200" show-overflow-tooltip />
          <el-table-column label="操作" width="130" align="right">
            <template #default="{ row }">
              <el-button text size="small" @click="openTimelineDialog(row)">编辑</el-button>
              <el-button text size="small" type="danger" @click="removeTimeline(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div v-else class="empty-block">
          <p>还没有事件。点右上角「AI 生成事件」，描述一下你想要什么剧情。</p>
        </div>
      </div>

      <!-- 物品 -->
      <div v-show="activeMenu === 'items'" class="panel">
        <header class="panel-head">
          <div>
            <h2>物品</h2>
            <p>法宝、信物、关键线索都记在这里；数量可留空，状态用来标记物品当前是否还在。</p>
          </div>
          <div class="panel-actions">
            <el-button :icon="Refresh" :loading="itemLoading" circle @click="loadItems" />
            <el-button :icon="MagicStick" :disabled="aiItemGenerating" @click="openAiItemDialog">
              AI 创建物品
            </el-button>
            <el-button type="primary" :icon="Plus" @click="openItemDialog()">新增物品</el-button>
          </div>
        </header>

        <el-table v-if="items.length" :data="items" class="asset-table">
          <el-table-column type="expand" width="36">
            <template #default="{ row }">
              <div class="item-detail">
                <div class="detail-block">
                  <span class="detail-label">物品描述</span>
                  <p>{{ row.description || '尚未填写' }}</p>
                </div>
                <div class="detail-block">
                  <span class="detail-label">说明</span>
                  <p>{{ row.origin || '尚未填写' }}</p>
                </div>
              </div>
            </template>
          </el-table-column>
          <el-table-column prop="name" label="名称" width="140" show-overflow-tooltip />
          <el-table-column label="类别" width="100">
            <template #default="{ row }">{{ row.category || '—' }}</template>
          </el-table-column>
          <el-table-column label="数量" width="84" align="center">
            <template #default="{ row }">
              <span v-if="row.quantity !== null && row.quantity !== undefined">{{ row.quantity }}</span>
              <span v-else class="text-muted">—</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="96">
            <template #default="{ row }">
              <el-tag size="small" :type="itemStatusType(row.status)" effect="plain">
                {{ row.statusLabel || itemStatusLabel(row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="持有者" width="120">
            <template #default="{ row }">{{ row.owner || '—' }}</template>
          </el-table-column>
          <el-table-column prop="description" label="物品描述" min-width="220" show-overflow-tooltip>
            <template #default="{ row }">{{ row.description || '—' }}</template>
          </el-table-column>
          <el-table-column label="操作" width="180" align="right">
            <template #default="{ row }">
              <el-button text size="small" @click="openItemDialog(row)">编辑</el-button>
              <el-button text size="small" @click="openEntityHistory('ITEM', row)">履历</el-button>
              <el-button text size="small" type="danger" @click="removeItem(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div v-else class="empty-block">
          <p>还没有物品道具。可以让 AI 按剧情需要生成一批，也可以手动添加。</p>
        </div>
      </div>

      <!-- 正文 -->
      <div v-show="activeMenu === 'content'" class="panel panel-content">
        <header class="panel-head">
          <div>
            <h2>正文</h2>
            <p>选择章节开始写作，保存后会同步字数与状态。</p>
          </div>
          <div class="panel-actions">
            <el-button :icon="Refresh" :loading="chapterLoading" circle @click="loadChapters" />
            <el-button type="primary" :icon="Plus" @click="openChapterDialog()">新增章节</el-button>
          </div>
        </header>

        <div class="content-layout">
          <div class="content-list">
            <div v-if="!chapters.length" class="empty-block small">
              <p>还没有章节</p>
            </div>
            <button
              v-for="chapter in chapters"
              :key="chapter.id"
              type="button"
              class="content-item"
              :class="{ active: chapter.id === activeChapterId }"
              @click="selectChapter(chapter)"
            >
              <span class="content-item-title">第 {{ chapter.chapterNo }} 章 {{ chapter.title }}</span>
              <span class="content-item-meta">{{ chapter.wordCount || 0 }} 字 · {{ chapterStatusLabel(chapter.status) }}</span>
            </button>
          </div>

          <div class="content-editor">
            <template v-if="activeChapterId">
              <div class="editor-head">
                <span class="editor-title">{{ editingChapter?.volumeTitle || '' }} 第 {{ editingChapter?.chapterNo }} 章</span>
                <div class="editor-actions">
                  <el-button size="small" :icon="MagicStick" :disabled="aiContentGenerating || contentLocked" @click="openAiContentDialog">
                    AI 写本章
                  </el-button>
                  <el-select
                    v-model="contentStatus"
                    size="small"
                    class="status-mini"
                    :disabled="contentEditing || statusSaving"
                    @change="changeContentStatus"
                  >
                    <el-option v-for="item in CHAPTER_STATUS" :key="item.value" :label="item.label" :value="item.value" />
                  </el-select>
                  <span class="editor-words">{{ contentWords }} 字</span>
                  <template v-if="contentEditing">
                    <el-button size="small" @click="cancelContentEdit">取消</el-button>
                    <el-button size="small" type="primary" :loading="contentSaving" @click="saveContent">保存正文</el-button>
                  </template>
                  <el-tooltip
                    v-else
                    :disabled="!contentLocked"
                    content="本章已标记为「已完成」，正文已锁定；先把状态改回草稿或写作中再编辑"
                    placement="top"
                  >
                    <span>
                      <el-button
                        size="small"
                        type="primary"
                        :icon="EditPen"
                        :disabled="contentLocked || statusSaving"
                        @click="startContentEdit"
                      >编辑</el-button>
                    </span>
                  </el-tooltip>
                </div>
              </div>
              <div v-if="editingChapter?.participants?.length || editingChapter?.events?.length" class="chapter-links editor-links">
                <el-tag
                  v-for="person in editingChapter?.participants || []"
                  :key="'ep-' + person.id"
                  size="small"
                  effect="plain"
                >{{ person.name }}</el-tag>
                <el-tag
                  v-for="event in editingChapter?.events || []"
                  :key="'ee-' + event.id"
                  size="small"
                  type="warning"
                  effect="plain"
                >{{ event.title }}</el-tag>
              </div>
              <SearchSources
                v-if="contentSearch"
                :bundle="contentSearch"
                title="本次正文生成的参考资料"
                @close="contentSearch = null"
              />
              <h3 v-if="!contentEditing" class="editor-title-text">{{ editingChapterTitle || '未命名章节' }}</h3>
              <el-input v-else v-model="editingChapterTitle" class="editor-title-input" placeholder="章节标题" maxlength="200" />

              <div v-if="contentLocked && !contentEditing" class="content-lock-hint">
                <el-icon><Lock /></el-icon>
                <span>本章已标记为「已完成」，正文已锁定，仅供查看。如需修改，请先把上方状态改回「草稿」或「写作中」。</span>
              </div>

              <el-input
                v-if="contentEditing"
                v-model="contentDraft"
                type="textarea"
                :autosize="{ minRows: 18, maxRows: 40 }"
                class="editor-textarea"
                placeholder="从这里开始写正文……"
              />
              <div v-else class="content-readonly">
                <p v-for="(para, index) in contentParagraphs" :key="index">{{ para }}</p>
                <p v-if="!contentParagraphs.length" class="content-readonly-empty">
                  {{ contentLocked ? '本章还没有正文。' : '本章还没有正文，点右上角「编辑」开始写。' }}
                </p>
              </div>
            </template>
            <div v-else class="empty-block">
              <p>先在左侧选择一章，或新建一章开始写作。</p>
            </div>
          </div>
        </div>
      </div>
      <!-- 分析 -->
      <div v-show="activeMenu === 'analysis'" class="panel">
        <header class="panel-head">
          <div>
            <h2>分析</h2>
            <p>
              按章节顺序分析：前面的章节分析完成后，后面的章节才能开始。点行展开该章的分析结果；每次分析会补齐新人物、
              写入变更履历、给事件收尾，并给出字数、大纲贴合与 AI 味结论。
            </p>
          </div>
          <div class="panel-actions">
            <el-button :icon="Refresh" :loading="analysisLoading" circle @click="loadAnalysisPanel" />
            <el-button
              type="primary"
              :icon="DataAnalysis"
              :loading="analysisRunning"
              :disabled="!nextAnalyzableChapter"
              @click="runNextAnalysis"
            >
              分析下一章
            </el-button>
          </div>
        </header>

        <div class="analysis-bar">
          <div class="analysis-chapter">
            <span class="analysis-label">分析进度</span>
            <strong class="analysis-chapter-title">{{ analyzedCount }} / {{ analysisRows.length }} 章</strong>
            <el-progress
              class="analysis-progress"
              :percentage="analysisPercent"
              :stroke-width="8"
              :show-text="false"
              color="#2563eb"
            />
            <el-tag v-if="nextAnalyzableChapter" size="small" type="success" effect="plain">
              下一章：第 {{ nextAnalyzableChapter.chapterNo }} 章 {{ nextAnalyzableChapter.title }}
            </el-tag>
            <el-tag v-else-if="blockedChapter" size="small" type="danger" effect="plain">
              第 {{ blockedChapter.chapterNo }} 章 正文为空，后续章节无法分析
            </el-tag>
            <el-tag v-else-if="analysisRows.length" size="small" type="info" effect="plain">没有待分析的章节</el-tag>
            <span v-else class="muted">还没有章节</span>
          </div>
          <div class="analysis-target">
            <span class="analysis-label">目标字数</span>
            <el-input-number
              v-model="analysisTargetWords"
              :min="300"
              :max="6000"
              :step="100"
              size="small"
              controls-position="right"
              @change="analysisTargetTouched = true"
            />
            <span class="muted">±15% 记为达标</span>
          </div>
        </div>

        <el-table
          v-if="analysisRows.length"
          :data="analysisRows"
          row-key="chapterId"
          class="analysis-table"
          :expand-row-keys="expandedKeys"
          @row-click="toggleAnalysisRow"
        >
          <el-table-column type="expand">
            <template #default="{ row }">
              <div class="analysis-expand">
                <p v-if="expandedLoadingId === row.chapterId" class="muted analysis-empty">正在读取分析报告…</p>
                <template v-else-if="analysisReport && analysisReport.chapterId === row.chapterId">
                  <div class="analysis-scores">
                    <div class="score-card">
                      <el-progress
                        type="dashboard"
                        :percentage="analysisReport.outlineScore"
                        :width="104"
                        :stroke-width="10"
                        :color="outlineColor"
                      />
                      <p class="score-title">大纲贴合度</p>
                      <p class="score-verdict">{{ analysisReport.outlineVerdict }}</p>
                    </div>
                    <div class="score-card">
                      <el-progress
                        type="dashboard"
                        :percentage="analysisReport.aiScore"
                        :width="104"
                        :stroke-width="10"
                        :color="aiColor"
                      />
                      <p class="score-title">AI 率（0 = 纯 AI 味）</p>
                      <p class="score-verdict">{{ analysisReport.aiVerdict }}</p>
                    </div>
                    <div class="score-card score-word">
                      <div class="word-figure">
                        <strong>{{ analysisReport.wordCount }}</strong>
                        <span class="muted">/ {{ analysisReport.targetWords }} 字</span>
                      </div>
                      <el-tag :type="analysisReport.wordPass ? 'success' : 'warning'" effect="plain">
                        {{ analysisReport.wordLabel }}
                      </el-tag>
                      <p class="word-range">合理区间 {{ analysisReport.minWords }}~{{ analysisReport.maxWords }} 字</p>
                      <p class="score-verdict">{{ analysisReport.wordComment }}</p>
                    </div>
                  </div>

                  <el-alert
                    v-if="analysisReport.warnings?.length"
                    class="analysis-warnings"
                    type="warning"
                    :closable="false"
                    show-icon
                    title="处理提示"
                  >
                    <p v-for="(warning, index) in analysisReport.warnings" :key="'warn-' + index">· {{ warning }}</p>
                  </el-alert>

                  <div class="analysis-grid">
                    <section class="analysis-section">
                      <div class="section-head">
                        <span>大纲贴合</span>
                        <el-tag
                          size="small"
                          :type="analysisReport.outlineScore >= 75 ? 'success' : analysisReport.outlineScore >= 60 ? 'warning' : 'danger'"
                          effect="plain"
                        >
                          {{ analysisReport.outlineScore }} 分
                        </el-tag>
                      </div>
                      <div class="outline-columns">
                        <div>
                          <p class="list-title ok">做到了</p>
                          <ul class="analysis-list">
                            <li v-for="(hit, index) in analysisReport.outlineHits" :key="'hit-' + index">{{ hit }}</li>
                            <li v-if="!analysisReport.outlineHits?.length" class="muted">模型没有给出正面项</li>
                          </ul>
                        </div>
                        <div>
                          <p class="list-title miss">没做到 / 写歪了</p>
                          <ul class="analysis-list">
                            <li v-for="(miss, index) in analysisReport.outlineMisses" :key="'miss-' + index">{{ miss }}</li>
                            <li v-if="!analysisReport.outlineMisses?.length" class="muted">没有明显偏离</li>
                          </ul>
                        </div>
                      </div>
                      <p class="list-title suggestion">修改建议</p>
                      <ul class="analysis-list">
                        <li v-for="(item, index) in analysisReport.outlineSuggestions" :key="'sug-' + index">{{ item }}</li>
                        <li v-if="!analysisReport.outlineSuggestions?.length" class="muted">—</li>
                      </ul>
                    </section>

                    <section class="analysis-section">
                      <div class="section-head">
                        <span>AI 味检测</span>
                        <el-tag
                          size="small"
                          :type="analysisReport.aiScore >= 70 ? 'success' : analysisReport.aiScore >= 45 ? 'warning' : 'danger'"
                          effect="plain"
                        >
                          {{ analysisReport.aiScore }} 分
                        </el-tag>
                      </div>
                      <p class="ai-verdict">{{ analysisReport.aiVerdict }}</p>
                      <div v-if="analysisReport.aiSignals?.length" class="signal-list">
                        <div v-for="(signal, index) in analysisReport.aiSignals" :key="'as-' + index" class="signal-row">
                          <span class="signal-name">{{ signal.name }}</span>
                          <span class="signal-detail">{{ signal.detail }}</span>
                        </div>
                      </div>
                      <div v-if="analysisReport.aiExamples?.length" class="quote-list">
                        <p v-for="(quote, index) in analysisReport.aiExamples" :key="'ex-' + index" class="quote-line">「{{ quote }}」</p>
                      </div>
                      <p class="list-title">本地统计</p>
                      <div class="signal-list">
                        <div v-for="(signal, index) in analysisReport.localSignals" :key="'ls-' + index" class="signal-row">
                          <span class="signal-name">{{ signal.name }}</span>
                          <span class="signal-detail">{{ signal.value }}</span>
                          <el-tag size="small" effect="plain" :type="signalTagType(signal.level)">{{ signalLevelLabel(signal.level) }}</el-tag>
                        </div>
                      </div>
                    </section>
                  </div>

                  <section class="analysis-section">
                    <div class="section-head">
                      <span>本次同步到档案</span>
                      <span class="muted">
                        新增人物 {{ analysisReport.newCharacters?.length || 0 }} 个 · 变更履历
                        {{ analysisReport.changes?.length || 0 }} 条 · 事件更新 {{ changedEventCount }} 条
                      </span>
                    </div>

                    <p class="list-title">新增人物</p>
                    <el-table v-if="analysisReport.newCharacters?.length" :data="analysisReport.newCharacters" size="small" class="asset-table">
                      <el-table-column prop="name" label="姓名" width="120" />
                      <el-table-column label="定位" width="80">
                        <template #default="{ row: person }">{{ person.roleLabel || '—' }}</template>
                      </el-table-column>
                      <el-table-column label="身份" width="160" show-overflow-tooltip>
                        <template #default="{ row: person }">{{ person.identity || '—' }}</template>
                      </el-table-column>
                      <el-table-column label="出场依据" min-width="200" show-overflow-tooltip>
                        <template #default="{ row: person }">{{ person.reason || '—' }}</template>
                      </el-table-column>
                    </el-table>
                    <p v-else class="muted analysis-empty">本章没有识别出需要新增的人物。</p>

                    <p class="list-title">变更履历</p>
                    <el-table v-if="analysisReport.changes?.length" :data="analysisReport.changes" size="small" class="asset-table">
                      <el-table-column label="类型" width="70">
                        <template #default="{ row: change }">{{ change.entityTypeLabel }}</template>
                      </el-table-column>
                      <el-table-column prop="entityName" label="对象" width="110" show-overflow-tooltip />
                      <el-table-column prop="fieldLabel" label="字段" width="100" />
                      <el-table-column label="原状态 → 现状态" min-width="190">
                        <template #default="{ row: change }">
                          <span class="state-before">{{ change.beforeState || '—' }}</span>
                          <span class="state-arrow">→</span>
                          <span class="state-after">{{ change.afterState || '—' }}</span>
                        </template>
                      </el-table-column>
                      <el-table-column label="依据" min-width="220" show-overflow-tooltip>
                        <template #default="{ row: change }">{{ change.evidence || change.reason || '—' }}</template>
                      </el-table-column>
                    </el-table>
                    <p v-else class="muted analysis-empty">本章没有识别到状态变化。</p>

                    <p class="list-title">事件状态</p>
                    <el-table v-if="analysisReport.events?.length" :data="analysisReport.events" size="small" class="asset-table">
                      <el-table-column prop="title" label="事件" min-width="180" show-overflow-tooltip />
                      <el-table-column label="状态变化" width="180">
                        <template #default="{ row: item }">
                          <span class="state-before">{{ item.fromLabel }}</span>
                          <span class="state-arrow">→</span>
                          <span class="state-after">{{ item.toLabel }}</span>
                          <el-tag v-if="!item.changed" size="small" type="info" effect="plain" class="same-tag">未变</el-tag>
                        </template>
                      </el-table-column>
                      <el-table-column label="依据" min-width="220" show-overflow-tooltip>
                        <template #default="{ row: item }">{{ item.reason || '—' }}</template>
                      </el-table-column>
                    </el-table>
                    <p v-else class="muted analysis-empty">本章没有关联事件，或事件仍在推进中。</p>
                  </section>

                  <div class="analysis-meta">
                    <span class="muted">
                      模型 {{ analysisReport.model || '—' }} · 用时 {{ (analysisReport.latencyMs / 1000).toFixed(1) }} 秒 ·
                      {{ formatDateTime(analysisReport.createdAt) }}
                      <template v-if="row.analysisCount > 1"> · 本章已分析 {{ row.analysisCount }} 次</template>
                    </span>
                    <div class="meta-actions">
                      <el-button type="primary" plain size="small" :icon="MagicStick" @click.stop="openRevise(row)">
                        按分析修改
                      </el-button>
                      <el-button
                        size="small"
                        :loading="analysisRunningId === row.chapterId"
                        :disabled="analysisRunning && analysisRunningId !== row.chapterId"
                        @click.stop="runAnalysis(row)"
                      >重新分析</el-button>
                    </div>
                  </div>
                </template>
                <div v-else class="analysis-expand-empty">
                  <p class="muted">
                    {{ row.blockReason || '这一章还没有分析。' }}
                  </p>
                  <el-button
                    v-if="row.analyzable"
                    type="primary"
                    size="small"
                    :loading="analysisRunningId === row.chapterId"
                    @click.stop="runAnalysis(row)"
                  >开始分析</el-button>
                </div>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="章节" min-width="260">
            <template #default="{ row }">
              <span class="analysis-row-title">
                <span class="analysis-row-no">第 {{ row.chapterNo }} 章</span>
                <strong>{{ row.title || '未命名' }}</strong>
              </span>
            </template>
          </el-table-column>
          <el-table-column label="字数" width="110">
            <template #default="{ row }">{{ row.wordCount || 0 }} 字</template>
          </el-table-column>
          <el-table-column label="大纲贴合" width="110">
            <template #default="{ row }">
              <span v-if="row.outlineScore !== null && row.outlineScore !== undefined" :style="{ color: scoreColor(row.outlineScore) }">
                {{ row.outlineScore }} 分
              </span>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column label="AI 率" width="110">
            <template #default="{ row }">
              <span v-if="row.aiScore !== null && row.aiScore !== undefined" :style="{ color: scoreColor(row.aiScore) }">
                {{ row.aiScore }} 分
              </span>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="150">
            <template #default="{ row }">
              <span v-if="analysisRunningId === row.chapterId" class="analysis-running-tag">
                <el-icon class="spin"><Loading /></el-icon>
                分析中 {{ analysisElapsed }}s
              </span>
              <el-tooltip v-else-if="!row.analyzable && row.blockReason" :content="row.blockReason" placement="top">
                <el-tag size="small" :type="analysisStatusType(row)" effect="plain">{{ row.statusLabel }}</el-tag>
              </el-tooltip>
              <el-tag v-else size="small" :type="analysisStatusType(row)" effect="plain">{{ row.statusLabel }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="110" align="right">
            <template #default="{ row }">
              <el-button v-if="row.status === 'ANALYZED'" text size="small" @click.stop="toggleAnalysisRow(row)">
                {{ expandedKeys.includes(row.chapterId) ? '收起' : '查看' }}
              </el-button>
              <el-button
                v-else
                text
                size="small"
                type="primary"
                :disabled="!row.analyzable || analysisRunning"
                :loading="analysisRunningId === row.chapterId"
                @click.stop="runAnalysis(row)"
              >
                分析
              </el-button>
            </template>
          </el-table-column>
        </el-table>

        <div v-else class="empty-block">
          <p>还没有章节。先在大纲里生成章节，再回来分析。</p>
        </div>
      </div>
    </section>

    <!-- AI 生成大纲弹窗 -->
    <el-dialog
      v-model="aiOutlineDialog.visible"
      title="AI 生成大纲"
      width="640px"
      :close-on-click-modal="!aiOutlineGenerating"
      :close-on-press-escape="!aiOutlineGenerating"
      :show-close="!aiOutlineGenerating"
      destroy-on-close
    >
      <div v-if="aiOutlineGenerating" class="ai-generating">
        <el-icon class="spin"><Loading /></el-icon>
        <p class="ai-title">AI 正在拆解故事骨架</p>
        <p class="ai-desc">结合世界观、背景、人物档案、事件池与联网资料生成章节大纲，通常 30~90 秒，单次最长 5 分钟</p>
        <p class="ai-elapsed">已用 {{ aiOutlineElapsed }} 秒</p>
      </div>
      <el-form v-else label-width="86px">
        <el-form-item label="大纲要求">
          <el-input
            v-model="aiOutlineDialog.prompt"
            type="textarea"
            :rows="4"
            maxlength="2000"
            show-word-limit
            placeholder="例如：第二卷进入宗门斗争，节奏加快，前三章埋下内奸伏笔，每章结尾都留钩子。"
          />
        </el-form-item>
        <el-row :gutter="12">
          <el-col :span="8">
            <el-form-item label="卷序号">
              <el-input-number v-model="aiOutlineDialog.volumeNo" :min="1" :max="999" controls-position="right" class="w-full" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="生成章数">
              <el-input-number v-model="aiOutlineDialog.chapterCount" :min="1" :max="20" controls-position="right" class="w-full" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="卷名">
              <el-input v-model="aiOutlineDialog.volumeTitle" maxlength="120" placeholder="可留空" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="指定人物">
          <el-select
            v-model="aiOutlineDialog.participantIds"
            multiple
            filterable
            clearable
            collapse-tags
            collapse-tags-tooltip
            placeholder="留空由 AI 按设定挑选；选了则每章都必须出场"
            class="w-full"
          >
            <el-option
              v-for="person in characters"
              :key="person.id"
              :label="`${person.name}（${person.roleLabel || '角色'}）`"
              :value="person.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="关联总纲">
          <el-select
            v-model="aiOutlineDialog.masterOutlineId"
            clearable
            filterable
            placeholder="可选：选定后本批章节大纲不得脱离这条总纲的范围"
            class="w-full"
          >
            <el-option
              v-for="item in masterOutlines"
              :key="item.id"
              :label="`总纲 ${item.sortNo} · ${item.title}`"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="联网资料">
          <span class="ai-hint">AI 会根据本次要求自动判断是否需要联网补充真实背景资料，并自动确定检索内容</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="aiOutlineGenerating" @click="aiOutlineDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="aiOutlineGenerating" @click="submitAiOutline">开始生成</el-button>
      </template>
    </el-dialog>

    <!-- 总纲编辑弹窗 -->
    <el-dialog
      v-model="masterOutlineDialog.visible"
      :title="masterOutlineDialog.id ? '编辑总纲' : '新增总纲'"
      width="620px"
      destroy-on-close
    >
      <el-form label-width="70px">
        <el-form-item label="标题" required>
          <el-input v-model="masterOutlineDialog.title" maxlength="200" placeholder="如：第一幕 落难与激活" />
        </el-form-item>
        <el-form-item label="内容">
          <el-input
            v-model="masterOutlineDialog.content"
            type="textarea"
            :autosize="{ minRows: 8, maxRows: 18 }"
            placeholder="这一段故事要发生什么：开局局面 → 主要矛盾 → 关键转折 → 这一段结束时的结果"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="masterOutlineDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="masterOutlineSaving" @click="submitMasterOutline">保存</el-button>
      </template>
    </el-dialog>

    <!-- AI 生成总纲弹窗 -->
    <el-dialog
      v-model="masterOutlineAiDialog.visible"
      title="AI 生成总纲"
      width="600px"
      :close-on-click-modal="!masterOutlineGenerating"
      :close-on-press-escape="!masterOutlineGenerating"
      :show-close="!masterOutlineGenerating"
      destroy-on-close
    >
      <div v-if="masterOutlineGenerating" class="ai-generating">
        <el-icon class="spin"><Loading /></el-icon>
        <p class="ai-title">AI 正在规划全书脉络</p>
        <p class="ai-desc">按现有设定与人物把故事主线拆成若干段总纲，通常 30~90 秒，单次最长 5 分钟</p>
      </div>
      <el-form v-else label-width="86px">
        <el-form-item label="总纲要求">
          <el-input
            v-model="masterOutlineAiDialog.prompt"
            type="textarea"
            :rows="4"
            maxlength="2000"
            show-word-limit
            placeholder="例如：重点写主角从地洞求生走向跨区联盟，前紧后松，第三段出现背叛。"
          />
        </el-form-item>
        <el-form-item label="生成条数">
          <el-input-number
            v-model="masterOutlineAiDialog.count"
            :min="1"
            :max="12"
            controls-position="right"
            class="w-full"
          />
        </el-form-item>
        <p class="form-hint">留空由系统判断：设定与人物齐全时覆盖全书，资料不足只生成一部分，之后可继续补充。</p>
      </el-form>
      <template #footer>
        <el-button :disabled="masterOutlineGenerating" @click="masterOutlineAiDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="masterOutlineGenerating" @click="submitMasterOutlineAi">开始生成</el-button>
      </template>
    </el-dialog>

    <!-- AI 写正文弹窗 -->
    <el-dialog
      v-model="aiContentDialog.visible"
      :title="`AI 写本章${editingChapter ? ' · 第 ' + editingChapter.chapterNo + ' 章' : ''}`"
      width="640px"
      :close-on-click-modal="!aiContentGenerating"
      :close-on-press-escape="!aiContentGenerating"
      :show-close="!aiContentGenerating"
      destroy-on-close
    >
      <div v-if="aiContentGenerating" class="ai-generating">
        <el-icon class="spin"><Loading /></el-icon>
        <p class="ai-title">AI 正在写这一章</p>
        <p class="ai-desc">按本章大纲、参与人物、物品势力与前情成稿，通常 60~180 秒，单次最长 5 分钟</p>
        <p class="ai-elapsed">已用 {{ aiContentElapsed }} 秒</p>
      </div>
      <el-form v-else label-width="86px">
        <el-alert
          type="info"
          :closable="false"
          show-icon
          class="ai-input-note"
          title="AI 会读取：本章大纲与关联事件 · 本章人物档案 · 相关物品与势力 · 上一章结尾 500 字 · 前 10 章大纲 · 前 50 章概括"
        />
        <el-form-item label="本章要求">
          <el-input
            v-model="aiContentDialog.prompt"
            type="textarea"
            :rows="4"
            maxlength="2000"
            show-word-limit
            placeholder="例如：多写主角与师父的对手戏，中段加一场雨夜追车，结尾停在主角发现档案被换。"
          />
        </el-form-item>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="预计字数">
              <el-input-number
                v-model="aiContentDialog.targetWords"
                :min="300"
                :max="6000"
                :step="100"
                controls-position="right"
                class="w-full"
              />
              <span class="ai-hint">默认 2500，只统计汉字（标点、数字不计）</span>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="写入方式">
              <el-radio-group v-model="aiContentDialog.mode">
                <el-radio-button value="REPLACE">覆盖本章</el-radio-button>
                <el-radio-button value="APPEND">接在末尾</el-radio-button>
              </el-radio-group>
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="联网资料">
          <span class="ai-hint">AI 会根据本章内容自动判断是否需要联网补充真实细节，并自动确定检索内容</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="aiContentGenerating" @click="aiContentDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="aiContentGenerating" @click="submitAiContent">开始写</el-button>
      </template>
    </el-dialog>

    <!-- 章节弹窗 -->
    <el-dialog v-model="chapterDialog.visible" :title="chapterDialog.id ? '编辑章节' : '新增章节'" width="640px" destroy-on-close>
      <el-form label-width="80px">
        <el-row :gutter="12">
          <el-col :span="8">
            <el-form-item label="卷序号">
              <el-input-number v-model="chapterDialog.volumeNo" :min="1" :max="999" controls-position="right" class="w-full" />
            </el-form-item>
          </el-col>
          <el-col :span="16">
            <el-form-item label="卷名">
              <el-input v-model="chapterDialog.volumeTitle" maxlength="120" placeholder="如：第一卷 旧书" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="8">
            <el-form-item label="章节号">
              <el-input-number v-model="chapterDialog.chapterNo" :min="1" :max="9999" controls-position="right" class="w-full" />
            </el-form-item>
          </el-col>
          <el-col :span="16">
            <el-form-item label="标题" required>
              <el-input v-model="chapterDialog.title" maxlength="200" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="摘要">
          <el-input v-model="chapterDialog.summary" type="textarea" :autosize="{ minRows: 3, maxRows: 8 }" placeholder="本章要写什么" />
        </el-form-item>
        <el-form-item label="参与人物">
          <el-select
            v-model="chapterDialog.participantIds"
            multiple
            filterable
            collapse-tags
            collapse-tags-tooltip
            placeholder="选择本章出场人物"
            class="w-full"
          >
            <el-option
              v-for="person in characters"
              :key="person.id"
              :label="`${person.name}（${person.roleLabel || '角色'}）`"
              :value="person.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="关联事件">
          <el-select
            v-model="chapterDialog.eventIds"
            multiple
            filterable
            collapse-tags
            collapse-tags-tooltip
            placeholder="选择本章要写到的事件"
            class="w-full"
          >
            <el-option
              v-for="event in timelines"
              :key="event.id"
              :label="`${event.title}（${event.statusLabel}）`"
              :value="event.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="所属总纲">
          <el-select
            v-model="chapterDialog.masterOutlineId"
            clearable
            filterable
            placeholder="可选：本章大纲属于哪一段剧情走向"
            class="w-full"
          >
            <el-option
              v-for="item in masterOutlines"
              :key="item.id"
              :label="`总纲 ${item.sortNo} · ${item.title}`"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
        <p class="form-hint">人物与事件决定 AI 写正文时注入哪些档案、物品与势力；大纲生成后会自动填好。</p>
        <el-form-item label="状态">
          <el-select v-model="chapterDialog.status" class="w-full">
            <el-option v-for="item in CHAPTER_STATUS" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="chapterDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="chapterSaving" @click="submitChapter">保存</el-button>
      </template>
    </el-dialog>

    <!-- AI 创建角色弹窗 -->
    <el-dialog
      v-model="aiDialog.visible"
      title="AI 创建角色"
      width="620px"
      :close-on-click-modal="!aiGenerating"
      :close-on-press-escape="!aiGenerating"
      :show-close="!aiGenerating"
      destroy-on-close
    >
      <div v-if="aiGenerating" class="ai-generating">
        <el-icon class="spin"><Loading /></el-icon>
        <p class="ai-title">AI 正在塑造角色</p>
        <p class="ai-desc">正在生成角色档案与人物关系，通常 20~60 秒，单次最长 5 分钟</p>
        <p class="ai-elapsed">已用 {{ aiElapsed }} 秒</p>
      </div>
      <el-form v-else label-width="70px">
        <el-form-item label="角色需求" required>
          <el-input
            v-model="aiDialog.prompt"
            type="textarea"
            :rows="5"
            maxlength="2000"
            show-word-limit
            placeholder="例如：加两个旧书商会的打手，一个忠心耿耿最后替主角挡刀，一个暗中投靠守夜人；再补一个档案馆的老馆员，知道当年大火的真相。"
          />
        </el-form-item>
        <div class="ai-samples">
          <span class="muted">试试：</span>
          <button
            v-for="item in AI_CHARACTER_SAMPLES"
            :key="item"
            type="button"
            class="sample-chip"
            @click="aiDialog.prompt = item"
          >
            {{ item }}
          </button>
        </div>
        <el-form-item label="角色数量">
          <el-select v-model="aiDialog.count" class="count-select">
            <el-option v-for="n in 6" :key="n" :label="`${n} 个`" :value="n" />
          </el-select>
          <span class="ai-hint">会同时生成角色之间的关系；与已有角色重名会被跳过</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="aiGenerating" @click="aiDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="aiGenerating" @click="submitAiCharacters">开始生成</el-button>
      </template>
    </el-dialog>

    <!-- 角色弹窗 -->
    <el-dialog v-model="characterDialog.visible" :title="characterDialog.id ? '编辑角色' : '新增角色'" width="900px" destroy-on-close>
      <el-tabs v-model="characterAssetTab" class="character-asset-tabs">
        <el-tab-pane label="档案" name="profile">
      <el-form label-width="80px">
        <el-row :gutter="12">
          <el-col :span="8">
            <el-form-item label="姓名" required>
              <el-input v-model="characterDialog.name" maxlength="64" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="定位">
              <el-select v-model="characterDialog.rolePos" class="w-full">
                <el-option v-for="item in ROLE_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="性别">
              <el-input v-model="characterDialog.gender" maxlength="16" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="身份">
              <el-input v-model="characterDialog.identity" maxlength="255" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="年龄">
              <el-input v-model="characterDialog.ageDesc" maxlength="32" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="外貌">
          <el-input v-model="characterDialog.appearance" type="textarea" :autosize="{ minRows: 2, maxRows: 4 }" />
        </el-form-item>
        <el-form-item label="性格">
          <el-input v-model="characterDialog.personality" type="textarea" :autosize="{ minRows: 2, maxRows: 4 }" />
        </el-form-item>
        <el-form-item label="动机">
          <el-input v-model="characterDialog.motivation" type="textarea" :autosize="{ minRows: 2, maxRows: 4 }" />
        </el-form-item>
        <el-form-item label="金手指">
          <el-input v-model="characterDialog.goldenFinger" type="textarea" :autosize="{ minRows: 2, maxRows: 4 }" />
        </el-form-item>
        <el-form-item label="成长弧">
          <el-input v-model="characterDialog.arc" type="textarea" :autosize="{ minRows: 2, maxRows: 4 }" />
        </el-form-item>
      </el-form>
        </el-tab-pane>

        <el-tab-pane label="属性" name="attributes">
          <div v-if="!characterDialog.id" class="asset-hint">先保存角色，再添加属性与背包。</div>
          <template v-else>
            <div class="asset-form">
              <el-input v-model="attributeDraft.name" placeholder="属性名，如 存款" maxlength="64" style="width: 150px" />
              <el-input v-model="attributeDraft.value" placeholder="当前值，如 300" maxlength="64" style="width: 130px" />
              <el-input v-model="attributeDraft.unit" placeholder="单位" maxlength="16" style="width: 90px" />
              <el-select v-model="attributeDraft.attrType" style="width: 96px">
                <el-option label="数值" value="NUMBER" />
                <el-option label="文本" value="TEXT" />
              </el-select>
              <el-button type="primary" @click="addAttribute">添加属性</el-button>
            </div>
            <div class="asset-quick">
              常用：
              <el-link v-for="item in QUICK_ATTRIBUTES" :key="item.name" type="primary" @click="fillAttribute(item)">
                {{ item.name }}
              </el-link>
            </div>
            <el-table :data="attributeRows" size="small" class="asset-table" empty-text="还没有属性，先加一条「存款」或「体质」">
              <el-table-column label="属性名" width="140">
                <template #default="{ row }"><el-input v-model="row.name" size="small" maxlength="64" /></template>
              </el-table-column>
              <el-table-column label="当前值" width="130">
                <template #default="{ row }"><el-input v-model="row.value" size="small" maxlength="64" /></template>
              </el-table-column>
              <el-table-column label="单位" width="90">
                <template #default="{ row }"><el-input v-model="row.unit" size="small" maxlength="16" /></template>
              </el-table-column>
              <el-table-column label="类型" width="100">
                <template #default="{ row }">
                  <el-select v-model="row.attrType" size="small">
                    <el-option label="数值" value="NUMBER" />
                    <el-option label="文本" value="TEXT" />
                  </el-select>
                </template>
              </el-table-column>
              <el-table-column label="备注" min-width="140">
                <template #default="{ row }"><el-input v-model="row.note" size="small" maxlength="255" /></template>
              </el-table-column>
              <el-table-column label="操作" width="110" fixed="right">
                <template #default="{ row }">
                  <el-button link type="primary" :loading="assetSaving" @click="saveAttribute(row)">保存</el-button>
                  <el-button link type="danger" @click="removeAttribute(row)">删除</el-button>
                </template>
              </el-table-column>
            </el-table>
          </template>
        </el-tab-pane>

        <el-tab-pane label="背包" name="inventory">
          <div v-if="!characterDialog.id" class="asset-hint">先保存角色，再添加属性与背包。</div>
          <template v-else>
            <div class="asset-form">
              <el-select v-model="inventoryDraft.entryType" style="width: 100px">
                <el-option label="货币" value="CURRENCY" />
                <el-option label="物资" value="MISC" />
              </el-select>
              <el-input v-model="inventoryDraft.name" placeholder="名称，如 铜钱 / 疗伤丹" maxlength="100" style="width: 190px" />
              <el-input-number
                v-model="inventoryDraft.quantity"
                :min="0"
                :controls="false"
                placeholder="数量"
                style="width: 110px"
              />
              <el-input v-model="inventoryDraft.unit" placeholder="单位" maxlength="16" style="width: 86px" />
              <el-button type="primary" @click="addInventory">添加</el-button>
            </div>
            <div class="asset-form">
              <el-select
                v-model="inventoryItemId"
                filterable
                placeholder="从物品档案挂入背包"
                style="width: 260px"
              >
                <el-option v-for="item in addableItems" :key="item.id" :label="item.name" :value="item.id" />
              </el-select>
              <el-button :disabled="!inventoryItemId" @click="addInventoryFromItem">挂入背包</el-button>
              <span class="asset-tip">物品档案里已有的东西不必重复建，直接挂进来即可共享描述与来历</span>
            </div>
            <el-table :data="inventoryRows" size="small" class="asset-table" empty-text="背包还是空的：可以挂物品档案，也可以直接记金钱与干粮">
              <el-table-column label="类型" width="80">
                <template #default="{ row }">
                  <el-tag size="small" :type="row.entryType === 'ITEM' ? 'primary' : row.entryType === 'CURRENCY' ? 'warning' : 'info'">
                    {{ row.entryTypeLabel }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="名称" min-width="150">
                <template #default="{ row }">
                  <el-input v-model="row.name" size="small" maxlength="100" :disabled="row.entryType === 'ITEM'" />
                </template>
              </el-table-column>
              <el-table-column label="数量" width="110">
                <template #default="{ row }">
                  <el-input-number v-model="row.quantity" :min="0" :controls="false" size="small" style="width: 100%" />
                </template>
              </el-table-column>
              <el-table-column label="单位" width="80">
                <template #default="{ row }"><el-input v-model="row.unit" size="small" maxlength="16" /></template>
              </el-table-column>
              <el-table-column label="状态" width="110">
                <template #default="{ row }">
                  <el-select v-model="row.status" size="small">
                    <el-option v-for="item in INVENTORY_STATUS_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
                  </el-select>
                </template>
              </el-table-column>
              <el-table-column label="备注" min-width="120">
                <template #default="{ row }"><el-input v-model="row.note" size="small" maxlength="1000" /></template>
              </el-table-column>
              <el-table-column label="操作" width="110" fixed="right">
                <template #default="{ row }">
                  <el-button link type="primary" :loading="assetSaving" @click="saveInventory(row)">保存</el-button>
                  <el-button link type="danger" @click="removeInventory(row)">删除</el-button>
                </template>
              </el-table-column>
            </el-table>
          </template>
        </el-tab-pane>
      </el-tabs>
      <template #footer>
        <el-button @click="characterDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="characterSaving" @click="submitCharacter">保存</el-button>
      </template>
    </el-dialog>

    <!-- 关系弹窗 -->
    <el-dialog v-model="relationDialog.visible" :title="relationDialog.id ? '编辑关系' : '新增关系'" width="520px" destroy-on-close>
      <el-form label-width="70px">
        <el-form-item label="角色 A" required>
          <el-select v-model="relationDialog.fromCharacterId" class="w-full" filterable placeholder="选择角色">
            <el-option v-for="person in characters" :key="person.id" :label="person.name" :value="person.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="关系" required>
          <el-select v-model="relationDialog.relationType" class="w-full">
            <el-option v-for="item in RELATION_TYPES" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="角色 B" required>
          <el-select v-model="relationDialog.toCharacterId" class="w-full" filterable placeholder="选择角色">
            <el-option
              v-for="person in characters"
              :key="person.id"
              :label="person.name"
              :value="person.id"
              :disabled="person.id === relationDialog.fromCharacterId"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="relationDialog.description" maxlength="255" placeholder="如：韩戍是沈默的师父，当年亲手烧了旧街" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="relationDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="relationSaving" @click="submitRelation">保存</el-button>
      </template>
    </el-dialog>

    <!-- 势力弹窗 -->
    <el-dialog v-model="factionDialog.visible" :title="factionDialog.id ? '编辑势力' : '新增势力'" width="560px" destroy-on-close>
      <el-form label-width="90px">
        <el-form-item label="名称" required>
          <el-input v-model="factionDialog.name" maxlength="100" placeholder="如：天穹传媒 / 青龙帮 / 大梁" />
        </el-form-item>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="类型">
              <el-select v-model="factionDialog.category" class="w-full" filterable allow-create clearable placeholder="选择或输入">
                <el-option v-for="item in FACTION_CATEGORIES" :key="item" :label="item" :value="item" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="状态">
              <el-select v-model="factionDialog.status" class="w-full">
                <el-option v-for="item in FACTION_STATUS" :key="item.value" :label="item.label" :value="item.value" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="营业/管辖范围">
          <el-input v-model="factionDialog.scope" maxlength="500" placeholder="如：华东地区娱乐产业 / 城南三条街 / 全国" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="factionDialog.remark" type="textarea" :autosize="{ minRows: 3, maxRows: 8 }" maxlength="1000" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="factionDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="factionSaving" @click="submitFaction">保存</el-button>
      </template>
    </el-dialog>

    <!-- 势力成员弹窗 -->
    <el-dialog v-model="memberDialog.visible" :title="`${memberDialog.factionName} · 成员`" width="660px" destroy-on-close>
      <div class="member-add">
        <el-select v-model="memberDialog.characterId" filterable placeholder="选择角色" class="member-select">
          <el-option
            v-for="person in characters"
            :key="person.id"
            :label="`${person.name}（${roleLabel(person.rolePos)}）`"
            :value="person.id"
          />
        </el-select>
        <el-input v-model="memberDialog.position" maxlength="64" placeholder="职位，如 董事长 / 堂主" class="member-position" />
        <el-button type="primary" :loading="memberSaving" @click="addFactionMember">添加</el-button>
      </div>
      <el-table v-if="memberDialog.members.length" :data="memberDialog.members" size="small" class="asset-table">
        <el-table-column prop="name" label="角色" width="130" />
        <el-table-column label="职位">
          <template #default="{ row }">
            <el-input v-model="row.position" size="small" maxlength="64" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="130" align="right">
          <template #default="{ row }">
            <el-button text size="small" @click="saveMemberPosition(row)">保存</el-button>
            <el-button text size="small" type="danger" @click="removeFactionMember(row)">移除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div v-else class="empty-block small">
        <p>还没有成员，从上面选个角色并填写职位。</p>
      </div>
    </el-dialog>

    <!-- AI 生成事件弹窗 -->
    <el-dialog
      v-model="aiEventDialog.visible"
      title="AI 生成事件"
      width="620px"
      :close-on-click-modal="!aiEventGenerating"
      :close-on-press-escape="!aiEventGenerating"
      :show-close="!aiEventGenerating"
      destroy-on-close
    >
      <div v-if="aiEventGenerating" class="ai-generating">
        <el-icon class="spin"><Loading /></el-icon>
        <p class="ai-title">AI 正在设计事件</p>
        <p class="ai-desc">正在结合已有事件与角色生成新事件，通常 20~60 秒，单次最长 5 分钟</p>
        <p class="ai-elapsed">已用 {{ aiEventElapsed }} 秒</p>
      </div>
      <el-form v-else label-width="86px">
        <el-form-item label="事件需求" required>
          <el-input
            v-model="aiEventDialog.prompt"
            type="textarea"
            :rows="5"
            maxlength="2000"
            show-word-limit
            placeholder="例如：围绕旧街大火补一批线索事件，从街坊的闲话一路查到档案塔，越往后越接近真相。"
          />
        </el-form-item>
        <div class="ai-samples">
          <span class="muted">试试：</span>
          <button
            v-for="item in AI_EVENT_SAMPLES"
            :key="item"
            type="button"
            class="sample-chip"
            @click="aiEventDialog.prompt = item"
          >
            {{ item }}
          </button>
        </div>
        <el-form-item label="数量">
          <el-select v-model="aiEventDialog.count" class="count-select">
            <el-option v-for="n in 10" :key="n" :label="`${n} 个`" :value="n" />
          </el-select>
          <span class="ai-hint">默认 1 个，最多 10 个；新事件默认「未使用」</span>
        </el-form-item>
        <el-form-item label="联网资料">
          <span class="ai-hint">AI 会根据本次要求自动判断是否需要联网补充真实背景，并自动确定检索内容</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="aiEventGenerating" @click="aiEventDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="aiEventGenerating" @click="submitAiEvents">开始生成</el-button>
      </template>
    </el-dialog>

    <!-- 事件弹窗（只用于编辑，新增只能走 AI） -->
    <el-dialog v-model="timelineDialog.visible" title="编辑事件" width="560px" destroy-on-close>
      <el-form label-width="70px">
        <el-form-item label="时间">
          <el-input v-model="timelineDialog.eventTime" maxlength="64" placeholder="如：第三年春" />
        </el-form-item>
        <el-form-item label="事件" required>
          <el-input v-model="timelineDialog.title" maxlength="200" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="timelineDialog.status" class="w-full">
            <el-option v-for="item in EVENT_STATUS" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="参与角色">
          <el-select
            v-model="timelineDialog.participantIds"
            class="w-full"
            multiple
            filterable
            collapse-tags
            collapse-tags-tooltip
            placeholder="选择参与本事件的角色"
          >
            <el-option
              v-for="person in characters"
              :key="person.id"
              :label="`${person.name}（${roleLabel(person.rolePos)}）`"
              :value="person.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="timelineDialog.description" type="textarea" :autosize="{ minRows: 4, maxRows: 10 }" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="timelineDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="timelineSaving" @click="submitTimeline">保存</el-button>
      </template>
    </el-dialog>

    <!-- AI 创建物品弹窗 -->
    <el-dialog
      v-model="aiItemDialog.visible"
      title="AI 创建物品"
      width="620px"
      :close-on-click-modal="!aiItemGenerating"
      :close-on-press-escape="!aiItemGenerating"
      :show-close="!aiItemGenerating"
      destroy-on-close
    >
      <div v-if="aiItemGenerating" class="ai-generating">
        <el-icon class="spin"><Loading /></el-icon>
        <p class="ai-title">AI 正在设计道具</p>
        <p class="ai-desc">结合作品设定、角色与已有物品生成，通常 20~60 秒，单次最长 5 分钟</p>
        <p class="ai-elapsed">已用 {{ aiItemElapsed }} 秒</p>
      </div>
      <el-form v-else label-width="86px">
        <el-form-item label="物品需求">
          <el-input
            v-model="aiItemDialog.prompt"
            type="textarea"
            :rows="5"
            maxlength="2000"
            show-word-limit
            placeholder="例如：给主角配一件修复古籍用的工具，写清它的形制与磨损，再补一个能证明旧街大火的信物，说明它的来历。"
          />
        </el-form-item>
        <div class="ai-samples">
          <span class="muted">试试：</span>
          <button
            v-for="item in AI_ITEM_SAMPLES"
            :key="item"
            type="button"
            class="sample-chip"
            @click="aiItemDialog.prompt = item"
          >
            {{ item }}
          </button>
        </div>
        <el-form-item label="数量">
          <el-select v-model="aiItemDialog.count" class="count-select">
            <el-option v-for="n in 10" :key="n" :label="`${n} 个`" :value="n" />
          </el-select>
          <span class="ai-hint">默认 3 个；与已有物品重名会自动跳过</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="aiItemGenerating" @click="aiItemDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="aiItemGenerating" @click="submitAiItems">开始生成</el-button>
      </template>
    </el-dialog>

    <!-- 物品弹窗 -->
    <el-dialog v-model="itemDialog.visible" :title="itemDialog.id ? '编辑物品' : '新增物品'" width="560px" destroy-on-close>
      <el-form label-width="70px">
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="名称" required>
              <el-input v-model="itemDialog.name" maxlength="100" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="类别">
              <el-select v-model="itemDialog.category" class="w-full" filterable allow-create clearable>
                <el-option v-for="item in ITEM_CATEGORIES" :key="item" :label="item" :value="item" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="状态">
              <el-select v-model="itemDialog.status" class="w-full">
                <el-option v-for="item in ITEM_STATUS" :key="item.value" :label="item.label" :value="item.value" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="持有者">
              <el-input v-model="itemDialog.owner" maxlength="100" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="数量">
          <div class="quantity-row">
            <el-input-number
              v-model="itemDialog.quantity"
              :min="0"
              :max="999999"
              :step="1"
              controls-position="right"
              placeholder="可留空"
              class="quantity-input"
            />
            <el-button v-if="itemDialog.quantity !== null && itemDialog.quantity !== undefined" text size="small" @click="itemDialog.quantity = null">
              清空
            </el-button>
            <span class="ai-hint">留空表示唯一或不可计数的物品</span>
          </div>
        </el-form-item>
        <el-form-item label="物品描述">
          <el-input
            v-model="itemDialog.description"
            type="textarea"
            :autosize="{ minRows: 3, maxRows: 8 }"
            maxlength="4000"
            placeholder="外貌形态、材质、尺寸、磨损或修补痕迹、纹样机关，以及它在故事里的象征意义"
          />
        </el-form-item>
        <el-form-item label="说明">
          <el-input
            v-model="itemDialog.origin"
            type="textarea"
            :autosize="{ minRows: 3, maxRows: 8 }"
            maxlength="4000"
            placeholder="来历：谁打造、从哪来、经手过谁、关联哪段关键事件或秘密"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="itemDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="itemSaving" @click="submitItem">保存</el-button>
      </template>
    </el-dialog>

    <ChangeHistoryDrawer ref="historyDrawer" :novel-id="novelId" />
    <ChapterReviseDialog
      v-model="reviseVisible"
      :chapter-id="reviseChapter?.chapterId || ''"
      :chapter-no="reviseChapter?.chapterNo || 0"
      :chapter-title="reviseChapter?.title || ''"
      :analysis="reviseAnalysis"
      @saved="handleRevisionSaved"
    />
    <AgentPanel
      v-model="agentOpen"
      :novel-id="novelId"
      :novel-title="novel?.title || ''"
      @edited="loadAll"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft, Calendar, ChatDotRound, Collection, Connection, DataAnalysis, Document, EditPen, Files, Loading, Lock, MagicStick, Notebook, OfficeBuilding, Plus, Reading, Refresh, User } from '@element-plus/icons-vue'
import {
  getNovelDetail,
  updateNovel,
  normalizeBlueprint,
  type CharacterDraft,
  type NovelBlueprint
} from '@/api/bookshelf'
import type { NovelVO } from '@/api/novel'
import RelationGraph from './components/RelationGraph.vue'
import SearchSources from './components/SearchSources.vue'
import ChangeHistoryDrawer from './components/ChangeHistoryDrawer.vue'
import ChapterReviseDialog from './components/ChapterReviseDialog.vue'
import AgentPanel from './components/AgentPanel.vue'
import {
  analyzeChapter,
  getAnalysis,
  listAnalysisOverview,
  type ChapterAnalysisOverviewVO,
  type ChapterAnalysisVO,
  type EntityType
} from '@/api/analysis'
import type { SearchBundleVO } from '@/api/search'
import {
  addFactionMember as apiAddFactionMember,
  createFaction,
  deleteFaction,
  listFactions,
  removeFactionMember as apiRemoveFactionMember,
  updateFaction,
  updateFactionMember,
  type FactionMemberVO,
  type FactionVO
} from '@/api/faction'
import {
  createChapter,
  createCharacter,
  createItem,
  createMasterOutline,
  createRelation,
  deleteChapter,
  deleteCharacter,
  deleteItem,
  deleteMasterOutline,
  deleteRelation,
  deleteTimeline,
  generateCharacters,
  generateContent,
  generateEvents,
  generateItems,
  generateMasterOutlines,
  generateOutline,
  getChapter,
  listChapters,
  listItems,
  listMasterOutlines,
  listRelations,
  listTimeline,
  updateChapter,
  updateCharacter,
  updateItem,
  updateMasterOutline,
  updateRelation,
  updateTimeline,
  type ChapterVO,
  type ItemVO,
  type MasterOutlineVO,
  type RelationPayload,
  type RelationVO,
  type TimelineVO
} from '@/api/novel-asset'
import {
  attributeApi,
  inventoryApi,
  type AttributeVO,
  type InventoryEntryType,
  type InventoryPayload,
  type InventoryVO
} from '@/api/character-asset'

const route = useRoute()
const router = useRouter()
const novelId = computed(() => String(route.params.id))
const agentOpen = ref(false)

const MENUS = [
  { key: 'basic', label: '基本信息', icon: Document },
  { key: 'masterOutline', label: '总纲', icon: Notebook },
  { key: 'outline', label: '大纲', icon: Files },
  { key: 'characters', label: '人物', icon: User },
  { key: 'factions', label: '势力', icon: OfficeBuilding },
  { key: 'timeline', label: '事件', icon: Calendar },
  { key: 'items', label: '物品', icon: Collection },
  { key: 'content', label: '正文', icon: Reading },
  { key: 'analysis', label: '分析', icon: DataAnalysis }
]

const GENRES = ['玄幻', '仙侠', '都市', '悬疑', '科幻', '历史', '游戏', '轻小说', '武侠', '现实']
const STATUS_OPTIONS = [
  { label: '草稿', value: 'DRAFT' },
  { label: '连载中', value: 'WRITING' },
  { label: '已完结', value: 'FINISHED' },
  { label: '已归档', value: 'ARCHIVED' }
]
const CHAPTER_STATUS = [
  { label: '草稿', value: 'DRAFT' },
  { label: '写作中', value: 'WRITING' },
  { label: '已完成', value: 'DONE' }
]
const AI_CHARACTER_SAMPLES = [
  '加两个旧书商会的打手，一个忠心耿耿，一个暗中投靠守夜人',
  '补一个知道当年大火真相的档案馆老馆员，再给主角加一个竞争对手',
  '来一个亦正亦邪的守夜人叛徒，和主角先敌对后结盟'
]
const ROLE_OPTIONS = [
  { label: '主角', value: 'PROTAGONIST' },
  { label: '配角', value: 'SUPPORTING' },
  { label: '反派', value: 'ANTAGONIST' },
  { label: '龙套', value: 'MINOR' }
]
const ITEM_CATEGORIES = ['法宝', '信物', '线索', '消耗品', '其他']
const ITEM_STATUS = [
  { label: '活跃', value: 'ACTIVE' },
  { label: '摧毁', value: 'DESTROYED' },
  { label: '废弃', value: 'ABANDONED' },
  { label: '遗失', value: 'LOST' },
  { label: '已消耗', value: 'CONSUMED' }
]
const AI_ITEM_SAMPLES = [
  '给主角配一件修复古籍用的工具，要写清形制磨损和师父的来历',
  '设计三件旧书商会的信物，分别掌握在不同人手里，各有暗记',
  '来一批守夜人的制式装备，其中一件藏着旧街大火的线索'
]
const FACTION_CATEGORIES = ['公司', '帮派', '国家', '组织', '其他']
const FACTION_STATUS = [
  { label: '活跃', value: 'ACTIVE' },
  { label: '蛰伏', value: 'DORMANT' },
  { label: '覆灭', value: 'DESTROYED' },
  { label: '解散', value: 'DISBANDED' }
]
const EVENT_STATUS = [
  { label: '未使用', value: 'UNUSED' },
  { label: '进行中', value: 'ONGOING' },
  { label: '已完成', value: 'DONE' },
  { label: '废弃', value: 'ABANDONED' }
]
const AI_EVENT_SAMPLES = [
  '围绕旧街大火补几条线索事件，越往后越接近真相',
  '给男女主安排三次相遇，一次比一次危险',
  '写一批守夜人施压的事件，逐步把主角逼到墙角'
]
const RELATION_TYPES = [
  { label: '师徒', value: 'MASTER_APPRENTICE' },
  { label: '亲属', value: 'FAMILY' },
  { label: '夫妻', value: 'SPOUSE' },
  { label: '恋人', value: 'LOVER' },
  { label: '好友', value: 'FRIEND' },
  { label: '盟友', value: 'ALLY' },
  { label: '仇敌', value: 'ENEMY' },
  { label: '对手', value: 'RIVAL' },
  { label: '同门', value: 'COLLEAGUE' },
  { label: '其他', value: 'OTHER' }
]

const loading = ref(false)
const initialTab = typeof route.query.tab === 'string' && MENUS.some((item) => item.key === route.query.tab)
  ? String(route.query.tab)
  : 'basic'
const activeMenu = ref(initialTab)
const novel = ref<NovelVO | null>(null)
const characters = ref<CharacterDraft[]>([])

const basic = reactive({
  title: '',
  penName: '',
  genre: '',
  tags: [] as string[],
  intro: '',
  status: 'DRAFT'
})
const settings = reactive<Record<string, string>>({
  WORLDVIEW: '',
  BACKGROUND: '',
  CORE_CONFLICT: '',
  HIGHLIGHT: '',
  STYLE: ''
})

const basicSaving = ref(false)
const settingSaving = ref(false)

/* ---------- 总纲 ---------- */
const masterOutlines = ref<MasterOutlineVO[]>([])
const masterOutlineLoading = ref(false)
const masterOutlineGenerating = ref(false)
const masterOutlineSaving = ref(false)
const masterOutlineDialog = reactive({
  visible: false,
  id: '',
  title: '',
  content: ''
})
const masterOutlineAiDialog = reactive({
  visible: false,
  prompt: '',
  count: undefined as number | undefined
})

/* ---------- 章节 ---------- */
const chapters = ref<ChapterVO[]>([])
const chapterLoading = ref(false)
const chapterSaving = ref(false)
const chapterDialog = reactive({
  visible: false,
  id: '',
  volumeNo: 1,
  volumeTitle: '',
  chapterNo: 1,
  title: '',
  summary: '',
  status: 'DRAFT',
  participantIds: [] as string[],
  eventIds: [] as string[],
  masterOutlineId: '' as string
})

/* ---------- 正文 ---------- */
const activeChapterId = ref('')
const editingChapter = ref<ChapterVO | null>(null)
const editingChapterTitle = ref('')
const contentDraft = ref('')
const contentStatus = ref('DRAFT')
const contentSaving = ref(false)
/** 正文默认进入查看态，点「编辑」才可改，避免误触污染已写的正文 */
const contentEditing = ref(false)
const statusSaving = ref(false)
/** 已完成章节视为定稿：只有草稿 / 写作中才允许编辑 */
const contentLocked = computed(() => contentStatus.value === 'DONE')
/** 查看态按行分段渲染，空行不占位 */
const contentParagraphs = computed(() =>
  contentDraft.value
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter((line) => line.length > 0)
)
/** 编辑态下是否有未保存的改动，用于切章 / 取消时兜底 */
const contentDirty = computed(() => {
  if (!contentEditing.value || !editingChapter.value) {
    return false
  }
  return (editingChapterTitle.value.trim() || '') !== (editingChapter.value.title || '')
    || contentDraft.value !== (editingChapter.value.content || '')
})
/** 字数按汉字统计：标点、数字、字母不计 */
const contentWords = computed(() => (contentDraft.value.match(/[\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff]/g) || []).length)

/* ---------- 分析 ---------- */
const historyDrawer = ref<InstanceType<typeof ChangeHistoryDrawer> | null>(null)
const analysisRows = ref<ChapterAnalysisOverviewVO[]>([])
/** 当前展开行的报告 */
const analysisReport = ref<ChapterAnalysisVO | null>(null)
const analysisLoading = ref(false)
const analysisRunning = ref(false)
/** 正在分析的章节 id，用于行内显示「分析中」 */
const analysisRunningId = ref('')
const analysisElapsed = ref(0)
const analysisTargetWords = ref(2500)
const analysisTargetTouched = ref(false)
const expandedKeys = ref<string[]>([])
const expandedLoadingId = ref('')
let analysisTimer: ReturnType<typeof setInterval> | null = null

const analyzedCount = computed(
  () => analysisRows.value.filter((item) => item.status === 'ANALYZED').length
)
const analysisPercent = computed(() =>
  analysisRows.value.length ? Math.round((analyzedCount.value / analysisRows.value.length) * 100) : 0
)
/** 按顺序：第一个可分析且还没分析的章节 */
const nextAnalyzableChapter = computed<ChapterAnalysisOverviewVO | null>(
  () => analysisRows.value.find((item) => item.analyzable && item.status !== 'ANALYZED') || null
)
/** 正文为空、把后面章节一起挡住的章 */
const blockedChapter = computed<ChapterAnalysisOverviewVO | null>(
  () => analysisRows.value.find((item) => item.status === 'EMPTY') || null
)
const changedEventCount = computed(
  () => (analysisReport.value?.events || []).filter((item) => item.changed).length
)
const outlineColor = computed(() => scoreColor(analysisReport.value?.outlineScore ?? 0))
const aiColor = computed(() => scoreColor(analysisReport.value?.aiScore ?? 0))

/** 按分析修改：弹窗依赖当前展开行，行收起或切走后报告就对不上了，所以按 chapterId 校验 */
const reviseVisible = ref(false)
const reviseChapter = ref<ChapterAnalysisOverviewVO | null>(null)
const reviseAnalysis = computed(() =>
  reviseChapter.value && analysisReport.value?.chapterId === reviseChapter.value.chapterId
    ? analysisReport.value
    : null
)

function analysisStatusType(row: ChapterAnalysisOverviewVO) {
  if (row.status === 'ANALYZED') {
    return 'success'
  }
  if (row.status === 'READY') {
    return 'primary'
  }
  if (row.status === 'EMPTY') {
    return 'danger'
  }
  return 'info'
}

function scoreColor(score: number) {
  if (score >= 75) {
    return '#16a34a'
  }
  if (score >= 55) {
    return '#d97706'
  }
  return '#dc2626'
}

function signalTagType(level: string) {
  if (level === 'HIGH') {
    return 'danger'
  }
  if (level === 'MID') {
    return 'warning'
  }
  return 'success'
}

function signalLevelLabel(level: string) {
  if (level === 'HIGH') {
    return '可疑'
  }
  if (level === 'MID') {
    return '一般'
  }
  return '正常'
}

function formatDateTime(value?: string) {
  if (!value) {
    return '—'
  }
  return value.replace('T', ' ').slice(0, 16)
}

/* ---------- 角色 ---------- */
const characterSaving = ref(false)
const characterDialog = reactive({
  visible: false,
  id: '',
  name: '',
  rolePos: 'SUPPORTING',
  identity: '',
  gender: '',
  ageDesc: '',
  appearance: '',
  personality: '',
  motivation: '',
  goldenFinger: '',
  arc: ''
})

/* ---------- 角色属性 / 背包 ---------- */
const characterAssetTab = ref<'profile' | 'attributes' | 'inventory'>('profile')
const assetSaving = ref(false)
const attributeRows = ref<AttributeVO[]>([])
const inventoryRows = ref<InventoryVO[]>([])
const attributeDraft = reactive({ name: '', value: '', unit: '', attrType: 'NUMBER', note: '' })
const inventoryDraft = reactive({
  entryType: 'CURRENCY' as InventoryEntryType,
  name: '',
  quantity: undefined as number | undefined,
  unit: '',
  note: ''
})
const inventoryItemId = ref('')

/* ---------- 人物面板 · 背包视图（按人物查看，随分析更新） ---------- */
const bagLoading = ref(false)
const bagSaving = ref(false)
const bagMap = ref<Record<string, InventoryVO[]>>({})
const bagCharacterId = ref('')
const bagKeyword = ref('')
const bagDraft = reactive({
  entryType: 'MISC' as InventoryEntryType,
  itemId: '',
  name: '',
  quantity: undefined as number | undefined,
  unit: ''
})

const bagPersons = computed(() => {
  const keyword = bagKeyword.value.trim()
  const list = keyword ? characters.value.filter((person) => (person.name || '').includes(keyword)) : characters.value
  return list
})
const bagCharacter = computed(() => characters.value.find((person) => person.id === bagCharacterId.value))
const bagRows = computed(() => (bagCharacterId.value ? bagMap.value[bagCharacterId.value] || [] : []))
/** 已经挂进这个人背包的物品，不再出现在下拉里 */
const bagAddableItems = computed(() => {
  const used = new Set(bagRows.value.map((row) => row.itemId).filter(Boolean))
  return items.value.filter((item) => !used.has(item.id))
})

const QUICK_ATTRIBUTES = [
  { name: '存款', unit: '元', attrType: 'NUMBER', value: '0' },
  { name: '体质', unit: '', attrType: 'NUMBER', value: '10' },
  { name: '积分', unit: '点', attrType: 'NUMBER', value: '0' },
  { name: '人气', unit: '万', attrType: 'NUMBER', value: '0' },
  { name: '等级', unit: '', attrType: 'TEXT', value: '' }
]

const INVENTORY_STATUS_OPTIONS = [
  { label: '持有', value: 'HELD' },
  { label: '遗失', value: 'LOST' },
  { label: '已消耗', value: 'CONSUMED' }
]

/** 已挂入背包的物品不再出现在下拉里，避免重复添加被后端拦下 */
const addableItems = computed(() =>
  items.value.filter((item) => !inventoryRows.value.some((row) => row.itemId === item.id))
)

async function loadCharacterAssets(characterId: string) {
  if (!characterId) {
    attributeRows.value = []
    inventoryRows.value = []
    return
  }
  try {
    const [attrs, inventory] = await Promise.all([
      attributeApi.list(characterId),
      inventoryApi.list(characterId)
    ])
    attributeRows.value = attrs
    inventoryRows.value = inventory
  } catch (error) {
    ElMessage.error((error as Error).message || '属性与背包加载失败')
  }
}

function fillAttribute(item: (typeof QUICK_ATTRIBUTES)[number]) {
  attributeDraft.name = item.name
  attributeDraft.unit = item.unit
  attributeDraft.attrType = item.attrType
  attributeDraft.value = item.value
}

/* ---------- 人物面板 · 背包视图 ---------- */
function bagSummary(characterId: string) {
  const rows = bagMap.value[characterId] || []
  if (!rows.length) {
    return ''
  }
  const names = rows.slice(0, 3).map((row) => {
    const quantity = typeof row.quantity === 'number' ? `×${row.quantity}` : ''
    return `${row.name}${quantity}`
  })
  return names.join('、') + (rows.length > 3 ? ` 等 ${rows.length} 项` : '')
}

function openBag(person: CharacterDraft) {
  if (!person.id) {
    return
  }
  bagCharacterId.value = person.id
  characterTab.value = 'bag'
  void loadBagAll()
}

function onCharacterTabChange(name: string | number) {
  if (name === 'bag') {
    void loadBagAll()
  }
}

async function loadBagAll() {
  if (!novelId.value) {
    return
  }
  bagLoading.value = true
  try {
    bagMap.value = await inventoryApi.listByNovel(novelId.value)
    const exists = characters.value.some((person) => person.id === bagCharacterId.value)
    if (!exists) {
      bagCharacterId.value = characters.value[0]?.id || ''
    }
  } catch (error) {
    ElMessage.error((error as Error).message || '背包加载失败')
  } finally {
    bagLoading.value = false
  }
}

async function addBagEntry() {
  const characterId = bagCharacterId.value
  if (!characterId) {
    ElMessage.warning('先在左侧选一个人物')
    return
  }
  const entryType = bagDraft.entryType
  const payload: InventoryPayload = {
    entryType,
    quantity: bagDraft.quantity ?? null,
    unit: bagDraft.unit.trim(),
    status: 'HELD'
  }
  if (entryType === 'ITEM') {
    if (!bagDraft.itemId) {
      ElMessage.warning('先选一个物品档案')
      return
    }
    payload.itemId = bagDraft.itemId
  } else {
    const name = bagDraft.name.trim()
    if (!name) {
      ElMessage.warning('请填写名称，例如 现金 / 能量块 / 歌曲版权')
      return
    }
    payload.name = name
  }
  bagSaving.value = true
  try {
    const created = await inventoryApi.create(characterId, payload)
    bagMap.value = { ...bagMap.value, [characterId]: [...(bagMap.value[characterId] || []), created] }
    bagDraft.name = ''
    bagDraft.itemId = ''
    bagDraft.quantity = undefined
    ElMessage.success(`已加入 ${created.name}`)
  } catch (error) {
    ElMessage.error((error as Error).message || '加入背包失败')
  } finally {
    bagSaving.value = false
  }
}

async function saveBagRow(row: InventoryVO) {
  bagSaving.value = true
  const hasQuantity = typeof row.quantity === 'number'
  try {
    const updated = await inventoryApi.update(row.id, {
      quantity: hasQuantity ? row.quantity : null,
      clearQuantity: !hasQuantity,
      unit: row.unit || '',
      status: row.status,
      note: row.note || ''
    })
    const list = bagMap.value[updated.characterId] || []
    const index = list.findIndex((item) => item.id === updated.id)
    if (index >= 0) {
      list[index] = updated
    }
    ElMessage.success('已保存')
  } catch (error) {
    ElMessage.error((error as Error).message || '保存失败')
  } finally {
    bagSaving.value = false
  }
}

async function removeBagRow(row: InventoryVO) {
  try {
    await ElMessageBox.confirm(`从背包里删除「${row.name}」？`, '确认删除', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  bagSaving.value = true
  try {
    await inventoryApi.remove(row.id)
    const list = bagMap.value[row.characterId] || []
    bagMap.value = { ...bagMap.value, [row.characterId]: list.filter((item) => item.id !== row.id) }
    ElMessage.success('已删除')
  } catch (error) {
    ElMessage.error((error as Error).message || '删除失败')
  } finally {
    bagSaving.value = false
  }
}

async function addAttribute() {
  if (!characterDialog.id) {
    return
  }
  if (!attributeDraft.name.trim()) {
    ElMessage.warning('请填写属性名')
    return
  }
  assetSaving.value = true
  try {
    const created = await attributeApi.create(characterDialog.id, {
      name: attributeDraft.name.trim(),
      value: attributeDraft.value.trim(),
      unit: attributeDraft.unit.trim(),
      attrType: attributeDraft.attrType,
      note: attributeDraft.note.trim()
    })
    attributeRows.value.push(created)
    attributeDraft.name = ''
    attributeDraft.value = ''
    attributeDraft.note = ''
    ElMessage.success('属性已添加')
  } catch (error) {
    ElMessage.error((error as Error).message || '添加失败')
  } finally {
    assetSaving.value = false
  }
}

async function saveAttribute(row: AttributeVO) {
  if (!row.name.trim()) {
    ElMessage.warning('属性名不能为空')
    return
  }
  assetSaving.value = true
  try {
    const updated = await attributeApi.update(row.id, {
      name: row.name.trim(),
      value: (row.value || '').trim(),
      unit: (row.unit || '').trim(),
      attrType: row.attrType,
      note: (row.note || '').trim()
    })
    Object.assign(row, updated)
    ElMessage.success('属性已保存')
  } catch (error) {
    ElMessage.error((error as Error).message || '保存失败')
  } finally {
    assetSaving.value = false
  }
}

async function removeAttribute(row: AttributeVO) {
  try {
    await ElMessageBox.confirm(`确定删除属性「${row.name}」吗？`, '删除属性', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  try {
    await attributeApi.remove(row.id)
    attributeRows.value = attributeRows.value.filter((item) => item.id !== row.id)
    ElMessage.success('属性已删除')
  } catch (error) {
    ElMessage.error((error as Error).message || '删除失败')
  }
}

async function addInventory() {
  if (!characterDialog.id) {
    return
  }
  if (!inventoryDraft.name.trim()) {
    ElMessage.warning('请填写名称')
    return
  }
  assetSaving.value = true
  try {
    const created = await inventoryApi.create(characterDialog.id, {
      entryType: inventoryDraft.entryType,
      name: inventoryDraft.name.trim(),
      quantity: inventoryDraft.quantity ?? null,
      unit: inventoryDraft.unit.trim(),
      status: 'HELD',
      note: inventoryDraft.note.trim()
    })
    inventoryRows.value.push(created)
    inventoryDraft.name = ''
    inventoryDraft.quantity = undefined
    inventoryDraft.note = ''
    ElMessage.success('已加入背包')
  } catch (error) {
    ElMessage.error((error as Error).message || '添加失败')
  } finally {
    assetSaving.value = false
  }
}

async function addInventoryFromItem() {
  if (!characterDialog.id || !inventoryItemId.value) {
    return
  }
  assetSaving.value = true
  try {
    const created = await inventoryApi.create(characterDialog.id, {
      entryType: 'ITEM',
      itemId: inventoryItemId.value,
      status: 'HELD'
    })
    inventoryRows.value.push(created)
    inventoryItemId.value = ''
    ElMessage.success('已挂入背包')
  } catch (error) {
    ElMessage.error((error as Error).message || '添加失败')
  } finally {
    assetSaving.value = false
  }
}

async function saveInventory(row: InventoryVO) {
  assetSaving.value = true
  try {
    const updated = await inventoryApi.update(row.id, {
      name: row.entryType === 'ITEM' ? undefined : row.name.trim(),
      quantity: row.quantity ?? null,
      clearQuantity: row.quantity === null || row.quantity === undefined,
      unit: (row.unit || '').trim(),
      status: row.status,
      note: (row.note || '').trim()
    })
    Object.assign(row, updated)
    ElMessage.success('背包已保存')
  } catch (error) {
    ElMessage.error((error as Error).message || '保存失败')
  } finally {
    assetSaving.value = false
  }
}

async function removeInventory(row: InventoryVO) {
  try {
    await ElMessageBox.confirm(`确定从背包里移除「${row.name}」吗？`, '移除条目', {
      type: 'warning',
      confirmButtonText: '移除',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  try {
    await inventoryApi.remove(row.id)
    inventoryRows.value = inventoryRows.value.filter((item) => item.id !== row.id)
    ElMessage.success('已移除')
  } catch (error) {
    ElMessage.error((error as Error).message || '移除失败')
  }
}

/* ---------- AI 创建角色 ---------- */
const aiDialog = reactive({ visible: false, prompt: '', count: 2 })
const aiGenerating = ref(false)
const aiElapsed = ref(0)
let aiTimer: number | undefined

/* ---------- 人物关系 ---------- */
const characterTab = ref<'list' | 'relations' | 'bag'>('list')
const relations = ref<RelationVO[]>([])
const relationLoading = ref(false)
const relationSaving = ref(false)
const relationDialog = reactive({
  visible: false,
  id: '',
  fromCharacterId: '',
  toCharacterId: '',
  relationType: 'MASTER_APPRENTICE',
  description: ''
})

/* ---------- AI 创建角色 ---------- */
function openAiCharacterDialog() {
  aiDialog.prompt = ''
  aiDialog.count = 2
  aiElapsed.value = 0
  aiDialog.visible = true
}

async function submitAiCharacters() {
  const prompt = aiDialog.prompt.trim()
  if (prompt.length < 4) {
    ElMessage.warning('再多描述两句，AI 才知道你想要什么角色')
    return
  }
  aiGenerating.value = true
  aiElapsed.value = 0
  aiTimer = window.setInterval(() => {
    aiElapsed.value += 1
  }, 1000)
  try {
    const result = await generateCharacters(novelId.value, { prompt, count: aiDialog.count })
    aiDialog.visible = false
    characterTab.value = 'list'
    await reloadCharacters()
    const skipped = result.skipped && result.skipped.length ? `，跳过重名 ${result.skipped.length} 个` : ''
    ElMessage.success(`已创建 ${result.characters.length} 个角色、${result.relations.length} 条关系${skipped}`)
  } catch (error) {
    ElMessage.error((error as Error).message || 'AI 生成失败，请重试')
  } finally {
    aiGenerating.value = false
    stopAiTimer()
  }
}

function stopAiTimer() {
  if (aiTimer) {
    window.clearInterval(aiTimer)
    aiTimer = undefined
  }
}

/* ---------- AI 生成事件 ---------- */
function openAiEventDialog() {
  aiEventDialog.prompt = ''
  aiEventDialog.count = 1
  aiEventElapsed.value = 0
  aiEventDialog.visible = true
}

async function submitAiEvents() {
  const prompt = aiEventDialog.prompt.trim()
  if (prompt.length < 4) {
    ElMessage.warning('再多描述两句，AI 才知道要什么事件')
    return
  }
  aiEventGenerating.value = true
  aiEventElapsed.value = 0
  aiEventTimer = window.setInterval(() => {
    aiEventElapsed.value += 1
  }, 1000)
  try {
    const result = await generateEvents(novelId.value, {
      prompt,
      count: aiEventDialog.count
    })
    aiEventDialog.visible = false
    eventSearch.value = result.search || null
    await loadTimeline()
    const newCharacters = result.createdCharacters || []
    if (newCharacters.length) {
      await reloadCharacters()
    }
    const skipped = result.skipped && result.skipped.length ? `，跳过重名 ${result.skipped.length} 个` : ''
    const created = newCharacters.length ? `，补建 ${newCharacters.length} 个角色` : ''
    const searched = result.search && result.search.results.length
      ? `，参考 ${result.search.results.length} 条资料` : ''
    ElMessage.success(`已生成 ${result.events.length} 个事件${created}${searched}${skipped}`)
  } catch (error) {
    ElMessage.error((error as Error).message || 'AI 生成失败，请重试')
  } finally {
    aiEventGenerating.value = false
    stopAiEventTimer()
  }
}

function stopAiEventTimer() {
  if (aiEventTimer) {
    window.clearInterval(aiEventTimer)
    aiEventTimer = undefined
  }
}

/* ---------- 总纲 ---------- */
async function loadMasterOutlines() {
  masterOutlineLoading.value = true
  try {
    masterOutlines.value = await listMasterOutlines(novelId.value)
  } catch (error) {
    ElMessage.error((error as Error).message || '加载总纲失败')
  } finally {
    masterOutlineLoading.value = false
  }
}

function masterOutlineTitle(id?: string | null) {
  if (!id) {
    return ''
  }
  const hit = masterOutlines.value.find((item) => item.id === id)
  return hit ? hit.title : ''
}

function openMasterOutlineDialog(item?: MasterOutlineVO) {
  masterOutlineDialog.id = item?.id || ''
  masterOutlineDialog.title = item?.title || ''
  masterOutlineDialog.content = item?.content || ''
  masterOutlineDialog.visible = true
}

async function submitMasterOutline() {
  if (!masterOutlineDialog.title.trim()) {
    ElMessage.warning('请填写总纲标题')
    return
  }
  masterOutlineSaving.value = true
  try {
    const payload = {
      title: masterOutlineDialog.title.trim(),
      content: masterOutlineDialog.content.trim()
    }
    if (masterOutlineDialog.id) {
      await updateMasterOutline(masterOutlineDialog.id, payload)
    } else {
      await createMasterOutline(novelId.value, payload)
    }
    masterOutlineDialog.visible = false
    await loadMasterOutlines()
    ElMessage.success('总纲已保存')
  } catch (error) {
    ElMessage.error((error as Error).message || '保存总纲失败')
  } finally {
    masterOutlineSaving.value = false
  }
}

async function removeMasterOutline(item: MasterOutlineVO) {
  try {
    await ElMessageBox.confirm(
      `确定删除「${item.title}」？引用它的 ${item.chapterCount} 条章节大纲会解除关联，章节本身保留。`,
      '删除总纲',
      { type: 'warning' }
    )
  } catch {
    return
  }
  try {
    await deleteMasterOutline(item.id)
    await Promise.all([loadMasterOutlines(), loadChapters()])
    ElMessage.success('已删除')
  } catch (error) {
    ElMessage.error((error as Error).message || '删除失败')
  }
}

function openMasterOutlineAiDialog() {
  masterOutlineAiDialog.prompt = ''
  masterOutlineAiDialog.count = undefined
  masterOutlineAiDialog.visible = true
}

async function submitMasterOutlineAi() {
  masterOutlineGenerating.value = true
  try {
    const created = await generateMasterOutlines(novelId.value, {
      prompt: masterOutlineAiDialog.prompt.trim() || undefined,
      count: masterOutlineAiDialog.count || undefined
    })
    masterOutlineAiDialog.visible = false
    await loadMasterOutlines()
    ElMessage.success(`已生成 ${created.length} 条总纲`)
  } catch (error) {
    ElMessage.error((error as Error).message || 'AI 生成总纲失败，请重试')
  } finally {
    masterOutlineGenerating.value = false
  }
}

/** 从某条总纲直接跳到「AI 生成大纲」，并自动带上关联 */
function generateOutlineForMaster(item: MasterOutlineVO) {
  activeMenu.value = 'outline'
  openAiOutlineDialog(item.id)
}

/* ---------- AI 生成大纲 ---------- */
function openAiOutlineDialog(masterOutlineId?: string) {
  const last = chapters.value.length ? chapters.value[chapters.value.length - 1] : null
  aiOutlineDialog.prompt = ''
  aiOutlineDialog.volumeNo = last?.volumeNo || 1
  aiOutlineDialog.volumeTitle = last?.volumeTitle || ''
  aiOutlineDialog.chapterCount = 5
  aiOutlineDialog.participantIds = []
  aiOutlineDialog.masterOutlineId = masterOutlineId || ''
  aiOutlineElapsed.value = 0
  aiOutlineDialog.visible = true
}

async function submitAiOutline() {
  aiOutlineGenerating.value = true
  aiOutlineElapsed.value = 0
  aiOutlineTimer = window.setInterval(() => {
    aiOutlineElapsed.value += 1
  }, 1000)
  try {
    const result = await generateOutline(novelId.value, {
      prompt: aiOutlineDialog.prompt.trim() || undefined,
      volumeNo: aiOutlineDialog.volumeNo,
      volumeTitle: aiOutlineDialog.volumeTitle.trim() || undefined,
      chapterCount: aiOutlineDialog.chapterCount,
      participantIds: aiOutlineDialog.participantIds,
      masterOutlineId: aiOutlineDialog.masterOutlineId || null
    })
    aiOutlineDialog.visible = false
    outlineSearch.value = result.search || null
    await loadChapters()
    await loadMasterOutlines()
    const skipped = result.skipped && result.skipped.length ? `，跳过重名 ${result.skipped.length} 章` : ''
    const searched = result.search && result.search.results.length
      ? `，参考 ${result.search.results.length} 条资料` : ''
    const created: string[] = []
    if (result.newCharacters?.length) created.push(`${result.newCharacters.length} 个人物`)
    if (result.newItems?.length) created.push(`${result.newItems.length} 件物品`)
    if (result.newFactions?.length) created.push(`${result.newFactions.length} 个势力`)
    if (result.newEvents?.length) created.push(`${result.newEvents.length} 个事件`)
    const built = created.length ? `，并初始化 ${created.join('、')}` : ''
    ElMessage.success(`已生成 ${result.chapters.length} 章大纲${searched}${built}${skipped}`)
    if (result.newCharacters?.length) await reloadCharacters()
    if (result.newItems?.length) await loadItems()
    if (!result.newCharacters?.length && result.newFactions?.length) await loadFactions()
    if (result.newEvents?.length) await loadTimeline()
  } catch (error) {
    ElMessage.error((error as Error).message || 'AI 生成大纲失败，请重试')
  } finally {
    aiOutlineGenerating.value = false
    stopAiOutlineTimer()
  }
}

function stopAiOutlineTimer() {
  if (aiOutlineTimer) {
    window.clearInterval(aiOutlineTimer)
    aiOutlineTimer = undefined
  }
}

/* ---------- AI 写正文 ---------- */
function openAiContentDialog() {
  if (!activeChapterId.value) {
    ElMessage.warning('先在左侧选择一章')
    return
  }
  aiContentDialog.prompt = ''
  aiContentDialog.targetWords = 2500
  aiContentDialog.mode = 'REPLACE'
  aiContentElapsed.value = 0
  aiContentDialog.visible = true
}

async function submitAiContent() {
  if (!activeChapterId.value) {
    return
  }
  aiContentGenerating.value = true
  aiContentElapsed.value = 0
  aiContentTimer = window.setInterval(() => {
    aiContentElapsed.value += 1
  }, 1000)
  try {
    const result = await generateContent(activeChapterId.value, {
      prompt: aiContentDialog.prompt.trim() || undefined,
      targetWords: aiContentDialog.targetWords,
      mode: aiContentDialog.mode
    })
    aiContentDialog.visible = false
    contentSearch.value = result.search || null
    contentDraft.value = result.content || ''
    contentStatus.value = 'WRITING'
    if (editingChapter.value) {
      // AI 已把正文写进库，本地基准同步过去：取消编辑时回到这份新内容而不是旧稿
      editingChapter.value = { ...editingChapter.value, content: result.content || '', wordCount: result.wordCount, status: 'WRITING' }
    }
    // 刚生成的稿子通常还要打磨，直接进编辑态
    contentEditing.value = true
    await loadChapters()
    const searched = result.search && result.search.results.length
      ? `，参考 ${result.search.results.length} 条资料` : ''
    const created: string[] = []
    if (result.newCharacters?.length) created.push(`${result.newCharacters.length} 个人物`)
    if (result.newItems?.length) created.push(`${result.newItems.length} 件物品`)
    if (result.newFactions?.length) created.push(`${result.newFactions.length} 个势力`)
    const built = created.length ? `，并补建 ${created.join('、')}` : ''
    ElMessage.success(`已写入 ${result.generatedWords} 个汉字${searched}${built}`)
    if (result.newCharacters?.length) await reloadCharacters()
    if (result.newItems?.length) await loadItems()
    if (result.newFactions?.length) await loadFactions()
  } catch (error) {
    ElMessage.error((error as Error).message || 'AI 写作失败，请重试')
  } finally {
    aiContentGenerating.value = false
    stopAiContentTimer()
  }
}

function stopAiContentTimer() {
  if (aiContentTimer) {
    window.clearInterval(aiContentTimer)
    aiContentTimer = undefined
  }
}

onBeforeUnmount(() => {
  stopAiTimer()
  stopAiEventTimer()
  stopAiOutlineTimer()
  stopAiContentTimer()
  stopAiItemTimer()
  stopAnalysisTimer()
})

/* ---------- 人物关系 ---------- */
async function loadRelations() {
  relationLoading.value = true
  try {
    relations.value = (await listRelations(novelId.value)) || []
  } catch (error) {
    ElMessage.error((error as Error).message || '加载人物关系失败')
  } finally {
    relationLoading.value = false
  }
}

function openRelationDialog(row?: RelationVO) {
  relationDialog.id = row?.id || ''
  relationDialog.fromCharacterId = row?.fromCharacterId || characters.value[0]?.id || ''
  relationDialog.toCharacterId = row?.toCharacterId || characters.value[1]?.id || ''
  relationDialog.relationType = row?.relationType || 'MASTER_APPRENTICE'
  relationDialog.description = row?.description || ''
  relationDialog.visible = true
}

async function submitRelation() {
  if (!relationDialog.fromCharacterId || !relationDialog.toCharacterId) {
    ElMessage.warning('请选择关系两端的角色')
    return
  }
  if (relationDialog.fromCharacterId === relationDialog.toCharacterId) {
    ElMessage.warning('不能给同一个角色建立关系')
    return
  }
  relationSaving.value = true
  try {
    const payload: RelationPayload = {
      fromCharacterId: relationDialog.fromCharacterId,
      toCharacterId: relationDialog.toCharacterId,
      relationType: relationDialog.relationType,
      description: relationDialog.description
    }
    if (relationDialog.id) {
      await updateRelation(relationDialog.id, payload)
    } else {
      await createRelation(novelId.value, payload)
    }
    relationDialog.visible = false
    ElMessage.success('关系已保存')
    await loadRelations()
  } catch (error) {
    ElMessage.error((error as Error).message || '保存失败')
  } finally {
    relationSaving.value = false
  }
}

async function removeRelation(row: RelationVO) {
  try {
    await ElMessageBox.confirm(
      `确定删除「${row.fromCharacterName} — ${row.relationLabel} — ${row.toCharacterName}」这条关系吗？`,
      '删除关系',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await deleteRelation(row.id)
    ElMessage.success('已删除')
    await loadRelations()
  } catch (error) {
    ElMessage.error((error as Error).message || '删除失败')
  }
}

/* ---------- 势力 ---------- */
const factions = ref<FactionVO[]>([])
const factionLoading = ref(false)
const factionSaving = ref(false)
const factionDialog = reactive({
  visible: false,
  id: '',
  name: '',
  category: '',
  status: 'ACTIVE',
  scope: '',
  remark: ''
})
const memberSaving = ref(false)
const memberDialog = reactive({
  visible: false,
  factionId: '',
  factionName: '',
  members: [] as FactionMemberVO[],
  characterId: '',
  position: ''
})

/* ---------- 时间线 ---------- */
const timelines = ref<TimelineVO[]>([])
const timelineLoading = ref(false)
const timelineSaving = ref(false)
const timelineDialog = reactive({
  visible: false,
  id: '',
  eventTime: '',
  title: '',
  description: '',
  status: 'UNUSED',
  participantIds: [] as string[]
})

/* ---------- AI 生成事件 ---------- */
const aiEventDialog = reactive({ visible: false, prompt: '', count: 1 })
const aiEventGenerating = ref(false)
const aiEventElapsed = ref(0)
let aiEventTimer: number | undefined
const eventSearch = ref<SearchBundleVO | null>(null)

/* ---------- AI 生成大纲 ---------- */
const aiOutlineDialog = reactive({
  visible: false,
  prompt: '',
  volumeNo: 1,
  volumeTitle: '',
  chapterCount: 5,
  participantIds: [] as string[],
  masterOutlineId: '' as string
})
const aiOutlineGenerating = ref(false)
const aiOutlineElapsed = ref(0)
let aiOutlineTimer: number | undefined
const outlineSearch = ref<SearchBundleVO | null>(null)

/* ---------- AI 写正文 ---------- */
const aiContentDialog = reactive({
  visible: false,
  prompt: '',
  targetWords: 2500,
  mode: 'REPLACE' as 'REPLACE' | 'APPEND'
})
const aiContentGenerating = ref(false)
const aiContentElapsed = ref(0)
let aiContentTimer: number | undefined
const contentSearch = ref<SearchBundleVO | null>(null)

/* ---------- 物品 ---------- */
const items = ref<ItemVO[]>([])
const itemLoading = ref(false)
const itemSaving = ref(false)
const itemDialog = reactive({
  visible: false,
  id: '',
  name: '',
  category: '',
  owner: '',
  description: '',
  origin: '',
  status: 'ACTIVE',
  quantity: null as number | null
})

/* ---------- AI 创建物品 ---------- */
const aiItemDialog = reactive({ visible: false, prompt: '', count: 3 })
const aiItemGenerating = ref(false)
const aiItemElapsed = ref(0)
let aiItemTimer: number | undefined

const coverWord = computed(() => {
  const title = novel.value?.title || '书'
  return title.length <= 2 ? title : title.slice(0, 2)
})

const coverStyle = computed(() => {
  const title = novel.value?.title || ''
  let hash = 0
  for (const ch of title) {
    hash = (hash * 31 + ch.charCodeAt(0)) % 9973
  }
  const palettes = [
    'linear-gradient(135deg, #3b6ef6, #7aa2ff)',
    'linear-gradient(135deg, #0f766e, #2dd4bf)',
    'linear-gradient(135deg, #7c3aed, #c084fc)',
    'linear-gradient(135deg, var(--sf-text-warning), #fbbf24)',
    'linear-gradient(135deg, #be123c, #fb7185)'
  ]
  return { background: palettes[hash % palettes.length] }
})

const statusLabel = computed(() => STATUS_OPTIONS.find((item) => item.value === basic.status)?.label ?? basic.status)

const volumeGroups = computed(() => {
  const map = new Map<number, { volumeNo: number; volumeTitle: string; items: ChapterVO[] }>()
  for (const chapter of chapters.value) {
    const volumeNo = chapter.volumeNo || 1
    if (!map.has(volumeNo)) {
      map.set(volumeNo, { volumeNo, volumeTitle: chapter.volumeTitle || '', items: [] })
    }
    map.get(volumeNo)!.items.push(chapter)
  }
  return Array.from(map.values()).sort((a, b) => a.volumeNo - b.volumeNo)
})

onMounted(loadAll)

async function loadAll() {
  loading.value = true
  try {
    const detail = await getNovelDetail(novelId.value)
    novel.value = detail.novel
    basic.title = detail.novel.title || ''
    basic.penName = detail.novel.penName || ''
    basic.genre = detail.novel.genre || ''
    basic.tags = detail.novel.tags || []
    basic.intro = detail.novel.intro || ''
    basic.status = detail.novel.status || 'DRAFT'
    for (const key of Object.keys(settings)) {
      settings[key] = ''
    }
    for (const doc of detail.settings || []) {
      settings[doc.category] = doc.content || ''
    }
    characters.value = detail.characters || []
    await Promise.all([loadChapters(), loadMasterOutlines(), loadTimeline(), loadItems(), loadRelations(), loadFactions(), loadBagAll()])
    // 直接带 ?tab=analysis 进入时不会触发菜单切换，这里补一次初始化
    if (activeMenu.value === 'analysis') {
      await loadAnalysisPanel()
    }
  } catch (error) {
    ElMessage.error((error as Error).message || '加载小说失败')
  } finally {
    loading.value = false
  }
}

function onMenuSelect(key: string) {
  activeMenu.value = key
  if (key === 'analysis') {
    void loadAnalysisPanel()
  }
  if (key === 'characters') {
    // 人物卡片上要显示背包摘要，进面板先取一次（一次性取整本书，不分人请求）
    void loadBagAll()
  }
}

function goBookshelf() {
  router.push('/bookshelf')
}

/* ---------- 基本信息 ---------- */
async function saveBasic() {
  if (!basic.title.trim()) {
    ElMessage.warning('请填写书名')
    return
  }
  basicSaving.value = true
  try {
    const updated = await updateNovel(novelId.value, {
      title: basic.title.trim(),
      penName: basic.penName.trim() || undefined,
      genre: basic.genre || undefined,
      tags: basic.tags,
      intro: basic.intro,
      status: basic.status
    })
    novel.value = updated
    ElMessage.success('基本信息已保存')
  } catch (error) {
    ElMessage.error((error as Error).message || '保存失败')
  } finally {
    basicSaving.value = false
  }
}

/** 设定保存复用「整体覆盖」接口：必须带上现有角色，否则会被清空 */
async function saveSettings() {
  settingSaving.value = true
  try {
    const blueprint: NovelBlueprint = normalizeBlueprint({
      title: basic.title.trim(),
      genre: basic.genre,
      tags: basic.tags,
      synopsis: basic.intro,
      worldview: settings.WORLDVIEW,
      background: settings.BACKGROUND,
      coreConflict: settings.CORE_CONFLICT,
      highlights: splitLines(settings.HIGHLIGHT),
      style: settings.STYLE,
      protagonist: characters.value.find((item) => item.rolePos === 'PROTAGONIST'),
      characters: characters.value.filter((item) => item.rolePos !== 'PROTAGONIST')
    })
    const updated = await updateNovel(novelId.value, { blueprint })
    novel.value = updated
    ElMessage.success('设定已保存')
    await reloadCharacters()
  } catch (error) {
    ElMessage.error((error as Error).message || '保存失败')
  } finally {
    settingSaving.value = false
  }
}

function splitLines(value: string) {
  return (value || '')
    .split(/\n/)
    .map((item) => item.trim())
    .filter(Boolean)
}

/* ---------- 章节 ---------- */
async function loadChapters() {
  chapterLoading.value = true
  try {
    chapters.value = await listChapters(novelId.value)
    if (!activeChapterId.value && chapters.value.length) {
      await selectChapter(chapters.value[0])
    }
  } catch (error) {
    ElMessage.error((error as Error).message || '加载章节失败')
  } finally {
    chapterLoading.value = false
  }
}

function openChapterDialog(chapter?: ChapterVO) {
  chapterDialog.id = chapter?.id || ''
  chapterDialog.volumeNo = chapter?.volumeNo || volumeGroups.value[volumeGroups.value.length - 1]?.volumeNo || 1
  chapterDialog.volumeTitle = chapter?.volumeTitle || volumeGroups.value[volumeGroups.value.length - 1]?.volumeTitle || ''
  chapterDialog.chapterNo = chapter?.chapterNo || (chapters.value.length ? Math.max(...chapters.value.map((item) => item.chapterNo)) + 1 : 1)
  chapterDialog.title = chapter?.title || ''
  chapterDialog.summary = chapter?.summary || ''
  chapterDialog.status = chapter?.status || 'DRAFT'
  chapterDialog.participantIds = (chapter?.participants || []).map((person) => person.id)
  chapterDialog.eventIds = (chapter?.events || []).map((event) => event.id)
  chapterDialog.masterOutlineId = chapter?.masterOutlineId || ''
  chapterDialog.visible = true
}

async function submitChapter() {
  if (!chapterDialog.title.trim()) {
    ElMessage.warning('请填写章节标题')
    return
  }
  chapterSaving.value = true
  try {
    const payload = {
      volumeNo: chapterDialog.volumeNo,
      volumeTitle: chapterDialog.volumeTitle,
      chapterNo: chapterDialog.chapterNo,
      title: chapterDialog.title.trim(),
      summary: chapterDialog.summary,
      status: chapterDialog.status,
      participantIds: chapterDialog.participantIds,
      eventIds: chapterDialog.eventIds,
      // 编辑时清空下拉要传 0，后端据此解除关联
      masterOutlineId: chapterDialog.masterOutlineId || (chapterDialog.id ? 0 : null)
    }
    if (chapterDialog.id) {
      await updateChapter(chapterDialog.id, payload)
    } else {
      await createChapter(novelId.value, payload)
    }
    chapterDialog.visible = false
    ElMessage.success('章节已保存')
    await loadChapters()
    await refreshNovel()
  } catch (error) {
    ElMessage.error((error as Error).message || '保存失败')
  } finally {
    chapterSaving.value = false
  }
}

async function removeChapter(chapter: ChapterVO) {
  try {
    await ElMessageBox.confirm(`确定删除「第 ${chapter.chapterNo} 章 ${chapter.title}」吗？正文也会一并删除。`, '删除章节', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  try {
    await deleteChapter(chapter.id)
    if (activeChapterId.value === chapter.id) {
      activeChapterId.value = ''
      editingChapter.value = null
      contentDraft.value = ''
    }
    ElMessage.success('已删除')
    await loadChapters()
    await refreshNovel()
  } catch (error) {
    ElMessage.error((error as Error).message || '删除失败')
  }
}

/* ---------- 正文 ---------- */
async function selectChapter(chapter: ChapterVO) {
  if (chapter.id === activeChapterId.value && !contentDirty.value) {
    return
  }
  if (contentDirty.value) {
    try {
      await ElMessageBox.confirm('当前章节还有未保存的修改，切走后会丢失。确定切换吗？', '有未保存的修改', {
        type: 'warning',
        confirmButtonText: '放弃修改并切换',
        cancelButtonText: '继续编辑'
      })
    } catch {
      return
    }
  }
  try {
    const detail = await getChapter(chapter.id)
    activeChapterId.value = chapter.id
    editingChapter.value = detail
    editingChapterTitle.value = detail.title || ''
    contentDraft.value = detail.content || ''
    contentStatus.value = detail.status || 'DRAFT'
    // 换章一律回到查看态
    contentEditing.value = false
  } catch (error) {
    ElMessage.error((error as Error).message || '加载正文失败')
  }
}

function startContentEdit() {
  if (contentLocked.value) {
    ElMessage.warning('本章已标记为「已完成」，请先把状态改回草稿或写作中')
    return
  }
  contentEditing.value = true
}

function cancelContentEdit() {
  if (editingChapter.value) {
    editingChapterTitle.value = editingChapter.value.title || ''
    contentDraft.value = editingChapter.value.content || ''
    contentStatus.value = editingChapter.value.status || 'DRAFT'
  }
  contentEditing.value = false
}

/** 查看态改状态即存库：这也是「已完成 → 草稿」的解锁入口，所以不能等到编辑态才提交 */
async function changeContentStatus(value: string) {
  if (!activeChapterId.value || !editingChapter.value) {
    return
  }
  const previous = editingChapter.value.status || 'DRAFT'
  statusSaving.value = true
  try {
    const saved = await updateChapter(activeChapterId.value, { status: value })
    editingChapter.value = saved
    contentStatus.value = saved.status || value
    await loadChapters()
    await refreshNovel()
    if (saved.status === 'DONE') {
      ElMessage.success('已标记为「已完成」，正文将锁定')
    } else {
      ElMessage.success(`状态已改为「${chapterStatusLabel(saved.status)}」`)
    }
  } catch (error) {
    contentStatus.value = previous
    ElMessage.error((error as Error).message || '状态更新失败')
  } finally {
    statusSaving.value = false
  }
}

async function saveContent() {
  if (!activeChapterId.value) {
    return
  }
  contentSaving.value = true
  try {
    const saved = await updateChapter(activeChapterId.value, {
      title: editingChapterTitle.value.trim() || editingChapter.value?.title,
      content: contentDraft.value,
      status: contentStatus.value
    })
    // 以服务端返回为准，保存完回到查看态
    editingChapter.value = saved
    editingChapterTitle.value = saved.title || ''
    contentDraft.value = saved.content || ''
    contentStatus.value = saved.status || 'DRAFT'
    contentEditing.value = false
    ElMessage.success('正文已保存')
    await loadChapters()
    await refreshNovel()
  } catch (error) {
    ElMessage.error((error as Error).message || '保存失败')
  } finally {
    contentSaving.value = false
  }
}

/* ---------- 分析 ---------- */
function stopAnalysisTimer() {
  if (analysisTimer) {
    clearInterval(analysisTimer)
    analysisTimer = null
  }
}

async function loadAnalysisPanel() {
  analysisLoading.value = true
  try {
    if (!chapters.value.length) {
      await loadChapters()
    }
    analysisRows.value = await listAnalysisOverview(novelId.value)
    if (!analysisTargetTouched.value && analysisRows.value.length) {
      const counts = analysisRows.value.map((item) => item.wordCount || 0).filter((count) => count > 0)
      if (counts.length) {
        const average = Math.round(counts.reduce((sum, count) => sum + count, 0) / counts.length / 100) * 100
        analysisTargetWords.value = Math.min(6000, Math.max(300, average || 2500))
      }
    }
    // 章节被删或列表刷新后，收起的展开行要一起清掉
    if (analysisReport.value && !analysisRows.value.some((item) => item.chapterId === analysisReport.value?.chapterId)) {
      analysisReport.value = null
      expandedKeys.value = []
    }
  } catch (error) {
    ElMessage.error((error as Error).message || '加载分析记录失败')
  } finally {
    analysisLoading.value = false
  }
}

/** 点行展开 / 收起；展开时按需拉取该章最近一次报告 */
async function toggleAnalysisRow(row: ChapterAnalysisOverviewVO) {
  if (expandedKeys.value.includes(row.chapterId)) {
    expandedKeys.value = []
    analysisReport.value = null
    return
  }
  expandedKeys.value = [row.chapterId]
  analysisReport.value = null
  if (!row.analysisId) {
    return
  }
  expandedLoadingId.value = row.chapterId
  try {
    const report = await getAnalysis(row.analysisId)
    if (expandedKeys.value.includes(row.chapterId)) {
      analysisReport.value = report
    }
  } catch (error) {
    ElMessage.error((error as Error).message || '加载报告失败')
  } finally {
    expandedLoadingId.value = ''
  }
}

function runNextAnalysis() {
  const target = nextAnalyzableChapter.value
  if (!target) {
    ElMessage.info('没有待分析的章节')
    return
  }
  void runAnalysis(target)
}

async function runAnalysis(row: ChapterAnalysisOverviewVO) {
  if (analysisRunning.value) {
    return
  }
  if (!row.analyzable) {
    ElMessage.warning(row.blockReason || '需要先分析前面的章节')
    return
  }
  analysisRunning.value = true
  analysisRunningId.value = row.chapterId
  analysisElapsed.value = 0
  stopAnalysisTimer()
  analysisTimer = setInterval(() => {
    analysisElapsed.value += 1
  }, 1000)
  try {
    const report = await analyzeChapter(row.chapterId, analysisTargetWords.value)
    const appended = report.newCharacters?.length || 0
    const histories = report.changes?.length || 0
    const events = report.events?.filter((item) => item.changed).length || 0
    ElMessage.success(
      `第 ${row.chapterNo} 章分析完成：大纲贴合 ${report.outlineScore} 分 · AI 率 ${report.aiScore} 分 · 新增人物 ${appended} · 履历 ${histories} · 事件 ${events}`
    )
    await loadAnalysisPanel()
    expandedKeys.value = [row.chapterId]
    analysisReport.value = report
    await Promise.all([reloadCharacters(), loadTimeline()])
    await loadAnalysisPanel()
  } catch (error) {
    ElMessage.error((error as Error).message || '分析失败')
    // 顺序被其他端改过时，刷新列表拿到真实的前置状态
    await loadAnalysisPanel()
  } finally {
    stopAnalysisTimer()
    analysisRunning.value = false
    analysisRunningId.value = ''
  }
}

/** 打开「按分析修改」：只有已分析的章节才有审稿单可依据 */
function openRevise(row: ChapterAnalysisOverviewVO) {
  if (analysisRunning.value) {
    ElMessage.warning('正在分析中，等这一轮结束再改稿')
    return
  }
  if (analysisReport.value?.chapterId !== row.chapterId) {
    ElMessage.warning('分析报告还没加载完，稍后再试')
    return
  }
  reviseChapter.value = row
  reviseVisible.value = true
}

/** 改稿保存成功后：同步编辑器 → 刷新章节与用量 → 重新分析本章 */
async function handleRevisionSaved(payload: { chapterId: string; content: string }) {
  if (activeChapterId.value === payload.chapterId) {
    contentDraft.value = payload.content
  }
  try {
    await loadChapters()
    await refreshNovel()
    analysisReport.value = null
    await loadAnalysisPanel()
    const row = analysisRows.value.find((item) => item.chapterId === payload.chapterId)
    if (!row) {
      ElMessage.warning('章节列表里没找到该章，请手动刷新后再分析')
      return
    }
    await runAnalysis(row)
  } catch (error) {
    ElMessage.error((error as Error).message || '刷新章节失败')
  }
}

function openEntityHistory(entityType: EntityType, row: { id?: string; name?: string }) {  if (!row?.id) {
    return
  }
  void historyDrawer.value?.open(entityType, row.id, row.name || '未命名')
}

/* ---------- 角色 ---------- */
async function reloadCharacters() {
  const detail = await getNovelDetail(novelId.value)
  characters.value = detail.characters || []
  novel.value = detail.novel
  await Promise.all([loadRelations(), loadFactions()])
}

function openCharacterDialog(person?: CharacterDraft) {
  characterDialog.id = person?.id || ''
  characterDialog.name = person?.name || ''
  characterDialog.rolePos = person?.rolePos || 'SUPPORTING'
  characterDialog.identity = person?.identity || ''
  characterDialog.gender = person?.gender || ''
  characterDialog.ageDesc = person?.ageDesc || ''
  characterDialog.appearance = person?.appearance || ''
  characterDialog.personality = person?.personality || ''
  characterDialog.motivation = person?.motivation || ''
  characterDialog.goldenFinger = person?.goldenFinger || ''
  characterDialog.arc = person?.arc || ''
  characterAssetTab.value = 'profile'
  attributeRows.value = []
  inventoryRows.value = []
  inventoryItemId.value = ''
  attributeDraft.name = ''
  attributeDraft.value = ''
  attributeDraft.note = ''
  characterDialog.visible = true
  if (characterDialog.id) {
    void loadCharacterAssets(characterDialog.id)
  }
}

async function submitCharacter() {
  if (!characterDialog.name.trim()) {
    ElMessage.warning('请填写角色姓名')
    return
  }
  characterSaving.value = true
  try {
    const payload: CharacterDraft = {
      name: characterDialog.name.trim(),
      rolePos: characterDialog.rolePos,
      identity: characterDialog.identity,
      gender: characterDialog.gender,
      ageDesc: characterDialog.ageDesc,
      appearance: characterDialog.appearance,
      personality: characterDialog.personality,
      motivation: characterDialog.motivation,
      goldenFinger: characterDialog.goldenFinger,
      arc: characterDialog.arc
    }
    if (characterDialog.id) {
      await updateCharacter(characterDialog.id, payload)
      ElMessage.success('角色已保存')
    } else {
      const created = await createCharacter(novelId.value, payload)
      characterDialog.id = created.id || ''
      // 新建后不关闭弹窗，直接切到属性页，省得再点一次"编辑"
      characterAssetTab.value = 'attributes'
      ElMessage.success('角色已创建，可以继续添加属性与背包')
    }
    await reloadCharacters()
    if (characterDialog.id) {
      await loadCharacterAssets(characterDialog.id)
    }
  } catch (error) {
    ElMessage.error((error as Error).message || '保存失败')
  } finally {
    characterSaving.value = false
  }
}

async function removeCharacter(person: CharacterDraft) {
  if (!person.id) {
    return
  }
  try {
    await ElMessageBox.confirm(`确定删除角色「${person.name}」吗？`, '删除角色', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  try {
    await deleteCharacter(person.id)
    ElMessage.success('已删除')
    await reloadCharacters()
  } catch (error) {
    ElMessage.error((error as Error).message || '删除失败')
  }
}

/* ---------- 势力 ---------- */
async function loadFactions() {
  factionLoading.value = true
  try {
    factions.value = (await listFactions(novelId.value)) || []
  } catch (error) {
    ElMessage.error((error as Error).message || '加载势力失败')
  } finally {
    factionLoading.value = false
  }
}

function openFactionDialog(faction?: FactionVO) {
  factionDialog.id = faction?.id || ''
  factionDialog.name = faction?.name || ''
  factionDialog.category = faction?.category || ''
  factionDialog.status = faction?.status || 'ACTIVE'
  factionDialog.scope = faction?.scope || ''
  factionDialog.remark = faction?.remark || ''
  factionDialog.visible = true
}

async function submitFaction() {
  if (!factionDialog.name.trim()) {
    ElMessage.warning('请填写势力名称')
    return
  }
  factionSaving.value = true
  try {
    const payload = {
      name: factionDialog.name.trim(),
      category: factionDialog.category,
      status: factionDialog.status,
      scope: factionDialog.scope,
      remark: factionDialog.remark
    }
    if (factionDialog.id) {
      await updateFaction(factionDialog.id, payload)
    } else {
      await createFaction(novelId.value, payload)
    }
    factionDialog.visible = false
    ElMessage.success('势力已保存')
    await loadFactions()
  } catch (error) {
    ElMessage.error((error as Error).message || '保存失败')
  } finally {
    factionSaving.value = false
  }
}

async function removeFaction(faction: FactionVO) {
  try {
    await ElMessageBox.confirm(
      `删除「${faction.name}」后，它的成员任职记录也会一并删除。确定删除吗？`,
      '删除势力',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await deleteFaction(faction.id)
    ElMessage.success('已删除')
    await loadFactions()
  } catch (error) {
    ElMessage.error((error as Error).message || '删除失败')
  }
}

function openMemberDialog(faction: FactionVO) {
  memberDialog.factionId = faction.id
  memberDialog.factionName = faction.name
  memberDialog.members = faction.members.map((member) => ({ ...member }))
  memberDialog.characterId = ''
  memberDialog.position = ''
  memberDialog.visible = true
}

/** 成员变更后把弹窗里的列表同步成最新数据 */
function syncMemberDialog() {
  const faction = factions.value.find((item) => item.id === memberDialog.factionId)
  if (faction) {
    memberDialog.factionName = faction.name
    memberDialog.members = faction.members.map((member) => ({ ...member }))
  }
}

async function addFactionMember() {
  if (!memberDialog.characterId) {
    ElMessage.warning('请选择角色')
    return
  }
  if (!memberDialog.position.trim()) {
    ElMessage.warning('请填写该角色在这个势力中的职位')
    return
  }
  memberSaving.value = true
  try {
    await apiAddFactionMember(memberDialog.factionId, {
      characterId: memberDialog.characterId,
      position: memberDialog.position.trim()
    })
    memberDialog.characterId = ''
    memberDialog.position = ''
    ElMessage.success('成员已添加')
    await loadFactions()
    syncMemberDialog()
  } catch (error) {
    ElMessage.error((error as Error).message || '添加失败')
  } finally {
    memberSaving.value = false
  }
}

async function saveMemberPosition(member: FactionMemberVO) {
  if (!member.position.trim()) {
    ElMessage.warning('职位不能为空')
    return
  }
  try {
    await updateFactionMember(member.id, member.position.trim())
    ElMessage.success('职位已更新')
    await loadFactions()
    syncMemberDialog()
  } catch (error) {
    ElMessage.error((error as Error).message || '保存失败')
  }
}

async function removeFactionMember(member: FactionMemberVO) {
  try {
    await ElMessageBox.confirm(`确定把「${member.name}」从该势力移除吗？`, '移除成员', {
      type: 'warning',
      confirmButtonText: '移除',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  try {
    await apiRemoveFactionMember(member.id)
    ElMessage.success('已移除')
    await loadFactions()
    syncMemberDialog()
  } catch (error) {
    ElMessage.error((error as Error).message || '移除失败')
  }
}

function factionStatusType(status: string) {
  if (status === 'ACTIVE') {
    return 'success'
  }
  if (status === 'DORMANT') {
    return 'warning'
  }
  if (status === 'DESTROYED') {
    return 'danger'
  }
  return 'info'
}

/* ---------- 时间线 ---------- */
async function loadTimeline() {
  timelineLoading.value = true
  try {
    timelines.value = await listTimeline(novelId.value)
  } catch (error) {
    ElMessage.error((error as Error).message || '加载事件失败')
  } finally {
    timelineLoading.value = false
  }
}

function openTimelineDialog(row?: TimelineVO) {
  if (!row) {
    return
  }
  timelineDialog.id = row.id
  timelineDialog.eventTime = row.eventTime || ''
  timelineDialog.title = row.title
  timelineDialog.description = row.description || ''
  timelineDialog.status = row.status || 'UNUSED'
  timelineDialog.participantIds = (row.participants || []).map((person) => person.id)
  timelineDialog.visible = true
}

async function submitTimeline() {
  if (!timelineDialog.title.trim()) {
    ElMessage.warning('请填写事件标题')
    return
  }
  timelineSaving.value = true
  try {
    const payload = {
      eventTime: timelineDialog.eventTime,
      title: timelineDialog.title.trim(),
      description: timelineDialog.description,
      status: timelineDialog.status,
      participantIds: timelineDialog.participantIds
    }
    await updateTimeline(timelineDialog.id, payload)
    timelineDialog.visible = false
    ElMessage.success('事件已保存')
    await loadTimeline()
  } catch (error) {
    ElMessage.error((error as Error).message || '保存失败')
  } finally {
    timelineSaving.value = false
  }
}

async function removeTimeline(row: TimelineVO) {
  try {
    await ElMessageBox.confirm(`确定删除事件「${row.title}」吗？`, '删除事件', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  try {
    await deleteTimeline(row.id)
    ElMessage.success('已删除')
    await loadTimeline()
  } catch (error) {
    ElMessage.error((error as Error).message || '删除失败')
  }
}

/* ---------- 物品 ---------- */
async function loadItems() {
  itemLoading.value = true
  try {
    items.value = await listItems(novelId.value)
  } catch (error) {
    ElMessage.error((error as Error).message || '加载物品失败')
  } finally {
    itemLoading.value = false
  }
}

function openItemDialog(row?: ItemVO) {
  itemDialog.id = row?.id || ''
  itemDialog.name = row?.name || ''
  itemDialog.category = row?.category || ''
  itemDialog.owner = row?.owner || ''
  itemDialog.description = row?.description || ''
  itemDialog.origin = row?.origin || ''
  itemDialog.status = row?.status || 'ACTIVE'
  itemDialog.quantity = row?.quantity ?? null
  itemDialog.visible = true
}

async function submitItem() {
  if (!itemDialog.name.trim()) {
    ElMessage.warning('请填写物品名称')
    return
  }
  itemSaving.value = true
  try {
    const payload = {
      name: itemDialog.name.trim(),
      category: itemDialog.category,
      owner: itemDialog.owner,
      description: itemDialog.description,
      origin: itemDialog.origin,
      status: itemDialog.status,
      quantity: itemDialog.quantity ?? undefined,
      // 编辑时把数量清空要显式告诉后端（null 默认表示不修改）
      clearQuantity: Boolean(itemDialog.id) && (itemDialog.quantity === null || itemDialog.quantity === undefined)
    }
    if (itemDialog.id) {
      await updateItem(itemDialog.id, payload)
    } else {
      await createItem(novelId.value, payload)
    }
    itemDialog.visible = false
    ElMessage.success('物品已保存')
    await loadItems()
  } catch (error) {
    ElMessage.error((error as Error).message || '保存失败')
  } finally {
    itemSaving.value = false
  }
}

async function removeItem(row: ItemVO) {
  try {
    await ElMessageBox.confirm(`确定删除物品「${row.name}」吗？`, '删除物品', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  try {
    await deleteItem(row.id)
    ElMessage.success('已删除')
    await loadItems()
  } catch (error) {
    ElMessage.error((error as Error).message || '删除失败')
  }
}

/* ---------- AI 创建物品 ---------- */
function openAiItemDialog() {
  aiItemDialog.prompt = ''
  aiItemDialog.count = 3
  aiItemElapsed.value = 0
  aiItemDialog.visible = true
}

async function submitAiItems() {
  const prompt = aiItemDialog.prompt.trim()
  if (prompt.length < 4) {
    ElMessage.warning('再多描述两句，AI 才知道要什么物品')
    return
  }
  aiItemGenerating.value = true
  aiItemElapsed.value = 0
  aiItemTimer = window.setInterval(() => {
    aiItemElapsed.value += 1
  }, 1000)
  try {
    const result = await generateItems(novelId.value, { prompt, count: aiItemDialog.count })
    aiItemDialog.visible = false
    await loadItems()
    const skipped = result.skipped && result.skipped.length ? `，跳过重名 ${result.skipped.length} 个` : ''
    ElMessage.success(`已创建 ${result.items.length} 个物品${skipped}`)
  } catch (error) {
    ElMessage.error((error as Error).message || 'AI 生成失败，请重试')
  } finally {
    aiItemGenerating.value = false
    stopAiItemTimer()
  }
}

function stopAiItemTimer() {
  if (aiItemTimer) {
    window.clearInterval(aiItemTimer)
    aiItemTimer = undefined
  }
}

/* ---------- 工具 ---------- */
async function refreshNovel() {
  const detail = await getNovelDetail(novelId.value)
  novel.value = detail.novel
}

function chapterStatusLabel(value: string) {
  return CHAPTER_STATUS.find((item) => item.value === value)?.label ?? value
}

function chapterStatusType(value: string) {
  if (value === 'DONE') {
    return 'success'
  }
  if (value === 'WRITING') {
    return 'warning'
  }
  return 'info'
}

function itemStatusLabel(value: string) {
  return ITEM_STATUS.find((item) => item.value === value)?.label ?? '活跃'
}

function itemStatusType(value: string) {
  if (value === 'DESTROYED') {
    return 'danger'
  }
  if (value === 'ABANDONED') {
    return 'info'
  }
  if (value === 'LOST' || value === 'CONSUMED') {
    return 'warning'
  }
  return 'success'
}

function eventStatusLabel(value: string) {
  return EVENT_STATUS.find((item) => item.value === value)?.label ?? '未使用'
}

function eventStatusType(value: string) {
  if (value === 'DONE') {
    return 'success'
  }
  if (value === 'ONGOING') {
    return 'warning'
  }
  if (value === 'ABANDONED') {
    return 'danger'
  }
  return 'info'
}

function roleLabel(value?: string) {
  return ROLE_OPTIONS.find((item) => item.value === value)?.label ?? '配角'
}

function roleType(value?: string) {
  if (value === 'PROTAGONIST') {
    return 'primary'
  }
  if (value === 'ANTAGONIST') {
    return 'danger'
  }
  return 'info'
}
</script>

<style scoped>
.workbench {
  display: flex;
  gap: 16px;
  min-height: calc(100vh - 96px);
}

.wb-side {
  width: 208px;
  flex: 0 0 208px;
  padding: 16px 12px;
  background: var(--sf-bg-elevated, #fff);
  border: 1px solid var(--sf-border, #ebeef5);
  border-radius: 12px;
  display: flex;
  flex-direction: column;
}

.wb-book {
  display: flex;
  gap: 10px;
  align-items: center;
  padding-bottom: 14px;
  border-bottom: 1px solid var(--sf-border, #ebeef5);
}

.wb-cover {
  width: 42px;
  height: 58px;
  border-radius: 6px;
  color: #fff;
  font-size: 15px;
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
  letter-spacing: 1px;
  flex: 0 0 42px;
}

.wb-book-meta {
  min-width: 0;
}

.wb-title {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--sf-text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.wb-sub {
  margin: 4px 0 0;
  font-size: 11px;
  color: var(--sf-text-muted);
}

.wb-menu {
  margin-top: 12px;
  border-right: none;
  background: transparent;
}

.wb-menu :deep(.el-menu-item) {
  height: 40px;
  line-height: 40px;
  border-radius: 8px;
  margin-bottom: 4px;
}

.wb-menu :deep(.el-menu-item.is-active) {
  background: rgba(59, 110, 246, 0.12);
}

.wb-side-foot {
  margin-top: auto;
  padding-top: 12px;
  border-top: 1px solid var(--sf-border, #ebeef5);
}

.wb-main {
  flex: 1;
  min-width: 0;
  background: var(--sf-bg-elevated, #fff);
  border: 1px solid var(--sf-border, #ebeef5);
  border-radius: 12px;
  padding: 20px 22px 28px;
}

.panel-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 18px;
}

.panel-head h2 {
  margin: 0;
  font-size: 17px;
  font-weight: 600;
  color: var(--sf-text-primary);
}

.panel-head p {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--sf-text-muted);
}

.panel-actions {
  display: flex;
  gap: 8px;
  align-items: center;
}

.section-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 8px 0 14px;
  padding-top: 16px;
  border-top: 1px dashed var(--sf-border, #ebeef5);
  font-size: 14px;
  font-weight: 600;
  color: var(--sf-text-primary);
}

.basic-form {
  width: 100%;
  max-width: 100%;
}

/* 输入框/文本域随容器宽度自适应，不再被固定宽度卡住 */
.basic-form :deep(.el-form-item__content) {
  min-width: 0;
}

.basic-form :deep(.el-input),
.basic-form :deep(.el-textarea),
.basic-form :deep(.el-select),
.basic-form :deep(.el-input-number) {
  width: 100%;
}

.basic-form :deep(.el-input__wrapper),
.basic-form :deep(.el-textarea__inner) {
  width: 100%;
  box-sizing: border-box;
}

/* ---------- 总纲 ---------- */
.mo-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.mo-item {
  border: 1px solid var(--sf-border, #ebeef5);
  border-radius: 10px;
  padding: 14px 16px;
  background: var(--sf-bg-elevated, #fff);
}

.mo-head {
  display: flex;
  align-items: center;
  gap: 10px;
}

.mo-index {
  font-size: 12px;
  color: var(--sf-text-muted);
}

.mo-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--sf-text-primary);
}

.mo-content {
  margin: 8px 0 0;
  font-size: 13px;
  line-height: 1.75;
  color: var(--sf-text-muted);
  white-space: pre-wrap;
}

.mo-actions {
  margin-top: 8px;
  display: flex;
  justify-content: flex-end;
  gap: 4px;
}

.empty-block {
  padding: 40px 0;
  text-align: center;
  color: var(--sf-text-muted);
  font-size: 13px;
}

.empty-block.small {
  padding: 20px 0;
  font-size: 12px;
}

.volume-block {
  margin-bottom: 18px;
}

.volume-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
}

.volume-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--sf-text-primary);
}

.volume-count {
  font-size: 11px;
  color: var(--sf-text-muted);
}

.chapter-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  border: 1px solid var(--sf-border, #ebeef5);
  border-radius: 8px;
  margin-bottom: 6px;
}

.chapter-no {
  font-size: 12px;
  color: var(--sf-text-muted);
  flex: 0 0 64px;
}

.chapter-main {
  flex: 1;
  min-width: 0;
}

.chapter-title {
  font-size: 13px;
  color: var(--sf-text-primary);
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.chapter-title-text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chapter-title .el-tag {
  flex: none;
}

.chapter-summary {
  margin-top: 3px;
  font-size: 11px;
  color: var(--sf-text-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chapter-links {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-top: 5px;
}

.row-actions {
  flex: 0 0 auto;
}

.character-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: 12px;
}

/* ---------- 人物面板 · 背包 ---------- */
.bag-layout {
  display: flex;
  gap: 14px;
  align-items: flex-start;
}

.bag-side {
  width: 176px;
  flex: 0 0 176px;
  border: 1px solid var(--sf-border, #ebeef5);
  border-radius: 10px;
  padding: 10px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-height: 560px;
}

.bag-side-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 12px;
  font-weight: 600;
  color: var(--sf-text-primary);
}

.bag-person-list {
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.bag-person {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
  padding: 6px 8px;
  border: none;
  border-radius: 6px;
  background: transparent;
  cursor: pointer;
  font-size: 13px;
  color: var(--sf-text-regular);
  text-align: left;
}

.bag-person:hover {
  background: var(--el-fill-color-light, #f5f7fa);
}

.bag-person.active {
  background: var(--el-color-primary-light-9, #ecf5ff);
  color: var(--el-color-primary, #409eff);
  font-weight: 600;
}

.bag-person-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.bag-person-count {
  font-size: 11px;
  color: var(--sf-text-muted);
}

.bag-main {
  flex: 1;
  min-width: 0;
}

.bag-main-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 10px;
}

.bag-main-head h3 {
  margin: 0;
  font-size: 14px;
  color: var(--sf-text-primary);
}

.bag-main-head p {
  margin: 4px 0 0;
  font-size: 12px;
  line-height: 1.5;
}

.bag-form {
  margin-bottom: 10px;
}

.bag-name {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.bag-item-desc {
  width: 100%;
  font-size: 11px;
  color: var(--sf-text-muted);
  line-height: 1.4;
}

.character-card {
  border: 1px solid var(--sf-border, #ebeef5);
  border-radius: 10px;
  padding: 14px;
}

.character-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.character-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--sf-text-primary);
}

.character-line {
  margin: 6px 0 0;
  font-size: 12px;
  color: var(--sf-text-muted);
}

.character-desc {
  margin: 8px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--sf-text-regular);
  min-height: 38px;
}

.character-bag {
  margin-top: 6px;
  font-size: 12px;
  line-height: 1.5;
  color: var(--sf-text-regular);
  overflow: hidden;
  text-overflow: ellipsis;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

.character-foot {
  margin-top: 8px;
  display: flex;
  justify-content: flex-end;
}

.asset-table {
  width: 100%;
}

.participant-tag {
  margin-right: 4px;
}

.quantity-row {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
}

.quantity-input {
  width: 140px;
}

.item-detail {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 18px;
  padding: 6px 44px 10px;
}

.detail-block p {
  margin: 4px 0 0;
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--sf-text-regular);
  white-space: pre-wrap;
}

.detail-label {
  display: inline-block;
  padding: 0 6px;
  font-size: 11px;
  color: var(--sf-text-regular);
  background: var(--sf-bg-subtle);
  border-radius: 4px;
}

.faction-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 12px;
}

.faction-card {
  border: 1px solid var(--sf-border, #ebeef5);
  border-radius: 10px;
  padding: 14px;
  display: flex;
  flex-direction: column;
}

.faction-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.faction-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--sf-text-primary);
}

.faction-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 6px;
}

.faction-line {
  margin: 8px 0 0;
  font-size: 12px;
  color: var(--sf-text-regular);
  line-height: 1.6;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.line-label {
  display: inline-block;
  margin-right: 6px;
  padding: 0 6px;
  font-size: 11px;
  color: var(--sf-text-muted);
  background: var(--sf-bg-page);
  border-radius: 4px;
}

.faction-members {
  margin-top: 10px;
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  min-height: 24px;
}

.faction-foot {
  margin-top: auto;
  padding-top: 10px;
  display: flex;
  justify-content: flex-end;
}

.member-add {
  display: flex;
  gap: 8px;
  margin-bottom: 14px;
}

.member-select {
  width: 200px;
}

.member-position {
  flex: 1;
}

.character-asset-tabs {
  margin-top: -4px;
}

.ai-generating {
  padding: 26px 20px 18px;
  text-align: center;
}

.spin {
  font-size: 28px;
  color: var(--sf-brand);
  animation: ai-rotating 1.2s linear infinite;
}

@keyframes ai-rotating {
  to {
    transform: rotate(360deg);
  }
}

.ai-title {
  margin: 14px 0 4px;
  font-size: 15px;
  font-weight: 600;
  color: var(--sf-text-primary);
}

.ai-desc {
  margin: 0;
  font-size: 12px;
  color: var(--sf-text-muted);
}

.ai-elapsed {
  margin: 10px 0 0;
  font-size: 12px;
  color: var(--sf-text-muted);
}

.ai-samples {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin: 0 0 14px 70px;
}

.sample-chip {
  padding: 4px 10px;
  font-size: 12px;
  color: var(--sf-text-regular);
  background: var(--sf-bg-page);
  border: 1px solid transparent;
  border-radius: 12px;
  cursor: pointer;
}

.sample-chip:hover {
  border-color: rgba(59, 110, 246, 0.45);
  color: var(--sf-brand);
}

.ai-hint {
  margin-left: 10px;
  font-size: 11px;
  color: var(--sf-text-muted);
}

.count-select {
  width: 110px;
}

.relation-layout {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.relation-panel {
  border: 1px solid var(--sf-border, #ebeef5);
  border-radius: 10px;
  padding: 12px 14px 14px;
  min-height: 320px;
}

.relation-panel-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 10px;
  font-size: 13px;
  font-weight: 600;
  color: var(--sf-text-primary);
}

.muted {
  font-size: 11px;
  font-weight: 400;
  color: var(--sf-text-muted);
}

.relation-panel-graph {
  padding-bottom: 8px;
}

.content-layout {
  display: flex;
  gap: 14px;
  align-items: flex-start;
}

.content-list {
  width: 220px;
  flex: 0 0 220px;
  max-height: 620px;
  overflow-y: auto;
  border-right: 1px solid var(--sf-border, #ebeef5);
  padding-right: 8px;
}

.content-item {
  display: block;
  width: 100%;
  text-align: left;
  padding: 8px 10px;
  margin-bottom: 4px;
  border: 1px solid transparent;
  border-radius: 8px;
  background: transparent;
  cursor: pointer;
}

.content-item:hover {
  background: rgba(59, 110, 246, 0.06);
}

.content-item.active {
  background: rgba(59, 110, 246, 0.12);
  border-color: rgba(59, 110, 246, 0.35);
}

.content-item-title {
  display: block;
  font-size: 12px;
  color: var(--sf-text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.content-item-meta {
  display: block;
  margin-top: 3px;
  font-size: 11px;
  color: var(--sf-text-muted);
}

.content-editor {
  flex: 1;
  min-width: 0;
}

.editor-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
}

.editor-title {
  font-size: 13px;
  color: var(--sf-text-muted);
}

.editor-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.status-mini {
  width: 96px;
}

.editor-words {
  font-size: 12px;
  color: var(--sf-text-muted);
}

.editor-title-input {
  margin-bottom: 10px;
}

.editor-textarea :deep(textarea) {
  font-family: inherit;
  line-height: 1.9;
  font-size: 14px;
}

/* 查看态标题：不用输入框，避免被当成可编辑区域 */
.editor-title-text {
  margin: 2px 0 10px;
  font-size: 17px;
  font-weight: 600;
  color: var(--sf-text-primary);
}

/* 查看态正文：按段落排版，长文在框内滚动，不把整页撑开 */
.content-readonly {
  min-height: 420px;
  max-height: 62vh;
  overflow-y: auto;
  padding: 18px 20px;
  border: 1px solid var(--sf-border);
  border-radius: 8px;
  background: var(--sf-editor-bg);
  font-size: 14px;
  line-height: 1.9;
  color: var(--sf-text-primary);
}

.content-readonly p {
  margin: 0 0 0.9em;
  text-indent: 2em;
  white-space: pre-wrap;
  word-break: break-word;
}

.content-readonly p:last-child {
  margin-bottom: 0;
}

.content-readonly-empty {
  text-indent: 0;
  color: var(--sf-text-muted);
}

/* 已完成锁定提示：跟随 Element 警告色，深浅主题都自动适配 */
.content-lock-hint {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  margin-bottom: 10px;
  padding: 8px 12px;
  border: 1px solid var(--el-color-warning-light-5);
  border-radius: 6px;
  background: var(--el-color-warning-light-9);
  color: var(--el-color-warning);
  font-size: 12px;
  line-height: 1.6;
}

.w-full {
  width: 100%;
}

@media (max-width: 1100px) {
  .workbench {
    flex-direction: column;
  }

  .wb-side {
    width: auto;
    flex: none;
  }

  .content-layout {
    flex-direction: column;
  }

  .content-list {
    width: 100%;
    flex: none;
    max-height: 220px;
    border-right: none;
    border-bottom: 1px solid var(--sf-border, #ebeef5);
  }

  .relation-layout {
    grid-template-columns: 1fr;
  }
}

.form-hint {
  margin: -4px 0 12px 80px;
  font-size: 11px;
  line-height: 1.6;
  color: var(--sf-text-muted);
}

.ai-input-note {
  margin-bottom: 14px;
}

.editor-links {
  margin-bottom: 8px;
}

/* ---------- 分析 ---------- */
.analysis-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  margin-bottom: 16px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  background: var(--el-fill-color-lighter);
}

.analysis-chapter,
.analysis-target {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
}

.analysis-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.analysis-chapter-title {
  font-size: 14px;
  color: var(--el-text-color-primary);
}

.analysis-progress {
  width: 180px;
}

.analysis-table {
  border-radius: 10px;
  border: 1px solid var(--el-border-color-lighter);
}

.analysis-table :deep(.el-table__row) {
  cursor: pointer;
}

.analysis-table :deep(.el-table__expanded-cell) {
  padding: 0;
  background: var(--el-fill-color-blank);
}

.analysis-row-title {
  display: inline-flex;
  gap: 8px;
  align-items: baseline;
}

.analysis-row-no {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.analysis-running-tag {
  display: inline-flex;
  gap: 6px;
  align-items: center;
  color: var(--el-color-primary);
  font-size: 12px;
}

.analysis-expand {
  padding: 16px 20px 20px;
}

.analysis-expand-empty {
  display: flex;
  flex-direction: column;
  gap: 10px;
  align-items: flex-start;
  padding: 8px 0;
}

.analysis-expand-empty p {
  margin: 0;
}

.analysis-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px dashed var(--el-border-color-lighter);
}

.meta-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.analysis-running {
  padding: 48px 0;
}

.analysis-scores {
  display: grid;
  grid-template-columns: 190px 210px minmax(0, 1fr);
  gap: 16px;
  margin-bottom: 16px;
}

.score-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  padding: 16px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 12px;
  background: var(--el-bg-color);
}

.score-card.score-word {
  align-items: flex-start;
  justify-content: center;
}

.score-title {
  margin: 6px 0 0;
  font-size: 13px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.score-verdict {
  margin: 0;
  font-size: 12px;
  line-height: 1.7;
  color: var(--el-text-color-secondary);
}

.word-figure {
  display: flex;
  gap: 8px;
  align-items: baseline;
}

.word-figure strong {
  font-size: 30px;
  line-height: 1;
  color: var(--el-text-color-primary);
}

.word-range {
  margin: 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.analysis-warnings {
  margin-bottom: 16px;
}

.analysis-warnings p {
  margin: 2px 0;
  font-size: 12px;
  line-height: 1.7;
}

.analysis-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 16px;
}

.analysis-section {
  padding: 14px 16px;
  margin-bottom: 16px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 12px;
  background: var(--el-bg-color);
}

.section-head {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
  font-size: 14px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.outline-columns {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 14px;
}

.list-title {
  margin: 10px 0 6px;
  font-size: 12px;
  font-weight: 600;
  color: var(--el-text-color-secondary);
}

.list-title.ok {
  color: var(--el-color-success);
}

.list-title.miss {
  color: var(--el-color-warning);
}

.list-title.suggestion {
  color: var(--el-color-primary);
}

.analysis-list {
  padding-left: 16px;
  margin: 0;
  font-size: 13px;
  line-height: 1.85;
  color: var(--el-text-color-regular);
}

.ai-verdict {
  margin: 0 0 8px;
  font-size: 13px;
  color: var(--el-text-color-regular);
}

.signal-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.signal-row {
  display: flex;
  gap: 10px;
  align-items: center;
  font-size: 12px;
}

.signal-name {
  flex: none;
  width: 76px;
  color: var(--el-text-color-secondary);
}

.signal-detail {
  flex: 1;
  color: var(--el-text-color-regular);
}

.quote-list {
  padding: 8px 10px;
  margin: 8px 0 0;
  background: var(--el-fill-color-lighter);
  border-left: 2px solid var(--el-border-color);
  border-radius: 4px;
}

.quote-line {
  margin: 2px 0;
  font-size: 12px;
  line-height: 1.7;
  color: var(--el-text-color-secondary);
}

.state-before {
  color: var(--el-text-color-secondary);
}

.state-arrow {
  margin: 0 6px;
  color: var(--el-text-color-placeholder);
}

.state-after {
  color: var(--el-color-primary);
  font-weight: 600;
}

.same-tag {
  margin-left: 8px;
}

.analysis-empty {
  margin: 4px 0 12px;
  font-size: 12px;
}

@media (max-width: 1280px) {
  .analysis-scores {
    grid-template-columns: 1fr 1fr;
  }

  .analysis-grid {
    grid-template-columns: 1fr;
  }
}

/* ---------- 角色属性 / 背包 ---------- */
.character-tabs {
  margin-top: -8px;
}

.asset-form {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}

.asset-quick {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
  font-size: 12px;
  color: var(--sf-text-muted);
}

.asset-quick .el-link {
  font-size: 12px;
}

.asset-hint {
  padding: 24px 0;
  text-align: center;
  color: var(--sf-text-muted);
  font-size: 13px;
}

.asset-tip {
  font-size: 12px;
  color: var(--sf-text-muted);
}

.asset-table {
  margin-top: 4px;
}
</style>
