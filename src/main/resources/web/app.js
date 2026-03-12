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
      <div class="flex items-center gap-4">
        <span :class="wsConnected ? 'text-brand-500' : 'text-red-400'" class="text-xs font-mono flex items-center gap-1">
          <span :class="wsConnected ? 'bg-brand-500' : 'bg-red-400'" class="w-2 h-2 rounded-full inline-block"></span>
          {{ wsConnected ? 'LIVE' : 'DISCONNECTED' }}
        </span>
        <span class="text-xs text-slate-400 font-mono">FIX Sessions: {{ sessions.length }}</span>
      </div>
    </header>

    <main class="p-6 grid grid-cols-12 gap-6">

      <!-- ── Stats Cards Row ─────────────────────────────────────────────── -->
      <div class="col-span-12 grid grid-cols-2 md:grid-cols-4 gap-4">
        <div class="bg-dark-800 rounded-xl p-4 border border-dark-600">
          <p class="text-xs text-slate-400 uppercase tracking-wider mb-1">Throughput</p>
          <p class="text-2xl font-bold font-mono text-brand-500">{{ metrics.throughputPerSec.toLocaleString() }}</p>
          <p class="text-xs text-slate-500">orders/sec</p>
        </div>
        <div class="bg-dark-800 rounded-xl p-4 border border-dark-600">
          <p class="text-xs text-slate-400 uppercase tracking-wider mb-1">p99 Latency</p>
          <p class="text-2xl font-bold font-mono" :class="metrics.p99Us < 100 ? 'text-brand-500' : metrics.p99Us < 500 ? 'text-yellow-400' : 'text-red-400'">{{ metrics.p99Us.toLocaleString() }}</p>
          <p class="text-xs text-slate-500">microseconds</p>
        </div>
        <div class="bg-dark-800 rounded-xl p-4 border border-dark-600">
          <p class="text-xs text-slate-400 uppercase tracking-wider mb-1">Fill Rate</p>
          <p class="text-2xl font-bold font-mono text-brand-500">{{ fillRate }}%</p>
          <p class="text-xs text-slate-500">{{ metrics.fills.toLocaleString() }} fills</p>
        </div>
        <div class="bg-dark-800 rounded-xl p-4 border border-dark-600">
          <p class="text-xs text-slate-400 uppercase tracking-wider mb-1">Reject Rate</p>
          <p class="text-2xl font-bold font-mono text-red-400">{{ rejectRate }}%</p>
          <p class="text-xs text-slate-500">{{ metrics.rejects.toLocaleString() }} rejects</p>
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
              <h2 class="font-semibold text-white text-sm">p99 Latency History</h2>
              <p class="text-xs text-slate-500 mt-1">last 60 dashboard refreshes</p>
            </div>
            <div class="flex items-center gap-3">
              <div class="flex flex-col items-end">
                <label class="text-xs text-slate-400 mb-1">Metrics refresh</label>
                <select v-model.number="selectedRefreshSeconds"
                        @change="updateMetricsRefreshInterval"
                        class="bg-dark-700 border border-dark-600 rounded-lg px-3 py-1.5 text-xs text-white focus:outline-none focus:border-brand-500">
                  <option v-for="seconds in metricsRefreshOptions" :key="seconds" :value="seconds">
                    {{ seconds }} second{{ seconds === 1 ? '' : 's' }}
                  </option>
                </select>
              </div>
              <span class="text-xs text-slate-400 font-mono text-right min-w-[7rem]">
                applied every {{ selectedRefreshSeconds }}s
              </span>
            </div>
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
      throughputPerSec: 0, p50Us: 0, p99Us: 0, p999Us: 0, maxUs: 0,
      fills: 0, rejects: 0, orders: 0
    })

    const fillRate   = ref('0.0')
    const rejectRate = ref('0.0')
    const selectedRefreshSeconds = ref(1)
    const metricsRefreshOptions = METRICS_REFRESH_OPTIONS

    const latencyChart = ref(null)
    let   chart        = null
    const latencyHistory = { labels: [], p99: [] }
    let metricsRefreshTimer = null
    let pollTimer = null
    let latestMetricsSnapshot = null

    // ── Form state ────────────────────────────────────────────────────────────
    const form = reactive({
      name: '', description: '', behaviorType: 'IMMEDIATE_FULL_FILL',
      fillPctBps: 10000, numPartialFills: 1, delayMs: 0,
      rejectReason: 'SIMULATOR_REJECT', randomMinQtyPct: 50, randomMaxQtyPct: 100,
      randomMinDelayMs: 0, randomMaxDelayMs: 5, priceImprovementBps: 1,
    })

    const behaviorTypes  = BEHAVIOR_TYPES
    const rejectReasons  = REJECT_REASONS

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
      ws = new WebSocket(`ws://${location.host}/ws`)
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
      metrics.p50Us  = m.p50Us;   metrics.p99Us  = m.p99Us
      metrics.p999Us = m.p999Us;  metrics.maxUs  = m.maxUs
      metrics.fills  = m.fills;   metrics.rejects = m.rejects
      metrics.orders = m.ordersReceived

      const total = m.fills + m.rejects
      fillRate.value   = total > 0 ? (m.fills  * 100 / total).toFixed(1) : '0.0'
      rejectRate.value = total > 0 ? (m.rejects * 100 / total).toFixed(1) : '0.0'

      // Update chart
      const now = new Date().toLocaleTimeString()
      if (latencyHistory.labels.length >= 60) { latencyHistory.labels.shift(); latencyHistory.p99.shift() }
      latencyHistory.labels.push(now)
      latencyHistory.p99.push(m.p99Us)
      if (chart) {
        chart.data.labels = latencyHistory.labels
        chart.data.datasets[0].data = latencyHistory.p99
        chart.update('none') // 'none' = no animation, zero overhead
      }
    }

    function flushLatestMetrics(force = false) {
      if (!latestMetricsSnapshot) return
      if (!force && !wsConnected.value) return
      applyMetricsSnapshot(latestMetricsSnapshot)
    }

    function updateMetricsRefreshInterval() {
      if (metricsRefreshTimer) clearInterval(metricsRefreshTimer)
      metricsRefreshTimer = setInterval(() => flushLatestMetrics(), selectedRefreshSeconds.value * 1000)
      flushLatestMetrics(true)
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
    async function fetchStats() {
      const r = await fetch('/api/statistics')
      const s = await r.json()
      activeProfile.value = s.activeProfile || '–'
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
      await fetchSessions()
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
            datasets: [{
              label: 'p99 latency (µs)',
              data: latencyHistory.p99,
              borderColor: '#22c55e', backgroundColor: '#22c55e18',
              borderWidth: 1.5, pointRadius: 0, fill: true, tension: 0.3
            }]
          },
          options: {
            responsive: true, maintainAspectRatio: true,
            animation: false,
            plugins: { legend: { display: false } },
            scales: {
              x: { display: false },
              y: { grid: { color: '#1e293b' }, ticks: { color: '#94a3b8', font: { size: 10 } } }
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
      orders, metrics, fillRate, rejectRate, form, behaviorTypes, rejectReasons,
      showFillPct, showDelay, showReject, showRandom, showPriceImprovement,
      latencyChart, selectedRefreshSeconds, metricsRefreshOptions, updateMetricsRefreshInterval,
      activateProfile, saveAndActivate, disconnectSession, execTypeBadge
    }
  }
}).mount('#app')

