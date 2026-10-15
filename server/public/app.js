/**
 * 小纸条网页版。
 *
 * 用的是和 Android 端**完全相同的后端接口**，
 * 所以网页和手机可以互相配对、互相收发 —— 这正是它作为测试端的意义。
 *
 * 身份存在 localStorage：首次使用生成一个匿名身份（userId + token）。
 * 清掉浏览器数据就等于换了一台新设备。
 */

(() => {
  'use strict'

  /**
   * 页面挂在 nginx 的子路径 /notedrop/ 下（不是站点根目录），
   * 所以 api 和 ws 的地址都要带上这个前缀。
   *
   * 从 pathname 推导而不是写死，这样本地直连 8090 根路径也能用。
   * 只剥掉末尾的 "/" 或 "/index.html"，不剥目录名 ——
   * 否则访问 /notedrop（不带斜杠）时会把整个前缀也吃掉。
   */
  const API = location.pathname.replace(/\/(index\.html)?$/, '')

  const $ = (id) => document.getElementById(id)

  const el = {
    conn: $('conn'), dot: $('dot'), connText: $('connText'),
    onboard: $('onboard'), nickname: $('nickname'), btnRegister: $('btnRegister'),
    app: $('app'),
    myName: $('myName'), code: $('code'),
    btnCreate: $('btnCreate'), btnJoin: $('btnJoin'), pairHint: $('pairHint'),
    peers: $('peers'),
    toWhom: $('toWhom'),
    text: $('text'), pad: $('pad'), paper: $('paper'),
    paperPicker: $('paperPicker'), toolPicker: $('toolPicker'),
    btnOrientation: $('btnOrientation'),
    btnWidth: $('btnWidth'), swatches: $('swatches'),
    btnUndo: $('btnUndo'), btnClear: $('btnClear'), btnSend: $('btnSend'),
    inbox: $('inbox'), lastTime: $('lastTime'),
    btnFav: $('btnFav'), favList: $('favList'), favCount: $('favCount'),
    btnLogout: $('btnLogout'),
    toast: $('toast'),
  }

  const state = {
    userId: null,
    token: null,
    nickname: '',
    peers: [],
    peerId: null,
    ws: null,
    reconnectTimer: null,
    backoff: 1000,
    lastNote: null,
    paper: 'white',
    landscape: false,
  }

  /** 与 Android 端保持一致的调色板。 */
  const PALETTE = [
    { name: '墨黑', value: '#2B2B2B' },
    { name: '靛蓝', value: '#2E5C8A' },
    { name: '松绿', value: '#3E7A4E' },
    { name: '琥珀', value: '#C8892F' },
    { name: '朱红', value: '#B4443A' },
    { name: '紫', value: '#7A4E8A' },
  ]

  const WIDTHS = [
    { label: '细', value: 1.6 },
    { label: '偏细', value: 2.6 },
    { label: '中', value: 3.6 },
    { label: '粗', value: 5.2 },
    { label: '很粗', value: 8 },
  ]

  // ---------- 小工具 ----------

  let toastTimer = null
  function toast(msg) {
    el.toast.textContent = msg
    el.toast.hidden = false
    clearTimeout(toastTimer)
    toastTimer = setTimeout(() => { el.toast.hidden = true }, 2200)
  }

  function setConn(status) {
    // status: 'on' | 'off' | 'wait'
    el.dot.className = 'dot ' + (status === 'on' ? 'on' : status === 'off' ? 'off' : '')
    el.connText.textContent =
      status === 'on' ? '已连接' : status === 'off' ? '已断开' : '连接中…'
  }

  async function api(path, { method = 'GET', body, auth = true } = {}) {
    const headers = {}
    if (body) headers['Content-Type'] = 'application/json'
    if (auth && state.token) headers['Authorization'] = 'Bearer ' + state.token

    const res = await fetch(API + path, {
      method,
      headers,
      body: body ? JSON.stringify(body) : undefined,
    })
    const json = await res.json().catch(() => ({}))
    if (!res.ok) throw new Error(json.error || `HTTP ${res.status}`)
    return json
  }

  // ---------- 身份 ----------

  const LS_KEY = 'notedrop.web.identity'

  function loadIdentity() {
    try {
      const raw = localStorage.getItem(LS_KEY)
      if (!raw) return false
      const obj = JSON.parse(raw)
      if (!obj.userId || !obj.token) return false
      Object.assign(state, {
        userId: obj.userId,
        token: obj.token,
        nickname: obj.nickname || '',
        peerId: obj.peerId || null,
      })
      return true
    } catch {
      return false
    }
  }

  function saveIdentity() {
    localStorage.setItem(LS_KEY, JSON.stringify({
      userId: state.userId,
      token: state.token,
      nickname: state.nickname,
      peerId: state.peerId,
    }))
  }

  // ---------- 进入主界面 ----------

  function enterApp() {
    el.onboard.hidden = true
    el.app.hidden = false
    el.myName.textContent = state.nickname
    // 此时画布才真正显示出来，尺寸才算得准
    requestAnimationFrame(() => pad.resize())
    connect()
    refreshPeers()
    pullPending()
    renderFavorites()
  }

  // ---------- 纸张 / 画笔 选择器 ----------

  function setupPickers() {
    // 纸张
    el.paperPicker.querySelectorAll('.seg-btn').forEach((btn) => {
      btn.onclick = () => {
        el.paperPicker.querySelectorAll('.seg-btn').forEach((b) => b.classList.remove('active'))
        btn.classList.add('active')
        state.paper = btn.dataset.paper
        el.paper.className = 'paper ' + paperClass(state.paper)
      }
    })

    // 横竖版：切换画布形状，并把这个选择随纸条一起发出去
    el.btnOrientation.onclick = () => {
      state.landscape = !state.landscape
      el.btnOrientation.textContent = state.landscape ? '横版' : '竖版'
      el.paper.classList.toggle('paper-landscape', state.landscape)
      requestAnimationFrame(() => pad.resize())
    }

    // 笔 / 橡皮
    el.toolPicker.querySelectorAll('.seg-btn').forEach((btn) => {
      btn.onclick = () => {
        el.toolPicker.querySelectorAll('.seg-btn').forEach((b) => b.classList.remove('active'))
        btn.classList.add('active')
        pad.setTool(btn.dataset.tool)
      }
    })

    // 粗细
    el.btnWidth.onclick = () => {
      const label = pad.cycleWidth()
      el.btnWidth.textContent = '粗细：' + label
    }
    el.btnWidth.textContent = '粗细：' + pad.widthLabel()

    // 颜色
    PALETTE.forEach((c, i) => {
      const dot = document.createElement('button')
      dot.className = 'swatch' + (i === 0 ? ' active' : '')
      dot.style.background = c.value
      dot.title = c.name
      dot.setAttribute('aria-label', '颜色 ' + c.name)
      dot.onclick = () => {
        el.swatches.querySelectorAll('.swatch').forEach((s) => s.classList.remove('active'))
        dot.classList.add('active')
        pad.setColor(c.value)
        // 选颜色时自动切回画笔：正在擦除时选颜色几乎一定是想继续画
        pad.setTool('pen')
        el.toolPicker.querySelectorAll('.seg-btn').forEach((b) => {
          b.classList.toggle('active', b.dataset.tool === 'pen')
        })
      }
      el.swatches.appendChild(dot)
    })
  }

  // ---------- 配对 ----------

  async function register() {
    const nick = el.nickname.value.trim() || '纸条用户' + Math.floor(Math.random() * 9000 + 1000)
    el.btnRegister.disabled = true
    try {
      const res = await api('/api/register', {
        method: 'POST',
        body: { nickname: nick },
        auth: false,
      })
      state.userId = res.userId
      state.token = res.token
      state.nickname = res.nickname
      saveIdentity()
      enterApp()
      toast('欢迎，' + res.nickname)
    } catch (e) {
      toast('注册失败：' + e.message)
    } finally {
      el.btnRegister.disabled = false
    }
  }

  async function createCode() {
    const custom = el.code.value.trim()
    try {
      const res = await api('/api/pair/code', {
        method: 'POST',
        body: custom ? { code: custom } : {},
      })
      el.code.value = res.code
      el.pairHint.textContent = `邀请码 ${res.code}，15 分钟内有效。把这串数字发给对方，让 TA 填进来点「配对」。`
      toast('邀请码：' + res.code)
    } catch (e) {
      toast('生成失败：' + e.message)
    }
  }

  async function joinPair() {
    const code = el.code.value.trim()
    if (!code) return toast('先填邀请码')
    try {
      const res = await api('/api/pair/join', { method: 'POST', body: { code } })
      state.peerId = res.peerId
      saveIdentity()
      toast('已和「' + res.peerNickname + '」配对')
      el.code.value = ''
      refreshPeers()
    } catch (e) {
      toast('配对失败：' + e.message)
    }
  }

  async function refreshPeers() {
    try {
      const res = await api('/api/peers')
      state.peers = res.peers || []
      renderPeers()
    } catch (e) {
      // 静默失败：可能是 token 失效，重连时会暴露
    }
  }

  function renderPeers() {
    el.peers.innerHTML = ''
    if (state.peers.length === 0) {
      el.peers.innerHTML = '<p class="hint" style="margin:0">还没有同伴。</p>'
      el.toWhom.textContent = '无收件人'
      return
    }
    // 没选过就默认第一个
    if (!state.peerId || !state.peers.some((p) => p.userId === state.peerId)) {
      state.peerId = state.peers[0].userId
      saveIdentity()
    }

    for (const p of state.peers) {
      const row = document.createElement('div')
      row.className = 'peer' + (p.userId === state.peerId ? ' sel' : '')
      row.innerHTML =
        (p.userId === state.peerId ? '<span class="tick">●</span>' : '<span>○</span>') +
        '<span></span>'
      row.lastElementChild.textContent = p.nickname
      row.onclick = () => {
        state.peerId = p.userId
        saveIdentity()
        renderPeers()
        toast('发送给「' + p.nickname + '」')
      }
      el.peers.appendChild(row)
    }
    const sel = state.peers.find((p) => p.userId === state.peerId)
    el.toWhom.textContent = sel ? '发给：' + sel.nickname : ''
  }

  // ---------- 发送 ----------

  async function send() {
    const text = el.text.value.trim()
    const doodle = pad.exportBase64()
    if (!text && !doodle) return toast('写点什么再发吧')
    if (!state.peerId) return toast('还没有配对的人')

    el.btnSend.disabled = true
    try {
      const res = await api('/api/notes', {
        method: 'POST',
        body: {
          toUserId: state.peerId,
          text,
          doodle: doodle || undefined,
          paperStyle: state.paper,
          landscape: state.landscape,
        },
      })
      toast(res.delivered ? '已发出，对方马上就能看到' : '已发出，对方上线后能看到')
      el.text.value = ''
      pad.clearAll()
    } catch (e) {
      toast('发送失败：' + e.message)
    } finally {
      el.btnSend.disabled = false
    }
  }

  // ---------- 收件 ----------

  function showNote(note) {
    state.lastNote = note
    el.inbox.innerHTML = ''

    // 收到的纸条按发送者选的纸张样式显示
    el.inbox.className = 'paper note ' + paperClass(note.paperStyle)
    // 横版纸条在网页上也给足宽度，形状跟发送方一致
    el.inbox.classList.toggle('paper-landscape', note.landscape === true)

    if (note.text) {
      const p = document.createElement('p')
      p.className = 'body'
      p.textContent = note.text
      el.inbox.appendChild(p)
    }
    if (note.doodle) {
      const img = document.createElement('img')
      const src = note.doodle.startsWith('data:') ? note.doodle : 'data:image/png;base64,' + note.doodle
      img.src = src
      img.alt = '手写涂鸦'
      el.inbox.appendChild(img)
    }
    const from = document.createElement('div')
    from.className = 'from'
    from.textContent = (note.fromNickname || 'TA') + ' · ' + timeAgo(note.createdAt)
    el.inbox.appendChild(from)

    el.lastTime.textContent = timeAgo(note.createdAt)
    refreshFavButton()
  }

  function paperClass(style) {
    if (style === 'grid') return 'paper-grid'
    if (style === 'cream') return 'paper-cream'
    return 'paper-white'
  }

  // ---------- 收藏 ----------

  const FAV_KEY = 'notedrop.web.favorites'

  function loadFavorites() {
    try {
      const raw = localStorage.getItem(FAV_KEY)
      return raw ? JSON.parse(raw) : []
    } catch {
      return []
    }
  }

  function saveFavorites(list) {
    localStorage.setItem(FAV_KEY, JSON.stringify(list.slice(0, 200)))
  }

  function favoriteKey(note) {
    return `${note.createdAt}|${note.text || ''}|${(note.doodle || '').length}`
  }

  function refreshFavButton() {
    if (!state.lastNote) {
      el.btnFav.disabled = true
      el.btnFav.textContent = '收藏这张'
      return
    }
    const exists = loadFavorites().some((f) => f.key === favoriteKey(state.lastNote))
    el.btnFav.disabled = exists
    el.btnFav.textContent = exists ? '已收藏' : '收藏这张'
  }

  function addFavorite() {
    const note = state.lastNote
    if (!note) return
    const list = loadFavorites()
    const key = favoriteKey(note)
    if (list.some((f) => f.key === key)) return toast('这张已经在收藏夹里了')
    list.unshift({
      key,
      text: note.text || '',
      doodle: note.doodle || '',
      from: note.fromNickname || 'TA',
      createdAt: note.createdAt,
      paperStyle: note.paperStyle || 'white',
    })
    saveFavorites(list)
    renderFavorites()
    refreshFavButton()
    toast('已收藏这张纸条')
  }

  function removeFavorite(key) {
    saveFavorites(loadFavorites().filter((f) => f.key !== key))
    renderFavorites()
    refreshFavButton()
    toast('已删除')
  }

  function renderFavorites() {
    const list = loadFavorites()
    el.favCount.textContent = list.length ? list.length + ' 张' : ''
    el.favList.innerHTML = ''

    if (list.length === 0) {
      el.favList.innerHTML = '<p class="hint" style="margin:0">还没有收藏。</p>'
      return
    }

    for (const f of list) {
      const item = document.createElement('div')
      item.className = 'fav-item ' + paperClass(f.paperStyle)

      if (f.text) {
        const p = document.createElement('p')
        p.className = 'body'
        p.textContent = f.text
        item.appendChild(p)
      }
      if (f.doodle) {
        const img = document.createElement('img')
        img.src = f.doodle.startsWith('data:') ? f.doodle : 'data:image/png;base64,' + f.doodle
        img.alt = '手写涂鸦'
        item.appendChild(img)
      }

      const meta = document.createElement('div')
      meta.className = 'meta'
      const label = document.createElement('span')
      label.textContent = f.from + ' · ' + timeAgo(f.createdAt)
      const del = document.createElement('button')
      del.className = 'del'
      del.textContent = '删除'
      del.onclick = () => removeFavorite(f.key)
      meta.appendChild(label)
      meta.appendChild(del)
      item.appendChild(meta)

      el.favList.appendChild(item)
    }
  }

  function timeAgo(ts) {
    const d = Date.now() - ts
    if (d < 60000) return '刚刚'
    if (d < 3600000) return Math.floor(d / 60000) + ' 分钟前'
    if (d < 86400000) return Math.floor(d / 3600000) + ' 小时前'
    return new Date(ts).toLocaleDateString('zh-CN')
  }

  async function pullPending() {
    try {
      const res = await api('/api/notes/pending')
      const list = res.notes || []
      if (list.length > 0) showNote(list[list.length - 1])
    } catch {
      /* ignore */
    }
  }

  // ---------- WebSocket ----------

  function connect() {
    if (!state.token) return
    clearTimeout(state.reconnectTimer)
    setConn('wait')

    const proto = location.protocol === 'https:' ? 'wss:' : 'ws:'
    const url = `${proto}//${location.host}${API}/ws?token=${encodeURIComponent(state.token)}`
    const ws = new WebSocket(url)
    state.ws = ws

    ws.onopen = () => {
      setConn('on')
      state.backoff = 1000
    }

    ws.onmessage = (ev) => {
      let msg
      try { msg = JSON.parse(ev.data) } catch { return }

      if (msg.type === 'ready') {
        setConn('on')
        pullPending()
      } else if (msg.type === 'note') {
        showNote(msg.note)
        toast('收到一张新纸条')
      } else if (msg.type === 'paired') {
        refreshPeers()
        toast('「' + msg.peer.nickname + '」和你配对了')
      }
    }

    ws.onclose = () => {
      setConn('off')
      scheduleReconnect()
    }

    ws.onerror = () => {
      setConn('off')
    }
  }

  function scheduleReconnect() {
    clearTimeout(state.reconnectTimer)
    // 指数退避，最多 30 秒一次，避免网络不稳时疯狂重试
    state.reconnectTimer = setTimeout(() => {
      state.backoff = Math.min(state.backoff * 2, 30000)
      connect()
    }, state.backoff)
  }

  // ---------- 手写画板 ----------

  const pad = (() => {
    const canvas = el.pad
    const ctx = canvas.getContext('2d')
    const strokes = []
    let drawing = false
    let cur = null

    let tool = 'pen'
    let color = PALETTE[0].value
    let widthIndex = 2

    const widthOf = () => WIDTHS[widthIndex].value

    function resize() {
      // 按设备像素比放大，避免手写在高分屏上发虚
      const dpr = window.devicePixelRatio || 1
      const rect = canvas.getBoundingClientRect()
      if (rect.width === 0 || rect.height === 0) return

      // 已经匹配就不用重建 —— 否则每次 resize 事件都会清空重放，白白闪烁
      const wantW = Math.round(rect.width * dpr)
      const wantH = Math.round(rect.height * dpr)
      if (canvas.width === wantW && canvas.height === wantH) return

      // 尺寸变化会清空画布，所以先保留内容再重放
      const snapshot = strokes.map((s) => ({ points: s.points.slice(), width: s.width }))
      canvas.width = wantW
      canvas.height = wantH
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
      ctx.lineCap = 'round'
      ctx.lineJoin = 'round'
      strokes.length = 0
      snapshot.forEach((s) => {
        strokes.push(s)
        drawStroke(s)
      })
    }

    function cssColor(name) {
      return getComputedStyle(document.documentElement).getPropertyValue(name).trim() || '#3D3527'
    }

    function drawStroke(s) {
      // 橡皮擦用 destination-out 真正擦掉像素，
      // 而不是画一笔背景色 —— 后者在方格纸上会留下明显的白块
      const isEraser = s.tool === 'eraser'
      if (s.points.length < 2) {
        const p = s.points[0]
        if (!p) return
        ctx.save()
        if (isEraser) ctx.globalCompositeOperation = 'destination-out'
        ctx.beginPath()
        ctx.fillStyle = isEraser ? 'rgba(0,0,0,1)' : (s.color || color)
        ctx.arc(p.x, p.y, Math.max(s.width, 6) / 2, 0, Math.PI * 2)
        ctx.fill()
        ctx.restore()
        return
      }
      ctx.save()
      if (isEraser) {
        ctx.globalCompositeOperation = 'destination-out'
        ctx.strokeStyle = 'rgba(0,0,0,1)'
        // 橡皮比同档画笔粗一些，擦起来更顺手
        ctx.lineWidth = s.width * 2.2
      } else {
        ctx.strokeStyle = s.color || color
        ctx.lineWidth = s.width
      }
      ctx.beginPath()
      ctx.moveTo(s.points[0].x, s.points[0].y)
      for (let i = 1; i < s.points.length - 1; i++) {
        const p = s.points[i]
        const n = s.points[i + 1]
        // 中点二次贝塞尔，跟 Android 端保持一致的平滑手感
        ctx.quadraticCurveTo(p.x, p.y, (p.x + n.x) / 2, (p.y + n.y) / 2)
      }
      const last = s.points[s.points.length - 1]
      ctx.lineTo(last.x, last.y)
      ctx.stroke()
      ctx.restore()
    }

    function pos(ev) {
      const rect = canvas.getBoundingClientRect()
      return { x: ev.clientX - rect.left, y: ev.clientY - rect.top }
    }

    canvas.addEventListener('pointerdown', (ev) => {
      canvas.setPointerCapture(ev.pointerId)
      drawing = true
      cur = { points: [pos(ev)], width: widthOf(), color, tool }
      strokes.push(cur)
    })

    canvas.addEventListener('pointermove', (ev) => {
      if (!drawing || !cur) return
      cur.points.push(pos(ev))
      // 逐段重绘整条当前笔画；Web 端笔画数量少，够用
      redraw()
    })

    const finish = () => {
      if (!drawing) return
      drawing = false
      cur = null
      redraw()
    }
    canvas.addEventListener('pointerup', finish)
    canvas.addEventListener('pointercancel', finish)
    canvas.addEventListener('pointerleave', finish)

    function redraw() {
      const dpr = window.devicePixelRatio || 1
      ctx.save()
      ctx.setTransform(1, 0, 0, 1, 0, 0)
      ctx.clearRect(0, 0, canvas.width, canvas.height)
      ctx.restore()
      strokes.forEach(drawStroke)
    }

    return {
      resize,
      clearAll() {
        strokes.length = 0
        redraw()
      },
      undo() {
        strokes.pop()
        redraw()
      },
      setTool(t) { tool = t },
      setColor(c) { color = c },
      cycleWidth() {
        widthIndex = (widthIndex + 1) % WIDTHS.length
        return WIDTHS[widthIndex].label
      },
      widthLabel: () => WIDTHS[widthIndex].label,
      get isEmpty() {
        return strokes.length === 0
      },
      /** 导出 PNG 的纯 base64（不含 data: 前缀），和 Android 端格式一致。 */
      exportBase64() {
        if (strokes.length === 0) return null
        return el.pad.toDataURL('image/png').split(',')[1] || null
      },
    }
  })()

  // ---------- 事件绑定 ----------

  el.btnRegister.onclick = register
  el.nickname.addEventListener('keydown', (e) => { if (e.key === 'Enter') register() })
  el.btnCreate.onclick = createCode
  el.btnJoin.onclick = joinPair
  el.code.addEventListener('keydown', (e) => { if (e.key === 'Enter') joinPair() })
  el.btnSend.onclick = send
  el.btnUndo.onclick = () => pad.undo()
  el.btnClear.onclick = () => { pad.clearAll(); el.text.value = '' }
  el.btnFav.onclick = addFavorite
  el.btnLogout.onclick = () => {
    if (!confirm('重置后本机身份会丢失，需要重新配对。确定吗？')) return
    localStorage.removeItem(LS_KEY)
    location.reload()
  }

  window.addEventListener('resize', () => pad.resize())
  window.addEventListener('beforeunload', () => {
    if (state.ws) state.ws.close()
  })

  // ---------- 启动 ----------

  setConn('off')
  setupPickers()
  refreshFavButton()

  /*
   * 画布初始化必须等布局完成。
   *
   * 之前直接调用 pad.resize()，此时 #app 还是 hidden 状态，
   * getBoundingClientRect 量到的是初始值（300x150 的画布默认尺寸），
   * 于是 canvas 被固定成 300x150，而 CSS 显示宽度是 465px ——
   * 画出来的笔迹会被浏览器拉伸放大，看着发虚。
   *
   * requestAnimationFrame 保证在浏览器完成布局之后再量。
   */
  requestAnimationFrame(() => pad.resize())

  if (loadIdentity()) {
    enterApp()
  } else {
    el.onboard.hidden = false
    el.nickname.focus()
  }

  // 注册页切到主页时画布才真正可见，需要再量一次
  window.addEventListener('load', () => requestAnimationFrame(() => pad.resize()))
})()
