/**
 * 生产库诊断脚本。
 *
 * 用来回答一个关键问题：纸条到底有没有到达服务器。
 * - notes 表有记录 → 客户端发出去了，问题在接收端
 * - notes 表没记录 → 客户端根本没发（走了"只存本地"的分支）
 *
 * 用法：node deploy/inspect.js
 */
'use strict'

const path = require('path')
const { db } = require(path.join(__dirname, '..', 'src', 'db'))

function nameOf(id) {
  const u = db.prepare('SELECT nickname FROM users WHERE user_id = ?').get(id)
  return u ? u.nickname : '(已不存在)'
}

console.log('=== 用户（按注册时间倒序，最多 12 个）===')
const users = db
  .prepare('SELECT user_id, nickname, created_at FROM users ORDER BY created_at DESC LIMIT 12')
  .all()
for (const u of users) {
  const pairCount = db
    .prepare('SELECT COUNT(*) AS c FROM pairs WHERE owner_id = ?')
    .get(u.user_id).c
  const t = new Date(u.created_at).toLocaleString('zh-CN')
  console.log(`  ${u.user_id}  「${u.nickname}」  配对 ${pairCount} 人  ${t}`)
}

console.log('')
console.log('=== 配对关系（解析成昵称）===')
const pairs = db
  .prepare('SELECT owner_id, peer_id, created_at FROM pairs ORDER BY created_at DESC LIMIT 12')
  .all()
if (pairs.length === 0) console.log('  (无)')
for (const p of pairs) {
  console.log(`  「${nameOf(p.owner_id)}」 <-> 「${nameOf(p.peer_id)}」`)
}

console.log('')
console.log('=== 纸条（最近 15 条）===')
const notes = db
  .prepare(
    `SELECT id, from_id, to_id, text, doodle, delivered, created_at
       FROM notes ORDER BY created_at DESC LIMIT 15`
  )
  .all()

if (notes.length === 0) {
  console.log('  (无任何纸条 — 说明纸条根本没到达服务器)')
} else {
  for (const n of notes) {
    const t = new Date(n.created_at).toLocaleString('zh-CN')
    const snippet = (n.text || '').slice(0, 16).replace(/\n/g, ' ')
    const hasDoodle = n.doodle ? '有涂鸦' : '无涂鸦'
    console.log(
      `  ${t}  「${nameOf(n.from_id)}」→「${nameOf(n.to_id)}」  ` +
        `delivered=${n.delivered}  ${hasDoodle}  "${snippet}"`
    )
  }
}

const total = db.prepare('SELECT COUNT(*) AS c FROM notes').get().c
console.log('')
console.log(`=== 纸条总数：${total} ===`)
