<script setup>
import { onMounted, onUnmounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, unwrap } from '../api'

const rounds = ref([])
const stats = ref({})
const reconcile = ref({ checked: 0, mismatched: 0, items: [] })
const strategy = ref('redis_mq')
const experimentClassId = ref(1)
const busy = ref('')
let timer = null

const STRATEGIES = [
  { value: 'direct', label: 'direct 直接扣减', desc: '无并发保护，会超选。论文的反面基线' },
  { value: 'pessimistic', label: 'pessimistic 悲观锁', desc: 'SELECT FOR UPDATE，正确但串行化' },
  { value: 'optimistic', label: 'optimistic 乐观锁', desc: '版本号 CAS，正确但高并发下重试率高' },
  { value: 'redis_mq', label: 'redis_mq 本文方案', desc: 'Lua 原子预扣 + 消息队列异步落库' }
]

async function loadAll() {
  const [r, s, rc] = await Promise.all([api.rounds(), api.stats(), api.reconcile(false)])
  rounds.value = unwrap(r) || []
  const st = unwrap(s)
  if (st) {
    stats.value = st
    // 服务端是策略的唯一真相，每次刷新都同步回来。
    // 早先这里为了「别和用户的点击打架」跳过了同步，结果切换后单选按钮不跟着动。
    strategy.value = st.deductStrategy
  }
  reconcile.value = unwrap(rc) || { checked: 0, mismatched: 0, items: [] }
}

async function warmup(id) {
  busy.value = 'warmup'
  try {
    const d = unwrap(await api.warmup(id))
    if (d) ElMessage.success(`预热完成：${d.classCount} 个教学班，耗时 ${d.costMs}ms`)
    await loadAll()
  } finally { busy.value = '' }
}

async function switchStrategy(name) {
  busy.value = 'strategy'
  try {
    const d = unwrap(await api.switchStrategy(name))
    if (d) ElMessage.success(`扣减策略：${d.from} → ${d.to}`)
    await loadAll()
  } finally { busy.value = '' }
}

async function doReconcile(autoFix) {
  busy.value = 'reconcile'
  try {
    const d = unwrap(await api.reconcile(autoFix))
    if (d) {
      reconcile.value = d
      ElMessage[d.mismatched === 0 ? 'success' : 'warning'](
        `检查 ${d.checked} 个教学班，偏差 ${d.mismatched} 个`)
    }
  } finally { busy.value = '' }
}

async function resetExperiment() {
  try {
    await ElMessageBox.confirm(
      `会清空 ${experimentClassId.value} 号教学班的全部选课记录、计数归零、缓存按容量重置。仅用于压测。`,
      '复位教学班', { type: 'warning' })
  } catch { return }
  busy.value = 'reset'
  try {
    const res = await api.resetExperiment(experimentClassId.value)
    if (res.code === 0) {
      ElMessage.success('已复位，可以开始下一轮压测')
      await loadAll()
    }
  } finally { busy.value = '' }
}

