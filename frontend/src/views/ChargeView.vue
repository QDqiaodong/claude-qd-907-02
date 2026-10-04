<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api'

const sheets = ref([])
const detail = ref(null)
const board = ref({ heats: [], materials: [], maxDeviationRate: 3 })
const selectedId = ref(null)
const createOpen = ref(false)
const loading = ref(false)

const form = ref({
  chargeNo: '',
  heatId: null,
  operator: '吴桂芳',
  remark: '',
  items: [{ materialCode: 'PIG_IRON', weightKg: 400 }, { materialCode: 'SCRAP', weightKg: 500 }]
})
const step = ref(10)

const maxRate = computed(() => Number(board.value.maxDeviationRate ?? 3))

const selected = computed(() => sheets.value.find((c) => c.id === selectedId.value) || null)
const items = computed(() => detail.value?.items || [])
const totalWeight = computed(() =>
  items.value.reduce((sum, i) => sum + Number(i.weightKg || 0), 0)
)
const ratioSum = computed(() => items.value.reduce((sum, i) => sum + Number(i.ratio || 0), 0))
const ratioOff = computed(() => Math.abs(ratioSum.value - 100) > 0.5)

// 新建表单的实时偏差：与所选炉次的出炉重量比，超过允许比例就标红
const formTotal = computed(() =>
  form.value.items.reduce((sum, i) => sum + Number(i.weightKg || 0), 0)
)
const formTarget = computed(() => {
  const heat = (board.value.heats || []).find((h) => h.id === form.value.heatId)
  return heat ? Number(heat.tapWeight || heat.planWeight || 0) : 0
})
const formDeviation = computed(() => {
  if (!formTarget.value) return null
  return ((formTotal.value - formTarget.value) / formTarget.value) * 100
})
const formOver = computed(() =>
  formDeviation.value !== null && Math.abs(formDeviation.value) > maxRate.value
)

function materialName(code) {
  const m = (board.value.materials || []).find((x) => x.code === code)
  return m ? m.name : code
}
function materialColor(code) {
  const m = (board.value.materials || []).find((x) => x.code === code)
  return m ? m.color : '#8e24aa'
}

