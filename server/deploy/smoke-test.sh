#!/usr/bin/env bash
# 部署后的冒烟测试：确认依赖、数据库驱动、服务启动都正常
set -u

cd /srv/slipnote || exit 1

echo "=== 依赖检查 ==="
ls node_modules | head -5

echo "=== node:sqlite 可用性 ==="
node -e "const {DatabaseSync} = require('node:sqlite'); const d = new DatabaseSync(':memory:'); d.exec('CREATE TABLE t(a)'); console.log('node:sqlite OK')"

echo "=== 启动测试（3 秒后自动结束）==="
PORT=8090 timeout 3 node src/index.js 2>&1 | head -10
echo "(启动测试结束)"
