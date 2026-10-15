'use strict'

const crypto = require('crypto')
const http = require('http')
const path = require('path')
const express = require('express')
const { WebSocketServer } = require('ws')

const { db } = require('./db')
const hub = require('./hub')

const PORT = Number(process.env.PORT || 8080)
const HOST = process.env.HOST || '0.0.0.0'

/** 邀请码有效期：15 分钟。 */
const CODE_TTL_MS = 15 * 60 * 1000

/** 单张纸条的涂鸦上限（base64 后）。720px 的手写图通常远小于这个值。 */
const MAX_DOODLE_BASE64 = 6 * 1024 * 1024

const app = express()

// 涂鸦以 base64 传，体积比纯文字大得多，这里放宽 body 限制。
app.use(express.json({ limit: '8mb' }))

/**
 * 网页版客户端。
 *
 * 和 Android 端共用同一套接口，所以手机和浏览器可以互相配对收发，
 * 浏览器端不需要安装任何东西就能当测试对端。
 */
app.use(express.static(path.join(__dirname, '..', 'public')))

function newId(prefix) {
  return `${prefix}_${crypto.randomBytes(9).toString('hex')}`
}

function newToken() {
  return crypto.randomBytes(24).toString('hex')
}

// ==================== 账号密码 ====================

/**
 * 用 scrypt 派生密码散列。
 *
 * 选 scrypt 而不是 sha256：它是刻意设计成"慢"的算法，
 * 能显著提高离线爆破成本。Node 内置 crypto 支持，不需要第三方库。
 *
 * 每个用户独立随机 salt，防止彩虹表。
 */
function hashPassword(password, salt) {
  return crypto.scryptSync(password, salt, 64).toString('hex')
}

function makePasswordRecord(password) {
  const salt = crypto.randomBytes(16).toString('hex')
  return { salt, hash: hashPassword(password, salt) }
}

/** 恒定时间比较，避免通过响应时间推断密码。 */
function verifyPassword(password, salt, expectedHash) {
  const actual = hashPassword(password, salt)
  const a = Buffer.from(actual, 'hex')
  const b = Buffer.from(expectedHash, 'hex')
  if (a.length !== b.length) return false
  return crypto.timingSafeEqual(a, b)
}

/** 用户名规范化：小写、去空格、限制字符集。 */
function normalizeUsername(raw) {
  return String(raw || '').trim().toLowerCase()
}

function validUsername(u) {
  // 3~20 位字母数字下划线，允许中文用户名
  return /^[a-z0-9_]{3,20}$/.test(u) || /^[\u4e00-\u9fa5]{2,12}$/.test(u)
}

// ==================== 纸条贴纸 ====================

/** 单张贴纸的 base64 上限。抠好的图通常几十 KB，2MB 足够宽松。 */
const MAX_STICKER_BASE64 = 2 * 1024 * 1024

/** 一张纸条最多贴几张，避免被拿来当图床。 */
const MAX_STICKERS_PER_NOTE = 12

/**
 * 校验并规范化客户端传来的贴纸数组。
 *
 * 为什么要逐个字段挑而不是整体透传：
 * 客户端传什么都存什么的话，别人构造一个超大 JSON 就能把库撑爆。
 * 这里只保留已知字段、逐个限制长度，多余的一律丢弃。
 *
 * @returns {{ok: true, value: string|null} | {ok: false, error: string}}
 */
function sanitizeStickers(raw) {
  if (raw == null) return { ok: true, value: null }
  if (!Array.isArray(raw)) return { ok: false, error: 'stickers must be an array' }
  if (raw.length === 0) return { ok: true, value: null }
  if (raw.length > MAX_STICKERS_PER_NOTE) {
    return { ok: false, error: `too many stickers (max ${MAX_STICKERS_PER_NOTE})` }
  }

  const out = []
  let totalBytes = 0

  for (const s of raw) {
    if (!s || typeof s !== 'object') continue

    const data = typeof s.data === 'string' ? s.data : ''
    if (!data) continue   // 没图的贴纸没有意义，丢掉

    totalBytes += data.length
    if (totalBytes > MAX_STICKER_BASE64) {
      return { ok: false, error: 'stickers too large' }
    }

    // 数值字段做范围钳制，防止客户端传 NaN / 极端值把接收方画崩
    const num = (v, lo, hi, dflt) => {
      const n = Number(v)
      if (!Number.isFinite(n)) return dflt
      return Math.min(hi, Math.max(lo, n))
    }

    out.push({
      id: String(s.id || '').slice(0, 40),
      cx: num(s.cx, 0, 1, 0.5),
      cy: num(s.cy, 0, 1, 0.5),
      widthRatio: num(s.widthRatio, 0.05, 1.2, 0.3),
      rotation: num(s.rotation, -360, 360, 0),
      data,
      fromName: String(s.fromName || '').slice(0, 24),
    })
  }

  return { ok: true, value: out.length ? JSON.stringify(out) : null }
}

