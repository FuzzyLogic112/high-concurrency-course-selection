<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { auth } from './stores/auth'

const route = useRoute()
const router = useRouter()

// 登录态一律从 auth 读。直接读 localStorage 的话 computed 追踪不到写入，
// 登录后「教务后台」入口不会出现，要手动刷新才行。
const loggedIn = computed(() => auth.isLoggedIn.value && route.path !== '/login')
const realName = auth.realName
const isTeacher = auth.isTeacher
const isAdmin = auth.isAdmin

function logout() {
  auth.clear()
  ElMessage.success('已退出登录')
  router.push('/login')
}
</script>

<template>
  <el-container>
    <el-header v-if="loggedIn" height="56px"
               style="background:#1f2420;display:flex;align-items:center;gap:20px">
      <span style="color:#fff;font-weight:700;font-size:16px">高校选课系统</span>
      <el-menu mode="horizontal" :default-active="route.path" router
               background-color="#1f2420" text-color="#b9c2ba" active-text-color="#5bae9f"
               style="border:0;flex:1">
        <el-menu-item v-if="!isTeacher" index="/courses">选课</el-menu-item>
        <el-menu-item v-if="!isTeacher" index="/timetable">我的课表</el-menu-item>
        <el-menu-item v-if="isTeacher" index="/teaching">我的任课</el-menu-item>
        <el-menu-item v-if="isAdmin" index="/admin">教务后台</el-menu-item>
      </el-menu>
      <span style="color:#b9c2ba;font-size:13px">{{ realName }}</span>
      <el-button link type="info" @click="logout">退出</el-button>
    </el-header>

    <el-main style="padding:0">
      <router-view />
    </el-main>
  </el-container>
</template>
