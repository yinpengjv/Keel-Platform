<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'
import { Button, Drawer, Field, Metric, SectionLabel, Select, Tag, TextInput, type Column } from '@/components/ui'
import { ListPage, PermissionButton } from '@/components/business'
import { systemApi, type RuntimeLogRow, type RuntimeLogStats, type StatsRange } from '@/api'
import { useListPage } from '@/composables/list'
import { confirm } from '@/composables/confirm'
import { pushToast, toastError } from '@/composables/toast'

/* ============================================================
   运行日志：请求访问、异常、SQL 与参数的统一视图。
   与「操作日志」的区别：这里记的是技术诊断，不是业务审计。
   最有用的动作是点 traceId —— 把一个请求的访问摘要、慢 SQL、异常串成一条线。
   ============================================================ */

type RuntimeQuery = { level: string; keyword: string; traceId: string; range: string }

const LEVELS = [
  { label: '全部级别', value: '' },
  { label: '错误 ERROR', value: 'ERROR' },
  { label: '警告 WARN', value: 'WARN' },
  { label: '信息 INFO', value: 'INFO' },
  { label: 'SQL / 调试', value: 'DEBUG' },
]

const RANGES = [
  { label: '24 小时', value: '24h' },
  { label: '7 天', value: '7d' },
  { label: '30 天', value: '30d' },
  { label: '不限时间', value: 'all' },
]

const LEVEL_TONE: Record<string, string> = { ERROR: 'danger', WARN: 'danger', INFO: 'info', DEBUG: 'neutral' }

const route = useRoute()
const list = useListPage<RuntimeLogRow, RuntimeQuery>({
  query: {
    level: '',
    keyword: typeof route.query.keyword === 'string' ? route.query.keyword : '',
    traceId: typeof route.query.traceId === 'string' ? route.query.traceId : '',
    range: '24h',
  },
  fetch: params => systemApi.runtimeLogs.list(params) as Promise<any>,
  exporter: params => systemApi.runtimeLogs.export(params),
  exportName: '运行日志.csv',
})

const columns: Column[] = [
  { key: 'createTime', title: '时间', width: 172 },
  { key: 'level', title: '级别', width: 88 },
  { key: 'request', title: '请求 / 摘要', minWidth: 300 },
  { key: 'httpStatus', title: '状态', width: 76, align: 'right' },
  { key: 'cost', title: '耗时', width: 92, align: 'right' },
  { key: 'username', title: '账号', width: 112 },
  { key: 'traceId', title: '链路', width: 124 },
  { key: 'actions', title: '操作', width: 84, className: 'col-actions' },
]

const stats = ref<RuntimeLogStats | null>(null)
const statsLoading = ref(false)

async function loadStats() {
  statsLoading.value = true
  try {
    stats.value = await systemApi.runtimeLogs.stats(list.query.range as StatsRange)
  } catch (error) {
    // 概览失败不阻断列表：日志页本身不该因为统计查询挂了就打不开
  } finally {
    statsLoading.value = false
  }
}
loadStats()

function onSearch() {
  list.search()
  loadStats()
}

function onReset() {
  list.reset()
  loadStats()
}

/* ── 详情：堆栈与 SQL 全文只在抽屉里按需加载 ── */
const detailOpen = ref(false)
const detailLoading = ref(false)
const detail = ref<RuntimeLogRow | null>(null)

async function openDetail(row: RuntimeLogRow) {
  detail.value = row
  detailOpen.value = true
  detailLoading.value = true
  try {
    detail.value = await systemApi.runtimeLogs.detail(row.id)
  } catch (error) {
    toastError(error, '详情加载失败')
  } finally {
    detailLoading.value = false
  }
}

const detailText = computed(() => detail.value?.detail || detail.value?.message || '这条记录没有额外明细')

/** 只看这一条链路：清掉级别与时间限制，否则可能筛掉同一次请求的其它日志 */
function focusTrace() {
  const traceId = detail.value?.traceId
  if (!traceId) return
  list.query.traceId = traceId
  list.query.level = ''
  list.query.range = 'all'
  detailOpen.value = false
  onSearch()
  pushToast(`已筛选链路 ${traceId.slice(0, 8)}`, 'ok')
}

function shortTrace(value?: string | null) {
  return value ? value.slice(0, 8) : '—'
}

