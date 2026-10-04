<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import router from '../router'
import api from '../api'

const board = ref({ inspections: [], defectStats: [], verdictStats: [], waitingCastings: [], defectTypes: [], severities: [] })
const loading = ref(false)
const picked = ref([])                 // 已选中的缺陷类型 code
const defectMap = ref({})              // 缺陷类型 → { severity, position, sizeMm, quantity }
const hardness = ref(null)
const inspector = ref('钱广安')
const remark = ref('')
const targetId = ref(null)

const VERDICTS = [
  { code: 'PASS', name: '合格', desc: '允许入库' },
  { code: 'REWORK', name: '返修', desc: '回清理重加工' },
  { code: 'SCRAP', name: '报废', desc: '不可再入库' }
]

const routeQuery = computed(() => router.currentRoute.value?.query || {})
const statMode = computed(() => routeQuery.value.view === 'stat')

const waiting = computed(() => board.value.waitingCastings || [])
const inspections = computed(() => board.value.inspections || [])
const defectTypes = computed(() => board.value.defectTypes || [])

const target = computed(() => waiting.value.find((c) => c.id === targetId.value) || null)

/** 判返修 / 报废必须至少一条缺陷 */
const needDefect = computed(() => problems.value.length > 0 && picked.value.length === 0)
const problems = computed(() =>
  picked.value.map((code) => ({
    defectType: code,
    severity: (defectMap.value[code] || {}).severity || 'MINOR',
    position: (defectMap.value[code] || {}).position || '',
    sizeMm: (defectMap.value[code] || {}).sizeMm || null,
    quantity: (defectMap.value[code] || {}).quantity || 1,
    remark: (defectMap.value[code] || {}).remark || ''
  }))
)

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

function nameOf(code) {
  const t = defectTypes.value.find((x) => x.code === code)
  return t ? t.name : code
}
function colorOf(code) {
  const t = defectTypes.value.find((x) => x.code === code)
  return t ? t.color : '#8e24aa'
}

