'use strict'

const http = require('node:http')
const fs = require('node:fs')
const net = require('node:net')
const { timingSafeEqual, randomUUID } = require('node:crypto')
const { performance } = require('node:perf_hooks')
const { Atem, Enums } = require('atem-connection')

const config = JSON.parse(fs.readFileSync(process.argv[2] || 'config.json', 'utf8').replace(/^\uFEFF/, ''))
if ((!config.simulator && !net.isIPv4(config.atem)) || !net.isIPv4(config.bind) ||
    typeof config.token !== 'string' || !/^[a-f0-9]{32}$/.test(config.token)) {
  throw new Error('Configure the ATEM IPv4, booth bind IPv4, and a 32-character pairing code.')
}

const atem = config.simulator ? null : new Atem()
let connected = Boolean(config.simulator)
let simulated = { preview: 2, program: 1, dsk: false, transitioning: false }
let lease = null
let busy = false
const leaseMs = 5000

atem?.on('error', error => console.error(new Date().toISOString(), error))
atem?.on('connected', () => { connected = true })
atem?.on('disconnected', () => {
  connected = false
  lease = null
})

function state() {
  if (config.simulator) {
    return { ...simulated, inputs: Array.from({ length: 8 }, (_, i) => ({ id: i + 1, name: 'Input ' + (i + 1) })) }
  }
  const raw = atem.state
  const me = raw?.video.mixEffects[0]
  const key = raw?.video.downstreamKeyers[0]
  if (!connected || !me || !key) return null
  const inputs = Object.values(raw.inputs)
    .filter(input => input.portType === Enums.InternalPortType.External)
    .map(input => ({ id: input.inputId, name: input.longName || input.shortName || 'Input ' + input.inputId }))
    .sort((a, b) => a.id - b.id)
  if (!inputs.length) return null
  return {
    preview: me.previewInput, program: me.programInput, dsk: key.onAir,
    transitioning: me.transitionPosition.inTransition, inputs,
  }
}

function send(res, status, body) {
  res.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' })
  res.end(JSON.stringify(body))
}

function owns(id) {
  if (lease && lease.until <= performance.now()) lease = null
  return lease?.id === id && Boolean(lease)
}

async function waitFor(predicate, ms) {
  const deadline = performance.now() + ms
  do {
    if (predicate(state())) return true
    await new Promise(resolve => setTimeout(resolve, 35))
  } while (performance.now() < deadline)
  return false
}

async function withTimeout(promise, ms) {
  let timer
  try {
    return await Promise.race([
      promise,
      new Promise((_, reject) => { timer = setTimeout(() => reject(new Error('Command timeout')), ms) }),
    ])
  } finally {
    clearTimeout(timer)
  }
}

async function execute(action, body, before) {
  if (config.simulator) {
    if (action === 'preview') simulated.preview = body.source
    if (action === 'dsk') simulated.dsk = body.onAir
    if (action === 'cut' || action === 'auto') {
      simulated.program = before.preview
      simulated.preview = before.program
    }
    return true
  }
  if (action === 'preview') {
    await withTimeout(atem.changePreviewInput(body.source), 2400)
    return waitFor(s => s?.preview === body.source, 1800)
  }
  if (action === 'dsk') {
    await withTimeout(atem.setDownstreamKeyOnAir(body.onAir, 0), 2400)
    return waitFor(s => s?.dsk === body.onAir, 1800)
  }
  let autoObserved = false
  const observe = () => { autoObserved ||= Boolean(state()?.transitioning) }
  if (action === 'auto') atem.on('stateChanged', observe)
  try {
    await withTimeout(action === 'cut' ? atem.cut() : atem.autoTransition(), 2400)
    return await waitFor(s => Boolean(s && !s.transitioning &&
      (s.program === before.preview && before.program !== before.preview || action === 'auto' && autoObserved)), 6500)
  } finally {
    atem.off('stateChanged', observe)
  }
}

async function handle(req, res) {
  const auth = Buffer.from(req.headers.authorization || '')
  const expected = Buffer.from('Bearer ' + config.token)
  if (auth.length !== expected.length || !timingSafeEqual(auth, expected)) {
    return send(res, 401, { error: 'Pairing code rejected' })
  }
  const id = req.headers['x-lifeswitch-session']
  if (typeof id !== 'string' || !/^[0-9a-f-]{36}$/.test(id)) return send(res, 400, { error: 'Invalid session' })
  const before = state()
  if (req.method === 'GET' && req.url === '/state') {
    const armed = Boolean(before && owns(id))
    if (armed) lease.until = performance.now() + leaseMs
    return send(res, 200, { state: before, armed, busy, simulator: Boolean(config.simulator) })
  }
  if (req.method !== 'POST') return send(res, 404, { error: 'Unknown request' })
  if (req.url === '/disarm') {
    if (owns(id)) lease = null
    return send(res, 200, { ok: true })
  }
  if (req.url === '/arm') {
    owns(id) // Discard an expired lease before checking the current owner.
    if (!before || busy || (lease && lease.id !== id)) return send(res, 409, { error: 'Switcher unavailable or another device has Live control' })
    lease = { id, until: performance.now() + leaseMs }
    return send(res, 200, { ok: true })
  }
  const action = req.url.slice(1)
  if (!['preview', 'dsk', 'cut', 'auto'].includes(action)) return send(res, 404, { error: 'Unknown request' })
  let data = ''
  for await (const chunk of req) {
    data += chunk
    if (data.length > 1024) return send(res, 413, { error: 'Request too large' })
  }
  let body
  try { body = JSON.parse(data) } catch { return send(res, 400, { error: 'Invalid request' }) }
  if (!body || typeof body !== 'object') return send(res, 400, { error: 'Invalid request' })
  const latest = state()
  if (!latest || !owns(id) || busy) return send(res, 409, { error: 'Live control unavailable' })
  if (action === 'preview' && !latest.inputs.some(input => input.id === body.source)) return send(res, 400, { error: 'Input unavailable' })
  if (action === 'dsk' && (typeof body.onAir !== 'boolean' || latest.dsk === body.onAir)) return send(res, 409, { error: 'Lower thirds changed. Check status.' })
  if ((action === 'cut' || action === 'auto') &&
      (latest.transitioning || body.preview !== latest.preview || body.program !== latest.program)) {
    return send(res, 409, { error: 'Switcher state changed. Check Preview and Program.' })
  }

  busy = true
  try {
    if (!(await execute(action, body, latest))) throw new Error('State not confirmed')
    send(res, 200, { ok: true })
  } catch (error) {
    lease = null
    console.error(randomUUID(), action, error.message)
    send(res, 504, { error: 'Outcome unknown. Check the physical switcher.' })
  } finally {
    busy = false
  }
}

const server = http.createServer((req, res) => {
  handle(req, res).catch(error => {
    console.error(error.message)
    if (!res.headersSent) send(res, 500, { error: 'Booth service error' })
  })
})
server.requestTimeout = 5000
server.listen(config.port || 8765, config.bind, () => {
  console.log('LifeSWITCH listening at', server.address(), config.simulator ? 'SIMULATOR' : 'ATEM ' + config.atem)
})
atem?.connect(config.atem).catch(error => console.error(error))
