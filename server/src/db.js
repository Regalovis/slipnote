'use strict'

const fs = require('fs')
const path = require('path')

const DATA_DIR = process.env.DATA_DIR || path.join(__dirname, '..', 'data')
fs.mkdirSync(DATA_DIR, { recursive: true })
const DB_FILE = path.join(DATA_DIR, 'slipnote.db')

/**
 * 数据库驱动自适应。
 *
 * 优先用 Node 内置的 node:sqlite（Node >= 22.5），它是零依赖的，
 * 不需要编译原生模块 —— better-sqlite3 在缺少构建工具的机器上装不上
 * （Windows 无 VS、部分精简版 Linux 镜像都会踩到）。
 *
 * 老版本 Node 上退回到 better-sqlite3。两者 API 高度相似，
 * 差异由下面的适配层抹平，上层代码完全不用改。
 */
function openDatabase() {
  let raw = null
  let driver = ''

  try {
    const { DatabaseSync } = require('node:sqlite')
    raw = new DatabaseSync(DB_FILE)
    driver = 'node:sqlite'
  } catch {
    const Database = require('better-sqlite3')
    raw = new Database(DB_FILE)
    driver = 'better-sqlite3'
  }

  // WAL 让读写并发更顺，移动端频繁写入时明显更稳
  try {
    raw.exec('PRAGMA journal_mode = WAL')
    raw.exec('PRAGMA foreign_keys = ON')
  } catch {
    /* 某些驱动/文件系统不支持 WAL，忽略即可 */
  }

  /**
   * 统一事务接口。
   * better-sqlite3 有 db.transaction()，node:sqlite 没有，
   * 所以这里自己用 BEGIN / COMMIT / ROLLBACK 实现。
   */
  function transaction(fn) {
    return (...args) => {
      raw.exec('BEGIN')
      try {
        const result = fn(...args)
        raw.exec('COMMIT')
        return result
      } catch (err) {
        try {
          raw.exec('ROLLBACK')
        } catch {
          /* ignore */
        }
        throw err
      }
    }
  }

  return {
    driver,
    exec: (sql) => raw.exec(sql),
    prepare: (sql) => raw.prepare(sql),
    transaction,
    close: () => raw.close(),
  }
}

const db = openDatabase()

/**
 * 建表。
 *
 * 配对本可以只存一行 (a,b)，但那样每次查询都要写 `a=? OR b=?` 还得判方向。
 * 这里直接存两行 (a→b, b→a)，查询变成单条件等值匹配，代码简单很多。
 */
db.exec(`
  CREATE TABLE IF NOT EXISTS users (
    user_id     TEXT PRIMARY KEY,
    nickname    TEXT NOT NULL,
    token       TEXT NOT NULL UNIQUE,
    created_at  INTEGER NOT NULL
  );

  CREATE TABLE IF NOT EXISTS pairs (
    owner_id    TEXT NOT NULL,
    peer_id     TEXT NOT NULL,
    created_at  INTEGER NOT NULL,
    PRIMARY KEY (owner_id, peer_id)
  );
  CREATE INDEX IF NOT EXISTS idx_pairs_owner ON pairs(owner_id);

  CREATE TABLE IF NOT EXISTS pair_codes (
    code        TEXT PRIMARY KEY,
    owner_id    TEXT NOT NULL,
    expires_at  INTEGER NOT NULL
  );

  CREATE TABLE IF NOT EXISTS notes (
    id          TEXT PRIMARY KEY,
    from_id     TEXT NOT NULL,
    to_id       TEXT NOT NULL,
    text        TEXT,
    doodle      TEXT,
    paper_style TEXT,
    landscape   INTEGER,
    created_at  INTEGER NOT NULL,
    delivered   INTEGER NOT NULL DEFAULT 0
  );
  CREATE INDEX IF NOT EXISTS idx_notes_to ON notes(to_id, delivered);

  -- ===== 群组 =====

  -- 一个群组用邀请码加入，成员之间可以互传纸条
  CREATE TABLE IF NOT EXISTS groups (
    group_id    TEXT PRIMARY KEY,
    name        TEXT NOT NULL,
    code        TEXT NOT NULL UNIQUE,
    owner_id    TEXT NOT NULL,
    created_at  INTEGER NOT NULL
  );

  /*
   * 群成员。
   *
   * order_index 是「接力」玩法的关键：纸条按这个顺序在成员间传递，
   * A 传给 B、B 传给 C……走到最后一个再绕回第一个。
   * 用自增序号而不是加入时间，是为了排序稳定、不受时钟影响。
   */
  CREATE TABLE IF NOT EXISTS group_members (
    group_id     TEXT NOT NULL,
    user_id      TEXT NOT NULL,
    joined_at    INTEGER NOT NULL,
    order_index  INTEGER NOT NULL,
    PRIMARY KEY (group_id, user_id)
  );
  CREATE INDEX IF NOT EXISTS idx_group_members_user ON group_members(user_id);
`)

