#!/usr/bin/env bash
# 部署贴纸支持并跑回归。
set -e

echo "==== 1. 拷贝文件 ===="
sudo cp /tmp/db.js /tmp/index.js /srv/notedrop/src/
sudo cp /tmp/stickers.js /srv/notedrop/test/ 2>/dev/null || true
sudo chown -R sysadmin:sysadmin /srv/notedrop
echo done

echo "==== 2. 重启 ===="
sudo systemctl restart notedrop
sleep 3
echo "status: $(systemctl is-active notedrop)"

echo "==== 3. 迁移日志 ===="
sudo journalctl -u notedrop -n 40 --no-pager | grep -i "migration" | tail -5 || echo "(无新迁移)"

echo "==== 4. 健康检查 ===="
curl -s --max-time 8 http://127.0.0.1:8090/api/health
echo

echo "==== 5. 贴纸测试 ===="
cd /srv/notedrop
BASE=http://127.0.0.1:8090 node test/stickers.js 2>&1 | grep -v Experimental | grep -v trace-warnings | tail -25

echo
echo "==== 6. 一对一回归 ===="
BASE=http://127.0.0.1:8090 node test/e2e.js 2>&1 | grep -v Experimental | grep -v trace-warnings | tail -5

echo
echo "==== 7. 群组回归 ===="
BASE=http://127.0.0.1:8090 node test/groups.js 2>&1 | grep -v Experimental | grep -v trace-warnings | tail -5

echo
echo "==== 8. 账号回归 ===="
BASE=http://127.0.0.1:8090 node test/auth.js 2>&1 | grep -v Experimental | grep -v trace-warnings | tail -5

echo
echo "==== 部署完成 ===="
