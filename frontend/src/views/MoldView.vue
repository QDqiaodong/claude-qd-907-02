<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import router from '../router'
import api from '../api'

const board = ref({ molds: [], types: [], typeMeta: [], warnRate: 20 })
const loading = ref(false)
const filterType = ref('ALL')
const dialog = ref(false)

const form = ref({
  moldNo: '', moldName: '', moldType: 'WAX', cavityCount: 4, lifeCount: 50,
  usedCount: 0, fitMaterial: 'CAST_IRON', storageSlot: '', note: ''
})

const routeQuery = computed(() => router.currentRoute.value?.query || {})
const schedulableMode = computed(() => routeQuery.value.view === 'schedulable')

const molds = computed(() => board.value.molds || [])
const shown = computed(() =>
  filterType.value === 'ALL' ? molds.value : molds.value.filter((m) => m.moldType === filterType.value)
)
const schedulable = computed(() => molds.value.filter((m) => m.schedulable))

function ringColor(mold) {
  if (mold.overLife || mold.status === 'SCRAPPED') return '#9e9e9e'
  if (mold.warn) return '#f9a825'
  return '#2e7d32'
}

function ringStyle(mold) {
  const pct = Math.min(100, Math.max(0, mold.usedRate))
  return {
    background: `conic-gradient(${ringColor(mold)} 0% ${pct}%, #ebe1f2 ${pct}% 100%)`
  }
}

async function filterChip(code) {
  filterType.value = code
}

