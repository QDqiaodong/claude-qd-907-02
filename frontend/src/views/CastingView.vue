<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import router from '../router'
import api from '../api'

const FLOW = ['WAIT_POUR', 'POURED', 'CLEANING', 'INSPECTING', 'STORED']

const castings = ref([])
const statusStats = ref([])
const materialStats = ref([])
const selected = ref(null)
const loading = ref(false)
const dialog = ref(false)

const heats = ref([])
// 下拉里给【全部】模具（含已报废/超寿命），失效项禁用并注明原因；
// 这只是页面提示，防止误选；真正的裁决在服务端（过期页面 / 重复请求仍会被后端原子拦截）
const molds = ref([])
const form = ref({
  castingNo: '', castingName: '', material: 'STAINLESS', heatId: null,
  moldId: null, cavityNo: '1', theoryWeight: 12.4, customer: '', operator: '陈志远'
})

const routeQuery = computed(() => router.currentRoute.value?.query || {})
const flowMode = computed(() => routeQuery.value.view === 'flow')

/**
 * 页面侧的模具可选判断（仅用于禁用/提示，不能作为安全边界）：
 * 寿命耗尽/已报废、与当前所选材质不匹配都视为明显失效。
 */
function moldBlockReason(mold) {
  if (!mold.schedulable) {
    return mold.status === 'SCRAPPED' || mold.overLife
      ? `寿命 ${mold.usedCount}/${mold.lifeCount} 已用完，${mold.statusName}`
      : mold.statusName
  }
  if (mold.fitMaterial && form.value.material && mold.fitMaterial !== form.value.material) {
    return `仅适用 ${mold.fitMaterialName}`
  }
  return ''
}

const chosenMold = computed(() => molds.value.find((m) => m.id === form.value.moldId) || null)
const chosenMoldBlock = computed(() => (chosenMold.value ? moldBlockReason(chosenMold.value) : ''))

function moldLabel(mold) {
  const base = `${mold.moldNo} · ${mold.moldName || ''}（剩 ${mold.remainCount}/${mold.lifeCount} 次 · ${mold.statusName}）`
  const block = moldBlockReason(mold)
  return block ? `${base} —— ${block}` : base
}

// 切换材质后，已选模具若不再匹配适用材质，给出提示并清掉，避免带着明显失效选择去提交
function onMaterialChange() {
  if (chosenMold.value && moldBlockReason(chosenMold.value)) {
    ElMessage.warning(`${chosenMold.value.moldNo} 不适用当前材质，已清空模具选择，请改选其他模具`)
    form.value.moldId = null
  }
}

const statusNameOf = computed(() => {
  const map = {}
  castings.value.forEach((c) => { map[c.status] = c.statusName })
  statusStats.value.forEach((s) => { map[s.status] = s.statusName })
  return map
})

function nextStatus(status) {
  const idx = FLOW.indexOf(status)
  return idx >= 0 && idx < FLOW.length - 1 ? FLOW[idx + 1] : null
}

// 返修支线状态只能由返修闭环推进，状态按钮在这里全部收起来（后端也会拦）
function inReworkLoop(status) {
  return status === 'REWORK' || status === 'REINSPECT'
}