async function selectSheet(sheet) {
  selectedId.value = sheet.id
  loading.value = true
  try {
    detail.value = await api.charge.detail(sheet.id)
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

/** 逐项 ± 微调 */
async function adjust(item, delta) {
  loading.value = true
  try {
    await api.charge.adjust(selectedId.value, { materialCode: item.materialCode, delta })
    ElMessage.success((delta > 0 ? '+' : '') + delta + ' kg 已并入「' + item.materialRealName + '」')
    await Promise.all([loadSheets(), api.charge.detail(selectedId.value).then((d) => (detail.value = d))])
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

async function createCharge() {
  if (!form.value.chargeNo) return ElMessage.warning('请填配料单号')
  if (formOver.value) return ElMessage.warning('配料偏差超过允许比例，请调整后再提交')
  loading.value = true
  try {
    await api.charge.create(form.value)
    ElMessage.success('配料单已建立')
    createOpen.value = false
    await loadSheets()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

async function removeSheet() {
  if (!selected.value) return
  loading.value = true
  try {
    await api.charge.remove(selected.value.id)
    ElMessage.success('配料单已删除')
    selectedId.value = null
    detail.value = null
    await loadSheets()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

function addRow() {
  form.value.items.push({ materialCode: 'SCRAP', weightKg: 100 })
}
function removeRow(idx) {
  form.value.items.splice(idx, 1)
}

async function loadSheets() {
  sheets.value = await api.charge.list()
  if (selectedId.value === null && sheets.value.length) {
    await selectSheet(sheets.value[0])
  } else if (selectedId.value) {
    detail.value = await api.charge.detail(selectedId.value)
  }
}

async function load() {
  loading.value = true
  try {
    board.value = await api.charge.board()
    await loadSheets()
    await loadSimHeats()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

onMounted(load)

// ---------------- 配料偏差双方案推演（只读试算，不改动炉次/配料/炉体） ----------------
const simHeats = ref([])
const simMaterials = ref([])
const simMaxItemWeight = ref(2000)
const simForm = ref({ heatId: null, targetWeight: null, allowNew: true, caps: {} })
const simResult = ref(null)
const simLoading = ref(false)
const simError = ref('')
let simSeq = 0 // 输入版本号：较慢的旧计算返回时不得覆盖新结果
let simTimer = null

const simHeat = computed(() => simHeats.value.find((h) => h.id === simForm.value.heatId) || null)
const simSchemes = computed(() => {
  if (!simResult.value) return []
  return [simResult.value.minCost, simResult.value.minDeviation].filter(Boolean)
})

const f2 = (v) => (v === null || v === undefined || Number.isNaN(Number(v)) ? '—' : Number(v).toFixed(2))

async function loadSimHeats() {
  const data = await api.charge.simulateHeats()
  simHeats.value = data.heats || []
  simMaterials.value = data.materials || []
  simMaxItemWeight.value = Number(data.maxItemWeight || 2000)
  const caps = {}
  for (const m of simMaterials.value) caps[m.code] = simMaxItemWeight.value
  simForm.value.caps = caps
  if (simHeats.value.length && !simForm.value.heatId) {
    simForm.value.heatId = simHeats.value[0].id
    syncSimTarget()
  }
}

function syncSimTarget() {
  const h = simHeat.value
  simForm.value.targetWeight = h && h.planWeight ? Number(h.planWeight) : null
}

async function runSimulate() {
  if (!simForm.value.heatId) {
    simResult.value = null
    return
  }
  if (!simForm.value.targetWeight || simForm.value.targetWeight <= 0) {
    simResult.value = null
    simError.value = '请填写目标重量（炉次无计划重量时必须在页面指定）'
    return
  }
  const seq = ++simSeq
  simLoading.value = true
  simError.value = ''
  try {
    const res = await api.charge.simulate({
      heatId: simForm.value.heatId,
      targetWeight: simForm.value.targetWeight,
      allowNewMaterial: simForm.value.allowNew,
      itemCaps: { ...simForm.value.caps }
    })
    if (seq !== simSeq) return // 已有更新的输入在算，这份过期结果直接丢弃
    simResult.value = res
  } catch (e) {
    if (seq !== simSeq) return
    simResult.value = null
    simError.value = e.message
  } finally {
    if (seq === simSeq) simLoading.value = false
  }
}

/** 目标重量 / 单项上限 / 是否允许新料变化 → 防抖重算，两套方案始终基于同一输入版本 */
function queueSimulate() {
  clearTimeout(simTimer)
  simTimer = setTimeout(runSimulate, 350)
}

watch(
  () => simForm.value.heatId,
  () => {
    syncSimTarget()
    queueSimulate()
  }
)
watch(
  () => [simForm.value.targetWeight, simForm.value.allowNew, JSON.stringify(simForm.value.caps)],
  queueSimulate
)
</script>

<template>
  <div>
    <div class="view-head">
      <span class="view-title">熔炼工段 · 炉料配比滑杆条</span>
      <span class="view-tip">每条横向滑杆＝一种炉料占比；合计不对 100% 会标红，逐项 ± 微调实时重算占比与偏差</span>
    </div>

    <div class="grid-2">
      <section class="panel">
        <div class="panel-title">
          配比滑杆
          <span class="view-tip" v-if="selected">
            {{ selected.chargeNo }} · 合计 {{ totalWeight.toFixed(2) }} kg · 占合计 {{ ratioSum.toFixed(2) }}%
          </span>
          <span :class="['sum-flag', { over: ratioOff }]">{{ ratioSum.toFixed(2) }}%</span>
        </div>

        <div v-if="items.length">
          <div class="slide-row" v-for="item in items" :key="item.id">
            <div class="slide-head">
              <span class="mat-name">
                <span class="dot" :style="{ background: item.materialColor }"></span>
                {{ item.materialRealName }}
              </span>
              <span class="mat-weight">{{ Number(item.weightKg).toFixed(2) }} kg</span>
              <span class="mat-ratio">{{ Number(item.ratio).toFixed(2) }}%</span>
            </div>
            <div class="slide-track">
              <div class="slide-bar" :style="{ width: Number(item.ratio) + '%', background: item.materialColor }"></div>
            </div>
            <div class="slide-op">
              <el-button size="small" circle @click="adjust(item, -step)">−</el-button>
              <el-button size="small" circle @click="adjust(item, step)">＋</el-button>
              <el-select v-model="step" size="small" style="width:88px">
                <el-option :value="5" label="±5 kg" />
                <el-option :value="10" label="±10 kg" />
                <el-option :value="25" label="±25 kg" />
              </el-select>
            </div>
          </div>
        </div>
        <div class="empty-tip" v-else>暂无配料明细</div>

        <div class="dev-row" v-if="selected">
          <span class="tag-line">目标重量</span>
          <b>{{ Number(selected.targetWeight).toFixed(2) }} kg</b>
          <span class="tag-line">配料合计</span>
          <b>{{ Number(selected.totalWeight).toFixed(2) }} kg</b>
          <span class="tag-line">偏差</span>
          <b :class="{ over: selected.overDeviation }">{{ Number(selected.deviationRate).toFixed(2) }}%</b>
          <span class="tag-line">允许</span>
          <b>±{{ maxRate }}%</b>
        </div>
      </section>

      <section class="panel">
        <div class="panel-title">配料单</div>
        <div class="sheet-list" v-if="sheets.length">
          <div
            v-for="s in sheets"
            :key="s.id"
            class="sheet-item"
            :class="{ on: selectedId === s.id }"
            @click="selectSheet(s)"
          >
            <div class="sheet-no">{{ s.chargeNo }}</div>
            <div class="sheet-sub">{{ s.heatNo }} · {{ s.itemCount }} 项 · {{ Number(s.totalWeight).toFixed(0) }} kg</div>
            <div class="sheet-rate" :class="{ over: s.overDeviation }">{{ Number(s.deviationRate).toFixed(2) }}%</div>
          </div>
        </div>
        <div class="empty-tip" v-else>暂无配料单</div>

        <div class="op-row">
          <el-button size="small" type="primary" plain @click="createOpen = true">新建配料单</el-button>
          <el-button size="small" type="danger" plain :disabled="!selected" @click="removeSheet">删除配料单</el-button>
        </div>
      </section>
    </div>

    <section class="panel sim-panel">
      <div class="panel-title">
        配料偏差双方案推演
        <span class="view-tip">
          选中未出炉炉次，以当前配料明细 / 目标重量 / 炉体容量 / 合金材质 / 回炉料比例为同一基准，
          一次试算「最低补料成本」与「最小成分偏离」两套方案 · 只推演不写库，正式调整仍走上方微调流程
        </span>
      </div>

      <div class="sim-controls">
        <el-select v-model="simForm.heatId" placeholder="选择未出炉炉次" size="small" style="width: 340px">
          <el-option
            v-for="h in simHeats"
            :key="h.id"
            :value="h.id"
            :label="h.heatNo + ' · ' + h.alloyName + ' · ' + h.stageName + ' · 计划 ' + f2(h.planWeight) + ' kg · ' + (h.furnaceNo ? h.furnaceNo + ' 容量 ' + f2(h.capacityKg) + ' kg' : '未排炉')"
          />
        </el-select>
        <span class="sim-label">目标重量</span>
        <el-input-number v-model="simForm.targetWeight" :min="1" :step="10" size="small" style="width: 130px" />
        <span class="sim-label">kg</span>
        <el-checkbox v-model="simForm.allowNew" size="small">允许新料</el-checkbox>
        <el-button size="small" type="primary" plain :loading="simLoading" @click="runSimulate">重新推演</el-button>
      </div>

      <div class="sim-caps" v-if="simMaterials.length">
        <span class="sim-label">单项上限 kg：</span>
        <span v-for="m in simMaterials" :key="m.code" class="cap-cell">
          <span class="dot" :style="{ background: m.color }"></span>{{ m.name }}
          <el-input-number v-model="simForm.caps[m.code]" :min="1" :max="simMaxItemWeight" :step="50" size="small" style="width: 118px" />
        </span>
      </div>

      <div v-if="simError" class="sim-error">{{ simError }}</div>

      <template v-if="simResult">
        <div class="sim-baseline">
          <b>同一基准</b>
          <span>{{ simResult.baseline.heatNo }} · {{ simResult.baseline.alloyName }} · {{ simResult.baseline.stageName }}</span>
          <span>目标 {{ f2(simResult.baseline.targetWeight) }} kg（{{ simResult.baseline.targetSource }}，允许 ±{{ simResult.baseline.deviationLimitRate }}%）</span>
          <span>炉体容量 {{ simResult.baseline.capacityKg ? f2(simResult.baseline.capacityKg) + ' kg' : '未关联炉体' }}</span>
          <span>
            当前配料 {{ f2(simResult.baseline.currentTotalKg) }} kg（{{ simResult.baseline.chargeNo || '空基准' }}<template v-if="simResult.baseline.currentScrapRatio !== null">，回炉料 {{ f2(simResult.baseline.currentScrapRatio) }}%</template>）
          </span>
          <span>回炉料上限 {{ f2(simResult.baseline.scrapRatioMax) }}%</span>
          <span>{{ simResult.baseline.allowNewMaterial ? '允许新料' : '仅现有料项' }}</span>
        </div>

        <div v-if="!simResult.feasible" class="sim-conflicts">
          <div class="conflict-title">约束互相矛盾，无法形成合法方案（不给出误导性推荐）：</div>
          <div v-for="(c, i) in simResult.conflicts" :key="i" class="conflict-line">⚠ {{ c }}</div>
          <div class="sim-schemes">
            <div class="scheme-card infeasible" v-for="s in simSchemes" :key="s.code">
              <div class="scheme-head">
                <span class="scheme-name">{{ s.name }}</span>
                <span class="scheme-badge bad">不可行</span>
              </div>
              <div class="scheme-notes">
                <div v-for="(n, i) in s.infeasibleReasons" :key="i">· {{ n }}</div>
              </div>
            </div>
          </div>
        </div>

        <template v-else>
          <div class="sim-schemes">
            <div
              v-for="s in simSchemes"
              :key="s.code"
              class="scheme-card"
              :class="{ recommended: simResult.recommendation && simResult.recommendation.choice === s.code }"
            >
              <div class="scheme-head">
                <span class="scheme-name">{{ s.name }}</span>
                <span class="scheme-badge" v-if="simResult.recommendation && simResult.recommendation.choice === s.code">推荐</span>
              </div>
              <div class="scheme-metrics">
                <span>试算总重 <b>{{ f2(s.totalWeight) }} kg</b></span>
                <span>偏差率 <b>{{ f2(s.deviationRate) }}%</b></span>
                <span>回炉料 <b>{{ f2(s.scrapRatio) }}%</b><i>/上限 {{ f2(s.scrapLimit) }}%</i></span>
                <span>补料成本 <b>{{ f2(s.supplementCost) }} 元</b></span>
                <span>成分偏离 <b>{{ f2(s.compositionDeviation) }} 点</b></span>
              </div>
              <table class="scheme-table">
                <thead>
                  <tr><th>料项</th><th>当前 kg</th><th>建议 kg</th><th>增减 kg</th><th>补料费 元</th></tr>
                </thead>
                <tbody>
                  <tr v-for="a in s.adjustments" :key="a.materialCode">
                    <td>
                      <span class="dot" :style="{ background: a.color }"></span>{{ a.materialName }}
                      <em v-if="a.isNew" class="new-tag">新料</em>
                    </td>
                    <td>{{ f2(a.currentKg) }}</td>
                    <td>{{ f2(a.suggestKg) }}</td>
                    <td :class="a.deltaKg > 0 ? 'up' : 'down'">{{ a.deltaKg > 0 ? '+' : '' }}{{ f2(a.deltaKg) }}</td>
                    <td>{{ f2(a.costDelta) }}</td>
                  </tr>
                  <tr v-if="!s.adjustments.length"><td colspan="5" class="empty-cell">当前配料已满足约束，无需调整</td></tr>
                </tbody>
              </table>
              <div class="scheme-notes" v-if="s.notes.length">
                <div v-for="(n, i) in s.notes" :key="i">· {{ n }}</div>
              </div>
            </div>
          </div>

          <div class="sim-reco" :class="{ none: simResult.recommendation.choice === 'NONE' }">
            <template v-if="simResult.recommendation.choice === 'NONE'">
              ⚖ <b>两套方案等价：无偏好</b>。{{ simResult.recommendation.reason }}
            </template>
            <template v-else>
              ★ 推荐「<b>{{ simResult.recommendation.choiceName }}</b>」：{{ simResult.recommendation.reason }}
              <div class="sacrifice">未选方案的代价：{{ simResult.recommendation.sacrifice }}</div>
            </template>
          </div>
        </template>
      </template>
      <div v-else-if="!simLoading && !simError" class="empty-tip">选择未出炉炉次后自动推演</div>
    </section>

    <el-dialog v-model="createOpen" title="新建配料单" width="620px">
      <el-form label-width="88px">
        <el-form-item label="配料单号"><el-input v-model="form.chargeNo" placeholder="如 CH-2604-0101" /></el-form-item>
        <el-form-item label="目标炉次">
          <el-select v-model="form.heatId" placeholder="选择已出炉的炉次" style="width:100%">
            <el-option
              v-for="h in board.heats"
              :key="h.id"
              :label="h.heatNo + ' · ' + h.alloyName + ' · 出炉 ' + Number(h.tapWeight).toFixed(0) + ' kg'"
              :value="h.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="配料员"><el-input v-model="form.operator" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="form.remark" /></el-form-item>
      </el-form>

      <div class="rows-title">配料明细（同一配料单重复品种由后端自动合并）</div>
      <div class="new-row" v-for="(it, idx) in form.items" :key="idx">
        <el-select v-model="it.materialCode" size="small" style="width:180px">
          <el-option v-for="m in board.materials" :key="m.code" :label="m.name" :value="m.code" />
        </el-select>
        <el-input-number v-model="it.weightKg" size="small" :min="1" :step="10" />
        <el-button size="small" type="danger" plain @click="removeRow(idx)">移除</el-button>
      </div>
      <el-button size="small" plain @click="addRow">+ 再加一种炉料</el-button>

      <div class="dev-box" :class="{ over: formOver }">
        <span>合计 {{ formTotal.toFixed(2) }} kg</span>
        <span>目标 {{ formTarget.toFixed(2) }} kg</span>
        <span v-if="formDeviation !== null">偏差 {{ formDeviation.toFixed(2) }}%</span>
        <span>（允许 ±{{ maxRate }}%）</span>
      </div>

      <template #footer>
        <el-button @click="createOpen = false">取消</el-button>
        <el-button type="primary" :loading="loading" @click="createCharge">建立配料单</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.sum-flag {
  margin-left: auto;
  padding: 2px 10px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 700;
  color: #fff;
  background: var(--pass);
}
.sum-flag.over { background: var(--pour); }

.slide-row { padding: 8px 0; border-bottom: 1px dashed var(--line); }
.slide-head { display: flex; align-items: center; gap: 10px; font-size: 12.5px; }
.mat-name { flex: 1 1 auto; font-weight: 700; }
.mat-weight { color: var(--ink-soft); }
.mat-ratio { width: 62px; text-align: right; font-weight: 800; color: var(--primary-deep); }

.slide-track {
  margin: 6px 0;
  height: 14px;
  border-radius: 8px;
  background: #f1ebf6;
  overflow: hidden;
  box-shadow: inset 0 1px 2px rgba(80, 40, 100, 0.08);
}
.slide-bar {
  height: 100%;
  border-radius: 8px;
  transition: width .3s ease;
  box-shadow: 0 2px 8px rgba(142, 36, 170, 0.24);
}

.slide-op { display: flex; align-items: center; gap: 6px; }

.dev-row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-top: 12px; font-size: 12.5px; }
.dev-row b { color: var(--primary-deep); }
.dev-row b.over { color: var(--pour); }

.sheet-list { max-height: 340px; overflow-y: auto; }
.sheet-item {
  position: relative;
  padding: 9px 90px 9px 12px;
  border-radius: 9px;
  border: 1px solid var(--line);
  margin-bottom: 8px;
  cursor: pointer;
  transition: all .18s ease;
}
.sheet-item:hover { border-color: var(--primary-soft); background: #fbf7fd; }
.sheet-item.on { border-color: var(--primary); background: var(--primary-wash); }
.sheet-no { font-size: 13px; font-weight: 700; color: var(--primary-deep); }
.sheet-sub { font-size: 11px; color: var(--ink-dim); margin-top: 2px; }
.sheet-rate {
  position: absolute;
  right: 12px; top: 50%;
  transform: translateY(-50%);
  font-size: 13px; font-weight: 800; color: var(--pass);
}
.sheet-rate.over { color: var(--pour); }

.rows-title { font-size: 12.5px; font-weight: 700; margin: 6px 0 8px; }
.new-row { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }

.dev-box {
  margin-top: 10px;
  padding: 9px 12px;
  border-radius: 9px;
  background: #eef6ef;
  border: 1px solid #c9e6cf;
  color: #256b2c;
  font-size: 12.5px;
  display: flex;
  gap: 12px;
}
.dev-box.over { background: #fdecea; border-color: #f3c2bd; color: #a01a14; font-weight: 700; }

.op-row { margin-top: 12px; display: flex; gap: 8px; }

/* ---------------- 双方案推演 ---------------- */
.sim-panel { margin-top: 12px; }
.sim-controls { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.sim-label { font-size: 12.5px; color: var(--ink-soft); }
.sim-caps {
  display: flex; align-items: center; gap: 10px 14px; flex-wrap: wrap;
  margin-top: 10px; padding: 8px 10px;
  background: #faf7fd; border: 1px dashed var(--line); border-radius: 9px;
}
.cap-cell { display: inline-flex; align-items: center; gap: 5px; font-size: 12px; color: var(--ink-soft); }

.sim-error {
  margin-top: 10px; padding: 9px 12px; border-radius: 9px;
  background: #fdecea; border: 1px solid #f3c2bd; color: #a01a14; font-size: 12.5px;
}

.sim-baseline {
  display: flex; align-items: center; gap: 8px 14px; flex-wrap: wrap;
  margin-top: 12px; padding: 8px 12px; border-radius: 9px;
  background: var(--primary-wash); font-size: 12px; color: var(--primary-deep);
}
.sim-baseline b { font-weight: 800; }

.sim-conflicts {
  margin-top: 12px; padding: 10px 12px; border-radius: 9px;
  background: #fdf3e7; border: 1px solid #f0d9b5; color: #8a5a00; font-size: 12.5px;
}
.conflict-title { font-weight: 800; margin-bottom: 6px; }
.conflict-line { margin: 3px 0; }

.sim-schemes { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; margin-top: 12px; }
.scheme-card { border: 1px solid var(--line); border-radius: 10px; padding: 10px 12px; }
.scheme-card.recommended { border-color: var(--primary); box-shadow: 0 0 0 2px var(--primary-wash); }
.scheme-card.infeasible { border-color: #f0d9b5; background: #fffaf2; }
.scheme-head { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.scheme-name { font-weight: 800; color: var(--primary-deep); }
.scheme-badge { padding: 1px 8px; border-radius: 999px; font-size: 11px; font-weight: 700; color: #fff; background: var(--pass); }
.scheme-badge.bad { background: var(--warn); }

.scheme-metrics { display: flex; flex-wrap: wrap; gap: 4px 14px; font-size: 12px; color: var(--ink-soft); margin-bottom: 8px; }
.scheme-metrics b { color: var(--ink); }
.scheme-metrics i { font-style: normal; color: var(--ink-dim); margin-left: 3px; }

.scheme-table { width: 100%; border-collapse: collapse; font-size: 12px; }
.scheme-table th, .scheme-table td { padding: 4px 6px; text-align: right; border-bottom: 1px dashed var(--line); }
.scheme-table th:first-child, .scheme-table td:first-child { text-align: left; }
.scheme-table th { color: var(--ink-dim); font-weight: 600; }
.scheme-table .up { color: var(--pass); font-weight: 700; }
.scheme-table .down { color: var(--pour); font-weight: 700; }
.new-tag {
  font-style: normal; font-size: 10px; color: #fff;
  background: var(--primary-soft); border-radius: 4px; padding: 0 4px; margin-left: 4px;
}
.empty-cell { text-align: center !important; color: var(--ink-dim); }

.scheme-notes { margin-top: 8px; font-size: 11.5px; color: var(--ink-dim); }

.sim-reco {
  margin-top: 12px; padding: 10px 12px; border-radius: 9px;
  background: #eef6ef; border: 1px solid #c9e6cf; color: #256b2c; font-size: 12.5px;
}
.sim-reco.none { background: #f1ebf6; border-color: var(--line-strong); color: var(--ink-soft); }
.sim-reco .sacrifice { margin-top: 4px; font-size: 12px; opacity: .85; }
</style>
