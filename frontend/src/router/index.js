import { createRouter, createWebHistory } from 'vue-router'
import { auth } from '../stores/auth'

const routes = [
  // 教师没有选课权限，落地页不同，所以根路径按角色分流
  { path: '/', redirect: () => (auth.isTeacher.value ? '/teaching' : '/courses') },
  { path: '/login', component: () => import('../views/Login.vue'), meta: { anon: true } },
  { path: '/courses', component: () => import('../views/CourseList.vue'), meta: { title: '选课' } },
  { path: '/timetable', component: () => import('../views/MyTimetable.vue'), meta: { title: '我的课表' } },
  { path: '/teaching', component: () => import('../views/TeacherClasses.vue'), meta: { title: '我的任课', teacher: true } },
  { path: '/admin', component: () => import('../views/AdminPanel.vue'), meta: { title: '教务后台', admin: true } }
]

const router = createRouter({ history: createWebHistory(), routes })

router.beforeEach(to => {
  if (!to.meta.anon && !auth.isLoggedIn.value) return '/login'
  // 教务后台只有教务角色能进。真正的拦截在后端，这里只是少让用户白跑一趟
  if (to.meta.admin && !auth.isAdmin.value) return '/'
  if (to.meta.teacher && !auth.isTeacher.value) return '/'
  // 教师账号没有学生身份，进选课页只会看到一堆空数据，直接挡回去
  if (auth.isTeacher.value && (to.path === '/courses' || to.path === '/timetable')) return '/teaching'
  if (to.path === '/login' && auth.isLoggedIn.value) return '/'
  return true
})

// 路由组件是按需 import 的。开发期热更新或重启 Vite 之后，旧的 chunk 地址会失效，
// 动态 import 直接 reject —— 表现就是点导航没反应、手动刷新才好。
// 这里兜底重载一次，用 sessionStorage 标记防止失败时反复刷新。
const RELOAD_FLAG = 'chunk-reload-once'
const CHUNK_ERR = /Failed to fetch dynamically imported module|Importing a module script failed|error loading dynamically imported module/i

router.onError((err, to) => {
  if (!CHUNK_ERR.test(err?.message || '')) return
  if (sessionStorage.getItem(RELOAD_FLAG)) return
  sessionStorage.setItem(RELOAD_FLAG, '1')
  window.location.assign(to.fullPath)
})

router.afterEach(() => sessionStorage.removeItem(RELOAD_FLAG))

export default router
