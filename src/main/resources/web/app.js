import { createApp, ref, reactive, onMounted, onUnmounted, nextTick } from 'https://unpkg.com/vue@3/dist/vue.esm-browser.prod.js'

// ── Constants ──────────────────────────────────────────────────────────────────
const BEHAVIOR_TYPES = [
  { value: 'IMMEDIATE_FULL_FILL',   label: 'Immediate Full Fill' },
  { value: 'PARTIAL_FILL',          label: 'Partial Fill' },
  { value: 'DELAYED_FILL',          label: 'Delayed Fill' },
  { value: 'REJECT',                label: 'Reject All' },
  { value: 'PARTIAL_THEN_CANCEL',   label: 'Partial Fill then Cancel' },
  { value: 'PRICE_IMPROVEMENT',     label: 'Price Improvement' },
  { value: 'FILL_AT_ARRIVAL_PRICE', label: 'Fill at Arrival Price' },
  { value: 'RANDOM_FILL',           label: 'Random Fill' },
  { value: 'NO_FILL_IOC_CANCEL',    label: 'No Fill / IOC Cancel' },
]

const REJECT_REASONS = [
  'SIMULATOR_REJECT','UNKNOWN_SYMBOL','HALTED','INVALID_PRICE','NOT_AUTHORIZED','STALE_ORDER'
]

const METRICS_REFRESH_OPTIONS = [1, 5, 10, 30, 60]

