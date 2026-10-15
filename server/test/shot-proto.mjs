/**
 * 给界面原型截图。
 *
 * 写完 HTML 直接给你看是不负责任的 —— 我没法确认它实际长什么样。
 * 先自己截图、自己挑毛病、改到满意，再拿给你看。
 *
 * 用法：node test/shot-proto.mjs <html路径> <输出前缀>
 */
import fs from 'node:fs'
import path from 'node:path'
import puppeteer from 'puppeteer-core'

const FILE = process.argv[2] || 'D:\\小部件\\proto\\index.html'
const PREFIX = process.argv[3] || 'D:\\小部件\\proto\\shot'

const CHROME =
  process.env.CHROME_PATH ||
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe'

// 手机视口。截图时把外框放大到接近真机像素，细节才看得清。
const DPR = 2

const browser = await puppeteer.launch({
  executablePath: CHROME,
  headless: 'new',
  userDataDir: `${process.env.TEMP || '/tmp'}/nd-proto-${Date.now()}`,
  args: ['--no-sandbox', '--disable-dev-shm-usage', '--force-device-scale-factor=2'],
})

try {
  const page = await browser.newPage()
  await page.setViewport({ width: 1400, height: 1000, deviceScaleFactor: DPR })
  await page.goto(`file:///${FILE.replace(/\\/g, '/')}`, { waitUntil: 'networkidle0' })

  // 等字体和渐变渲染完成
  await new Promise((r) => setTimeout(r, 900))

  // 逐块截图：每个 .phone-wrap 单独出一张，放大后才看得清细节
  const count = await page.$$eval('.phone-wrap', (els) => els.length)
  console.log(`找到 ${count} 个界面`)

  for (let i = 0; i < count; i++) {
    const handles = await page.$$('.phone-wrap')
    const el = handles[i]
    const label = await page.$$eval(
      '.phone-wrap .phone-label',
      (els, idx) => els[idx].querySelector('b')?.textContent?.trim() || `screen-${idx}`,
      i
    )
    const safe = label.replace(/[^\w\u4e00-\u9fa5]+/g, '-')
    const out = `${PREFIX}-${i + 1}-${safe}.png`
    await el.screenshot({ path: out })
    console.log(`  ${path.basename(out)}`)
  }
} finally {
  await browser.close()
}
