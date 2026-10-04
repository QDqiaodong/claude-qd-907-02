import { createRouter, createWebHashHistory } from 'vue-router'

// 注意：main.js 里没有 app.use(router)，路由实例由 App.vue 直接驱动，
// 所以这里全部用静态 import —— <component :is> 拿到的是组件对象本身。
import SmeltView from '../views/SmeltView.vue'
import ChargeView from '../views/ChargeView.vue'
import MoldView from '../views/MoldView.vue'
import CastingView from '../views/CastingView.vue'
import InspectionView from '../views/InspectionView.vue'
import ReworkView from '../views/ReworkView.vue'

const routes = [
  { path: '/', redirect: '/smelt' },
  { path: '/smelt', name: 'smelt', component: SmeltView },
  { path: '/charge', name: 'charge', component: ChargeView },
  { path: '/mold', name: 'mold', component: MoldView },
  { path: '/casting', name: 'casting', component: CastingView },
  { path: '/inspection', name: 'inspection', component: InspectionView },
  { path: '/rework', name: 'rework', component: ReworkView },
  { path: '/:pathMatch(.*)*', redirect: '/smelt' }
]

export default createRouter({
  history: createWebHashHistory(),
  routes
})
