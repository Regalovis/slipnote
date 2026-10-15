/**
 * 探测一个 OpenAI 兼容接口是否真的支持图片输入。
 *
 * 不靠猜配置：生成一张位置特征明确的图，发给模型，
 * 看它能否读出来。能读出来才说明视觉能力可用。
 *
 * 用法：
 *   OPENAI_API_KEY=sk-xxx node test/vision-probe.mjs
 *
 * 可选环境变量：
 *   OPENAI_BASE   接口地址（默认官方地址）
 *   OPENAI_MODEL  模型名
 *
 * 这个脚本和 SlipNote 本身无关，是开发期用来排查
 * "模型到底能不能看图"的小工具，留在这里供参考。
 */
import fs from 'node:fs'
import path from 'node:path'
import zlib from 'node:zlib'

/*
 * 密钥只从环境变量读。
 *
 * 之前这里会去读本机 Codex 的 auth.json 兜底 —— 那种写法一旦
 * 进到公开仓库，等于告诉别人"去翻这个路径能拿到密钥"，
 * 而且路径里还带着本机用户名。开源版去掉这个兜底。
 */
const KEY = process.env.OPENAI_API_KEY
if (!KEY) {
  console.error('缺少 OPENAI_API_KEY。用法：OPENAI_API_KEY=sk-xxx node test/vision-probe.mjs')
  process.exit(1)
}

const BASE = process.env.OPENAI_BASE || 'https://api.openai.com/v1'
const MODEL = process.env.OPENAI_MODEL || 'gpt-4o-mini'

/**
 * 手写一张最小 PNG：纯色背景 + 一块深色方块。
 *
 * 不用文字渲染（要引字体库），改成"左上角有一块黑色方块"这种
 * 位置特征 —— 模型只要答对位置和颜色，就证明它真的在看图。
 */
function makeTestPng() {
  const W = 64, H = 64
  const rows = []
  for (let y = 0; y < H; y++) {
    const row = Buffer.alloc(1 + W * 3)
    row[0] = 0 // filter: none
    for (let x = 0; x < W; x++) {
      // 左上角 20x20 画深蓝方块，其余纯白
      const inBlock = x < 20 && y < 20
      const i = 1 + x * 3
      if (inBlock) {
        row[i] = 0x2F; row[i + 1] = 0x6F; row[i + 2] = 0xED
      } else {
        row[i] = 0xFF; row[i + 1] = 0xFF; row[i + 2] = 0xFF
      }
    }
    rows.push(row)
  }
  const raw = Buffer.concat(rows)

  const chunk = (type, data) => {
    const len = Buffer.alloc(4); len.writeUInt32BE(data.length)
    const t = Buffer.from(type, 'ascii')
    const crcBuf = Buffer.concat([t, data])
    const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(crcBuf) >>> 0)
    return Buffer.concat([len, t, data, crc])
  }

  const ihdr = Buffer.alloc(13)
  ihdr.writeUInt32BE(W, 0); ihdr.writeUInt32BE(H, 4)
  ihdr[8] = 8; ihdr[9] = 2; ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0

  const png = Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', zlib.deflateSync(raw)),
    chunk('IEND', Buffer.alloc(0)),
  ])
  return png
}

let CRC_TABLE = null
function crc32(buf) {
  if (!CRC_TABLE) {
    CRC_TABLE = new Int32Array(256)
    for (let n = 0; n < 256; n++) {
      let c = n
      for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1
      CRC_TABLE[n] = c
    }
  }
  let c = -1
  for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8)
  return c ^ -1
}

const png = makeTestPng()
const outPath = path.join(process.env.TEMP || '/tmp', 'vision-probe.png')
fs.writeFileSync(outPath, png)
console.log(`test image: ${outPath} (${png.length} bytes)`)
console.log('内容：白底，左上角有一块深蓝色方块\n')

const b64 = png.toString('base64')

const body = {
  model: MODEL,
  messages: [
    {
      role: 'user',
      content: [
        {
          type: 'text',
          text: '这张图里有什么？请只回答：图形的位置（左上/右上/左下/右下/中间）和颜色。',
        },
        { type: 'image_url', image_url: { url: `data:image/png;base64,${b64}` } },
      ],
    },
  ],
  max_tokens: 200,
}

console.log(`endpoint: ${BASE}/chat/completions`)
console.log(`model:    ${MODEL}\n`)

try {
  const res = await fetch(`${BASE}/chat/completions`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${KEY}`,
    },
    body: JSON.stringify(body),
  })

  const text = await res.text()
  console.log(`HTTP ${res.status}`)

  if (!res.ok) {
    console.log('原始响应：')
    console.log(text.slice(0, 900))
    process.exitCode = 1
  } else {
    const json = JSON.parse(text)
    const reply = json.choices?.[0]?.message?.content
    console.log('模型回答：')
    console.log(reply)

    const ok = /左|left|左上|top.?left/i.test(String(reply)) && /蓝|blue/i.test(String(reply))
    console.log('')
    console.log(ok ? '✅ 视觉能力可用（正确识别出左上角的蓝色方块）' : '⚠️ 回答不符合预期，请人工判断')
  }
} catch (e) {
  console.error('请求失败:', e.message)
  process.exitCode = 1
}
