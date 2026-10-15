# 小纸条服务端

单进程 Node.js 服务，负责三件事：**用户注册**、**配对**、**纸条中转 + 实时推送**。

## 技术选型说明

| 选择 | 理由 |
| --- | --- |
| Node.js + Express | 单进程够用，不需要额外运维 |
| SQLite（better-sqlite3） | 单文件数据库，不用装 MySQL，备份=拷一个文件 |
| WebSocket | **替代四家厂商推送 SDK**，一次接入即可实时送达 |

选 WebSocket 而不是接小米/华为/OPPO/vivo 推送，是因为自己用或小范围用，
长连接的实时性反而更好，且省掉四套 SDK 的接入与维护成本。
正式发布到应用商店时再补厂商推送作为离线兜底。

## 启动

```bash
npm install
npm start
```

默认监听 `0.0.0.0:8080`，可用环境变量覆盖：

```bash
PORT=9000 DATA_DIR=/var/lib/slipnote npm start
```

## 接口

所有接口均为 JSON。除注册外，其余接口需要在 Header 带 `Authorization: Bearer <token>`。

### 1. 注册设备（匿名，无需手机号）

```
POST /api/register
{ "nickname": "小明" }

→ { "userId": "u_xxx", "token": "tk_xxx", "nickname": "小明" }
```

token 存本地即可，丢失后无法找回身份（Demo 阶段够用；正式版应加账号体系）。

### 2. 生成邀请码

```
POST /api/pair/code
Authorization: Bearer <token>

→ { "code": "4821", "expiresAt": 1791685557708 }
```

6 位数字，15 分钟有效。也可以传 `{"code":"1234"}` 自定义一个 4 位数字，
适合"两个人约定同一个数字"的场景。

### 3. 用邀请码配对

```
POST /api/pair/join
Authorization: Bearer <token>
{ "code": "4821" }

→ { "peerId": "u_yyy", "peerNickname": "小红" }
```

配对是**双向**的，配对后双方互相可见，支持多对多（可与多个用户配对）。

### 4. 发送纸条

```
POST /api/notes
Authorization: Bearer <token>
Content-Type: application/json

{
  "toUserId": "u_yyy",
  "text": "今天降温",
  "doodle": "<base64 PNG，可省略>"
}
```

服务端会立即通过 WebSocket 推给接收方的所有在线设备。
接收方离线时纸条入库，下次上线用 `GET /api/notes/pending` 拉取。

### 5. 拉取未读纸条

```
GET /api/notes/pending
Authorization: Bearer <token>

→ { "notes": [ { "id": "...", "fromUserId": "...", "fromNickname": "...", "text": "...", "doodle": "...", "createdAt": 123 } ] }
```

### 6. 配对列表

```
GET /api/peers
Authorization: Bearer <token>

→ { "peers": [ { "userId": "u_yyy", "nickname": "小红", "pairedAt": 123 } ] }
```

### 7. WebSocket

```
ws://<host>:8080/ws?token=<token>
```

服务端下发的消息：

```json
{ "type": "note", "note": { "id": "...", "fromUserId": "...", "text": "...", "doodle": "...", "createdAt": 123 } }
{ "type": "paired", "peer": { "userId": "...", "nickname": "..." } }
```

客户端只需在收到 `type == "note"` 时刷新桌面小部件即可实现"对方一发我桌面就变"。

## 数据存储

SQLite 文件默认在 `./data/slipnote.db`。

表结构：

- `users` —— 用户与 token
- `pairs` —— 配对关系（双向，存两行）
- `notes` —— 纸条，含送达状态
- `pair_codes` —— 临时邀请码

## 部署建议

```bash
# 用 pm2 常驻
npm i -g pm2
pm2 start src/index.js --name slipnote
pm2 save
```

建议在 Nginx 后面跑，并配 HTTPS —— Android 9 以上默认禁止明文 HTTP。