/** 从数据库里读出的贴纸 JSON 字符串 → 数组，供下发。 */
function parseStickers(raw) {
  if (!raw) return []
  try {
    const arr = JSON.parse(raw)
    return Array.isArray(arr) ? arr : []
  } catch {
    return []
  }
}

/** 校验 Bearer token，成功返回用户行，失败返回 null。 */
function auth(req) {
  const header = req.get('authorization') || ''
  const m = header.match(/^Bearer\s+(.+)$/i)
  if (!m) return null
  return db.prepare('SELECT * FROM users WHERE token = ?').get(m[1].trim()) || null
}

/** 统一的鉴权中间件。 */
function requireAuth(req, res, next) {
  const user = auth(req)
  if (!user) return res.status(401).json({ error: 'unauthorized' })
  req.user = user
  next()
}

app.get('/api/health', (req, res) => {
  res.json({ ok: true, ts: Date.now() })
})

/**
 * 注册设备。Demo 阶段不做手机号/密码，直接发一个 token，
 * 客户端把它存本地当身份即可。
 */
app.post('/api/register', (req, res) => {
  const nickname = String(req.body?.nickname || '').trim().slice(0, 24) || '匿名纸条'
  const userId = newId('u')
  const token = newToken()

  db.prepare(
    'INSERT INTO users (user_id, nickname, token, created_at) VALUES (?, ?, ?, ?)'
  ).run(userId, nickname, token, Date.now())

  res.json({ userId, token, nickname })
})

/**
 * 账号注册。
 *
 * username 是稳定标识，登录后永远拿到同一个 userId，
 * 所以配对关系和收到的纸条都跟着账号走。
 * nickname 只是显示名，之后可以随便改。
 */
app.post('/api/auth/register', (req, res) => {
  const username = normalizeUsername(req.body?.username)
  const password = String(req.body?.password || '')
  const nickname = String(req.body?.nickname || '').trim().slice(0, 24) || username

  if (!validUsername(username)) {
    return res.status(400).json({ error: '用户名需为 3~20 位字母/数字/下划线，或 2~12 位中文' })
  }
  if (password.length < 6) {
    return res.status(400).json({ error: '密码至少 6 位' })
  }

  const exists = db.prepare('SELECT 1 FROM users WHERE username = ?').get(username)
  if (exists) {
    return res.status(409).json({ error: '这个用户名已经被注册了' })
  }

  const userId = newId('u')
  const token = newToken()
  const { salt, hash } = makePasswordRecord(password)

  db.prepare(
    'INSERT INTO users (user_id, nickname, token, created_at, username, password_hash, password_salt) ' +
      'VALUES (?, ?, ?, ?, ?, ?, ?)'
  ).run(userId, nickname, token, Date.now(), username, hash, salt)

  res.json({ userId, token, nickname, username })
})

/**
 * 登录。
 *
 * 每次登录换发一个新 token（旧设备上的 token 随之失效）——
 * 简单起见不做多设备会话管理，但足以避免 token 长期不变的风险。
 */
app.post('/api/auth/login', (req, res) => {
  const username = normalizeUsername(req.body?.username)
  const password = String(req.body?.password || '')

  const user = db.prepare('SELECT * FROM users WHERE username = ?').get(username)
  // 不区分"用户不存在"和"密码错误"，避免被用来枚举用户名
  if (!user || !user.password_hash || !user.password_salt) {
    return res.status(401).json({ error: '用户名或密码不对' })
  }
  if (!verifyPassword(password, user.password_salt, user.password_hash)) {
    return res.status(401).json({ error: '用户名或密码不对' })
  }

  const token = newToken()
  db.prepare('UPDATE users SET token = ? WHERE user_id = ?').run(token, user.user_id)

  res.json({
    userId: user.user_id,
    token,
    nickname: user.nickname,
    username: user.username,
  })
})

