<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import router from '../router'
import api from '../api'

// 返修看板：cards 按返修阶段排列（返修中 / 待复检 / 已入库 / 已终止）
const board = ref({ cards: [], stageStats: [], methods: [] })
const detail = ref(null)
const selectedId = ref(null)
const stageFilter = ref('')
const loading = ref(false)
const busy = ref(false)          // 操作中按钮置灰，防现场重复点击（数据库行锁是最终防线）

// 返修完成登记表单
const completeForm = ref({ method: 'WELD', responsiblePerson: '', note: '', operator: '范国栋' })
// 复检表单
const inspector = ref('周立群')
const reinspectRemark = ref('')
const picked = ref([])
const defectMap = ref({})
// 更正表单
const correctionForm = ref({ target: 'REWORK', content: '', operator: '范国栋' })

const defectTypes = ref([])
const severities = ref([
  { code: 'MINOR', name: '轻微' }, { code: 'MAJOR', name: '一般' }, { code: 'CRITICAL', name: '严重' }
])

const STAGES = [
  { code: '', name: '全部' },
  { code: 'REWORKING', name: '返修中' },
  { code: 'REINSPECTING', name: '待复检' },
  { code: 'STORED', name: '已入库' },
  { code: 'SCRAPPED', name: '已终止' }
]

const cards = computed(() => board.value.cards || [])
const order = computed(() => detail.value?.order || null)
const casting = computed(() => detail.value?.casting || null)
const rounds = computed(() => detail.value?.rounds || [])
const events = computed(() => detail.value?.events || [])
const corrections = computed(() => detail.value?.corrections || [])
const methods = computed(() => board.value.methods || [])

const currentRoundView = computed(() => {
  const no = order.value?.currentRound
  return rounds.value.find((r) => r.round.roundNo === no) || null
})

/** 是否处于可登记完成：返修中 + 当前轮待返修 */
const canComplete = computed(() =>
  order.value?.stage === 'REWORKING' && currentRoundView.value?.round.roundStatus === 'PENDING')

/** 是否处于可发起复检：待复检 + 当前轮已完成且复检单未创建 */
const canReinspect = computed(() =>
  order.value?.stage === 'REINSPECTING'
  && currentRoundView.value?.round.roundStatus === 'COMPLETED'
  && !currentRoundView.value.round.reinspectId)

/** 存在已创建复检单的轮次（被冻结），才允许追加更正 */
const frozenRounds = computed(() => rounds.value.filter((r) => r.round.reinspectId))

const defectsOfReinspect = computed(() =>
  picked.value.map((code) => ({
    defectType: code,
    severity: (defectMap.value[code] || {}).severity || 'MINOR',
    position: (defectMap.value[code] || {}).position || '',
    sizeMm: (defectMap.value[code] || {}).sizeMm || null,
    quantity: (defectMap.value[code] || {}).quantity || 1,
    remark: (defectMap.value[code] || {}).remark || ''
  }))
)

function nameOf(code) {
  const t = defectTypes.value.find((x) => x.code === code)
  return t ? t.name : code
}
function colorOf(code) {
  const t = defectTypes.value.find((x) => x.code === code)
  return t ? t.color : '#8e24aa'
}
function methodName(code) {
  const m = methods.value.find((x) => x.code === code)
  return m ? m.name : code
}
function fmt(t) {
  if (!t) return '—'
  return String(t).replace('T', ' ').slice(5, 16)
}
function fullFmt(t) {
  return t ? String(t).replace('T', ' ').slice(0, 19) : '—'
}

function toggleType(code) {
  const idx = picked.value.indexOf(code)
  if (idx >= 0) {
    picked.value.splice(idx, 1)
  } else {
    picked.value.push(code)
    if (!defectMap.value[code]) {
      defectMap.value[code] = { severity: 'MINOR', position: '', sizeMm: null, quantity: 1, remark: '' }
    }
  }
}

function correctionsOfRound(roundId) {
  return corrections.value.filter((c) => c.roundId === roundId)
}

