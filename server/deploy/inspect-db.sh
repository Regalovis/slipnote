#!/usr/bin/env bash
# 查看生产库里的用户与纸条记录，用于定位"发不出 / 收不到"的问题
set -u
cd /srv/notedrop || exit 1

node -e "
const { db } = require('./src/db');

const users = db.prepare('SELECT user_id, nickname, created_at FROM users ORDER BY created_at DESC LIMIT 10').all();
console.log('=== 最近注册的用户 ===');
users.forEach(u => console.log('  ' + u.user_id + '  ' + u.nickname));

const pairs = db.prepare('SELECT owner_id, peer_id FROM pairs ORDER BY created_at DESC LIMIT 10').all();
console.log('=== 配对关系 ===');
if (pairs.length === 0) console.log('  (无)');
pairs.forEach(p => console.log('  ' + p.owner_id + '  ->  ' + p.peer_id));

const notes = db.prepare('SELECT id, from_id, to_id, substr(coalesce(text,\"\"),1,20) AS t, delivered, created_at FROM notes ORDER BY created_at DESC LIMIT 15').all();
console.log('=== 最近的纸条 ===');
if (notes.length === 0) console.log('  (无 — 说明纸条根本没到达服务器)');
notes.forEach(n => {
  const time = new Date(n.created_at).toLocaleString('zh-CN');
  console.log('  ' + time + '  ' + n.from_id + ' -> ' + n.to_id + '  delivered=' + n.delivered + '  \"' + n.t + '\"');
});

const stats = db.prepare('SELECT COUNT(*) AS c FROM notes').get();
console.log('=== 纸条总数: ' + stats.c + ' ===');
" 2>&1 | grep -v ExperimentalWarning | grep -v trace-warnings
