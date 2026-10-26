#!/usr/bin/env node
/**
 * 从 PostgreSQL 基线派生 MySQL / SQLite 基线。
 *
 * 为什么要有这个脚本：
 *   三套 DDL 手写 33 张表、500 多个列必然发散。以 PostgreSQL 基线为唯一事实来源，
 *   用规则派生另外两份，结构变更时只需改 PG 版再重跑本脚本，三库不会走样。
 *
 * 用法（在 app 目录下）：
 *   node script/db/gen-baseline.mjs            # 生成 mysql / sqlite 两份
 *   node script/db/gen-baseline.mjs --check    # 只校验，不写文件（CI 用）
 *
 * 约定：
 *   1. 只处理 pg_dump --schema-only 的固定输出形态（CREATE TABLE 逐列一行、
 *      ALTER TABLE ONLY 加主键、COMMENT ON、CREATE INDEX 单行）。
 *   2. 无法识别的语句直接报错退出，不做「尽力而为」的猜测——
 *      悄悄漏掉一个约束比生成失败危险得多。
 */

import fs from 'node:fs';
import path from 'node:path';

const APP_ROOT = path.resolve(import.meta.dirname, '..', '..');
const MIGRATION_ROOT = path.join(APP_ROOT, 'src', 'main', 'resources', 'db', 'migration');
const PG_FILE = path.join(MIGRATION_ROOT, 'postgresql', 'V1__baseline.sql');

/** 解析 pg_dump 输出，拆成表 / 主键 / 注释 / 索引四类结构。 */
function parsePgBaseline(source) {
  const lines = source.split(/\r?\n/);
  const tables = [];
  const primaryKeys = [];
  const comments = [];
  const indexes = [];
  const unknown = [];

  let i = 0;
  while (i < lines.length) {
    const raw = lines[i];
    const line = raw.trim();

    if (line === '' || line.startsWith('--')) {
      i += 1;
      continue;
    }

    // 会话参数与 search_path：只对 psql 有意义，其他库的脚本里没有对应概念
    if (/^SET\s/.test(line) || /^SELECT pg_catalog\.set_config/.test(line)) {
      i += 1;
      continue;
    }

    const createMatch = line.match(/^CREATE TABLE public\.(\w+) \($/);
    if (createMatch) {
      const name = createMatch[1];
      const body = [];
      i += 1;
      while (i < lines.length && lines[i].trim() !== ');') {
        body.push(lines[i]);
        i += 1;
      }
      if (i >= lines.length) {
        throw new Error(`表 ${name} 的 CREATE TABLE 未闭合`);
      }
      tables.push({ name, body });
      i += 1;
      continue;
    }

    const pkMatch = line.match(/^ALTER TABLE ONLY public\.(\w+)$/);
    if (pkMatch) {
      const target = lines[i + 1] ? lines[i + 1].trim() : '';
      const addPk = target.match(/^ADD CONSTRAINT (\w+) PRIMARY KEY \(("?\w+"?)\);$/);
      if (!addPk) {
        throw new Error(`无法识别的主键语句：${lines[i + 1]}`);
      }
      primaryKeys.push({ table: pkMatch[1], constraint: addPk[1], column: unquote(addPk[2]) });
      i += 2;
      continue;
    }

    const commentMatch = line.match(/^COMMENT ON (TABLE|COLUMN) public\.([\w."]+) IS '(.*)';$/);
    if (commentMatch) {
      comments.push({
        kind: commentMatch[1],
        target: commentMatch[2],
        text: commentMatch[3].replace(/''/g, "'"),
      });
      i += 1;
      continue;
    }

    const indexMatch = line.match(
      /^CREATE (UNIQUE )?INDEX (\w+) ON public\.(\w+) USING btree \(([^)]*)\)( WHERE (.*))?;$/,
    );
    if (indexMatch) {
      indexes.push({
        unique: Boolean(indexMatch[1]),
        name: indexMatch[2],
        table: indexMatch[3],
        columns: indexMatch[4],
        where: indexMatch[6] || null,
      });
      i += 1;
      continue;
    }

    // 分号结尾但上面都没匹配到，说明出现了新形态，必须人工介入
    if (line.endsWith(';')) {
      unknown.push(line);
    } else {
      unknown.push(line);
    }
    i += 1;
  }

  if (unknown.length > 0) {
    throw new Error(`基线里存在未识别的语句（共 ${unknown.length} 条），请扩展脚本：\n  ${unknown.slice(0, 5).join('\n  ')}`);
  }
  if (tables.length === 0) {
    throw new Error('未解析出任何表，基线格式可能已变化');
  }
  return { tables, primaryKeys, comments, indexes };
}

/** 把 PostgreSQL 的约束表达式改写成三库都能接受的写法。 */
function normalizeExpression(expr) {
  let out = expr;
  // ((col)::text = ANY ((ARRAY['A'::character varying, 'B'::character varying])::text[]))
  out = out.replace(
    /\(\((\w+)\)::text = ANY \(\(ARRAY\[([^\]]*)\]\)::text\[\]\)\)/g,
    (_m, col, items) => `(${col} IN (${listOf(items)}))`,
  );
  // (col = ANY (ARRAY[0, 1]))
  out = out.replace(
    /\((\w+) = ANY \(ARRAY\[([^\]]*)\]\)\)/g,
    (_m, col, items) => `(${col} IN (${listOf(items)}))`,
  );
  out = out.replace(/ARRAY\[([^\]]*)\]/g, (_m, items) => `(${listOf(items)})`);
  // (0)::numeric / 'X'::character varying 之类的类型强转
  out = out.replace(/::(?:character varying|text|numeric|decimal|bigint|integer|smallint|boolean|date|timestamp(?: without time zone)?)(?:\(\d+(?:,\d+)?\))?/g, '');
  out = out.replace(/::text\[\]/g, '');
  // PostgreSQL 布尔字面量在 MySQL / SQLite 里按整数处理
  out = out.replace(/=\s*true\b/gi, '= 1').replace(/=\s*false\b/gi, '= 0');
  return out;
}