/* ── 清理 ── */
async function cleanup() {
  const days = stats.value?.retainDays && stats.value.retainDays > 0 ? stats.value.retainDays : 7
  const ok = await confirm({
    title: '清理运行日志',
    message: `将删除 ${days} 天前的运行日志，近期记录保留。确定继续？`,
    confirmText: '清理',
    danger: true,
  })
  if (!ok) return
  try {
    const removed = await systemApi.runtimeLogs.cleanup(days)
    pushToast(`已清理 ${removed} 条运行日志`, 'ok')
    list.load()
    loadStats()
  } catch (error) {
    toastError(error, '清理失败')
  }
}
</script>

<template>
  <ListPage
    title="运行日志"
    desc="记录接口请求、慢请求、异常堆栈与 SQL 执行情况；按 traceId 可把一次请求的全部日志串起来看。"
    table-title="日志明细"
    :list="list"
    :columns="columns"
    :min-width="1100"
  >
    <template #before>
      <section class="band reveal">
        <div class="band-head">
          <SectionLabel title="概览" />
          <Select v-model="list.query.range" :options="RANGES" style="width: 132px" @update:model-value="onSearch" />
        </div>
        <div class="readout-strip">
          <Metric label="日志总量" :value="stats?.total ?? 0" unit="条" />
          <Metric label="服务端错误" :value="stats?.errors ?? 0" unit="次" :class="{ 'is-alert': stats?.errors }" />
          <Metric label="慢请求" :value="stats?.slow ?? 0" unit="次" />
          <Metric label="队列丢弃" :value="stats?.dropped ?? 0" unit="条" />
          <Metric label="写库失败" :value="stats?.failedBatches ?? 0" unit="批" />
        </div>

        <p v-if="stats && !stats.capturing" class="band-note">
          运行日志采集当前是关闭的（RUNTIME_LOG_ENABLED=false），新请求不会入库。
        </p>
        <p v-else-if="stats && !stats.captureSql" class="band-note">
          当前只采集访问日志与异常。要连 SQL 与参数一起记录，把环境变量 RUNTIME_LOG_CAPTURE_SQL 设为 true 后重启后端。
        </p>

        <div v-if="stats?.topSlow?.length" class="slow-list">
          <span class="slow-title">最慢接口</span>
          <button
            v-for="item in stats.topSlow"
            :key="item.url"
            type="button"
            class="slow-item"
            :title="`${item.url}｜平均 ${item.avgCost}ms｜最慢 ${item.maxCost}ms｜${item.total} 次`"
            @click="list.query.keyword = item.url; onSearch()"
          >
            <span class="slow-url">{{ item.url }}</span>
            <span class="slow-cost tnum">{{ item.maxCost }}ms</span>
          </button>
        </div>
      </section>
    </template>

    <template #actions>
      <PermissionButton perm="monitor:runtime:remove" variant="secondary" icon="trash" danger @click="cleanup">
        清理过期日志
      </PermissionButton>
    </template>

    <template #search>
      <Field label="关键词" wide>
        <TextInput v-model="list.query.keyword" icon="search" placeholder="摘要 / 请求地址 / 来源类" clearable @enter="onSearch" />
      </Field>
      <Field label="级别">
        <Select v-model="list.query.level" :options="LEVELS" />
      </Field>
      <Field label="链路 traceId">
        <TextInput v-model="list.query.traceId" placeholder="完整或留空" clearable @enter="onSearch" />
      </Field>
    </template>

    <template #cell-createTime="{ row }">
      <span class="tnum">{{ row.createTime?.replace('T', ' ').slice(0, 19) }}</span>
    </template>

    <template #cell-level="{ row }">
      <Tag :tone="LEVEL_TONE[row.level] ?? 'neutral'">{{ row.level }}</Tag>
    </template>

    <template #cell-request="{ row }">
      <div class="req-cell">
        <span class="req-main">
          <span v-if="row.requestMethod" class="req-method">{{ row.requestMethod }}</span>
          <span class="req-url">{{ row.requestUrl || row.logger || '—' }}</span>
        </span>
        <span class="req-msg">{{ row.message }}</span>
      </div>
    </template>

    <template #cell-httpStatus="{ row }">
      <span :class="['tnum', { 'is-bad': (row.httpStatus ?? 0) >= 500 }]">{{ row.httpStatus ?? '—' }}</span>
    </template>

    <template #cell-cost="{ row }">
      <span :class="['tnum', { 'is-slow': (row.cost ?? 0) >= 1000 }]">{{ row.cost == null ? '—' : `${row.cost} ms` }}</span>
    </template>

    <template #cell-username="{ row }">{{ row.username || '—' }}</template>

    <template #cell-traceId="{ row }">
      <button v-if="row.traceId" type="button" class="trace-btn tnum" :title="row.traceId" @click="list.query.traceId = row.traceId!; onSearch()">
        {{ shortTrace(row.traceId) }}
      </button>
      <span v-else class="faint">—</span>
    </template>

    <template #cell-actions="{ row }">
      <Button variant="text" size="sm" @click="openDetail(row)">详情</Button>
    </template>
  </ListPage>

  <Drawer :open="detailOpen" title="日志详情" :sub="detail?.level" :width="640" @close="detailOpen = false">
    <div v-if="detail" class="detail-body">
      <dl class="detail-grid">
        <dt>时间</dt><dd class="tnum">{{ detail.createTime?.replace('T', ' ').slice(0, 23) }}</dd>
        <dt>级别</dt><dd><Tag :tone="LEVEL_TONE[detail.level] ?? 'neutral'">{{ detail.level }}</Tag></dd>
        <dt>来源</dt><dd class="mono">{{ detail.logger || '—' }}</dd>
        <dt>请求</dt><dd class="mono">{{ detail.requestMethod || '' }} {{ detail.requestUrl || '—' }}</dd>
        <dt>状态 / 耗时</dt><dd class="tnum">{{ detail.httpStatus ?? '—' }} / {{ detail.cost == null ? '—' : detail.cost + ' ms' }}</dd>
        <dt>账号 / IP</dt><dd class="mono">{{ detail.username || '—' }} · {{ detail.ip || '—' }}</dd>
        <dt>链路</dt>
        <dd>
          <button v-if="detail.traceId" type="button" class="trace-btn mono" @click="focusTrace">{{ detail.traceId }}</button>
          <span v-else>—</span>
        </dd>
      </dl>

      <div class="detail-actions">
        <Button v-if="detail.traceId" size="sm" icon="search" @click="focusTrace">只看这条链路</Button>
      </div>

      <SectionLabel title="明细" />
      <pre class="detail-pre">{{ detailLoading ? '加载中…' : detailText }}</pre>
    </div>
  </Drawer>
