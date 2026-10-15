#!/usr/bin/env bash
# 一键安装：写 systemd 服务 + 挂 nginx 反代 + 启动验证
# 用法：bash install.sh
set -euo pipefail

BACKUP_DIR=/etc/nginx/sites-available
STAMP=$(date +%Y%m%d-%H%M%S)

echo "==== 1. 安装 systemd 服务 ===="
sudo cp /tmp/slipnote-deploy/deploy/slipnote.service /etc/systemd/system/slipnote.service
sudo systemctl daemon-reload
sudo systemctl enable slipnote
echo "服务已注册"

echo "==== 2. 接入 nginx（先备份）===="
sudo cp "$BACKUP_DIR/rks" "$BACKUP_DIR/rks.backup-$STAMP"
echo "已备份为 rks.backup-$STAMP"

# 把 snippet 文件放到 nginx 可 include 的位置
sudo mkdir -p /etc/nginx/snippets
sudo cp /tmp/slipnote-deploy/deploy/nginx-slipnote.conf /etc/nginx/snippets/slipnote.conf

# 在 rks 站点的 443 server 块里插入 include。
# 用 python 精确插入到最后一个 } 之前，避免破坏原有配置结构。
sudo python3 - "$BACKUP_DIR/rks" <<'PYEOF'
import re
import sys

path = sys.argv[1]
with open(path, encoding='utf-8') as f:
    src = f.read()

if '/etc/nginx/snippets/slipnote.conf' in src:
    print('nginx 配置里已存在 include，跳过')
    sys.exit(0)

# 只在 HTTPS(443) 的 server 块里加，第一个 server 块就是它
first = src.find('server {')
if first < 0:
    raise SystemExit('找不到 server 块')

snippet = (
    '\n    # ---- SlipNote 服务（由 install.sh 添加）----\n'
    '    include /etc/nginx/snippets/slipnote.conf;\n'
)

# 从第一个 server 块开始找到与它配对的右花括号
idx = src.find('{', first)
depth = 0
end = None
for i in range(idx, len(src)):
    ch = src[i]
    if ch == '{':
        depth += 1
    elif ch == '}':
        depth -= 1
        if depth == 0:
            end = i
            break

if end is None:
    raise SystemExit('server 块花括号不匹配')

out = src[:end] + snippet + src[end:]
with open(path, 'w', encoding='utf-8') as f:
    f.write(out)
print('已在 443 server 块内插入 SlipNote 反代配置')
PYEOF

echo "==== 3. 校验 nginx 配置 ===="
sudo nginx -t

echo "==== 4. 启动服务 ===="
sudo systemctl restart slipnote
sleep 2
sudo systemctl reload nginx

echo "==== 5. 状态确认 ===="
systemctl is-active slipnote && echo "slipnote: active"
sudo ss -tlnp | grep 8090 || echo "警告：8090 未监听"
echo "本机直连测试："
curl -s --max-time 10 http://127.0.0.1:8090/api/health || echo "健康检查失败"
echo
echo "==== 安装完成 ===="