async function consume(mold) {
  loading.value = true
  try {
    const updated = await api.mold.consume(mold.id)
    ElMessage.success(`${updated.moldNo} 排产 +1，已用 ${updated.usedCount}/${updated.lifeCount} 次`)
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

async function createMold() {
  loading.value = true
  try {
    await api.mold.create(form.value)
    ElMessage.success('模具已建档')
    dialog.value = false
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
    board.value = await api.mold.board()
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
      <span class="view-title">造型工段 · 模具寿命进度环</span>
      <span class="view-tip">每环＝一套模具的寿命消耗进度，中心为剩余次数；剩余低于 {{ board.warnRate }}% 转黄，超寿命或报废转灰并禁用排产</span>
    </div>

    <section class="panel" v-if="!schedulableMode">
      <div class="panel-title">
        寿命进度环阵列
        <span class="view-tip">共 {{ molds.length }} 套 · 剩余 {{ schedulable.length }} 套可排产</span>
      </div>

      <div class="chip-row">
        <span class="chip" :class="{ on: filterType === 'ALL' }" @click="filterChip('ALL')">全部</span>
        <span
          v-for="t in board.typeMeta"
          :key="t.code"
          class="chip"
          :class="{ on: filterType === t.code }"
          @click="filterChip(t.code)"
        >{{ t.name }} · {{ t.count }}</span>
      </div>

      <div class="ring-grid" v-if="shown.length">
        <div
          v-for="mold in shown"
          :key="mold.id"
          class="ring-cell"
          :class="{ dead: mold.overLife || mold.status === 'SCRAPPED' }"
        >
          <div class="ring-wrap" :style="ringStyle(mold)">
            <div class="ring-inner">
              <div class="ring-num" :style="{ color: ringColor(mold) }">{{ mold.remainCount }}</div>
              <div class="ring-label">剩余次数</div>
            </div>
          </div>
          <div class="ring-no">{{ mold.moldNo }}</div>
          <div class="ring-name">{{ mold.moldName }}</div>
          <div class="ring-meta">
            <span class="tag-line">型腔 {{ mold.cavityCount }}</span>
            <span class="tag-line">已用 {{ mold.usedCount }}/{{ mold.lifeCount }}</span>
          </div>
          <div class="ring-rate" :style="{ color: ringColor(mold) }">消耗 {{ mold.usedRate }}%</div>
          <el-button
            size="small"
            type="primary"
            plain
            :disabled="mold.overLife || mold.status === 'SCRAPPED'"
            @click="consume(mold)"
          >
            {{ mold.overLife || mold.status === 'SCRAPPED' ? '禁止排产' : '排产 +1' }}
          </el-button>
        </div>
      </div>
      <div class="empty-tip" v-else>该类型下暂无模具</div>
    </section>

    <section class="panel" v-else>
      <div class="panel-title">可排产模具清单</div>
      <el-table :data="schedulable" size="small" border stripe>
        <el-table-column prop="moldNo" label="模具编号" width="130" />
        <el-table-column prop="moldName" label="名称" min-width="160" />
        <el-table-column prop="moldTypeName" label="型壳类型" width="120" />
        <el-table-column prop="cavityCount" label="型腔数" width="90" />
        <el-table-column label="寿命" width="110">
          <template #default="{ row }">{{ row.usedCount }} / {{ row.lifeCount }}</template>
        </el-table-column>
        <el-table-column label="剩余率" width="110">
          <template #default="{ row }">
            <b :style="{ color: ringColor(row) }">{{ row.remainRate }}%</b>
          </template>
        </el-table-column>
        <el-table-column prop="storageSlot" label="存放位" min-width="130" />
        <el-table-column label="操作" width="110">
          <template #default="{ row }">
            <el-button size="small" type="primary" plain @click="consume(row)">排产 +1</el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <div class="grid-2" style="margin-top:12px">
      <section class="panel">
        <div class="panel-title">型壳类型寿命统计（JPQL ④）</div>
        <el-table :data="board.types" size="small" border stripe>
          <el-table-column prop="moldTypeName" label="型壳类型" min-width="140" />
          <el-table-column prop="count" label="套数" width="80" />
          <el-table-column prop="avgUsedRate" label="平均消耗率 %" width="120" />
          <el-table-column prop="usedSum" label="已用次数合计" width="120" />
        </el-table>
      </section>

      <section class="panel">
        <div class="panel-title">新模具建档</div>
        <el-form label-width="88px">
          <el-form-item label="模具编号"><el-input v-model="form.moldNo" placeholder="如 M-CER-033" /></el-form-item>
          <el-form-item label="名称"><el-input v-model="form.moldName" placeholder="如 航空接头陶瓷型壳" /></el-form-item>
          <el-form-item label="型壳类型">
            <el-select v-model="form.moldType" style="width:100%">
              <el-option label="熔模蜡型壳" value="WAX" />
              <el-option label="树脂砂型" value="RESIN_SAND" />
              <el-option label="陶瓷型壳" value="CERAMIC" />
              <el-option label="金属型" value="METAL" />
            </el-select>
          </el-form-item>
          <el-form-item label="型腔数 / 寿命">
            <el-input-number v-model="form.cavityCount" :min="1" size="small" />
            <el-input-number v-model="form.lifeCount" :min="1" :step="10" size="small" style="margin-left:8px" />
          </el-form-item>
          <el-form-item label="适用材质">
            <el-select v-model="form.fitMaterial" style="width:100%">
              <el-option label="灰铸铁 HT250" value="CAST_IRON" />
              <el-option label="球墨铸铁 QT450" value="DUCTILE_IRON" />
              <el-option label="铸造碳钢 ZG270" value="CARBON_STEEL" />
              <el-option label="不锈钢 CF8C" value="STAINLESS" />
              <el-option label="铸造铝合金 ZL104" value="ALUMINUM" />
            </el-select>
          </el-form-item>
          <el-form-item label="存放位"><el-input v-model="form.storageSlot" placeholder="如 陶瓷区 C-03" /></el-form-item>
        </el-form>
        <el-button size="small" type="primary" :loading="loading" @click="createMold">建档</el-button>
      </section>
    </div>
    <el-dialog v-model="dialog" title="新模具建档" width="460px"><div /></el-dialog>
  </div>
</template>

<style scoped>
.ring-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(158px, 1fr));
  gap: 12px;
}

.ring-cell {
  padding: 12px 10px 10px;
  border-radius: 12px;
  border: 1px solid var(--line-strong);
  background: #fff;
  text-align: center;
  box-shadow: 0 4px 14px rgba(78, 40, 96, 0.06);
  transition: transform .2s ease, box-shadow .2s ease;
}
.ring-cell:hover { transform: translateY(-2px); box-shadow: 0 10px 22px rgba(78, 40, 96, 0.12); }
.ring-cell.dead { background: #f4f2f6; filter: grayscale(0.4); }

.ring-wrap {
  width: 96px;
  height: 96px;
  margin: 0 auto 8px;
  border-radius: 50%;
  padding: 9px;
}
.ring-inner {
  width: 100%;
  height: 100%;
  border-radius: 50%;
  background: #fff;
  display: grid;
  place-items: center;
  box-shadow: inset 0 1px 4px rgba(80, 40, 100, 0.12);
}
.ring-num { font-size: 21px; font-weight: 800; line-height: 1; }
.ring-label { font-size: 10px; color: var(--ink-dim); margin-top: 2px; }

.ring-no { font-size: 12.5px; font-weight: 800; color: var(--primary-deep); }
.ring-name { font-size: 11.5px; color: var(--ink-soft); margin: 2px 0 6px; min-height: 16px; }
.ring-meta { display: flex; gap: 4px; justify-content: center; flex-wrap: wrap; margin-bottom: 4px; }
.ring-rate { font-size: 12px; font-weight: 700; margin-bottom: 8px; }
</style>
