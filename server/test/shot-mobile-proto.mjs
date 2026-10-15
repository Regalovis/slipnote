/**
 * 按真机视口给原型截图。
 *
 * 用户是在手机上打开的，必须按手机宽度验证，不能只看桌面效果。
 * 用 Redmi K70E 的实际参数：1220x2712 @480dpi ≈ 393x874 CSS 像素。
 *
 * 用法：node test/shot-mobile-proto.mjs <url或file> <输出前缀>
 */
import path from 'node:path'
import puppeteer from 'puppeteer-core'

const TARGET = process.argv[2] || 'D:\\小部件\\proto\\index.html'
const PREFIX = process.argv[3] || 'D:\\小部件\\proto\\m'

const CHROME =
  process.env.CHROME_PATH ||
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe'

const VIEW = { width: 393, height: 874, deviceScaleFactor: 2 }

const URL_ = /^https?:\/\//.test(TARGET)
  ? TARGET
  : `file:///${TARGET.replace(/\\/g, '/')}`

const browser = await puppeteer.launch({
  executablePath: CHROME,
  headless: 'new',
  userDataDir: `${process.env.TEMP || '/tmp'}/nd-m-${Date.now()}`,
  args: ['--no-sandbox', '--disable-dev-shm-usage'],
})

/**
 * 依次截图：首页 → 收件箱 → 写纸条。
 *
 * 页面现在没有演示用的切换条（那是上一版的错误），
 * 所以通过点击真实的导航入口来切换，顺便验证导航本身是通的。
 */
async function shoot(page, colorScheme, tag) {
  await page.emulateMediaFeatures([
    { name: 'prefers-color-scheme', value: colorScheme },
  ])
  await page.reload({ waitUntil: 'networkidle0' })
  await new Promise((r) => setTimeout(r, 500))

  // 首页
  await page.screenshot({ path: `${PREFIX}-${tag}-首页.png` })
  console.log(`  ${tag}-首页.png`)

  // 点"收件箱"进入
  await page.click('.q[data-go="list"]')
  await new Promise((r) => setTimeout(r, 350))
  await page.screenshot({ path: `${PREFIX}-${tag}-收件箱.png` })
  console.log(`  ${tag}-收件箱.png`)

  // 返回首页，再点"写纸条"进入
  await page.click('[data-go="home"]')
  await new Promise((r) => setTimeout(r, 250))
  await page.click('.q[data-go="compose"]')
  await new Promise((r) => setTimeout(r, 350))
  await page.screenshot({ path: `${PREFIX}-${tag}-写纸条.png` })
  console.log(`  ${tag}-写纸条.png`)

  // 抽屉展开后的样子 —— 验证工具栏没有把画布挤没
  await page.click('#grabber')
  await new Promise((r) => setTimeout(r, 500))
  await page.screenshot({ path: `${PREFIX}-${tag}-写纸条-工具展开.png` })
  console.log(`  ${tag}-写纸条-工具展开.png`)
}

try {
  const page = await browser.newPage()
  await page.setViewport(VIEW)
  await page.goto(URL_, { waitUntil: 'networkidle0', timeout: 40000 })

  await shoot(page, 'light', '白')
  await shoot(page, 'dark', '夜')
} finally {
  await browser.close()
}