// ── Vue App ────────────────────────────────────────────────────────────────────
createApp({
  template: `
  <div class="min-h-screen bg-dark-900">
    <!-- Header -->
    <header class="bg-dark-800 border-b border-dark-600 px-6 py-3 flex items-center justify-between">
      <div class="flex items-center gap-3">
        <div class="w-8 h-8 rounded-lg bg-brand-600 flex items-center justify-center text-xs font-bold">FX</div>
        <h1 class="text-lg font-semibold text-white">LLExSimulator</h1>
        <span class="text-xs text-slate-400 font-mono">FIX Exchange Simulator</span>
      </div>
      <div class="flex items-center gap-4 flex-wrap justify-end">
        <a href="/reports"
           class="text-xs font-semibold text-slate-300 hover:text-white border border-dark-600 hover:border-brand-500 rounded-full px-3 py-1.5 transition-colors">
          Reports
        </a>
        <span :class="wsConnected ? 'text-brand-500' : 'text-red-400'" class="text-xs font-mono flex items-center gap-1">
          <span :class="wsConnected ? 'bg-brand-500' : 'bg-red-400'" class="w-2 h-2 rounded-full inline-block"></span>
          {{ wsConnected ? 'WS LIVE' : 'WS RETRYING' }}
        </span>
        <span :class="loggedOnSessionCount > 0 ? 'text-brand-500' : 'text-yellow-400'" class="text-xs font-mono flex items-center gap-1">
          <span :class="loggedOnSessionCount > 0 ? 'bg-brand-500' : 'bg-yellow-400'" class="w-2 h-2 rounded-full inline-block"></span>
          {{ loggedOnSessionCount > 0 ? 'FIX CONNECTED' : 'FIX IDLE' }}
        </span>
        <span class="text-xs text-slate-400 font-mono">FIX Sessions: {{ loggedOnSessionCount }}/{{ sessions.length }}</span>
      </div>
    </header>

    <main class="p-6 grid grid-cols-12 gap-6">

      <!-- ── Metrics Toolbar ─────────────────────────────────────────────── -->
      <div class="col-span-12 bg-dark-800 rounded-xl border border-dark-600 px-5 py-4 flex flex-col md:flex-row md:items-center md:justify-between gap-4">
        <div>
          <h2 class="font-semibold text-white text-sm">Metrics Dashboard</h2>
          <p class="text-xs text-slate-400 mt-1">Control how often throughput, latency, fill rate, reject rate, and the p99 chart refresh on screen.</p>
        </div>
        <div class="flex flex-col sm:flex-row sm:items-center gap-3 sm:gap-4">
          <div class="flex items-center gap-3">
            <label class="text-xs text-slate-400 whitespace-nowrap">Refresh every</label>
            <select v-model.number="selectedRefreshSeconds"
                    @change="updateMetricsRefreshInterval"
                    class="bg-dark-700 border border-dark-600 rounded-lg px-3 py-2 text-sm text-white focus:outline-none focus:border-brand-500">
              <option v-for="seconds in metricsRefreshOptions" :key="seconds" :value="seconds">
                {{ seconds }} second{{ seconds === 1 ? '' : 's' }}
              </option>
            </select>
            <span class="text-xs text-brand-500 font-mono whitespace-nowrap">applied every {{ selectedRefreshSeconds }}s</span>
          </div>
          <button @click="resetMetrics"
                  :disabled="isResettingMetrics"
                  class="px-3 py-2 rounded-lg text-xs font-semibold transition-colors border border-dark-500"
                  :class="isResettingMetrics ? 'bg-dark-700 text-slate-500 cursor-not-allowed' : 'bg-dark-700 hover:bg-dark-600 text-white'">
            {{ isResettingMetrics ? 'Resetting…' : 'Reset Metrics' }}
          </button>
        </div>
      </div>

      <!-- ── Stats Cards Row ─────────────────────────────────────────────── -->
      <div class="col-span-12 grid grid-cols-2 md:grid-cols-4 gap-4">
        <div class="bg-dark-800 rounded-xl p-4 border border-dark-600">
          <p class="text-xs text-slate-400 uppercase tracking-wider mb-1">Throughput</p>
          <p class="text-2xl font-bold font-mono text-brand-500">{{ metrics.throughputPerSec.toLocaleString() }}</p>
          <p class="text-xs text-slate-500">orders/sec</p>
        </div>
        <div class="bg-dark-800 rounded-xl p-4 border border-dark-600">
          <p class="text-xs text-slate-400 uppercase tracking-wider mb-1">p80 Latency</p>
          <p class="text-2xl font-bold font-mono text-brand-500">{{ metrics.p80Us.toLocaleString() }}</p>
          <p class="text-xs text-slate-500">µs · rolling window</p>
        </div>
        <div class="bg-dark-800 rounded-xl p-4 border border-dark-600">
          <p class="text-xs text-slate-400 uppercase tracking-wider mb-1">p90 Latency</p>
          <p class="text-2xl font-bold font-mono text-yellow-400">{{ metrics.p90Us.toLocaleString() }}</p>
          <p class="text-xs text-slate-500">µs · rolling window</p>
        </div>
        <div class="bg-dark-800 rounded-xl p-4 border border-dark-600">
          <p class="text-xs text-slate-400 uppercase tracking-wider mb-1">p99 Latency</p>
          <p class="text-2xl font-bold font-mono" :class="metrics.p99Us < 500 ? 'text-yellow-400' : 'text-red-400'">{{ metrics.p99Us.toLocaleString() }}</p>
          <p class="text-xs text-slate-500">µs · rolling window</p>
        </div>
      </div>

      <!-- ── Order Count Cards Row ────────────────────────────────────────── -->
      <div class="col-span-12 bg-dark-800 rounded-xl border border-dark-600 px-5 py-4">
        <p class="text-xs font-semibold text-slate-300 uppercase tracking-wider mb-3">Order Counts — Simulator Totals</p>
        <div class="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-5 gap-3">
          <div class="bg-dark-700 rounded-lg px-4 py-3 border border-dark-600">
            <p class="text-xs text-slate-400 mb-1">Received</p>
            <p class="text-xl font-bold font-mono text-white">{{ metrics.orders.toLocaleString() }}</p>
          </div>
          <div class="bg-dark-700 rounded-lg px-4 py-3 border border-dark-600">
            <p class="text-xs text-slate-400 mb-1">Acked (Exec Reports)</p>
            <p class="text-xl font-bold font-mono text-brand-400">{{ metrics.execReports.toLocaleString() }}</p>
          </div>
          <div class="bg-dark-700 rounded-lg px-4 py-3 border border-dark-600">
            <p class="text-xs text-slate-400 mb-1">Filled</p>
            <p class="text-xl font-bold font-mono text-brand-500">{{ metrics.fills.toLocaleString() }}</p>
          </div>
          <div class="bg-dark-700 rounded-lg px-4 py-3 border border-dark-600">
            <p class="text-xs text-slate-400 mb-1">Rejected</p>
            <p class="text-xl font-bold font-mono text-red-400">{{ metrics.rejects.toLocaleString() }}</p>
          </div>
          <div class="bg-dark-700 rounded-lg px-4 py-3 border border-dark-600">
            <p class="text-xs text-slate-400 mb-1">Cancelled</p>
            <p class="text-xl font-bold font-mono text-yellow-400">{{ metrics.cancels.toLocaleString() }}</p>
          </div>
        </div>
      </div>

      <!-- ── Left Column: Fill Profile Config + Sessions ───────────────── -->
      <div class="col-span-12 lg:col-span-4 flex flex-col gap-6">

        <!-- Fill Profile Panel -->
        <div class="bg-dark-800 rounded-xl border border-dark-600 overflow-hidden">
          <div class="px-5 py-3 border-b border-dark-600 flex items-center justify-between">
            <h2 class="font-semibold text-white text-sm">Fill Behavior</h2>
            <span class="text-xs text-brand-500 font-mono bg-brand-900 px-2 py-0.5 rounded">{{ activeProfile }}</span>
          </div>
          <div class="p-5 flex flex-col gap-4">
            <!-- Profile selector -->
            <div>
              <label class="text-xs text-slate-400 mb-1 block">Saved Profiles</label>
              <div class="flex gap-2">
                <select v-model="selectedProfile" class="flex-1 bg-dark-700 border border-dark-600 rounded-lg px-3 py-2 text-sm text-white focus:outline-none focus:border-brand-500">
                  <option v-for="p in profiles" :key="p.name" :value="p.name">{{ p.name }}</option>
                </select>
                <button @click="activateProfile" class="px-3 py-2 bg-brand-600 hover:bg-brand-700 rounded-lg text-xs font-semibold transition-colors">Activate</button>
              </div>
            </div>

            <!-- New Profile Form -->
            <div class="border-t border-dark-600 pt-4">
              <p class="text-xs font-semibold text-slate-300 mb-3">Create / Update Profile</p>
              <div class="grid grid-cols-2 gap-3">
                <div class="col-span-2">
                  <label class="text-xs text-slate-400 mb-1 block">Name</label>
                  <input v-model="form.name" class="w-full bg-dark-700 border border-dark-600 rounded-lg px-3 py-2 text-sm text-white focus:outline-none focus:border-brand-500" placeholder="my-profile"/>
                </div>
                <div class="col-span-2">
                  <label class="text-xs text-slate-400 mb-1 block">Behavior Type</label>
                  <select v-model="form.behaviorType" class="w-full bg-dark-700 border border-dark-600 rounded-lg px-3 py-2 text-sm text-white focus:outline-none focus:border-brand-500">
                    <option v-for="b in behaviorTypes" :key="b.value" :value="b.value">{{ b.label }}</option>
                  </select>
                </div>
                <div v-if="showFillPct">
                  <label class="text-xs text-slate-400 mb-1 block">Fill % (bps)</label>
                  <input v-model.number="form.fillPctBps" type="number" min="0" max="10000" class="w-full bg-dark-700 border border-dark-600 rounded-lg px-3 py-2 text-sm text-white focus:outline-none focus:border-brand-500"/>
                </div>
                <div v-if="showFillPct">
                  <label class="text-xs text-slate-400 mb-1 block">Partial Legs</label>
                  <input v-model.number="form.numPartialFills" type="number" min="1" max="10" class="w-full bg-dark-700 border border-dark-600 rounded-lg px-3 py-2 text-sm text-white focus:outline-none focus:border-brand-500"/>
                </div>
                <div v-if="showDelay" class="col-span-2">
                  <label class="text-xs text-slate-400 mb-1 block">Delay (ms)</label>
                  <input v-model.number="form.delayMs" type="number" min="0" class="w-full bg-dark-700 border border-dark-600 rounded-lg px-3 py-2 text-sm text-white focus:outline-none focus:border-brand-500"/>
                </div>
                <div v-if="showReject" class="col-span-2">
                  <label class="text-xs text-slate-400 mb-1 block">Reject Reason</label>
                  <select v-model="form.rejectReason" class="w-full bg-dark-700 border border-dark-600 rounded-lg px-3 py-2 text-sm text-white focus:outline-none focus:border-brand-500">
                    <option v-for="r in rejectReasons" :key="r" :value="r">{{ r }}</option>
                  </select>
                </div>
                <div v-if="showRandom">
                  <label class="text-xs text-slate-400 mb-1 block">Min Fill %</label>
                  <input v-model.number="form.randomMinQtyPct" type="number" min="0" max="100" class="w-full bg-dark-700 border border-dark-600 rounded-lg px-3 py-2 text-sm text-white focus:outline-none focus:border-brand-500"/>
                </div>
                <div v-if="showRandom">
                  <label class="text-xs text-slate-400 mb-1 block">Max Fill %</label>
                  <input v-model.number="form.randomMaxQtyPct" type="number" min="0" max="100" class="w-full bg-dark-700 border border-dark-600 rounded-lg px-3 py-2 text-sm text-white focus:outline-none focus:border-brand-500"/>
                </div>
                <div v-if="showRandom">
                  <label class="text-xs text-slate-400 mb-1 block">Min Delay (ms)</label>
                  <input v-model.number="form.randomMinDelayMs" type="number" min="0" class="w-full bg-dark-700 border border-dark-600 rounded-lg px-3 py-2 text-sm text-white focus:outline-none focus:border-brand-500"/>
                </div>
                <div v-if="showRandom">
                  <label class="text-xs text-slate-400 mb-1 block">Max Delay (ms)</label>
                  <input v-model.number="form.randomMaxDelayMs" type="number" min="0" class="w-full bg-dark-700 border border-dark-600 rounded-lg px-3 py-2 text-sm text-white focus:outline-none focus:border-brand-500"/>
                </div>
                <div v-if="showPriceImprovement" class="col-span-2">
                  <label class="text-xs text-slate-400 mb-1 block">Price Improvement (bps)</label>
                  <input v-model.number="form.priceImprovementBps" type="number" min="0" class="w-full bg-dark-700 border border-dark-600 rounded-lg px-3 py-2 text-sm text-white focus:outline-none focus:border-brand-500"/>
                </div>
              </div>
              <button @click="saveAndActivate" class="mt-4 w-full py-2 bg-brand-600 hover:bg-brand-700 rounded-lg text-sm font-semibold transition-colors">
                Save &amp; Activate
              </button>
            </div>
          </div>
        </div>

        <!-- FIX Sessions -->
        <div class="bg-dark-800 rounded-xl border border-dark-600 overflow-hidden">
          <div class="px-5 py-3 border-b border-dark-600">
            <h2 class="font-semibold text-white text-sm">FIX Sessions</h2>
          </div>
          <div class="p-2">
            <div v-if="sessions.length === 0" class="px-3 py-4 text-xs text-slate-500 text-center">No active sessions</div>
            <div v-for="s in sessions" :key="s.sessionId"
                 class="flex items-center justify-between px-3 py-2 rounded-lg hover:bg-dark-700 transition-colors">
              <div>
                <p class="text-xs font-mono text-white">{{ s.senderCompId }} → {{ s.targetCompId }}</p>
                <p class="text-xs text-slate-400">{{ s.beginString }} | {{ s.loggedOn ? 'LOGGED ON' : 'OFFLINE' }}</p>
              </div>
              <button @click="disconnectSession(s.sessionId)"
                      class="text-xs text-red-400 hover:text-red-300 px-2 py-1 rounded hover:bg-dark-600 transition-colors">
                Disconnect
              </button>
            </div>
          </div>
        </div>
      </div>

      <!-- ── Right Column: Charts + Order Flow ─────────────────────────── -->
      <div class="col-span-12 lg:col-span-8 flex flex-col gap-6">

        <!-- Latency Chart -->
        <div class="bg-dark-800 rounded-xl border border-dark-600 p-5">
          <div class="flex items-center justify-between mb-4">
            <div>
              <h2 class="font-semibold text-white text-sm">Latency Percentiles — History</h2>
              <p class="text-xs text-slate-500 mt-1">p80 · p90 · p99 · rolling window · last 60 refreshes</p>
            </div>
            <span class="text-xs text-slate-400 font-mono">{{ selectedRefreshSeconds }}s cadence</span>
          </div>
          <canvas ref="latencyChart" height="80"></canvas>
        </div>

        <!-- Order Flow Table -->
        <div class="bg-dark-800 rounded-xl border border-dark-600 overflow-hidden">
          <div class="px-5 py-3 border-b border-dark-600 flex items-center justify-between">
            <h2 class="font-semibold text-white text-sm">Order Flow</h2>
            <span class="text-xs text-slate-400 font-mono">{{ orders.length }} recent</span>
          </div>
          <div class="overflow-x-auto">
            <table class="w-full text-xs">
              <thead>
                <tr class="text-slate-400 border-b border-dark-600">
                  <th class="text-left px-4 py-2 font-medium">Time</th>
                  <th class="text-left px-4 py-2 font-medium">Symbol</th>
                  <th class="text-left px-4 py-2 font-medium">Side</th>
                  <th class="text-right px-4 py-2 font-medium">Qty</th>
                  <th class="text-right px-4 py-2 font-medium">Price</th>
                  <th class="text-left px-4 py-2 font-medium">ExecType</th>
                  <th class="text-left px-4 py-2 font-medium">Status</th>
                  <th class="text-left px-4 py-2 font-medium">Behavior</th>
                  <th class="text-right px-4 py-2 font-medium">Latency µs</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="o in orders" :key="o.id"
                    :class="[o.flash, 'border-b border-dark-700 hover:bg-dark-700 transition-colors font-mono']">
                  <td class="px-4 py-1.5 text-slate-400">{{ o.time }}</td>
                  <td class="px-4 py-1.5 text-white font-semibold">{{ o.symbol }}</td>
                  <td class="px-4 py-1.5" :class="o.side === 'BUY' ? 'text-brand-500' : 'text-red-400'">{{ o.side }}</td>
                  <td class="px-4 py-1.5 text-right text-white">{{ o.qty }}</td>
                  <td class="px-4 py-1.5 text-right text-slate-300">{{ o.price }}</td>
                  <td class="px-4 py-1.5">
                    <span :class="execTypeBadge(o.execType)" class="px-1.5 py-0.5 rounded text-xs">{{ o.execType }}</span>
                  </td>
                  <td class="px-4 py-1.5 text-slate-300">{{ o.status }}</td>
                  <td class="px-4 py-1.5 text-slate-400">{{ o.behavior }}</td>
                  <td class="px-4 py-1.5 text-right" :class="o.latencyUs < 100 ? 'text-brand-500' : o.latencyUs < 500 ? 'text-yellow-400' : 'text-red-400'">{{ o.latencyUs }}</td>
                </tr>
                <tr v-if="orders.length === 0">
                  <td colspan="9" class="px-4 py-8 text-center text-slate-500">Waiting for orders…</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      </div>

    </main>
  </div>
  `,

  setup() {
    // ── Reactive state ────────────────────────────────────────────────────────
    const wsConnected    = ref(false)
    const activeProfile  = ref('–')
    const profiles       = ref([])
    const selectedProfile = ref('')
    const sessions       = ref([])
    const orders         = ref([])

    const metrics = reactive({
      throughputPerSec: 0, p80Us: 0, p90Us: 0, p99Us: 0,
      fills: 0, rejects: 0, cancels: 0, execReports: 0, orders: 0
    })

    const selectedRefreshSeconds = ref(1)
    const metricsRefreshOptions = METRICS_REFRESH_OPTIONS
    const isResettingMetrics = ref(false)

    const latencyChart = ref(null)
    let   chart        = null
    const latencyHistory = { labels: [], p80: [], p90: [], p99: [] }
    let metricsRefreshTimer = null
    let pollTimer = null
    let latestMetricsSnapshot = null
    let metricsFetchInFlight = false

    // ── Form state ────────────────────────────────────────────────────────────
    const form = reactive({
      name: '', description: '', behaviorType: 'IMMEDIATE_FULL_FILL',
      fillPctBps: 10000, numPartialFills: 1, delayMs: 0,
      rejectReason: 'SIMULATOR_REJECT', randomMinQtyPct: 50, randomMaxQtyPct: 100,
      randomMinDelayMs: 0, randomMaxDelayMs: 5, priceImprovementBps: 1,
    })

    const behaviorTypes  = BEHAVIOR_TYPES
    const rejectReasons  = REJECT_REASONS
    const loggedOnSessionCount = computed(() => sessions.value.filter(s => s.loggedOn).length)

    const showFillPct         = computed(() => ['PARTIAL_FILL','PARTIAL_THEN_CANCEL'].includes(form.behaviorType))
    const showDelay           = computed(() => form.behaviorType === 'DELAYED_FILL')
    const showReject          = computed(() => form.behaviorType === 'REJECT')
    const showRandom          = computed(() => form.behaviorType === 'RANDOM_FILL')
    const showPriceImprovement= computed(() => form.behaviorType === 'PRICE_IMPROVEMENT')

    // Need to import computed
    function computed(fn) {
      // Simple reactive computed using a getter reference
      return { get value() { return fn() } }
    }

    // ── WebSocket ─────────────────────────────────────────────────────────────
    let ws, reconnectTimer
    function connectWs() {
      const wsScheme = location.protocol === 'https:' ? 'wss' : 'ws'
      ws = new WebSocket(`${wsScheme}://${location.host}/ws`)
      ws.onopen  = () => { wsConnected.value = true; clearTimeout(reconnectTimer) }
      ws.onclose = () => { wsConnected.value = false; reconnectTimer = setTimeout(connectWs, 2000) }
      ws.onerror = () => ws.close()
      ws.onmessage = ({ data }) => {
        const msg = JSON.parse(data)
        if (msg.type === 'metrics') handleMetrics(msg)
        else if (msg.type === 'order') handleOrder(msg)
      }
    }

    function handleMetrics(m) {
      latestMetricsSnapshot = m
    }

    function applyMetricsSnapshot(m) {
      if (!m) return

      metrics.throughputPerSec = m.throughputPerSec
      metrics.p80Us       = m.p80Us       ?? 0
      metrics.p90Us       = m.p90Us       ?? 0
      metrics.p99Us       = m.p99Us       ?? 0
      metrics.fills       = m.fills        ?? 0
      metrics.rejects     = m.rejects      ?? 0
      metrics.cancels     = m.cancels      ?? 0
      metrics.execReports = m.execReports  ?? 0
      metrics.orders      = m.ordersReceived ?? 0

      // Update multi-line chart
      const now = new Date().toLocaleTimeString()
      if (latencyHistory.labels.length >= 60) {
        latencyHistory.labels.shift()
        latencyHistory.p80.shift()
        latencyHistory.p90.shift()
        latencyHistory.p99.shift()
      }
      latencyHistory.labels.push(now)
      latencyHistory.p80.push(m.p80Us ?? 0)
      latencyHistory.p90.push(m.p90Us ?? 0)
      latencyHistory.p99.push(m.p99Us ?? 0)
      if (chart) {
        chart.data.labels = latencyHistory.labels
        chart.data.datasets[0].data = latencyHistory.p99
        chart.data.datasets[1].data = latencyHistory.p90
        chart.data.datasets[2].data = latencyHistory.p80
        chart.update('none')
      }
    }

    function toMetricsSnapshot(source) {
      if (!source) return null
      return {
        throughputPerSec:  source.throughputPerSec ?? 0,
        p80Us:             source.p80LatencyUs  ?? source.p80Us  ?? 0,
        p90Us:             source.p90LatencyUs  ?? source.p90Us  ?? 0,
        p99Us:             source.p99LatencyUs  ?? source.p99Us  ?? 0,
        fills:             source.fillsSent     ?? source.fills   ?? 0,
        rejects:           source.rejectsSent   ?? source.rejects ?? 0,
        cancels:           source.cancelsSent   ?? source.cancels ?? 0,
        execReports:       source.execReportsSent ?? source.execReports ?? 0,
        ordersReceived:    source.ordersReceived ?? source.orders ?? 0,
      }
    }

    function clearLatencyChart() {
      latencyHistory.labels.length = 0
      latencyHistory.p80.length = 0
      latencyHistory.p90.length = 0
      latencyHistory.p99.length = 0
      if (chart) {
        chart.data.labels = latencyHistory.labels
        chart.data.datasets[0].data = latencyHistory.p99
        chart.data.datasets[1].data = latencyHistory.p90
        chart.data.datasets[2].data = latencyHistory.p80
        chart.update('none')
      }
    }

    function flushLatestMetrics(force = false) {
      if (!latestMetricsSnapshot) return
      if (!force && !wsConnected.value) return
      applyMetricsSnapshot(latestMetricsSnapshot)
    }

    function updateMetricsRefreshInterval() {
      if (metricsRefreshTimer) clearInterval(metricsRefreshTimer)
      metricsRefreshTimer = setInterval(refreshMetricsDashboard, selectedRefreshSeconds.value * 1000)
      refreshMetricsDashboard()
    }

    async function refreshMetricsDashboard() {
      await fetchStats({ silent: true })
    }

    function handleOrder(o) {
      const entry = {
        id: o.correlationId, time: new Date().toLocaleTimeString('en',{hour12:false,hour:'2-digit',minute:'2-digit',second:'2-digit',fractionalSecondDigits:3}),
        symbol: o.symbol, side: o.side, qty: o.orderQty, price: o.price,
        execType: o.execType, status: o.ordStatus, behavior: o.fillBehavior,
        latencyUs: Math.round(o.latencyNs / 1000),
        flash: o.execType === 'REJECTED' ? 'flash-reject' : 'flash-fill'
      }
      orders.value.unshift(entry)
      if (orders.value.length > 100) orders.value.pop()
      // Remove flash class after animation
      setTimeout(() => { entry.flash = '' }, 700)
    }

    // ── REST helpers ──────────────────────────────────────────────────────────
    async function fetchProfiles() {
      const r = await fetch('/api/fill-profiles')
      profiles.value = await r.json()
      if (profiles.value.length > 0 && !selectedProfile.value)
        selectedProfile.value = profiles.value[0].name
    }
    async function fetchSessions() {
      const r = await fetch('/api/sessions')
      sessions.value = await r.json()
    }
    async function fetchStats({ silent = false } = {}) {
      if (metricsFetchInFlight) return
      metricsFetchInFlight = true
      try {
        const r = await fetch('/api/statistics')
        if (!r.ok) throw new Error(`Statistics fetch failed with status ${r.status}`)
        const s = await r.json()
        activeProfile.value = s.activeProfile || '–'
        latestMetricsSnapshot = toMetricsSnapshot(s)
        flushLatestMetrics(true)
      } catch (e) {
        if (!silent) {
          console.error('Failed to fetch statistics', e)
        }
      } finally {
        metricsFetchInFlight = false
      }
    }

    async function resetMetrics() {
      if (isResettingMetrics.value) return
      isResettingMetrics.value = true
      try {
        const r = await fetch('/api/statistics/reset', { method: 'POST' })
        if (!r.ok) throw new Error(`Reset failed with status ${r.status}`)
        const s = await r.json()
        activeProfile.value = s.activeProfile || activeProfile.value
        latestMetricsSnapshot = toMetricsSnapshot(s)
        clearLatencyChart()
        flushLatestMetrics(true)
      } catch (e) {
        console.error('Failed to reset metrics', e)
        alert('Failed to reset metrics. Please try again.')
      } finally {
        isResettingMetrics.value = false
      }
    }

    async function activateProfile() {
      if (!selectedProfile.value) return
      await fetch(`/api/fill-profiles/${encodeURIComponent(selectedProfile.value)}/activate`, { method: 'PUT' })
      activeProfile.value = selectedProfile.value
    }

    async function saveAndActivate() {
      if (!form.name) return alert('Profile name is required')
      await fetch('/api/fill-profiles', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ ...form })
      })
      await fetchProfiles()
      selectedProfile.value = form.name
      await activateProfile()
    }

    async function disconnectSession(id) {
      await fetch(`/api/sessions/${encodeURIComponent(id)}`, { method: 'DELETE' })
      // Disconnect is async — give Artio time to process before refreshing
      setTimeout(fetchSessions, 1500)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    function execTypeBadge(t) {
      if (t === 'FILL' || t === 'TRADE')    return 'bg-brand-900 text-brand-400'
      if (t === 'PARTIAL_FILL')             return 'bg-yellow-900 text-yellow-400'
      if (t === 'REJECTED' || t==='CANCELED') return 'bg-red-900 text-red-400'
      return 'bg-dark-600 text-slate-400'
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    onMounted(async () => {
      await fetchProfiles()
      await fetchSessions()
      await fetchStats()

      // Poll sessions every 5 s
      pollTimer = setInterval(fetchSessions, 5000)

      // Build Chart.js latency chart
      await nextTick()
      if (latencyChart.value) {
        chart = new Chart(latencyChart.value, {
          type: 'line',
          data: {
            labels: latencyHistory.labels,
            datasets: [
              {
                label: 'p99',
                data: latencyHistory.p99,
                borderColor: '#ef4444', backgroundColor: '#ef444418',
                borderWidth: 2, pointRadius: 0, fill: false, tension: 0.3
              },
              {
                label: 'p90',
                data: latencyHistory.p90,
                borderColor: '#f59e0b', backgroundColor: '#f59e0b18',
                borderWidth: 1.5, pointRadius: 0, fill: false, tension: 0.3
              },
              {
                label: 'p80',
                data: latencyHistory.p80,
                borderColor: '#22c55e', backgroundColor: '#22c55e18',
                borderWidth: 1.5, pointRadius: 0, fill: false, tension: 0.3
              }
            ]
          },
          options: {
            responsive: true, maintainAspectRatio: true,
            animation: false,
            plugins: {
              legend: {
                display: true,
                labels: { color: '#94a3b8', font: { size: 11 }, boxWidth: 14, padding: 12 }
              }
            },
            scales: {
              x: { display: false },
              y: {
                grid: { color: '#1e293b' },
                ticks: { color: '#94a3b8', font: { size: 10 } },
                title: { display: true, text: 'µs', color: '#64748b', font: { size: 10 } }
              }
            }
          }
        })
      }

      connectWs()
      updateMetricsRefreshInterval()

    })

    onUnmounted(() => {
      if (pollTimer) clearInterval(pollTimer)
      if (metricsRefreshTimer) clearInterval(metricsRefreshTimer)
      if (reconnectTimer) clearTimeout(reconnectTimer)
      if (ws) ws.close()
      if (chart) chart.destroy()
    })

    return {
      wsConnected, activeProfile, profiles, selectedProfile, sessions,
      orders, metrics, form, behaviorTypes, rejectReasons,
      loggedOnSessionCount,
      showFillPct, showDelay, showReject, showRandom, showPriceImprovement,
      latencyChart, selectedRefreshSeconds, metricsRefreshOptions, isResettingMetrics,
      updateMetricsRefreshInterval, resetMetrics,
      activateProfile, saveAndActivate, disconnectSession, execTypeBadge
    }
  }
}).mount('#app')

