import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '../router'
import { auth } from '../stores/auth'

const http = axios.create({ baseURL: '/api', timeout: 20000 })

// 每个请求带上 JWT。后端一律从 token 里取学生身份，
// 不接受前端传 studentId —— 否则改个参数就能替别人选课
http.interceptors.request.use(cfg => {
  const token = auth.state.token
  if (token) cfg.headers.Authorization = `Bearer ${token}`
  return cfg
})

http.interceptors.response.use(
  resp => resp.data,
  err => {
    const body = err.response?.data
    if (body?.code === 1002) {
      auth.clear()
      router.push('/login')
      ElMessage.error('登录已过期，请重新登录')
    } else {
      ElMessage.error(body?.msg || err.message || '请求失败')
    }
    return Promise.reject(err)
  }
)

/** 业务码不为 0 时给出提示，调用方只需判断返回值是否为 null */
export function unwrap(res, { silent = false } = {}) {
  if (!res) return null
  if (res.code !== 0) {
    if (!silent) ElMessage.warning(res.msg)
    return null
  }
  return res.data
}

export const api = {
  login: (username, password) => http.post('/auth/login', { username, password }),
  health: () => http.get('/health'),

  classes: () => http.get('/teaching-classes'),
  myTimetable: () => http.get('/selection/my'),
  currentRound: () => http.get('/rounds/current'),

  preCheck: classId => http.post('/selection/pre-check', { classId }),
  select: classId => http.post('/selection/select', { classId }),
  status: classId => http.get('/selection/status', { params: { classId } }),
  drop: classId => http.post('/selection/drop', { classId }),
  waiting: classId => http.post('/selection/waiting', { classId }),

  teacherClasses: () => http.get('/teacher/classes'),
  teacherRoster: classId => http.get(`/teacher/classes/${classId}/roster`),

  rounds: () => http.get('/admin/rounds'),
  warmup: roundId => http.post(`/admin/warmup/${roundId}`),
  reconcile: autoFix => http.get('/admin/reconcile', { params: { autoFix } }),
  stats: () => http.get('/admin/stats'),
  switchStrategy: name => http.post('/admin/strategy', null, { params: { name } }),
  resetExperiment: classId => http.post(`/admin/reset-experiment/${classId}`)
}

export default http