function listOf(items) {
  return items
    .split(',')
    .map((item) => item.trim().replace(/::[a-z ]+(\(\d+(,\d+)?\))?$/i, '').trim())
    .filter(Boolean)
    .join(', ');
}

/** 列定义拆成「缩进 + 列名 + 剩余定义 + 行尾逗号」。列名可能被 pg_dump 加了双引号（保留字）。 */
function splitColumn(line) {
  const match = line.match(/^(\s+)("?\w+"?)(\s+)([\s\S]*?)(,?)$/);
  if (!match) {
    return null;
  }
  return {
    indent: match[1],
    name: unquote(match[2]),
    quoted: match[2].startsWith('"'),
    gap: match[3],
    rest: match[4],
    comma: match[5],
  };
}

function unquote(identifier) {
  return identifier.replace(/^"|"$/g, '');
}

/**
 * 按方言给标识符加引号。
 *
 * <p>pg_dump 只给保留字加引号，这里沿用同一判断：原名带引号的，到 MySQL 用反引号、
 * SQLite 用双引号；其余保持裸名，避免 DDL 出现满屏引号。</p>
 */
function quoteFor(identifier, dialect) {
  if (!identifier.quoted) {
    return identifier.name;
  }
  return dialect === 'mysql' ? `\`${identifier.name}\`` : `"${identifier.name}"`;
}

const MYSQL_TYPES = [
  [/^character varying\((\d+)\)/i, 'varchar($1)'],
  [/^character varying/i, 'varchar(255)'],
  [/^timestamp without time zone/i, 'datetime(3)'],
  [/^timestamp with time zone/i, 'datetime(3)'],
  [/^boolean/i, 'tinyint(1)'],
  [/^numeric\((\d+),(\d+)\)/i, 'decimal($1,$2)'],
  [/^numeric/i, 'decimal(18,4)'],
  [/^integer/i, 'int'],
  [/^text/i, 'text'],
  [/^bigint/i, 'bigint'],
  [/^smallint/i, 'smallint'],
];

const SQLITE_TYPES = [
  [/^character varying(\(\d+\))?/i, 'text'],
  [/^timestamp without time zone/i, 'text'],
  [/^timestamp with time zone/i, 'text'],
  [/^boolean/i, 'integer'],
  [/^numeric(\(\d+(,\d+)?\))?/i, 'numeric'],
  [/^integer/i, 'integer'],
  [/^bigint/i, 'integer'],
  [/^smallint/i, 'integer'],
  [/^text/i, 'text'],
];

function mapType(rest, rules) {
  for (const [pattern, replacement] of rules) {
    if (pattern.test(rest)) {
      return rest.replace(pattern, replacement);
    }
  }
  throw new Error(`无法映射的列类型：${rest}`);
}

/**
 * 归一化 DEFAULT 子句。
 *
 * <p>PostgreSQL 的默认时间有两套写法：{@code now()} 与 {@code CURRENT_TIMESTAMP}，
 * 前者在 MySQL 里要用 {@code CURRENT_TIMESTAMP(3)}（列是 datetime(3)），
 * 在 SQLite 里要显式取本地时间——SQLite 的 CURRENT_TIMESTAMP 是 UTC，
 * 直接用会让桌面版的时间比 PostgreSQL 版少 8 小时。</p>
 */