/**
 * 轻量迁移。
 *
 * 老库里的 notes 表没有 paper_style 列，直接跑新 SQL 会报错。
 * 这里检测一下并补列 —— 比引入完整迁移框架简单得多，
 * 对这种单表加字段的场景完全够用。
 */
function ensureColumn(table, column, definition) {
  try {
    const cols = db.prepare(`PRAGMA table_info(${table})`).all()
    if (cols.some((c) => c.name === column)) return
    db.exec(`ALTER TABLE ${table} ADD COLUMN ${column} ${definition}`)
    console.log(`[db] migration: added ${table}.${column}`)
  } catch (err) {
    console.error(`[db] migration failed for ${table}.${column}:`, err.message)
  }
}

ensureColumn('notes', 'paper_style', 'TEXT')
ensureColumn('notes', 'landscape', 'INTEGER')
// 群组纸条：记录归属的群、是否匿名、以及投递模式
ensureColumn('notes', 'group_id', 'TEXT')
ensureColumn('notes', 'anonymous', 'INTEGER')
ensureColumn('notes', 'chain_mode', 'TEXT')

/*
 * 贴纸数据。
 *
 * 存成 JSON 字符串：一个纸条上可能贴多张，每张有位置/大小/旋转/图片。
 * 拆成独立表会让"取纸条"变成一次 JOIN + 组装，
 * 而贴纸永远是**跟着纸条一起读**的，没有单独查询的需求，
 * 所以整体存一列更简单也更快。
 *
 * 内容示例：
 *   [{"id":"s1","cx":0.5,"cy":0.4,"widthRatio":0.3,"rotation":12,"data":"<base64>"}]
 */
ensureColumn('notes', 'stickers', 'TEXT')

/*
 * 账号体系。
 *
 * 原来的 register 每次都新发一个 userId，导致"改个昵称"等于换了个账号，
 * 配对和纸条全断。加上账号密码后：
 *   - username 是稳定标识，登录后 userId 不变
 *   - nickname 只是显示名，随便改
 *
 * username 存小写做唯一键，避免 "Black" / "black" 被当成两个人；
 * password_hash 存 scrypt 的散列（Node 内置 crypto，不引第三方库）。
 */
ensureColumn('users', 'username', 'TEXT')
ensureColumn('users', 'password_hash', 'TEXT')
ensureColumn('users', 'password_salt', 'TEXT')

// 唯一索引必须单独建，且要容忍历史数据里 username 为 NULL
try {
  db.exec('CREATE UNIQUE INDEX IF NOT EXISTS idx_users_username ON users(username) WHERE username IS NOT NULL')
} catch (err) {
  console.error('[db] username index failed:', err.message)
}

console.log(`[db] using ${db.driver} -> ${DB_FILE}`)

module.exports = { db, DATA_DIR, DB_FILE }
