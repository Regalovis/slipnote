/**
 * 排查画板导出问题：确认画布尺寸、绘制、导出是否都正常。
 * 用法：node test/debug-pad.mjs https://example.com/notedrop/
 */
import puppeteer from 'puppeteer-core'

const PAGE = process.argv[2] || 'http://127.0.0.1:8090/'
const CHROME = process.env.CHROME_PATH || 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe'

const browser = await puppeteer.launch({
  executablePath: CHROME,
  headless: 'new',
  userDataDir: `${process.env.TEMP || '/tmp'}/nd-dbg-${Date.now()}`,
  args: ['--no-sandbox'],
})

try {
  const page = await browser.newPage()
  const errs = []
  page.on('pageerror', (e) => errs.push('PAGEERR: ' + e.message))
  page.on('console', (m) => {
    if (m.type() === 'error') errs.push('CONSOLE: ' + m.text())
  })

  await page.goto(PAGE, { waitUntil: 'networkidle2' })
  await page.evaluate(() => { try { localStorage.clear() } catch {} })
  await page.reload({ waitUntil: 'networkidle2' })

  await page.waitForSelector('#nickname', { visible: true })
  await page.type('#nickname', '调试')
  await page.click('#btnRegister')
  await page.waitForSelector('#app', { visible: true, timeout: 15000 })

  const info = await page.evaluate(() => {
    const c = document.getElementById('pad')
    const r = c.getBoundingClientRect()
    return { cssW: r.width, cssH: r.height, pxW: c.width, pxH: c.height }
  })
  console.log('画布:', JSON.stringify(info))

  const box = await page.$eval('#pad', (el) => {
    const r = el.getBoundingClientRect()
    return { x: r.x, y: r.y, w: r.width, h: r.height }
  })
  console.log('位置:', JSON.stringify(box))

  await page.mouse.move(box.x + 30, box.y + 40)
  await page.mouse.down()
  for (let i = 0; i < 20; i++) {
    await page.mouse.move(box.x + 30 + i * 8, box.y + 40 + Math.sin(i / 3) * 18)
  }
  await page.mouse.up()

  const len = await page.evaluate(() => document.getElementById('pad').toDataURL('image/png').length)
  console.log('导出 dataURL 长度:', len)

  // 检查纸张切换是否可用
  await page.click('#paperPicker .seg-btn[data-paper="grid"]')
  const cls = await page.$eval('#paper', (e) => e.className)
  console.log('切换方格后 class:', cls)

  // 检查粗细与颜色
  await page.click('#btnWidth')
  const wLabel = await page.$eval('#btnWidth', (e) => e.textContent)
  console.log('粗细标签:', wLabel)
  const swatchCount = await page.$$eval('#swatches .swatch', (els) => els.length)
  console.log('颜色点数量:', swatchCount)

  /*
   * 关键排查：发送时到底有没有把涂鸦带上。
   * 拦截 fetch 请求，看看 POST /api/notes 的 body 长什么样。
   */
  let captured = null
  await page.setRequestInterception(true)
  page.on('request', (req) => {
    if (req.url().includes('/api/notes') && req.method() === 'POST') {
      const body = req.postData() || ''
      captured = {
        len: body.length,
        hasDoodle: body.includes('"doodle"'),
        hasPaper: body.includes('paperStyle'),
        preview: body.slice(0, 160),
      }
    }
    req.continue()
  })

  // 配对后再发一次，才能走到真实发送路径
  const peerInfo = await page.evaluate(async () => {
    // 直接调接口造一个"同伴"太绕，这里只验证 send() 的 body 构造
    return typeof window !== 'undefined'
  })
  console.log('页面可用:', peerInfo)

  await page.click('#btnSend')
  await new Promise((r) => setTimeout(r, 1200))
  console.log('发送请求:', captured ? JSON.stringify(captured) : '(未捕获，可能因未配对而提前返回)')

  console.log('错误:', errs.length ? errs.join(' | ') : '无')
} finally {
  await browser.close()
}
