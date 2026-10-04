<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import router from './router'
import api from './api'

import SmeltView from './views/SmeltView.vue'
import ChargeView from './views/ChargeView.vue'
import MoldView from './views/MoldView.vue'
import CastingView from './views/CastingView.vue'
import InspectionView from './views/InspectionView.vue'
import ReworkView from './views/ReworkView.vue'

// 左侧一列按「工段」分组的手风琴：点组名才展开该组子项，同一时刻只有一组是展开的。
// 页面没有顶部菜单，工段切换全靠这一列。
const groups = [
  {
    key: 'melt',
    name: '熔炼工段',
    desc: '炉次 · 温度带 · 配料',
    children: [
      { label: '炉次温度带', path: '/smelt', query: {} },
      { label: '配料配比条', path: '/charge', query: {} }
    ]
  },
  {
    key: 'shaping',
    name: '造型工段',
    desc: '蜡型壳 · 砂型 · 金属型',
    children: [
      { label: '模具寿命环', path: '/mold', query: {} },
      { label: '可排产模具', path: '/mold', query: { view: 'schedulable' } }
    ]
  },
  {
    key: 'pour',
    name: '浇注清理',
    desc: '铸件卡 · 状态流转',
    children: [
      { label: '铸件大卡片', path: '/casting', query: {} },
      { label: '状态流转表', path: '/casting', query: { view: 'flow' } }
    ]
  },
  {
    key: 'judge',
    name: '检验判定',
    desc: '缺陷徽章 · 结论三选一',
    children: [
      { label: '缺陷判定', path: '/inspection', query: {} },
      { label: '返修处置与复检', path: '/rework', query: {} },
      { label: '缺陷分布', path: '/inspection', query: { view: 'stat' } }
    ]
  }
]

const route = computed(() => router.currentRoute.value)
const currentPath = computed(() => route.value?.path || '/smelt')
const currentQuery = computed(() => route.value?.query || {})

// main.js 里没有 app.use(router)，App.vue 自己驱动路由实例，用 <component :is> 渲染当前组件。
const viewMap = {
  '/smelt': SmeltView,
  '/charge': ChargeView,
  '/mold': MoldView,
  '/casting': CastingView,
  '/inspection': InspectionView,
  '/rework': ReworkView
}

const currentView = computed(
  () => viewMap[currentPath.value]
    || route.value?.matched?.[0]?.components?.default
    || SmeltView
)

const openKey = ref('melt')
const overview = ref(null)

function groupOfPath(path) {
  const found = groups.find((g) => g.children.some((c) => c.path === path))
  return found ? found.key : groups[0].key
}

function toggle(key) {
  openKey.value = openKey.value === key ? '' : key
}

function go(child) {
  router.push({ path: child.path, query: child.query })
}

function isChildOn(child) {
  if (child.path !== currentPath.value) return false
  const q = currentQuery.value || {}
  const keys = Object.keys(child.query || {})
  return keys.length === Object.keys(q).length
    && keys.every((k) => String(q[k] ?? '') === String(child.query[k]))
}

function hasActive(group) {
  return group.children.some(isChildOn)
}

async function loadOverview() {
  try {
    overview.value = await api.stats.overview()
  } catch (e) {
    overview.value = null
  }
}

watch(currentPath, (path) => {
  openKey.value = groupOfPath(path)
})

onMounted(() => {
  loadOverview()
  // 路由没有 install，初始 currentRoute 是 START_LOCATION，这里按地址栏补一次导航
  const hash = window.location.hash.replace(/^#/, '')
  const target = hash.startsWith('/') && hash !== '/' ? hash : '/smelt'
  openKey.value = groupOfPath(target.split('?')[0])
  router.replace(target)
})
</script>

<template>
  <div class="app-shell">
    <header class="app-brand">
      <div class="brand-mark">铸</div>
      <div>
        <div class="brand-title">精密铸造车间 · 熔炼炉次与铸件质量管理</div>
        <div class="brand-sub">熔炼 · 造型 · 浇注清理 · 检验判定</div>
      </div>
      <div class="top-stats" v-if="overview">
        <div class="top-stat"><b>{{ overview.furnaceCount }}</b><span>熔炼炉</span></div>
        <div class="top-stat"><b>{{ overview.heatCount }}</b><span>炉次</span></div>
        <div class="top-stat"><b>{{ overview.castingCount }}</b><span>铸件卡</span></div>
        <div class="top-stat"><b>{{ overview.storedCount }}</b><span>已入库</span></div>
        <div class="top-stat"><b>{{ overview.passCount }}</b><span>检验合格</span></div>
        <div class="top-stat"><b>{{ overview.scrapCount }}</b><span>判废</span></div>
      </div>
    </header>

    <div class="app-body">
      <!-- 左侧折叠手风琴：唯一导航 -->
      <nav class="accordion">
        <div class="acc-caption">WORKSHOP SECTIONS</div>
        <div v-for="(group, idx) in groups" :key="group.key" class="acc-group">
          <div
            class="acc-head"
            :class="{ open: openKey === group.key || hasActive(group) }"
            @click="toggle(group.key)"
          >
            <span class="acc-index">{{ idx + 1 }}</span>
            <span class="acc-text">
              <span class="acc-name">{{ group.name }}</span>
              <span class="acc-desc">{{ group.desc }}</span>
            </span>
            <span class="acc-arrow">▶</span>
          </div>
          <transition name="acc">
            <div class="acc-body" v-show="openKey === group.key">
              <div
                v-for="child in group.children"
                :key="child.label"
                class="acc-item"
                :class="{ on: isChildOn(child) }"
                @click="go(child)"
              >{{ child.label }}</div>
            </div>
          </transition>
        </div>
      </nav>

      <main class="app-content">
        <component :is="currentView" :key="currentPath" />
      </main>
    </div>
  </div>
</template>

<style scoped>
.acc-text { display: block; }
.acc-name { display: block; }
.acc-desc { display: block; }

.acc-enter-active,
.acc-leave-active { transition: max-height .24s ease, opacity .2s ease; }
.acc-enter-from,
.acc-leave-to { max-height: 0; opacity: 0; }
.acc-enter-to,
.acc-leave-from { max-height: 240px; opacity: 1; }
</style>
