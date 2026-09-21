<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, unwrap } from '../api'

const classes = ref([])
const round = ref(null)
const loading = ref(false)
const keyword = ref('')
const acting = ref(null)
let timer = null

const filtered = computed(() => {
  const k = keyword.value.trim()
  if (!k) return classes.value
  return classes.value.filter(c =>
    (c.courseName || '').includes(k) ||
    (c.teacherName || '').includes(k) ||
    (c.classNo || '').includes(k))
})

const roundOpen = computed(() => {
  if (!round.value) return false
  const now = Date.now()
  return new Date(round.value.startTime).getTime() <= now
      && now <= new Date(round.value.endTime).getTime()
})

async function load(silent = false) {
  if (!silent) loading.value = true
  try {
    const [cs, rd] = await Promise.all([api.classes(), api.currentRound()])
    classes.value = unwrap(cs) || []
    round.value = unwrap(rd)
  } finally {
    loading.value = false
  }
}

/** 选课：先前置校验给提示，通过了再真正提交 */
async function doSelect(row) {
  acting.value = row.id
  try {
    const pre = unwrap(await api.preCheck(row.id))
    if (pre && !pre.passed) {
      ElMessage.warning(pre.msg)
      return
    }
    const res = await api.select(row.id)
    if (res.code !== 0) {
      // 名额满时提供候补入口
      if (res.code === 3001) {
        await offerWaiting(row)
      } else {
        ElMessage.warning(res.msg)
      }
      return
    }
    const d = res.data
    if (d.ticket) {
      // redis_mq 是异步落库，拿受理凭证轮询最终结果
      ElMessage.info('选课请求已受理，正在确认…')
      await pollStatus(row.id)
    } else {
      ElMessage.success('选课成功')
    }
    await load(true)
  } finally {
    acting.value = null
  }
}

async function pollStatus(classId) {
  for (let i = 0; i < 20; i++) {
    await new Promise(r => setTimeout(r, 300))
    const d = unwrap(await api.status(classId), { silent: true })
    if (d && d.accepted && d.code === 0) {
      ElMessage.success('选课成功')
      return
    }
  }
  ElMessage.warning('仍在处理中，请稍后刷新查看')
}

async function offerWaiting(row) {
  try {
    await ElMessageBox.confirm(
      `《${row.courseName}》名额已满，是否加入候补队列？有人退选时会按排队顺序自动补位。`,
      '名额已满', { confirmButtonText: '加入候补', cancelButtonText: '算了', type: 'warning' })
    const res = await api.waiting(row.id)
    if (res.code === 0) ElMessage.success('已加入候补队列')
  } catch { /* 用户取消 */ }
}

async function doDrop(row) {
  try {
    await ElMessageBox.confirm(
      `确认退掉《${row.courseName}》？名额会立即释放给候补队列。`,
      '退选', { type: 'warning' })
  } catch { return }
  acting.value = row.id
  try {
    const res = await api.drop(row.id)
    if (res.code === 0) {
      ElMessage.success('已退选')
      await load(true)
    }
  } finally {
    acting.value = null
  }
}

onMounted(() => {
  load()
  // 余量实时刷新。真实选课场景里这个数字每秒都在变
  timer = setInterval(() => load(true), 5000)
})
onUnmounted(() => clearInterval(timer))
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>选课</h2>
        <div class="sub">
          <template v-if="round">
            {{ round.name }} ·
            <el-tag v-if="roundOpen" type="success" size="small" effect="plain">选课中</el-tag>
            <el-tag v-else type="info" size="small" effect="plain">未开放</el-tag>
            <span v-if="round.warmedUp === 0" style="color:#a63527;margin-left:8px">
              名额尚未预热，教务需先执行预热
            </span>
          </template>
          <template v-else>暂无选课轮次</template>
        </div>
      </div>
      <div style="display:flex;gap:10px">
        <el-input v-model="keyword" placeholder="搜课程名 / 教师 / 教学班号"
                  clearable style="width:260px" />
        <el-button :loading="loading" @click="load()">刷新</el-button>
      </div>
    </div>

    <el-alert type="info" :closable="false" show-icon style="margin-bottom:14px">
      余量每 5 秒自动刷新，读自 Redis。点「选课」会先做一次前置校验
      （时间冲突、学分上限、先修课程），不通过不会占用名额。
    </el-alert>

    <el-table :data="filtered" v-loading="loading" stripe border style="width:100%">
      <el-table-column prop="classNo" label="教学班号" width="120" class-name="mono" />
      <el-table-column prop="courseName" label="课程" min-width="130" />
      <el-table-column prop="credit" label="学分" width="72" align="center" />
      <el-table-column prop="teacherName" label="教师" width="90" />
      <el-table-column prop="timeSlots" label="上课时间" min-width="180" />
      <el-table-column prop="location" label="地点" width="110" />
      <el-table-column label="余量 / 容量" width="130" align="center">
        <template #default="{ row }">
          <span :class="row.remain > 0 ? 'remain-ok' : 'remain-low'" class="mono">
            {{ row.remain }}
          </span>
          <span class="mono" style="color:#5a635b"> / {{ row.capacity }}</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="110" align="center" fixed="right">
        <template #default="{ row }">
          <el-button v-if="row.selected" size="small" type="danger" plain
                     :loading="acting === row.id" @click="doDrop(row)">退选</el-button>
          <el-button v-else size="small" type="primary"
                     :disabled="!roundOpen" :loading="acting === row.id"
                     @click="doSelect(row)">选课</el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>