async function openCard(item) {
  loading.value = true
  try {
    // 详情走 Casting 上的 @SQLSelect：一条 join SQL 把炉次号 / 模具号一起查出来
    selected.value = await api.casting.get(item.id)
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

async function move(status) {
  if (!selected.value) return
  loading.value = true
  try {
    selected.value = await api.casting.transition(selected.value.id, status)
    ElMessage.success('铸件状态已流转到「' + selected.value.statusName + '」')
    await loadList()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

async function createCasting() {
  // 页面预检（与服务端同口径的明显失效选择），给出即时提示；服务端仍会独立全量校验
  if (chosenMoldBlock.value) {
    ElMessage.error(`所选模具不可用：${chosenMoldBlock.value}，请改选其他模具或刷新页面查看最新寿命`)
    return
  }
  if (chosenMold.value) {
    const cavityNo = Number(form.value.cavityNo)
    if (!Number.isInteger(cavityNo) || cavityNo < 1 || cavityNo > chosenMold.value.cavityCount) {
      ElMessage.error(`型腔号必须是 1～${chosenMold.value.cavityCount} 之间的整数（该模具共 ${chosenMold.value.cavityCount} 个型腔）`)
      return
    }
  }
  loading.value = true
  try {
    await api.casting.create(form.value)
    ElMessage.success('铸件卡已建立')
    dialog.value = false
    // 成功后重新拉取：末次寿命占用成功的模具会立即从可选项中消失（状态转报废）
    await Promise.all([loadList(), refreshMolds()])
  } catch (e) {
    ElMessage.error(e.message)
    // 失败（含末次寿命竞争失败/重复提交）后也要刷新，避免停留在过期页面再次提交同一失效选择
    await Promise.all([loadList(), refreshMolds()]).catch(() => {})
  } finally {
    loading.value = false
  }
}

async function removeCasting() {
  if (!selected.value) return
  loading.value = true
  try {
    await api.casting.remove(selected.value.id)
    ElMessage.success('铸件卡已删除')
    selected.value = null
    await loadList()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

async function loadList() {
  const board = await api.casting.board()
  castings.value = board.castings
  statusStats.value = board.statusStats
  materialStats.value = board.materialStats
}

// 全量模具（含失效项），用于建卡表单：失效项禁用并标注原因，可排产且材质匹配的才允许选中
async function refreshMolds() {
  molds.value = await api.mold.list()
}

async function load() {
  loading.value = true
  try {
    await loadList()
    const [allHeats] = await Promise.all([api.smelt.heats(), refreshMolds()])
    heats.value = allHeats.filter((h) => Number(h.tapWeight || 0) > 0)
    selected.value = castings.value[0] ? await api.casting.get(castings.value[0].id) : null
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
      <span class="view-title">浇注清理 · 铸件编号大卡片</span>
      <span class="view-tip">卡片灰底区是只读派生字段（炉次号 / 炉次材质 / 模具编号），来自实体 @SQLSelect 那条 join SQL，不在 casting 表里</span>
    </div>

    <div class="chip-row">
      <span v-for="s in statusStats" :key="s.status" class="chip">
        <span class="dot" :style="{ background: s.color }"></span>
        {{ s.statusName }} · {{ s.count }} 件
      </span>
    </div>

    <div v-if="!flowMode" class="card-wrap">
      <section class="panel">
        <div class="panel-title">
          铸件大卡片
          <span class="view-tip">点卡片查看 @SQLSelect 派生字段</span>
        </div>

        <div class="cast-grid" v-if="castings.length">
          <div
            v-for="c in castings"
            :key="c.id"
            class="cast-card"
            :class="{ on: selected && selected.id === c.id }"
            :style="{ borderLeftColor: c.statusColor }"
            @click="openCard(c)"
          >
            <div class="cast-head">
              <span class="cast-no">{{ c.castingNo }}</span>
              <span class="cast-state" :style="{ background: c.statusColor }">{{ c.statusName }}</span>
            </div>
            <div class="cast-name">{{ c.castingName }}</div>
            <div class="cast-derived">
              <div class="d-line">
                <span class="d-k">炉次号</span>
                <span class="d-v">{{ c.heatNo || '—' }}</span>
                <span class="d-k">炉次材质</span>
                <span class="d-v">{{ c.heatAlloyName || '—' }}</span>
              </div>
              <div class="d-line">
                <span class="d-k">模具编号</span>
                <span class="d-v">{{ c.moldNo || '—' }}</span>
              </div>
              <div class="d-line">
                <span class="d-k">模具名称</span>
                <span class="d-v">{{ c.moldName || '—' }}</span>
              </div>
            </div>
            <div class="cast-foot">
              <span class="tag-line">
                <span class="dot" :style="{ background: c.materialColor }"></span>{{ c.materialName }}
              </span>
              <span class="tag-line">理论 {{ c.theoryWeight }} kg</span>
              <span class="tag-line" v-if="c.actualWeight">实测 {{ c.actualWeight }} kg</span>
              <span class="tag-line">型腔 {{ c.cavityNo }}</span>
            </div>
          </div>
        </div>
        <div class="empty-tip" v-else>暂无铸件卡</div>
      </section>

      <section class="panel cast-detail" v-if="selected">
        <div class="panel-title">
          {{ selected.castingNo }} 详情
          <span class="view-tip">GET /api/casting/{{ selected.id }} → @SQLSelect</span>
        </div>

        <div class="detail-no">{{ selected.castingNo }}</div>
        <div class="detail-name">{{ selected.castingName }} · {{ selected.materialName }}</div>

        <div class="flow-strip">
          <span
            v-for="(code, idx) in FLOW"
            :key="code"
            class="flow-node"
            :class="{ done: idx <= FLOW.indexOf(selected.status), cur: code === selected.status }"
          >{{ statusNameOf[code] || code }}</span>
        </div>

        <el-descriptions :column="2" size="small" border>
          <el-descriptions-item label="状态">{{ selected.statusName }}</el-descriptions-item>
          <el-descriptions-item label="型腔号">{{ selected.cavityNo }}</el-descriptions-item>
          <el-descriptions-item label="理论重量">{{ selected.theoryWeight }} kg</el-descriptions-item>
          <el-descriptions-item label="实测重量">{{ selected.actualWeight ?? '未记录' }}</el-descriptions-item>
          <el-descriptions-item label="客户">{{ selected.customer }}</el-descriptions-item>
          <el-descriptions-item label="操作者">{{ selected.operator }}</el-descriptions-item>
        </el-descriptions>

        <div class="derived-box">
          <div class="derived-title">只读派生字段（insertable=false / updatable=false）</div>
          <div class="d-line"><span class="d-k">heat_no</span><span class="d-v">{{ selected.heatNo || '—' }}</span></div>
          <div class="d-line"><span class="d-k">heat_alloy</span><span class="d-v">{{ selected.heatAlloyName || '—' }}</span></div>
          <div class="d-line"><span class="d-k">mold_no</span><span class="d-v">{{ selected.moldNo || '—' }}</span></div>
          <div class="d-line"><span class="d-k">mold_name</span><span class="d-v">{{ selected.moldName || '—' }}</span></div>
        </div>

        <div class="op-row">
          <template v-if="!inReworkLoop(selected.status)">
            <el-button
              v-if="nextStatus(selected.status)"
              size="small"
              type="primary"
              :loading="loading"
              @click="move(nextStatus(selected.status))"
            >流转 → {{ statusNameOf[nextStatus(selected.status)] }}</el-button>
            <el-button
              size="small"
              type="danger"
              plain
              :loading="loading"
              @click="move('SCRAPPED')"
            >判废</el-button>
          </template>
          <el-tag v-else type="warning" size="small" effect="plain">
            该铸件在返修闭环中（{{ selected.statusName }}），请到「返修处置与复检」页登记处置/复检
          </el-tag>
          <el-button size="small" type="danger" plain @click="removeCasting">删除卡片</el-button>
        </div>
      </section>
    </div>

    <section class="panel" v-else>
      <div class="panel-title">铸件状态流转表</div>
      <el-table :data="castings" size="small" border stripe>
        <el-table-column prop="castingNo" label="铸件编号" width="130" />
        <el-table-column prop="castingName" label="名称" min-width="170" />
        <el-table-column prop="materialName" label="材质" width="140" />
        <el-table-column prop="heatNo" label="炉次号(派生)" width="130" />
        <el-table-column prop="moldNo" label="模具编号(派生)" width="140" />
        <el-table-column prop="theoryWeight" label="理论重量 kg" width="110" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" :style="{ background: row.statusColor, color: '#fff', border: 'none' }">
              {{ row.statusName }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="customer" label="客户" min-width="140" />
      </el-table>
    </section>

    <div class="grid-2" style="margin-top:12px">
      <section class="panel">
        <div class="panel-title">材质分布（JPQL ③）</div>
        <el-table :data="materialStats" size="small" border stripe>
          <el-table-column prop="materialName" label="材质" min-width="150" />
          <el-table-column prop="count" label="件数" width="80" />
          <el-table-column prop="weightSum" label="理论重量合计 kg" width="140" />
          <el-table-column prop="avgWeight" label="平均单件 kg" width="120" />
        </el-table>
      </section>

      <section class="panel">
        <div class="panel-title">建铸件卡</div>
        <el-button size="small" type="primary" plain @click="dialog = true">打开建档表单</el-button>
        <div class="op-note">
          铸件卡必须挂在已出炉的炉次上，材质要与炉次一致；模具按适用材质与剩余寿命选择，型腔号须在型腔数范围内。
          模具最后一次寿命由服务端原子占用：两人同时建卡只会成功一张，失败方按提示刷新即可看到真实结果，不要重复提交。
        </div>
      </section>
    </div>

    <el-dialog v-model="dialog" title="建铸件卡" width="520px">
      <el-form label-width="96px">
        <el-form-item label="铸件编号"><el-input v-model="form.castingNo" placeholder="如 C-2606-0601" /></el-form-item>
        <el-form-item label="名称"><el-input v-model="form.castingName" placeholder="如 离心泵叶轮 φ180" /></el-form-item>
        <el-form-item label="所属炉次">
          <el-select v-model="form.heatId" style="width:100%">
            <el-option
              v-for="h in heats" :key="h.id"
              :label="h.heatNo + ' · ' + h.alloyName + ' · 出炉 ' + Number(h.tapWeight).toFixed(0) + ' kg'"
              :value="h.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="材质">
          <el-select v-model="form.material" style="width:100%" @change="onMaterialChange">
            <el-option label="灰铸铁 HT250" value="CAST_IRON" />
            <el-option label="球墨铸铁 QT450" value="DUCTILE_IRON" />
            <el-option label="铸造碳钢 ZG270" value="CARBON_STEEL" />
            <el-option label="不锈钢 CF8C" value="STAINLESS" />
            <el-option label="铸造铝合金 ZL104" value="ALUMINUM" />
          </el-select>
        </el-form-item>
        <el-form-item label="模具">
          <el-select v-model="form.moldId" clearable style="width:100%" placeholder="不使用模具可留空">
            <el-option
              v-for="m in molds" :key="m.id"
              :label="moldLabel(m)" :value="m.id"
              :disabled="Boolean(moldBlockReason(m))"
            />
          </el-select>
          <div v-if="chosenMold" class="mold-meta" :class="{ blocked: Boolean(chosenMoldBlock) }">
            <template v-if="chosenMoldBlock">
              <el-tag type="danger" size="small" effect="plain">不可用</el-tag>
              {{ chosenMold.moldNo }}：{{ chosenMoldBlock }}（页面可能已过期，刷新后仍以服务端结果为准）
            </template>
            <template v-else>
              剩余寿命 <b>{{ chosenMold.remainCount }}</b> / {{ chosenMold.lifeCount }} 次 ·
              适用 {{ chosenMold.fitMaterialName || '不限' }} ·
              {{ chosenMold.cavityCount }} 个型腔，型腔号填 1～{{ chosenMold.cavityCount }}
            </template>
          </div>
        </el-form-item>
        <el-form-item label="型腔号">
          <el-input v-model="form.cavityNo" :placeholder="chosenMold ? `1～${chosenMold.cavityCount}` : '型腔号'" />
        </el-form-item>
        <el-form-item label="理论重量"><el-input-number v-model="form.theoryWeight" :min="0.1" :step="0.1" /></el-form-item>
        <el-form-item label="客户"><el-input v-model="form.customer" /></el-form-item>
        <el-form-item label="操作者"><el-input v-model="form.operator" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="loading" @click="createCasting">建立卡片</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.card-wrap { display: grid; grid-template-columns: minmax(0, 1.35fr) minmax(0, 1fr); gap: 12px; align-items: start; }

.cast-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(228px, 1fr)); gap: 10px; }

.cast-card {
  padding: 10px 12px;
  border-radius: 11px;
  border: 1px solid var(--line-strong);
  border-left: 5px solid var(--primary);
  background: #fff;
  cursor: pointer;
  transition: transform .18s ease, box-shadow .18s ease;
}
.cast-card:hover { transform: translateY(-2px); box-shadow: 0 10px 22px rgba(78, 40, 96, 0.13); }
.cast-card.on { box-shadow: 0 0 0 2px var(--primary-soft); }

.cast-head { display: flex; align-items: center; gap: 8px; }
.cast-no { font-size: 15px; font-weight: 800; color: var(--primary-deep); letter-spacing: 0.4px; }
.cast-state {
  margin-left: auto;
  padding: 2px 8px;
  border-radius: 6px;
  font-size: 11px;
  color: #fff;
}
.cast-name { font-size: 12.5px; color: var(--ink); margin: 4px 0 8px; }

.cast-derived {
  padding: 7px 9px;
  border-radius: 8px;
  background: repeating-linear-gradient(135deg, #f7f1fa 0 8px, #fbf8fd 8px 16px);
  border: 1px dashed var(--primary-soft);
  margin-bottom: 8px;
}
.d-line { display: flex; align-items: center; gap: 6px; font-size: 11.5px; padding: 1px 0; }
.d-k { color: var(--ink-dim); min-width: 56px; }
.d-v { color: var(--primary-deep); font-weight: 700; }

.cast-foot { display: flex; gap: 6px; flex-wrap: wrap; }

.detail-no { font-size: 20px; font-weight: 800; color: var(--primary-deep); letter-spacing: 1px; }
.detail-name { font-size: 12.5px; color: var(--ink-soft); margin-bottom: 8px; }

.flow-strip { display: flex; gap: 4px; margin-bottom: 10px; flex-wrap: wrap; }
.flow-node {
  padding: 3px 9px;
  border-radius: 6px;
  font-size: 11px;
  background: #f1ecf6;
  color: var(--ink-dim);
  border: 1px solid var(--line);
}
.flow-node.done { background: var(--primary-wash); color: var(--primary-deep); border-color: var(--primary-soft); }
.flow-node.cur { background: var(--primary); color: #fff; font-weight: 700; }

.derived-box {
  margin: 10px 0;
  padding: 9px 10px;
  border-radius: 9px;
  border: 1px dashed var(--primary-soft);
  background: #fbf8fd;
}
.derived-title { font-size: 11.5px; font-weight: 700; color: var(--primary-deep); margin-bottom: 5px; }

.op-row { display: flex; gap: 8px; flex-wrap: wrap; }
.op-note { margin-top: 8px; font-size: 11.5px; color: var(--ink-dim); line-height: 1.7; }

.mold-meta {
  margin-top: 5px;
  font-size: 11.5px;
  line-height: 1.7;
  color: var(--ink-dim);
}
.mold-meta b { color: var(--primary-deep); }
.mold-meta.blocked { color: #c0392b; }
</style>
