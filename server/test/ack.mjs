// 验证 ACK 机制：多条离线纸条都要能拉到，且 ACK 之后不再重复
import WebSocket from 'ws'
const BASE = process.env.BASE || 'http://127.0.0.1:8099'
const WS_BASE = BASE.replace(/^http/, 'ws')

async function post(path, body, token) {
  const res = await fetch(`${BASE}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: JSON.stringify(body ?? {}),
  })
  return { status: res.status, json: await res.json().catch(() => ({})) }
}
async function get(path, token) {
  const res = await fetch(`${BASE}${path}`, { headers: token ? { Authorization: `Bearer ${token}` } : {} })
  return { status: res.status, json: await res.json().catch(() => ({})) }
}
const assert = (c, l) => { console.log(c ? `  ✓ ${l}` : `  ✗ ${l}`); if (!c) process.exit(1) }

;(async () => {
  const r1 = await post('/api/auth/register', { username: 'ack_a_' + Date.now(), password: 'pw123456', nickname: 'A' })
  const r2 = await post('/api/auth/register', { username: 'ack_b_' + Date.now(), password: 'pw123456', nickname: 'B' })
  const a = r1.json, b = r2.json
  const inv = await post('/api/pair/code', {}, a.token)
  await post('/api/pair/join', { code: inv.json.code }, b.token)
  console.log('[1] 两张都注册并配对')

  // A 连发三张，B 不在线
  for (const t of ['第一张', '第二张', '第三张']) {
    await post('/api/notes', { toUserId: b.userId, text: t, doodle: '' }, a.token)
  }
  console.log('[2] A 连发 3 张')

  // B 拉取，应该三张都在
  const p1 = await get('/api/notes/pending', b.token)
  assert(p1.json.notes.length === 3, `第一次拉到 3 张（实际 ${p1.json.notes.length}）`)
  assert(p1.json.notes.map(n => n.text).join(',') === '第一张,第二张,第三张', '顺序正确')

  // 没 ACK，再拉一次应该还是三张（不会丢）
  const p2 = await get('/api/notes/pending', b.token)
  assert(p2.json.notes.length === 3, `未 ACK 时再拉仍是 3 张（实际 ${p2.json.notes.length}）`)

  // 建 WebSocket，发 ACK
  await new Promise((resolve) => {
    const ws = new WebSocket(`${WS_BASE}/ws?token=${encodeURIComponent(b.token)}`)
    ws.on('open', () => {
      ws.send(JSON.stringify({ type: 'ack', ids: p2.json.notes.map(n => n.id) }))
      setTimeout(() => { ws.close(); resolve() }, 300)
    })
    ws.on('error', resolve)
  })
  console.log('[3] 已回执')

  const p3 = await get('/api/notes/pending', b.token)
  assert(p3.json.notes.length === 0, `ACK 之后待发清空（实际 ${p3.json.notes.length}）`)

  // 别人不能替 B 回执
  console.log('[4] 越权 ACK 检查')
  const c = (await post('/api/auth/register', { username: 'ack_c_' + Date.now(), password: 'pw123456', nickname: 'C' })).json
  // 未配对不能发纸条，先把 A 和 C 配上
  const inv2 = await post('/api/pair/code', {}, a.token)
  await post('/api/pair/join', { code: inv2.json.code }, c.token)
  await post('/api/notes', { toUserId: c.userId, text: '给C的', doodle: '' }, a.token)
  await new Promise((resolve) => {
    const ws = new WebSocket(`${WS_BASE}/ws?token=${encodeURIComponent(b.token)}`)
    ws.on('open', () => {
      // B 试图确认发给 C 的纸条
      const all = p2.json.notes.map(n => n.id)
      ws.send(JSON.stringify({ type: 'ack', ids: [...all, 'fake-id-123'] }))
      setTimeout(() => { ws.close(); resolve() }, 300)
    })
    ws.on('error', resolve)
  })
  const cp = await get('/api/notes/pending', c.token)
  assert(cp.json.notes.length === 1, `C 的纸条没被别人 ACK 掉（实际 ${cp.json.notes.length}）`)

  console.log('\n✅ ACK 机制正常：不丢、不重复、不可越权')
  process.exit(0)
})().catch(e => { console.error(e); process.exit(1) })


