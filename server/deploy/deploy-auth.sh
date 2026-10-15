#!/usr/bin/env bash
# 部署账号系统更新并跑回归测试。
# 用法：bash /tmp/deploy-auth.sh
set -e

echo "==== 1. 拷贝文件 ===="
sudo cp /tmp/db.js /tmp/index.js /srv/slipnote/src/
sudo cp /tmp/auth.js /tmp/groups.js /srv/slipnote/test/ 2>/dev/null || true
sudo chown -R sysadmin:sysadmin /srv/slipnote
echo "done"

echo "==== 2. 重启服务 ===="
sudo systemctl restart slipnote
sleep 3
echo "status: $(systemctl is-active slipnote)"

echo "==== 3. 数据库迁移日志 ===="
sudo journalctl -u slipnote -n 40 --no-pager | grep -i "migration" | tail -8 || echo "(无新迁移)"

echo "==== 4. 健康检查 ===="
curl -s --max-time 8 http://127.0.0.1:8090/api/health
echo

echo "==== 5. 账号系统测试 ===="
cd /srv/slipnote
BASE=http://127.0.0.1:8090 node test/auth.js 2>&1 | grep -v Experimental | grep -v trace-warnings | tail -22

echo
echo "==== 6. 一对一回归 ===="
BASE=http://127.0.0.1:8090 node test/e2e.js 2>&1 | grep -v Experimental | grep -v trace-warnings | tail -6

echo
echo "==== 7. 群组回归 ===="
BASE=http://127.0.0.1:8090 node test/groups.js 2>&1 | grep -v Experimental | grep -v trace-warnings | tail -6

echo
echo "==== 部署完成 ===="