/** 改显示名。账号不变，所以配对关系、纸条全部保留。 */
app.post('/api/auth/nickname', requireAuth, (req, res) => {
  const nickname = String(req.body?.nickname || '').trim().slice(0, 24)
  if (!nickname) return res.status(400).json({ error: '昵称不能为空' })

  db.prepare('UPDATE users SET nickname = ? WHERE user_id = ?').run(nickname, req.user.user_id)
  res.json({ nickname })
})

/** 当前账号信息。用来在设置页显示"已登录为 xxx"。 */
app.get('/api/auth/me', requireAuth, (req, res) => {
  res.json({
    userId: req.user.user_id,
    nickname: req.user.nickname,
    username: req.user.username || null,
  })
})

/**
 * 生成邀请码。
 *
 * 默认随机 6 位数字；也允许客户端自定义（"两个人约定同一个数字"的场景），
 * 自定义时限制为 4~6 位数字，避免太短的码被轻易撞中。
 */
app.post('/api/pair/code', requireAuth, (req, res) => {
  const custom = String(req.body?.code || '').trim()
  let code

  if (custom) {
    if (!/^\d{4,6}$/.test(custom)) {
      return res.status(400).json({ error: 'code must be 4-6 digits' })
    }
    code = custom
  } else {
    // 避免与未过期的码冲突
    for (let i = 0; i < 20; i++) {
      const candidate = String(crypto.randomInt(100000, 1000000))
      const exists = db
        .prepare('SELECT 1 FROM pair_codes WHERE code = ? AND expires_at > ?')
        .get(candidate, Date.now())
      if (!exists) {
        code = candidate
        break
      }
    }
    if (!code) return res.status(500).json({ error: 'cannot allocate code' })
  }

  const expiresAt = Date.now() + CODE_TTL_MS
  // 同一个码被重复使用时覆盖旧的
  db.prepare(
    'INSERT INTO pair_codes (code, owner_id, expires_at) VALUES (?, ?, ?) ' +
      'ON CONFLICT(code) DO UPDATE SET owner_id = excluded.owner_id, expires_at = excluded.expires_at'
  ).run(code, req.user.user_id, expiresAt)

  res.json({ code, expiresAt })
})

/**
 * 用邀请码配对。
 *
 * 配对成功后写入双向两行，并立刻通过 WebSocket 通知双方，
 * 这样对方不用手动刷新就能看到新同伴。
 */
app.post('/api/pair/join', requireAuth, (req, res) => {
  const code = String(req.body?.code || '').trim()
  if (!code) return res.status(400).json({ error: 'code required' })

  const row = db.prepare('SELECT * FROM pair_codes WHERE code = ?').get(code)
  if (!row) return res.status(404).json({ error: 'code not found' })
  if (row.expires_at < Date.now()) {
    db.prepare('DELETE FROM pair_codes WHERE code = ?').run(code)
    return res.status(410).json({ error: 'code expired' })
  }
  if (row.owner_id === req.user.user_id) {
    return res.status(400).json({ error: 'cannot pair with yourself' })
  }

  const peer = db.prepare('SELECT * FROM users WHERE user_id = ?').get(row.owner_id)
  if (!peer) return res.status(404).json({ error: 'peer not found' })

  const now = Date.now()
  const insert = db.prepare(
    'INSERT OR IGNORE INTO pairs (owner_id, peer_id, created_at) VALUES (?, ?, ?)'
  )
  // 双向各写一行，查询时只需单条件等值匹配
  const tx = db.transaction(() => {
    insert.run(req.user.user_id, peer.user_id, now)
    insert.run(peer.user_id, req.user.user_id, now)
  })
  tx()

  // 邀请码用掉即废，防止被第三人复用
  db.prepare('DELETE FROM pair_codes WHERE code = ?').run(code)

  hub.send(peer.user_id, {
    type: 'paired',
    peer: { userId: req.user.user_id, nickname: req.user.nickname },
  })
  hub.send(req.user.user_id, {
    type: 'paired',
    peer: { userId: peer.user_id, nickname: peer.nickname },
  })

  res.json({ peerId: peer.user_id, peerNickname: peer.nickname })
})

