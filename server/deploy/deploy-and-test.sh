#!/usr/bin/env bash
# 部署服务端更新并跑群组测试。
# 用法：bash /tmp/deploy-and-test.sh
set -e

echo "==== 1. 拷贝文件 ===="
sudo cp /tmp/db.js /tmp/index.js /srv/slipnote/src/
sudo cp /tmp/groups.js /srv/slipnote/test/ 2>/dev/null || true
sudo chown -R sysadmin:sysadmin /srv/slipnote
echo "done"

echo "==== 2. 重启服务 ===="
sudo systemctl restart slipnote
sleep 3
echo "status: $(systemctl is-active slipnote)"

echo "==== 3. 数据库迁移日志 ===="
sudo journalctl -u slipnote -n 30 --no-pager | grep -i migration || echo "(本次无新迁移)"

echo "==== 4. 健康检查 ===="
curl -s --max-time 8 http://127.0.0.1:8090/api/health
echo

echo "==== 5. 群组端到端测试 ===="
cd /srv/slipnote
BASE=http://127.0.0.1:8090 node test/groups.js 2>&1 | grep -v ExperimentalWarning | grep -v trace-warnings
