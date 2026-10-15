'use strict'

/**
 * WebSocket 连接中心。
 *
 * 一个用户可能同时有多台设备在线（手机 + 平板），所以 user → Set<ws>。
 * 推送时遍历该用户的全部连接，任一台在线就算送达。
 */
class Hub {
  constructor() {
    /** @type {Map<string, Set<import('ws').WebSocket>>} */
    this.clients = new Map()
  }

  add(userId, ws) {
    let set = this.clients.get(userId)
    if (!set) {
      set = new Set()
      this.clients.set(userId, set)
    }
    set.add(ws)
  }

  remove(userId, ws) {
    const set = this.clients.get(userId)
    if (!set) return
    set.delete(ws)
    if (set.size === 0) this.clients.delete(userId)
  }

  /** 该用户是否至少有一台设备在线。 */
  isOnline(userId) {
    const set = this.clients.get(userId)
    return !!set && set.size > 0
  }

  /** 给某个用户的所有在线设备推一条 JSON 消息，返回是否推成功。 */
  send(userId, payload) {
    const set = this.clients.get(userId)
    if (!set || set.size === 0) return false

    const raw = JSON.stringify(payload)
    let sent = false
    for (const ws of set) {
      // 1 === WebSocket.OPEN
      if (ws.readyState === 1) {
        try {
          ws.send(raw)
          sent = true
        } catch {
          // 单台设备发送失败不影响其他设备
        }
      }
    }
    return sent
  }
}

module.exports = new Hub()