/** 我的配对列表。 */
app.get('/api/peers', requireAuth, (req, res) => {
  const rows = db
    .prepare(
      `SELECT p.peer_id AS userId, u.nickname, p.created_at AS pairedAt
         FROM pairs p JOIN users u ON u.user_id = p.peer_id
        WHERE p.owner_id = ? ORDER BY p.created_at DESC`
    )
    .all(req.user.user_id)
  res.json({ peers: rows })
})

// ==================== 群组 ====================

/** 生成 6 位群组邀请码，避免与已有群冲突。 */
function newGroupCode() {
  for (let i = 0; i < 30; i++) {
    const code = String(crypto.randomInt(100000, 1000000))
    const exists = db.prepare('SELECT 1 FROM groups WHERE code = ?').get(code)
    if (!exists) return code
  }
  throw new Error('cannot allocate group code')
}

/**
 * 创建群组。创建者自动成为第一个成员。
 *
 * 群组的存在意义是让纸条在**多人之间流动**，所以成员顺序很重要 ——
 * 接力玩法靠 order_index 决定"下一位是谁"。
 */
app.post('/api/groups', requireAuth, (req, res) => {
  const name = String(req.body?.name || '').trim().slice(0, 24) || '纸条群'
  const groupId = newId('g')
  const code = newGroupCode()
  const now = Date.now()

  const tx = db.transaction(() => {
    db.prepare(
      'INSERT INTO groups (group_id, name, code, owner_id, created_at) VALUES (?, ?, ?, ?, ?)'
    ).run(groupId, name, code, req.user.user_id, now)

    db.prepare(
      'INSERT INTO group_members (group_id, user_id, joined_at, order_index) VALUES (?, ?, ?, ?)'
    ).run(groupId, req.user.user_id, now, 0)
  })
  tx()

  res.json({ groupId, name, code, memberCount: 1 })
})

/** 用邀请码加入群组。 */
app.post('/api/groups/join', requireAuth, (req, res) => {
  const code = String(req.body?.code || '').trim()
  if (!code) return res.status(400).json({ error: 'code required' })

  const group = db.prepare('SELECT * FROM groups WHERE code = ?').get(code)
  if (!group) return res.status(404).json({ error: 'group not found' })

  const already = db
    .prepare('SELECT 1 FROM group_members WHERE group_id = ? AND user_id = ?')
    .get(group.group_id, req.user.user_id)
  if (already) {
    return res.json({ groupId: group.group_id, name: group.name, joined: false })
  }

  // 新成员排在最后：接力方向是 A→B→C→D，谁后进来谁接在队尾
  const maxOrder = db
    .prepare('SELECT COALESCE(MAX(order_index), -1) AS m FROM group_members WHERE group_id = ?')
    .get(group.group_id).m

  db.prepare(
    'INSERT INTO group_members (group_id, user_id, joined_at, order_index) VALUES (?, ?, ?, ?)'
  ).run(group.group_id, req.user.user_id, Date.now(), maxOrder + 1)

  // 通知群里其他人有新成员加入
  const members = db
    .prepare('SELECT user_id FROM group_members WHERE group_id = ?')
    .all(group.group_id)
  for (const m of members) {
    if (m.user_id === req.user.user_id) continue
    hub.send(m.user_id, {
      type: 'group_joined',
      groupId: group.group_id,
      name: group.name,
      nickname: req.user.nickname,
    })
  }

  res.json({ groupId: group.group_id, name: group.name, joined: true })
})

/** 我加入的群组列表。 */
app.get('/api/groups', requireAuth, (req, res) => {
  const rows = db
    .prepare(
      `SELECT g.group_id AS groupId, g.name, g.code,
              (SELECT COUNT(*) FROM group_members m2 WHERE m2.group_id = g.group_id) AS memberCount,
              m.order_index AS orderIndex
         FROM group_members m JOIN groups g ON g.group_id = m.group_id
        WHERE m.user_id = ?
        ORDER BY m.joined_at DESC`
    )
    .all(req.user.user_id)
  res.json({ groups: rows })
})

