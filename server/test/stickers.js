'use strict'

/**
 * 贴纸端到端测试。
 *
 * 验证三件事：
 * 1. 贴纸能随纸条一起发出去、原样到达
 * 2. 服务端会过滤非法字段（不能让客户端塞任意东西）
 * 3. 离线补发时贴纸不丢
 *
 * 用法：BASE=http://127.0.0.1:8090 node test/stickers.js
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
  return { status: res.status, ok: res.ok, json }
}

async function get(path, token) {
  const res = await fetch(`${BASE}${path}`, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  })
  return { status: res.status, ok: res.ok, json: await res.json().catch(() => ({})) }
}

function assert(cond, label) {
  if (cond) console.log(`  ✓ ${label}`)
  else {
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

const rnd = Math.random().toString(36).slice(2, 7)

async function main() {
  console.log(`目标：${BASE}\n`)

  console.log('[1] 注册两个用户并配对')
  const a = (await post('/api/auth/register', {
    username: `stickerA_${rnd}`,
    password: 'pass123456',
    nickname: '贴纸A',
  })).json
  const b = (await post('/api/auth/register', {
    username: `stickerB_${rnd}`,
    password: 'pass123456',
    nickname: '贴纸B',
  })).json
  const code = (await post('/api/pair/code', {}, a.token)).json.code
  await post('/api/pair/join', { code }, b.token)
  assert(true, 'A 和 B 已配对')

  const { ws: wsB } = await connectAndWait(b.token, 'ready')

  console.log('[2] 带贴纸的纸条可以正常送达')
  // 用一段假的 PNG base64 —— 服务端不解析图片内容，只做长度校验和转发
  const fakePng = Buffer.from('fake-png-content-for-test').toString('base64')
  const stickers = [
    { id: 's1', cx: 0.35, cy: 0.4, widthRatio: 0.3, rotation: 12, data: fakePng, fromName: '贴纸A' },
    { id: 's2', cx: 0.7, cy: 0.65, widthRatio: 0.2, rotation: -25, data: fakePng },
  ]

  const incoming = nextMessage(wsB, 'note')
  await post(
    '/api/notes',
    { toUserId: b.userId, text: '看这张贴纸', stickers },
    a.token
  )
  const got = await incoming
  assert(Array.isArray(got.note.stickers), '收到 stickers 数组')
  assert(got.note.stickers.length === 2, `收到 ${got.note.stickers.length} 张贴纸`)
  assert(got.note.stickers[0].data === fakePng, '贴纸图片数据完整')
  assert(
    Math.abs(got.note.stickers[0].cx - 0.35) < 0.001,
    `位置保留（cx=${got.note.stickers[0].cx}）`
  )
  assert(
    Math.abs(got.note.stickers[1].rotation - -25) < 0.001,
    `旋转保留（rotation=${got.note.stickers[1].rotation}）`
  )
  assert(got.note.stickers[0].fromName === '贴纸A', '贴纸来源昵称保留')

  console.log('[3] 没有贴纸的纸条仍然正常（不能把 stickers 写成 null 崩掉）')
  const incoming2 = nextMessage(wsB, 'note')
  await post('/api/notes', { toUserId: b.userId, text: '纯文字' }, a.token)
  const got2 = await incoming2
  assert(Array.isArray(got2.note.stickers) && got2.note.stickers.length === 0, 'stickers 为空数组')

  console.log('[4] 服务端过滤非法数值')
  const incoming3 = nextMessage(wsB, 'note')
  await post(
    '/api/notes',
    {
      toUserId: b.userId,
      text: '越界测试',
      stickers: [
        // cx 超出 0~1、rotation 超大、多余字段 —— 都应被规范化
        { id: 'bad', cx: 99, cy: -5, widthRatio: 50, rotation: 99999, data: fakePng, evil: 'x' },
      ],
    },
    a.token
  )
  const got3 = await incoming3
  const s = got3.note.stickers[0]
  assert(s.cx <= 1 && s.cx >= 0, `cx 被钳制到 ${s.cx}`)
  assert(s.cy <= 1 && s.cy >= 0, `cy 被钳制到 ${s.cy}`)
  assert(s.widthRatio <= 1.2, `widthRatio 被钳制到 ${s.widthRatio}`)
  assert(Math.abs(s.rotation) <= 360, `rotation 被钳制到 ${s.rotation}`)
  assert(s.evil === undefined, '未知字段被丢弃，不会存进库')

  console.log('[5] 超过数量上限应被拒绝')
  const many = Array.from({ length: 20 }, (_, i) => ({
    id: 's' + i, cx: 0.5, cy: 0.5, widthRatio: 0.2, rotation: 0, data: fakePng,
  }))
  const tooMany = await post(
    '/api/notes',
    { toUserId: b.userId, text: '太多贴纸', stickers: many },
    a.token
  )
  assert(tooMany.status === 400, `超过 12 张被拒（${tooMany.status}）`)

  console.log('[6] 离线补发时贴纸不丢')
  wsB.terminate()
  sockets.delete(wsB)
  await new Promise((r) => setTimeout(r, 600))

  await post(
    '/api/notes',
    { toUserId: b.userId, text: '离线时的贴纸', stickers: [stickers[0]] },
    a.token
  )
  const pending = await get('/api/notes/pending', b.token)
  const offlineNote = pending.json.notes.find((n) => n.text === '离线时的贴纸')
  assert(!!offlineNote, '拉到离线纸条')
  assert(offlineNote.stickers.length === 1, '离线纸条的贴纸也在')
  assert(offlineNote.stickers[0].data === fakePng, '离线贴纸内容完整')

  console.log('\n全部通过 ✅  贴纸能随纸条流转，且服务端做了字段校验')
}

main()
  .then(() => {
    for (const ws of sockets) ws.terminate()
    process.exit(0)
  })
  .catch((e) => {
    console.error('\n贴纸测试失败 ❌', e.message)
    for (const ws of sockets) ws.terminate()
    process.exit(1)
  })
