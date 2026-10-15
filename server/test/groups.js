'use strict'

/**
 * 群组功能端到端测试。
 *
 * 重点验证三件事：
 * 1. 接力顺序是否正确（A→B→C→A 绕回）
 * 2. 匿名模式是否真的不泄露身份
 * 3. 非群成员能否被正确拒绝
 *
 * 用法：BASE=http://127.0.0.1:8090 node test/groups.js
 */

const WebSocket = require('ws')

const BASE = (process.env.BASE || 'http://127.0.0.1:8090').replace(/\/+$/, '')
const WS_BASE = BASE.replace(/^http/, 'ws')

const sockets = new Set()

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
    throw new Error('assertion failed: ' + label)
  }
}

function connectAndWait(token, wanted, timeoutMs = 10000) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(`${WS_BASE}/ws?token=${encodeURIComponent(token)}`)
    sockets.add(ws)
    const timer = setTimeout(() => reject(new Error(`timeout ${wanted}`)), timeoutMs)
    ws.on('message', (raw) => {
      let m
      try {
        m = JSON.parse(raw.toString())
      } catch {
        return
      }
      if (m.type !== wanted) return
      clearTimeout(timer)
      resolve({ ws, msg: m })
    })
    ws.on('error', (e) => {
      clearTimeout(timer)
      reject(e)
    })
  })
}

function nextMessage(ws, wanted, timeoutMs = 10000) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      ws.off('message', onMsg)
      reject(new Error(`timeout ${wanted}`))
    }, timeoutMs)
    function onMsg(raw) {
      let m
      try {
        m = JSON.parse(raw.toString())
      } catch {
        return
      }
      if (m.type !== wanted) return
      clearTimeout(timer)
      ws.off('message', onMsg)
      resolve(m)
    }
    ws.on('message', onMsg)
  })
}

async function main() {
  console.log(`目标：${BASE}\n`)

  console.log('[1] 注册三个用户')
  const a = await post('/api/register', { nickname: '小A' })
  const b = await post('/api/register', { nickname: '小B' })
  const c = await post('/api/register', { nickname: '小C' })
  assert(!!a.token && !!b.token && !!c.token, 'A / B / C 注册成功')

  console.log('[2] 三人建立长连接')
  const wa = (await connectAndWait(a.token, 'ready')).ws
  const wb = (await connectAndWait(b.token, 'ready')).ws
  const wc = (await connectAndWait(c.token, 'ready')).ws
  assert(true, '三端均已连上')

  console.log('[3] A 建群，B / C 用邀请码加入')
  const g = await post('/api/groups', { name: '接力群' }, a.token)
  assert(/^\d{6}$/.test(g.code), `群组邀请码 ${g.code}`)
  assert(g.memberCount === 1, '创建者自动成为第 1 位成员')

  const bJoin = await post('/api/groups/join', { code: g.code }, b.token)
  const cJoin = await post('/api/groups/join', { code: g.code }, c.token)
  assert(bJoin.joined && cJoin.joined, 'B 和 C 加入成功')

  const groups = await get('/api/groups', a.token)
  assert(groups.groups.length === 1, 'A 看到 1 个群')
  assert(groups.groups[0].memberCount === 3, '群成员数为 3')

  console.log('[4] 接力模式：A→B')
  const bWait = nextMessage(wb, 'note')
  await post(
    `/api/groups/${g.groupId}/notes`,
    { mode: 'chain', text: '第一棒' },
    a.token
  )
  const n1 = await bWait
  assert(n1.note.text === '第一棒', 'B 收到 A 的接力')
  assert(n1.note.chainMode === 'chain', '标记为接力模式')
  assert(n1.note.anonymous === false, '接力模式保留身份')
  assert(n1.note.fromNickname === '小A', `落款是真实昵称：${n1.note.fromNickname}`)

  console.log('[5] 接力模式：B→C')
  const cWait = nextMessage(wc, 'note')
  await post(`/api/groups/${g.groupId}/notes`, { mode: 'chain', text: '第二棒' }, b.token)
  const n2 = await cWait
  assert(n2.note.text === '第二棒', 'C 收到 B 的接力')
  assert(n2.note.fromNickname === '小B', '落款是 B')

  console.log('[6] 接力模式：C→A 绕回队首')
  const aWait = nextMessage(wa, 'note')
  await post(`/api/groups/${g.groupId}/notes`, { mode: 'chain', text: '第三棒' }, c.token)
  const n3 = await aWait
  assert(n3.note.text === '第三棒', 'C 的下一棒绕回到 A')
  assert(n3.note.fromNickname === '小C', '落款是 C')

  console.log('[7] 匿名随机模式：身份必须完全隐藏')
  // A 连发几张，收集 B、C 收到的内容
  const received = []
  const collect = (ws, name) => {
    ws.on('message', (raw) => {
      let m
      try {
        m = JSON.parse(raw.toString())
      } catch {
        return
      }
      if (m.type === 'note' && m.note.chainMode === 'random') {
        received.push({ to: name, note: m.note })
      }
    })
  }
  collect(wb, 'B')
  collect(wc, 'C')

  for (let i = 0; i < 6; i++) {
    await post(
      `/api/groups/${g.groupId}/notes`,
      { mode: 'random', text: `匿名${i}` },
      a.token
    )
  }
  await new Promise((r) => setTimeout(r, 800))

  assert(received.length > 0, `随机投递生效，收到 ${received.length} 张`)
  const allAnonymous = received.every(
    (r) => r.note.anonymous === true && r.note.fromUserId === null
  )
  assert(allAnonymous, '所有随机纸条的发送者 ID 都为 null')
  const allMasked = received.every((r) => r.note.fromNickname === '一位群友')
  assert(allMasked, '昵称一律显示为「一位群友」，未泄露真实身份')

  console.log('[8] 群发纸条可以带涂鸦')
  const doodle = 'data:image/png;base64,' + Buffer.from('group-doodle').toString('base64')
  const doodleWait = nextMessage(wb, 'note')
  await post(
    `/api/groups/${g.groupId}/notes`,
    { mode: 'chain', text: '', doodle },
    a.token
  )
  const nd = await doodleWait
  assert(nd.note.doodle === doodle, '涂鸦透传正常')

  console.log('[9] 非群成员不能发群纸条')
  const outsider = await post('/api/register', { nickname: '路人' })
  let rejected = false
  try {
    await post(`/api/groups/${g.groupId}/notes`, { text: '混进来' }, outsider.token)
  } catch {
    rejected = true
  }
  assert(rejected, '非成员被拒绝')

  console.log('[10] 只有 1 个人的群不能发')
  const solo = await post('/api/groups', { name: '单人' }, outsider.token)
  let soloRejected = false
  try {
    await post(`/api/groups/${solo.groupId}/notes`, { text: 'hi' }, outsider.token)
  } catch {
    soloRejected = true
  }
  assert(soloRejected, '单人群被拒绝（至少需要 2 人）')

  console.log('\n全部通过 ✅  群组接力与匿名互传均正常')
}

main()
  .then(() => {
    for (const ws of sockets) ws.terminate()
    process.exit(0)
  })
  .catch((e) => {
    console.error('\n群组测试失败 ❌', e.message)
    for (const ws of sockets) ws.terminate()
    process.exit(1)
  })