onMounted(() => {
  loadAll()
  timer = setInterval(() => loadAll(), 4000)
})
onUnmounted(() => clearInterval(timer))
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>教务后台</h2>
        <div class="sub">轮次管理、名额预热、实验控制与一致性对账</div>
      </div>
      <el-button @click="loadAll()">刷新</el-button>
    </div>

    <el-row :gutter="16">
      <el-col :span="12">
        <el-card shadow="never" style="margin-bottom:16px">
          <template #header><b>选课轮次</b></template>
          <el-table :data="rounds" size="small" border>
            <el-table-column prop="name" label="轮次" min-width="150" />
            <el-table-column label="状态" width="90" align="center">
              <template #default="{ row }">
                <el-tag size="small" :type="row.status === 1 ? 'success' : 'info'">
                  {{ row.status === 1 ? '进行中' : row.status === 0 ? '未开始' : '已结束' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="预热" width="80" align="center">
              <template #default="{ row }">
                <el-tag size="small" :type="row.warmedUp === 1 ? 'success' : 'danger'" effect="plain">
                  {{ row.warmedUp === 1 ? '已预热' : '未预热' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="90" align="center">
              <template #default="{ row }">
                <el-button size="small" type="primary" plain
                           :loading="busy === 'warmup'" @click="warmup(row.id)">预热</el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-alert type="warning" :closable="false" style="margin-top:10px">
            选课开放前必须预热。未预热时 Lua 脚本返回 -2，
            请求全部降级走数据库，吞吐会塌掉。
          </el-alert>
        </el-card>
      </el-col>

      <el-col :span="12">
        <el-card shadow="never" style="margin-bottom:16px">
          <template #header><b>运行时指标</b></template>
          <el-descriptions :column="2" size="small" border>
            <el-descriptions-item label="扣减策略">
              <span class="mono">{{ stats.deductStrategy }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="消息通道">
              <span class="mono">{{ stats.mqProvider }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="队列深度">
              <span class="mono">{{ stats.mqQueueDepth }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="限流 QPS">
              <span class="mono">{{ stats.rateLimitQps }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="已消费">
              <span class="mono">{{ stats.consumerHandled }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="重复消息">
              <span class="mono">{{ stats.consumerDuplicated }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="CAS 重试次数" :span="2">
              <span class="mono">{{ stats.optimisticRetries }}</span>
              <span style="color:#5a635b;font-size:12px"> （仅 optimistic 策略会增长）</span>
            </el-descriptions-item>
          </el-descriptions>
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="never" style="margin-bottom:16px">
      <template #header><b>对比实验控制</b></template>
      <el-radio-group v-model="strategy" @change="switchStrategy">
        <el-radio-button v-for="s in STRATEGIES" :key="s.value" :value="s.value">
          {{ s.label }}
        </el-radio-button>
      </el-radio-group>
      <div style="color:#5a635b;font-size:12px;margin-top:8px">
        {{ STRATEGIES.find(s => s.value === strategy)?.desc }}
      </div>
      <el-divider />
      <div style="display:flex;align-items:center;gap:10px;flex-wrap:wrap">
        <span style="font-size:13px">复位教学班</span>
        <el-input-number v-model="experimentClassId" :min="1" size="small" style="width:120px" />
        <el-button size="small" type="danger" plain :loading="busy === 'reset'"
                   @click="resetExperiment">复位</el-button>
        <span style="color:#5a635b;font-size:12px">
          四种方案共用同一套业务代码，切换不重启服务——JVM 预热状态与连接池保持一致，对比条件才干净
        </span>
      </div>
    </el-card>

    <el-card shadow="never">
      <template #header>
        <div style="display:flex;align-items:center;justify-content:space-between">
          <b>缓存与数据库对账</b>
          <div style="display:flex;gap:8px;align-items:center">
            <el-tag :type="reconcile.mismatched === 0 ? 'success' : 'danger'" size="small">
              检查 {{ reconcile.checked }} 个，偏差 {{ reconcile.mismatched }} 个
            </el-tag>
            <el-button size="small" :loading="busy === 'reconcile'"
                       @click="doReconcile(false)">重新对账</el-button>
            <el-button size="small" type="warning" plain :loading="busy === 'reconcile'"
                       @click="doReconcile(true)">对账并修复</el-button>
          </div>
        </div>
      </template>
      <el-table :data="reconcile.items" size="small" border max-height="320">
        <el-table-column prop="classNo" label="教学班号" width="130" class-name="mono" />
        <el-table-column prop="capacity" label="容量" width="90" align="center" />
        <el-table-column prop="redisRemain" label="Redis 余量" width="120" align="center" class-name="mono" />
        <el-table-column prop="dbSelected" label="库中选课数" width="120" align="center" class-name="mono" />
        <el-table-column label="偏差" width="100" align="center">
          <template #default="{ row }">
            <span :class="row.diff === 0 ? 'remain-ok' : 'remain-low'" class="mono">
              {{ row.diff }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="说明" min-width="200">
          <template #default="{ row }">
            <span v-if="row.diff === 0" style="color:#5a635b">一致</span>
            <span v-else style="color:#a63527">
              Redis 余量应为 容量 − 库中选课数 = {{ row.capacity - row.dbSelected }}
            </span>
          </template>
        </el-table-column>
      </el-table>
      <el-alert type="info" :closable="false" show-icon style="margin-top:10px">
        对账只对 <span class="mono">redis_mq</span> 策略有意义——
        只有它以 Redis 作为名额真相。另外三种直接操作数据库、不碰 Redis，
        跑完后余量会停在预热时的初始值，那不是偏差。
      </el-alert>
    </el-card>
  </div>
</template>
