<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import router from '../router'
import api from '../api'

const MAX_DISPLAY_TEMP = 1800

const board = ref({ furnaces: [], heats: [], stages: [] })
const loading = ref(false)
const selectedId = ref(null)
const tapForm = ref({ tapTemp: null, tapWeight: null })
const dialog = ref(false)
const form = ref({ heatNo: '', furnaceId: null, alloy: 'CAST_IRON', planWeight: 900, meltTemp: 1470, operator: '张宏伟' })

const routeQuery = computed(() => router.currentRoute.value?.query || {})
const heats = computed(() => board.value.heats || [])
const furnaces = computed(() => board.value.furnaces || [])
const stages = computed(() => board.value.stages || [])

const selected = computed(() => heats.value.find((h) => h.id === selectedId.value) || null)

const furnaceMap = computed(() => {
  const map = {}
  furnaces.value.forEach((f) => { map[f.id] = f })
  return map
})

function barHeight(heat) {
  const temp = heat.meltTemp || 0
  return Math.max(6, Math.round((temp / MAX_DISPLAY_TEMP) * 100))
}

function barColor(heat) {
  if (!heat.tempValid && heat.meltTemp) return '#c62828'
  return heat.stageColor
}

function furnaceName(heat) {
  return heat.furnaceNo || (furnaceMap.value[heat.furnaceId]?.furnaceNo || '-')
}

function select(heat) {
  selectedId.value = heat.id
  tapForm.value = { tapTemp: heat.tapTemp || null, tapWeight: heat.tapWeight || null }
}