/**
 * 群内发纸条。
 *
 * 两种玩法：
 * - random：随机挑一个群成员，双方都不显示昵称（匿名互传）
 * - chain ：按 order_index 传给"下一位"，形成 A→B→C→D 接力
 *
 * 发送方自己指定 mode；不指定时默认 random。
 */
app.post('/api/groups/:groupId/notes', requireAuth, (req, res) => {
  const groupId = req.params.groupId
  const mode = String(req.body?.mode || 'random')
  const text = String(req.body?.text || '')
  const doodle = req.body?.doodle ? String(req.body.doodle) : null
  const rawPaper = String(req.body?.paperStyle || '').trim()
  const paperStyle = ['white', 'grid', 'cream'].includes(rawPaper) ? rawPaper : 'white'
  const landscape = req.body?.landscape === true ? 1 : 0

  if (!text.trim() && !doodle) {
    return res.status(400).json({ error: 'note is empty' })
  }
  if (doodle && doodle.length > MAX_DOODLE_BASE64) {
    return res.status(413).json({ error: 'doodle too large' })
  }

  // 必须是群成员才能发
  const me = db
    .prepare('SELECT * FROM group_members WHERE group_id = ? AND user_id = ?')
    .get(groupId, req.user.user_id)
  if (!me) return res.status(403).json({ error: 'not a group member' })

  const members = db
    .prepare(
      'SELECT user_id, order_index FROM group_members WHERE group_id = ? ORDER BY order_index ASC'
    )
    .all(groupId)

  if (members.length < 2) {
    return res.status(400).json({ error: 'group needs at least 2 members' })
  }

  // 选出收件人
  let target = null
  if (mode === 'chain') {
    // 按顺序找下一位；自己是最后一个就绕回第一个
    const idx = members.findIndex((m) => m.user_id === req.user.user_id)
    target = members[(idx + 1) % members.length]
  } else {
    // 随机挑一个「不是自己」的成员
    const others = members.filter((m) => m.user_id !== req.user.user_id)
    target = others[crypto.randomInt(0, others.length)]
  }

  const id = newId('n')
  const createdAt = Date.now()
  // 随机模式一律匿名；接力模式保留昵称，便于知道"上一位是谁"
  const anonymous = mode === 'random' ? 1 : 0

  const st = sanitizeStickers(req.body?.stickers)
  if (!st.ok) return res.status(400).json({ error: st.error })

  db.prepare(
    'INSERT INTO notes (id, from_id, to_id, text, doodle, paper_style, landscape, group_id, anonymous, chain_mode, stickers, created_at, delivered) ' +
      'VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)'
  ).run(
    id, req.user.user_id, target.user_id, text, doodle,
    paperStyle, landscape, groupId, anonymous, mode, st.value, createdAt
  )

  const note = {
    id,
    fromUserId: anonymous ? null : req.user.user_id,
    fromNickname: anonymous ? '一位群友' : req.user.nickname,
    text,
    doodle,
    paperStyle,
    landscape: landscape === 1,
    groupId,
    anonymous: anonymous === 1,
    chainMode: mode,
    stickers: st.value ? JSON.parse(st.value) : [],
    createdAt,
  }

  const deliveredNow = hub.send(target.user_id, { type: 'note', note })
  if (deliveredNow) {
    db.prepare('UPDATE notes SET delivered = 1 WHERE id = ?').run(id)
  }

  res.json({ id, delivered: deliveredNow, createdAt, mode })
})

/**
 * 发送纸条。
 *
 * 核心逻辑：先入库，再尝试实时推送。
 * 对方在线 → 立刻推 WebSocket；
 * 对方离线 → 留在库里，等它下次上线拉取。
 * 两种路径都保证纸条不丢。
 */
