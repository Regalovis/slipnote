'use strict'

/**
 * 端到端联调脚本。
 *
 * 模拟两个用户：A 和 B 各自注册 → A 生成邀请码 → B 用码配对 →
 * B 挂上 WebSocket 长连接 → A 发纸条 → 断言 B 秒级收到推送。
 *
 * 用法：
 *   BASE=http://127.0.0.1:8090 node test/e2e.js              # 直连后端
 *   BASE=https://example.com/SlipNote node test/e2e.js          # 走 nginx（推荐）
 *
 * 注意 WebSocket 的监听必须在连接建立**之前**注册。
 * 服务端连上后会立刻下发 ready，如果等 open 之后才挂 listener，
 * 当握手响应与 ready 帧落在同一个 TCP 包里时就会漏掉这条消息。
 */

const WebSocket = require('ws')

const BASE = (process.env.BASE || 'http://127.0.0.1:8090').replace(/\/+$/, '')
const WS_BASE = BASE.replace(/^http/, 'ws')

// 所有打开的连接，结束时统一关闭，避免进程挂住不退出
const openSockets = new Set()

async function post(path, body, token) {
  const res = await fetch(`${BASE}${path}`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify(body ?? {}),
  })
  const json = await res.json().catch(() => ({}))
  if (!res.ok) throw new Error(`${path} -> ${res.status} ${JSON.stringify(json)}`)
  return json
}

async function get(path, token) {
  const res = await fetch(`${BASE}${path}`, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  })
  const json = await res.json().catch(() => ({}))
  if (!res.ok) throw new Error(`${path} -> ${res.status} ${JSON.stringify(json)}`)
  return json
}

function assert(cond, label) {
  if (cond) {
    console.log(`  ✓ ${label}`)
  } else {
    console.error(`  ✗ ${label}`)
    throw new Error(`assertion failed: ${label}`)
  }
}

/**
 * 连接 WebSocket 并等待指定类型的第一条消息。
 *
 * 关键点：message 监听在 new WebSocket() 之后**立即**注册，
 * 早于 open 事件。这样服务端在握手刚完成时下发的 ready 不会被漏掉。
 */
function connectAndWaitFor(token, wantedType, timeoutMs = 10000) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(`${WS_BASE}/ws?token=${encodeURIComponent(token)}`)
    openSockets.add(ws)

    const timer = setTimeout(() => {
      reject(new Error(`timeout waiting for "${wantedType}" (${timeoutMs}ms)`))
    }, timeoutMs)

    ws.on('message', (raw) => {
      let msg
      try {
        msg = JSON.parse(raw.toString())
      } catch {
        return
      }
      if (msg.type !== wantedType) return
      clearTimeout(timer)
      resolve({ ws, msg })
    })

    ws.on('error', (err) => {
      clearTimeout(timer)
      reject(err)
    })
  })
}

/** 等待一个已连接 socket 上的下一条指定类型消息。 */
function nextMessage(ws, wantedType, timeoutMs = 10000) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      ws.off('message', onMessage)
      reject(new Error(`timeout waiting for "${wantedType}" (${timeoutMs}ms)`))
    }, timeoutMs)

    function onMessage(raw) {
      let msg
      try {
        msg = JSON.parse(raw.toString())
      } catch {
        return
      }
      if (msg.type !== wantedType) return
      clearTimeout(timer)
      ws.off('message', onMessage)
      resolve(msg)
    }

    ws.on('message', onMessage)
  })
}

function closeAll() {
  for (const ws of openSockets) {
    try {
      ws.terminate()
    } catch {
      /* ignore */
    }
  }
  openSockets.clear()
}

