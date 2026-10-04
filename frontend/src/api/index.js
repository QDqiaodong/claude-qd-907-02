import axios from 'axios'

const http = axios.create({ baseURL: '/api', timeout: 20000 })

// 后端统一返回 {ok, message, data}；HTTP 400 的业务异常也要把中文提示带出来
http.interceptors.response.use(
  (response) => response,
  (error) => {
    const message = error?.response?.data?.message || error?.message || '请求失败'
    return Promise.reject(new Error(message))
  }
)

async function call(promise) {
  const response = await promise
  const body = response.data
  if (!body || body.ok === false) {
    throw new Error((body && body.message) || '请求失败')
  }
  return body.data
}

const smelt = {
  board: () => call(http.get('/smelt/board')),
  furnaces: () => call(http.get('/smelt/furnaces')),
  createFurnace: (data) => call(http.post('/smelt/furnaces', data)),
  updateFurnace: (id, data) => call(http.put(`/smelt/furnaces/${id}`, data)),
  removeFurnace: (id) => call(http.delete(`/smelt/furnaces/${id}`)),
  heats: () => call(http.get('/smelt/heats')),
  heat: (id) => call(http.get(`/smelt/heats/${id}`)),
  createHeat: (data) => call(http.post('/smelt/heats', data)),
  updateHeat: (id, data) => call(http.put(`/smelt/heats/${id}`, data)),
  removeHeat: (id) => call(http.delete(`/smelt/heats/${id}`)),
  advance: (id, targetStage) =>
    call(http.post(`/smelt/heats/${id}/stage?targetStage=${encodeURIComponent(targetStage)}`)),
  tap: (id, data) => call(http.post(`/smelt/heats/${id}/tap`, data)),
  furnaceLoad: () => call(http.get('/smelt/furnace-load')),
  heatsOfFurnace: (furnaceId) => call(http.get(`/smelt/heats/furnace/${furnaceId}`))
}

const charge = {
  board: () => call(http.get('/charge/board')),
  list: () => call(http.get('/charge/list')),
  detail: (id) => call(http.get(`/charge/${id}`)),
  create: (data) => call(http.post('/charge', data)),
  adjust: (id, data) => call(http.post(`/charge/${id}/adjust`, data)),
  remove: (id) => call(http.delete(`/charge/${id}`)),
  // 双方案推演：未出炉炉次看板 + 同一输入版本一次算两套方案（只读试算）
  simulateHeats: () => call(http.get('/charge/simulate/heats')),
  simulate: (data) => call(http.post('/charge/simulate', data))
}

const mold = {
  board: () => call(http.get('/mold/board')),
  list: () => call(http.get('/mold/list')),
  schedulable: () => call(http.get('/mold/schedulable')),
  get: (id) => call(http.get(`/mold/${id}`)),
  create: (data) => call(http.post('/mold', data)),
  update: (id, data) => call(http.put(`/mold/${id}`, data)),
  consume: (id) => call(http.post(`/mold/${id}/consume`)),
  remove: (id) => call(http.delete(`/mold/${id}`))
}

const casting = {
  board: () => call(http.get('/casting/board')),
  list: () => call(http.get('/casting/list')),
  get: (id) => call(http.get(`/casting/${id}`)),
  detail: (id) => call(http.get(`/casting/${id}/detail`)),
  create: (data) => call(http.post('/casting', data)),
  update: (id, data) => call(http.put(`/casting/${id}`, data)),
  transition: (id, status) =>
    call(http.post(`/casting/${id}/status?status=${encodeURIComponent(status)}`)),
  remove: (id) => call(http.delete(`/casting/${id}`))
}

const inspection = {
  board: () => call(http.get('/inspection/board')),
  list: () => call(http.get('/inspection/list')),
  detail: (id) => call(http.get(`/inspection/${id}`)),
  create: (data) => call(http.post('/inspection', data)),
  remove: (id) => call(http.delete(`/inspection/${id}`))
}

// 返修处置与复检闭环：返修中/待复检队列 → 完成登记 → 撤回 → 复检（合格/再返修/报废）→ 追加更正
const rework = {
  board: (stage) => call(http.get('/rework/board', { params: stage ? { stage } : {} })),
  detail: (id) => call(http.get(`/rework/${id}`)),
  complete: (id, data) => call(http.post(`/rework/${id}/complete`, data)),
  withdraw: (id, operator) => call(http.post(`/rework/${id}/withdraw`, { operator })),
  reinspect: (id, data) => call(http.post(`/rework/${id}/reinspect`, data)),
  correct: (id, data) => call(http.post(`/rework/${id}/correct`, data))
}

const stats = {
  overview: () => call(http.get('/stats/overview')),
  dict: () => call(http.get('/stats/dict')),
  furnace: () => call(http.get('/stats/furnace')),
  castingStatus: () => call(http.get('/stats/casting-status')),
  material: () => call(http.get('/stats/material')),
  moldLife: () => call(http.get('/stats/mold-life')),
  defect: () => call(http.get('/stats/defect')),
  verdict: () => call(http.get('/stats/verdict'))
}

export default { http, smelt, charge, mold, casting, inspection, rework, stats }