app.post('/api/notes', requireAuth, (req, res) => {
  const toUserId = String(req.body?.toUserId || '').trim()
  const text = String(req.body?.text || '')
  const doodle = req.body?.doodle ? String(req.body.doodle) : null
  // 纸张样式：只接受已知取值，避免客户端传入任意字符串影响渲染
  const rawPaper = String(req.body?.paperStyle || '').trim()
  const paperStyle = ['white', 'grid', 'cream'].includes(rawPaper) ? rawPaper : 'white'
  // 纸张方向：横版纸条在接收方需要不同的排版方式
  const landscape = req.body?.landscape === true ? 1 : 0

  if (!toUserId) return res.status(400).json({ error: 'toUserId required' })
  if (!text.trim() && !doodle) {
    return res.status(400).json({ error: 'note is empty' })
  }
  if (doodle && doodle.length > MAX_DOODLE_BASE64) {
    return res.status(413).json({ error: 'doodle too large' })
  }

  // 必须已配对才能发，避免被陌生人投递
  const paired = db
    .prepare('SELECT 1 FROM pairs WHERE owner_id = ? AND peer_id = ?')
    .get(req.user.user_id, toUserId)
  if (!paired) return res.status(403).json({ error: 'not paired' })

  const id = newId('n')
  const createdAt = Date.now()

  // 校验贴纸（逐字段过滤，不接受客户端原样透传）
  const st = sanitizeStickers(req.body?.stickers)
  if (!st.ok) return res.status(400).json({ error: st.error })

  db.prepare(
    'INSERT INTO notes (id, from_id, to_id, text, doodle, paper_style, landscape, stickers, created_at, delivered) ' +
      'VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0)'
  ).run(
    id, req.user.user_id, toUserId, text, doodle,
    paperStyle, landscape, st.value, createdAt
  )

  const note = {
    id,
    fromUserId: req.user.user_id,
    fromNickname: req.user.nickname,
    text,
    doodle,
    paperStyle,
    landscape: landscape === 1,
    stickers: st.value ? JSON.parse(st.value) : [],
    createdAt,
  }

  const deliveredNow = hub.send(toUserId, { type: 'note', note })
  if (deliveredNow) {
    db.prepare('UPDATE notes SET delivered = 1 WHERE id = ?').run(id)
  }

  res.json({ id, delivered: deliveredNow, createdAt })
})

/**
 * 拉取未读纸条。
 *
 * 客户端每次启动、以及 WebSocket 重连成功后都应调用一次，
 * 用来补齐离线期间错过的纸条。
 */
app.get('/api/notes/pending', requireAuth, (req, res) => {
  const rows = db
    .prepare(
      /*
       * 匿名纸条在 SQL 层就把身份抹掉。
       *
       * 不能等到 JS 里再替换：一旦真实昵称被查出来，
       * 后续任何一处忘记处理就会泄露身份。在源头抹除更安全。
       */
      `SELECT n.id,
              CASE WHEN n.anonymous = 1 THEN NULL ELSE n.from_id END AS fromUserId,
              CASE WHEN n.anonymous = 1 THEN '一位群友' ELSE u.nickname END AS fromNickname,
              n.text, n.doodle, n.paper_style AS paperStyle,
              n.landscape AS landscape, n.stickers AS stickersJson,
              n.group_id AS groupId,
              n.anonymous AS anonymous, n.chain_mode AS chainMode,
              n.created_at AS createdAt
         FROM notes n JOIN users u ON u.user_id = n.from_id
        WHERE n.to_id = ? AND n.delivered = 0
        ORDER BY n.created_at ASC LIMIT 50`
    )
    .all(req.user.user_id)

  if (rows.length > 0) {
    /*
     * 这里**不再**直接标记已送达。
     *
     * 原来是一边返回一边标记，但客户端那时候只取最后一张
     * （见客户端 pullPending 的说明），结果另外几张被永久丢掉 ——
     * 服务端以为送出去了，客户端根本没收到，谁都不会再发。
     *
     * 现在改成等客户端处理完发 ACK 回来才标记。客户端没确认的
     * 会一直留在待发队列里，下次拉取还在。
     */
  }

  // SQLite 存的是 0/1，转成布尔再下发，前端才能直接用
  res.json({
    notes: rows.map((r) => ({
      ...r,
      landscape: r.landscape === 1,
      anonymous: r.anonymous === 1,
      stickers: parseStickers(r.stickersJson),
      stickersJson: undefined,
    })),
  })
})

const server = http.createServer(app)

/**
 * WebSocket 接入。
 *
 * 用 query 里的 token 认证（WebSocket 握手不方便带自定义 Header）。
 * 连上后先推送一次未读纸条，覆盖掉服务重启或断线期间的积压。
 */
const wss = new WebSocketServer({ server, path: '/ws' })

