<script setup>
import { computed, onMounted, ref } from 'vue'
import { api, unwrap } from '../api'

const items = ref([])
const loading = ref(false)

const WEEK = ['一', '二', '三', '四', '五', '六', '日']
const PERIODS = 12

async function load() {
  loading.value = true
  try {
    items.value = unwrap(await api.myTimetable()) || []
  } finally {
    loading.value = false
  }
}

const totalCredit = computed(() =>
  items.value.reduce((s, x) => s + Number(x.credit || 0), 0))

/**
 * 把「周一 1-2节，周三 4-5节」还原成网格坐标。
 * 后端存的是 84 位时间位图，这里只是把可读文本再解析回来用于展示。
 */
function parseSlots(text) {
  const out = []
  const re = /周([一二三四五六日])\s*(\d+)-(\d+)节/g
  let m
  while ((m = re.exec(text || '')) !== null) {
    const day = WEEK.indexOf(m[1]) + 1
    if (day > 0) out.push({ day, start: Number(m[2]), end: Number(m[3]) })
  }
  return out
}

/** grid[day][period] = 课程 */
const grid = computed(() => {
  const g = {}
  for (const it of items.value) {
    for (const s of parseSlots(it.timeSlots)) {
      for (let p = s.start; p <= s.end; p++) {
        g[`${s.day}-${p}`] = it
      }
    }
  }
  return g
})

const busyCount = computed(() => Object.keys(grid.value).length)

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>我的课表</h2>
        <div class="sub">
          已选 {{ items.length }} 门 · 合计
          <span class="mono">{{ totalCredit }}</span> 学分 ·
          占用 <span class="mono">{{ busyCount }}</span> / 84 个时间槽
        </div>
      </div>
      <el-button :loading="loading" @click="load">刷新</el-button>
    </div>

    <el-empty v-if="!loading && items.length === 0" description="还没有选课" />

    <template v-else>
      <el-card shadow="never" style="margin-bottom:18px">
        <div class="tt-grid">
          <div class="tt-cell tt-head"></div>
          <div v-for="w in WEEK" :key="w" class="tt-cell tt-head">周{{ w }}</div>

          <template v-for="p in PERIODS" :key="p">
            <div class="tt-cell tt-period">{{ p }}</div>
            <div v-for="d in 7" :key="`${d}-${p}`"
                 class="tt-cell" :class="{ 'tt-busy': grid[`${d}-${p}`] }">
              <template v-if="grid[`${d}-${p}`]">
                <div class="name">{{ grid[`${d}-${p}`].courseName }}</div>
                <div class="meta">{{ grid[`${d}-${p}`].location }}</div>
              </template>
            </div>
          </template>
        </div>
        <div style="color:#5a635b;font-size:12px;margin-top:10px">
          一周 7 天 × 每天 12 节 = 84 个时间槽。系统内部用 84 位位图表示这张表，
          冲突检测就是两张位图做一次按位与。
        </div>
      </el-card>

      <el-table :data="items" border stripe>
        <el-table-column prop="courseName" label="课程" min-width="150" />
        <el-table-column prop="credit" label="学分" width="80" align="center" />
        <el-table-column prop="teacherName" label="教师" width="100" />
        <el-table-column prop="timeSlots" label="上课时间" min-width="200" />
        <el-table-column prop="location" label="地点" width="120" />
      </el-table>
    </template>
  </div>
</template>
