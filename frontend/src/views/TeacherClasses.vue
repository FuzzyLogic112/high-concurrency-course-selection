<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api, unwrap } from '../api'

const classes = ref([])
const loading = ref(false)

// 名单按教学班缓存。教师会反复展开收起几个班，每次都重查没必要 ——
// 这是只读数据，选课结束后也不会变。
const rosters = ref({})
const rosterLoading = ref('')
const expanded = ref([])

const totalSelected = computed(() =>
  classes.value.reduce((n, c) => n + (c.selectedCount || 0), 0))
const totalCapacity = computed(() =>
  classes.value.reduce((n, c) => n + (c.capacity || 0), 0))

async function load() {
  loading.value = true
  try {
    classes.value = unwrap(await api.teacherClasses()) || []
  } finally {
    loading.value = false
  }
}

async function loadRoster(classId) {
  if (rosters.value[classId]) return
  rosterLoading.value = classId
  try {
    const d = unwrap(await api.teacherRoster(classId))
    if (d) rosters.value[classId] = d
  } finally {
    rosterLoading.value = ''
  }
}

function onExpand(row, rows) {
  // rows 是展开后的全部行，包含 row 才说明这次是展开而不是收起
  if (rows.some(r => r.id === row.id)) loadRoster(row.id)
}

/** 把名单导成 CSV，教师拿去打考勤表。浏览器端生成，不走后端 */
function exportCsv(row) {
  const list = rosters.value[row.id]
  if (!list || !list.length) {
    ElMessage.warning('名单为空，无可导出内容')
    return
  }
  const head = ['序号', '学号', '姓名', '年级', '班级', '选课时间']
  const lines = [head.join(',')].concat(
    list.map((s, i) => [i + 1, s.studentNo, s.realName, s.grade, s.className, fmtTime(s.selectedAt)]
      .map(v => `"${v ?? ''}"`).join(',')))
  // 加 BOM，否则 Excel 打开中文是乱码
  const blob = new Blob(['﻿' + lines.join('\r\n')], { type: 'text/csv;charset=utf-8' })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = `${row.classNo}_${row.courseName}_选课名单.csv`
  a.click()
  URL.revokeObjectURL(a.href)
}

/** H2 回来的时间戳带微秒（2026-09-21 19:25:57.55082），截到秒就够了。
 *  不在 SQL 里格式化：H2 和 MySQL 的日期函数不一样，放前端反而跨库通用。 */
function fmtTime(s) {
  return s ? String(s).slice(0, 19) : ''
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>我的任课</h2>
        <div class="sub">本学期任教的教学班与选课名单</div>
      </div>
      <el-button @click="load">刷新</el-button>
    </div>

    <el-card shadow="never">
      <template #header>
        <div style="display:flex;align-items:center;justify-content:space-between">
          <b>教学班</b>
          <span v-if="classes.length" style="font-size:13px;color:#888">
            共 {{ classes.length }} 个班 · 已选 {{ totalSelected }} / {{ totalCapacity }} 人
          </span>
        </div>
      </template>

      <el-table :data="classes" v-loading="loading" row-key="id"
                :expand-row-keys="expanded" @expand-change="onExpand"
                empty-text="本学期没有你任教的教学班">
        <el-table-column type="expand">
          <template #default="{ row }">
            <div style="padding:8px 16px 16px 48px">
              <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:10px">
                <b style="font-size:13px">选课名单</b>
                <el-button size="small" :disabled="!(rosters[row.id] || []).length"
                           @click="exportCsv(row)">导出 CSV</el-button>
              </div>
              <el-table :data="rosters[row.id] || []" size="small" border
                        v-loading="rosterLoading === row.id"
                        empty-text="这个班还没有人选">
                <el-table-column type="index" label="#" width="56" align="center" />
                <el-table-column prop="studentNo" label="学号" width="140" />
                <el-table-column prop="realName" label="姓名" width="120" />
                <el-table-column prop="grade" label="年级" width="80" align="center" />
                <el-table-column prop="className" label="班级" width="130" />
                <el-table-column label="选课时间">
                  <template #default="{ row }">{{ fmtTime(row.selectedAt) }}</template>
                </el-table-column>
              </el-table>
            </div>
          </template>
        </el-table-column>

        <el-table-column prop="classNo" label="教学班号" width="130" />
        <el-table-column prop="courseName" label="课程" min-width="150" />
        <el-table-column prop="courseNo" label="课程号" width="110" />
        <el-table-column prop="credit" label="学分" width="80" align="center" />
        <el-table-column prop="timeSlots" label="上课时间" min-width="180" />
        <el-table-column prop="location" label="地点" width="120" />
        <el-table-column label="已选 / 容量" width="130" align="center">
          <template #default="{ row }">
            <el-tag :type="row.selectedCount >= row.capacity ? 'danger' : 'success'"
                    effect="plain" size="small">
              {{ row.selectedCount }} / {{ row.capacity }}
            </el-tag>
          </template>
        </el-table-column>
      </el-table>

      <el-alert type="info" :closable="false" show-icon style="margin-top:14px">
        教师端是只读的，不参与名额扣减。这里的已选人数读自数据库，
        是消息消费落库之后的结果；学生端选课页显示的余量读自 Redis，
        两者在异步落库完成前可能有短暂差异，这正是最终一致性的体现。
      </el-alert>
    </el-card>
  </div>
</template>
