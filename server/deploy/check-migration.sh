#!/usr/bin/env bash
# 检查生产库的迁移状态与字段是否齐全
set -u
cd /srv/slipnote || exit 1

echo "=== notes 表字段 ==="
node -e "
const { db } = require('./src/db');
const cols = db.prepare('PRAGMA table_info(notes)').all();
console.log(cols.map(c => c.name).join(', '));
" 2>&1 | grep -v ExperimentalWarning | grep -v trace-warnings

echo "=== 健康检查 ==="
curl -s --max-time 8 http://127.0.0.1:8090/api/health
echo
