import { reactive, computed } from 'vue'

/**
 * 登录态的唯一真相源。
 *
 * 为什么不直接读 localStorage：它不是响应式数据源，Vue 的 computed 追踪不到它的变化。
 * 之前 App.vue 里 `computed(() => localStorage.getItem('role') === '3')` 没有任何响应式依赖，
 * 首次求值后就被永久缓存 —— 登录写入 role 之后视图不会更新，「教务后台」入口要刷新才出现。
 *
 * 这里改成：状态放在 reactive 对象里（视图读它），写入时同步落 localStorage（刷新后能恢复）。
 */
const state = reactive({
  token: localStorage.getItem('token') || '',
  role: localStorage.getItem('role') || '',
  realName: localStorage.getItem('realName') || '',
  studentId: localStorage.getItem('studentId') || ''
})

function persist() {
  localStorage.setItem('token', state.token)
  localStorage.setItem('role', state.role)
  localStorage.setItem('realName', state.realName)
  localStorage.setItem('studentId', state.studentId)
}

export const auth = {
  state,

  isLoggedIn: computed(() => !!state.token),
  isTeacher: computed(() => state.role === '2'),
  isAdmin: computed(() => state.role === '3'),
  realName: computed(() => state.realName),

  /** 登录成功后调用，参数是后端返回的 data */
  login(data) {
    state.token = data.token
    state.role = String(data.role)
    state.realName = data.realName || ''
    state.studentId = data.studentId ?? ''
    persist()
  },

  /** 退出、或 token 失效时调用 */
  clear() {
    state.token = ''
    state.role = ''
    state.realName = ''
    state.studentId = ''
    localStorage.clear()
  }
}
