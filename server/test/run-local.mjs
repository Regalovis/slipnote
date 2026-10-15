/**
 * 本地一体化测试：起一个临时服务端 → 跑指定测试 → 关掉。
 *
 * 为什么要这样：在 PowerShell 里用 Start-Process 起后台服务会被策略拦，
 * 用 Start-Job 又因为跨会话拿不到进程。把整个流程放进一个 Node 进程里
 * 最省事，跑完自动清理，不会留下野进程占端口。
 *
 * 用法：
 *   node test/run-local.mjs test/auth.js
 *   node test/run-local.mjs test/groups.js test/e2e.js
 */
import { spawn } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const root = path.join(here, '..')

const tests = process.argv.slice(2)
if (tests.length === 0) {
  console.error('用法: node test/run-local.mjs <测试脚本...>')
  process.exit(2)
}

const PORT = 8099
const DATA_DIR = path.join(process.env.TEMP || '/tmp', `nd-test-${Date.now()}`)
fs.mkdirSync(DATA_DIR, { recursive: true })

console.log(`临时数据目录: ${DATA_DIR}`)
console.log(`端口: ${PORT}\n`)

// 用独立端口和独立数据目录，绝不碰服务器上的生产库
const server = spawn(process.execPath, ['src/index.js'], {
  cwd: root,
  env: {
    ...process.env,
    PORT: String(PORT),
    HOST: '127.0.0.1',
    DATA_DIR,
  },
  stdio: ['ignore', 'pipe', 'pipe'],
})

server.stdout.on('data', (d) => process.stdout.write(`[server] ${d}`))
server.stderr.on('data', (d) => process.stderr.write(`[server] ${d}`))

/** 等服务端口真正可连（比固定 sleep 稳）。 */
async function waitReady(timeoutMs = 15000) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    try {
      const res = await fetch(`http://127.0.0.1:${PORT}/api/health`)
      if (res.ok) return true
    } catch {
      /* 还没起来 */
    }
    await new Promise((r) => setTimeout(r, 300))
  }
  return false
}

function shutdown() {
  try {
    server.kill()
  } catch {
    /* ignore */
  }
  try {
    fs.rmSync(DATA_DIR, { recursive: true, force: true })
  } catch {
    /* ignore */
  }
}

let failed = false

try {
  if (!(await waitReady())) {
    console.error('❌ 服务端启动超时')
    process.exit(1)
  }
  console.log('\n服务端已就绪，开始测试\n' + '='.repeat(60))

  for (const t of tests) {
    const file = path.join(root, t)
    if (!fs.existsSync(file)) {
      console.error(`跳过不存在的测试: ${t}`)
      failed = true
      continue
    }
    console.log(`\n>>> ${t}\n`)
    const code = await new Promise((resolve) => {
      const child = spawn(process.execPath, [file], {
        cwd: root,
        env: { ...process.env, BASE: `http://127.0.0.1:${PORT}` },
        stdio: 'inherit',
      })
      child.on('exit', (c) => resolve(c ?? 1))
    })
    if (code !== 0) failed = true
  }
} finally {
  shutdown()
}

console.log('\n' + '='.repeat(60))
if (failed) {
  console.log('❌ 有测试未通过')
  process.exit(1)
} else {
  console.log('✅ 全部测试通过')
}