/** 点阶段台阶：一次一级，跳阶段由后端抛中文提示 */
async function advance(code) {
  if (!selected.value) return
  loading.value = true
  try {
    await api.smelt.advance(selected.value.id, code)
    ElMessage.success('阶段已推进到「' + stages.value.find((s) => s.code === code)?.name + '」')
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

async function submitTap() {
  if (!selected.value) return
  loading.value = true
  try {
    await api.smelt.tap(selected.value.id, tapForm.value)
    ElMessage.success('出炉温度 / 重量已登记')
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

async function createHeat() {
  loading.value = true
  try {
    await api.smelt.createHeat(form.value)
    ElMessage.success('炉次已开')
    dialog.value = false
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

async function removeHeat() {
  if (!selected.value) return
  loading.value = true
  try {
    await api.smelt.removeHeat(selected.value.id)
    ElMessage.success('炉次已删除')
    selectedId.value = null
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
    board.value = await api.smelt.board()
    if (selectedId.value === null && heats.value.length) {
      select(heats.value[0])
    }
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
      <span class="view-title">熔炼工段 · 炉次温度带</span>
      <span class="view-tip">竖条高度＝熔炼温度；点竖条选中，点右侧台阶推进阶段（一次一级），在精炼/出炉阶段登记出炉温度与重量</span>
    </div>

    <div class="grid-2">
      <!-- 竖条温度带 -->
      <section class="panel">
        <div class="panel-title">
          炉次温度带
          <span class="view-tip">共 {{ heats.length }} 个炉次 · 基准 {{ MAX_DISPLAY_TEMP }}℃</span>
        </div>

        <div class="temp-band" v-if="heats.length">
          <div
            v-for="heat in heats"
            :key="heat.id"
            class="band-col"
            :class="{ on: selectedId === heat.id }"
            @click="select(heat)"
          >
            <div class="band-scale">
              <div
                class="band-fill"
                :style="{ height: barHeight(heat) + '%', background: 'linear-gradient(0deg,' + barColor(heat) + ',#f7d9a8)' }"
              >
                <span class="band-temp">{{ heat.meltTemp || '—' }}℃</span>
              </div>
            </div>
            <div class="band-no">{{ heat.heatNo }}</div>
            <div class="band-sub">{{ furnaceName(heat) }} · {{ heat.stageName }}</div>
          </div>
        </div>
        <div class="empty-tip" v-else>暂无炉次</div>

        <div class="chip-row" style="margin-top:12px">
          <span
            v-for="stage in stages"
            :key="stage.code"
            class="chip"
            :style="{ borderColor: stage.color, color: stage.color }"
          >{{ stage.name }} · {{ stage.count }} 炉</span>
        </div>
      </section>

      <!-- 选中炉次的阶段推进 + 出炉登记 -->
      <section class="panel">
        <div class="panel-title">
          阶段推进与出炉登记
          <span class="view-tip" v-if="selected">{{ selected.heatNo }}</span>
        </div>

        <template v-if="selected">
          <div class="kv-row">
            <span class="k">熔炼炉</span><span class="v">{{ furnaceName(selected) }}</span>
            <span class="k">材质</span>
            <span class="v">
              <span class="dot" :style="{ background: selected.alloyColor }"></span>
              {{ selected.alloyName }}
            </span>
          </div>
          <div class="kv-row">
            <span class="k">熔炼温度</span>
            <span class="v" :class="{ danger: selected.meltTemp && !selected.tempValid }">
              {{ selected.meltTemp }}℃
            </span>
            <span class="k">出炉重量</span>
            <span class="v">{{ selected.tapWeight ?? '未登记' }}</span>
          </div>

          <div class="stage-ladder">
            <div
              v-for="(stage, idx) in stages"
              :key="stage.code"
              class="ladder-step"
              :class="{ done: idx <= stages.findIndex(s => s.code === selected.stage), cur: stage.code === selected.stage }"
              :style="{ '--c': stage.color }"
              @click="advance(stage.code)"
            >
              <span class="step-idx">{{ idx + 1 }}</span>
              <span class="step-name">{{ stage.name }}</span>
              <span class="step-temp">{{ stage.temp }}℃</span>
            </div>
          </div>

          <el-divider />
          <div class="tap-form">
            <el-input-number v-model="tapForm.tapTemp" :min="0" :max="1800" :step="5" controls-position="right" />
            <span class="unit">出炉温度 ℃</span>
            <el-input-number v-model="tapForm.tapWeight" :min="0" :max="5000" :step="10" controls-position="right" />
            <span class="unit">出炉重量 kg</span>
            <el-button size="small" type="primary" :loading="loading" @click="submitTap">登记出炉</el-button>
          </div>

          <div class="op-row">
            <el-button size="small" type="primary" plain @click="dialog = true">开新炉次</el-button>
            <el-button size="small" type="danger" plain @click="removeHeat">删除本炉次</el-button>
          </div>
        </template>
        <div class="empty-tip" v-else>先在左侧点一条炉次</div>
      </section>
    </div>

    <section class="panel">
      <div class="panel-title">熔炼炉台账</div>
      <el-table :data="furnaces" size="small" border stripe>
        <el-table-column prop="furnaceNo" label="炉号" width="90" />
        <el-table-column prop="furnaceName" label="炉名" min-width="160" />
        <el-table-column prop="furnaceTypeName" label="炉型" width="120" />
        <el-table-column prop="capacityKg" label="额定容量 kg" width="110" />
        <el-table-column prop="maxTemp" label="最高温度 ℃" width="110" />
        <el-table-column prop="powerKw" label="功率 kW" width="90" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'IDLE' ? 'success' : row.status === 'BUSY' ? 'warning' : 'danger'">
              {{ row.statusName }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="note" label="备注" min-width="180" />
      </el-table>
    </section>

    <el-dialog v-model="dialog" title="开新炉次" width="460px">
      <el-form label-width="96px">
        <el-form-item label="炉次号"><el-input v-model="form.heatNo" placeholder="如 H-2604-0101" /></el-form-item>
        <el-form-item label="熔炼炉">
          <el-select v-model="form.furnaceId" placeholder="选择熔炼炉">
            <el-option v-for="f in furnaces" :key="f.id" :label="f.furnaceNo + ' · ' + f.furnaceName" :value="f.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="材质">
          <el-select v-model="form.alloy">
            <el-option label="灰铸铁 HT250" value="CAST_IRON" />
            <el-option label="球墨铸铁 QT450" value="DUCTILE_IRON" />
            <el-option label="铸造碳钢 ZG270" value="CARBON_STEEL" />
            <el-option label="不锈钢 CF8C" value="STAINLESS" />
            <el-option label="铸造铝合金 ZL104" value="ALUMINUM" />
          </el-select>
        </el-form-item>
        <el-form-item label="计划重量"><el-input-number v-model="form.planWeight" :min="1" /></el-form-item>
        <el-form-item label="熔炼温度"><el-input-number v-model="form.meltTemp" :min="100" :max="1800" /></el-form-item>
        <el-form-item label="操作者"><el-input v-model="form.operator" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="loading" @click="createHeat">开炉</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.temp-band {
  display: flex;
  align-items: flex-end;
  gap: 8px;
  height: 260px;
  padding: 10px 6px 4px;
  border-radius: 10px;
  background: repeating-linear-gradient(0deg, #f3eaf8 0 1px, transparent 1px 26px), #fbf8fd;
  border: 1px solid var(--line);
  overflow-x: auto;
}

.band-col {
  flex: 0 0 62px;
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  cursor: pointer;
  padding: 4px 2px;
  border-radius: 8px;
  transition: background .18s ease;
}
.band-col:hover { background: #f6eefb; }
.band-col.on { background: var(--primary-wash); box-shadow: inset 0 0 0 1px var(--primary-soft); }

.band-scale {
  position: relative;
  flex: 1 1 auto;
  width: 22px;
  border-radius: 6px;
  background: #efe7f5;
  display: flex;
  align-items: flex-end;
  overflow: hidden;
}
.band-fill {
  width: 100%;
  border-radius: 6px 6px 2px 2px;
  position: relative;
  transition: height .35s ease;
}
.band-temp {
  position: absolute;
  top: 2px;
  left: 50%;
  transform: translateX(-50%);
  font-size: 10px;
  color: #4a2b5a;
  font-weight: 700;
  white-space: nowrap;
}
.band-no { font-size: 11.5px; font-weight: 700; margin-top: 6px; }
.band-sub { font-size: 10px; color: var(--ink-dim); text-align: center; }

.kv-row { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; font-size: 12.5px; margin-bottom: 6px; }
.kv-row .k { color: var(--ink-dim); }
.kv-row .v { color: var(--ink); font-weight: 700; margin-right: 10px; }
.kv-row .v.danger { color: var(--pour); }

.stage-ladder { margin: 10px 0 4px; }
.ladder-step {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 7px 10px;
  margin-bottom: 6px;
  border-radius: 9px;
  border: 1px dashed var(--line-strong);
  cursor: pointer;
  transition: all .18s ease;
}
.ladder-step:hover { background: var(--primary-wash); border-style: solid; }
.ladder-step.done { border-color: var(--c); border-style: solid; background: color-mix(in srgb, var(--c) 8%, #fff); }
.ladder-step.cur { box-shadow: 0 0 0 2px color-mix(in srgb, var(--c) 30%, transparent); font-weight: 700; }
.step-idx {
  width: 18px; height: 18px; line-height: 18px; text-align: center;
  border-radius: 5px; font-size: 10.5px; color: #fff; background: var(--c);
}
.step-name { flex: 1 1 auto; }
.step-temp { font-size: 11px; color: var(--ink-dim); }

.tap-form { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.tap-form .el-input-number { width: 120px; }
.tap-form .unit { font-size: 11.5px; color: var(--ink-dim); margin-right: 8px; }

.op-row { margin-top: 12px; display: flex; gap: 8px; }
</style>