async function loadBoard(keepSelected = true) {
  loading.value = true
  try {
    board.value = await api.rework.board(stageFilter.value || undefined)
    if ((!selectedId.value || !cards.value.some((c) => c.order.id === selectedId.value)) && cards.value.length) {
      selectedId.value = cards.value[0].order.id
    }
    if (selectedId.value) await loadDetail(selectedId.value)
    else if (!keepSelected) detail.value = null
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

async function loadDetail(id) {
  try {
    detail.value = await api.rework.detail(id)
    // 每次打开/刷新都用服务端数据重置操作区，保证后提交方刷新后看到的是已生效的轮次与操作者
    picked.value = []
    defectMap.value = {}
    reinspectRemark.value = ''
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function selectCard(card) {
  selectedId.value = card.order.id
  await loadDetail(card.order.id)
}

async function submitComplete() {
  if (!order.value) return
  if (!completeForm.value.responsiblePerson.trim()) return ElMessage.warning('请填写返修责任人')
  if (!completeForm.value.note.trim()) return ElMessage.warning('请填写处置说明')
  busy.value = true
  try {
    await api.rework.complete(order.value.id, { ...completeForm.value })
    ElMessage.success('返修完成已登记，铸件转入待复检')
    await loadBoard()
  } catch (e) {
    // 并发失败：提示里带先成功者，立即刷新让页面与已生效轮次对齐
    ElMessage.error(e.message)
    await loadBoard()
  } finally {
    busy.value = false
  }
}

async function withdraw() {
  if (!order.value) return
  try {
    await ElMessageBox.confirm(
      '复检单尚未创建，撤回后本轮责任人/处置方式/完成时间将清空并可重新登记。确认撤回？',
      '撤回本轮返修完成结果', { confirmButtonText: '撤回并回到待返修', cancelButtonText: '取消', type: 'warning' })
  } catch {
    return
  }
  busy.value = true
  try {
    await api.rework.withdraw(order.value.id, completeForm.value.operator)
    ElMessage.success('已撤回，可重新登记返修完成')
    await loadBoard()
  } catch (e) {
    ElMessage.error(e.message)
    await loadBoard()
  } finally {
    busy.value = false
  }
}

async function submitReinspect(verdict) {
  if (!order.value) return
  if (verdict !== 'PASS' && picked.value.length === 0) {
    return ElMessage.warning('判「' + (verdict === 'SCRAP' ? '报废' : '返修') + '」必须至少选一类本轮缺陷')
  }
  const tip = verdict === 'PASS' ? '合格后铸件立即入库，返修单闭环'
    : verdict === 'SCRAP' ? '判废后返修链永久终止，铸件不可再入库'
      : '将保留本轮记录并生成下一轮返修待办'
  try {
    await ElMessageBox.confirm(`确认复检结论为「${verdict === 'PASS' ? '合格' : verdict === 'SCRAP' ? '报废' : '再返修'}」？${tip}。`,
      '复检判定', { confirmButtonText: '确认提交', cancelButtonText: '取消', type: verdict === 'SCRAP' ? 'warning' : 'info' })
  } catch {
    return
  }
  busy.value = true
  try {
    await api.rework.reinspect(order.value.id, {
      verdict,
      inspector: inspector.value,
      remark: reinspectRemark.value,
      defects: defectsOfReinspect.value
    })
    ElMessage.success(verdict === 'PASS' ? '复检合格，铸件已入库'
      : verdict === 'SCRAP' ? '复检判废，返修链终止' : '已生成下一轮返修待办')
    await loadBoard()
  } catch (e) {
    ElMessage.error(e.message)
    await loadBoard()
  } finally {
    busy.value = false
  }
}

async function submitCorrection() {
  if (!order.value) return
  if (!correctionForm.value.content.trim()) return ElMessage.warning('请填写更正说明内容')
  busy.value = true
  try {
    await api.rework.correct(order.value.id, { ...correctionForm.value })
    ElMessage.success('更正说明已追加，原始记录保留不变')
    correctionForm.value.content = ''
    await loadBoard()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    busy.value = false
  }
}

async function changeStage(stage) {
  stageFilter.value = stage
  selectedId.value = null
  detail.value = null
  await loadBoard(false)
}

onMounted(async () => {
  loading.value = true
  try {
    const inspBoard = await api.inspection.board()
    defectTypes.value = inspBoard.defectTypes || []
    severities.value = inspBoard.severities || severities.value
  } catch {
    // 字典拉不到不阻塞返修主流程
  }
  await loadBoard(false)
  loading.value = false
})
</script>

<template>
  <div v-loading="loading">
    <div class="view-head">
      <span class="view-title">返修处置与复检 · 闭环队列</span>
      <span class="view-tip">首次判返修进独立队列并留存缺陷快照；完成后只能基于上一轮缺陷复检：合格入库、报废终止、再返修开下一轮</span>
    </div>

    <div class="stage-row">
      <span
        v-for="s in STAGES" :key="s.code || 'ALL'"
        class="stage-chip" :class="{ on: stageFilter === s.code }"
        @click="changeStage(s.code)"
      >
        {{ s.name }}
        <b v-if="s.code">·{{ (board.stageStats.find((x) => x.stage === s.code) || {}).count ?? 0 }}</b>
      </span>
    </div>

    <div class="rw-wrap">
      <!-- 左：返修队列（按阶段定位积压） -->
      <section class="panel">
        <div class="panel-title">返修队列<span class="view-tip">一件铸件一张返修单，阶段标签即积压位置</span></div>
        <div v-if="cards.length" class="queue">
          <div
            v-for="c in cards" :key="c.order.id"
            class="q-card" :class="{ on: selectedId === c.order.id }"
            :style="{ borderLeftColor: c.order.stageColor }"
            @click="selectCard(c)"
          >
            <div class="q-head">
              <span class="q-no">{{ c.order.reworkNo }}</span>
              <span class="q-stage" :style="{ background: c.order.stageColor }">{{ c.order.stageName }}</span>
            </div>
            <div class="q-casting">{{ c.order.castingNo }} · {{ c.casting?.castingName || '—' }}</div>
            <div class="q-meta">
              <span>第 {{ c.order.currentRound }} 轮</span>
              <span v-if="c.currentRound">
                <em :style="{ color: c.currentRound.roundStatusColor }">{{ c.currentRound.roundStatusName }}</em>
              </span>
            </div>
            <div class="q-meta" v-if="c.currentRound?.responsiblePerson">
              责任人 <b>{{ c.currentRound.responsiblePerson }}</b> · {{ methodName(c.currentRound.reworkMethod) }}
            </div>
            <div class="q-snap" v-if="c.snapshotInspection">
              首次快照 {{ c.snapshotInspection.inspectNo }} · {{ c.snapshotInspection.defectCount }} 条缺陷
            </div>
          </div>
        </div>
        <div class="empty-tip" v-else>该阶段暂无返修单</div>
      </section>

      <!-- 右：详情时间线 -->
      <section class="panel detail-panel" v-if="order">
        <div class="panel-title">
          {{ order.reworkNo }} 详情
          <el-tag size="small" :style="{ background: order.stageColor, color: '#fff', border: 'none', marginLeft: '8px' }">
            {{ order.stageName }}
          </el-tag>
        </div>

        <div class="casting-line">
          <b>{{ order.castingNo }}</b>
          <span>{{ casting?.castingName }} · {{ casting?.materialName }}</span>
          <span v-if="casting?.heatNo">炉次 {{ casting.heatNo }}</span>
          <span v-if="casting?.moldNo">模具 {{ casting.moldNo }}</span>
          <el-button size="small" link type="primary" @click="router.push('/inspection')">旧检验记录去检验页查看 →</el-button>
        </div>

        <!-- 首次缺陷快照 -->
        <div class="snap-box" v-if="detail.snapshot">
          <div class="snap-title">🔒 首次缺陷快照（检验单 {{ detail.snapshot.inspection.inspectNo }}，永久留存不可改写）</div>
          <div class="snap-meta">
            检验员 {{ detail.snapshot.inspection.inspector || '—' }} · {{ fullFmt(detail.snapshot.inspection.inspectTime) }}
            · 硬度 {{ detail.snapshot.inspection.hardnessHb ?? '—' }} HB
          </div>
          <div class="defect-list">
            <span v-for="(d, i) in detail.snapshot.defects" :key="i" class="defect-pill" :style="{ borderColor: d.defectTypeColor }">
              <i :style="{ background: d.defectTypeColor }"></i>{{ d.defectTypeName }} · {{ d.severityName }}
              · {{ d.position || '未注明部位' }} · {{ d.sizeMm ?? '—' }}mm ×{{ d.quantity }}
            </span>
          </div>
          <div class="snap-remark" v-if="detail.snapshot.inspection.remark">备注：{{ detail.snapshot.inspection.remark }}</div>
        </div>

        <!-- 每轮处置与复检 -->
        <div class="rounds">
          <div v-for="rv in rounds" :key="rv.round.id" class="round" :class="{ current: rv.round.roundNo === order.currentRound && order.open }">
            <div class="r-axis">
              <span class="r-dot" :style="{ background: rv.round.roundStatusColor }"></span>
              <span class="r-line"></span>
            </div>
            <div class="r-body">
              <div class="r-head">
                <b>第 {{ rv.round.roundNo }} 轮</b>
                <el-tag size="small" :style="{ background: rv.round.roundStatusColor, color: '#fff', border: 'none' }">
                  {{ rv.round.roundStatusName }}
                </el-tag>
                <span class="r-base" v-if="rv.round.roundNo > 1">基于上一轮复检单 {{ rv.source?.inspection.inspectNo }}</span>
                <span class="r-base" v-else>基于首次检验单 {{ rv.source?.inspection.inspectNo }}</span>
              </div>

              <!-- 本轮依据的缺陷快照 -->
              <div class="r-defects">
                <span class="r-k">本轮缺陷：</span>
                <span v-for="(d, i) in (rv.source?.defects || [])" :key="i" class="defect-pill small" :style="{ borderColor: d.defectTypeColor }">
                  <i :style="{ background: d.defectTypeColor }"></i>{{ d.defectTypeName }}{{ d.position ? ' · ' + d.position : '' }} ×{{ d.quantity }}
                </span>
                <span v-if="!(rv.source?.defects || []).length" class="r-k">无</span>
              </div>

              <!-- 处置登记 -->
              <div class="r-rework" v-if="rv.round.completedAt">
                <div class="r-k">返修处置：</div>
                <div>
                  {{ methodName(rv.round.reworkMethod) }} · 责任人 <b>{{ rv.round.responsiblePerson }}</b>
                  · 完成 {{ fullFmt(rv.round.completedAt) }}
                  <span v-if="rv.round.completedBy">（登记：{{ rv.round.completedBy }}）</span>
                </div>
                <div class="r-note">{{ rv.round.reworkNote }}</div>
              </div>
              <div class="r-rework pending" v-else>
                <span class="r-k">返修处置：</span>尚未登记（待返修）
              </div>

              <!-- 复检结论 -->
              <div class="r-reinspect" v-if="rv.reinspection">
                <div class="r-k">复检结论（{{ rv.reinspection.inspection.inspectNo }}）：</div>
                <el-tag size="small" :style="{ background: rv.reinspection.inspection.verdictColor, color: '#fff', border: 'none' }">
                  {{ rv.reinspection.inspection.verdictName }}
                </el-tag>
                <span>检验员 {{ rv.reinspection.inspection.inspector || '—' }} · {{ fullFmt(rv.round.reinspectTime) }}</span>
                <div class="r-defects" v-if="rv.reinspection.defects?.length">
                  <span class="r-k">新发现：</span>
                  <span v-for="(d, i) in rv.reinspection.defects" :key="i" class="defect-pill small" :style="{ borderColor: d.defectTypeColor }">
                    <i :style="{ background: d.defectTypeColor }"></i>{{ d.defectTypeName }}{{ d.position ? ' · ' + d.position : '' }} ×{{ d.quantity }}
                  </span>
                </div>
                <div class="r-note" v-if="rv.reinspection.inspection.remark">备注：{{ rv.reinspection.inspection.remark }}</div>
              </div>
              <div class="r-reinspect pending" v-else-if="rv.round.roundStatus === 'COMPLETED'">
                <span class="r-k">复检：</span>返修已完成，待发起复检
              </div>

              <!-- 本轮更正说明（只追加） -->
              <div class="corrections" v-if="correctionsOfRound(rv.round.id).length">
                <div v-for="c in correctionsOfRound(rv.round.id)" :key="c.id" class="corr-item">
                  📝 更正【{{ c.targetTypeName }}】{{ c.content }}
                  <span class="corr-meta">— {{ c.operator || '未署名' }} · {{ fullFmt(c.createdAt) }}</span>
                </div>
              </div>
            </div>
          </div>
        </div>

        <!-- 操作区 -->
        <div class="op-zone">
          <!-- 闭环终态 -->
          <div v-if="order.stage === 'STORED'" class="closed-banner pass">✓ 复检合格已入库，返修闭环结束</div>
          <div v-else-if="order.stage === 'SCRAPPED'" class="closed-banner scrap">✕ 复检判废，返修链永久终止，铸件不可再入库</div>

          <!-- 登记返修完成 -->
          <template v-else-if="canComplete">
            <div class="op-title">登记返修完成<span class="op-hint">提交后铸件转「待复检」；下一轮复检单创建前仍可撤回</span></div>
            <el-form label-width="84px" size="small">
              <el-form-item label="处置方式">
                <el-select v-model="completeForm.method" style="width:200px">
                  <el-option v-for="m in methods" :key="m.code" :label="m.name" :value="m.code" />
                </el-select>
              </el-form-item>
              <el-form-item label="责任人">
                <el-input v-model="completeForm.responsiblePerson" placeholder="返修责任人姓名" style="width:200px" />
              </el-form-item>
              <el-form-item label="处置说明">
                <el-input v-model="completeForm.note" type="textarea" :rows="2" placeholder="处置部位、工艺与自检情况" />
              </el-form-item>
              <el-form-item label="登记人">
                <el-input v-model="completeForm.operator" style="width:200px" />
              </el-form-item>
            </el-form>
            <el-button type="primary" :loading="busy" @click="submitComplete">登记返修完成 →</el-button>
          </template>

          <!-- 撤回 + 发起复检 -->
          <template v-else-if="canReinspect">
            <div class="op-title">
              发起第 {{ currentRoundView.round.roundNo }} 轮复检
              <span class="op-hint">只能基于本轮已留存的缺陷；判再返修/报废须录本轮新缺陷</span>
            </div>
            <el-form label-width="84px" size="small">
              <el-form-item label="检验员"><el-input v-model="inspector" style="width:200px" /></el-form-item>
              <el-form-item label="本轮缺陷">
                <div class="badge-grid">
                  <div
                    v-for="t in defectTypes" :key="t.code"
                    class="badge" :class="{ on: picked.includes(t.code) }" :style="{ '--c': t.color }"
                    @click="toggleType(t.code)"
                  >
                    {{ t.name }}<span v-if="picked.includes(t.code)" class="tick">✓</span>
                  </div>
                </div>
              </el-form-item>
              <div v-if="picked.length" class="defect-rows">
                <div v-for="code in picked" :key="code" class="defect-row">
                  <span class="dot" :style="{ background: colorOf(code) }"></span>
                  <b style="width:56px">{{ nameOf(code) }}</b>
                  <el-select v-model="defectMap[code].severity" size="small" style="width:88px">
                    <el-option v-for="s in severities" :key="s.code" :label="s.name" :value="s.code" />
                  </el-select>
                  <el-input v-model="defectMap[code].position" size="small" placeholder="部位" style="width:100px" />
                  <el-input-number v-model="defectMap[code].sizeMm" size="small" :min="0" :step="0.1" placeholder="尺寸mm" style="width:110px" />
                  <el-input-number v-model="defectMap[code].quantity" size="small" :min="1" style="width:88px" />
                </div>
              </div>
              <el-form-item label="复检备注" style="margin-top:8px">
                <el-input v-model="reinspectRemark" type="textarea" :rows="2" />
              </el-form-item>
            </el-form>
            <div class="verdict-row">
              <el-button type="success" :loading="busy" @click="submitReinspect('PASS')">合格入库</el-button>
              <el-button type="warning" :loading="busy" @click="submitReinspect('REWORK')">再返修（开下一轮）</el-button>
              <el-button type="danger" :loading="busy" @click="submitReinspect('SCRAP')">报废终止</el-button>
            </div>
            <div class="withdraw-line">
              <el-button size="small" plain :loading="busy" @click="withdraw">撤回本轮完成结果</el-button>
              <span class="op-hint">复检单一旦创建，处置结果即冻结，只能在下方追加更正说明</span>
            </div>
          </template>
        </div>

        <!-- 追加更正（复检创建后唯一修正方式） -->
        <div class="correct-zone" v-if="frozenRounds.length">
          <div class="op-title">追加更正说明<span class="op-hint">历史证据不可改写，更正只追加到最近一轮已复检的轮次</span></div>
          <el-form label-width="84px" size="small">
            <el-form-item label="更正对象">
              <el-radio-group v-model="correctionForm.target">
                <el-radio value="REWORK">返修处置</el-radio>
                <el-radio value="REINSPECT">复检结论</el-radio>
              </el-radio-group>
            </el-form-item>
            <el-form-item label="更正内容">
              <el-input v-model="correctionForm.content" type="textarea" :rows="2" placeholder="说明误填项与正确内容，原始记录保留" />
            </el-form-item>
            <el-form-item label="操作人"><el-input v-model="correctionForm.operator" style="width:200px" /></el-form-item>
          </el-form>
          <el-button size="small" type="warning" plain :loading="busy" @click="submitCorrection">追加更正（不改写原值）</el-button>
        </div>

        <!-- 事件时间线 -->
        <div class="events">
          <div class="op-title">操作时间线（只增不改不删）</div>
          <div v-for="e in [...events].reverse()" :key="e.id" class="ev-item">
            <span class="ev-dot" :style="{ background: e.eventColor }"></span>
            <span class="ev-time">{{ fmt(e.createdAt) }}</span>
            <el-tag size="small" :style="{ background: e.eventColor, color: '#fff', border: 'none' }">{{ e.eventName }}</el-tag>
            <span class="ev-detail">{{ e.detail }}</span>
            <span class="ev-op" v-if="e.operator">{{ e.operator }}</span>
          </div>
        </div>
      </section>

      <section class="panel" v-else><div class="empty-tip">请选择左侧返修单查看闭环详情</div></section>
    </div>
  </div>
</template>

<style scoped>
.stage-row { display: flex; gap: 8px; margin-bottom: 10px; flex-wrap: wrap; }
.stage-chip {
  padding: 5px 14px;
  border-radius: 16px;
  background: #fff;
  border: 1px solid var(--line-strong);
  font-size: 12.5px;
  cursor: pointer;
  color: var(--ink-soft);
}
.stage-chip b { color: var(--primary-deep); margin-left: 2px; }
.stage-chip.on { background: var(--primary); color: #fff; border-color: var(--primary); }
.stage-chip.on b { color: #fff; }

.rw-wrap { display: grid; grid-template-columns: minmax(280px, 0.85fr) minmax(0, 1.4fr); gap: 12px; align-items: start; }

.queue { display: flex; flex-direction: column; gap: 8px; }
.q-card {
  padding: 9px 11px;
  border-radius: 10px;
  border: 1px solid var(--line-strong);
  border-left: 5px solid var(--primary);
  background: #fff;
  cursor: pointer;
  transition: box-shadow .15s ease;
}
.q-card:hover { box-shadow: 0 6px 16px rgba(78, 40, 96, 0.12); }
.q-card.on { box-shadow: 0 0 0 2px var(--primary-soft); }
.q-head { display: flex; justify-content: space-between; align-items: center; }
.q-no { font-weight: 800; color: var(--primary-deep); font-size: 13.5px; }
.q-stage { color: #fff; font-size: 10.5px; padding: 2px 8px; border-radius: 6px; }
.q-casting { font-size: 12px; margin: 3px 0; }
.q-meta { font-size: 11.5px; color: var(--ink-dim); display: flex; gap: 10px; }
.q-meta em { font-style: normal; font-weight: 700; }
.q-snap { font-size: 11px; color: var(--ink-dim); margin-top: 3px; padding-top: 3px; border-top: 1px dashed var(--line); }

.detail-panel { max-height: none; }
.casting-line { display: flex; gap: 12px; align-items: center; flex-wrap: wrap; font-size: 12.5px; color: var(--ink-soft); margin-bottom: 8px; }
.casting-line b { color: var(--primary-deep); font-size: 14px; }

.snap-box {
  border: 1px dashed var(--primary-soft);
  background: repeating-linear-gradient(135deg, #f7f1fa 0 8px, #fbf8fd 8px 16px);
  border-radius: 10px;
  padding: 9px 11px;
  margin-bottom: 12px;
}
.snap-title { font-size: 12.5px; font-weight: 700; color: var(--primary-deep); margin-bottom: 4px; }
.snap-meta { font-size: 11.5px; color: var(--ink-dim); margin-bottom: 6px; }
.snap-remark { font-size: 11.5px; color: var(--ink-soft); margin-top: 5px; }

.defect-list { display: flex; flex-wrap: wrap; gap: 6px; }
.defect-pill {
  display: inline-flex; align-items: center; gap: 4px;
  border: 1px solid var(--line-strong);
  border-radius: 7px;
  padding: 2px 8px;
  font-size: 11.5px;
  background: #fff;
}
.defect-pill.small { font-size: 11px; padding: 1px 7px; }
.defect-pill i { width: 7px; height: 7px; border-radius: 50%; display: inline-block; }

.rounds { margin: 6px 0 12px; }
.round { display: flex; gap: 10px; }
.r-axis { display: flex; flex-direction: column; align-items: center; width: 14px; }
.r-dot { width: 11px; height: 11px; border-radius: 50%; margin-top: 4px; flex: 0 0 auto; }
.r-line { flex: 1; width: 2px; background: var(--line); min-height: 26px; }
.round:last-child .r-line { display: none; }
.r-body { flex: 1; padding-bottom: 14px; }
.round.current .r-body { background: #fcf9fe; border-radius: 8px; padding: 6px 8px 12px; margin-left: -4px; }
.r-head { display: flex; align-items: center; gap: 8px; margin-bottom: 5px; }
.r-base { font-size: 11px; color: var(--ink-dim); }
.r-defects { display: flex; gap: 6px; flex-wrap: wrap; align-items: center; font-size: 11.5px; margin: 3px 0; }
.r-k { color: var(--ink-dim); font-size: 11.5px; }
.r-rework, .r-reinspect { font-size: 12px; color: var(--ink); margin: 3px 0; }
.r-rework.pending, .r-reinspect.pending { color: var(--ink-dim); }
.r-note { font-size: 11.5px; color: var(--ink-soft); background: #faf7fc; border-radius: 6px; padding: 4px 8px; margin-top: 3px; }

.corrections { margin-top: 5px; }
.corr-item {
  font-size: 11.5px;
  background: #fff8e6;
  border-left: 3px solid var(--warn);
  border-radius: 0 6px 6px 0;
  padding: 4px 8px;
  margin-bottom: 4px;
  color: #7a5b12;
}
.corr-meta { color: var(--ink-dim); margin-left: 4px; }

.op-zone, .correct-zone, .events {
  border-top: 1px dashed var(--line-strong);
  padding-top: 10px;
  margin-top: 10px;
}
.op-title { font-size: 13px; font-weight: 800; color: var(--primary-deep); margin-bottom: 8px; display: flex; gap: 10px; align-items: baseline; }
.op-hint { font-size: 11px; font-weight: 400; color: var(--ink-dim); }

.badge-grid { display: grid; grid-template-columns: repeat(6, minmax(0, 1fr)); gap: 6px; width: 100%; }
.badge {
  position: relative;
  padding: 8px 4px;
  border-radius: 9px;
  border: 2px solid var(--line-strong);
  background: #fff;
  text-align: center;
  cursor: pointer;
  font-size: 12px;
  font-weight: 700;
  color: var(--ink-soft);
}
.badge.on { border-color: var(--c); color: #fff; background: var(--c); }
.tick { position: absolute; right: 4px; top: 2px; font-size: 10px; }
.defect-rows { width: 100%; }
.defect-row { display: flex; align-items: center; gap: 6px; margin-bottom: 6px; flex-wrap: wrap; }
.dot { width: 8px; height: 8px; border-radius: 50%; }

.verdict-row { display: flex; gap: 10px; margin: 4px 0 8px; }
.withdraw-line { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }

.closed-banner { border-radius: 10px; padding: 12px 14px; font-weight: 800; font-size: 13.5px; }
.closed-banner.pass { background: #e8f5e9; color: #2e7d32; border: 1px solid #a5d6a7; }
.closed-banner.scrap { background: #fdecea; color: #c62828; border: 1px solid #ef9a9a; }

.ev-item { display: flex; align-items: center; gap: 8px; font-size: 11.5px; margin-bottom: 6px; flex-wrap: wrap; }
.ev-dot { width: 8px; height: 8px; border-radius: 50%; }
.ev-time { color: var(--ink-dim); width: 78px; }
.ev-detail { color: var(--ink-soft); flex: 1; min-width: 180px; }
.ev-op { color: var(--primary-deep); font-weight: 700; }
</style>
