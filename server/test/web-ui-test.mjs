/**
 * 网页端 UI 冒烟测试。
 *
 * 用无头 Chrome 真实加载页面、点按钮、发纸条，
 * 验证的不只是接口，而是"用户真的能用"。
 *
 * 用法：
 *   node test/web-ui-test.mjs https://example.com/SlipNote/
 *   node test/web-ui-test.mjs http://127.0.0.1:8090/
 */

const PAGE = process.argv[2] || 'http://127.0.0.1:8090/'
const BASE = PAGE.replace(/\/+$/, '')

// 两个不同端口 = 两个独立的存储环境，模拟两台设备
const PORT_A = 9333
const PORT_B = 9334

/**
 * 每次运行都用全新的浏览器数据目录。
 *
 * 用固定目录的话，上一次跑完留下的身份（localStorage）会被复用，
 * 页面直接进主界面、不显示注册框，测试就会卡在等 #nickname 上。
 */
const RUN_ID = Date.now().toString(36)

// 这是 ESM 模块（.mjs），必须用 import 而不是 require
let puppeteer
try {
  puppeteer = (await import('puppeteer-core')).default
} catch {
  console.error('缺少 puppeteer-core，请先 npm i -D puppeteer-core')
  process.exit(2)
}

const CHROME =
  process.env.CHROME_PATH ||
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe'

function assert(cond, label) {
  if (cond) {
    console.log(`  ✓ ${label}`)
  } else {
    console.error(`  ✗ ${label}`)
    throw new Error('assertion failed: ' + label)
  }
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

async function launch(port) {
  return puppeteer.launch({
    executablePath: CHROME,
    headless: 'new',
    userDataDir: `${process.env.TEMP || '/tmp'}/nd-web-${port}-${RUN_ID}`,
    args: [
      '--no-sandbox',
      '--disable-dev-shm-usage',
      `--remote-debugging-port=${port}`,
      '--window-size=420,900',
    ],
  })
}

/** 在一个页面里完成"注册 + 返回自己昵称"。 */
async function registerAs(page, nickname) {
  await page.goto(PAGE, { waitUntil: 'networkidle2' })
  // 双保险：即使目录被复用，也强制清掉旧身份，从注册页开始
  await page.evaluate(() => {
    try {
      localStorage.removeItem('SlipNote.web.identity')
    } catch {}
  })
  await page.reload({ waitUntil: 'networkidle2' })
  await page.waitForSelector('#nickname', { visible: true })
  await page.type('#nickname', nickname)
  await page.click('#btnRegister')
  await page.waitForSelector('#app', { visible: true, timeout: 15000 })
}

/** 等连接状态变成"已连接"。 */
async function waitConnected(page) {
  await page.waitForFunction(
    () => document.getElementById('connText')?.textContent === '已连接',
    { timeout: 15000 }
  )
}

async function main() {
  console.log(`页面：${PAGE}\n`)

  const browserA = await launch(PORT_A)
  const browserB = await launch(PORT_B)

  try {
    const pageA = await browserA.newPage()
    const pageB = await browserB.newPage()

    // 收集页面里的 JS 报错 —— 前端静默崩溃是最难排查的
    const errors = []
    for (const [name, page] of [['A', pageA], ['B', pageB]]) {
      page.on('pageerror', (e) => errors.push(`[页面${name}] ${e.message}`))
      page.on('console', (m) => {
        if (m.type() !== 'error') return
        // 浏览器会自动去要 favicon 之类的东西，这类 404 与功能无关，
        // 会在日志里淹没真正的前端异常，所以单独排除。
        const t = m.text()
        if (/favicon|apple-touch-icon|manifest\.json/i.test(t)) return
        if (/status of 404/.test(t) && /Failed to load resource/.test(t)) return
        errors.push(`[页面${name}] ${t}`)
      })
    }

    console.log('[1] 两个浏览器各自注册身份')
    await registerAs(pageA, '网页小明')
    await registerAs(pageB, '网页小红')
    assert(true, '两个身份都注册成功并进入主界面')

    console.log('[2] 建立 WebSocket 长连接')
    await waitConnected(pageA)
    await waitConnected(pageB)
    assert(true, '两端状态都显示「已连接」')

    console.log('[3] A 生成邀请码，B 用码配对')
    await pageA.click('#btnCreate')
    await pageA.waitForFunction(
      () => /^\d{4,6}$/.test(document.getElementById('code').value),
      { timeout: 15000 }
    )
    const code = await pageA.$eval('#code', (e) => e.value)
    assert(/^\d{4,6}$/.test(code), `A 生成邀请码 ${code}`)

    await pageB.type('#code', code)
    await pageB.click('#btnJoin')
    await pageB.waitForFunction(
      () => document.querySelectorAll('#peers .peer').length > 0,
      { timeout: 15000 }
    )
    const peerNameOnB = await pageB.$eval('#peers .peer', (e) => e.textContent.trim())
    assert(peerNameOnB.includes('网页小明'), `B 看到同伴：${peerNameOnB}`)

    console.log('[4] A 发纸条，断言 B 秒级收到')
    const t0 = Date.now()
    await pageA.type('#text', '网页端也能发纸条 🧥')
    await pageA.click('#btnSend')

    await pageB.waitForFunction(
      () => {
        const inbox = document.getElementById('inbox')
        return inbox && inbox.textContent.includes('网页端也能发纸条')
      },
      { timeout: 15000 }
    )
    const cost = Date.now() - t0
    const inboxText = await pageB.$eval('#inbox', (e) => e.textContent)
    assert(inboxText.includes('网页端也能发纸条'), 'B 收到了纸条内容')
    assert(inboxText.includes('网页小明'), '落款显示发送者昵称')
    assert(cost < 5000, `端到端耗时 ${cost}ms`)

    console.log('[5] 手写涂鸦能否送达')
    // 在 A 的画布上画一道，模拟手写
    const box = await pageA.$eval('#pad', (el) => {
      const r = el.getBoundingClientRect()
      return { x: r.x, y: r.y, w: r.width, h: r.height }
    })
    await pageA.mouse.move(box.x + 30, box.y + 40)
    await pageA.mouse.down()
    for (let i = 0; i < 20; i++) {
      await pageA.mouse.move(box.x + 30 + i * 8, box.y + 40 + Math.sin(i / 3) * 18)
    }
    await pageA.mouse.up()

    await pageA.click('#btnSend')
    await pageB.waitForFunction(
      () => !!document.querySelector('#inbox img'),
      { timeout: 15000 }
    )
    const imgLen = await pageB.$eval('#inbox img', (e) => e.src.length)
    assert(imgLen > 500, `B 收到涂鸦图片（dataURL ${imgLen} 字符）`)

    console.log('[6] 检查前端有无 JS 报错')
    assert(errors.length === 0, errors.length ? errors.join(' | ') : '无控制台错误')

    // 截图存档，便于人工看一眼
    await pageB.screenshot({ path: 'web-inbox.png' })
    console.log('\n  已保存截图：web-inbox.png')
    console.log('\n全部通过 ✅  网页端可独立作为测试对端')
  } finally {
    await browserA.close().catch(() => {})
    await browserB.close().catch(() => {})
  }
}

main().catch((e) => {
  console.error('\n网页端测试失败 ❌', e.message)
  process.exit(1)
})
