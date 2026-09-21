<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api, unwrap } from '../api'
import { auth } from '../stores/auth'

const router = useRouter()
const loading = ref(false)
const form = reactive({ username: 's0001', password: '123456' })

async function submit() {
  if (!form.username || !form.password) {
    ElMessage.warning('请填写用户名和密码')
    return
  }
  loading.value = true
  try {
    const data = unwrap(await api.login(form.username, form.password))
    if (!data) return
    auth.login(data)
    ElMessage.success(`欢迎，${data.realName}`)
    router.push(data.role === 3 ? '/admin' : '/courses')
  } catch (e) {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
}

function fill(u) {
  form.username = u
  form.password = '123456'
}
</script>

<template>
  <div style="display:flex;justify-content:center;padding-top:80px">
    <el-card style="width:420px">
      <template #header>
        <div style="font-weight:700;font-size:18px">高校选课系统</div>
        <div style="color:#5a635b;font-size:12px;margin-top:4px">
          基于缓存与消息队列的高并发选课
        </div>
      </template>

      <el-form :model="form" label-width="72px" @submit.prevent="submit">
        <el-form-item label="用户名">
          <el-input v-model="form.username" placeholder="学号或 admin" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="form.password" type="password" show-password
                    @keyup.enter="submit" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" style="width:100%" @click="submit">
            登录
          </el-button>
        </el-form-item>
      </el-form>

      <el-divider>演示账号</el-divider>
      <div style="display:flex;gap:8px">
        <el-button size="small" @click="fill('s0001')">学生 s0001</el-button>
        <el-button size="small" @click="fill('s0002')">学生 s0002</el-button>
        <el-button size="small" @click="fill('t001')">教师 t001</el-button>
        <el-button size="small" @click="fill('admin')">教务 admin</el-button>
      </div>
      <div style="color:#5a635b;font-size:12px;margin-top:10px">
        口令统一 123456，学生账号 s0001 ~ s3000，教师账号 t001 ~ t005。口令以 BCrypt 加盐哈希存储，数据库里没有明文。</div>
    </el-card>
  </div>
</template>
