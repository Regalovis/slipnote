'use strict'

/**
 * 账号系统端到端测试。
 *
 * 重点验证用户提的那个问题：**改昵称不能丢配对**。
 * 还要验证注册/登录/改名的完整闭环，以及各种错误输入。
 *
 * 用法：BASE=http://127.0.0.1:8090 node test/auth.js
 */

const BASE = (process.env.BASE || 'http://127.0.0.1:8090').replace(/\/+$/, '')
const WS_BASE = BASE.replace(/^http/, 'ws')

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
  const json = await res.json().catch(() => ({}))
  return { status: res.status, ok: res.ok, json }
}

function assert(cond, label) {
  if (cond) console.log(`  ✓ ${label}`)
  else {
    console.error(`  ✗ ${label}`)
    throw new Error('assertion failed: ' + label)
  }
}

const rnd = Math.random().toString(36).slice(2, 7)

async function main() {
  console.log(`目标：${BASE}\n`)

  const userA = `hemao_${rnd}`
  const userB = `guoguo_${rnd}`

  console.log('[1] 注册两个账号')
  const a = await post('/api/auth/register', {
    username: userA,
    password: 'pass123456',
    nickname: '黑猫',
  })
  assert(a.ok, `注册 ${userA}，昵称「黑猫」`)
  const b = await post('/api/auth/register', {
    username: userB,
    password: 'pass123456',
    nickname: '果果',
  })
  assert(b.ok, `注册 ${userB}，昵称「果果」`)

  const tokenA = a.json.token
  const tokenB = b.json.token
  const userIdA = a.json.userId

  console.log('[2] 用户名重复应被拒绝')
  const dup = await post('/api/auth/register', {
    username: userA,
    password: 'other12345',
    nickname: '冒充',
  })
  assert(dup.status === 409, '同名注册被拒（409）')

  console.log('[3] 弱密码应被拒绝')
  const weak = await post('/api/auth/register', {
    username: `weak_${rnd}`,
    password: '123',
    nickname: '弱密码',
  })
  assert(weak.status === 400, '少于 6 位被拒（400）')

  console.log('[4] 两人配对')
  const code = await post('/api/pair/code', {}, tokenA)
  assert(code.ok && /^\d{6}$/.test(code.json.code), `黑猫生成邀请码 ${code.json.code}`)
  const join = await post('/api/pair/join', { code: code.json.code }, tokenB)
  assert(join.ok, `果果用邀请码配对成功`)

  const peersBefore = await get('/api/peers', tokenA)
  assert(peersBefore.json.peers.length === 1, `黑猫有 ${peersBefore.json.peers.length} 个配对`)

  console.log('[5] 关键验证：改昵称后配对必须还在')
  const rename = await post('/api/auth/nickname', { nickname: '黑猫改名了' }, tokenA)
  assert(rename.ok && rename.json.nickname === '黑猫改名了', '昵称已改为「黑猫改名了」')

  const me = await get('/api/auth/me', tokenA)
  assert(me.json.userId === userIdA, 'userId 完全没变（账号稳定）')
  assert(me.json.nickname === '黑猫改名了', '服务端昵称已更新')

  const peersAfter = await get('/api/peers', tokenA)
  assert(
    peersAfter.json.peers.length === 1,
    `改昵称后配对仍在（${peersAfter.json.peers.length} 个）`
  )

  console.log('[6] 关键验证：重新登录后配对依然在')
  const relogin = await post('/api/auth/login', {
    username: userA,
    password: 'pass123456',
  })
  assert(relogin.ok, '用原账号密码重新登录成功')
  assert(relogin.json.userId === userIdA, '登录拿回同一个 userId')
  assert(relogin.json.nickname === '黑猫改名了', '昵称保留了修改后的值')
  assert(relogin.json.token !== tokenA, '换发了新 token（旧的作废）')

  const peersAfterRelogin = await get('/api/peers', relogin.json.token)
  assert(
    peersAfterRelogin.json.peers.length === 1,
    '重新登录后配对全部回来'
  )

  console.log('[7] 错误密码应被拒绝')
  const bad = await post('/api/auth/login', {
    username: userA,
    password: 'wrongpassword',
  })
  assert(bad.status === 401, '错误密码被拒（401）')

  console.log('[8] 不存在的用户与错误密码返回相同信息（防枚举）')
  const noUser = await post('/api/auth/login', {
    username: `nobody_${rnd}`,
    password: 'whatever',
  })
  assert(
    noUser.status === 401 && noUser.json.error === bad.json.error,
    '两种失败的报错文案一致，无法用来枚举用户名'
  )

  console.log('[9] 旧 token 在新登录后失效')
  const oldTokenProbe = await get('/api/auth/me', tokenA)
  assert(oldTokenProbe.status === 401, '旧 token 已失效（401）')

  console.log('[10] 发纸条仍然正常（用新 token）')
  const note = await post(
    '/api/notes',
    { toUserId: b.json.userId, text: '改名后依然能发' },
    relogin.json.token
  )
  assert(note.ok, '用重新登录的 token 成功发出纸条')

  console.log('\n全部通过 ✅  账号稳定、昵称可改、配对不丢')
}

main().catch((e) => {
  console.error('\n账号测试失败 ❌', e.message)
  process.exit(1)
})