</template>

<style scoped>
.band-note {
  margin: 8px 0 0;
  color: var(--ink-muted);
  font-size: 12px;
}

.slow-list {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-top: 12px;
}

.slow-title {
  color: var(--ink-muted);
  font-size: 11px;
  letter-spacing: 0.04em;
}

.slow-item {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 4px 10px;
  border: 1px solid var(--line);
  border-radius: 999px;
  background: transparent;
  color: var(--ink);
  font-size: 11px;
  cursor: pointer;
}

.slow-item:hover {
  border-color: var(--e-accent);
}

.slow-url {
  max-width: 260px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.slow-cost {
  color: var(--ink-muted);
}

.req-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.req-main {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.req-method {
  flex: none;
  color: var(--ink-muted);
  font-size: 11px;
}

.req-url {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-family: var(--font-mono);
  font-size: 11.5px;
}

.req-msg {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--ink-muted);
  font-size: 11.5px;
}

.is-bad,
.is-slow {
  color: var(--red-text);
}

.trace-btn {
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--e-accent);
  font-family: var(--font-mono);
  font-size: 11.5px;
  cursor: pointer;
}

.trace-btn:hover {
  text-decoration: underline;
}

.detail-body {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.detail-grid {
  display: grid;
  grid-template-columns: 92px 1fr;
  gap: 8px 12px;
  margin: 0;
  font-size: 12.5px;
}

.detail-grid dt {
  color: var(--ink-muted);
}

.detail-grid dd {
  margin: 0;
  word-break: break-all;
}

.detail-actions {
  display: flex;
  gap: 8px;
}

.detail-pre {
  max-height: 46vh;
  margin: 0;
  padding: 12px;
  overflow: auto;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  background: var(--groove-bg);
  font-family: var(--font-mono);
  font-size: 11.5px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