async function main() {
  console.log(`目标：${BASE}\n`)

  console.log('[0] 健康检查')
  const health = await get('/api/health')
  assert(health.ok === true, '服务端存活')

  console.log('[1] 注册两个用户')
  const a = await post('/api/register', { nickname: '小明' })
  const b = await post('/api/register', { nickname: '小红' })
  assert(!!a.token && !!b.token, `A=${a.nickname} B=${b.nickname} 注册成功`)
  assert(a.userId !== b.userId, '两个用户 ID 不同')

  console.log('[2] B 建立 WebSocket 长连接')
  const { ws: wsB, msg: ready } = await connectAndWaitFor(b.token, 'ready')
  assert(ready.userId === b.userId, 'WebSocket 认证通过并收到 ready')

  console.log('[3] A 生成邀请码，B 用码配对')
  const { code } = await post('/api/pair/code', {}, a.token)
  assert(/^\d{6}$/.test(code), `邀请码为 6 位数字：${code}`)

  const pairedNotice = nextMessage(wsB, 'paired')
  const joined = await post('/api/pair/join', { code }, b.token)
  assert(joined.peerId === a.userId, `B 配对到了 ${joined.peerNickname}`)
  const notice = await pairedNotice
  assert(notice.peer.userId === a.userId, 'B 实时收到配对通知')

  console.log('[4] 校验双方配对列表')
  const peersA = await get('/api/peers', a.token)
  const peersB = await get('/api/peers', b.token)
  assert(peersA.peers.length === 1 && peersA.peers[0].userId === b.userId, 'A 看到 1 个同伴')
  assert(peersB.peers.length === 1 && peersB.peers[0].userId === a.userId, 'B 看到 1 个同伴')

  console.log('[5] A 发纸条，断言 B 秒级收到')
  const t0 = Date.now()
  const incoming = nextMessage(wsB, 'note')
  const sent = await post(
    '/api/notes',
    { toUserId: b.userId, text: '今天降温，记得带外套 🧥' },
    a.token
  )
  assert(sent.delivered === true, '服务端判定为实时送达')

  const got = await incoming
  const cost = Date.now() - t0
  assert(got.note.text === '今天降温，记得带外套 🧥', `内容正确：${got.note.text}`)
  assert(got.note.fromNickname === '小明', `落款正确：${got.note.fromNickname}`)
  assert(cost < 3000, `端到端耗时 ${cost}ms（< 3000ms）`)

  console.log('[6] 带涂鸦的纸条')
  const doodle = 'data:image/png;base64,' + Buffer.from('fake-png-bytes').toString('base64')
  const incoming2 = nextMessage(wsB, 'note')
  await post(
    '/api/notes',
    { toUserId: b.userId, text: '', doodle, paperStyle: 'grid', landscape: true },
    a.token
  )
  const got2 = await incoming2
  assert(got2.note.doodle === doodle, '涂鸦原样透传')
  assert(got2.note.paperStyle === 'grid', '纸张样式透传（方格）')
  assert(got2.note.landscape === true, '横版方向透传')

  console.log('[6b] 非法纸张样式应被兜底为 white')
  const incoming3 = nextMessage(wsB, 'note')
  await post(
    '/api/notes',
    { toUserId: b.userId, text: '兜底测试', paperStyle: '不存在的样式' },
    a.token
  )
  const got3 = await incoming3
  assert(got3.note.paperStyle === 'white', '未知样式回落为 white')
  assert(got3.note.landscape === false, '未传方向时默认为竖版')

  console.log('[7] 未配对不能发送')
  const c = await post('/api/register', { nickname: '路人' })
  let rejected = false
  try {
    await post('/api/notes', { toUserId: b.userId, text: 'hi' }, c.token)
  } catch {
    rejected = true
  }
  assert(rejected, '未配对用户被拒绝')

  console.log('[8] 离线补偿')
  wsB.terminate()
  openSockets.delete(wsB)
  await new Promise((r) => setTimeout(r, 800))
  const offline = await post('/api/notes', { toUserId: b.userId, text: '离线时发的' }, a.token)
  assert(offline.delivered === false, '离线时标记为未送达')
  const pending = await get('/api/notes/pending', b.token)
  assert(
    pending.notes.some((n) => n.text === '离线时发的'),
    'B 重新上线后能拉到离线纸条'
  )

  console.log('\n全部通过 ✅  "对方一发 → 我秒收"的链路在真实网络下成立')
}

main()
  .then(() => {
    closeAll()
    process.exit(0)
  })
  .catch((err) => {
    console.error('\n联调失败 ❌', err.message)
    closeAll()
    process.exit(1)
  })