async function submitVerdict(code) {
  if (!target.value) return ElMessage.warning('先选一张待检验的铸件卡')
  if (code !== 'PASS' && picked.value.length === 0) {
    return ElMessage.warning('判「' + VERDICTS.find((v) => v.code === code).name + '」必须至少选一类缺陷')
  }
  loading.value = true
  try {
    await api.inspection.create({
      inspectNo: 'I-' + Date.now().toString().slice(-8),
      castingId: target.value.id,
      inspector: inspector.value,
      hardnessHb: hardness.value,
      verdict: code,
      remark: remark.value,
      defects: problems.value
    })
    ElMessage.success('已判「' + VERDICTS.find((v) => v.code === code).name + '」')
    picked.value = []
    defectMap.value = {}
    targetId.value = null
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

async function removeInspection(row) {
  loading.value = true
  try {
    await api.inspection.remove(row.id)
    ElMessage.success('检验单已删除')
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

async function load() {
  loading.value = true
  try {
    board.value = await api.inspection.board()
    if (targetId.value === null && waiting.value.length) targetId.value = waiting.value[0].id
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div>
    <div class="view-head">
      <span class="view-title">检验判定 · 缺陷徽章与结论三选一</span>
      <span class="view-tip">六类缺陷徽章可多选；结论三选一大按钮 —— 判返修或报废必须先至少选一类缺陷</span>
    </div>

    <div v-if="!statMode" class="judge-wrap">
      <section class="panel">
        <div class="panel-title">缺陷类型徽章（多选）</div>

        <div class="badge-grid">
          <div
            v-for="t in defectTypes"
            :key="t.code"
            class="badge"
            :class="{ on: picked.includes(t.code) }"
            :style="{ '--c': t.color }"
            @click="toggleType(t.code)"
          >
            <span class="badge-name">{{ t.name }}</span>
            <span class="badge-tick" v-if="picked.includes(t.code)">✓</span>
          </div>
        </div>

        <div v-if="picked.length" class="defect-rows">
          <div v-for="code in picked" :key="code" class="defect-row">
            <span class="dot" :style="{ background: colorOf(code) }"></span>
            <b class="row-name">{{ nameOf(code) }}</b>
            <el-select v-model="defectMap[code].severity" size="small" style="width:92px">
              <el-option v-for="s in board.severities" :key="s.code" :label="s.name" :value="s.code" />
            </el-select>
            <el-input v-model="defectMap[code].position" size="small" placeholder="部位" style="width:110px" />
            <el-input-number v-model="defectMap[code].sizeMm" size="small" :min="0" :step="0.1" placeholder="尺寸mm" style="width:110px" />
            <el-input-number v-model="defectMap[code].quantity" size="small" :min="1" style="width:92px" />
          </div>
        </div>
        <div class="empty-tip" v-else>尚未选任何缺陷类型</div>
      </section>

      <section class="panel">
        <div class="panel-title">结论三选一</div>

        <div class="target-box">
          <div class="target-k">待检铸件</div>
          <el-select v-model="targetId" size="small" style="width:100%">
            <el-option
              v-for="c in waiting" :key="c.id"
              :label="c.castingNo + ' · ' + c.castingName + ' · ' + c.materialName"
              :value="c.id"
            />
          </el-select>
          <div class="target-derived" v-if="target">
            炉次号 <b>{{ target.heatNo || '—' }}</b> · 模具 <b>{{ target.moldNo || '—' }}</b> · 理论 {{ target.theoryWeight }} kg
          </div>
        </div>

        <el-form label-width="72px" size="small">
          <el-form-item label="检验员"><el-input v-model="inspector" /></el-form-item>
          <el-form-item label="硬度 HB"><el-input-number v-model="hardness" :min="0" :step="1" controls-position="right" /></el-form-item>
          <el-form-item label="备注"><el-input v-model="remark" type="textarea" :rows="2" /></el-form-item>
        </el-form>

        <div class="verdict-row">
          <div
            v-for="v in VERDICTS"
            :key="v.code"
            class="verdict-btn"
            :class="[v.code.toLowerCase(), { off: v.code !== 'PASS' && needDefect }]"
            @click="submitVerdict(v.code)"
          >
            <div class="vb-name">{{ v.name }}</div>
            <div class="vb-desc">{{ v.desc }}</div>
          </div>
        </div>
        <div class="warn-tip" v-if="needDefect">判返修 / 报废必须先选至少一类缺陷；判合格可以没有缺陷记录。</div>
      </section>
    </div>

    <!-- 缺陷分布 -->
    <div class="grid-2" v-if="statMode">
      <section class="panel">
        <div class="panel-title">缺陷类型分布（JPQL ⑤）</div>
        <div class="stat-rows">
          <div v-for="s in board.defectStats" :key="s.defectType" class="stat-row">
            <span class="dot" :style="{ background: s.color }"></span>
            <span class="sr-name">{{ s.defectTypeName }}</span>
            <div class="sr-track">
              <div
                class="sr-bar"
                :style="{ width: Math.round((s.count / Math.max(1, board.defectStats[0].count)) * 100) + '%', background: s.color }"
              ></div>
            </div>
            <span class="sr-val">{{ s.count }} 次 / 平均 {{ s.avgSizeMm }} mm</span>
          </div>
        </div>
      </section>

      <section class="panel">
        <div class="panel-title">结论分布（JPQL ⑥）</div>
        <div class="stat-rows">
          <div v-for="s in board.verdictStats" :key="s.verdict" class="stat-row">
            <span class="dot" :style="{ background: s.color }"></span>
            <span class="sr-name">{{ s.verdictName }}</span>
            <div class="sr-track">
              <div
                class="sr-bar"
                :style="{ width: Math.round((s.count / Math.max(1, board.verdictStats[0].count)) * 100) + '%', background: s.color }"
              ></div>
            </div>
            <span class="sr-val">{{ s.count }} 张</span>
          </div>
        </div>
      </section>
    </div>

    <section class="panel">
      <div class="panel-title">检验记录</div>
      <el-table :data="inspections" size="small" border stripe>
        <el-table-column prop="inspectNo" label="检验单号" width="150" />
        <el-table-column prop="castingNo" label="铸件编号" width="130" />
        <el-table-column prop="hardnessHb" label="硬度 HB" width="90" />
        <el-table-column prop="defectCount" label="缺陷条数" width="90" />
        <el-table-column label="结论" width="100">
          <template #default="{ row }">
            <el-tag size="small" :style="{ background: row.verdictColor, color: '#fff', border: 'none' }">
              {{ row.verdictName }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="inspector" label="检验员" width="100" />
        <el-table-column prop="remark" label="备注" min-width="220" />
        <el-table-column label="操作" width="150">
          <template #default="{ row }">
            <el-button
              v-if="row.verdict === 'REWORK'"
              size="small" type="warning" plain
              @click="router.push('/rework')"
            >返修队列 →</el-button>
            <el-button size="small" type="danger" plain @click="removeInspection(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>
  </div>
</template>

<style scoped>
.judge-wrap { display: grid; grid-template-columns: minmax(0, 1.1fr) minmax(0, 1fr); gap: 12px; align-items: start; }

.badge-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px; margin-bottom: 12px; }

.badge {
  position: relative;
  padding: 14px 10px;
  border-radius: 12px;
  border: 2px solid var(--line-strong);
  background: #fff;
  text-align: center;
  cursor: pointer;
  font-size: 14px;
  font-weight: 700;
  color: var(--ink-soft);
  transition: all .18s ease;
}
.badge:hover { border-color: var(--c); color: var(--c); }
.badge.on {
  border-color: var(--c);
  color: #fff;
  background: var(--c);
  box-shadow: 0 8px 20px color-mix(in srgb, var(--c) 28%, transparent);
}
.badge-tick { position: absolute; right: 6px; top: 4px; font-size: 12px; }

.defect-rows { border-top: 1px dashed var(--line-strong); padding-top: 10px; }
.defect-row { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; flex-wrap: wrap; }
.row-name { min-width: 64px; color: var(--primary-deep); }

.target-box { margin-bottom: 10px; }
.target-k { font-size: 11.5px; color: var(--ink-dim); margin-bottom: 4px; }
.target-derived {
  margin-top: 6px;
  padding: 6px 9px;
  border-radius: 8px;
  background: #f7f1fa;
  border: 1px dashed var(--primary-soft);
  font-size: 11.5px;
  color: var(--ink-soft);
}
.target-derived b { color: var(--primary-deep); }

.verdict-row { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px; margin-top: 6px; }
.verdict-btn {
  padding: 16px 8px;
  border-radius: 14px;
  text-align: center;
  cursor: pointer;
  color: #fff;
  box-shadow: 0 8px 20px rgba(78, 40, 96, 0.18);
  transition: transform .18s ease, opacity .18s ease;
}
.verdict-btn:hover { transform: translateY(-2px); }
.verdict-btn.off { opacity: 0.42; cursor: not-allowed; }
.verdict-btn.pass { background: linear-gradient(160deg, #43a047, #2e7d32); }
.verdict-btn.rework { background: linear-gradient(160deg, #fbc02d, #f9a825); }
.verdict-btn.scrap { background: linear-gradient(160deg, #e53935, #c62828); }
.vb-name { font-size: 21px; font-weight: 800; letter-spacing: 2px; }
.vb-desc { font-size: 11px; opacity: 0.9; margin-top: 2px; }

.warn-tip { margin-top: 10px; font-size: 11.5px; color: var(--pour); }

.stat-rows { padding-top: 4px; }
.stat-row { display: flex; align-items: center; gap: 8px; margin-bottom: 10px; }
.sr-name { width: 66px; font-size: 12.5px; font-weight: 700; }
.sr-track { flex: 1 1 auto; height: 14px; border-radius: 8px; background: #f1ebf6; overflow: hidden; }
.sr-bar { height: 100%; border-radius: 8px; transition: width .35s ease; }
.sr-val { width: 150px; text-align: right; font-size: 11.5px; color: var(--ink-dim); }
</style>