function normalizeDefault(rest, dialect) {
  let out = rest
    .replace(/\bDEFAULT true\b/g, 'DEFAULT 1')
    .replace(/\bDEFAULT false\b/g, 'DEFAULT 0');
  if (dialect === 'mysql') {
    out = out
      .replace(/\bDEFAULT now\(\)/g, 'DEFAULT CURRENT_TIMESTAMP(3)')
      .replace(/\bDEFAULT CURRENT_TIMESTAMP(?!\()/g, 'DEFAULT CURRENT_TIMESTAMP(3)');
  } else {
    out = out.replace(/\bDEFAULT (?:now\(\)|CURRENT_TIMESTAMP(?!\())/g, "DEFAULT (datetime('now','localtime'))");
  }
  return out;
}

/* ------------------------------------------------------------------ */
/* MySQL                                                               */
/* ------------------------------------------------------------------ */

function buildMysql(parsed) {
  const commentByTable = new Map();
  const commentByColumn = new Map();
  for (const comment of parsed.comments) {
    if (comment.kind === 'TABLE') {
      commentByTable.set(comment.target, comment.text);
    } else {
      const dot = comment.target.indexOf('.');
      const table = comment.target.slice(0, dot);
      const column = unquote(comment.target.slice(dot + 1));
      commentByColumn.set(`${table}.${column}`, comment.text);
    }
  }

  const out = [];
  out.push(header('MySQL', 'MySQL 8.0.13+ / MariaDB 10.2+'));
  out.push('SET NAMES utf8mb4;');
  out.push('SET FOREIGN_KEY_CHECKS = 0;');
  out.push('');

  for (const table of parsed.tables) {
    const pk = parsed.primaryKeys.find((item) => item.table === table.name);
    if (!pk) {
      throw new Error(`表 ${table.name} 缺少主键定义`);
    }
    out.push(`-- ---------------------------- ${table.name} ----------------------------`);
    out.push(`CREATE TABLE ${table.name} (`);
    const bodyLines = table.body.map((line) => {
      if (/^\s*CONSTRAINT\s+/.test(line)) {
        return normalizeExpression(line);
      }
      const column = splitColumn(line);
      if (!column) {
        return line;
      }
      let rest = normalizeDefault(mapType(column.rest, MYSQL_TYPES), 'mysql');
      rest = normalizeExpression(rest);
      const comment = commentByColumn.get(`${table.name}.${column.name}`);
      if (comment) {
        rest = `${rest} COMMENT '${comment.replace(/'/g, "''")}'`;
      }
      return `${column.indent}${quoteFor(column, 'mysql')} ${rest}${column.comma}`;
    });
    out.push(...bodyLines);
    const tableComment = commentByTable.get(table.name);
    out.push(tableComment ? `) COMMENT='${tableComment.replace(/'/g, "''")}';` : ');');
    out.push('');
    out.push(`ALTER TABLE ${table.name} ADD CONSTRAINT ${pk.constraint} PRIMARY KEY (${pk.column});`);
    out.push('');
  }

  for (const index of parsed.indexes) {
    const unique = index.unique ? 'UNIQUE ' : '';
    let columns = index.columns;
    let suffix = '';
    if (index.where) {
      if (index.unique) {
        // 部分唯一索引：MySQL 没有 WHERE 索引，用「条件函数列」等效。
        // 条件成立时表达式取 0 参与唯一性判断，不成立时取 NULL ——
        // NULL 在唯一索引里可以重复，于是「只有未删除的行受唯一约束」被完整保留。
        const condition = normalizeExpression(index.where);
        columns = `${columns}, ((CASE WHEN ${condition} THEN 0 ELSE NULL END))`;
      } else {
        // 部分普通索引：直接丢掉 WHERE 即可。索引多包含已删除的行，但查询里的
        // deleted = 0 仍能命中前缀，只是索引体积略大，不会退化到全表扫描。
        suffix = `   -- 原 WHERE ${normalizeExpression(index.where)} 在 MySQL 无对应语法，普通索引忽略`;
      }
    }
    out.push(`CREATE ${unique}INDEX ${index.name} ON ${index.table} (${columns});${suffix}`);
  }
  out.push('');
  out.push('SET FOREIGN_KEY_CHECKS = 1;');
  out.push('');
  return out.join('\n');
}

/* ------------------------------------------------------------------ */
/* SQLite                                                              */
/* ------------------------------------------------------------------ */

function buildSqlite(parsed) {
  const out = [];
  out.push(header('SQLite', 'SQLite 3.35+'));
  // 不写 PRAGMA foreign_keys：它在 Flyway 眼里是非事务性语句，与建表混在同一脚本里会报
  // 「Detected both transactional and non-transactional statements」。该开关由 JDBC URL
  // 与连接池的 connection-init-sql 负责，不依赖迁移脚本。
  out.push('');

  for (const table of parsed.tables) {
    const pk = parsed.primaryKeys.find((item) => item.table === table.name);
    if (!pk) {
      throw new Error(`表 ${table.name} 缺少主键定义`);
    }
    out.push(`-- ---------------------------- ${table.name} ----------------------------`);
    out.push(`CREATE TABLE ${table.name} (`);
    const bodyLines = table.body.map((line) => {
      if (/^\s*CONSTRAINT\s+/.test(line)) {
        return normalizeExpression(line);
      }
      const column = splitColumn(line);
      if (!column) {
        return line;
      }
      if (column.name === pk.column) {
        // SQLite 不支持建表后 ADD PRIMARY KEY，必须内联；INTEGER PRIMARY KEY 即 rowid 别名
        const rest = normalizeDefault(mapType(column.rest, SQLITE_TYPES), 'sqlite');
        return `${column.indent}${quoteFor(column, 'sqlite')} ${rest} PRIMARY KEY${column.comma}`;
      }
      const rest = normalizeDefault(mapType(column.rest, SQLITE_TYPES), 'sqlite');
      return `${column.indent}${quoteFor(column, 'sqlite')} ${normalizeExpression(rest)}${column.comma}`;
    });
    out.push(...bodyLines);
    out.push(');');
    out.push('');
  }

  for (const index of parsed.indexes) {
    const unique = index.unique ? 'UNIQUE ' : '';
    const where = index.where ? ` WHERE ${normalizeExpression(index.where)}` : '';
    out.push(`CREATE ${unique}INDEX ${index.name} ON ${index.table} (${index.columns})${where};`);
  }
  out.push('');
  return out.join('\n');
}

function header(dialect, requirement) {
  const differences = dialect === 'MySQL'
    ? `--   1. 逻辑删除的部分唯一索引（WHERE deleted = 0）改用「条件函数列」等效实现：
--      条件成立时表达式取 0 参与唯一性，不成立时取 NULL（唯一索引允许 NULL 重复），
--      因此「只有未删除的行受唯一约束」的语义与 PostgreSQL 完全一致。
--      MySQL 的函数索引要求 8.0.13+。
--   2. 部分普通索引（非唯一）直接丢弃 WHERE，仅索引体积略大，查询计划不受影响。`
    : `--   1. 部分索引（WHERE 子句）SQLite 原生支持，与 PostgreSQL 写法一致，无需改写。
--   2. 时间列一律为 TEXT，格式 yyyy-MM-dd HH:mm:ss.SSS，由 SqliteLocalDateTimeTypeHandler
--      负责读写转换；DEFAULT 用 datetime('now','localtime')，与 PostgreSQL 一样存本地时间。`;
  return `-- =============================================================================
-- StoryForge 数据库基线（V1 合并版 · ${dialect}）
--
-- 本文件由 script/db/gen-baseline.mjs 从 postgresql/V1__baseline.sql 派生，
-- 禁止手工修改：结构变更请改 PostgreSQL 版并重跑脚本，三份脚本始终同源。
--
-- 目标版本：${requirement}
--
-- 与 PostgreSQL 版有意保留的差异：
${differences}
-- =============================================================================

`;
}

/* ------------------------------------------------------------------ */

function main() {
  const checkOnly = process.argv.includes('--check');
  const source = fs.readFileSync(PG_FILE, 'utf8');
  const parsed = parsePgBaseline(source);

  const outputs = [
    { file: path.join(MIGRATION_ROOT, 'mysql', 'V1__baseline.sql'), content: buildMysql(parsed) },
    { file: path.join(MIGRATION_ROOT, 'sqlite', 'V1__baseline.sql'), content: buildSqlite(parsed) },
  ];

  for (const output of outputs) {
    const relative = path.relative(APP_ROOT, output.file);
    if (checkOnly) {
      const existing = fs.existsSync(output.file) ? fs.readFileSync(output.file, 'utf8') : '';
      if (existing !== output.content) {
        console.error(`[gen-baseline] ${relative} 与 PostgreSQL 基线不一致，请重新生成`);
        process.exitCode = 1;
      } else {
        console.log(`[gen-baseline] ${relative} 已同步`);
      }
      continue;
    }
    fs.mkdirSync(path.dirname(output.file), { recursive: true });
    fs.writeFileSync(output.file, output.content, 'utf8');
    console.log(`[gen-baseline] 已生成 ${relative}`);
  }

  console.log(
    `[gen-baseline] 表 ${parsed.tables.length} / 主键 ${parsed.primaryKeys.length} / `
    + `注释 ${parsed.comments.length} / 索引 ${parsed.indexes.length}`,
  );
}

main();