wss.on('connection', (ws, req) => {
  let userId = null
  try {
    const url = new URL(req.url, 'http://localhost')
    const token = url.searchParams.get('token') || ''
    const user = db.prepare('SELECT * FROM users WHERE token = ?').get(token)
    if (!user) {
      ws.close(4001, 'invalid token')
      return
    }
    userId = user.user_id
    hub.add(userId, ws)

    // 心跳：客户端每 30 秒发一次 ping，服务端回 pong，
    // 用来及时发现半开连接（移动网络下很常见）。
    ws.isAlive = true
    ws.on('pong', () => {
      ws.isAlive = true
    })
    ws.on('message', (raw) => {
      let msg = null
      try {
        msg = JSON.parse(raw.toString())
      } catch {
        return
      }
      if (msg?.type === 'ping') {
        ws.isAlive = true
        ws.send(JSON.stringify({ type: 'pong', ts: Date.now() }))
        return
      }

      /*
       * 客户端确认收到纸条。
       *
       * 只有收到 ACK 才把 delivered 置 1 —— 这样"服务端已发出"
       * 和"客户端确实拿到了"是两件事，中途断线不会丢消息。
       *
       * 只允许确认**发给自己的**纸条，避免有人拿别人的 id 乱刷。
       */
      if (msg?.type === 'ack' && Array.isArray(msg.ids) && msg.ids.length > 0) {
        const ids = msg.ids.filter((x) => typeof x === 'string').slice(0, 100)
        if (ids.length > 0) {
          const mark = db.prepare(
            'UPDATE notes SET delivered = 1 WHERE id = ? AND to_id = ?'
          )
          const tx = db.transaction(() => ids.forEach((id) => mark.run(id, userId)))
          try {
            tx()
          } catch (err) {
            console.error('[ws] ack failed', err)
          }
        }
      }
    })
    ws.on('close', () => hub.remove(userId, ws))
    ws.on('error', () => hub.remove(userId, ws))

    ws.send(JSON.stringify({ type: 'ready', userId, ts: Date.now() }))

    // 补发离线纸条
    const rows = db
      .prepare(
        `SELECT n.id,
                CASE WHEN n.anonymous = 1 THEN NULL ELSE n.from_id END AS fromUserId,
                CASE WHEN n.anonymous = 1 THEN '一位群友' ELSE u.nickname END AS fromNickname,
                n.text, n.doodle, n.paper_style AS paperStyle,
                n.landscape AS landscape, n.stickers AS stickersJson,
                n.group_id AS groupId,
                n.anonymous AS anonymous, n.chain_mode AS chainMode,
                n.created_at AS createdAt
           FROM notes n JOIN users u ON u.user_id = n.from_id
          WHERE n.to_id = ? AND n.delivered = 0
          ORDER BY n.created_at ASC LIMIT 50`
      )
      .all(userId)
    if (rows.length > 0) {
      // 同样等客户端 ACK 再标记，见上面 ack 分支的说明
      for (const note of rows) {
        ws.send(
          JSON.stringify({
            type: 'note',
            note: {
              ...note,
              landscape: note.landscape === 1,
              anonymous: note.anonymous === 1,
              stickers: parseStickers(note.stickersJson),
              stickersJson: undefined,
            },
          })
        )
      }
    }
  } catch (err) {
    console.error('[ws] connection error', err)
    if (userId) hub.remove(userId, ws)
  }
})

// 定期清理死连接与过期邀请码
const sweep = setInterval(() => {
  for (const ws of wss.clients) {
    if (ws.isAlive === false) {
      ws.terminate()
      continue
    }
    ws.isAlive = false
    try {
      ws.ping()
    } catch {
      /* ignore */
    }
  }
  db.prepare('DELETE FROM pair_codes WHERE expires_at < ?').run(Date.now())
}, 30000)
sweep.unref?.()

server.listen(PORT, HOST, () => {
  console.log(`[notedrop] listening on http://${HOST}:${PORT}`)
})

function shutdown() {
  clearInterval(sweep)
  wss.close()
  server.close(() => process.exit(0))
  setTimeout(() => process.exit(0), 3000).unref?.()
}
process.on('SIGINT', shutdown)
process.on('SIGTERM', shutdown)
