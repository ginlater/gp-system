"""工牌接诊分析系统 - Web 主应用 (v2: session-centric)

数据模型:
- session  = 同一 (顾问, 顾客, 服务日期) 的一次接诊
- recording = session 下的一段录音文件（可能 1-N 段）
- evaluation = 老板对整个 session 的人工点评

流程（全自动）:
  上传/扫描录音 → 自动 ASR → 同 session 所有录音 ASR 完成 → 自动 Claude 分析
"""
import hashlib
import json
import os
import re
import sqlite3
import threading
import uuid
from datetime import datetime, timedelta
from functools import wraps
from http import HTTPStatus
from pathlib import Path
from urllib import request as urllib_request
from urllib.parse import quote

import oss2
from flask import (Flask, abort, g, jsonify, make_response, redirect,
                   render_template, request, send_file, session, url_for)

import dashscope
from dashscope.audio.asr import Transcription


# ============ 加载 .env ============
def _load_env():
    env_path = Path(__file__).parent / ".env"
    if env_path.exists():
        for line in env_path.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            os.environ.setdefault(k.strip(), v.strip())


_load_env()


# ============ 配置 ============
FLASK_SECRET_KEY = os.environ["FLASK_SECRET_KEY"]
BOSS_USERNAME = os.environ["BOSS_USERNAME"]
BOSS_PASSWORD = os.environ["BOSS_PASSWORD"]

OSS_ACCESS_KEY_ID = os.environ["OSS_ACCESS_KEY_ID"]
OSS_ACCESS_KEY_SECRET = os.environ["OSS_ACCESS_KEY_SECRET"]
OSS_BUCKET_NAME = os.environ["OSS_BUCKET"]
OSS_ENDPOINT = os.environ["OSS_ENDPOINT"]

DASHSCOPE_API_KEY = os.environ["DASHSCOPE_API_KEY"]
ANTHROPIC_API_KEY = os.environ["ANTHROPIC_API_KEY"]
DEEPSEEK_API_KEY = os.environ.get("DEEPSEEK_API_KEY", "")
DEEPSEEK_API_BASE = os.environ.get("DEEPSEEK_API_BASE", "https://api.deepseek.com")

# 豆包（火山方舟 / Ark）OpenAI 兼容接口；豆包用「接入点ID」而非模型名
# 空字符串视为未配置
DOUBAO_API_KEY = os.environ.get("DOUBAO_API_KEY", "")
DOUBAO_API_BASE = os.environ.get("DOUBAO_API_BASE", "https://ark.cn-beijing.volces.com/api/v3")
DOUBAO_EP_LITE = os.environ.get("DOUBAO_EP_LITE", "")  # 极速版接入点ID
DOUBAO_EP_PRO = os.environ.get("DOUBAO_EP_PRO", "")    # 进阶版接入点ID
# 模型 id -> 接入点ID 映射（接入点未配置则为空字符串）
DOUBAO_ENDPOINTS = {
    "doubao-2.0-lite": DOUBAO_EP_LITE,
    "doubao-2.0-pro": DOUBAO_EP_PRO,
}

DB_PATH = os.environ.get("DB_PATH", str(Path(__file__).parent / "recordings.db"))
KB_PATH = os.environ.get("KNOWLEDGE_BASE_PATH",
                         str(Path(__file__).parent / "output" / "logic_library.json"))

DEFAULT_MODEL = os.environ.get("ANALYSIS_MODEL", "deepseek-v4-pro")
ANTHROPIC_PROXY = os.environ.get("ANTHROPIC_PROXY", "http://127.0.0.1:7890")

# 可选分析模型表（前端下拉用）
# provider: anthropic 走代理；deepseek 直连国内不走代理
SUPPORTED_MODELS = [
    {"id": "doubao-2.0-lite",   "provider": "doubao", "tier_label": "极速版",
     "name": "豆包 2.0 Lite",
     "label": "豆包 2.0 极速版（火山方舟，最快最省）"},
    {"id": "doubao-2.0-pro",    "provider": "doubao", "tier_label": "进阶版",
     "name": "豆包 2.0 Pro",
     "label": "豆包 2.0 进阶版（火山方舟，更强）"},
    {"id": "deepseek-v4-pro",   "provider": "deepseek", "tier_label": "专家版",
     "name": "DeepSeek V4 Pro",
     "label": "DeepSeek V4 Pro（国内直连，默认）"},
    {"id": "claude-sonnet-4-6", "provider": "anthropic",
     "label": "Claude Sonnet 4.6（更强但贵且慢）"},
]
# 兼容历史数据库中遗留的 deepseek-chat 模型 id
MODEL_PROVIDER = {m["id"]: m["provider"] for m in SUPPORTED_MODELS}
MODEL_PROVIDER.setdefault("deepseek-chat", "deepseek")

_oss_auth = oss2.Auth(OSS_ACCESS_KEY_ID, OSS_ACCESS_KEY_SECRET)
oss_bucket = oss2.Bucket(_oss_auth, OSS_ENDPOINT, OSS_BUCKET_NAME)
dashscope.api_key = DASHSCOPE_API_KEY


# ============ Flask ============
app = Flask(
    __name__,
    template_folder=str(Path(__file__).parent / "web_v2" / "templates"),
    static_folder=str(Path(__file__).parent / "web_v2" / "static"),
)
app.secret_key = FLASK_SECRET_KEY
app.config["MAX_CONTENT_LENGTH"] = 1024 * 1024 * 1024  # 1GB（支持多文件批量上传）


class PrefixMiddleware:
    """支持反向代理子路径（如 /gp/）"""

    def __init__(self, wsgi_app):
        self.wsgi_app = wsgi_app

    def __call__(self, environ, start_response):
        prefix = environ.get("HTTP_X_FORWARDED_PREFIX", "").rstrip("/")
        if prefix:
            environ["SCRIPT_NAME"] = prefix
            path_info = environ.get("PATH_INFO", "")
            if path_info.startswith(prefix):
                environ["PATH_INFO"] = path_info[len(prefix):] or "/"
        return self.wsgi_app(environ, start_response)


app.wsgi_app = PrefixMiddleware(app.wsgi_app)


# ============ Jinja 过滤器 ============
_MD_BOLD_RE = re.compile(r'\*\*(.+?)\*\*')
_MD_EM_RE = re.compile(r'(?<!\*)\*([^*\n]+?)\*(?!\*)')
import html as _html_mod


@app.template_filter('md_inline')
def md_inline(text):
    """极简的内联 Markdown：**bold** → <strong>，*em* → <em>。先 HTML 转义，再做替换。"""
    if not text:
        return ''
    s = _html_mod.escape(str(text))
    s = _MD_BOLD_RE.sub(r'<strong>\1</strong>', s)
    s = _MD_EM_RE.sub(r'<em>\1</em>', s)
    s = s.replace('\n', '<br>')
    return s


_CHN_NUMS = ['零', '一', '二', '三', '四', '五', '六', '七', '八', '九', '十']


@app.template_filter('chinese_num')
def chinese_num(n):
    n = int(n)
    if 0 <= n <= 10:
        return _CHN_NUMS[n]
    if 11 <= n <= 19:
        return '十' + _CHN_NUMS[n - 10]
    return str(n)


# ============ SQLite ============
# 写队列：一个专用线程串行消费所有写操作，避免高并发时多线程竞争写锁。
# 每条写任务是 (sql, params, future)；future=None 表示 fire-and-forget。
import queue as _queue
from concurrent.futures import Future as _Future

_db_write_queue: "_queue.Queue[tuple]" = _queue.Queue()
_db_lock = threading.Lock()  # 保留，供 db_exec 等遗留调用使用


def _db_writer_loop():
    """单线程消费写队列，持有一个长连接。
    队列元素有两种形态：
      - (sql, params, fut)    — 单条写，返回 lastrowid
      - (callable, fut)       — 批量事务，callable(conn) 由调用方负责 commit/rollback
    """
    conn = sqlite3.connect(DB_PATH, check_same_thread=False, timeout=30.0)
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA synchronous=NORMAL")
    conn.execute("PRAGMA busy_timeout=10000")
    while True:
        item = _db_write_queue.get()
        if item is None:
            break
        if callable(item[0]):
            fn, fut = item
            try:
                result = fn(conn)
                if fut is not None:
                    fut.set_result(result)
            except Exception as e:
                try:
                    conn.rollback()
                except Exception:
                    pass
                if fut is not None:
                    fut.set_exception(e)
                else:
                    print(f"[db_writer] 批量写失败: {e}")
        else:
            sql, params, fut = item
            try:
                cur = conn.execute(sql, params)
                conn.commit()
                if fut is not None:
                    fut.set_result(cur.lastrowid)
            except Exception as e:
                conn.rollback()
                if fut is not None:
                    fut.set_exception(e)
                else:
                    print(f"[db_writer] fire-and-forget 写失败: {e} | sql={sql[:120]}")


_db_writer_thread = threading.Thread(target=_db_writer_loop, daemon=True, name="db-writer")
_db_writer_thread.start()

SCHEMA_SQL = """
CREATE TABLE IF NOT EXISTS sessions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    advisor TEXT,
    customer TEXT,
    service_date TEXT,
    created_at TEXT DEFAULT (datetime('now', 'localtime')),

    analysis_status TEXT DEFAULT 'pending',
    analysis_result TEXT,
    analysis_error TEXT,
    analysis_started_at TEXT,
    analysis_finished_at TEXT,
    analysis_signature TEXT,
    analysis_scores TEXT,
    analysis_model TEXT,
    analysis_progress TEXT,
    task_status TEXT,  -- JSON: {"T1":{"status":"done","updated_at":"...","error":null}, ...}

    has_evaluation INTEGER DEFAULT 0,

    UNIQUE(advisor, customer, service_date)
);

CREATE TABLE IF NOT EXISTS recordings (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id INTEGER,
    oss_key TEXT UNIQUE NOT NULL,
    advisor TEXT,
    customer TEXT,
    recorded_at TEXT,
    duration_label TEXT,
    size_bytes INTEGER,
    source TEXT,
    created_at TEXT DEFAULT (datetime('now', 'localtime')),

    asr_status TEXT DEFAULT 'pending',
    asr_result_json TEXT,
    asr_transcript TEXT,
    asr_error TEXT,
    asr_started_at TEXT,
    asr_finished_at TEXT,

    FOREIGN KEY (session_id) REFERENCES sessions(id)
);

CREATE INDEX IF NOT EXISTS idx_recordings_session ON recordings(session_id);

CREATE TABLE IF NOT EXISTS evaluations (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id INTEGER NOT NULL,
    wrong_points TEXT,
    improvement TEXT,
    correct_practice TEXT,
    comment TEXT,
    created_at TEXT DEFAULT (datetime('now', 'localtime')),
    updated_at TEXT,
    FOREIGN KEY (session_id) REFERENCES sessions(id)
);

CREATE INDEX IF NOT EXISTS idx_evaluations_session ON evaluations(session_id);

CREATE TABLE IF NOT EXISTS customer_tags (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    customer_name TEXT NOT NULL,
    advisor_name TEXT,
    tag TEXT NOT NULL,
    source_session_id INTEGER,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_ct_name ON customer_tags(customer_name);

-- ============ 角色 / 多公司 ============
CREATE TABLE IF NOT EXISTS companies (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL UNIQUE,
    created_at TEXT DEFAULT (datetime('now', 'localtime'))
);

CREATE TABLE IF NOT EXISTS users (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    username TEXT NOT NULL UNIQUE,  -- 顾问用手机号；admin 用 env BOSS_USERNAME
    password_hash TEXT NOT NULL,
    role TEXT NOT NULL DEFAULT 'consultant',  -- super | admin | consultant | store_manager
    company_id INTEGER,
    advisor_name TEXT,   -- 顾问姓名（用于匹配 sessions.advisor）
    employee_id TEXT,    -- 工号
    phone TEXT,          -- 手机号
    created_at TEXT DEFAULT (datetime('now', 'localtime')),
    FOREIGN KEY (company_id) REFERENCES companies(id)
);
CREATE INDEX IF NOT EXISTS idx_users_company ON users(company_id);
CREATE INDEX IF NOT EXISTS idx_users_advisor ON users(advisor_name);

CREATE TABLE IF NOT EXISTS company_customers (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    company_id INTEGER NOT NULL,
    name TEXT NOT NULL,
    member_card TEXT,
    phone_tail TEXT,  -- 手机后4位，新增客户时必填
    created_at TEXT DEFAULT (datetime('now', 'localtime')),
    UNIQUE(company_id, name),
    FOREIGN KEY (company_id) REFERENCES companies(id)
);
CREATE INDEX IF NOT EXISTS idx_cc_company ON company_customers(company_id);

-- ============ 今日接诊白名单 ============
CREATE TABLE IF NOT EXISTS daily_reception (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    company_id INTEGER NOT NULL,
    advisor_user_id INTEGER NOT NULL,
    advisor_name TEXT,
    customer_id INTEGER NOT NULL,
    service_date TEXT NOT NULL,  -- YYYY-MM-DD
    created_at TEXT DEFAULT (datetime('now', 'localtime')),
    UNIQUE(advisor_user_id, customer_id, service_date),
    FOREIGN KEY (customer_id) REFERENCES company_customers(id)
);
CREATE INDEX IF NOT EXISTS idx_dr_advisor_date ON daily_reception(advisor_user_id, service_date);

CREATE TABLE IF NOT EXISTS delete_requests (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    recording_id INTEGER NOT NULL,
    session_id INTEGER,
    requester_user_id INTEGER NOT NULL,
    requester_name TEXT,
    reason TEXT,
    status TEXT NOT NULL DEFAULT 'pending',  -- pending | approved | rejected
    reviewer_user_id INTEGER,
    reviewer_name TEXT,
    reviewed_at TEXT,
    reject_reason TEXT,
    created_at TEXT DEFAULT (datetime('now', 'localtime')),
    FOREIGN KEY (recording_id) REFERENCES recordings(id)
);
CREATE INDEX IF NOT EXISTS idx_dr_status ON delete_requests(status);
CREATE INDEX IF NOT EXISTS idx_dr_recording ON delete_requests(recording_id);

CREATE TABLE IF NOT EXISTS rebind_requests (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    company_id INTEGER NOT NULL,
    recording_id INTEGER NOT NULL,
    rec_date TEXT,
    requester_user_id INTEGER NOT NULL,
    requester_name TEXT,
    from_session_id INTEGER,
    from_customer_id INTEGER,
    from_customer_name TEXT,
    to_customer_id INTEGER NOT NULL,
    to_customer_name TEXT,
    reason TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'pending',  -- pending | approved | rejected
    reviewer_user_id INTEGER,
    reviewer_name TEXT,
    reviewed_at TEXT,
    reject_reason TEXT,
    created_at TEXT DEFAULT (datetime('now', 'localtime')),
    FOREIGN KEY (recording_id) REFERENCES recordings(id)
);
CREATE INDEX IF NOT EXISTS idx_rebind_status ON rebind_requests(status);
CREATE INDEX IF NOT EXISTS idx_rebind_recording ON rebind_requests(recording_id);
"""


def get_db():
    if "db" not in g:
        g.db = sqlite3.connect(DB_PATH, check_same_thread=False)
        g.db.row_factory = sqlite3.Row
        g.db.execute("PRAGMA foreign_keys = ON")
    return g.db


@app.teardown_appcontext
def close_db(_exc):
    db = g.pop("db", None)
    if db is not None:
        db.close()


def init_db():
    conn = sqlite3.connect(DB_PATH)
    # WAL：读不阻塞写、写不阻塞读；synchronous=NORMAL 在 WAL 下安全且更快；
    # busy_timeout：拿不到锁时等待而不是立刻 SQLITE_BUSY 抛错
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA synchronous=NORMAL")
    conn.execute("PRAGMA busy_timeout=5000")
    conn.executescript(SCHEMA_SQL)
    # 顾问 × 顾客 × 服务日期 × 公司 唯一，防并发绑定重复建 session
    # （orphan session 用 fake_date "?-xxxx"，天然不冲突）
    conn.execute(
        "CREATE UNIQUE INDEX IF NOT EXISTS uq_sessions_acsc "
        "ON sessions(advisor, customer, service_date, company_id)"
    )
    # 轻量迁移：补缺失的列
    existing = {r[1] for r in conn.execute("PRAGMA table_info(sessions)").fetchall()}
    if "analysis_model" not in existing:
        conn.execute("ALTER TABLE sessions ADD COLUMN analysis_model TEXT")
    if "analysis_progress" not in existing:
        conn.execute("ALTER TABLE sessions ADD COLUMN analysis_progress TEXT")
    if "task_status" not in existing:
        conn.execute("ALTER TABLE sessions ADD COLUMN task_status TEXT")
    # 历史 evaluations 表是 UNIQUE(session_id)，迁移到允许多条
    eval_cols = {r[1] for r in conn.execute("PRAGMA table_info(evaluations)").fetchall()}
    if "comment" not in eval_cols:
        try:
            conn.execute("ALTER TABLE evaluations ADD COLUMN comment TEXT")
        except sqlite3.OperationalError:
            pass
    # 检查 UNIQUE 约束；如果还在就重建表
    idx_rows = conn.execute(
        "SELECT sql FROM sqlite_master WHERE type='table' AND name='evaluations'"
    ).fetchone()
    if idx_rows and "UNIQUE" in (idx_rows[0] or ""):
        conn.executescript("""
            CREATE TABLE evaluations_new (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id INTEGER NOT NULL,
                wrong_points TEXT,
                improvement TEXT,
                correct_practice TEXT,
                comment TEXT,
                created_at TEXT DEFAULT (datetime('now', 'localtime')),
                updated_at TEXT
            );
            INSERT INTO evaluations_new
              (id, session_id, wrong_points, improvement, correct_practice, comment, created_at, updated_at)
            SELECT id, session_id, wrong_points, improvement, correct_practice,
                   COALESCE(comment, ''), created_at, updated_at
            FROM evaluations;
            DROP TABLE evaluations;
            ALTER TABLE evaluations_new RENAME TO evaluations;
            CREATE INDEX IF NOT EXISTS idx_evaluations_session ON evaluations(session_id);
        """)
    # sessions / recordings 加 company_id
    sess_cols = {r[1] for r in conn.execute("PRAGMA table_info(sessions)").fetchall()}
    if "company_id" not in sess_cols:
        conn.execute("ALTER TABLE sessions ADD COLUMN company_id INTEGER DEFAULT 1")
    rec_cols = {r[1] for r in conn.execute("PRAGMA table_info(recordings)").fetchall()}
    if "company_id" not in rec_cols:
        conn.execute("ALTER TABLE recordings ADD COLUMN company_id INTEGER DEFAULT 1")
    if "uploader_user_id" not in rec_cols:
        conn.execute("ALTER TABLE recordings ADD COLUMN uploader_user_id INTEGER")
    if "asr_speaker_count" not in rec_cols:
        conn.execute("ALTER TABLE recordings ADD COLUMN asr_speaker_count INTEGER")
    if "asr_speaker_warning" not in rec_cols:
        conn.execute("ALTER TABLE recordings ADD COLUMN asr_speaker_warning INTEGER DEFAULT 0")
    if "speaker_confirmed" not in rec_cols:
        conn.execute("ALTER TABLE recordings ADD COLUMN speaker_confirmed INTEGER DEFAULT 0")
    if "upload_status" not in rec_cols:
        conn.execute("ALTER TABLE recordings ADD COLUMN upload_status TEXT DEFAULT 'done'")
    # 2026-06-07 断流截断告警：上报时长(墙钟)远大于实测音频时长(ffprobe)时打标，
    # 兜底提醒"上报X分钟、实际只录到Y秒，疑似蓝牙断流丢失"，并保留原始件待复检。
    if "truncate_note" not in rec_cols:
        conn.execute("ALTER TABLE recordings ADD COLUMN truncate_note TEXT")
    # 2026-06-08 录音笔SN绑定：每个顾问绑一台笔的SN，App 端只准用绑定那台录音。
    #   recordings.device_sn = 产生该录音的笔SN(审计)；users.pen_sn = 绑定的SN；
    #   pen_sn_sightings = 该顾问连过/用过的SN(供管理员从下拉里选着绑，不用手抄)。
    if "device_sn" not in rec_cols:
        conn.execute("ALTER TABLE recordings ADD COLUMN device_sn TEXT")
    # 2026-06-08 录音笔机身文件名（手动同步去重用：同一上传人同一 pen_file 只入一条）
    if "pen_file" not in rec_cols:
        conn.execute("ALTER TABLE recordings ADD COLUMN pen_file TEXT")
    # 2026-06-08 删除墓碑：录音被删时记一条，"从录音笔同步"据此显示"已删"、不复活
    conn.execute("""
        CREATE TABLE IF NOT EXISTS pen_tombstone (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            uploader_user_id INTEGER,
            pen_file TEXT,
            recorded_at TEXT,
            deleted_at TEXT DEFAULT (datetime('now','localtime'))
        )
    """)
    conn.execute("CREATE INDEX IF NOT EXISTS idx_pen_tomb_user ON pen_tombstone(uploader_user_id)")
    user_cols = {r[1] for r in conn.execute("PRAGMA table_info(users)").fetchall()}
    if "pen_sn" not in user_cols:
        conn.execute("ALTER TABLE users ADD COLUMN pen_sn TEXT")
    conn.execute("""
        CREATE TABLE IF NOT EXISTS pen_sn_sightings (
            user_id INTEGER NOT NULL,
            sn TEXT NOT NULL,
            last_seen_at TEXT DEFAULT (datetime('now','localtime')),
            PRIMARY KEY (user_id, sn)
        )
    """)

    # 2026-05-28 customer_tags 加 mention_count（本次录音里顾客提及该标签话题的次数）
    ct_cols = {r[1] for r in conn.execute("PRAGMA table_info(customer_tags)").fetchall()}
    if "mention_count" not in ct_cols:
        conn.execute("ALTER TABLE customer_tags ADD COLUMN mention_count INTEGER DEFAULT 1")

    # 2026-05-28 顾问查看报告埋点表
    conn.execute("""
        CREATE TABLE IF NOT EXISTS report_view_events (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id INTEGER,
            username TEXT,
            role TEXT,
            company_id INTEGER,
            session_id INTEGER,
            source TEXT,
            part_key TEXT,
            event TEXT,
            duration_ms INTEGER,
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
        )
    """)
    conn.execute("CREATE INDEX IF NOT EXISTS idx_rve_user ON report_view_events(user_id, created_at)")
    conn.execute("CREATE INDEX IF NOT EXISTS idx_rve_session ON report_view_events(session_id)")
    conn.execute("CREATE INDEX IF NOT EXISTS idx_rve_company ON report_view_events(company_id, created_at)")

    # 2026-05-28 差评高风险预警查看记录（每个管理员单独记一条 viewed）
    conn.execute("""
        CREATE TABLE IF NOT EXISTS high_risk_views (
            session_id INTEGER NOT NULL,
            user_id INTEGER NOT NULL,
            viewed_at TEXT DEFAULT (datetime('now', 'localtime')),
            PRIMARY KEY (session_id, user_id)
        )
    """)

    # 2026-05-25 接诊包改造：phone_tail / locked / customer_id / daily_reception
    cc_cols = {r[1] for r in conn.execute("PRAGMA table_info(company_customers)").fetchall()}
    if "phone_tail" not in cc_cols:
        conn.execute("ALTER TABLE company_customers ADD COLUMN phone_tail TEXT")
    sess_cols2 = {r[1] for r in conn.execute("PRAGMA table_info(sessions)").fetchall()}
    if "locked" not in sess_cols2:
        conn.execute("ALTER TABLE sessions ADD COLUMN locked INTEGER DEFAULT 0")
    if "customer_id" not in sess_cols2:
        conn.execute("ALTER TABLE sessions ADD COLUMN customer_id INTEGER")

    # 2026-05-27 换绑/退回未归档：rebind_requests 表加 action 列（rebind / unbind）
    rb_cols = {r[1] for r in conn.execute("PRAGMA table_info(rebind_requests)").fetchall()}
    if "action" not in rb_cols:
        conn.execute("ALTER TABLE rebind_requests ADD COLUMN action TEXT DEFAULT 'rebind'")

    # 2026-05-27 删除申请：加 withdrawn 状态 + dismissed_at 标记拒绝是否被顾问看到过
    dr_cols = {r[1] for r in conn.execute("PRAGMA table_info(delete_requests)").fetchall()}
    if "dismissed_at" not in dr_cols:
        conn.execute("ALTER TABLE delete_requests ADD COLUMN dismissed_at TEXT")

    # 2026-05-28 允许同名顾客并存 + session 按 customer_id 聚合
    # company_customers 旧版有 UNIQUE(company_id, name)，需要拆掉
    idx_rows = conn.execute("PRAGMA index_list(company_customers)").fetchall()
    has_legacy_unique = False
    for ir in idx_rows:
        if ir[2]:  # unique=1
            cols = [c[2] for c in conn.execute(f"PRAGMA index_info({ir[1]})").fetchall()]
            if set(cols) == {"company_id", "name"}:
                has_legacy_unique = True
                break
    if has_legacy_unique:
        conn.executescript("""
            CREATE TABLE company_customers_new (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                company_id INTEGER NOT NULL,
                name TEXT NOT NULL,
                member_card TEXT,
                phone_tail TEXT,
                created_at TEXT DEFAULT (datetime('now', 'localtime'))
            );
            INSERT INTO company_customers_new (id, company_id, name, member_card, phone_tail, created_at)
                SELECT id, company_id, name, member_card, phone_tail, created_at FROM company_customers;
            DROP TABLE company_customers;
            ALTER TABLE company_customers_new RENAME TO company_customers;
            CREATE INDEX IF NOT EXISTS idx_cc_company ON company_customers(company_id);
            CREATE INDEX IF NOT EXISTS idx_cc_name ON company_customers(company_id, name);
            CREATE UNIQUE INDEX IF NOT EXISTS uq_cc_member ON company_customers(company_id, member_card)
                WHERE member_card IS NOT NULL AND member_card != '';
        """)
    else:
        conn.execute(
            "CREATE UNIQUE INDEX IF NOT EXISTS uq_cc_member ON company_customers(company_id, member_card) "
            "WHERE member_card IS NOT NULL AND member_card != ''"
        )

    # sessions：旧表有 table-level UNIQUE(advisor, customer, service_date) 自动索引，
    # 阻止同名客户分别建 session；rebuild 表去掉这个约束
    sess_idx = conn.execute("PRAGMA index_list(sessions)").fetchall()
    has_legacy_sess_unique = any(
        (ix[2] and ix[1].startswith("sqlite_autoindex_sessions"))
        for ix in sess_idx
    )
    if has_legacy_sess_unique:
        conn.executescript("""
            CREATE TABLE sessions_new (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                advisor TEXT,
                customer TEXT,
                service_date TEXT,
                created_at TEXT DEFAULT (datetime('now', 'localtime')),
                analysis_status TEXT DEFAULT 'pending',
                analysis_result TEXT,
                analysis_error TEXT,
                analysis_started_at TEXT,
                analysis_finished_at TEXT,
                analysis_signature TEXT,
                has_evaluation INTEGER DEFAULT 0,
                analysis_scores TEXT,
                analysis_model TEXT,
                analysis_progress TEXT,
                task_status TEXT,
                company_id INTEGER DEFAULT 1,
                locked INTEGER DEFAULT 0,
                customer_id INTEGER
            );
            INSERT INTO sessions_new SELECT
                id, advisor, customer, service_date, created_at,
                analysis_status, analysis_result, analysis_error,
                analysis_started_at, analysis_finished_at, analysis_signature,
                has_evaluation, analysis_scores, analysis_model, analysis_progress,
                task_status, company_id, locked, customer_id
            FROM sessions;
            DROP TABLE sessions;
            ALTER TABLE sessions_new RENAME TO sessions;
        """)
    conn.execute("DROP INDEX IF EXISTS uq_sessions_acsc")
    conn.execute(
        "CREATE UNIQUE INDEX IF NOT EXISTS uq_sessions_acid "
        "ON sessions(advisor, customer_id, service_date, company_id) "
        "WHERE customer_id IS NOT NULL"
    )
    conn.execute(
        "CREATE UNIQUE INDEX IF NOT EXISTS uq_sessions_acn "
        "ON sessions(advisor, customer, service_date, company_id) "
        "WHERE customer_id IS NULL"
    )

    # seed: 默认公司 + BOSS 管理员
    conn.execute("INSERT OR IGNORE INTO companies (id, name) VALUES (1, '默认公司')")
    boss_u = os.environ.get("BOSS_USERNAME", "boss")
    boss_p = os.environ.get("BOSS_PASSWORD", "boss")
    boss_hash = hashlib.sha256(boss_p.encode("utf-8")).hexdigest()
    row = conn.execute("SELECT id, role FROM users WHERE username=?", (boss_u,)).fetchone()
    if not row:
        conn.execute(
            """INSERT INTO users (username, password_hash, role, company_id, advisor_name)
               VALUES (?, ?, 'admin', 1, ?)""",
            (boss_u, boss_hash, "管理员"),
        )
    else:
        # 同步密码（方便用 env 改），保留 role
        conn.execute(
            "UPDATE users SET password_hash=? WHERE id=?", (boss_hash, row[0])
        )

    # 2026-05-29 门店层级：stores 表 + 事件表 store_id + 回填默认门店
    # store_id 只加在「服务事件」相关表（users/sessions/recordings/daily_reception）；
    # company_customers 是公司级共享主档（同一客人可跨店消费），不按门店切分。
    conn.execute("""
        CREATE TABLE IF NOT EXISTS stores (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            company_id INTEGER NOT NULL,
            name TEXT NOT NULL,
            region TEXT,
            created_at TEXT DEFAULT (datetime('now', 'localtime')),
            UNIQUE(company_id, name),
            FOREIGN KEY (company_id) REFERENCES companies(id)
        )
    """)
    conn.execute("CREATE INDEX IF NOT EXISTS idx_stores_company ON stores(company_id)")
    for _tbl in ("users", "sessions", "recordings", "daily_reception"):
        _cols = {r[1] for r in conn.execute(f"PRAGMA table_info({_tbl})").fetchall()}
        if "store_id" not in _cols:
            conn.execute(f"ALTER TABLE {_tbl} ADD COLUMN store_id INTEGER")
    # 每个公司：仅当存在 store_id 为空的行时，才建「默认门店」兜底回填。
    # （幂等关键：首次迁移把存量数据归入默认门店；之后没有 NULL 行就不再创建，
    #  否则用户把默认门店改名后，每次重启都会重复生成一个空的「默认门店」。）
    for (_cid,) in conn.execute("SELECT id FROM companies").fetchall():
        _need = conn.execute(
            "SELECT (SELECT COUNT(*) FROM users WHERE company_id=? AND store_id IS NULL)"
            " + (SELECT COUNT(*) FROM sessions WHERE company_id=? AND store_id IS NULL)"
            " + (SELECT COUNT(*) FROM recordings WHERE company_id=? AND store_id IS NULL)"
            " + (SELECT COUNT(*) FROM daily_reception WHERE company_id=? AND store_id IS NULL)",
            (_cid, _cid, _cid, _cid)).fetchone()[0]
        if not _need:
            continue
        conn.execute("INSERT OR IGNORE INTO stores (company_id, name) VALUES (?, '默认门店')", (_cid,))
        _sid = conn.execute(
            "SELECT id FROM stores WHERE company_id=? AND name='默认门店'", (_cid,)
        ).fetchone()[0]
        conn.execute("UPDATE users SET store_id=? WHERE company_id=? AND store_id IS NULL", (_sid, _cid))
        conn.execute("UPDATE sessions SET store_id=? WHERE company_id=? AND store_id IS NULL", (_sid, _cid))
        conn.execute("UPDATE recordings SET store_id=? WHERE company_id=? AND store_id IS NULL", (_sid, _cid))
        conn.execute("UPDATE daily_reception SET store_id=? WHERE company_id=? AND store_id IS NULL", (_sid, _cid))
    # 兜底：仍有 NULL 门店的（company_id 异常），且确有「默认门店」时归入
    _def1 = conn.execute("SELECT id FROM stores WHERE company_id=1 AND name='默认门店'").fetchone()
    if _def1 and conn.execute(
        "SELECT 1 FROM (SELECT store_id FROM users UNION ALL SELECT store_id FROM sessions "
        "UNION ALL SELECT store_id FROM recordings UNION ALL SELECT store_id FROM daily_reception) "
        "WHERE store_id IS NULL LIMIT 1").fetchone():
        for _tbl in ("users", "sessions", "recordings", "daily_reception"):
            conn.execute(f"UPDATE {_tbl} SET store_id=? WHERE store_id IS NULL", (_def1[0],))
    conn.execute("CREATE INDEX IF NOT EXISTS idx_sessions_store ON sessions(store_id)")
    conn.execute("CREATE INDEX IF NOT EXISTS idx_recordings_store ON recordings(store_id)")

    # 2026-05-29 取消"多说话人(>2)误录确认"机制：清掉历史警告标记（幂等）
    conn.execute("UPDATE recordings SET asr_speaker_warning=0 WHERE asr_speaker_warning=1")

    # 2026-05-29 模块2 标签归一化词典
    conn.execute("""
        CREATE TABLE IF NOT EXISTS tag_dictionary (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            company_id INTEGER NOT NULL,
            category TEXT,
            canonical_tag TEXT NOT NULL,
            synonyms TEXT DEFAULT '[]',      -- JSON 数组
            status TEXT NOT NULL DEFAULT 'active',  -- active | blacklist
            created_at TEXT DEFAULT (datetime('now', 'localtime')),
            UNIQUE(company_id, canonical_tag)
        )
    """)
    conn.execute("CREATE INDEX IF NOT EXISTS idx_tagdict_company ON tag_dictionary(company_id)")
    # 2026-06-04 Wave5 标签归一：AI 归并建议（人工审核后才落库；不自动改 tag_dictionary/customer_tags）
    conn.execute("""
        CREATE TABLE IF NOT EXISTS tag_merge_suggestions (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            company_id INTEGER NOT NULL,
            category TEXT,
            canonical TEXT NOT NULL,         -- 该组选定的标准词
            members_json TEXT DEFAULT '[]',  -- JSON 数组：被归并的碎片词
            sample_count INTEGER DEFAULT 0,  -- 该组碎片词在 customer_tags 里的总出现次数
            status TEXT NOT NULL DEFAULT 'pending',  -- pending | applied | rejected
            created_at TEXT DEFAULT (datetime('now', 'localtime')),
            applied_at TEXT
        )
    """)
    conn.execute("CREATE INDEX IF NOT EXISTS idx_tagmerge_company ON tag_merge_suggestions(company_id, status)")
    # customer_tags 加 canonical_tag(归一标准词) + service_date(接诊日期，为时间筛选铺路)
    ct_cols2 = {r[1] for r in conn.execute("PRAGMA table_info(customer_tags)").fetchall()}
    if "canonical_tag" not in ct_cols2:
        conn.execute("ALTER TABLE customer_tags ADD COLUMN canonical_tag TEXT")
    if "service_date" not in ct_cols2:
        conn.execute("ALTER TABLE customer_tags ADD COLUMN service_date TEXT")
    conn.execute("CREATE INDEX IF NOT EXISTS idx_ct_canonical ON customer_tags(canonical_tag)")
    # 预填：用 tag_taxonomy 里 6 个"顾客标签"大类做种子（顾问话术/流程类不算顾客标签）
    try:
        import json as _json
        _kbp = globals().get("KB_PATH")
        with open(_kbp, encoding="utf-8") as _f:
            _l2 = ((_json.load(_f) or {}).get("tag_taxonomy") or {}).get("L2_categories") or {}
        _seed_cats = ("客人新老", "顾客类型", "顾客画像", "顾客痛点", "顾客状态", "需求类型")
        _seed_pairs = [(c, v) for c in _seed_cats for v in (_l2.get(c) or [])]
        for (_cid,) in conn.execute("SELECT id FROM companies").fetchall():
            for _cat, _val in _seed_pairs:
                conn.execute(
                    "INSERT OR IGNORE INTO tag_dictionary (company_id, category, canonical_tag, synonyms, status) "
                    "VALUES (?, ?, ?, '[]', 'active')",
                    (_cid, _cat, _val),
                )
    except Exception as _e:
        print(f"[tag_dictionary seed] 跳过：{_e}", flush=True)
    # 回填：service_date 取自来源 session；canonical_tag 仅对"恰好等于某标准词"的做精确归一（其余留待归类）
    conn.execute(
        """UPDATE customer_tags SET service_date = (
               SELECT s.service_date FROM sessions s WHERE s.id = customer_tags.source_session_id
           ) WHERE service_date IS NULL AND source_session_id IS NOT NULL"""
    )
    conn.execute(
        """UPDATE customer_tags SET canonical_tag = tag
           WHERE canonical_tag IS NULL AND EXISTS (
               SELECT 1 FROM tag_dictionary d
               JOIN sessions s ON s.id = customer_tags.source_session_id
               WHERE d.company_id = s.company_id
                 AND d.canonical_tag = customer_tags.tag
                 AND d.status = 'active'
           )"""
    )

    # 2026-05-29 画像-4 当月重点项目清单（按公司+月份）
    conn.execute("""
        CREATE TABLE IF NOT EXISTS monthly_projects (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            company_id INTEGER NOT NULL,
            month TEXT NOT NULL,          -- 'YYYY-MM'
            name TEXT NOT NULL,           -- 项目名
            keywords TEXT DEFAULT '[]',   -- JSON 数组：命中关键词
            created_at TEXT DEFAULT (datetime('now', 'localtime'))
        )
    """)
    conn.execute("CREATE INDEX IF NOT EXISTS idx_mp_company_month ON monthly_projects(company_id, month)")

    # 2026-05-29 画像-5 客户价值预测缓存（按公司+客人）
    conn.execute("""
        CREATE TABLE IF NOT EXISTS customer_value_cache (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            company_id INTEGER NOT NULL,
            customer_id INTEGER,
            customer_name TEXT,
            content TEXT,                 -- JSON：各维度结果
            source_signature TEXT,        -- 历史 session 指纹，变了则过期
            model TEXT,
            generated_at TEXT,
            UNIQUE(company_id, customer_id, customer_name)
        )
    """)
    conn.execute("CREATE INDEX IF NOT EXISTS idx_cvc_lookup ON customer_value_cache(company_id, customer_id, customer_name)")

    # 2026-05-29 客户合并/拆分工具：company_customers 加 merged_into（NULL=有效；非空=已被合并进该 id）
    cc_cols2 = {r[1] for r in conn.execute("PRAGMA table_info(company_customers)").fetchall()}
    if "merged_into" not in cc_cols2:
        conn.execute("ALTER TABLE company_customers ADD COLUMN merged_into INTEGER")
    conn.execute("CREATE INDEX IF NOT EXISTS idx_cc_merged ON company_customers(merged_into)")
    # 合并日志（affected 存被改动行快照，供拆分还原）
    conn.execute("""
        CREATE TABLE IF NOT EXISTS customer_merge_log (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            company_id INTEGER,
            from_customer_id INTEGER,
            from_name TEXT,
            into_customer_id INTEGER,
            into_name TEXT,
            operator_user_id INTEGER,
            operator_name TEXT,
            reason TEXT,
            affected TEXT,                -- JSON 快照
            created_at TEXT DEFAULT (datetime('now', 'localtime')),
            undone INTEGER DEFAULT 0,
            undone_at TEXT
        )
    """)
    conn.execute("CREATE INDEX IF NOT EXISTS idx_cml_company ON customer_merge_log(company_id, id)")

    # 2026-05-29 模块4 提醒系统：reminder_config（按公司，store_id 可空=公司默认） + reminder_log
    conn.execute("""
        CREATE TABLE IF NOT EXISTS reminder_config (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            company_id INTEGER NOT NULL,
            store_id INTEGER,                       -- NULL = 公司默认配置
            enable_phone INTEGER DEFAULT 0,         -- 是否启用电话提醒（stub）
            remind_after_hours INTEGER DEFAULT 2,   -- 几小时后开始提醒（一级阈值）
            max_per_day INTEGER DEFAULT 3,          -- 每天最多对同一对象提醒几次
            avoid_offwork INTEGER DEFAULT 1,        -- 是否避开非工作时间（仅影响电话级）
            work_start TEXT DEFAULT '09:00',
            work_end TEXT DEFAULT '21:00',
            retry_on_fail INTEGER DEFAULT 0,        -- 提醒（电话）失败是否重拨（stub 占位）
            log_results INTEGER DEFAULT 1,          -- 是否记录提醒结果
            updated_at TEXT DEFAULT (datetime('now', 'localtime'))
        )
    """)
    conn.execute(
        "CREATE UNIQUE INDEX IF NOT EXISTS uq_reminder_config "
        "ON reminder_config(company_id, IFNULL(store_id, -1))"
    )
    conn.execute("""
        CREATE TABLE IF NOT EXISTS reminder_log (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            company_id INTEGER,
            store_id INTEGER,
            target_user_id INTEGER,
            target_name TEXT,
            kind TEXT,                              -- 'unbound' | 'unviewed'
            level INTEGER,                          -- 1 / 2 / 3
            channel TEXT,                           -- 'inapp' | 'phone' | 'sms' | 'wecom' | 'board'
            ref_type TEXT,                          -- 'recording' | 'session'
            ref_id INTEGER,
            message TEXT,
            result TEXT,
            processed INTEGER DEFAULT 0,
            processed_at TEXT,
            created_at TEXT DEFAULT (datetime('now', 'localtime'))
        )
    """)
    conn.execute("CREATE INDEX IF NOT EXISTS idx_reminder_log_company ON reminder_log(company_id, id)")
    conn.execute("CREATE INDEX IF NOT EXISTS idx_reminder_log_target ON reminder_log(target_user_id, processed)")
    conn.execute(
        "CREATE INDEX IF NOT EXISTS idx_reminder_log_dedup "
        "ON reminder_log(kind, ref_type, ref_id, level, created_at)"
    )

    # 2026-06-03 提醒子系统大改：升级项(escalation) + 店长「已读/已处理」状态
    # 新增列：read_at（店长拉取升级项即标已读）、handled_at/handled_by（店长在看板点「已跟进」）
    rl_cols = {r[1] for r in conn.execute("PRAGMA table_info(reminder_log)").fetchall()}
    if "read_at" not in rl_cols:
        conn.execute("ALTER TABLE reminder_log ADD COLUMN read_at TEXT")
    if "handled_at" not in rl_cols:
        conn.execute("ALTER TABLE reminder_log ADD COLUMN handled_at TEXT")
    if "handled_by" not in rl_cols:
        conn.execute("ALTER TABLE reminder_log ADD COLUMN handled_by INTEGER")
    conn.execute(
        "CREATE INDEX IF NOT EXISTS idx_reminder_log_escalation "
        "ON reminder_log(channel, store_id, processed)"
    )

    # 一次性数据清理（带 marker，只跑一次）：
    # 历史上 inapp 提醒「按天判重」→ 同一对象(kind,ref_type,ref_id,target_user_id,level)
    # 累积了多行未处理 inapp（12 倍刷屏）。把每组只保留最新 1 行，其余 processed=2。
    conn.execute("""
        CREATE TABLE IF NOT EXISTS app_migration_marker (
            name TEXT PRIMARY KEY,
            done_at TEXT DEFAULT (datetime('now', 'localtime'))
        )
    """)
    _mk = conn.execute(
        "SELECT 1 FROM app_migration_marker WHERE name=?",
        ("reminder_inapp_dedup_2026_06_03",),
    ).fetchone()
    if not _mk:
        try:
            # 折叠未处理 inapp：每个 (kind,ref_type,ref_id,target_user_id,level) 只留最大 id，
            # 其余置 processed=2（表示被去重折叠，非用户真正处理）。
            conn.execute("""
                UPDATE reminder_log SET processed=2,
                    processed_at=datetime('now','localtime')
                WHERE channel='inapp' AND processed=0
                  AND id NOT IN (
                      SELECT MAX(id) FROM reminder_log
                      WHERE channel='inapp' AND processed=0
                      GROUP BY kind, ref_type, ref_id,
                               IFNULL(target_user_id,-1), IFNULL(level,-1)
                  )
            """)
            # 历史 board 升级行 target 是顾问、店长收不到——本次改用 channel='escalation'。
            # 把历史 unviewed 的 board 行作废（processed=2），下一轮 scan 会按新模型重建 escalation。
            conn.execute("""
                UPDATE reminder_log SET processed=2,
                    processed_at=datetime('now','localtime')
                WHERE channel='board' AND kind='unviewed' AND processed=0
            """)
            conn.execute(
                "INSERT INTO app_migration_marker(name) VALUES (?)",
                ("reminder_inapp_dedup_2026_06_03",),
            )
        except Exception as _e:
            print(f"[migration reminder_inapp_dedup] 跳过：{_e}", flush=True)

    # 2026-06-04 customer_tags 身份地基：加 customer_id 列 + 索引；并回填一次。
    # 背景：customer_tags 历史只按 customer_name 关联，同名客户（库内多个'测试'/'李女士'等）
    # 在合并/统计时会互相串。改为以 customer_id 精确归属。
    ct_cols = {r[1] for r in conn.execute("PRAGMA table_info(customer_tags)").fetchall()}
    if "customer_id" not in ct_cols:
        conn.execute("ALTER TABLE customer_tags ADD COLUMN customer_id INTEGER")
    conn.execute(
        "CREATE INDEX IF NOT EXISTS idx_ctags_customer ON customer_tags(customer_id)"
    )
    conn.execute(
        "CREATE INDEX IF NOT EXISTS idx_sessions_cust_date "
        "ON sessions(company_id, customer_id, service_date)"
    )

    # 一次性回填 customer_id（marker 守卫，只跑一次）：
    # 仅走 source_session_id → sessions.customer_id 这条精确路径；绝不按姓名回填（同名会串）。
    # source_session_id 为空、或对应 session.customer_id 为空的行，customer_id 留 NULL（本就无法精确归属）。
    _mk_ctid = conn.execute(
        "SELECT 1 FROM app_migration_marker WHERE name=?",
        ("customer_tags_backfill_customer_id_2026_06_04",),
    ).fetchone()
    if not _mk_ctid:
        try:
            cur = conn.execute("""
                UPDATE customer_tags
                SET customer_id = (
                    SELECT s.customer_id FROM sessions s
                    WHERE s.id = customer_tags.source_session_id
                )
                WHERE source_session_id IS NOT NULL
                  AND customer_id IS NULL
                  AND EXISTS (
                      SELECT 1 FROM sessions s
                      WHERE s.id = customer_tags.source_session_id
                        AND s.customer_id IS NOT NULL
                  )
            """)
            filled = cur.rowcount
            remain = conn.execute(
                "SELECT COUNT(*) FROM customer_tags WHERE customer_id IS NULL"
            ).fetchone()[0]
            total = conn.execute("SELECT COUNT(*) FROM customer_tags").fetchone()[0]
            print(
                f"[migration customer_tags_backfill] 回填 customer_id {filled} 行；"
                f"仍为 NULL {remain} 行（共 {total} 行）",
                flush=True,
            )
            conn.execute(
                "INSERT INTO app_migration_marker(name) VALUES (?)",
                ("customer_tags_backfill_customer_id_2026_06_04",),
            )
        except Exception as _e:
            print(f"[migration customer_tags_backfill] 跳过：{_e}", flush=True)

    conn.commit()
    conn.close()


def db_exec(sql, params=()):
    """线程安全的写/读单条，走写队列。"""
    fut = _Future()
    _db_write_queue.put((sql, params, fut))
    return fut.result(timeout=30)


def db_write(sql, params=()):
    """把写操作投入队列，等待写线程执行完成后返回 lastrowid。"""
    fut = _Future()
    _db_write_queue.put((sql, params, fut))
    return fut.result(timeout=30)


def db_fetchone(sql, params=()):
    # WAL 模式下读不阻塞写、写不阻塞读，读取不必再抢 _db_lock
    conn = sqlite3.connect(DB_PATH, timeout=5.0)
    conn.row_factory = sqlite3.Row
    try:
        return conn.execute(sql, params).fetchone()
    finally:
        conn.close()


def db_fetchall(sql, params=()):
    conn = sqlite3.connect(DB_PATH, timeout=5.0)
    conn.row_factory = sqlite3.Row
    try:
        return conn.execute(sql, params).fetchall()
    finally:
        conn.close()


def db_batch(fn, timeout=60):
    """在写线程的连接上执行批量事务。
    fn(conn) 由调用方负责 executemany/commit 等操作，返回值透传给调用方。
    """
    fut = _Future()
    _db_write_queue.put((fn, fut))
    return fut.result(timeout=timeout)


# ============ 文件名解析 ============
# 格式: 顾客-顾问-录音YYYYMMDDHHMMSS[-_]时长.<ext>
# 示例:
#   代素英-伏琳-录音20260422125856-11分钟17秒.mp3
#   吕女士-刘佳玲-录音20260510114613_01分钟56秒.mp3
#   曹艾欣-王凤-录音20260421183200-14.44.mp3
FILENAME_RE = re.compile(
    r"^(?P<customer>[^-_/]+)[-_](?P<advisor>[^-_/]+)[-_]录音(?P<ts>\d{14})[-_](?P<dur>.+?)\.[A-Za-z0-9]{1,5}$"
)


def parse_filename(filename):
    """从文件名解析顾客/顾问/时间/时长。返回 dict 或 None。"""
    name = filename.split("/")[-1]
    m = FILENAME_RE.match(name)
    if not m:
        return None
    ts = m.group("ts")
    try:
        dt = datetime.strptime(ts, "%Y%m%d%H%M%S")
        recorded_at = dt.strftime("%Y-%m-%d %H:%M:%S")
        service_date = dt.strftime("%Y-%m-%d")
    except ValueError:
        recorded_at = None
        service_date = None
    return {
        "customer": m.group("customer"),
        "advisor": m.group("advisor"),
        "recorded_at": recorded_at,
        "service_date": service_date,
        "duration_label": m.group("dur"),
    }


_OSS_ERROR_HINTS = {
    "UserDisable": "OSS 账号被禁用（多为欠费或账号冻结），请登录阿里云控制台检查账单/账号状态",
    "AccessDenied": "OSS 拒绝访问，请检查 AccessKey 权限或 Bucket 策略",
    "InvalidAccessKeyId": "OSS AccessKey 无效，请检查配置",
    "SignatureDoesNotMatch": "OSS 签名不匹配，请检查 AccessKeySecret 配置",
    "NoSuchBucket": "OSS Bucket 不存在，请检查 Bucket 名称/Region 配置",
    "RequestTimeTooSkewed": "服务器时间与 OSS 偏差过大，请校准系统时间",
}


def _friendly_oss_error(e):
    """把 OSS/网络异常翻译成中文可读提示，避免前端只看到一串机器码。"""
    # oss2.exceptions.OssError 带 .code / .details["Code"]
    code = getattr(e, "code", None)
    if not code:
        details = getattr(e, "details", None)
        if isinstance(details, dict):
            code = details.get("Code")
    if code and code in _OSS_ERROR_HINTS:
        return _OSS_ERROR_HINTS[code]
    msg = str(e) or e.__class__.__name__
    for key, hint in _OSS_ERROR_HINTS.items():
        if key in msg:
            return hint
    return f"存储服务异常：{msg}"


def _sanitize_name_for_oss(s):
    """OSS 文件名安全化：白名单保留中文/英文/数字，其它清掉，截断到 20 字符。"""
    if not s:
        return "未命名"
    cleaned = re.sub(r"[^一-鿿A-Za-z0-9]", "", s)
    cleaned = cleaned[:20]
    return cleaned or "未命名"


def _format_duration_label(sec):
    """整数秒 → 'MM分SS秒'。无效返回 None。"""
    try:
        n = int(float(sec))
    except (TypeError, ValueError):
        return None
    if n < 0:
        n = 0
    m, s = divmod(n, 60)
    return f"{m:02d}分{s:02d}秒"


def _build_consultant_oss_key(company_id, user_id, customer, advisor, ts14, dur_label, ext):
    c = _sanitize_name_for_oss(customer)
    a = _sanitize_name_for_oss(advisor) or "顾问"
    dur = dur_label or "未知时长"
    return f"consultant-uploads/{company_id}/{user_id}/{c}_{a}_录音{ts14}_{dur}.{ext}"


# ============ OSS ============
AUDIO_EXTS = {".mp3", ".wav", ".m4a", ".flac", ".aac", ".ogg", ".opus"}


def _oss_key_exists(key):
    try:
        return bool(oss_bucket.object_exists(key))
    except Exception:
        return False


def _oss_copy_with_collision_suffix(old_key, new_key):
    """从 old_key 复制到 new_key；若 new_key 已存在则追加 4 位 hex 后缀避让。
    成功返回最终 key；失败抛异常（由调用方处理回滚）。"""
    import uuid as __uuid
    if _oss_key_exists(new_key):
        if "." in new_key.rsplit("/", 1)[-1]:
            stem, _, ex = new_key.rpartition(".")
            new_key = f"{stem}_{__uuid.uuid4().hex[:4]}.{ex}"
        else:
            new_key = f"{new_key}_{__uuid.uuid4().hex[:4]}"
    oss_bucket.copy_object(oss_bucket.bucket_name, old_key, new_key)
    return new_key


def _oss_delete_quiet(key):
    try:
        oss_bucket.delete_object(key)
    except Exception as e:
        app.logger.warning("oss delete failed key=%s err=%s", key, e)


def _maybe_rename_consultant_oss(rec, new_customer, new_advisor):
    """若 rec.oss_key 是顾问端新格式录音，则按新顾客/顾问名重命名（仅 OSS copy，未删旧）。
    返回 (new_key, old_key_to_delete_after_db_commit)；若不需要重命名返回 (rec.oss_key, None)。
    OSS copy 失败时抛异常（调用方负责回滚 DB 不变）。
    旧的 uuid.ext 文件按"只管新文件"约定保持不动。"""
    old_key = rec["oss_key"]
    if not old_key or not old_key.startswith("consultant-uploads/"):
        return old_key, None
    basename = old_key.rsplit("/", 1)[-1]
    if not parse_filename(basename):
        return old_key, None  # 旧 uuid.ext 格式，不动
    rec_at = rec["recorded_at"] or ""
    digits = re.sub(r"\D", "", rec_at)
    if len(digits) < 14:
        return old_key, None
    ts14 = digits[:14]
    dur_label = rec["duration_label"] or "未知时长"
    ext = basename.rsplit(".", 1)[-1] if "." in basename else "webm"
    cid = rec["company_id"] or 1
    uid = rec["uploader_user_id"] or 0
    new_key = _build_consultant_oss_key(cid, uid, new_customer, new_advisor, ts14, dur_label, ext)
    if new_key == old_key:
        return old_key, None
    final_key = _oss_copy_with_collision_suffix(old_key, new_key)
    return final_key, old_key


def oss_signed_url(oss_key, expires=7200, download_name=None):
    params = None
    if download_name:
        # 强制浏览器下载（而非内联播放）；filename 用纯 ASCII，避免中文编码坑
        params = {"response-content-disposition": f'attachment; filename="{download_name}"'}
    url = oss_bucket.sign_url("GET", oss_key, expires, params=params, slash_safe=True)
    # 站点跑在 https，OSS endpoint 未带 scheme 时 sign_url 默认拼 http://，
    # 浏览器会按 mixed content 静默拦截（音频加载失败 / 地址栏不安全提示）。
    if url.startswith("http://"):
        url = "https://" + url[len("http://"):]
    return url


# ============ Session 管理 ============
def store_for_advisor(advisor, company_id):
    """按顾问姓名解析其所属门店 store_id（用于新建 session/recording 时归店）。找不到返回 None。"""
    if not advisor:
        return None
    r = db_fetchone(
        "SELECT store_id FROM users WHERE advisor_name=? AND company_id=? "
        "AND store_id IS NOT NULL ORDER BY id LIMIT 1",
        (advisor, company_id or 1),
    )
    return r["store_id"] if r else None


def get_or_create_session(advisor, customer, service_date, company_id=1, customer_id=None):
    """根据 (advisor, customer, service_date, company_id) 找或创建 session。

    唯一键是 uq_sessions_acsc(advisor, customer, service_date, company_id)——**不含 customer_id**。
    所以同一(顾问·姓名·日期·公司)只能有一条 session；绑定/换绑/上传都必须**复用**它，
    绝不能因为"按 customer_id 没查到"就另建一条同键行（会撞唯一键：旧库 INSERT 出重复行、
    新库 INSERT OR IGNORE 被静默忽略导致返回错 session）。这正是 2026-06-14 宋心重复→重启
    init_db 建唯一索引失败→生产崩的根因。
    """
    if not (advisor and customer and service_date):
        return None

    _sid = store_for_advisor(advisor, company_id)
    cid = company_id or 1

    if customer_id:
        # 1) 按 customer_id 精确命中
        row = db_fetchone(
            "SELECT id FROM sessions WHERE advisor=? AND customer_id=? AND service_date=? AND company_id=?",
            (advisor, customer_id, service_date, cid),
        )
        if row:
            return row["id"]
        # 2) 复用同(顾问·姓名·日期·公司)的现有会话（多半是"只有名字 customer_id IS NULL"的那条），
        #    把它升级为带 customer_id，而不是另建——否则撞唯一键。
        row = db_fetchone(
            "SELECT id, customer_id FROM sessions WHERE advisor=? AND customer=? AND service_date=? AND company_id=?",
            (advisor, customer, service_date, cid),
        )
        if row:
            if row["customer_id"] is None:
                db_write("UPDATE sessions SET customer_id=? WHERE id=?", (customer_id, row["id"]))
            return row["id"]
        # 3) 确无同键行才新建
        db_write(
            "INSERT OR IGNORE INTO sessions (advisor, customer, customer_id, service_date, company_id, store_id) "
            "VALUES (?, ?, ?, ?, ?, ?)",
            (advisor, customer, customer_id, service_date, cid, _sid),
        )
        row = db_fetchone(
            "SELECT id FROM sessions WHERE advisor=? AND customer=? AND service_date=? AND company_id=?",
            (advisor, customer, service_date, cid),
        )
        return row["id"] if row else None

    # 没传 customer_id：按(顾问·姓名·日期·公司)复用任意已存在行（含已带 customer_id 的），避免反向重复
    row = db_fetchone(
        "SELECT id FROM sessions WHERE advisor=? AND customer=? AND service_date=? AND company_id=?",
        (advisor, customer, service_date, cid),
    )
    if row:
        return row["id"]
    db_write(
        "INSERT OR IGNORE INTO sessions (advisor, customer, service_date, company_id, store_id) VALUES (?, ?, ?, ?, ?)",
        (advisor, customer, service_date, cid, _sid),
    )
    row = db_fetchone(
        "SELECT id FROM sessions WHERE advisor=? AND customer=? AND service_date=? AND company_id=?",
        (advisor, customer, service_date, cid),
    )
    return row["id"] if row else None


def get_or_create_orphan_session(advisor, customer, oss_key, company_id=1):
    """元数据不全的录音：以 oss_key 当 service_date 创建独立 session（保证唯一）"""
    fake_date = "?-" + hashlib.md5(oss_key.encode()).hexdigest()[:8]
    row = db_fetchone(
        "SELECT id FROM sessions WHERE service_date=?",
        (fake_date,),
    )
    if row:
        return row["id"]
    return db_write(
        "INSERT INTO sessions (advisor, customer, service_date, company_id, store_id) VALUES (?, ?, ?, ?, ?)",
        (advisor or "(未填顾问)", customer or "(未填顾客)", fake_date, company_id or 1,
         store_for_advisor(advisor, company_id)),
    )


def compute_session_signature(session_id):
    """session 当前所有 recording_id 的指纹；用于分析结果失效检测"""
    rows = db_fetchall(
        "SELECT id FROM recordings WHERE session_id=? ORDER BY id", (session_id,)
    )
    ids = ",".join(str(r["id"]) for r in rows)
    return hashlib.sha1(ids.encode()).hexdigest()[:16]


# ============ 自动流水线 ============
_pipeline_lock = threading.Lock()  # 防止同 session 重复触发分析

# 全局分析并发闸：所有 run_session_analysis 走这个池，最多 4 并发。
# 多余的任务在 executor 内部 FIFO 排队，避免大批量上传时把内存打爆。
# 不要 import concurrent.futures 到顶层会与函数内已有局部 import 冲突，这里单独引。
import concurrent.futures as _cf
ANALYSIS_MAX_CONCURRENCY = 4
_ANALYSIS_POOL = _cf.ThreadPoolExecutor(
    max_workers=ANALYSIS_MAX_CONCURRENCY,
    thread_name_prefix="analysis",
)
# 仅用于监控：未完成（pending+running）的分析任务数
_analysis_inflight = 0
_analysis_inflight_lock = threading.Lock()


def submit_analysis(session_id, signature, *args, **kwargs):
    """把一次 run_session_analysis 入队，受 ANALYSIS_MAX_CONCURRENCY 限制。

    替代原来 `threading.Thread(target=run_session_analysis,...).start()`，
    保证全局并发 ≤ 4，多余任务在池内 FIFO 排队，不再瞬时占满内存。
    """
    global _analysis_inflight
    with _analysis_inflight_lock:
        _analysis_inflight += 1
        depth = _analysis_inflight

    def _runner():
        global _analysis_inflight
        # 真正被 worker 拉起来执行的瞬间，把状态从 queued 翻成 running
        try:
            db_write(
                """UPDATE sessions SET analysis_status='running',
                   analysis_started_at=datetime('now','localtime'),
                   analysis_progress='分析正在进行中…'
                   WHERE id=? AND analysis_status IN ('queued','running','pending')""",
                (session_id,),
            )
        except Exception as e:
            print(f"[submit_analysis] session={session_id} 翻转 running 失败: {e}", flush=True)
        try:
            return run_session_analysis(session_id, signature, *args, **kwargs)
        except Exception as e:
            print(f"[submit_analysis] session={session_id} 异常: {e}", flush=True)
            try:
                db_write(
                    """UPDATE sessions SET analysis_status='failed',
                       analysis_error=?,
                       analysis_progress=NULL,
                       analysis_finished_at=datetime('now','localtime')
                       WHERE id=? AND analysis_status IN ('running','queued','pending')""",
                    (f"未预期异常: {str(e)[:300]}", session_id),
                )
            except Exception:
                pass
        finally:
            with _analysis_inflight_lock:
                _analysis_inflight -= 1

    print(f"[submit_analysis] session={session_id} 入队，当前在飞={depth}/"
          f"{ANALYSIS_MAX_CONCURRENCY} 并发上限", flush=True)
    return _ANALYSIS_POOL.submit(_runner)


def trigger_pipeline_for_recording(recording_id):
    """为新录音排 ASR；ASR 完成后自动检查 session 是否需要分析"""
    threading.Thread(
        target=_asr_then_maybe_analyze, args=(recording_id,), daemon=True
    ).start()


def _asr_then_maybe_analyze(recording_id):
    # 仅跑 ASR，不再自动触发分析：顾问可能要绑多条录音合并分析，分析改为手动点"开始分析"触发。
    rec = db_fetchone("SELECT session_id, asr_status FROM recordings WHERE id=?", (recording_id,))
    if not rec:
        return
    if rec["asr_status"] != "done":
        run_asr(recording_id)


def maybe_trigger_session_analysis(session_id, model=None, force_refresh_shared=False):
    """所有录音 ASR done 且分析过期/未完成 → 触发分析。

    model: 指定分析模型 id（来自 SUPPORTED_MODELS）；为 None 则沿用上次或默认。
    force_refresh_shared: True 时强制重跑 shared_context（顶部"⟳ 重跑"语义）。
    """
    if not session_id:
        return
    with _pipeline_lock:
        recs = db_fetchall(
            "SELECT asr_status FROM recordings WHERE session_id=?", (session_id,)
        )
        if not recs:
            return
        if not all(r["asr_status"] == "done" for r in recs):
            return  # 还有 ASR 没完成
        sess = db_fetchone(
            "SELECT analysis_status, analysis_signature, analysis_model FROM sessions WHERE id=?",
            (session_id,),
        )
        if not sess:
            return
        if sess["analysis_status"] in ("running", "queued"):
            return  # 已在跑或已在队列里
        new_sig = compute_session_signature(session_id)
        chosen_model = model or sess["analysis_model"] or DEFAULT_MODEL
        if chosen_model not in MODEL_PROVIDER:
            chosen_model = DEFAULT_MODEL
        if (sess["analysis_status"] == "done"
                and sess["analysis_signature"] == new_sig
                and model is None):
            return  # 已是最新且没强制换模型
        # 立即标 queued，避免重复触发；真正进池开跑时 submit_analysis 会改成 running。
        db_write(
            """UPDATE sessions SET analysis_status='queued',
               analysis_model=?,
               analysis_progress='排队中…',
               analysis_error=NULL WHERE id=?""",
            (chosen_model, session_id),
        )

    submit_analysis(
        session_id, new_sig, chosen_model,
        force_refresh_shared=force_refresh_shared,
    )


# ============ ASR ============
# 限制 DashScope 的并发提交（提交阶段最容易被限流，提交后等待可并行）
_asr_submit_semaphore = threading.Semaphore(2)


def _asr_submit_with_retry(audio_url, attempts=5):
    """提交 ASR 任务，失败重试。返回 task_id。"""
    import time as _time
    last_err = None
    for i in range(attempts):
        with _asr_submit_semaphore:
            try:
                r = Transcription.async_call(
                    model="fun-asr",
                    file_urls=[audio_url],
                    diarization_enabled=True,
                    speaker_count=4,
                    language_hints=["zh", "en"],
                )
            except Exception as e:
                last_err = e
                r = None
        if r is not None and r.status_code == HTTPStatus.OK and r.output and r.output.get("task_id"):
            return r.output["task_id"]
        # 记录详细错误
        last_err = (
            f"status_code={getattr(r,'status_code',None)} "
            f"code={getattr(r,'code',None)} "
            f"message={getattr(r,'message',None)} "
            f"output={getattr(r,'output',None)}"
        )
        _time.sleep(2 ** i)  # 1, 2, 4, 8, 16 s
    raise RuntimeError(f"DashScope async_call 重试 {attempts} 次仍失败: {last_err}")


def _detect_garbled_audio(wav_path, win_sec=5):
    """检测「错位/损坏的 opus 帧被解码出来的满刻度噪声」段。
    成因：蓝牙补下载/实时流丢了非整块字节 → 录音笔 KA 80B 块对齐错位 → 端上 ATWOpusConverter 固定步长
    把这一错位放大成「后段全是垃圾 opus 包」→ 解出来就是削到 ±满刻度的宽带噪声，
    顾问以为录好了、实际后半段全是乱码（典型：前段正常、某点后整条尾巴废）。
    判据（逐 win_sec 窗口，单遍 ffmpeg astats metadata）：Peak_level > -0.5dB 且 Flat_factor > 3.0。
    干净语音 Flat_factor 恒为 0、峰值不会持续顶满刻度；错位噪声两者同时成立。
    且只认「坏段连续延伸到文件结尾」(≥3 窗口)：BLE 错位后固定步长转换器回不来、必然烂到 EOF；中段短块(真实削波)会恢复成语音，排除以免误报。
    实测 2026-06-10：recId970(坏) 75s 起一路烂到结尾全中；979/982/986(好) 共 348 窗口零误报。
    返回 (is_garbled, first_bad_sec, bad_sec_total)；任何异常都返回 (False, None, 0)，绝不阻断主流程。"""
    try:
        import subprocess
        n_samples = int(16000 * win_sec)
        r = subprocess.run(
            ["ffmpeg", "-hide_banner", "-nostats", "-i", wav_path,
             "-af", f"asetnsamples=n={n_samples}:p=0,astats=metadata=1:reset=1,ametadata=print:file=-",
             "-f", "null", "-"],
            capture_output=True, text=True, timeout=180)
        peaks, flats = [], []
        for line in (r.stdout or "").splitlines() + (r.stderr or "").splitlines():
            line = line.strip()
            if line.startswith("lavfi.astats.1.Peak_level="):
                try: peaks.append(float(line.split("=", 1)[1]))
                except ValueError: pass
            elif line.startswith("lavfi.astats.1.Flat_factor="):
                try: flats.append(float(line.split("=", 1)[1]))
                except ValueError: pass
        m = min(len(peaks), len(flats))
        is_bad = [(peaks[i] > -0.5 and flats[i] > 3.0) for i in range(m)]
        # ★BLE 错位损坏的铁律：固定步长转换器一旦错位就再也回不来 → 必然一路烂到文件结尾。
        #   所以只认「连续延伸到结尾」的坏段；中段短块(真实削波/大声笑/拍麦)会恢复成干净语音，不是本类损坏，排除以免误报。
        last_bad = max((i for i in range(m) if is_bad[i]), default=-1)
        if last_bad < m - 2:            # 坏段没到结尾(在中段) → 不是 BLE 错位损坏
            return (False, None, 0)
        start = last_bad
        clean_gap = 0
        i = last_bad
        while i >= 0:                   # 从结尾往回数坏段，容忍单个干净窗口间隔(阈值噪声)
            if is_bad[i]:
                start = i; clean_gap = 0
            else:
                clean_gap += 1
                if clean_gap > 1:
                    break
            i -= 1
        bad_in_run = sum(1 for k in range(start, last_bad + 1) if is_bad[k])
        if bad_in_run < 3:              # 尾部坏段不足 15s → 不判定(避免偶发)
            return (False, None, 0)
        return (True, start * win_sec, (last_bad - start + 1) * win_sec)
    except Exception as e:
        app.logger.info("[garble] 检测跳过 %s: %s", wav_path, e)
        return (False, None, 0)


def _ensure_clean_audio(recording_id, oss_key):
    """ASR/播放/分割前确保音频是带正确时长头与时间戳的干净格式。
    浏览器 webm/opus 录音常无时长头 → 三连坑：(1)DashScope 解码会提前停、转录覆盖不全；
    (2)ffmpeg 按时间 seek 的切点与 ASR 时间轴对不上、分割后音频/文字错位；(3)播放器 duration=Infinity。
    这里在 ASR 前把这类文件用 ffmpeg 转成 wav(无损 PCM，带时长+时间戳)，替换 OSS 对象与 oss_key/duration_label，
    后续 ASR/播放/分割全部基于干净文件。用 wav 而非 mp3：不对音频做有损压缩(录音笔本身也是 wav)。
    转码失败则回退用原文件，不阻断 ASR。返回最终使用的 oss_key。"""
    if not oss_key or "." not in oss_key:
        return oss_key
    ext = oss_key.rsplit(".", 1)[-1].lower()
    if ext in ("wav", "mp3", "m4a"):
        return oss_key  # 已是带正确头的格式，无需转码
    import tempfile, shutil
    tmpdir = tempfile.mkdtemp(prefix="reclean_")
    src = os.path.join(tmpdir, f"src.{ext}")
    out = os.path.join(tmpdir, "clean.wav")
    new_key = None
    try:
        oss_bucket.get_object_to_file(oss_key, src)
        _run_ffmpeg(["-i", src, "-vn", "-ac", "1", "-ar", "16000", "-acodec", "pcm_s16le", out])  # 16kHz单声道无损PCM wav
        dur = _ffprobe_duration(out)
        if not dur or dur < 0.2:
            return oss_key  # 转码异常，回退原文件
        new_key = (oss_key.rsplit(".", 1)[0]) + "_clean.wav"
        oss_bucket.put_object_from_file(new_key, out)
        # ★兜底检测：蓝牙补下载/实时流丢字节 → KA 块错位 → 后段全是乱码噪声（顾问以为录好了实则乱码）。
        #   命中就把损坏说明写进 truncate_note，未归档/未绑定列表会标红，绝不当成正常录音静默放过。
        #   断流时长告警仍下线（暂停/继续会让墙钟>实测而误报）；这里查的是「内容损坏」，与时长无关。
        garbled, gbad_start, gbad_sec = _detect_garbled_audio(out)
        gnote = None
        if garbled:
            gnote = (f"⚠️ 后段疑似蓝牙传输损坏：约第 {_format_duration_label(gbad_start)} 起 "
                     f"{_format_duration_label(gbad_sec)} 为乱码噪声（非真实录音），"
                     f"原始音频多半还在录音笔机身，建议用 USB 从笔重新导出该文件")
            app.logger.warning("[garble] rec %s 检测到后段损坏：起%ss 计%ss key=%s",
                               recording_id, gbad_start, gbad_sec, new_key)
        db_write(
            "UPDATE recordings SET oss_key=?, duration_label=?, size_bytes=?, truncate_note=? WHERE id=?",
            (new_key, _format_duration_label(dur), os.path.getsize(out), gnote, recording_id),
        )
        _oss_delete_quiet(oss_key)  # 删原 webm/ogg，失败仅记日志（孤儿可接受）
        app.logger.info("[clean audio] rec %s 转码 %s → %s (%.1fs)", recording_id, oss_key, new_key, dur)
        return new_key
    except Exception as e:
        # 转码失败：若已上传 new_key 但 DB 未更新则清掉，回退原文件继续 ASR
        if new_key:
            _oss_delete_quiet(new_key)
        app.logger.warning("[clean audio] rec %s 转码失败，回退原文件: %s", recording_id, e)
        return oss_key
    finally:
        shutil.rmtree(tmpdir, ignore_errors=True)


def _clean_audio_async(recording_id):
    """未归档录音入库后的【后台预清洗】：把录音笔/浏览器产出的无时长头 ogg/webm/opus
    用 ffmpeg 转成带正确时长头的 wav，当场修正两件事——
      (1) duration_label：客户端上报的【墙钟】时长 → ffprobe【实测】真实时长。断流补传时墙钟含断连空跑，
          比实际音频长，列表「时长」就和试听对不上；用实测值校正后一致。
      (2) 试听：无时长头的 ogg 让浏览器 audio.duration=Infinity，播放器恒显 0:00/0:00 且不能拖动；
          转成带头 wav 后总时长/进度条/拖动全部正常。
    复用 ASR 同款 _ensure_clean_audio（已含截断告警 + OSS 对象替换），幂等：已是 wav/mp3 直接跳过，
    故绑定后再跑 ASR 不会重复转码。放后台线程跑：不阻塞上传响应、不冒 gunicorn worker 超时；
    未归档列表 5s 自动刷新即可见正确时长。失败仅记日志（绑定跑 ASR 时还会再清洗一次兜底）。"""
    try:
        rec = db_fetchone("SELECT oss_key, upload_status FROM recordings WHERE id=?", (recording_id,))
        if not rec or (rec["upload_status"] or "") == "processing":
            return  # 占位行还没回填真音频，跳过
        _ensure_clean_audio(recording_id, rec["oss_key"])
    except Exception as e:
        app.logger.warning("[clean audio async] rec %s 预清洗失败(忽略，ASR 时会重试): %s", recording_id, e)


# 未归档录音预清洗：全局小并发池 + in-flight 去重。
# 既给【入库即转】用，也给【惰性补转存量】用（未归档列表刷新时对旧 ogg 逐步补转）。
# 限流到 2 路并发，避免一次刷新把几十条旧录音同时丢给 ffmpeg 打满 CPU。
_clean_pool = _cf.ThreadPoolExecutor(max_workers=2, thread_name_prefix="clean-audio")
_clean_inflight = set()
_clean_inflight_lock = threading.Lock()


def _kick_clean_audio_async(recording_id):
    """入队后台预清洗（见 _clean_audio_async）。同一录音在飞时不重复入队；池满则 FIFO 排队。"""
    with _clean_inflight_lock:
        if recording_id in _clean_inflight:
            return
        _clean_inflight.add(recording_id)

    def _job():
        try:
            _clean_audio_async(recording_id)
        finally:
            with _clean_inflight_lock:
                _clean_inflight.discard(recording_id)

    try:
        _clean_pool.submit(_job)
    except Exception:
        with _clean_inflight_lock:
            _clean_inflight.discard(recording_id)


def run_asr(recording_id):
    rec = db_fetchone("SELECT oss_key FROM recordings WHERE id = ?", (recording_id,))
    if not rec:
        return
    db_write(
        """UPDATE recordings SET asr_status='running',
           asr_started_at=datetime('now','localtime'), asr_error=NULL WHERE id=?""",
        (recording_id,),
    )
    try:
        # webm/opus 无时长头 → 先转码成干净 mp3，再做 ASR（同时修复转录覆盖/分割错位/播放时长）
        oss_key = _ensure_clean_audio(recording_id, rec["oss_key"])
        audio_url = oss_signed_url(oss_key, expires=7200)
        task_id = _asr_submit_with_retry(audio_url)
        resp = Transcription.wait(task=task_id)
        if resp.status_code != HTTPStatus.OK:
            raise RuntimeError(f"DashScope 请求失败: {resp.output.message}")

        full_json = []
        transcript_lines = []
        speaker_set = set()
        for transcription in resp.output["results"]:
            if transcription["subtask_status"] != "SUCCEEDED":
                raise RuntimeError(f"识别失败: {transcription}")
            detailed = json.loads(
                # ★加 timeout：无超时时下载转写结果若 hang，run_asr 线程永远挂在 try 里、
                #   asr_status 永远停在 running、进不了 except 标 failed → 前端永久"处理中"。
                urllib_request.urlopen(transcription["transcription_url"], timeout=60).read().decode("utf8")
            )
            full_json.append(detailed)
            for tr in detailed.get("transcripts", []):
                for s in tr.get("sentences", []):
                    spk = s.get("speaker_id")
                    if spk is not None:
                        speaker_set.add(spk)
                    speaker = f"说话人{spk}" if spk is not None else "说话人?"
                    start_sec = (s.get("begin_time", 0) or 0) / 1000.0
                    end_sec = (s.get("end_time", 0) or 0) / 1000.0
                    text = s.get("text", "")
                    transcript_lines.append(
                        f"[{start_sec:.2f}s - {end_sec:.2f}s] {speaker}: {text}"
                    )

        transcript = "\n".join(transcript_lines)
        spk_count = len(speaker_set)
        # 用户要求：即使 >2 个说话人也照常分析、不弹"误录确认"提示。
        # 仍记录说话人数(asr_speaker_count)作信息展示，但不再打 warning 拦截。
        warning = 0
        db_write(
            """UPDATE recordings SET asr_status='done',
               asr_result_json=?, asr_transcript=?,
               asr_speaker_count=?, asr_speaker_warning=?,
               asr_finished_at=datetime('now','localtime') WHERE id=?""",
            (json.dumps(full_json, ensure_ascii=False), transcript,
             spk_count, warning, recording_id),
        )
        # ★顾问转写没完时点了"开始分析"(session 标 queued 等转写)→ 这段转完后,若全转完就自动开始真分析。
        _maybe_autostart_analysis_after_asr(recording_id)
    except Exception as e:
        db_write(
            """UPDATE recordings SET asr_status='failed', asr_error=?,
               asr_finished_at=datetime('now','localtime') WHERE id=?""",
            (str(e)[:2000], recording_id),
        )
        # 即使本段转写失败,也看看 session 其它段是否都已结束(done/failed)→ 别让"已请求分析"的 session 永远卡 queued
        _maybe_autostart_analysis_after_asr(recording_id)


def _maybe_autostart_analysis_after_asr(recording_id):
    """顾问在转写未完成时点过"开始分析"(session 被标 queued 等转写)。这段 ASR 结束后检查：
       该 session 已无"在转写/没转写"的录音 → 自动开始真分析，不用顾问回来再点一次。"""
    try:
        rec = db_fetchone("SELECT session_id FROM recordings WHERE id=?", (recording_id,))
        if not rec or not rec["session_id"]:
            return
        sid = rec["session_id"]
        sess = db_fetchone("SELECT analysis_status FROM sessions WHERE id=?", (sid,))
        if not sess or sess["analysis_status"] != "queued":
            return   # 没被请求分析(等转写)就不插手——queued 是 start_analysis 等转写时标的
        still = db_fetchone(
            "SELECT COUNT(*) AS c FROM recordings WHERE session_id=? "
            "AND asr_status IN ('pending','running','awaiting_intake')", (sid,))
        if still and still["c"] > 0:
            return   # 还有没转完的，等最后一段转完再触发
        sig = compute_session_signature(sid)
        app.logger.info("[autostart] session=%s 转写全完成 → 自动开始分析", sid)
        submit_analysis(sid, sig)
    except Exception as e:
        app.logger.warning("[autostart] session 自动分析失败: %s", e)


# ============ Claude 知识库分析 ============
_kb_cache = None


def load_kb():
    global _kb_cache
    if _kb_cache is not None:
        return _kb_cache
    with open(KB_PATH, "r", encoding="utf-8") as f:
        raw = json.load(f)
    # 兼容两种结构：旧版直接是 list，新版是 {tag_taxonomy, canonical_logics}
    if isinstance(raw, dict) and isinstance(raw.get("canonical_logics"), list):
        items = raw["canonical_logics"]
    elif isinstance(raw, list):
        items = raw
    else:
        raise ValueError(
            f"logic_library.json 根结构异常: {type(raw).__name__}，"
            f"期望 list 或含 canonical_logics 的 dict"
        )
    bad_items = [
        {"index": i, "type": type(item).__name__, "value": repr(item)[:200]}
        for i, item in enumerate(items)
        if not isinstance(item, dict)
    ]
    if bad_items:
        raise ValueError(
            f"logic_library.json 含 {len(bad_items)} 条非 dict 元素，"
            f"示例: {bad_items[:3]}"
        )
    _kb_cache = items
    return _kb_cache


def build_kb_brief():
    kb = load_kb()
    lines = []
    for item in kb:
        if not isinstance(item, dict):
            continue
        stage = item.get("stage", "")
        tags = "/".join(item.get("secondary_tags", [])[:3])
        prio = item.get("boss_priority", "")
        logic = item.get("logic", "").strip()
        anti = item.get("anti_pattern", "").strip()
        signals = "、".join(item.get("trigger_signals", [])[:6])
        lines.append(
            f"【{item['id']}】[{stage}|{prio}] {tags}\n"
            f"  逻辑: {logic}\n"
            f"  反面: {anti}\n"
            f"  信号: {signals}"
        )
    return "\n\n".join(lines)


# ============ 全维度复盘报告 Schema ============
# 完全对齐 report_v2.html 的 PART01-09 视觉结构
# 三个必含的痛点标签（顺序不限，无对应素材可省略）
PAIN_BADGES = ["最强突破口", "最佳情感连接点", "最高价值突破口"]

REPORT_TOOL = {
    "name": "submit_full_report",
    "description": "提交一份完整的接诊全维度复盘报告，对应前端 PART01-09 全部字段。",
    "input_schema": {
        "type": "object",
        "required": ["overview", "persona", "root_cause", "pain_points",
                     "harvest", "scoring", "logic_chain", "cases", "next_steps"],
        "properties": {
            # ── PART 01 ──
            "overview": {
                "type": "object",
                "required": ["customer_value", "pain_summary", "sales_diagnosis",
                             "quality_score"],
                "properties": {
                    "customer_value": {
                        "type": "object",
                        "required": ["tag", "note"],
                        "properties": {
                            "tag": {"type": "string",
                                    "description": "顾客价值评级标签，如 'C 级潜力客户'"},
                            "tag_kind": {"type": "string",
                                         "enum": ["level", "good", "warn", "bad"],
                                         "description": "标签底色：level=深蓝, good=绿, warn=橙, bad=红"},
                            "note": {"type": "string",
                                     "description": "1-2 句解读，可含 **加粗**"},
                        },
                    },
                    "pain_summary": {
                        "type": "object",
                        "required": ["tag", "items"],
                        "properties": {
                            "tag": {"type": "string",
                                    "description": "如 '3个核心可攻破点'"},
                            "tag_kind": {"type": "string",
                                         "enum": ["level", "good", "warn", "bad"]},
                            "items": {
                                "type": "array",
                                "items": {
                                    "type": "object",
                                    "required": ["color", "text"],
                                    "properties": {
                                        "color": {"type": "string",
                                                  "enum": ["red", "blue", "teal", "orange", "green"]},
                                        "text": {"type": "string",
                                                 "description": "如 '肩颈筋膜粘连（顾客主动提及3次+...）'"},
                                    },
                                },
                            },
                        },
                    },
                    "sales_diagnosis": {
                        "type": "object",
                        "required": ["tag", "note"],
                        "properties": {
                            "tag": {"type": "string"},
                            "tag_kind": {"type": "string",
                                         "enum": ["level", "good", "warn", "bad"]},
                            "note": {"type": "string"},
                        },
                    },
                    "quality_score": {
                        "type": "object",
                        "required": ["score", "note"],
                        "properties": {
                            "score": {"type": "number", "minimum": 0, "maximum": 10},
                            "note": {"type": "string"},
                        },
                    },
                },
            },
            # ── PART 02 顾客画像 ──
            "persona": {
                "type": "object",
                "required": ["signals", "summary"],
                "properties": {
                    "lead": {"type": "string", "description": "section_lead 引言一行"},
                    "signals": {
                        "type": "array",
                        "minItems": 3,
                        "items": {
                            "type": "object",
                            "required": ["signal", "interpretation"],
                            "properties": {
                                "signal": {"type": "string",
                                           "description": "顾客原话（不带引号）"},
                                "interpretation": {"type": "string",
                                                   "description": "解读：性格/动机/防御等"},
                            },
                        },
                    },
                    "summary": {"type": "string",
                                "description": "综合判断：顾客是 XX 驱动型 + 真实潜力（120 字内，深蓝大块卡片）"},
                },
            },
            # ── PART 03 失分根因 ──
            "root_cause": {
                "type": "object",
                "required": ["headline", "product_dimension",
                             "problem_dimension", "gap_note"],
                "properties": {
                    "lead": {"type": "string"},
                    "headline": {"type": "string",
                                 "description": "顾问做错的核心一句话（红底卡）"},
                    "product_dimension": {
                        "type": "array", "minItems": 2,
                        "items": {"type": "string",
                                  "description": "顾问实际说的话（原话或贴近原话）"},
                    },
                    "problem_dimension": {
                        "type": "array", "minItems": 2,
                        "items": {"type": "string",
                                  "description": "顾客真正想听的诊断式话术"},
                    },
                    "gap_note": {"type": "string",
                                 "description": "差距的本质（橙底总结一句）"},
                },
            },
            # ── PART 04 可攻破痛点 ──
            "pain_points": {
                "type": "array",
                "minItems": 1, "maxItems": 5,
                "description": (
                    "可攻破痛点列表。**必含**至少 3 条，分别带 badge=最强突破口 / "
                    "最佳情感连接点 / 最高价值突破口；除非录音里实在抽不出来某一种。"
                ),
                "items": {
                    "type": "object",
                    "required": ["title", "badge", "lead"],
                    "properties": {
                        "title": {"type": "string",
                                  "description": "如 '肩颈' / '干眼症' / '医美未解决的问题'"},
                        "badge": {"type": "string",
                                  "enum": ["最强突破口", "最佳情感连接点",
                                           "最高价值突破口", "其它"]},
                        "lead": {"type": "string",
                                 "description": "1 句话说明为什么是这种类型的突破口"},
                        "steps": {
                            "type": "array",
                            "description": "推进步骤（3-4 步）。如果该痛点用策略表呈现则可省略",
                            "items": {
                                "type": "object",
                                "required": ["label", "body"],
                                "properties": {
                                    "label": {"type": "string",
                                              "description": "步骤名（如 '建立专业诊断感'，2-6 字）"},
                                    "body": {"type": "string",
                                             "description": "顾问应该说的话（建议带引号或具体场景）"},
                                },
                            },
                        },
                        "strategy_table": {
                            "type": "array",
                            "description": "可替代 steps 的策略表（用于'医美未解决'这种横向多策略场景）",
                            "items": {
                                "type": "object",
                                "required": ["strategy", "logic"],
                                "properties": {
                                    "strategy": {"type": "string"},
                                    "logic": {"type": "string"},
                                },
                            },
                        },
                    },
                },
            },
            # ── PART 05 收割四步 ──
            "harvest": {
                "type": "object",
                "required": ["intro", "steps"],
                "properties": {
                    "intro": {"type": "string",
                              "description": "蓝底卡片导语，强调黄金窗口"},
                    "steps": {
                        "type": "array",
                        "minItems": 3, "maxItems": 5,
                        "items": {
                            "type": "object",
                            "required": ["title", "body"],
                            "properties": {
                                "title": {"type": "string",
                                          "description": "步骤标题，如 '引导顾客说出效果'"},
                                "body": {"type": "string",
                                         "description": "具体话术 + 操作说明（带引号）"},
                            },
                        },
                    },
                },
            },
            # ── PART 06 评分 ──
            "scoring": {
                "type": "object",
                "required": ["overall", "good_highlights", "bad_highlights", "stages"],
                "properties": {
                    "overall": {"type": "number", "minimum": 0, "maximum": 10},
                    "good_highlights": {
                        "type": "array", "minItems": 1,
                        "items": {"type": "string",
                                  "description": "做得好的简评（1 行）"},
                    },
                    "bad_highlights": {
                        "type": "array", "minItems": 1,
                        "items": {"type": "string"},
                    },
                    "stages": {
                        "type": "array",
                        "minItems": 3, "maxItems": 3,
                        "description": "三阶段（壹 一咨找需求 / 贰 确认加大意愿 / 叁 成交阶段）",
                        "items": {
                            "type": "object",
                            "required": ["name", "score", "sub"],
                            "properties": {
                                "name": {"type": "string",
                                         "description": "如 '壹 一咨找需求'"},
                                "score": {"type": "number", "minimum": 0, "maximum": 10},
                                "sub": {
                                    "type": "array",
                                    "minItems": 2,
                                    "items": {
                                        "type": "object",
                                        "required": ["name", "score", "detail"],
                                        "properties": {
                                            "name": {"type": "string",
                                                     "description": "如 '1.1 档案掌握与破冰'"},
                                            "score": {"type": "number",
                                                      "minimum": 0, "maximum": 10},
                                            "detail": {"type": "string",
                                                       "description": "本子项扣分原因/亮点（1-2 句）"},
                                        },
                                    },
                                },
                            },
                        },
                    },
                },
            },
            # ── PART 07 逻辑链 + 训练路径 ──
            "logic_chain": {
                "type": "object",
                "required": ["bad_chain", "good_chain", "missing_step", "training"],
                "properties": {
                    "bad_chain": {"type": "string",
                                  "description": "顾问实际思维模式：'顾客来了 → 了解需求 → 介绍产品 → 希望成交'"},
                    "bad_chain_note": {"type": "string"},
                    "good_chain": {"type": "string"},
                    "missing_step": {"type": "string",
                                     "description": "缺失的那一步（橙底卡片）"},
                    "training": {
                        "type": "array", "minItems": 3,
                        "items": {
                            "type": "object",
                            "required": ["stage", "issue", "skill"],
                            "properties": {
                                "stage": {"type": "string",
                                          "description": "接待阶段：破冰 / 需求挖掘 / 产品推荐 …"},
                                "issue": {"type": "string"},
                                "skill": {"type": "string",
                                          "description": "需要训练的能力（可含 **加粗**）"},
                            },
                        },
                    },
                },
            },
            # ── PART 08 Case 复盘 ──
            "cases": {
                "type": "array",
                "minItems": 3, "maxItems": 10,
                "description": "关键 Case 复盘，3-10 条，至少包含 1-2 个做得好的 (kind=good)",
                "items": {
                    "type": "object",
                    "required": ["kind", "title", "quote", "surface", "deep", "improve"],
                    "properties": {
                        "kind": {"type": "string",
                                 "enum": ["good", "miss", "bad"],
                                 "description": "good=做到（绿）, miss=遗漏（橙）, bad=做错（红）"},
                        "title": {"type": "string",
                                  "description": "Case 主标题，如 '精油选择拖了3分钟·失去主导权'"},
                        "timestamp_seconds": {
                            "type": "number",
                            "description": "可点击跳转的录音秒数（基于第 segment 段的相对秒数）。"
                                           "未能定位则为 0。",
                        },
                        "segment": {"type": "integer", "minimum": 1,
                                    "description": "对应第几段录音，1 起"},
                        "timestamp_label": {
                            "type": "string",
                            "description": "格式化时间戳，如 '0:01:34'",
                        },
                        "quote": {"type": "string",
                                  "description": "顾客或顾问的原话（10-40 字）"},
                        "surface": {"type": "string",
                                    "description": "表层问题/做法（顾问看到的）"},
                        "deep": {"type": "string",
                                 "description": "深层问题（为什么这是关键时刻）"},
                        "improve": {"type": "string",
                                    "description": "改进/正确做法（最好包含 *...* 形式的对应话术）"},
                    },
                },
            },
            "cases_summary": {
                "type": "string",
                "description": "一句话总结整个 Case（深蓝底大卡，2-3 行）",
            },
            # ── PART 09 下一步动作 ──
            "next_steps": {
                "type": "object",
                "required": ["return_scripts", "priority_projects", "objection_qa"],
                "properties": {
                    "return_scripts": {
                        "type": "array", "minItems": 1, "maxItems": 4,
                        "description": "回店切入话术（每条一种切入角度）",
                        "items": {
                            "type": "object",
                            "required": ["title", "body"],
                            "properties": {
                                "title": {"type": "string",
                                          "description": "如 '优先从干眼症切入'"},
                                "body": {"type": "string",
                                         "description": "完整话术，可加引号"},
                            },
                        },
                    },
                    "priority_projects": {
                        "type": "array", "minItems": 2, "maxItems": 5,
                        "items": {
                            "type": "object",
                            "required": ["name", "desc"],
                            "properties": {
                                "name": {"type": "string"},
                                "desc": {"type": "string",
                                         "description": "为什么排这个顺序的理由"},
                            },
                        },
                    },
                    "objection_qa": {
                        "type": "array", "minItems": 2, "maxItems": 6,
                        "items": {
                            "type": "object",
                            "required": ["q", "a"],
                            "properties": {
                                "q": {"type": "string",
                                      "description": "预设异议（Q）"},
                                "a": {"type": "string",
                                      "description": "标准应答（A）"},
                            },
                        },
                    },
                },
            },
        },
    },
}


# ─── 三次调用拆分：把大 schema 切成 3 个独立 tool ───────────────
# 原因：DeepSeek V4 单次 max_tokens 不足以容纳一次性输出 10 个 PART，会被截断。
# 现在拆成：调用1（顾客理解）+ 调用2（接诊评判，含知识库）并行；调用3（综合输出）串行在后，
# 仅传入前两次结果摘要、不重传录音，节省 token。

SYSTEM_PROMPT_CALL1 = """\
你是身美美容院的顾客洞察分析师。
你的任务是从一段接诊录音里读懂这个顾客——她是谁、她有什么问题、这次接诊有没有成交可能。

录音格式说明：
- 每行格式：[开始秒s - 结束秒s] 说话人X: 内容
- 说话人0/1由ASR自动分配，你需要根据对话内容判断谁是顾问、谁是顾客
- 多段录音用 ### 录音段 N/总段数 分隔，时间戳是相对每段的秒数

你的输出标准：
1. 画像要有洞察，不要废话。不要写"注重保养的消费者"，要写"被动决策型——给了三个痛点信号没有一个被顾问接住"
2. 痛点话术必须出现顾客真名，禁止写"您"或"顾客"
3. 每步话术30-60字，是顾问真实会说的口语，不要书面化
4. 外部信号只写顾客真实说过的，禁止编造
5. 成交诊断的判断依据必须引用录音原话（用「」）
6. 所有字段都必须填，没有的用空数组[]
7. 竞品提取要求：穷尽列出顾客提到的所有外部品牌/项目/机构，不是身美自己家的东西都算竞品，宁可多列不要漏列。

调用 submit_call1 工具提交结果，不要输出其他任何文字。
"""

SYSTEM_PROMPT_CALL2 = """\
你是身美美容院的接诊质检专家。
你的任务是对照知识库，评判顾问这次接诊做得怎么样——哪里扣分、哪里做对了、根本问题在哪。

录音格式说明：
- 每行格式：[开始秒s - 结束秒s] 说话人X: 内容
- 说话人0/1由ASR自动分配，根据对话内容判断谁是顾问谁是顾客
- 多段录音用 ### 录音段 N/总段数 分隔

你的判断标准：
1. 评分必须有区分度：做得很差给2-3分，做得一般给4-5分，做得好给7-8分，做得很好给9分。禁止全给5-6分
2. 每个子项的detail只写一句话，说本次实际做了什么或没做什么，不写定义，不写改进建议
3. Case复盘要引用顾问或顾客的原话（用「」），每条有完整的三层分析
4. 根因分析要找到背后唯一的根本问题，不是罗列所有错误
5. 知识库的L编号只用于内部判断参考，最终输出里不要出现L编号

调用 submit_call2 工具提交结果，不要输出其他任何文字。
"""

SYSTEM_PROMPT_SHARED_CALL1 = """\
你是身美美容院的接诊预分析员。你只产出"共用判断底稿"，不写最终报告。

录音格式：
- 每行：[开始秒s - 结束秒s] 说话人X: 内容
- 说话人0/1 由 ASR 分配，需根据内容判断顾问 / 顾客
- 多段录音用 ### 录音段 N/总段数 分隔

底稿目标：基于完整录音抽取后续生成正式报告所需的"判断 + 证据"，给下游分块任务复用，不要重复读录音。

输出纪律：
1. 只写判断、证据、关键原话；不写任何长篇话术、不写下一步动作、不写完整方案
2. 关键原话必须从录音里照抄（带说话人和时间），不要改写
3. 没有出现的字段宁填空数组也不要编造
4. 字段保持精炼，整份底稿期望 < 2500 字

调用 submit_shared_call1 工具提交结果，不要输出其他任何文字。
"""

SYSTEM_PROMPT_SHARED_CALL2 = """\
你是身美美容院的接诊质检预分析员。你只产出"共用质检底稿"，不写最终报告。

录音格式：
- 每行：[开始秒s - 结束秒s] 说话人X: 内容
- 说话人0/1 由 ASR 分配
- 多段录音用 ### 录音段 N/总段数 分隔

知识库使用纪律：
- 知识库（L 编号）仅供你判断时参考
- 不要在底稿里复述知识库条目本身，只写"本次命中了什么 + 证据原话"
- 严禁把知识库内容大段抄进字段里

底稿目标：抽取后续质检评分、Case 复盘、根因、收割机会所需的"判断 + 证据"，给下游分块复用。

输出纪律：
1. 只写判断要点、命中依据、关键原话；不写最终分数、不写完整 case 三层分析、不写改进建议
2. 关键原话必须照抄
3. 字段保持精炼，整份底稿期望 < 3000 字

调用 submit_shared_call2 工具提交结果，不要输出其他任何文字。
"""


SYSTEM_PROMPT_CALL3 = """\
你是身美美容院的接诊报告撰写专家。
你会收到前两步的分析结果（顾客画像、痛点、成交诊断、质检评分、Case复盘），
你的任务是把这些诊断结论转化成顾问能立刻用的方案，并生成报告首屏的总览。

你的输出标准：
1. 所有话术必须含顾客真名，禁止写"您"或"顾客"
2. 回店话术必须提到上次顾客说的具体痛点，不能是通用模板
3. 泛医疗异议的应答逻辑必须遵循：①承认医院权威 → ②说明分工边界 → ③说明我们的位置 → ④互补不冲突
4. 禁止贬低医疗机构
5. PART1总览是整份报告的入口，判断要准确、标签要尖锐

调用 submit_call3 工具提交结果，不要输出其他任何文字。
"""


# ─── 三个 Tool Schema ───────────────────────────────────────

TOOL_CALL1 = {
    "name": "submit_call1",
    "description": "提交顾客理解分析：画像、痛点话术、外部信号、成交诊断",
    "input_schema": {
        "type": "object",
        "required": ["persona", "pain_points", "external_signals",
                     "customer_tags", "deal_diagnosis"],
        "properties": {
            "persona": {
                "type": "object",
                "required": ["signals", "summary"],
                "properties": {
                    "lead": {"type": "string"},
                    "signals": {
                        "type": "array",
                        "minItems": 5,
                        "items": {
                            "type": "object",
                            "required": ["signal", "interpretation"],
                            "properties": {
                                "signal": {"type": "string",
                                           "description": "顾客原话或行为"},
                                "interpretation": {"type": "string",
                                                   "description": "对销售的洞察"},
                            },
                        },
                    },
                    "summary": {"type": "string",
                                "description": "综合判断，要尖锐准确"},
                },
            },
            "pain_points": {
                "type": "array",
                "minItems": 2, "maxItems": 4,
                "items": {
                    "type": "object",
                    "required": ["title", "badge", "evidence", "steps"],
                    "properties": {
                        "title": {"type": "string"},
                        "lead": {"type": "string"},
                        "badge": {"type": "string",
                                  "enum": ["最强突破口", "最佳情感连接点",
                                           "最高价值突破口", "其它"]},
                        "evidence": {"type": "string",
                                     "description": "录音里支撑这个痛点的关键原话"},
                        "steps": {
                            "type": "array",
                            "minItems": 4, "maxItems": 4,
                            "items": {
                                "type": "object",
                                "required": ["label", "body"],
                                "properties": {
                                    "label": {"type": "string",
                                              "enum": ["建立专业诊断感",
                                                       "放大连锁影响",
                                                       "说出为什么之前没解决",
                                                       "给系统方案 + 预期"]},
                                    "body": {"type": "string", "minLength": 25,
                                             "description": "完整话术，必须含顾客真名，30-60字"},
                                },
                            },
                        },
                    },
                },
            },
            "external_signals": {
                "type": "object",
                # lifestyle_habits / self_care 改为可选（不强制 LLM 输出），仍保留字段定义
                "required": ["medical_aesthetics", "other_institutions",
                             "external_brands"],
                "properties": {
                    # 三类竞品：含 customer_quote + competitor_learn
                    **{
                        cat: {
                            "type": "array",
                            "items": {
                                "type": "object",
                                "required": ["item", "type", "competitor_learn"],
                                "properties": {
                                    "item": {"type": "string",
                                             "description": "竞品名称，原词"},
                                    "type": {"type": "string",
                                             "description": "竞品类型：医美项目/医美机构/护肤品牌/仪器品牌/养生机构"},
                                    "customer_quote": {"type": "string",
                                                       "description": "顾客提到这个竞品时的原话片段，10-20 字"},
                                    "competitor_learn": {"type": "string",
                                                         "description": "顾问售后应了解什么，20 字以内，只说要学什么"},
                                },
                            },
                        }
                        for cat in ["medical_aesthetics", "other_institutions",
                                    "external_brands"]
                    },
                    # 两类非竞品：仅 item + customer_tag
                    **{
                        cat: {
                            "type": "array",
                            "items": {
                                "type": "object",
                                "required": ["item", "customer_tag"],
                                "properties": {
                                    "item": {"type": "string",
                                             "description": "顾客行为/习惯描述"},
                                    "customer_tag": {"type": "string",
                                                     "description": "基于此生成的顾客标签，10 字以内"},
                                },
                            },
                        }
                        for cat in ["lifestyle_habits", "self_care"]
                    },
                },
            },
            "customer_tags": {
                "type": "array",
                "description": "从录音中提取的顾客所有特征标签（消费/习惯/身体状况/心理特征/品牌偏好），每个 10 字以内；count 为本次录音里顾客自己提及该标签相关话题的次数（至少 1）",
                "minItems": 3,
                "items": {
                    "type": "object",
                    "required": ["tag", "count"],
                    "properties": {
                        "tag": {"type": "string", "description": "标签名（10 字以内）"},
                        "count": {"type": "integer", "minimum": 1,
                                  "description": "本次录音里顾客提及该标签相关话题的次数"},
                    },
                },
            },
            "deal_diagnosis": {
                "type": "object",
                "required": ["deal_result", "deal_amount", "risk_level",
                             "dimensions", "risk_alert", "risk_text"],
                "properties": {
                    "deal_result": {"type": "boolean"},
                    "deal_amount": {"type": "string",
                                    "description": "从录音里识别的成交金额，如'6800元'，未识别到填'未识别'"},
                    "risk_level": {"type": "string",
                                   "enum": ["high", "medium", "low"],
                                   "description": "差评风险等级：未成交且心动/认同/效果满意三条都不是 ok 填 high；未成交但有 partial 填 medium；已成交填 low"},
                    "dimensions": {
                        "type": "object",
                        "required": ["customer_moved", "customer_agreed",
                                     "effect_satisfied", "price_matched",
                                     "urgency_built"],
                        "properties": {
                            dim: {
                                "type": "object",
                                "required": ["status", "note"],
                                "properties": {
                                    "status": {"type": "string",
                                               "enum": ["ok", "partial", "missing"]},
                                    "note": {"type": "string"},
                                },
                            }
                            for dim in ["customer_moved", "customer_agreed",
                                        "effect_satisfied", "price_matched",
                                        "urgency_built"]
                        },
                    },
                    "risk_alert": {"type": "boolean"},
                    "risk_text": {"type": "string"},
                },
            },
        },
    },
}

TOOL_CALL2 = {
    "name": "submit_call2",
    "description": "提交接诊评判：质检评分、Case复盘、收割四步",
    "input_schema": {
        "type": "object",
        "required": ["scoring", "cases", "cases_summary", "harvest"],
        "properties": {
            "scoring": {
                "type": "object",
                "required": ["overall", "good_highlights", "bad_highlights", "stages"],
                "properties": {
                    "overall": {"type": "number", "minimum": 0, "maximum": 10},
                    "good_highlights": {"type": "array", "items": {"type": "string"}},
                    "bad_highlights":  {"type": "array", "items": {"type": "string"}},
                    "stages": {
                        "type": "array",
                        "minItems": 3, "maxItems": 3,
                        "items": {
                            "type": "object",
                            "required": ["name", "score", "sub"],
                            "properties": {
                                "name": {"type": "string",
                                         "enum": ["壹 一咨找需求",
                                                  "贰 确认加大意愿",
                                                  "叁 成交阶段"]},
                                "score": {"type": "number", "minimum": 0, "maximum": 10},
                                "sub": {
                                    "type": "array",
                                    "items": {
                                        "type": "object",
                                        "required": ["name", "score", "detail"],
                                        "properties": {
                                            "name": {"type": "string"},
                                            "score": {"type": "number",
                                                      "minimum": 0, "maximum": 10},
                                            "detail": {"type": "string",
                                                       "description": "一句话：本次做了什么或没做什么。不要定义，不要建议，只说本次事实，20-40字"},
                                        },
                                    },
                                },
                            },
                        },
                    },
                },
            },
            "cases": {
                "type": "array",
                "minItems": 5, "maxItems": 8,
                "items": {
                    "type": "object",
                    "required": ["kind", "title", "quote", "segment",
                                 "timestamp_seconds", "timestamp_label",
                                 "surface", "deep", "improve"],
                    "properties": {
                        "kind": {"type": "string", "enum": ["good", "miss", "bad"]},
                        "title": {"type": "string"},
                        "quote": {"type": "string",
                                  "description": "原话，用「」，15 字以内"},
                        "segment": {"type": "integer", "minimum": 1},
                        "timestamp_seconds": {"type": "number"},
                        "timestamp_label": {"type": "string"},
                        "surface": {"type": "string", "description": "表层做法或问题"},
                        "deep": {"type": "string",
                                 "description": "这个具体时刻错在哪，或者错过了什么机会，只说这一句话的问题，不要上升到整体根因，20-30 字"},
                        "improve": {"type": "string",
                                    "description": "正确做法，含顾客真名的具体话术"},
                    },
                },
            },
            "cases_summary": {"type": "string",
                              "description": "一句话总结整个 Case，要尖锐有力"},
            "harvest": {
                "type": "object",
                "required": ["intro", "steps"],
                "description": "项目后价值收割·黄金窗口标准流程：顾客做完项目情绪放松时的 3-5 步标准收割动作",
                "properties": {
                    "intro": {"type": "string",
                              "description": "蓝底卡片导语，强调项目后 10 分钟是黄金窗口"},
                    "steps": {
                        "type": "array",
                        "minItems": 3, "maxItems": 5,
                        "items": {
                            "type": "object",
                            "required": ["title"],
                            "properties": {
                                "title": {"type": "string",
                                          "description": "步骤名 ≤8 字、动宾短语，如'引导说出效果'/'埋下下次钩子'，不带引号、不写话术"},
                            },
                        },
                    },
                },
            },
        },
    },
}

TOOL_CALL3 = {
    "name": "submit_call3",
    "description": "提交综合输出：PART1 总览、能力训练路径、下一步动作",
    "input_schema": {
        "type": "object",
        "required": ["overview", "logic_chain", "next_steps"],
        "properties": {
            "overview": {
                "type": "object",
                "required": ["customer_value", "pain_summary", "sales_diagnosis",
                             "quality_score"],
                "properties": {
                    "customer_value": {
                        "type": "object",
                        "required": ["tag", "tag_kind", "note"],
                        "properties": {
                            "tag": {"type": "string"},
                            "tag_kind": {"type": "string",
                                         "enum": ["level", "good", "warn", "bad"]},
                            "note": {"type": "string"},
                        },
                    },
                    "pain_summary": {
                        "type": "object",
                        "required": ["tag", "tag_kind", "items"],
                        "properties": {
                            "tag": {"type": "string"},
                            "tag_kind": {"type": "string",
                                         "enum": ["good", "warn", "bad"]},
                            "items": {
                                "type": "array",
                                "items": {
                                    "type": "object",
                                    "required": ["color", "text"],
                                    "properties": {
                                        "color": {"type": "string",
                                                  "enum": ["red", "blue",
                                                           "teal", "orange"]},
                                        "text": {"type": "string"},
                                    },
                                },
                            },
                        },
                    },
                    "sales_diagnosis": {
                        "type": "object",
                        "required": ["tag", "tag_kind", "note"],
                        "properties": {
                            "tag": {"type": "string"},
                            "tag_kind": {"type": "string",
                                         "enum": ["good", "warn", "bad"]},
                            "note": {"type": "string"},
                        },
                    },
                    "quality_score": {
                        "type": "object",
                        "required": ["score", "note"],
                        "properties": {
                            "score": {"type": "number"},
                            "note": {"type": "string"},
                        },
                    },
                },
            },
            "logic_chain": {
                "type": "object",
                "required": ["bad_chain", "bad_chain_note", "good_chain",
                             "missing_step", "training"],
                "properties": {
                    "bad_chain": {"type": "string",
                                  "description": "顾问实际思维模式，用 → 连接"},
                    "bad_chain_note": {"type": "string"},
                    "good_chain": {"type": "string",
                                   "description": "正确思维模式，用 → 连接"},
                    "missing_step": {"type": "string",
                                     "description": "缺失的关键一步"},
                    "training": {
                        "type": "array",
                        "minItems": 5, "maxItems": 5,
                        "items": {
                            "type": "object",
                            "required": ["stage", "issue", "skill"],
                            "properties": {
                                "stage": {"type": "string",
                                          "enum": ["破冰", "需求挖掘", "产品推荐",
                                                   "异议处理", "项目结束后"]},
                                "issue": {"type": "string",
                                          "description": "本次暴露的问题，20 字以内"},
                                "skill": {"type": "string",
                                          "description": "需要训练的能力"},
                            },
                        },
                    },
                },
            },
            "next_steps": {
                "type": "object",
                "required": ["return_scripts", "priority_projects",
                             "pain_entry_scripts", "medical_objections"],
                "properties": {
                    "return_scripts": {
                        "type": "array",
                        "minItems": 2,
                        "items": {
                            "type": "object",
                            "required": ["title"],
                            "properties": {
                                "title": {"type": "string",
                                          "description": "回店切入角度短标题，≤10 字（如'从干眼症切入'），不写话术、不带引号"},
                            },
                        },
                    },
                    "priority_projects": {
                        "type": "array",
                        "minItems": 2, "maxItems": 3,
                        "items": {
                            "type": "object",
                            "required": ["name", "desc"],
                            "properties": {
                                "name": {"type": "string"},
                                "desc": {"type": "string"},
                            },
                        },
                    },
                    "pain_entry_scripts": {
                        "type": "array",
                        "minItems": 2,
                        "items": {
                            "type": "object",
                            "required": ["pain_name"],
                            "properties": {
                                "pain_name": {"type": "string",
                                              "description": "痛点名，≤8 字（如'肩颈僵硬'），不写描述句、不带引号"},
                            },
                        },
                    },
                    "medical_objections": {
                        "type": "array",
                        "minItems": 3,
                        "items": {
                            "type": "object",
                            "required": ["objection", "answer"],
                            "properties": {
                                "objection": {"type": "string"},
                                "answer": {"type": "string", "minLength": 60,
                                           "description": "60-100 字，遵循：承认医院→分工边界→我们位置→互补不冲突"},
                            },
                        },
                    },
                },
            },
        },
    },
}


# ─── Shared-context preflight Tool Schema ────────────────
# 仅做内部底稿：判断 + 证据 + 关键原话；不写完整话术 / 长篇方案。
# 输出存到 analysis_result._shared_customer_context / _shared_quality_context，
# 渲染时被 `_` 前缀过滤掉，不暴露给前端。

TOOL_SHARED_CALL1 = {
    "name": "submit_shared_call1",
    "description": "提交 Call1 的共用顾客判断底稿（不是最终报告）",
    "input_schema": {
        "type": "object",
        "required": ["shared_customer_context"],
        "properties": {
            "shared_customer_context": {
                "type": "object",
                "required": ["customer_profile", "core_pains", "external_signals",
                             "deal_judgment", "key_quotes"],
                "properties": {
                    "customer_profile": {
                        "type": "object",
                        "description": "顾客画像要点，不写长篇判断",
                        "properties": {
                            "age_band": {"type": "string", "description": "年龄段，如'30-35'"},
                            "occupation": {"type": "string"},
                            "consumption_style": {"type": "string",
                                                  "description": "消费观一句话，<30 字"},
                            "personality_signals": {"type": "array",
                                                     "items": {"type": "string"},
                                                     "description": "性格信号，每条 <20 字，最多 5 条"},
                            "decision_type": {"type": "string",
                                              "description": "主动决策型 / 被动决策型 / 比价型 等，一句话"},
                        },
                    },
                    "core_pains": {
                        "type": "array",
                        "description": "核心痛点，最多 5 条；只列名 + 证据原话",
                        "items": {
                            "type": "object",
                            "required": ["name", "evidence_quote"],
                            "properties": {
                                "name": {"type": "string", "description": "痛点名，<15 字"},
                                "evidence_quote": {"type": "string",
                                                   "description": "证据原话，照抄"},
                                "speaker": {"type": "string",
                                            "description": "顾客 / 顾问"},
                                "timestamp_label": {"type": "string",
                                                    "description": "时间戳，如 '12:30'"},
                            },
                        },
                    },
                    "external_signals": {
                        "type": "object",
                        "properties": {
                            "medical_aesthetics": {"type": "array",
                                                    "items": {"type": "string"},
                                                    "description": "医美经历，每条 <20 字"},
                            "lifestyle_habits": {"type": "array",
                                                  "items": {"type": "string"}},
                            "external_brands": {"type": "array",
                                                 "items": {"type": "string"},
                                                 "description": "外部品牌 / 项目 / 机构"},
                        },
                    },
                    "deal_judgment": {
                        "type": "object",
                        "description": "成交判断要点，仅事实和依据",
                        "properties": {
                            "is_deal": {"type": "string",
                                        "description": "成交 / 未成交 / 待定"},
                            "deal_amount": {"type": "string"},
                            "deal_items": {"type": "array",
                                            "items": {"type": "string"}},
                            "risk_level": {"type": "string",
                                            "description": "低 / 中 / 高"},
                            "key_basis_quotes": {"type": "array",
                                                  "items": {"type": "string"},
                                                  "description": "支撑判断的原话，最多 3 条"},
                        },
                    },
                    "key_quotes": {
                        "type": "array",
                        "description": "贯穿后续判断的关键原话，8-12 条，照抄",
                        "items": {
                            "type": "object",
                            "required": ["text"],
                            "properties": {
                                "speaker": {"type": "string"},
                                "timestamp_label": {"type": "string"},
                                "text": {"type": "string"},
                                "use_for": {"type": "string",
                                            "description": "用途标记，如 '画像/痛点/成交'"},
                            },
                        },
                    },
                },
            },
        },
    },
}


TOOL_SHARED_CALL2 = {
    "name": "submit_shared_call2",
    "description": "提交 Call2 的共用质检判断底稿（不是最终报告）",
    "input_schema": {
        "type": "object",
        "required": ["shared_quality_context"],
        "properties": {
            "shared_quality_context": {
                "type": "object",
                "required": ["stage_scoring_basis", "root_cause_hypothesis",
                             "case_candidates", "harvest_opportunities", "key_quotes"],
                "properties": {
                    "stage_scoring_basis": {
                        "type": "array",
                        "description": "三阶段（开场/挖需+方案/收单）的评分依据；只写命中要点，不出最终分数",
                        "items": {
                            "type": "object",
                            "required": ["stage_name", "good_points", "bad_points"],
                            "properties": {
                                "stage_name": {"type": "string",
                                                "description": "开场 / 挖需+方案 / 收单"},
                                "good_points": {"type": "array",
                                                 "items": {"type": "string"},
                                                 "description": "亮点要点，每条 <30 字"},
                                "bad_points": {"type": "array",
                                                "items": {"type": "string"},
                                                "description": "失分要点，每条 <30 字"},
                                "rough_band": {"type": "string",
                                                "description": "粗略档位：差/一般/好/优秀（仅供下游参考）"},
                            },
                        },
                    },
                    "root_cause_hypothesis": {
                        "type": "object",
                        "description": "失分根因假设；不写改进建议",
                        "properties": {
                            "headline": {"type": "string",
                                          "description": "一句话根因假设，<40 字"},
                            "gap_pairs": {
                                "type": "array",
                                "description": "顾问说了什么 vs 应该说什么，至少 3 对",
                                "items": {
                                    "type": "object",
                                    "properties": {
                                        "actually_said": {"type": "string"},
                                        "should_say": {"type": "string"},
                                    },
                                },
                            },
                            "gap_note": {"type": "string",
                                          "description": "差距本质一句话，<30 字"},
                        },
                    },
                    "case_candidates": {
                        "type": "array",
                        "description": "候选 case 原始片段，6-10 条；不做三层分析",
                        "items": {
                            "type": "object",
                            "required": ["kind", "title", "quote"],
                            "properties": {
                                "kind": {"type": "string",
                                          "description": "做得好 / 做得差 / 关键转折"},
                                "title": {"type": "string", "description": "<20 字"},
                                "timestamp_label": {"type": "string"},
                                "speaker": {"type": "string"},
                                "quote": {"type": "string", "description": "原话照抄"},
                                "hit_logic": {"type": "string",
                                               "description": "命中的判断逻辑要点（不写 L 编号），<30 字"},
                            },
                        },
                    },
                    "harvest_opportunities": {
                        "type": "array",
                        "description": "收割 / 复购机会原始线索；不写完整方案",
                        "items": {
                            "type": "object",
                            "properties": {
                                "opportunity": {"type": "string",
                                                 "description": "机会一句话，<30 字"},
                                "evidence_quote": {"type": "string"},
                            },
                        },
                    },
                    "key_quotes": {
                        "type": "array",
                        "description": "质检判断关键原话，8-12 条",
                        "items": {
                            "type": "object",
                            "required": ["text"],
                            "properties": {
                                "speaker": {"type": "string"},
                                "timestamp_label": {"type": "string"},
                                "text": {"type": "string"},
                                "use_for": {"type": "string",
                                             "description": "用途标记，如 '评分/根因/case'"},
                            },
                        },
                    },
                },
            },
        },
    },
}


# ─── 任务注册表 ───────────────────────────────────────────
# 每个任务的元数据：属于哪次调用、输出字段、user_prompt 片段。
# TOOL_CALL1/2/3 的聚合 schema 不变；单任务重跑时会从聚合 schema 抽出对应
# properties 拼一个子集 schema 调用，省 token、隔离失败。

TASK_REGISTRY = {
    # ───── 调用1：仅录音 ─────
    "T1": {
        "name": "顾客真实画像",
        "call": 1,
        "result_keys": ["persona"],
        "schema_keys": ["persona"],
        "depends_on": [],
        "prompt_snippet": """【任务1】顾客真实画像
从顾客发言中提取 5 条以上"对话信号→解读"（填入 persona.signals）。
**persona.summary 字段必填**（不能省略！），写一句尖锐的综合判断：消费类型（主动/被动） + 决策驱动力（专业信任/价格/情感） + 当前状态评估。
例如："被动决策型——给了三个痛点信号没有一个被顾问接住，处于'惯性回头客'状态，随时可能沉默流失。"
""",
    },
    "T2": {
        "name": "可攻破痛点+作战方案",
        "call": 1,
        "result_keys": ["pain_points"],
        "schema_keys": ["pain_points"],
        "depends_on": [],
        "prompt_snippet": """【任务2】可攻破痛点 + 完整作战方案
从录音中识别 2-4 个可攻破痛点，三种 badge 尽量都覆盖：最强突破口 / 最佳情感连接点 / 最高价值突破口。
每个痛点的四步话术（label 严格枚举）：
  第一步 建立专业诊断感：说出顾客不知道的专业判断
  第二步 放大连锁影响：这个问题不解决会引发什么
  第三步 说出为什么之前没解决：区分我们和之前的方法
  第四步 给系统方案 + 预期：几次、多久、什么效果
每步 body 必须含"{customer_name}"，30-60 字。
""",
    },
    "T3": {
        "name": "竞品提取+顾客标签",
        "call": 1,
        # 同时输出两个字段
        "result_keys": ["external_signals", "customer_tags"],
        "schema_keys": ["external_signals", "customer_tags"],
        "depends_on": [],
        "prompt_snippet": """【任务3】竞品提取 + 顾客标签

⚠ 重要：本任务必须输出两个独立字段：
  (A) external_signals —— 5 类外部信号分类提取
  (B) customer_tags    —— 汇总的标签数组（外层字段，至少 5 个）
两个都要填，缺一个就算失败。

【(A) external_signals】

竞品定义：所有不是身美自家的、属于美容护肤/医美/养生**同行业**的外部品牌、项目、仪器、机构都是竞品。
非同行业的（如大众点评、外卖、银行、餐厅、电商、社交平台等）不算竞品，不要提取。

竞品分三类（每条要 item + type + customer_quote + competitor_learn）：
1. medical_aesthetics：别家医美项目
2. other_institutions：别家美容院/医美机构/养生机构
3. external_brands：别家护肤品牌/仪器品牌/产品名

⚠ 生活习惯和自我护理不需要单独提取，直接归纳到 customer_tags 数组里即可。
比如顾客经常熬夜 → customer_tags 加 {{"tag":"长期熬夜","count":N}}
比如顾客在用益生菌 → customer_tags 加 {{"tag":"益生菌用户","count":N}}

【(B) customer_tags 数组（外层字段，绝对不能省略！）】

从整段录音里提炼顾客特征标签（每个 10 字以内），至少 5 个。
来源不限：消费类型 + 生活习惯 + 身体状况 + 心理特征 + 品牌偏好。

每个标签必须输出为对象 {{"tag": "标签名", "count": N}}，其中 count 为本次录音里**顾客自己**提及该标签相关话题的次数（统计顾客发言里出现该话题的轮次，至少为 1）。
示例：[{{"tag":"医美深度用户","count":4}}, {{"tag":"C级潜力客户","count":1}}, {{"tag":"长期熬夜","count":3}}, {{"tag":"理性克制型","count":2}}, {{"tag":"活细胞用户","count":2}}]

⚠ 再次强调：customer_tags 是 external_signals 的**同级**字段，不是包在里面。两个字段都要填，缺一个就是失败。每个元素必须是带 tag+count 的对象，不能是裸字符串。
""",
    },
    "T4": {
        "name": "成交诊断",
        "call": 1,
        "result_keys": ["deal_diagnosis"],
        "schema_keys": ["deal_diagnosis"],
        "depends_on": [],
        "prompt_snippet": """【任务4】成交诊断 + 风险预警
判断 5 维度（status 取 ok/partial/missing，note 引用原话）：
customer_moved / customer_agreed / effect_satisfied / price_matched / urgency_built

同时判断：
- deal_result：是否成交（true/false）
- deal_amount：识别成交金额
  · 如果 deal_result=true → 填具体金额如"6800元"，识别不到就填"未识别"
  · 如果 deal_result=false → **必须填"无"**（不能填金额，不能留空）
- risk_level 按以下规则严格判断：
  · 未成交 且 心动/认同/效果满意 三条都不是 ok → "high"
  · 未成交 但 三条里有至少一条 partial → "medium"
  · 已成交 → "low"

规则：deal_result=false 且前三项都不是 ok → risk_alert=true，risk_text 写明风险点。
""",
    },

    # ───── 调用2：录音 + 知识库 ─────
    "T5": {
        "name": "质检评分",
        "call": 2,
        "result_keys": ["scoring"],
        "schema_keys": ["scoring"],
        "depends_on": [],
        "prompt_snippet": """【任务5】质检评分 · 三大接诊阶段
综合评分 0-10，必须有区分度（差 2-3 分，一般 4-5 分，好 7-8 分，很好 9 分）。

⚠ scoring.stages 必须是 3 个阶段，每个阶段必须包含所有子项，不能省略：

壹 一咨找需求（5 子项）：
  1.1 档案掌握与破冰
  1.2 快速找到痛点
  1.3 做检测
  1.4 解决原理和方向（不提项目）
  1.5 解决方案

贰 确认加大意愿（3 子项）：
  2.1 重述痛点原理
  2.2 客人做对比效果感受
  2.3 提供情绪价值

叁 成交阶段（6 子项）：
  3.1 效果确认
  3.2 顾客当下结论评估
  3.3 本店解决方向和方案
  3.4 报价（提到价格即算触发）
  3.5 异议处理
  3.6 好评 + 返邀约

每个子项 detail 只写一句本次事实（不写定义不写建议，20-40 字）。
good_highlights 和 bad_highlights 各 2-3 条，不能为空数组。

⚠ 计分规则（严格执行）：
- 每个 sub 子项只打分（0-10），stage 的 score 字段固定填 0，overall 字段固定填 0
- 不要自己算平均分，系统会自动根据子项均值重新计算 stage 分和总分
""",
    },
    "T6": {
        "name": "Case复盘",
        "call": 2,
        "result_keys": ["cases", "cases_summary"],
        "schema_keys": ["cases", "cases_summary"],
        "depends_on": [],
        "prompt_snippet": """【任务6】关键 Case 复盘
5-8 条，按时间顺序，good / miss / bad 都有（**至少各 1 条，不能全是 miss**）。
每条 kind / title / quote（「」15 字内）/ segment / timestamp_seconds / timestamp_label / surface / deep / improve。
- deep：这个具体时刻的问题，只说这一句话错在哪，不要上升到整体根因，20-30 字
- improve 必须含"{customer_name}"的具体话术
- cases_summary 字段必填，一句话总结整个 Case，要尖锐有力

时间戳：录音转录每行 `[Xs - Ys] 说话人N: ...`。第 2 段的 [134s-142s] → segment=2, timestamp_seconds=134, timestamp_label="0:02:14"。
""",
    },
    "T8": {
        "name": "黄金窗口收割",
        "call": 2,
        "result_keys": ["harvest"],
        "schema_keys": ["harvest"],
        "depends_on": [],
        "prompt_snippet": """【任务8】项目后价值收割·黄金窗口标准流程
项目结束后的 10 分钟是成交概率最高的窗口（顾客身体放松、防御最低）。
基于本次接诊的痛点和顾客状态，给出 3-5 步顾问应执行的标准收割动作。

⚠ harvest 字段：
- intro：导语一句，强调黄金窗口为什么重要
- steps：3-5 步，每步**只写 title 步骤名，≤8 字、动宾短语**（如"引导说出效果""埋下下次钩子"），不写 body、不写完整话术、不带引号

注意：harvest 是给顾问的"下次怎么做"指引，不是复盘本次。只列步骤标题即可，不展开话术。
""",
    },

    # ───── 调用3：用前两次结果摘要 ─────
    "T9": {
        "name": "PART1总览",
        "call": 3,
        "result_keys": ["overview"],
        "schema_keys": ["overview"],
        "depends_on": ["T1", "T2", "T3", "T4", "T5", "T6"],
        "prompt_snippet": """【任务9】PART1 全维度评估总览
⚠ overview 对象必含 4 个子字段，都不能省略：
- customer_value：顾客价值评级（tag 简短 + tag_kind + note 一句话引用关键信号）
- pain_summary：痛点识别（tag 如"3 个核心可攻破点" + items 列每个痛点，color 用 red/blue/teal/orange）
- sales_diagnosis：销售问题诊断（tag 标签化根因 + tag_kind + note 一句话点出销售根因）
- quality_score：质检评分（score 复用 overall + note 一句话）
""",
    },
    "T10": {
        "name": "能力训练路径",
        "call": 3,
        "result_keys": ["logic_chain"],
        "schema_keys": ["logic_chain"],
        "depends_on": ["T6"],
        "prompt_snippet": """【任务10】能力训练路径
⚠ logic_chain 对象所有字段必填：
- bad_chain：顾问实际思维链（用 → 连接），如"顾客来了 → 了解需求 → 介绍产品 → 希望成交"
- bad_chain_note：一句话点评 bad_chain 的问题
- good_chain：正确思维链（用 → 连接）
- missing_step：缺失关键一步
- training：5 个阶段固定（破冰 / 需求挖掘 / 产品推荐 / 异议处理 / 项目结束后）
  每阶段：stage + issue（本次问题 20 字内）+ skill（需要训练的能力）
""",
    },
    "T11": {
        "name": "下一步动作",
        "call": 3,
        "result_keys": ["next_steps"],
        "schema_keys": ["next_steps"],
        "depends_on": ["T1", "T2", "T4"],
        "prompt_snippet": """【任务11】下一步动作 · 回店规划
⚠ next_steps 对象 4 个字段必填：
- return_scripts：至少 2 条回店切入角度，**每条只写 title 短标题：≤10 字的切入角度名**（如"从干眼症切入""借换季理由"），不写话术正文、不写完整句子、不带引号
- priority_projects：按成交难度从低到高 2-3 条（name + desc）
- pain_entry_scripts：列出针对的每个痛点，**每条只写 pain_name：≤8 字的痛点名**（如"肩颈僵硬""睡眠差""产后修复"），不写描述句、不写 entry/principle/direction/sales_link 话术、不带引号
- medical_objections：至少 3 条泛医疗异议应答，60-100 字，严格遵循 ①承认医院 → ②分工边界 → ③我们位置 → ④互补不冲突
""",
    },
}


CALL_GROUPS = {
    1: ["T1", "T2", "T3", "T4"],
    2: ["T5", "T6", "T8"],
    3: ["T9", "T10", "T11"],
}

# 在同一个 Call 内部，每个 chunk 走一次独立的 LLM 小调用，输出字段少、不易被
# DeepSeek V4 tool_call 的字符串化 JSON 撑爆 max_tokens。Call 1/2 走 shared
# preflight 先产判断底稿，再按下面分块各自基于底稿组装正式字段；Call 3 输入
# 本就是结构化摘要，不做 preflight，直接分块。
CALL_CHUNKS = {
    1: [["T1"], ["T2"], ["T3", "T4"]],
    2: [["T5"], ["T6"], ["T8"]],
    3: [["T9"], ["T10"], ["T11"]],
}

SHARED_CONTEXT_KEYS = {
    1: "_shared_customer_context",
    2: "_shared_quality_context",
}


def expand_to_call_chunk(task_ids):
    """把任务列表扩展到各自所在 chunk 的完整任务集（不再扩到整个 call group）。
    新分块模型下，每个 chunk 就是一次独立 LLM 小调用：缺哪个任务，只把同 chunk
    的兄弟带上一起跑，避免把同 call 已完成的其它任务无谓重跑。
    """
    expanded = set()
    for tid in task_ids:
        if tid not in TASK_REGISTRY:
            continue
        call_no = TASK_REGISTRY[tid]["call"]
        for chunk in CALL_CHUNKS[call_no]:
            if tid in chunk:
                expanded.update(chunk)
                break
    return list(expanded)


# ─── 任务输出验证 ─────────────────────────────────────────
_NONEMPTY_LIST_KEYS = {
    "customer_tags", "cases", "pain_points",
    "good_highlights", "bad_highlights",
    "return_scripts", "pain_entry_scripts", "medical_objections",
}


def validate_task_output(task_id, result):
    """检查任务输出是否合格。返回 (ok: bool, reason: str)"""
    if not result:
        return False, "结果为空"

    task = TASK_REGISTRY[task_id]
    for key in task["result_keys"]:
        if key not in result:
            return False, f"缺少字段 {key}"
        val = result[key]
        # 对象类型不能是空 {}
        if isinstance(val, dict) and not val:
            return False, f"{key} 是空对象 {{}}"
        # 关键数组字段不能为空 []
        if isinstance(val, list) and not val and key in _NONEMPTY_LIST_KEYS:
            return False, f"{key} 是空数组"

    # 特殊规则
    if task_id == "T3":
        tags = result.get("customer_tags") or []
        if not isinstance(tags, list) or len(tags) < 5:
            return False, f"customer_tags 必须 ≥5 个，实际 {len(tags) if isinstance(tags, list) else 0}"

    if task_id == "T4":
        diag = result.get("deal_diagnosis") or {}
        if not diag.get("deal_amount"):
            return False, "deal_amount 未填"
        if not diag.get("risk_level"):
            return False, "risk_level 未填"

    if task_id == "T5":
        scoring = result.get("scoring") or {}
        stages = scoring.get("stages") or []
        if len(stages) != 3:
            return False, f"stages 必须是 3 个，实际 {len(stages)}"

    return True, ""


def build_call_summaries(call1_result: dict, call2_result: dict):
    """把调用1和2的结果压缩成给调用3的输入摘要（约 1.5-2K 字）。"""
    c1 = []
    persona = call1_result.get("persona", {}) or {}
    c1.append(f"【顾客画像】{persona.get('summary', '')}")
    for s in (persona.get("signals") or [])[:6]:
        c1.append(f"  · {s.get('signal','')} → {s.get('interpretation','')}")

    c1.append("【识别到的痛点】")
    for pp in call1_result.get("pain_points") or []:
        c1.append(f"  · {pp.get('title','')}（{pp.get('badge','')}）证据：{pp.get('evidence','')}")

    ext = call1_result.get("external_signals", {}) or {}
    med = ext.get("medical_aesthetics") or []
    if med:
        c1.append(f"【医美经历】{' / '.join(m.get('item','') for m in med)}")
    habits = ext.get("lifestyle_habits") or []
    if habits:
        c1.append(f"【生活习惯】{' / '.join(h.get('item','') for h in habits)}")
    brands = ext.get("external_brands") or []
    if brands:
        c1.append(f"【外部品牌】{' / '.join(b.get('item','') for b in brands)}")

    diag = call1_result.get("deal_diagnosis", {}) or {}
    dims = diag.get("dimensions", {}) or {}
    c1.append("【成交诊断】")
    for k, v in dims.items():
        if isinstance(v, dict):
            c1.append(f"  · {k}: {v.get('status','')} — {v.get('note','')}")
    if diag.get("risk_alert"):
        c1.append(f"⚠️ 差评风险：{diag.get('risk_text','')}")

    c2 = []
    scoring = call2_result.get("scoring", {}) or {}
    c2.append(f"【综合评分】{scoring.get('overall','')} 分")
    c2.append(f"亮点：{' / '.join(scoring.get('good_highlights') or [])}")
    c2.append(f"失分：{' / '.join(scoring.get('bad_highlights') or [])}")

    c2.append("【各阶段评分】")
    for stage in scoring.get("stages") or []:
        c2.append(f"  {stage.get('name','')} {stage.get('score','')} 分")
        for sub in stage.get("sub") or []:
            c2.append(f"    · {sub.get('name','')} {sub.get('score','')} 分：{sub.get('detail','')}")

    c2.append("【关键 Case 摘要】")
    for case in call2_result.get("cases") or []:
        c2.append(f"  [{case.get('kind','')}] {case.get('title','')}（{case.get('timestamp_label','')}）原话：{case.get('quote','')}")

    c2.append(f"【一句话总结】{call2_result.get('cases_summary','')}")

    return "\n".join(c1), "\n".join(c2)


def _call_anthropic(model, system_prompt, user_prompt, tool=None, max_tokens=16000):
    """调用 Claude 走代理，返回 tool_use 的 input dict"""
    from anthropic import Anthropic
    import httpx

    tool = tool or REPORT_TOOL
    client = Anthropic(
        api_key=ANTHROPIC_API_KEY,
        http_client=httpx.Client(
            proxy=ANTHROPIC_PROXY,
            http2=False,
            timeout=httpx.Timeout(connect=15.0, read=300.0, write=60.0, pool=15.0),
        ) if ANTHROPIC_PROXY else None,
    )
    message = client.messages.create(
        model=model,
        max_tokens=max_tokens,
        system=system_prompt,
        tools=[tool],
        tool_choice={"type": "tool", "name": tool["name"]},
        messages=[{"role": "user", "content": user_prompt}],
    )
    tool_blocks = [blk for blk in message.content if getattr(blk, "type", "") == "tool_use"]
    if not tool_blocks:
        raise RuntimeError(f"Claude 未调用 {tool['name']} 工具")
    # 合并所有 tool_use block（正常只有 1 个，防御多个）
    merged = {}
    for blk in tool_blocks:
        if isinstance(blk.input, dict):
            merged.update(blk.input)
    return merged


def _parse_tool_calls_arguments(tool_calls, stage_label="") -> dict:
    """把 DeepSeek 返回的 tool_calls 列表合并成一个 dict。

    兼容两种情况：
    1. 多个 tool_call，每个 arguments 是完整 JSON object → 遍历全部，逐个 merge
    2. 某个 arguments 字符串里被拼了多个 JSON object（{...}{...}）→ raw_decode 循环解析

    合并规则：
    - 不同 key 直接加入
    - 同 key 都是 dict → 递归 merge
    - 同 key 冲突 → 后面覆盖前面，打 warning
    - 解析结果不是 dict → 打 warning，跳过，不让整体失败
    """
    def _merge(base: dict, patch: dict) -> dict:
        for k, v in patch.items():
            if k in base:
                if isinstance(base[k], dict) and isinstance(v, dict):
                    base[k] = _merge(base[k], v)
                else:
                    print(f"[parse_tool_calls] key 冲突，覆盖: {k}")
                    base[k] = v
            else:
                base[k] = v
        return base

    def _raw_decode_all(s: str) -> list:
        """从字符串里循环解析出所有 JSON object，兼容 {...}{...} 拼接情况。"""
        decoder = json.JSONDecoder()
        results = []
        idx = 0
        s = s.strip()
        while idx < len(s):
            # 跳过空白
            while idx < len(s) and s[idx] in " \t\n\r":
                idx += 1
            if idx >= len(s):
                break
            try:
                obj, end_idx = decoder.raw_decode(s, idx)
                results.append(obj)
                idx = end_idx
            except json.JSONDecodeError as e:
                print(f"[parse_tool_calls] raw_decode 失败 at {idx}: {e}")
                break
        return results

    tag = f"[parse_tool_calls{':'+stage_label if stage_label else ''}]"
    # DeepSeek V4 偶发把 tool_calls 整个 list 或单个 entry 序列化成字符串
    if isinstance(tool_calls, str):
        try:
            tool_calls = json.loads(tool_calls)
            print(f"{tag} tool_calls 是字符串，已 JSON 解开")
        except (json.JSONDecodeError, ValueError) as e:
            print(f"{tag} tool_calls 是字符串且无法解析: {e}; head={tool_calls[:200]!r}")
            tool_calls = []
    if not isinstance(tool_calls, list):
        print(f"{tag} tool_calls 不是 list: {type(tool_calls).__name__}")
        tool_calls = []
    print(f"{tag} tool_calls 数量: {len(tool_calls)}")
    merged = {}
    for i, tc in enumerate(tool_calls):
        # tc 也可能是字符串
        if isinstance(tc, str):
            try:
                tc = json.loads(tc)
                print(f"{tag} tool_call[{i}] 是字符串，已 JSON 解开")
            except (json.JSONDecodeError, ValueError):
                print(f"{tag} tool_call[{i}] 是字符串且无法解析；head={tc[:200]!r}")
                continue
        if not isinstance(tc, dict):
            print(f"{tag} tool_call[{i}] 不是 dict: {type(tc).__name__}，跳过")
            continue
        fn = tc.get("function", {})
        # DeepSeek V4 偶发把整个 function 字段序列化成 JSON 字符串
        if isinstance(fn, str):
            try:
                fn = json.loads(fn)
            except (json.JSONDecodeError, ValueError):
                fn = {}
        args_str = fn.get("arguments", "") if isinstance(fn, dict) else ""
        print(f"{tag} tool_call[{i}] arguments 长度: {len(args_str)}")
        # DeepSeek V4 偶发在 JSON 字符串里用非法转义 \' （JSON 不支持），
        # raw_decode 会直接报 "Expecting ',' delimiter"。这里做最小清洗：
        # 仅把不是 \\\' 形式的 \' 替换成 '，不动 \" \\ \n \uXXXX 等合法转义。
        if args_str:
            cleaned, n_sub = re.subn(r"(?<!\\)\\'", "'", args_str)
            if n_sub > 0:
                print(f"{tag} cleaned illegal escape \\' x{n_sub}")
                args_str = cleaned
        objs = _raw_decode_all(args_str)
        for obj in objs:
            if not isinstance(obj, dict):
                print(f"{tag} tool_call[{i}] 解析结果不是 dict，跳过: {type(obj)}")
                continue
            print(f"{tag} tool_call[{i}] 解析出 keys: {list(obj.keys())}")
            merged = _merge(merged, obj)

    print(f"{tag} merge 后最终 keys: {list(merged.keys())}")
    if not merged:
        # 空 dict：把每个 tool_call 的 arguments 长度、头尾各 500 字打出来，
        # 用于判断到底是空字符串、坏 JSON 还是嵌套字符串没解开
        # 注意：tc["function"] 在 DeepSeek V4 偶发是字符串，必须 isinstance 守卫，
        # 否则 .get 会抛 "'str' object has no attribute 'get'" 把真实错误掩盖
        for i, tc in enumerate(tool_calls):
            fn = tc.get("function", {}) if isinstance(tc, dict) else {}
            if isinstance(fn, str):
                fn_str_head = fn[:200]
                try:
                    fn = json.loads(fn)
                except (json.JSONDecodeError, ValueError):
                    print(f"{tag} EMPTY_MERGE tool_call[{i}] function 是字符串且无法 JSON 解析；"
                          f"head={fn_str_head!r}")
                    fn = {}
            args_str = fn.get("arguments", "") if isinstance(fn, dict) else ""
            args_str = args_str or ""
            head = args_str[:500]
            tail = args_str[-500:] if len(args_str) > 500 else ""
            print(f"{tag} EMPTY_MERGE tool_call[{i}] len={len(args_str)} "
                  f"head={head!r} tail={tail!r}")
    return merged


def _deep_json_unwrap(node):
    """DeepSeek V4 tool-call 会把嵌套对象/数组序列化成字符串，递归反解析。"""
    if isinstance(node, str):
        s = node.strip()
        if s.startswith(("{", "[")):
            try:
                return _deep_json_unwrap(json.loads(s))
            except (json.JSONDecodeError, ValueError):
                return node
        return node
    if isinstance(node, list):
        return [_deep_json_unwrap(x) for x in node]
    if isinstance(node, dict):
        return {k: _deep_json_unwrap(v) for k, v in node.items()}
    return node


def _call_deepseek(model, system_prompt, user_prompt, tool=None, max_tokens=None,
                   enable_thinking=False, stage_label="", temperature=0.3,
                   force_tool_choice=False):
    """调用 DeepSeek（OpenAI 兼容），不走代理，返回 tool_calls[0].function.arguments dict。

    max_tokens 语义是"输出 token 预算"；对 V4 思考模式会在内部加 16K thinking buffer。
    force_tool_choice=True 时：显式 tool_choice=function 强制调用工具（用于打破 V4 auto
    模式偶发返回空 tool_calls 的死循环）；此时会自动关闭 V4 thinking，因为 V4 思考模式
    只支持 tool_choice=auto。
    """
    import httpx

    if not DEEPSEEK_API_KEY:
        raise RuntimeError("未配置 DEEPSEEK_API_KEY")

    tool = tool or REPORT_TOOL
    openai_tool = {
        "type": "function",
        "function": {
            "name": tool["name"],
            "description": tool["description"],
            "parameters": tool["input_schema"],
        },
    }

    is_v4 = "v4" in model.lower()
    # 输出 token 预算（不含 V4 thinking）
    out_budget = max_tokens or 8000
    # force_tool_choice 与 V4 thinking 互斥：强制 tool_choice 时关掉 thinking
    effective_thinking = enable_thinking and not force_tool_choice
    # 实际是否启用 V4 thinking
    use_thinking = is_v4 and effective_thinking
    explicit_tc = {"type": "function", "function": {"name": tool["name"]}}
    payload = {
        "model": model,
        "messages": [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_prompt},
        ],
        "tools": [openai_tool],
        "max_tokens": out_budget,
        "temperature": temperature,
    }
    # tool_choice 与 thinking 的关系（DeepSeek V4 实测）：
    #   - thinking 开启时：API 不接受任何 tool_choice（含 "auto"），下发会报
    #     "Thinking mode does not support this tool_choice"，必须完全省略该字段，
    #     让模型自然调用工具（空 tool_calls 由上层重试兜底，最后一次会 force 强制）
    #   - force_tool_choice：显式指定函数强制调用（此时 thinking 已关）
    #   - 非 V4 模型：显式强制指定函数
    if use_thinking:
        pass  # 不下发 tool_choice
    elif force_tool_choice or not is_v4:
        payload["tool_choice"] = explicit_tc
    else:
        payload["tool_choice"] = "auto"
    # V4 thinking 模式：只在显式开启且未强制 tool_choice 时启用
    if use_thinking:
        payload["reasoning_effort"] = "high"
        payload["thinking"] = {"type": "enabled"}
        # thinking token 计入 max_tokens，预留 24K buffer
        payload["max_tokens"] = max(out_budget + 24000, 28000)
    # trust_env=False 关键：服务器有 http_proxy=7890，DeepSeek 不能走代理
    with httpx.Client(
        trust_env=False,
        # thinking 开启时耗时长（~20min），关闭时普通超时即可
        timeout=httpx.Timeout(connect=15.0, read=(1200.0 if enable_thinking else 600.0), write=60.0, pool=15.0),
    ) as client:
        resp = client.post(
            f"{DEEPSEEK_API_BASE.rstrip('/')}/chat/completions",
            headers={
                "Authorization": f"Bearer {DEEPSEEK_API_KEY}",
                "Content-Type": "application/json",
            },
            json=payload,
        )
    if resp.status_code != 200:
        raise RuntimeError(f"DeepSeek HTTP {resp.status_code}: {resp.text[:500]}")
    try:
        data = resp.json()
    except Exception as json_err:
        raise RuntimeError(
            f"DeepSeek 响应不是合法 JSON: {json_err}; "
            f"HTTP {resp.status_code}; body_head={resp.text[:300]}"
        )
    stage_tag = f"[{stage_label}] " if stage_label else ""
    try:
        choice0 = data["choices"][0]
        finish = choice0.get("finish_reason")
        msg = choice0["message"]
        tool_calls = msg.get("tool_calls") or []
        # length 截断：成功路径也显式抛错，不要让下游解析半截 JSON 当 "结果为空"
        if finish == "length":
            raise RuntimeError(
                f"{stage_tag}DeepSeek 输出被 max_tokens 截断（finish_reason=length，"
                f"out_budget={out_budget}，model={model}）；"
                f"建议拆分输出或调大预算"
            )
        if not tool_calls:
            raise RuntimeError(
                f"{stage_tag}DeepSeek 未返回 tool_calls；finish_reason={finish}；"
                f"content={msg.get('content','')[:200]}"
            )
        result = _parse_tool_calls_arguments(tool_calls, stage_label=stage_label)
        if not result:
            raise RuntimeError(
                f"{stage_tag}DeepSeek tool_calls 解析后为空 dict；finish_reason={finish}"
            )
        return _deep_json_unwrap(result)
    except (KeyError, IndexError, json.JSONDecodeError, AttributeError, TypeError) as e:
        # 出错时把 finish_reason 显式带出来，方便判断是否是 length 截断
        finish = None
        try:
            finish = data["choices"][0].get("finish_reason")
        except Exception:
            pass
        raise RuntimeError(
            f"{stage_tag}DeepSeek 响应解析失败: {e}; finish_reason={finish}; "
            f"body_head={json.dumps(data, ensure_ascii=False)[:400]}"
        )


def _call_doubao(model, system_prompt, user_prompt, tool=None, max_tokens=None,
                 enable_thinking=False, stage_label="", temperature=0.3,
                 force_tool_choice=False):
    """调用豆包（火山方舟 / Ark，OpenAI 兼容接口），不走代理。

    豆包用「接入点ID」（endpoint id）而非模型名作为请求里的 model 字段：
    model='doubao-2.0-lite' -> DOUBAO_EP_LITE，'doubao-2.0-pro' -> DOUBAO_EP_PRO。
    返回 tool_calls[0].function.arguments dict（与 _call_deepseek 一致）。

    骨架说明：DOUBAO_API_KEY 或对应接入点ID 未配置时直接抛 RuntimeError；
    配置齐全后该代码路径可直接用于真实请求。
    """
    import httpx

    endpoint_id = DOUBAO_ENDPOINTS.get(model, "")
    if not DOUBAO_API_KEY or not endpoint_id:
        raise RuntimeError("豆包(火山方舟)未配置，请设置 DOUBAO_API_KEY/接入点ID")

    tool = tool or REPORT_TOOL
    openai_tool = {
        "type": "function",
        "function": {
            "name": tool["name"],
            "description": tool["description"],
            "parameters": tool["input_schema"],
        },
    }

    out_budget = max_tokens or 8000
    explicit_tc = {"type": "function", "function": {"name": tool["name"]}}
    payload = {
        # 火山方舟用接入点ID填 model 字段
        "model": endpoint_id,
        "messages": [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_prompt},
        ],
        "tools": [openai_tool],
        "max_tokens": out_budget,
        "temperature": temperature,
    }
    # 强制工具调用或默认 auto
    if force_tool_choice:
        payload["tool_choice"] = explicit_tc
    else:
        payload["tool_choice"] = "auto"

    # trust_env=False：服务器有 http_proxy=7890，豆包国内直连不走代理
    with httpx.Client(
        trust_env=False,
        timeout=httpx.Timeout(connect=15.0, read=600.0, write=60.0, pool=15.0),
    ) as client:
        resp = client.post(
            f"{DOUBAO_API_BASE.rstrip('/')}/chat/completions",
            headers={
                "Authorization": f"Bearer {DOUBAO_API_KEY}",
                "Content-Type": "application/json",
            },
            json=payload,
        )
    if resp.status_code != 200:
        raise RuntimeError(f"豆包 HTTP {resp.status_code}: {resp.text[:500]}")
    try:
        data = resp.json()
    except Exception as json_err:
        raise RuntimeError(
            f"豆包 响应不是合法 JSON: {json_err}; "
            f"HTTP {resp.status_code}; body_head={resp.text[:300]}"
        )
    stage_tag = f"[{stage_label}] " if stage_label else ""
    try:
        choice0 = data["choices"][0]
        finish = choice0.get("finish_reason")
        msg = choice0["message"]
        tool_calls = msg.get("tool_calls") or []
        if finish == "length":
            raise RuntimeError(
                f"{stage_tag}豆包 输出被 max_tokens 截断（finish_reason=length，"
                f"out_budget={out_budget}，model={model}）；建议拆分输出或调大预算"
            )
        if not tool_calls:
            raise RuntimeError(
                f"{stage_tag}豆包 未返回 tool_calls；finish_reason={finish}；"
                f"content={msg.get('content','')[:200]}"
            )
        result = _parse_tool_calls_arguments(tool_calls, stage_label=stage_label)
        if not result:
            raise RuntimeError(
                f"{stage_tag}豆包 tool_calls 解析后为空 dict；finish_reason={finish}"
            )
        return _deep_json_unwrap(result)
    except (KeyError, IndexError, json.JSONDecodeError, AttributeError, TypeError) as e:
        finish = None
        try:
            finish = data["choices"][0].get("finish_reason")
        except Exception:
            pass
        raise RuntimeError(
            f"{stage_tag}豆包 响应解析失败: {e}; finish_reason={finish}; "
            f"body_head={json.dumps(data, ensure_ascii=False)[:400]}"
        )


def _call_llm(model, system_prompt, user_prompt, tool, max_tokens=None,
              enable_thinking=False, stage_label="", temperature=0.3,
              force_tool_choice=False):
    """统一入口：根据 model 的 provider 调 Claude / DeepSeek / 豆包。"""
    provider = MODEL_PROVIDER.get(model)
    if provider == "anthropic":
        return _call_anthropic(model, system_prompt, user_prompt, tool=tool,
                                max_tokens=max_tokens or 16000)
    if provider == "deepseek":
        return _call_deepseek(model, system_prompt, user_prompt, tool=tool,
                              max_tokens=max_tokens, enable_thinking=enable_thinking,
                              stage_label=stage_label, temperature=temperature,
                              force_tool_choice=force_tool_choice)
    if provider == "doubao":
        return _call_doubao(model, system_prompt, user_prompt, tool=tool,
                            max_tokens=max_tokens, enable_thinking=enable_thinking,
                            stage_label=stage_label, temperature=temperature,
                            force_tool_choice=force_tool_choice)
    raise RuntimeError(f"未知 provider for model {model}")


def _call_llm_with_retry(model, system_prompt, user_prompt, tool, max_tokens=None,
                          enable_thinking=False, stage_label="", max_attempts=3):
    """带通用重试的 LLM 调用。
    适用：DeepSeek tool_calls 偶发 arguments 为空/坏 JSON / 解析后空 dict。
    重试条件：_call_llm 抛错，或返回空/非 dict 结果。
    重试时抖动 temperature（0.3 → 0.6 → 0.9），打破 V4 tool_choice=auto 卡在空响应的循环。
    成功路径不变；最终仍失败则抛出最后一次错误。
    """
    last_err = None
    for attempt in range(1, max_attempts + 1):
        # 第一次走默认 0.3；重试时抖一下，避免复现同样的空 tool_calls
        temperature = 0.3 + 0.3 * (attempt - 1)
        # 最后一次重试：强制 tool_choice 指定函数（V4 会自动关 thinking），
        # 打破 V4 auto 模式偶发返回空 tool_calls 的死循环
        force_tc = attempt == max_attempts and max_attempts > 1
        try:
            result = _call_llm(model, system_prompt, user_prompt, tool=tool,
                               max_tokens=max_tokens,
                               enable_thinking=enable_thinking,
                               stage_label=stage_label,
                               temperature=temperature,
                               force_tool_choice=force_tc)
            if not result or not isinstance(result, dict):
                last_err = RuntimeError(
                    f"[{stage_label}] attempt {attempt}/{max_attempts} "
                    f"返回结果为空或非 dict: {type(result).__name__}")
                print(str(last_err) +
                      ("; 即将重试" if attempt < max_attempts else "; 不再重试"))
                continue
            if attempt > 1:
                print(f"[{stage_label}] 重试 attempt {attempt} 成功")
            return result
        except Exception as e:
            last_err = e
            print(f"[{stage_label}] attempt {attempt}/{max_attempts} (temp={temperature:.1f}) 失败: {e}; "
                  + ("即将重试" if attempt < max_attempts else "不再重试"))
    raise last_err if last_err else RuntimeError(f"[{stage_label}] 未知失败")


def _set_progress(session_id, msg):
    db_write("UPDATE sessions SET analysis_progress=? WHERE id=?", (msg, session_id))


# ─── 任务状态管理 ─────────────────────────────────────────
# task_status 字段是一个 JSON：{"T1":{"status":"done","updated_at":"...","error":null}, ...}
# 单任务级别记录状态，独立于 analysis_status（session 总状态）

def get_task_status(session_id):
    """读取 task_status JSON。返回 dict（无数据则返回 {}）。"""
    row = db_fetchone("SELECT task_status FROM sessions WHERE id=?", (session_id,))
    if not row or not row["task_status"]:
        return {}
    try:
        return json.loads(row["task_status"])
    except (json.JSONDecodeError, TypeError):
        return {}


def set_task_status(session_id, task_id, status, error=None):
    """更新单个任务状态。status: pending / running / done / failed / missing。"""
    ts = get_task_status(session_id)
    ts[task_id] = {
        "status": status,
        "updated_at": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        "error": (error[:300] if error else None),
    }
    db_write(
        "UPDATE sessions SET task_status=? WHERE id=?",
        (json.dumps(ts, ensure_ascii=False), session_id),
    )


def recalc_scoring(scoring):
    """根据 sub 子项分重算 stage.score 和 overall（均保留一位小数）。"""
    if not scoring or not isinstance(scoring, dict):
        return scoring
    stages = scoring.get("stages") or []
    stage_scores = []
    for stg in stages:
        subs = stg.get("sub") or []
        sub_scores = [s.get("score") for s in subs if isinstance(s, dict) and isinstance(s.get("score"), (int, float))]
        if sub_scores:
            avg = round(sum(sub_scores) / len(sub_scores), 1)
            stg["score"] = avg
            stage_scores.append(avg)
    if stage_scores:
        scoring["overall"] = round(sum(stage_scores) / len(stage_scores), 1)
    return scoring


def reconcile_task_status_from_result(session_id):
    """根据 analysis_result 实际内容修正 task_status：
    若某任务的全部 result_keys 都已写入（非空），但 task_status 标的是 failed/missing/running，
    则纠正为 done（带 note 说明来自历史结果）。
    用于解决"重跑部分失败但旧结果仍在"导致的状态不一致问题。
    """
    row = db_fetchone(
        "SELECT analysis_result, task_status FROM sessions WHERE id=?", (session_id,))
    if not row:
        return
    try:
        result = json.loads(row["analysis_result"]) if row["analysis_result"] else {}
    except (json.JSONDecodeError, TypeError):
        result = {}
    try:
        ts = json.loads(row["task_status"]) if row["task_status"] else {}
    except (json.JSONDecodeError, TypeError):
        ts = {}

    changed = False
    for tid, meta in TASK_REGISTRY.items():
        keys = meta["result_keys"]
        all_present = all(
            k in result and result[k] not in (None, "", [], {})
            for k in keys
        )
        cur_status = (ts.get(tid) or {}).get("status")
        if all_present and cur_status != "done" and cur_status != "running":
            ts[tid] = {
                "status": "done",
                "updated_at": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
                "error": None,
                "note": "由历史结果回填",
            }
            changed = True

    if changed:
        db_write(
            "UPDATE sessions SET task_status=? WHERE id=?",
            (json.dumps(ts, ensure_ascii=False), session_id),
        )

    # 同步 session 总状态：仅在已结束状态下（done/failed）按任务结果重判
    cur_session = db_fetchone(
        "SELECT analysis_status FROM sessions WHERE id=?", (session_id,))
    cur_status = cur_session["analysis_status"] if cur_session else None
    if cur_status in ("done", "failed"):
        total = len(TASK_REGISTRY)
        done_cnt = sum(1 for tid in TASK_REGISTRY
                       if (ts.get(tid) or {}).get("status") == "done")
        expected = "done" if done_cnt == total else "failed"
        if expected != cur_status:
            db_write(
                "UPDATE sessions SET analysis_status=? WHERE id=?",
                (expected, session_id),
            )


def save_task_result(session_id, task_id, result):
    """把任务输出合并写入 analysis_result JSON 的对应字段。
    仅更新该任务声明的 result_keys，不动其它字段。"""
    row = db_fetchone("SELECT analysis_result FROM sessions WHERE id=?", (session_id,))
    try:
        cur = json.loads(row["analysis_result"]) if row and row["analysis_result"] else {}
    except (json.JSONDecodeError, TypeError):
        cur = {}

    task = TASK_REGISTRY[task_id]
    for key in task["result_keys"]:
        if key in result:
            cur[key] = result[key]

    if task_id == "T5" and "scoring" in cur:
        recalc_scoring(cur["scoring"])

    db_write(
        "UPDATE sessions SET analysis_result=? WHERE id=?",
        (json.dumps(cur, ensure_ascii=False), session_id),
    )


def get_missing_tasks(session_id):
    """返回未完成（status != 'done'）的任务 id 列表，按 TASK_REGISTRY 顺序。"""
    ts = get_task_status(session_id)
    return [tid for tid in TASK_REGISTRY.keys()
            if ts.get(tid, {}).get("status") != "done"]


# ============ 标签归一化词典 ============
_tag_dict_cache: dict = {}      # company_id -> {norm_key: (canonical_tag, status)}
_tag_dict_lock = threading.Lock()


def _norm_key(s):
    return (s or "").strip().lower()


def invalidate_tag_dict(company_id=None):
    """词典变更后清缓存。company_id=None 清全部。"""
    with _tag_dict_lock:
        if company_id is None:
            _tag_dict_cache.clear()
        else:
            _tag_dict_cache.pop(company_id, None)


def get_tag_dict_map(company_id):
    """返回 {归一化key: (canonical_tag, status)}，canonical 本名和每个同义词都映射到该 canonical。"""
    cid = company_id or 1
    with _tag_dict_lock:
        cached = _tag_dict_cache.get(cid)
    if cached is not None:
        return cached
    rows = db_fetchall(
        "SELECT canonical_tag, synonyms, status FROM tag_dictionary WHERE company_id=?",
        (cid,),
    )
    m = {}
    for r in rows:
        canon = r["canonical_tag"]
        status = r["status"] or "active"
        m[_norm_key(canon)] = (canon, status)
        try:
            for syn in (json.loads(r["synonyms"] or "[]") or []):
                k = _norm_key(syn)
                if k:
                    m[k] = (canon, status)
        except (json.JSONDecodeError, TypeError):
            pass
    with _tag_dict_lock:
        _tag_dict_cache[cid] = m
    return m


def normalize_tag(raw, company_id):
    """把原始标签归一。返回 (canonical_tag, status)：
    - 命中词典 → (标准词, 'active'/'blacklist')
    - 未命中 → (None, None)  表示待归类"""
    hit = get_tag_dict_map(company_id).get(_norm_key(raw))
    return hit if hit else (None, None)


# 7 大顾客标签分类（受控词表注入 / AI 归并都沿用这套）
TAG_CATEGORIES = ("客人新老", "顾客类型", "顾客画像", "顾客痛点",
                  "顾客状态", "需求类型", "竞品")


def build_tag_vocab_brief(company_id, per_cat=25, max_total=140):
    """构造【该公司现有标准词清单】，按 7 类分组，注入 Call1 的 customer_tags 任务，
    引导 AI 优先复用既有标准词、压住新词面爆炸。
    - 只取 active（黑名单/竞品自动登记的也算 active，但竞品类对画像意义不大，靠 per_cat 截断）；
    - 控制长度：每类最多 per_cat 个、总数最多 max_total，超出截断。
    返回拼好的多行文本；无标准词时返回空串（不注入，避免误导）。
    """
    rows = db_fetchall(
        "SELECT category, canonical_tag FROM tag_dictionary "
        "WHERE company_id=? AND status='active' ORDER BY category, canonical_tag",
        (company_id or 1,),
    )
    by_cat: dict = {}
    for r in rows:
        cat = (r["category"] or "其他")
        by_cat.setdefault(cat, []).append(r["canonical_tag"])
    if not by_cat:
        return ""
    lines, total = [], 0
    # 先按 7 类固定顺序输出，其余分类垫底
    ordered = [c for c in TAG_CATEGORIES if c in by_cat] + \
              [c for c in by_cat if c not in TAG_CATEGORIES]
    for cat in ordered:
        if total >= max_total:
            break
        words = by_cat[cat][:per_cat]
        if total + len(words) > max_total:
            words = words[:max_total - total]
        if not words:
            continue
        total += len(words)
        lines.append(f"  · {cat}：{ '、'.join(words) }")
    if not lines:
        return ""
    return (
        "\n【本公司现有标准标签词清单（请优先复用，能套上就用清单里的原词，"
        "实在没有合适的才造新词）】\n" + "\n".join(lines) + "\n"
    )


def save_customer_tags(session_id, customer_name, advisor_name, call1_result):
    """把本次分析生成的顾客标签写入 customer_tags 表（去重，先删本 session 来源的旧记录）"""
    if not customer_name:
        return
    ext = call1_result.get("external_signals", {}) or {}
    # tag -> count（同 session 内同名标签取最大值合并）
    tags: dict = {}
    def _add(tag, cnt):
        tag = (tag or "").strip()
        if not tag:
            return
        try:
            cnt = int(cnt)
        except Exception:
            cnt = 1
        if cnt < 1:
            cnt = 1
        if tag in tags:
            tags[tag] = max(tags[tag], cnt)
        else:
            tags[tag] = cnt

    # 1) 外层 customer_tags 数组（兼容字符串和 {tag,count} 对象）
    for item in call1_result.get("customer_tags") or []:
        if isinstance(item, str):
            _add(item, 1)
        elif isinstance(item, dict):
            _add(item.get("tag"), item.get("count", 1))

    # 2) lifestyle_habits / self_care 的 customer_tag
    for cat in ("lifestyle_habits", "self_care"):
        for item in ext.get(cat) or []:
            tag = (item.get("customer_tag") if isinstance(item, dict) else "") or ""
            _add(tag, 1)

    # 3) 三类竞品 → "XX用户" 标签
    competitor_raw = set()
    for cat in ("medical_aesthetics", "other_institutions", "external_brands"):
        for item in ext.get(cat) or []:
            brand = (item.get("item") if isinstance(item, dict) else "") or ""
            if brand:
                ctag = f"{brand.strip()}用户"
                _add(ctag, 1)
                competitor_raw.add(ctag)

    if not tags:
        return

    # 取来源 session 的公司 / 接诊日期 / 客户身份，用于归一化、时间筛选和精确归属
    srow = db_fetchone(
        "SELECT company_id, service_date, customer_id FROM sessions WHERE id=?",
        (session_id,),
    )
    company_id = (srow["company_id"] if srow else None) or 1
    service_date = srow["service_date"] if srow else None
    customer_id = srow["customer_id"] if srow else None  # 取不到则 NULL

    # 竞品标签自动登记进词典(category=竞品)，使竞品纵览可统计、可拉黑；已在词典(含黑名单)的不动
    dmap = get_tag_dict_map(company_id)
    new_comp = [ct for ct in competitor_raw if _norm_key(ct) not in dmap]
    if new_comp:
        def _reg_comp(conn):
            conn.executemany(
                "INSERT OR IGNORE INTO tag_dictionary (company_id, category, canonical_tag, synonyms, status) "
                "VALUES (?, '竞品', ?, '[]', 'active')",
                [(company_id, ct) for ct in new_comp],
            )
            conn.commit()
        db_batch(_reg_comp)
        invalidate_tag_dict(company_id)

    # 重跑时先清掉本 session 之前写入的标签，避免重复累计
    db_write(
        "DELETE FROM customer_tags WHERE source_session_id=?", (session_id,)
    )
    # 归一化：命中词典→canonical_tag；命中黑名单→跳过不入库；未命中→canonical_tag=NULL（待归类）
    rows = []
    for t, c in tags.items():
        canon, status = normalize_tag(t, company_id)
        if status == "blacklist":
            continue
        rows.append((customer_name, advisor_name, t, canon, session_id, c, service_date, customer_id))
    if not rows:
        return

    def _insert_tags(conn):
        conn.executemany(
            """INSERT INTO customer_tags
               (customer_name, advisor_name, tag, canonical_tag,
                source_session_id, mention_count, service_date, customer_id)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
            rows,
        )
        conn.commit()
    db_batch(_insert_tags)


_SESSION_RUN_LOCKS: dict = {}
_SESSION_RUN_LOCKS_GUARD = threading.Lock()


def _get_session_run_lock(session_id):
    """每个 session 一把可重入锁，序列化同一 session 的 run_session_analysis。
    避免 fill-missing 与单任务 rerun 同时触发，导致 Call 3 在 Call 2 完成前
    抢先做依赖检查而误判 failed。"""
    with _SESSION_RUN_LOCKS_GUARD:
        lock = _SESSION_RUN_LOCKS.get(session_id)
        if lock is None:
            lock = threading.Lock()
            _SESSION_RUN_LOCKS[session_id] = lock
        return lock


def run_session_analysis(session_id, signature, model=None, only_tasks=None,
                         enable_thinking=False, result_col="analysis_result",
                         task_status_col="task_status",
                         force_refresh_shared=False):
    """同 session 串行入口：等已有 run 完成再执行，避免 fill-missing 与
    单任务 rerun 并发时 Call 3 抢跑导致依赖误判 failed。"""
    import time as _t
    lock = _get_session_run_lock(session_id)
    wait_t = _t.time()
    lock.acquire()
    waited = _t.time() - wait_t
    if waited > 0.5:
        print(f"[run_session_analysis] session {session_id} 等待前一个 run "
              f"{waited:.1f}s 后开始（only_tasks={only_tasks}）")
    try:
        return _run_session_analysis_impl(
            session_id, signature, model=model, only_tasks=only_tasks,
            enable_thinking=enable_thinking, result_col=result_col,
            task_status_col=task_status_col,
            force_refresh_shared=force_refresh_shared)
    finally:
        lock.release()


def _run_session_analysis_impl(session_id, signature, model=None, only_tasks=None,
                         enable_thinking=False, result_col="analysis_result",
                         task_status_col="task_status",
                         force_refresh_shared=False):
    """整段接诊（多录音拼接）→ 三次 LLM tool_use 拆分 → 任务独立存盘。

    only_tasks: 指定只跑这些任务 id；None 表示全部。
    enable_thinking: 是否开启 DeepSeek V4 thinking 模式（默认关）。
    result_col: 结果写入的列名（保留为参数以便测试覆写，正式只用 analysis_result）。
    task_status_col: 任务状态写入的列名。
    """
    import time as _t
    import concurrent.futures
    t0 = _t.time()
    model = model or DEFAULT_MODEL
    if model not in MODEL_PROVIDER:
        db_write(
            """UPDATE sessions SET analysis_status='failed',
               analysis_error=?, analysis_finished_at=datetime('now','localtime')
               WHERE id=?""",
            (f"不支持的模型: {model}", session_id),
        )
        return

    sess = db_fetchone(
        "SELECT advisor, customer, service_date, company_id FROM sessions WHERE id=?", (session_id,)
    )
    if not sess:
        return
    company_id = (sess["company_id"] if sess else None) or 1
    recs = db_fetchall(
        """SELECT id, recorded_at, duration_label, asr_transcript
           FROM recordings WHERE session_id=? AND asr_status='done'
           ORDER BY COALESCE(recorded_at, ''), id""",
        (session_id,),
    )
    if not recs:
        db_write(
            """UPDATE sessions SET analysis_status='failed',
               analysis_error='没有可分析的转录文本',
               analysis_finished_at=datetime('now','localtime') WHERE id=?""",
            (session_id,),
        )
        return

    _set_progress(session_id, f"拼接 {len(recs)} 段转录中…")
    blocks = []
    for i, r in enumerate(recs, 1):
        header = f"### 录音段 {i}/{len(recs)}"
        if r["recorded_at"]:
            header += f"  录音时间 {r['recorded_at']}"
        if r["duration_label"]:
            header += f"  时长 {r['duration_label']}"
        blocks.append(f"{header}\n{r['asr_transcript']}")
    full_transcript = "\n\n".join(blocks)

    customer_name = sess["customer"] or "未知顾客"
    advisor_name = sess["advisor"] or "未知顾问"

    # 何时清空旧 _shared_*：
    #   a) signature 变化（录音改了，底稿不再适用）
    #   b) force_refresh_shared=True（顶部"⟳ 重跑"语义：整体重跑必须重读完整录音）
    # 补齐任务 / 单任务重跑保持 False，让 signature 没变时可以复用底稿省成本。
    sig_row = db_fetchone(
        f"SELECT analysis_signature, {result_col} FROM sessions WHERE id=?",
        (session_id,),
    )
    prev_sig = sig_row["analysis_signature"] if sig_row else None
    if force_refresh_shared or prev_sig != signature:
        try:
            prev_full = json.loads(sig_row[result_col]) if (sig_row and sig_row[result_col]) else {}
        except (json.JSONDecodeError, TypeError):
            prev_full = {}
        cleared = False
        for k in list(prev_full.keys()):
            if k.startswith("_shared_"):
                prev_full.pop(k, None)
                cleared = True
        if cleared:
            db_write(f"UPDATE sessions SET {result_col}=? WHERE id=?",
                     (json.dumps(prev_full, ensure_ascii=False), session_id))
            reason = ("force_refresh" if force_refresh_shared and prev_sig == signature
                     else "signature_change")
            print(f"[clear_shared:{reason}] session {session_id} 清空旧 _shared_* "
                  f"(prev_sig={prev_sig}, new_sig={signature}, "
                  f"force_refresh_shared={force_refresh_shared})")

    # 决定要跑哪些任务
    target_tasks = list(TASK_REGISTRY.keys()) if only_tasks is None else only_tasks

    # 按 call 分组
    by_call = {1: [], 2: [], 3: []}
    for tid in target_tasks:
        by_call[TASK_REGISTRY[tid]["call"]].append(tid)

    # ── 心跳：实时进度反馈 ────────────────────────────
    stop_heartbeat = threading.Event()
    stage_label = {"v": "调用 1+2（并行）"}

    def _heartbeat():
        while not stop_heartbeat.wait(10):
            elapsed = int(_t.time() - t0)
            mm, ss = divmod(elapsed, 60)
            _set_progress(
                session_id,
                f"{stage_label['v']} 深度思考中… 已等待 {mm}:{ss:02d}",
            )

    threading.Thread(target=_heartbeat, daemon=True).start()

    def build_subset_tool(call_no, schema_keys):
        """从聚合 schema 抽取指定字段组成子集工具"""
        full_tool = {1: TOOL_CALL1, 2: TOOL_CALL2, 3: TOOL_CALL3}[call_no]
        full_props = full_tool["input_schema"]["properties"]
        full_required = full_tool["input_schema"].get("required", [])
        sub_props = {k: full_props[k] for k in schema_keys if k in full_props}
        sub_required = [k for k in full_required if k in sub_props]
        return {
            "name": full_tool["name"],
            "description": full_tool["description"],
            "input_schema": {
                "type": "object",
                "required": sub_required,
                "properties": sub_props,
            },
        }

    def _ts_get():
        row = db_fetchone(f"SELECT {task_status_col} FROM sessions WHERE id=?", (session_id,))
        if not row or not row[task_status_col]:
            return {}
        try:
            return json.loads(row[task_status_col])
        except (json.JSONDecodeError, TypeError):
            return {}

    # 并行调用（call1+call2、call3 拆 T9 / T10+T11）时，多线程对同一 JSON 列
    # 做 read-modify-write 会丢更新；用 session 内锁串行化
    _save_lock = threading.Lock()

    def _ts_set(task_id, status, error=None):
        with _save_lock:
            ts = _ts_get()
            ts[task_id] = {
                "status": status,
                "updated_at": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
                "error": (error[:300] if error else None),
            }
            db_write(f"UPDATE sessions SET {task_status_col}=? WHERE id=?",
                     (json.dumps(ts, ensure_ascii=False), session_id))

    def _result_save(task_id, result):
        with _save_lock:
            row = db_fetchone(f"SELECT {result_col} FROM sessions WHERE id=?", (session_id,))
            try:
                cur = json.loads(row[result_col]) if row and row[result_col] else {}
            except (json.JSONDecodeError, TypeError):
                cur = {}
            for key in TASK_REGISTRY[task_id]["result_keys"]:
                if key in result:
                    cur[key] = result[key]
            db_write(f"UPDATE sessions SET {result_col}=? WHERE id=?",
                     (json.dumps(cur, ensure_ascii=False), session_id))

    def _save_shared_context(call_no, shared_obj):
        """把 shared_context 以 _shared_xxx 内部字段并入 result_col"""
        key = SHARED_CONTEXT_KEYS[call_no]
        with _save_lock:
            row = db_fetchone(f"SELECT {result_col} FROM sessions WHERE id=?", (session_id,))
            try:
                cur = json.loads(row[result_col]) if row and row[result_col] else {}
            except (json.JSONDecodeError, TypeError):
                cur = {}
            cur[key] = shared_obj
            db_write(f"UPDATE sessions SET {result_col}=? WHERE id=?",
                     (json.dumps(cur, ensure_ascii=False), session_id))

    def _load_shared_context(call_no):
        """读已存的 shared_context；不存在返回 None"""
        key = SHARED_CONTEXT_KEYS[call_no]
        row = db_fetchone(f"SELECT {result_col} FROM sessions WHERE id=?", (session_id,))
        try:
            cur = json.loads(row[result_col]) if row and row[result_col] else {}
        except (json.JSONDecodeError, TypeError):
            cur = {}
        v = cur.get(key)
        return v if v else None

    def _run_shared_preflight(call_no):
        """跑 Call1/2 的共用底稿；返回 shared dict；失败 raise。
        如果 result_col 里已存在则直接复用（fill-missing 时少跑一次）。
        """
        cached = _load_shared_context(call_no)
        if cached:
            print(f"[shared_call{call_no}] session={session_id} 复用已存底稿")
            return cached
        _t_pf0 = _t.time()

        if call_no == 1:
            system = SYSTEM_PROMPT_SHARED_CALL1
            tool = TOOL_SHARED_CALL1
            shared_key = "shared_customer_context"
            input_section = (
                f"录音段数：{len(recs)} 段\n\n### 接诊录音\n{full_transcript}"
            )
            budget = 8000
        else:
            kb_text = build_kb_brief()
            system = SYSTEM_PROMPT_SHARED_CALL2
            tool = TOOL_SHARED_CALL2
            shared_key = "shared_quality_context"
            input_section = (
                f"### 接诊录音\n{full_transcript}\n\n"
                f"### 判断知识库（参考用，仅用于判断本次命中什么，不要复述；"
                f"输出不带 L 编号；共 {len(load_kb())} 条）\n{kb_text}"
            )
            budget = 10000

        user_prompt = (
            f"顾客姓名：{customer_name}\n"
            f"顾问姓名：{advisor_name}\n"
            f"服务日期：{sess['service_date'] or '未知'}\n\n"
            f"{input_section}"
        )
        stage = f"shared_call{call_no}"
        print(f"[{stage}] session={session_id} 开始 preflight")
        result = _call_llm_with_retry(
            model, system, user_prompt, tool=tool,
            max_tokens=budget, enable_thinking=enable_thinking,
            stage_label=stage, max_attempts=3)
        shared = result.get(shared_key) if isinstance(result, dict) else None
        if not shared or not isinstance(shared, dict):
            raise RuntimeError(f"[{stage}] 未返回 {shared_key} 或为空")
        _save_shared_context(call_no, shared)
        print(f"[{stage}] session={session_id} preflight 完成 耗时{_t.time()-_t_pf0:.1f}s")
        return shared

    def _run_chunk(call_no, tids, chunk_input):
        """跑单个分块小调用：基于 shared_context / 摘要 产出正式字段；
        逐 task validate + save + ts_set。任何失败只影响本 chunk。"""
        if not tids:
            return

        system_prompt = {
            1: SYSTEM_PROMPT_CALL1,
            2: SYSTEM_PROMPT_CALL2,
            3: SYSTEM_PROMPT_CALL3,
        }[call_no]

        if call_no in (1, 2):
            ctx_label = ("共用顾客判断底稿" if call_no == 1
                         else "共用质检判断底稿")
            input_section = (
                f"### {ctx_label}（已基于完整录音整理；请直接基于底稿组装/扩写正式字段，"
                f"不要再假设有原始录音可读）\n"
                f"{json.dumps(chunk_input, ensure_ascii=False, indent=2)}"
            )
        else:
            c1_summary, c2_summary = chunk_input
            input_section = (
                f"### 顾客理解结果摘要\n{c1_summary}\n\n"
                f"### 接诊评判结果摘要\n{c2_summary}"
            )

        task_prompts = []
        all_schema_keys = []
        for tid in tids:
            t = TASK_REGISTRY[tid]
            task_prompts.append(t["prompt_snippet"].format(customer_name=customer_name))
            all_schema_keys.extend(t["schema_keys"])

        # 受控词表：T3（顾客标签）出现时，把本公司现有标准词清单注入，
        # 引导 AI 优先复用既有标准词，从源头压住新词面爆炸。
        vocab_brief = ""
        if call_no == 1 and "T3" in tids:
            try:
                vocab_brief = build_tag_vocab_brief(company_id)
            except Exception as _vb_err:
                print(f"[vocab_brief] session={session_id} 构造失败：{_vb_err}")
                vocab_brief = ""

        tool_name = {1: "submit_call1", 2: "submit_call2", 3: "submit_call3"}[call_no]
        user_prompt = (
            f"顾客姓名：{customer_name}\n"
            f"顾问姓名：{advisor_name}\n"
            f"服务日期：{sess['service_date'] or '未知'}\n\n"
            f"{input_section}\n\n"
            f"---\n请完成以下 {len(tids)} 个任务，调用 {tool_name} 提交：\n\n"
            + "\n\n".join(task_prompts)
            + vocab_brief
        )

        sub_tool = build_subset_tool(call_no, all_schema_keys)

        for tid in tids:
            _ts_set(tid, "running")

        stage = f"chunk_call{call_no}_{'+'.join(tids)}"
        _t_chunk0 = _t.time()
        print(f"[{stage}] session={session_id} 开始 tasks={tids}")
        try:
            result = _call_llm_with_retry(
                model, system_prompt, user_prompt,
                tool=sub_tool, max_tokens=8000,
                enable_thinking=enable_thinking,
                stage_label=stage, max_attempts=3)
        except Exception as e:
            elapsed = _t.time() - _t_chunk0
            print(f"[{stage}] session={session_id} 失败 耗时{elapsed:.1f}s error={e}")
            for tid in tids:
                _ts_set(tid, "failed", error=f"[{stage}] {e}"[:300])
            return

        elapsed_llm = _t.time() - _t_chunk0
        print(f"[{stage}] session={session_id} LLM返回 耗时{elapsed_llm:.1f}s")
        for tid in tids:
            t = TASK_REGISTRY[tid]
            sub_result = {k: result.get(k) for k in t["result_keys"] if k in result}
            ok, reason = validate_task_output(tid, sub_result)
            if ok:
                _result_save(tid, sub_result)
                _ts_set(tid, "done")
                print(f"[{stage}] session={session_id} {tid}({t['name']}) done 耗时{_t.time()-_t_chunk0:.1f}s")
            else:
                _ts_set(tid, "failed", error=reason)
                print(f"[{stage}] session={session_id} {tid}({t['name']}) failed reason={reason[:100]}")

        # T3 完成 → 写 customer_tags 累积表
        if "T3" in tids and result_col == "analysis_result":
            ts_now = _ts_get()
            if ts_now.get("T3", {}).get("status") == "done":
                try:
                    row = db_fetchone(
                        f"SELECT {result_col} FROM sessions WHERE id=?", (session_id,))
                    full = json.loads(row[result_col]) if row else {}
                    save_customer_tags(session_id, customer_name, advisor_name, full)
                except Exception as _tag_err:
                    print(f"[save_customer_tags] {session_id}: {_tag_err}")

        # ★兜底：多任务分块里，模型偶发只产出其中一个任务的字段(如 T7+T8 只回了 T7)，
        #   另一个被整段漏掉 → 校验报"结果为空/缺少字段"。这类"漏任务"单独重跑一次极易成功
        #   (单任务时模型注意力集中、不会漏)。只在 bundled(len>1) 时触发；重跑时 tids 长度=1
        #   不会再递归，避免死循环。把 T8 这类偶发空结果从"分析失败"里救回来。
        if len(tids) > 1:
            ts_now = _ts_get()
            dropped = [
                tid for tid in tids
                if ts_now.get(tid, {}).get("status") == "failed"
                and any(k in (ts_now.get(tid, {}).get("error") or "")
                        for k in ("结果为空", "缺少字段"))
            ]
            if dropped:
                print(f"[{stage}] session={session_id} 漏任务 {dropped} → 单独重跑一次")
                for tid in dropped:
                    _run_chunk(call_no, [tid], chunk_input)

    def run_call(call_no, target_tids):
        """编排单个 Call：Call1/2 先 shared_preflight 再分块并行；Call3 不做 preflight"""
        if not target_tids:
            return

        # Call 3 路径：依赖检查 + 用 c1+c2 摘要做 chunk_input
        if call_no == 3:
            row = db_fetchone(
                f"SELECT {result_col} FROM sessions WHERE id=?", (session_id,))
            try:
                prev = json.loads(row[result_col]) if row and row[result_col] else {}
            except (json.JSONDecodeError, TypeError):
                prev = {}
            dep_failed = set()
            for tid in list(target_tids):
                for dep in TASK_REGISTRY[tid]["depends_on"]:
                    for k in TASK_REGISTRY[dep]["result_keys"]:
                        if k not in prev or not prev[k]:
                            _ts_set(tid, "failed",
                                    error=f"依赖 {dep}（{k}）未完成")
                            dep_failed.add(tid)
                            break
            target_tids = [t for t in target_tids if t not in dep_failed]
            if not target_tids:
                return
            chunk_input = build_call_summaries(prev, prev)
        else:
            # Call 1/2：先跑 shared preflight
            try:
                shared = _run_shared_preflight(call_no)
            except Exception as e:
                import traceback
                tb_str = traceback.format_exc()
                try:
                    with open("/tmp/gp_shared_preflight_err.log", "a") as f:
                        f.write(f"\n=== session={session_id} call={call_no} "
                                f"at {datetime.now()} ===\n{tb_str}\n")
                except Exception:
                    pass
                err = f"shared_context 失败: {e}"
                for tid in target_tids:
                    _ts_set(tid, "failed", error=err[:300])
                return
            chunk_input = shared

        # 按 CALL_CHUNKS 取出与 target_tids 相交的分块；分块之间并行
        relevant_chunks = []
        for chunk in CALL_CHUNKS[call_no]:
            intersect = [t for t in chunk if t in target_tids]
            if intersect:
                relevant_chunks.append(intersect)
        if not relevant_chunks:
            return
        if len(relevant_chunks) == 1:
            _run_chunk(call_no, relevant_chunks[0], chunk_input)
        else:
            with concurrent.futures.ThreadPoolExecutor(
                    max_workers=len(relevant_chunks)) as ex:
                futs = [ex.submit(_run_chunk, call_no, ch, chunk_input)
                        for ch in relevant_chunks]
                for f in futs:
                    f.result()

    try:
        call1_tasks = by_call[1]
        call2_tasks = by_call[2]
        call3_tasks = by_call[3]

        # 第一波：Call 1 + Call 2 并行；每个内部先 shared preflight 再分块并行
        if call1_tasks or call2_tasks:
            _set_progress(session_id, "调用 1+2 并行启动（含共用底稿）…")
            with concurrent.futures.ThreadPoolExecutor(max_workers=2) as ex:
                futures = []
                if call1_tasks:
                    futures.append(ex.submit(run_call, 1, call1_tasks))
                if call2_tasks:
                    futures.append(ex.submit(run_call, 2, call2_tasks))
                for f in futures:
                    f.result()

        # 第二波：Call 3（不做 preflight；内部 T9/T10/T11 各自一个 chunk 并行）
        if call3_tasks:
            stage_label["v"] = "调用 3"
            _set_progress(session_id, "汇总前两次结果，分块生成总览与下一步…")
            run_call(3, call3_tasks)

        stop_heartbeat.set()

        # 重跑场景下，若旧结果仍在但本次该任务失败，回填为 done，避免显示不一致
        try:
            reconcile_task_status_from_result(session_id)
        except Exception as _rec_err:
            print(f"[reconcile] session={session_id}: {_rec_err}")

        # 汇总 session 总状态
        ts = _ts_get()
        all_task_ids = list(TASK_REGISTRY.keys())
        done_count = sum(1 for tid in all_task_ids
                         if ts.get(tid, {}).get("status") == "done")
        any_done = done_count > 0
        # 只有全部任务都成功才算 done；任一失败 → failed，让用户在列表里能一眼看到
        final_status = "done" if done_count == len(all_task_ids) else "failed"

        # 失败时汇总失败任务清单写入 analysis_error，便于顾问端展示具体原因
        err_msg = None
        if final_status == "failed":
            failed_tids = [tid for tid in all_task_ids
                           if ts.get(tid, {}).get("status") != "done"]
            err_parts = []
            for tid in failed_tids:
                st_info = ts.get(tid, {})
                e = (st_info.get("error") or st_info.get("status") or "missing")
                err_parts.append(f"{tid}: {e}")
            err_msg = (f"完成 {done_count}/{len(all_task_ids)} 任务，未完成 "
                       f"{len(failed_tids)} 项 — " + "; ".join(err_parts))[:1900]

        elapsed = int(_t.time() - t0)
        mm, ss = divmod(elapsed, 60)
        db_write(
            """UPDATE sessions SET analysis_status=?,
               analysis_signature=?, analysis_model=?,
               analysis_progress=?, analysis_error=?,
               analysis_finished_at=datetime('now','localtime') WHERE id=?""",
            (final_status, signature, model,
             f"完成 {done_count}/{len(all_task_ids)} 任务（耗时 {mm}:{ss:02d}）",
             err_msg, session_id),
        )

        # 重新计算 scoring 摘要（只在写主列时更新）
        if any_done and result_col == "analysis_result":
            row = db_fetchone(f"SELECT {result_col} FROM sessions WHERE id=?",
                              (session_id,))
            try:
                full = json.loads(row[result_col]) if row else {}
                scoring = full.get("scoring") or {}
                if scoring:
                    scoring = recalc_scoring(scoring)
                    scores_summary = {
                        "overall": scoring.get("overall"),
                        "stages": [
                            {"name": st.get("name"), "score": st.get("score")}
                            for st in (scoring.get("stages") or [])
                        ],
                        "good_highlights": scoring.get("good_highlights", []),
                        "bad_highlights": scoring.get("bad_highlights", []),
                    }
                    db_write(
                        "UPDATE sessions SET analysis_scores=? WHERE id=?",
                        (json.dumps(scores_summary, ensure_ascii=False), session_id),
                    )
            except (json.JSONDecodeError, TypeError):
                pass

        # 画像-5：接诊分析完成即给该客人重算价值预测（异步、不阻塞本次分析收尾）
        if final_status == "done" and result_col == "analysis_result":
            _trigger_value_after_analysis(session_id)

    except Exception as e:
        stop_heartbeat.set()
        db_write(
            """UPDATE sessions SET analysis_status='failed', analysis_error=?,
               analysis_progress=NULL,
               analysis_finished_at=datetime('now','localtime') WHERE id=?""",
            (f"[{model}] {str(e)[:1900]}", session_id),
        )
    finally:
        stop_heartbeat.set()


# ============ 录音入库（公共入口）============
def ingest_recording(oss_key, *, source, size_bytes=None,
                     advisor=None, customer=None, recorded_at=None,
                     service_date=None, duration_label=None,
                     company_id=1, uploader_user_id=None,
                     orphan=False, customer_id=None):
    """新增 recording + 关联 session + 启动自动流水线。返回 recording_id（如果已存在则返回原 id 且不重复入库）"""
    existing = db_fetchone("SELECT id FROM recordings WHERE oss_key=?", (oss_key,))
    if existing:
        return existing["id"]

    # 文件名补全元数据
    parsed = parse_filename(oss_key) or {}
    advisor = advisor or parsed.get("advisor")
    customer = customer or parsed.get("customer")
    recorded_at = recorded_at or parsed.get("recorded_at")
    service_date = service_date or parsed.get("service_date")
    duration_label = duration_label or parsed.get("duration_label")

    # 推导 service_date
    if not service_date and recorded_at:
        service_date = recorded_at[:10]

    # 关联 / 创建 session
    if orphan:
        session_id = None  # 顾问端浏览器录音：先不绑 session，等绑定客户
    elif advisor and customer and service_date:
        session_id = get_or_create_session(advisor, customer, service_date, company_id, customer_id=customer_id)
    else:
        session_id = get_or_create_orphan_session(advisor, customer, oss_key, company_id)

    if size_bytes is None:
        try:
            size_bytes = oss_bucket.head_object(oss_key).content_length
        except Exception:
            size_bytes = 0

    # 录音归店：有 session 继承 session 门店；否则用上传者门店；再退到按顾问解析
    rec_store_id = None
    if session_id:
        _srow = db_fetchone("SELECT store_id FROM sessions WHERE id=?", (session_id,))
        rec_store_id = _srow["store_id"] if _srow else None
    if not rec_store_id and uploader_user_id:
        _urow = db_fetchone("SELECT store_id FROM users WHERE id=?", (uploader_user_id,))
        rec_store_id = _urow["store_id"] if _urow else None
    if not rec_store_id:
        rec_store_id = store_for_advisor(advisor, company_id)

    rid = db_write(
        """INSERT INTO recordings
           (session_id, oss_key, advisor, customer, recorded_at,
            duration_label, size_bytes, source, company_id, uploader_user_id, store_id)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
        (session_id, oss_key, advisor, customer, recorded_at,
         duration_label, size_bytes, source, company_id or 1, uploader_user_id, rec_store_id),
    )

    # 新增录音会让 session 之前的分析结果过期；标 pending 等流水线
    if session_id:
        db_write(
            """UPDATE sessions SET analysis_status='pending',
               analysis_error=NULL WHERE id=?
               AND analysis_status='done' AND analysis_signature != ?""",
            (session_id, compute_session_signature(session_id)),
        )

    # 启动流水线
    # orphan 录音（顾问端自录、未绑客户）不立即跑 ASR，省钱：
    # 顾问需要先在「未入库」里听前 60s 决定归属哪个客户，绑定后才入库 + 跑 ASR。
    if orphan:
        # 标一个特殊状态，方便前端展示
        db_write(
            "UPDATE recordings SET asr_status='awaiting_intake' WHERE id=?",
            (rid,),
        )
    else:
        trigger_pipeline_for_recording(rid)
    return rid


def scan_oss_bucket():
    added = 0
    for obj in oss2.ObjectIterator(oss_bucket):
        key = obj.key
        if not any(key.lower().endswith(ext) for ext in AUDIO_EXTS):
            continue
        existing = db_fetchone("SELECT id FROM recordings WHERE oss_key=?", (key,))
        if existing:
            continue
        ingest_recording(key, source="oss-scan", size_bytes=obj.size)
        added += 1
    return added


# ============ 分析文本解析（纯正则，不调 LLM）============

def _strip_l_codes(s):
    """剥离所有 L 编号知识库引用：[L0001]、【L0001/L0008】、（违反L0001）、命中L0001/L0002 等"""
    if not s:
        return s
    # 中英方括号：[L0001] [L0001/L0002] 【L0001】【L0001/L0002】
    s = re.sub(r'[\[【]\s*L\d+(?:\s*[/／、]\s*L\d+)*\s*[\]】]', '', s)
    # 圆括号（中英）内含 L code：(违反L0002精神) （命中L0072/L0150）
    s = re.sub(r'[（(][^）)\n]*L\d+[^）)\n]*[）)]', '', s)
    # 破折号引出 L 码到句末：——L0108/L0274 是本次最大短板
    s = re.sub(r'[—–]{1,2}\s*L\d+(?:\s*[/／、]\s*L\d+)*[^。\n]*', '', s)
    # 关键词 + L 码 + 后续修饰到下一个标点：，命中L0072/L0150 这条逻辑
    s = re.sub(
        r'[，、,；;]?\s*(?:命中|违反|参考|对应|引用|遵循)\s*L\d+(?:\s*[/／、]\s*L\d+)*[^，。；！？\n]*',
        '', s)
    # 兜底：残留的裸 L 码（如 L0089）以及斜杠连号 L0087/L0081
    s = re.sub(r'(?<![A-Za-z0-9])L\d{3,4}(?:\s*[/／、]\s*L\d{3,4})*', '', s)
    # 收尾：清掉空的括号壳与多余空格
    s = re.sub(r'[（(]\s*[）)]|[\[【]\s*[\]】]', '', s)
    s = re.sub(r'[ \t]+', ' ', s)
    return s.strip()


def _clean(s):
    s = _strip_l_codes(s)
    s = re.sub(r'[ \t]+', ' ', s).strip()
    return s


def _extract_timestamps(s):
    """提取 '段N [XXXs-YYYs]' 等多种格式 → [{segment, startSec, endSec}]

    兼容：段1 [XXs-YYs]、段1的[XXs-YYs]、段1的 [XXs - YYs]、【录音段1的 0.64s-5.20s】、段1，XXs~YYs
    """
    pat = re.compile(
        r'段\s*(\d+)[^\d\n]{0,8}?'
        r'(\d+(?:\.\d+)?)\s*s\s*[-–~至到]\s*(\d+(?:\.\d+)?)\s*s'
    )
    return [
        {'segment': int(m.group(1)),
         'startSec': float(m.group(2)),
         'endSec': float(m.group(3))}
        for m in pat.finditer(s)
    ]


def parse_analysis(text):
    """把 AI 分析 Markdown 拆解为结构化 dict，纯字符串/正则处理，不调 LLM。"""
    result = {
        'overall': '',
        'strengths': [],
        'weaknesses': [],
        'stageCheck': {'before': [], 'during': [], 'after': []},
        'keySuggestions': [],
    }
    if not text:
        return result

    # 按 ## 分节
    parts = re.split(r'\n## ', '\n' + text)
    for part in parts:
        if not part.strip():
            continue
        nl = part.find('\n')
        if nl == -1:
            continue
        title = part[:nl].strip()
        body = part[nl + 1:].strip()

        # ── 总体评价 ──
        if '总体评价' in title:
            result['overall'] = _clean(body)

        # ── 做得好的点 ──
        elif '做得好' in title:
            for item in re.split(r'\n-\s+', '\n' + body):
                item = item.strip().lstrip('- ')
                if not item:
                    continue
                m = re.match(r'\*\*(.*?)\*\*[：:](.*)', item, re.DOTALL)
                if m:
                    raw_title = m.group(1)
                    raw_content = m.group(2).strip()
                    result['strengths'].append({
                        'title': _clean(raw_title),
                        'content': _clean(raw_content),
                        'timestamps': _extract_timestamps(raw_content),
                    })

        # ── 做错/遗漏 ──
        elif '做错' in title or '遗漏' in title:
            # 兼容两种格式：
            #   V3: `### 1. **[L...] 标题**\n - **位置**:...\n - **影响**:...\n - **正确做法**:...`
            #   V4: `- **[L...] 标题**：内容 **影响**：x **正确做法**：x`（全部内联）
            # 顶层入口标记 = `### N. ` 或 `- ` 后紧跟 `**[L或【L`（带 L 码的标题）
            entry_split = re.compile(
                r'(?:^|\n)(?:###\s*\d*\.?\s*|-\s+)(?=\*\*\s*[\[【]\s*L\d+)'
            )
            chunks = entry_split.split(body)

            def _extract_inline_field(field_pat, text):
                m = re.search(
                    rf'\*\*\s*(?:{field_pat})\s*\*\*\s*[：:]\s*'
                    r'(.*?)(?=\*\*\s*(?:影响|风险|正确做法|位置)\s*\*\*\s*[：:]|\Z)',
                    text, re.DOTALL,
                )
                return _clean(m.group(1)) if m else ''

            for chunk in chunks:
                chunk = chunk.strip()
                if not chunk.startswith('**'):
                    continue
                m = re.match(r'\*\*(.*?)\*\*\s*[：:]\s*(.*)', chunk, re.DOTALL)
                if not m:
                    continue
                raw_title = m.group(1)
                rest = m.group(2)
                # 第一个子字段之前的部分 → problem（V4 内联）
                first_field = re.search(
                    r'\*\*\s*(?:影响|风险|位置|正确做法)\s*\*\*\s*[：:]', rest,
                )
                inline_problem = rest[:first_field.start()] if first_field else rest
                risk = _extract_inline_field('影响|风险', rest)
                correct = _extract_inline_field('正确做法', rest)
                position = _extract_inline_field('位置', rest)
                # V3 格式有 **位置** 字段；V4 没有，把开头描述当作 problem
                problem = position or _clean(inline_problem)
                result['weaknesses'].append({
                    'title': _clean(raw_title),
                    'problem': problem,
                    'risk': risk,
                    'correctAction': correct,
                    'timestamps': _extract_timestamps(chunk),
                })

        # ── 阶段流程检查 ──
        elif '阶段流程' in title or '阶段' in title:
            key_map = {'进房前': 'before', '房中': 'during', '房后': 'after'}
            # 兼容两种格式：
            #   V3: `### 进房前\n - ✅ x\n - ❌ y\n - ⚠️ z`
            #   V4: `- **进房前**：xxx 叙述段落...\n- **房中**：...`
            stage_split = re.compile(
                r'(?:^|\n)(?:###\s*|-\s+\*\*\s*)(?=进房前|房中|房后)'
            )
            for stage_chunk in stage_split.split(body):
                if not stage_chunk.strip():
                    continue
                # 取首行作为阶段标题，余下作为内容
                first_nl = stage_chunk.find('\n')
                head = stage_chunk[:first_nl] if first_nl != -1 else stage_chunk
                stage_body = stage_chunk[first_nl + 1:] if first_nl != -1 else ''
                # V4 头部形如 `进房前**（...）：内容...`，截到第一个 ** 或 ：
                key = next((v for k, v in key_map.items() if k in head), None)
                if not key:
                    continue
                # V4 情况：head 后半段已经包含正文，把它合并回 stage_body
                # 兼容 `进房前**：xxx` 和 `进房前**（注解）：xxx` 两种
                tail_match = re.search(
                    r'\*\*\s*(?:[（(][^）)\n]*[）)])?\s*[：:]\s*(.*)',
                    head, re.DOTALL,
                )
                if tail_match:
                    stage_body = (tail_match.group(1) + '\n' + stage_body).strip()
                items = []
                # 先尝试按 ✅/❌/⚠️ 解析（V3）
                for line in stage_body.splitlines():
                    line = line.strip().lstrip('- ')
                    if not line:
                        continue
                    if line.startswith('✅'):
                        items.append({'type': 'good', 'text': _clean(line[1:])})
                    elif line.startswith('❌'):
                        items.append({'type': 'bad',  'text': _clean(line[1:])})
                    elif line.startswith('⚠️'):
                        items.append({'type': 'warn', 'text': _clean(line[2:])})
                # 没有标记 → V4 叙述段落，按句号切分为多条 'warn' 项
                if not items and stage_body.strip():
                    clean_text = _clean(stage_body.replace('\n', ' '))
                    for sent in re.split(r'(?<=[。！？])\s*', clean_text):
                        sent = sent.strip()
                        if len(sent) >= 4:
                            items.append({'type': 'warn', 'text': sent})
                result['stageCheck'][key] = items

        # ── 关键改进建议 ──
        elif '建议' in title:
            for item in re.split(r'\n\d+\.\s+', '\n' + body):
                item = item.strip()
                if not item:
                    continue
                m = re.match(r'\*\*(.*?)\*\*[：:](.*)', item, re.DOTALL)
                if m:
                    result['keySuggestions'].append({
                        'title': _clean(m.group(1)),
                        'body':  _clean(m.group(2)),
                        'timestamps': _extract_timestamps(m.group(2)),
                    })

    return result


# ============ 认证 ============
def login_required(f):
    @wraps(f)
    def wrapped(*args, **kwargs):
        if not session.get("logged_in"):
            if request.path.startswith("/api/"):
                return jsonify({"error": "未登录", "code": "auth_required"}), 401
            # 带上 SCRIPT_NAME（反向代理前缀，如 /gp），保证登录后能跳回本应用而非主域根
            next_url = (request.script_root or "") + request.full_path.rstrip("?")
            return redirect(url_for("login", next=next_url))
        return f(*args, **kwargs)

    return wrapped


def current_user():
    uid = session.get("user_id")
    if not uid:
        return None
    return db_fetchone(
        "SELECT id, username, role, company_id, advisor_name, employee_id, phone, store_id FROM users WHERE id=?",
        (uid,),
    )


def current_company_id():
    u = current_user()
    return u["company_id"] if u else None


def current_store_id():
    return session.get("store_id")


def store_scope_sql(col="s.store_id", allow_filter=True):
    """门店作用域。返回 (裸条件, params)，裸条件不带前导 AND（调用方按需拼接）。
    - store_manager：强制收口到本店；没绑门店则 '1=0'（看不到任何数据）。
    - admin / super：默认不限门店；若 allow_filter 且带 ?store_id= 则按所选门店筛选。
    - consultant：不按门店过滤（本人隔离另行处理）。"""
    role = session.get("role")
    if role == "store_manager":
        sid = session.get("store_id")
        return (f"{col} = ?", [sid]) if sid else ("1=0", [])
    if allow_filter and role in ("admin", "super"):
        req = (request.args.get("store_id") or "").strip()
        if req:
            try:
                return f"{col} = ?", [int(req)]
            except ValueError:
                pass
    return "", []


def current_store_filter():
    """返回需要按门店过滤的 store_id（int），None 表示不按门店过滤。
    - store_manager：本店（未绑门店返回 0，等于查不到任何数据）。
    - admin / super：带 ?store_id= 时按其筛选，否则 None。"""
    role = session.get("role")
    if role == "store_manager":
        return session.get("store_id") or 0
    if role in ("admin", "super"):
        req = (request.args.get("store_id") or "").strip()
        if req:
            try:
                return int(req)
            except ValueError:
                return None
    return None


def admin_required(f):
    @wraps(f)
    def wrapped(*args, **kwargs):
        if not session.get("logged_in"):
            return jsonify({"error": "未登录", "code": "auth_required"}), 401
        role = session.get("role")
        if role not in ("admin", "super"):
            return jsonify({"error": "需要管理员权限"}), 403
        return f(*args, **kwargs)
    return wrapped


def super_required(f):
    @wraps(f)
    def wrapped(*args, **kwargs):
        if session.get("role") != "super":
            return jsonify({"error": "需要超级管理员"}), 403
        return f(*args, **kwargs)
    return wrapped


def manager_required(f):
    """管理类只读视图（看板等）：admin / super / store_manager 都可进；
    store_manager 的数据由 store_scope_sql() 收口到本店。
    注意：建店、改员工、改配置等"写"操作仍用 admin_required（店长无权）。"""
    @wraps(f)
    def wrapped(*args, **kwargs):
        if not session.get("logged_in"):
            return jsonify({"error": "未登录", "code": "auth_required"}), 401
        if session.get("role") not in ("admin", "super", "store_manager"):
            return jsonify({"error": "需要管理权限"}), 403
        return f(*args, **kwargs)
    return wrapped


def _hash_pw(p):
    return hashlib.sha256((p or "").encode("utf-8")).hexdigest()


def _safe_next(target):
    """只允许跳转到本应用（同 SCRIPT_NAME 前缀下）的相对路径，防止开放重定向 / 跨服务跳走"""
    if not target:
        return None
    # 必须是站内绝对路径，不能是 //evil.com 或 http://...
    if not target.startswith("/") or target.startswith("//"):
        return None
    prefix = request.script_root or ""
    # 没有前缀部署时，任何站内路径都 OK；有前缀时必须落在前缀下
    if prefix and not (target == prefix or target.startswith(prefix + "/")):
        return None
    return target


@app.route("/login", methods=["GET", "POST"])
def login():
    error = None
    if request.method == "POST":
        u = (request.form.get("username") or "").strip()
        p = request.form.get("password") or ""
        row = db_fetchone(
            "SELECT id, username, password_hash, role, company_id, advisor_name, store_id FROM users WHERE username=?",
            (u,),
        )
        if row and row["password_hash"] == _hash_pw(p):
            session["logged_in"] = True
            session["user_id"] = row["id"]
            session["username"] = row["username"]
            session["role"] = row["role"]
            session["company_id"] = row["company_id"]
            session["store_id"] = row["store_id"]
            session["advisor_name"] = row["advisor_name"] or ""
            session.permanent = True
            # 顾问默认跳 consultant 页
            nxt = _safe_next(request.args.get("next"))
            if nxt:
                return redirect(nxt)
            if row["role"] in ("consultant", "store_manager"):
                return redirect(url_for("consultant_page"))
            return redirect(url_for("index"))
        error = "用户名或密码错误"
    return render_template("login.html", error=error)


@app.route("/logout")
def logout():
    session.clear()
    return redirect(url_for("login"))


# ============ App 下载（公开页面，无需登录）============
APK_PATH = Path(__file__).parent / "app-release.apk"
# v2 原生重写包（com.aibeautyfulwomen.gongpai.v2）独立下载链路，与 v1 同机并存、互不顶包。
V2_APK_PATH = Path(__file__).parent / "app-v2-release.apk"
APP_V2_VERSION_NAME = "2.0.21"
# v2 原生包版本检查（独立于 v1）：App 启动查 /api/app/v2/version 比对。
#   - 装的 versionCode < APP_V2_MIN_VERSION_CODE → 强制更新(不可关)；
#   - < APP_V2_LATEST_VERSION_CODE 但 ≥ MIN → 可关的「有新版」提示。
#   发新版时把 LATEST 抬到新 versionCode；要强更才动 MIN。
APP_V2_LATEST_VERSION_CODE = 22   # = build.gradle versionCode（2.0.19）
APP_V2_MIN_VERSION_CODE = 1       # 默认不强更；要强更时抬到 LATEST
APP_V2_UPDATE_NOTE = "建议更新到最新版，体验更顺、修复已知问题。"
# ★下载文件名必须带版本号（在 download_apk() 里由 APP_LATEST_VERSION_* 动态生成）：
#   每个版本同名("刁姐陪伴.apk")时，上次强更留在手机下载目录里的旧包会顶包——浏览器弹"该文件已下载"
#   或存成"(1)"副本，顾问点开装的还是旧版 → 版本仍 < MIN → 又弹强更，"点了立即更新还要更新"死循环。

# ============ App 版本 / 强制更新 ============
# ★发版时：和 android_app/app/build.gradle 的 versionCode/versionName 一起改这里。
#   App 启动/回前台会查 /api/app/version：装的 versionCode < APP_MIN_VERSION_CODE → 弹不可关的强制更新框。
#   - 强制升级：把 APP_MIN_VERSION_CODE 和 LATEST 一起抬到新版本号。
#   - 可选升级（不挡，仅提示）：只抬 LATEST，MIN 不动。
#   注意：强制更新逻辑是 versionCode≥3 的 App 才内置的；更早版本(1/2)没有这段检查，挡不住，需手动装一次新包。
# ★【发布版本】v9/2.0.8 强制全网升级(2026-06-08)：/download 给 v9、MIN=9 强制。
#   v9 = v8 + A1 下载补传加固：①待补传任务持久化(App被杀也不丢，重启续传)；
#   ②下载失败/找不到文件不再3分钟就放弃，改退避重试(8s→16s…)跨重连一直试；
#   ③下载卡死看门狗(>30s无进度取消重来)；④"上传中"加「重试」按钮；
#   ⑤补传2小时墙钟封顶才真放弃+删占位(音频在笔上、可日后重导)。真机验过①②③。
#   与 build.gradle(versionCode 12 / 2.1.1) 已对齐。
APP_LATEST_VERSION_CODE = 25
APP_LATEST_VERSION_NAME = "2.1.14"
# ★v25 灰度中：MIN 暂留 24（可选更新、不强制）→ 先让测试机/林春华手动装 v25 验证(治乱码#3/RSSI电量诊断/防丢音频#1/手机麦#5/待取回#4)，
#   验证通过后再把 MIN 抬到 25 全网强更。改 MIN=25 即全网强更。
APP_MIN_VERSION_CODE = 24
APP_UPDATE_NOTE = "本次更新：① 后段乱码根治——蓝牙传来的录音不再从某处起变噪音；② 登录失效也不会丢录音，重登后自动补传；③ 录音笔信号/电量可被诊断读取，排查更快；④ 手机录音被来电打断会提示并保留；⑤「小伙伴里没导入的录音」会主动提示取回。更新后更稳 💛"


@app.route("/download")
def download_page():
    """安卓 App 下载落地页：用户点链接 → 看安装说明 → 下载。无需登录。"""
    size_mb = None
    updated = None
    try:
        st = APK_PATH.stat()
        size_mb = round(st.st_size / 1024 / 1024, 1)
        updated = datetime.fromtimestamp(st.st_mtime).strftime("%Y-%m-%d")
    except OSError:
        pass
    return render_template(
        "download.html",
        size_mb=size_mb,
        updated=updated,
        available=APK_PATH.exists(),
        apk_version=APP_LATEST_VERSION_CODE,   # 链接带版本参数，防下载管理器按 URL 去重给旧文件
    )


@app.route("/download/app.apk")
def download_apk():
    """直接下载 APK 安装包。无需登录。max_age=0 保证替换安装包后用户拿到最新版。"""
    if not APK_PATH.exists():
        abort(404)
    resp = send_file(
        str(APK_PATH),
        mimetype="application/vnd.android.package-archive",
        max_age=0,
    )
    resp.headers["Cache-Control"] = "no-cache"
    # 手动拼 Content-Disposition：filename 给非空 ASCII 回退名，filename* 给中文名。
    # 不用 send_file 的 download_name —— 它会从中文名剥出 ASCII 回退，结果只剩 ".apk"。
    # ★文件名带版本号：防上次强更留下的同名旧包顶包（详见上方 APK 命名注释），发版自动跟随版本常量。
    fallback_name = f"app-release-v{APP_LATEST_VERSION_CODE}.apk"
    download_name = f"刁姐陪伴-{APP_LATEST_VERSION_NAME}.apk"
    resp.headers["Content-Disposition"] = (
        f"attachment; filename={fallback_name}; "
        f"filename*=UTF-8''{quote(download_name)}"
    )
    return resp


@app.route("/download/v2")
def download_page_v2():
    """v2 原生重写包「美丽陪伴」下载落地页，与 v1 独立、互不影响。无需登录。"""
    size_mb = updated = None
    try:
        st = V2_APK_PATH.stat()
        size_mb = round(st.st_size / 1024 / 1024, 1)
        updated = datetime.fromtimestamp(st.st_mtime).strftime("%Y-%m-%d")
    except OSError:
        pass
    return render_template(
        "download.html",
        size_mb=size_mb,
        updated=updated,
        available=V2_APK_PATH.exists(),
        app_label="美丽陪伴",
        logo_char="美",
        apk_href=url_for("download_apk_v2", v=APP_V2_VERSION_NAME),
        apk_version=APP_V2_VERSION_NAME,
    )


@app.route("/download/v2.apk")
def download_apk_v2():
    """v2 原生包直接下载（com.aibeautyfulwomen.gongpai.v2）。无需登录，与 v1 包并存不顶包。"""
    if not V2_APK_PATH.exists():
        abort(404)
    resp = send_file(
        str(V2_APK_PATH),
        mimetype="application/vnd.android.package-archive",
        max_age=0,
    )
    resp.headers["Cache-Control"] = "no-cache"
    fallback_name = f"meili-v2-{APP_V2_VERSION_NAME}.apk"
    download_name = f"美丽陪伴-{APP_V2_VERSION_NAME}.apk"
    resp.headers["Content-Disposition"] = (
        f"attachment; filename={fallback_name}; "
        f"filename*=UTF-8''{quote(download_name)}"
    )
    return resp


@app.route("/api/app/version")
def app_version():
    """App 启动/回前台查最新版本与强制更新阈值。无需登录。
    客户端拿 installedVersionCode 和 minVersionCode 比：低于就弹不可关的强制更新框。"""
    return jsonify({
        "latestVersionCode": APP_LATEST_VERSION_CODE,
        "latestVersionName": APP_LATEST_VERSION_NAME,
        "minVersionCode": APP_MIN_VERSION_CODE,
        # ★URL 带版本参数：部分浏览器/下载管理器按 URL 去重("该文件已下载"直接给旧文件)，变 URL 强制真下载
        "apkUrl": f"/download/app.apk?v={APP_LATEST_VERSION_CODE}",
        "pageUrl": "/download",
        "updateNote": APP_UPDATE_NOTE,
    })


@app.route("/api/app/v2/version")
def app_version_v2():
    """v2 原生包（MeiliActivity）独立的版本检查。无需登录。
    客户端拿 installedVersionCode 比：< minVersionCode → 强制更新；< latest → 可关提示。"""
    return jsonify({
        "latestVersionCode": APP_V2_LATEST_VERSION_CODE,
        "latestVersionName": APP_V2_VERSION_NAME,
        "minVersionCode": APP_V2_MIN_VERSION_CODE,
        "apkUrl": f"/download/v2.apk?v={APP_V2_LATEST_VERSION_CODE}",
        "pageUrl": "/download/v2",
        "updateNote": APP_V2_UPDATE_NOTE,
    })


# ============ 页面 ============
@app.route("/")
@login_required
def index():
    u = current_user()
    return render_template(
        "index.html",
        username=session.get("username"),
        role=session.get("role"),
        advisor_name=(u["advisor_name"] if u else "") or "",
    )


@app.route("/admin")
@manager_required
def admin_page():
    return render_template(
        "admin.html",
        username=session.get("username"),
        role=session.get("role"),
    )


@app.route("/customer/<int:customer_id>")
@login_required  # P1.7：档案"看"对所有登录角色开放（顾问也能看非本人客人档案）；公司作用域仍在数据接口内收口
def customer_profile_page(customer_id):
    return render_template(
        "customer_profile.html",
        customer_id=customer_id,
        username=session.get("username"),
        role=session.get("role"),
    )


@app.route("/consultant")
@login_required
def consultant_page():
    u = current_user()
    if not u:
        return redirect(url_for("logout"))
    resp = make_response(render_template(
        "consultant.html",
        username=u["username"],
        advisor_name=u["advisor_name"] or "",
        role=u["role"],
    ))
    # ★在线打开必取最新网页：否则改了 consultant.html，App 的 WebView(LOAD_DEFAULT)还显示缓存的旧版
    #   (本次"已传/已删还在显示"就是缓存旧页)。no-cache=每次在线先回源校验；仍允许离线冷启用缓存(LOAD_CACHE_ELSE_NETWORK)。
    resp.headers["Cache-Control"] = "no-cache, must-revalidate"
    return resp


@app.route("/session/<int:sid>")
@login_required
def session_detail(sid):
    sess = db_fetchone("SELECT * FROM sessions WHERE id=?", (sid,))
    if not sess:
        abort(404)
    _u = current_user()
    if _u and _u["role"] != "super" and sess["company_id"] is not None and sess["company_id"] != _u["company_id"]:
        abort(403)
    sess_d = dict(sess)
    recs = db_fetchall("""
        SELECT id, oss_key, recorded_at, duration_label, size_bytes, source,
               customer, asr_status, asr_transcript, asr_error,
               asr_started_at, asr_finished_at,
               asr_speaker_count, asr_speaker_warning, speaker_confirmed
        FROM recordings WHERE session_id=?
        ORDER BY COALESCE(recorded_at, ''), id
    """, (sid,))
    recordings = [dict(r) for r in recs]
    evals = [dict(e) for e in db_fetchall(
        "SELECT * FROM evaluations WHERE session_id=? ORDER BY id DESC", (sid,)
    )]
    try:
        report = json.loads(sess_d.get("analysis_result") or "null")
    except (json.JSONDecodeError, TypeError):
        report = None
    # 过滤内部字段（_shared_customer_context / _shared_quality_context 等），不暴露给前端
    if isinstance(report, dict):
        report = {k: v for k, v in report.items() if not k.startswith("_")}
    sess_d["display_status"] = _display_status(
        sess_d.get("analysis_status"), sess_d.get("analysis_progress"),
        bool(sess_d.get("analysis_result")),
    )

    # 顾客标签（本次/历史累积、按服务次数、时间区间）改由前端调
    # /api/session/<sid>/customer_tags 动态加载，这里不再服务端计算。

    resp = app.make_response(render_template(
        "report.html",
        sess=sess_d,
        recordings=recordings,
        report=report,
        evaluations=evals,
        username=session.get("username"),
        role=session.get("role"),
    ))
    resp.headers["Cache-Control"] = "no-store, no-cache, must-revalidate, max-age=0"
    resp.headers["Pragma"] = "no-cache"
    resp.headers["Expires"] = "0"
    return resp


@app.route("/api/session/<int:sid>/customer_tags")
@login_required
def api_session_customer_tags(sid):
    """接诊详情页「顾客标签」：本次标签(标记是否新增) + 历史累积(按服务次数, 支持时间区间)。
    口径：分组用 COALESCE(canonical_tag, tag) 归一；计数 = COUNT(DISTINCT source_session_id) 服务次数。"""
    sess = db_fetchone(
        "SELECT id, customer, customer_id, company_id, advisor FROM sessions WHERE id=?", (sid,))
    if not sess:
        return jsonify({"error": "not found"}), 404
    # 可见性：顾问仅本人；admin/店长 本公司；super 全部
    role = session.get("role")
    if role == "consultant" and (sess["advisor"] or "") != (session.get("advisor_name") or ""):
        return jsonify({"error": "无权查看"}), 403
    if (role in ("admin", "store_manager") and sess["company_id"]
            and session.get("company_id") and sess["company_id"] != session.get("company_id")):
        return jsonify({"error": "无权查看"}), 403

    customer = sess["customer"]
    cust_id = sess["customer_id"]
    # 同名串扰防护：本次有 customer_id 就按 id 聚合历史，否则回退按姓名。
    if cust_id:
        cust_pred = "customer_id=?"
        cust_val = cust_id
    else:
        cust_pred = "customer_name=?"
        cust_val = customer
    rng = (request.args.get("range") or "all").strip()
    days_map = {"30": 30, "90": 90, "180": 180, "365": 365}
    cutoff = None
    if rng in days_map:
        cutoff = (datetime.now() - timedelta(days=days_map[rng])).strftime("%Y-%m-%d")

    if not customer:
        return jsonify({"first_visit": True, "current_tags": [], "history": [], "range": rng})

    EFF = "COALESCE(NULLIF(canonical_tag,''), tag)"
    # 本次标签（去重）
    cur_rows = db_fetchall(
        f"SELECT DISTINCT {EFF} AS t FROM customer_tags WHERE source_session_id=?", (sid,))
    current = [r["t"] for r in cur_rows if r["t"]]
    # 历史出现过的标签集合（判断"本次新增"，不受时间区间限制）
    seen_rows = db_fetchall(
        f"SELECT DISTINCT {EFF} AS t FROM customer_tags "
        f"WHERE {cust_pred} AND source_session_id<>?", (cust_val, sid))
    seen = {r["t"] for r in seen_rows if r["t"]}
    current_tags = [{"tag": t, "is_new": t not in seen} for t in current]

    # 历史累积：按服务次数(去重 session)，可按 service_date 过滤区间
    where = f"{cust_pred} AND source_session_id<>?"
    params = [cust_val, sid]
    if cutoff:
        where += " AND service_date IS NOT NULL AND service_date >= ?"
        params.append(cutoff)
    hist_rows = db_fetchall(
        f"""SELECT {EFF} AS t, COUNT(DISTINCT source_session_id) AS cnt
            FROM customer_tags WHERE {where}
            GROUP BY {EFF} ORDER BY cnt DESC, MAX(created_at) DESC LIMIT 60""",
        tuple(params))
    history = [{"tag": r["t"], "count": r["cnt"]} for r in hist_rows if r["t"]]
    return jsonify({"first_visit": len(seen) == 0, "current_tags": current_tags,
                    "history": history, "range": rng})


# ============ API ============
# ============ 分析状态分桶：单一真源 ============
# 数据库 analysis_status 合法值（写入时应在此集合内，未识别值会在启动自检中打日志）
_VALID_DB_STATUSES = {"pending", "queued", "running", "done", "failed", "outdated", None, ""}
# 前端展示桶（pill / badge / 筛选 共用）
_DISPLAY_BUCKETS = ("running", "queued", "done", "failed", "stuck", "idle")


def _status_bucket(status, progress, has_result):
    """Python 端：把 (analysis_status, progress, 是否有结果) 映射到 6 个展示桶。"""
    if status in ("done", "failed", "running", "queued"):
        return status
    if status == "pending" and (progress or has_result):
        return "stuck"
    return "idle"


def _status_bucket_sql(prefix="s."):
    """SQL 端：与 _status_bucket 等价的 CASE 表达式，返回桶名字符串。"""
    p = prefix
    return (
        "CASE "
        f"WHEN {p}analysis_status='done'    THEN 'done' "
        f"WHEN {p}analysis_status='failed'  THEN 'failed' "
        f"WHEN {p}analysis_status='running' THEN 'running' "
        f"WHEN {p}analysis_status='queued'  THEN 'queued' "
        f"WHEN {p}analysis_status='pending' AND ("
        f"  ({p}analysis_progress IS NOT NULL AND {p}analysis_progress<>'')"
        f"  OR ({p}analysis_result IS NOT NULL AND {p}analysis_result<>'')"
        ") THEN 'stuck' "
        "ELSE 'idle' END"
    )


def _display_status(status, progress, has_result):
    """向后兼容旧调用点；委托 _status_bucket。"""
    return _status_bucket(status, progress, has_result)


@app.route("/api/sessions")
@login_required
def api_sessions():
    """接诊列表，支持按顾问/顾客模糊筛选 + 按服务日期精确筛选 + 分页。"""
    advisor = (request.args.get("advisor") or "").strip()
    customer = (request.args.get("customer") or "").strip()
    date = (request.args.get("date") or "").strip()  # YYYY-MM-DD，精确到天

    try:
        page = max(1, int(request.args.get("page", 1)))
    except ValueError:
        page = 1
    try:
        page_size = int(request.args.get("page_size", 10))
    except ValueError:
        page_size = 10
    page_size = max(1, min(page_size, 100))

    where = []
    params = []
    # 角色 / 公司隔离
    role = session.get("role")
    cid = session.get("company_id")
    if role == "consultant":
        where.append("s.advisor = ?")
        params.append(session.get("advisor_name") or "__none__")
        if cid:
            where.append("(s.company_id IS NULL OR s.company_id = ?)")
            params.append(cid)
    elif role in ("admin", "store_manager"):
        if cid:
            where.append("(s.company_id IS NULL OR s.company_id = ?)")
            params.append(cid)
    # super 不过滤公司
    # 店长强制本店；admin/super 可选 ?store_id= 按门店筛选
    _sc, _scp = store_scope_sql("s.store_id")
    if _sc:
        where.append(_sc)
        params.extend(_scp)
    if advisor:
        where.append("s.advisor LIKE ?")
        params.append(f"%{advisor}%")
    if customer:
        where.append("s.customer LIKE ?")
        params.append(f"%{customer}%")
    # 日期筛选也按 time_type 区分：recording → service_date / analysis → DATE(analysis_finished_at)
    _time_type_for_date = request.args.get("time_type", "recording")
    if date:
        if _time_type_for_date == "analysis":
            where.append("s.analysis_finished_at IS NOT NULL AND DATE(s.analysis_finished_at) = ?")
        else:
            where.append("s.service_date = ?")
        params.append(date)
    # 时间区间筛选：time_type=recording(默认) 按录音时间 / time_type=analysis 按最后分析时间
    # recorded_at 存在两种格式：'YYYY-MM-DD HH:MM:SS'(len=19) 和 'YYYYMMDDHHmmss'(len=14)
    # 录音时间筛选必须与列表显示的"录音时间"列(MIN(recorded_at)，即首条录音)一致，
    # 否则会出现"显示 13:42 却被 16:52-17:52 命中"的诡异结果。
    _FIRST_REC_TIME_SQL = (
        "(SELECT CASE WHEN length(MIN(r2.recorded_at))=14 "
        "THEN substr(MIN(r2.recorded_at),9,2)||':'||substr(MIN(r2.recorded_at),11,2) "
        "ELSE TIME(MIN(r2.recorded_at)) END "
        "FROM recordings r2 WHERE r2.session_id=s.id AND r2.recorded_at IS NOT NULL)"
    )
    time_from = (request.args.get("time_from") or "").strip()  # HH:MM
    time_to   = (request.args.get("time_to")   or "").strip()  # HH:MM
    time_type = request.args.get("time_type", "recording")     # recording | analysis
    _HM = r'^\d{2}:\d{2}$'
    if time_type == "analysis":
        if time_from and re.match(_HM, time_from):
            where.append("s.analysis_finished_at IS NOT NULL AND TIME(s.analysis_finished_at) >= ?")
            params.append(time_from)
        if time_to and re.match(_HM, time_to):
            where.append("s.analysis_finished_at IS NOT NULL AND TIME(s.analysis_finished_at) <= ?")
            params.append(time_to)
    else:
        if time_from and re.match(_HM, time_from):
            where.append(f"{_FIRST_REC_TIME_SQL} >= ?")
            params.append(time_from)
        if time_to and re.match(_HM, time_to):
            where.append(f"{_FIRST_REC_TIME_SQL} <= ?")
            params.append(time_to)
    # 状态筛选：?status=running,queued,done,failed,stuck,idle  （逗号分隔，多选）
    # 用 _status_bucket_sql() 作为单一真源，与 pill 计数 / 行 badge 完全一致
    status_filter = (request.args.get("status") or "").strip()
    if status_filter:
        wanted = [x.strip() for x in status_filter.split(",")
                  if x.strip() in _DISPLAY_BUCKETS]
        if wanted:
            placeholders = ",".join(["?"] * len(wanted))
            where.append(f"({_status_bucket_sql('s.')}) IN ({placeholders})")
            params.extend(wanted)
    where_sql = ("WHERE " + " AND ".join(where)) if where else ""

    total_row = db_fetchone(
        f"SELECT COUNT(*) AS c FROM sessions s {where_sql}", tuple(params)
    )
    total = total_row["c"] if total_row else 0

    offset = (page - 1) * page_size
    rows = db_fetchall(f"""
        SELECT s.id, s.advisor, s.customer, s.service_date,
               s.analysis_status, s.analysis_scores, s.analysis_progress,
               s.analysis_result, s.task_status, s.has_evaluation, s.created_at,
               s.analysis_started_at,
               s.analysis_finished_at,
               COUNT(r.id) AS recording_count,
               SUM(CASE WHEN r.asr_status='done' THEN 1 ELSE 0 END) AS asr_done_count,
               SUM(CASE WHEN r.asr_status='running' THEN 1 ELSE 0 END) AS asr_running_count,
               SUM(CASE WHEN r.asr_status='failed' THEN 1 ELSE 0 END) AS asr_failed_count,
               MIN(r.recorded_at) AS first_recorded_at,
               MAX(r.recorded_at) AS last_recorded_at,
               cc.member_card AS member_card
        FROM sessions s
        LEFT JOIN recordings r ON r.session_id = s.id
        LEFT JOIN company_customers cc ON cc.id = s.customer_id
        {where_sql}
        GROUP BY s.id
        ORDER BY s.service_date DESC, s.id DESC
        LIMIT ? OFFSET ?
    """, tuple(params) + (page_size, offset))
    out_rows = []
    for r in rows:
        d = dict(r)
        d["display_status"] = _display_status(
            d.get("analysis_status"), d.get("analysis_progress"),
            bool(d.get("analysis_result")),
        )
        # 解析 task_status，给出 part 进度（done/total + 正在跑哪些）
        ts_done = 0
        ts_total = 0
        ts_running: list[str] = []
        try:
            ts = json.loads(d.get("task_status") or "{}") or {}
            ts_total = len(ts)
            for tid, info in ts.items():
                st = (info or {}).get("status")
                if st == "done":
                    ts_done += 1
                elif st == "running":
                    ts_running.append(tid)
            ts_running.sort()
        except (json.JSONDecodeError, TypeError, AttributeError):
            pass
        d["task_done"] = ts_done
        d["task_total"] = ts_total
        d["task_running"] = ts_running
        # 列表不需要把完整内容回传
        d.pop("analysis_result", None)
        d.pop("task_status", None)
        out_rows.append(d)
    return jsonify({
        "sessions": out_rows,
        "total": total,
        "page": page,
        "page_size": page_size,
    })


@app.route("/api/sessions/status_counts")
@login_required
def api_sessions_status_counts():
    """给列表页 5 个状态 pill 用的计数。受角色/公司隔离约束，支持 advisor/customer/date 筛选。"""
    where = []
    params: list = []
    role = session.get("role")
    cid = session.get("company_id")
    if role == "consultant":
        where.append("s.advisor = ?")
        params.append(session.get("advisor_name") or "__none__")
        if cid:
            where.append("(s.company_id IS NULL OR s.company_id = ?)")
            params.append(cid)
    elif role in ("admin", "store_manager"):
        if cid:
            where.append("(s.company_id IS NULL OR s.company_id = ?)")
            params.append(cid)
    _sc0, _scp0 = store_scope_sql("s.store_id")
    if _sc0:
        where.append(_sc0); params.extend(_scp0)
    # 额外筛选参数（与列表页保持一致）
    advisor_q = request.args.get("advisor", "").strip()
    customer_q = request.args.get("customer", "").strip()
    date_q = request.args.get("date", "").strip()
    if advisor_q and role != "consultant":
        where.append("s.advisor LIKE ?")
        params.append(f"%{advisor_q}%")
    if customer_q:
        where.append("s.customer LIKE ?")
        params.append(f"%{customer_q}%")
    _time_type_for_date_q = request.args.get("time_type", "recording")
    if date_q:
        if _time_type_for_date_q == "analysis":
            where.append("s.analysis_finished_at IS NOT NULL AND DATE(s.analysis_finished_at) = ?")
        else:
            where.append("s.service_date = ?")
        params.append(date_q)
    time_from_q = request.args.get("time_from", "").strip()
    time_to_q   = request.args.get("time_to",   "").strip()
    time_type_q = request.args.get("time_type", "recording")
    # 与 /api/sessions 保持一致：按首条录音时间(MIN(recorded_at)) 过滤
    _FIRST_REC_TIME_SQL2 = (
        "(SELECT CASE WHEN length(MIN(r2.recorded_at))=14 "
        "THEN substr(MIN(r2.recorded_at),9,2)||':'||substr(MIN(r2.recorded_at),11,2) "
        "ELSE TIME(MIN(r2.recorded_at)) END "
        "FROM recordings r2 WHERE r2.session_id=s.id AND r2.recorded_at IS NOT NULL)"
    )
    _HM2 = r'^\d{2}:\d{2}$'
    if time_type_q == "analysis":
        if time_from_q and re.match(_HM2, time_from_q):
            where.append("s.analysis_finished_at IS NOT NULL AND TIME(s.analysis_finished_at) >= ?")
            params.append(time_from_q)
        if time_to_q and re.match(_HM2, time_to_q):
            where.append("s.analysis_finished_at IS NOT NULL AND TIME(s.analysis_finished_at) <= ?")
            params.append(time_to_q)
    else:
        if time_from_q and re.match(_HM2, time_from_q):
            where.append(f"{_FIRST_REC_TIME_SQL2} >= ?")
            params.append(time_from_q)
        if time_to_q and re.match(_HM2, time_to_q):
            where.append(f"{_FIRST_REC_TIME_SQL2} <= ?")
            params.append(time_to_q)
    where_sql = ("WHERE " + " AND ".join(where)) if where else ""
    bucket_expr = _status_bucket_sql("s.")
    rows = db_fetchall(f"""
        SELECT {bucket_expr} AS bucket, COUNT(*) AS c
        FROM sessions s {where_sql}
        GROUP BY bucket
    """, tuple(params))
    counts = {b: 0 for b in _DISPLAY_BUCKETS}
    for r in rows:
        counts[r["bucket"]] = r["c"]
    counts["all"] = sum(counts[b] for b in _DISPLAY_BUCKETS)
    # 自检：6 桶之和必须等于 total，不等则记录详情
    total_row = db_fetchone(
        f"SELECT COUNT(*) AS c FROM sessions s {where_sql}", tuple(params)
    )
    actual_total = total_row["c"] if total_row else 0
    if counts["all"] != actual_total:
        app.logger.warning(
            "status_counts mismatch: buckets_sum=%s real_total=%s buckets=%s",
            counts["all"], actual_total, {b: counts[b] for b in _DISPLAY_BUCKETS},
        )
        counts["all"] = actual_total
    return jsonify({"counts": counts})


@app.route("/api/session/<int:sid>")
@login_required
def api_session_get(sid):
    sess = db_fetchone("SELECT * FROM sessions WHERE id=?", (sid,))
    if not sess:
        return jsonify({"error": "not found"}), 404
    u = current_user()
    if u and u["role"] != "super" and sess["company_id"] is not None and sess["company_id"] != u["company_id"]:
        return jsonify({"error": "无权访问"}), 403
    out = dict(sess)
    recs = db_fetchall("""
        SELECT id, oss_key, recorded_at, duration_label, size_bytes, source,
               customer, asr_status, asr_transcript, asr_error,
               asr_started_at, asr_finished_at,
               asr_speaker_count, asr_speaker_warning, speaker_confirmed,
               advisor, uploader_user_id, store_id
        FROM recordings WHERE session_id=?
        ORDER BY COALESCE(recorded_at, ''), id
    """, (sid,))
    out["recordings"] = []
    for r in recs:
        d = dict(r)
        # P1.7：仅对有权收听者签发音频URL，其余置 None（转写/报告仍可见，符合"档案开放、录音限本人"）
        d["audio_url"] = oss_signed_url(r["oss_key"], expires=7200) if _can_listen_recording(u, r) else None
        out["recordings"].append(d)
    evs = db_fetchall(
        "SELECT * FROM evaluations WHERE session_id=? ORDER BY id DESC", (sid,)
    )
    out["evaluations"] = [dict(e) for e in evs]
    # 结构化报告 JSON
    try:
        out["report"] = json.loads(out.get("analysis_result") or "null")
    except (json.JSONDecodeError, TypeError):
        out["report"] = None
    if isinstance(out["report"], dict):
        out["report"] = {k: v for k, v in out["report"].items() if not k.startswith("_")}
    # 该接诊是否有待审批的删除申请（顾问端据此显示「删除审批中 / 可撤销」）
    out["delete_request_pending"] = bool(db_fetchone(
        "SELECT 1 FROM delete_requests WHERE session_id=? AND status='pending' LIMIT 1", (sid,)))
    out["display_status"] = _display_status(
        out.get("analysis_status"), out.get("analysis_progress"),
        bool(out.get("analysis_result")),
    )
    return jsonify(out)


@app.route("/api/scan", methods=["POST"])
@admin_required
def api_scan():
    added = scan_oss_bucket()
    return jsonify({"added": added})


@app.route("/api/upload", methods=["POST"])
@admin_required
def api_upload():
    """支持单文件或多文件批量上传。
    可选表单字段（用于文件名不规范时兜底）：advisor, customer, recorded_at, duration_label
    """
    files = request.files.getlist("files") or []
    if not files:
        # 兼容旧字段 name="file"
        single = request.files.get("file")
        if single:
            files = [single]
    if not files:
        return jsonify({"error": "没有文件"}), 400

    advisor = (request.form.get("advisor") or "").strip() or None
    customer = (request.form.get("customer") or "").strip() or None
    customer_id_raw = (request.form.get("customer_id") or "").strip()
    customer_id = None
    company_id = session.get("company_id") or 1
    if customer_id_raw:
        try:
            cid_int = int(customer_id_raw)
            cust_row = db_fetchone(
                "SELECT id, name FROM company_customers WHERE id=? AND (company_id=? OR ?=1)",
                (cid_int, company_id, 1 if session.get("role") == "super" else 0),
            )
            if not cust_row:
                return jsonify({"error": "顾客不存在"}), 404
            customer_id = cust_row["id"]
            customer = cust_row["name"]
        except ValueError:
            return jsonify({"error": "customer_id 非法"}), 400
    service_date_raw = (request.form.get("service_date") or "").strip()
    recorded_at_raw = (request.form.get("recorded_at") or "").strip()
    duration_label = (request.form.get("duration_label") or "").strip() or None
    duration_sec_list = request.form.getlist("duration_sec")

    service_date = None
    if service_date_raw:
        try:
            service_date = datetime.strptime(service_date_raw, "%Y-%m-%d").strftime("%Y-%m-%d")
        except ValueError:
            service_date = None

    recorded_at = None
    if recorded_at_raw:
        for fmt in ("%Y-%m-%dT%H:%M", "%Y-%m-%d %H:%M:%S", "%Y-%m-%d %H:%M"):
            try:
                recorded_at = datetime.strptime(recorded_at_raw, fmt).strftime("%Y-%m-%d %H:%M:%S")
                break
            except ValueError:
                continue
        if recorded_at is None:
            recorded_at = recorded_at_raw
    if not recorded_at and service_date:
        recorded_at = f"{service_date} 00:00:00"

    use_struct_name = bool(customer and advisor and service_date)

    created = []
    failed = []
    for idx, f in enumerate(files):
        if not f or not f.filename:
            continue
        orig_name = f.filename
        ext = orig_name.rsplit(".", 1)[-1].lower() if "." in orig_name else "mp3"
        ext = re.sub(r"[^A-Za-z0-9]", "", ext) or "mp3"

        per_dur_label = duration_label
        if idx < len(duration_sec_list) and duration_sec_list[idx]:
            lbl = _format_duration_label(duration_sec_list[idx])
            if lbl:
                per_dur_label = lbl

        try:
            if use_struct_name:
                c = _sanitize_name_for_oss(customer)
                a = _sanitize_name_for_oss(advisor)
                dur = per_dur_label or "未知时长"
                base = f"{c}_{a}_{service_date}_{dur}.{ext}"
                oss_key = f"upload/{service_date.replace('-','')}/{base}"
                if oss_bucket.object_exists(oss_key):
                    stem, _, ex = oss_key.rpartition(".")
                    oss_key = f"{stem}_{uuid.uuid4().hex[:4]}.{ex}"
            else:
                oss_key = orig_name
                if "/" in oss_key or "\\" in oss_key:
                    oss_key = oss_key.replace("/", "_").replace("\\", "_")
                if oss_bucket.object_exists(oss_key):
                    oss_key = f"upload/{datetime.now().strftime('%Y%m%d')}/{uuid.uuid4().hex[:6]}_{oss_key}"

            f.stream.seek(0)
            oss_bucket.put_object(oss_key, f.stream)

            rid = ingest_recording(
                oss_key,
                source="upload",
                advisor=advisor,
                customer=customer,
                recorded_at=recorded_at,
                service_date=service_date,
                duration_label=per_dur_label,
                company_id=company_id,
                customer_id=customer_id,
            )
            rec = db_fetchone("SELECT session_id FROM recordings WHERE id=?", (rid,))
            created.append({"id": rid, "oss_key": oss_key, "session_id": rec["session_id"]})
        except Exception as e:
            app.logger.exception("上传文件失败: %s", orig_name)
            failed.append({"filename": orig_name, "error": _friendly_oss_error(e)})

    if failed and not created:
        # 全部失败：返回 500 + 结构化错误，前端能直接读到原因
        return jsonify({"error": failed[0]["error"], "failed": failed}), 500
    return jsonify({"created": created, "failed": failed})


def _can_listen_recording(u, rec):
    """P1.7 录音收听统一闸：判断用户 u 是否有权收听录音 rec（rec 需含 advisor/uploader_user_id/store_id）。
    所有会返回签名播放URL/音频的端点都必须经此判定，避免再出现绕过的侧门。
      - admin/super：放行；store_manager：仅本店；consultant：仅本人 advisor 或 uploader；其它/未登录：拒绝。"""
    if not u:
        return False
    role = u["role"]
    if role in ("admin", "super"):
        return True
    if role == "store_manager":
        return bool(u["store_id"]) and rec["store_id"] == u["store_id"]
    if role == "consultant":
        is_advisor = bool(rec["advisor"]) and rec["advisor"] == u["advisor_name"]
        is_uploader = bool(rec["uploader_user_id"]) and rec["uploader_user_id"] == u["id"]
        return is_advisor or is_uploader
    return False


@app.route("/api/recording/<int:rid>/url")
@login_required
def api_recording_url(rid):
    """按需签名：返回新鲜的 OSS 播放 URL，1 小时有效。?download=1 时返回强制下载链接。
    P1.7 录音"听"硬闸（后端权限核心，即使前端显示播放按钮也必须拦住）：
      - admin/super：放行；
      - store_manager：仅本店录音（recording.store_id==本人 store_id）放行；
      - consultant：仅当 recording.advisor==本人 advisor_name 或 recording.uploader_user_id==本人 id 放行；
      - 否则 403 无权收听非本人接待的录音。"""
    rec = db_fetchone(
        "SELECT oss_key, advisor, uploader_user_id, store_id, company_id FROM recordings WHERE id=?",
        (rid,))
    if not rec or not rec["oss_key"]:
        return jsonify({"error": "not found"}), 404

    u = current_user()
    if not u:
        return jsonify({"error": "未登录", "code": "auth_required"}), 401
    if not _can_listen_recording(u, rec):
        return jsonify({"error": "无权收听非本人接待的录音"}), 403
    download_name = None
    if request.args.get("download"):
        base = rec["oss_key"].rsplit("/", 1)[-1]
        ext = base.rsplit(".", 1)[-1] if "." in base else "mp3"
        download_name = f"recording_{rid}.{ext}"
    try:
        url = oss_signed_url(rec["oss_key"], expires=3600, download_name=download_name)
    except Exception as e:
        return jsonify({"error": str(e)}), 500
    return jsonify({"url": url})


@app.route("/api/recording/<int:rid>/asr", methods=["POST"])
@login_required
def api_run_asr(rid):
    """老板手动重跑 ASR（兜底）"""
    rec = db_fetchone("SELECT asr_status FROM recordings WHERE id=?", (rid,))
    if not rec:
        return jsonify({"error": "not found"}), 404
    if rec["asr_status"] == "running":
        return jsonify({"status": "running"})
    trigger_pipeline_for_recording(rid)
    return jsonify({"status": "started"})


@app.route("/api/recording/<int:rid>/delete", methods=["DELETE"])
@admin_required
def api_recording_delete(rid):
    """管理员直接删除录音（OSS + DB）"""
    rec = db_fetchone("SELECT id, oss_key, session_id, company_id, uploader_user_id, pen_file, recorded_at FROM recordings WHERE id=?", (rid,))
    if not rec:
        return jsonify({"error": "录音不存在"}), 404
    if session.get("role") != "super" and rec["company_id"] != session.get("company_id"):
        return jsonify({"error": "无权操作"}), 403
    try:
        oss_bucket.delete_object(rec["oss_key"])
    except Exception as e:
        print(f"[delete_recording] OSS delete failed: {e}")
    _add_pen_tombstone(rec)
    db_write("DELETE FROM delete_requests WHERE recording_id=?", (rid,))
    db_write("DELETE FROM recordings WHERE id=?", (rid,))
    _clear_session_if_empty(rec["session_id"])  # 删空则连派生(点评/标签/价值预测)一起清
    return jsonify({"ok": True})


# ─── 免审批删除：<5分钟的录音 / 总时长<5分钟的接诊，顾问可直接删，不走管理员审批 ───
FREE_DELETE_MAX_SEC = 300  # 小于 5 分钟

def _duration_label_seconds(label):
    """'MM分SS秒' → 秒（MM 可 >59）；无法解析（'未知时长'/空/异格式）返回 None → 不享受免审批（安全侧：算不出就走审批）。"""
    if not label:
        return None
    m = re.match(r"^\s*(\d+)\s*分\s*(\d+)\s*秒", str(label))
    if not m:
        return None
    return int(m.group(1)) * 60 + int(m.group(2))

def _hard_delete_recording(rec):
    """彻底删一条录音：OSS + 墓碑(防"从录音笔同步"复活) + delete_requests + recordings。
    rec 需含 oss_key/id/uploader_user_id/pen_file/recorded_at。与管理员审批通过删除同一套路。"""
    try:
        oss_bucket.delete_object(rec["oss_key"])
    except Exception as e:
        print(f"[free_delete] OSS {rec['oss_key']}: {e}")
    _add_pen_tombstone(rec)
    db_write("DELETE FROM delete_requests WHERE recording_id=?", (rec["id"],))
    db_write("DELETE FROM recordings WHERE id=?", (rec["id"],))

def _purge_session_derived(sid):
    """删掉一次接诊【派生出来的所有下游数据】，让顾客档案不残留孤儿：
       ① 点评 evaluations  ② 顾客标签 customer_tags(source_session_id，直接从顾客档案抹掉)
       ③ 该顾客的价值预测缓存 customer_value_cache 失效(删缓存行→下次按删后的新数据重新生成)。
    必须在删 sessions 行【之前】调用(要先读 customer_id)。"""
    if not sid:
        return
    row = db_fetchone("SELECT customer_id, company_id FROM sessions WHERE id=?", (sid,))
    db_write("DELETE FROM evaluations WHERE session_id=?", (sid,))
    db_write("DELETE FROM customer_tags WHERE source_session_id=?", (sid,))
    if row and row["customer_id"]:
        db_write(
            "DELETE FROM customer_value_cache WHERE company_id=? AND customer_id=?",
            (row["company_id"], row["customer_id"]),
        )

def _clear_session_if_empty(sid):
    """录音删空后：先清派生(点评/标签/价值预测都是孤儿了) + 解锁 + 清空分析态
    （保留 session 行，便于再绑录音重新分析）。"""
    if not sid:
        return
    cnt = db_fetchone("SELECT COUNT(*) AS n FROM recordings WHERE session_id=?", (sid,))
    if not cnt or not cnt["n"]:
        _purge_session_derived(sid)
        db_write(
            """UPDATE sessions SET locked=0,
                   analysis_status=NULL, analysis_result=NULL, analysis_error=NULL,
                   analysis_started_at=NULL, analysis_finished_at=NULL,
                   analysis_signature=NULL, analysis_scores=NULL,
                   analysis_progress=NULL, task_status=NULL
               WHERE id=?""",
            (sid,),
        )


@app.route("/api/recording/<int:rid>/delete-request", methods=["POST"])
@login_required
def api_recording_delete_request(rid):
    """顾问申请删除录音；<5分钟的录音免审批直接删。"""
    rec = db_fetchone("SELECT id, session_id, company_id, oss_key, duration_label, uploader_user_id, pen_file, recorded_at FROM recordings WHERE id=?", (rid,))
    if not rec:
        return jsonify({"error": "录音不存在"}), 404
    role = session.get("role")
    if role in ("admin", "super"):
        return jsonify({"error": "管理员请直接删除"}), 400
    if rec["company_id"] != session.get("company_id"):
        return jsonify({"error": "无权操作"}), 403
    # 免审批：时长可解析且 <5 分钟 → 直接删
    secs = _duration_label_seconds(rec["duration_label"])
    if secs is not None and secs < FREE_DELETE_MAX_SEC:
        sid_of = rec["session_id"]
        _hard_delete_recording(rec)
        _clear_session_if_empty(sid_of)
        return jsonify({"ok": True, "deleted": True})
    existing = db_fetchone(
        "SELECT id FROM delete_requests WHERE recording_id=? AND status='pending'", (rid,)
    )
    if existing:
        return jsonify({"error": "已有待审批的删除申请"}), 400
    data = request.get_json(silent=True) or {}
    reason = (data.get("reason") or "").strip()
    user_id = session.get("user_id")
    advisor_name = session.get("advisor_name") or session.get("username")
    db_write(
        """INSERT INTO delete_requests
           (recording_id, session_id, requester_user_id, requester_name, reason)
           VALUES (?,?,?,?,?)""",
        (rid, rec["session_id"], user_id, advisor_name, reason),
    )
    return jsonify({"ok": True})


@app.route("/api/recording/<int:rid>/delete-request/withdraw", methods=["POST"])
@login_required
def api_recording_delete_request_withdraw(rid):
    """顾问撤回自己未审批的删除申请。"""
    user_id = session.get("user_id")
    dr = db_fetchone(
        "SELECT id, requester_user_id FROM delete_requests WHERE recording_id=? AND status='pending'",
        (rid,),
    )
    if not dr:
        return jsonify({"error": "没有待审批的删除申请"}), 404
    if dr["requester_user_id"] != user_id and session.get("role") not in ("admin", "super"):
        return jsonify({"error": "只能撤回自己的申请"}), 403
    db_write(
        """UPDATE delete_requests
           SET status='withdrawn', reviewed_at=datetime('now','localtime')
           WHERE id=?""",
        (dr["id"],),
    )
    return jsonify({"ok": True})


@app.route("/api/recording/<int:rid>/delete-request/dismiss", methods=["POST"])
@login_required
def api_recording_delete_request_dismiss(rid):
    """顾问关闭"删除被拒"提示。把该录音最新一条 rejected 申请标记 dismissed_at。"""
    user_id = session.get("user_id")
    dr = db_fetchone(
        """SELECT id FROM delete_requests
           WHERE recording_id=? AND status='rejected' AND dismissed_at IS NULL
           ORDER BY id DESC LIMIT 1""",
        (rid,),
    )
    if not dr:
        return jsonify({"ok": True})  # 没什么可关闭的
    db_write(
        "UPDATE delete_requests SET dismissed_at=datetime('now','localtime') WHERE id=?",
        (dr["id"],),
    )
    return jsonify({"ok": True})


@app.route("/api/session/<int:sid>/report/delete", methods=["DELETE"])
@admin_required
def api_session_report_delete(sid):
    """管理员清除分析报告（接诊记录和录音保留，便于重新分析）"""
    sess = db_fetchone("SELECT id, company_id FROM sessions WHERE id=?", (sid,))
    if not sess:
        return jsonify({"error": "接诊不存在"}), 404
    if session.get("role") != "super" and sess["company_id"] != session.get("company_id"):
        return jsonify({"error": "无权操作"}), 403
    # 清空报告的同时，把这次分析产生的派生物(点评/顾客标签/价值预测)也清掉——它们都是这份分析
    # 的产物，否则"清了分析、还没重新分析"的空窗期里顾客档案仍挂着旧标签/旧价值预测。重新分析会重写。
    _purge_session_derived(sid)
    db_write("""UPDATE sessions SET
        analysis_status='pending', analysis_result=NULL, analysis_error=NULL,
        analysis_started_at=NULL, analysis_finished_at=NULL, analysis_signature=NULL,
        analysis_scores=NULL, analysis_model=NULL, analysis_progress=NULL, task_status=NULL
        WHERE id=?""", (sid,))
    return jsonify({"ok": True})


@app.route("/api/session/<int:sid>/delete", methods=["DELETE"])
@admin_required
def api_session_delete(sid):
    """管理员完全删除接诊（录音 OSS + DB + 报告 + 接诊记录）"""
    sess = db_fetchone("SELECT id, company_id FROM sessions WHERE id=?", (sid,))
    if not sess:
        return jsonify({"error": "接诊不存在"}), 404
    if session.get("role") != "super" and sess["company_id"] != session.get("company_id"):
        return jsonify({"error": "无权操作"}), 403
    recs = db_fetchall("SELECT id, oss_key, uploader_user_id, pen_file, recorded_at FROM recordings WHERE session_id=?", (sid,))
    for r in recs:
        try:
            oss_bucket.delete_object(r["oss_key"])
        except Exception as e:
            print(f"[session_full_delete] OSS {r['oss_key']}: {e}")
        _add_pen_tombstone(r)
        db_write("DELETE FROM delete_requests WHERE recording_id=?", (r["id"],))
        db_write("DELETE FROM recordings WHERE id=?", (r["id"],))
    _purge_session_derived(sid)   # 点评 + 顾客标签 + 价值预测缓存一起清
    db_write("DELETE FROM sessions WHERE id=?", (sid,))
    return jsonify({"ok": True})


@app.route("/api/sessions/batch_delete", methods=["POST"])
@admin_required
def api_sessions_batch_delete():
    """批量完全删除接诊(录音OSS+DB+报告+记录)。Body: {ids:[sid,...]}。"""
    data = request.get_json(silent=True) or {}
    ids = data.get("ids") or []
    if not isinstance(ids, list) or not ids:
        return jsonify({"error": "ids 为空"}), 400
    try:
        ids = [int(x) for x in ids]
    except (ValueError, TypeError):
        return jsonify({"error": "ids 含非法值"}), 400
    is_super = session.get("role") == "super"
    cid = session.get("company_id")
    deleted, skipped = 0, []
    for sid in ids:
        sess = db_fetchone("SELECT id, company_id FROM sessions WHERE id=?", (sid,))
        if not sess:
            skipped.append(sid); continue
        if not is_super and sess["company_id"] != cid:
            skipped.append(sid); continue
        for r in db_fetchall("SELECT id, oss_key, uploader_user_id, pen_file, recorded_at FROM recordings WHERE session_id=?", (sid,)):
            try:
                oss_bucket.delete_object(r["oss_key"])
            except Exception as e:
                app.logger.warning("[batch_delete] OSS %s: %s", r["oss_key"], e)
            _add_pen_tombstone(r)
            db_write("DELETE FROM delete_requests WHERE recording_id=?", (r["id"],))
            db_write("DELETE FROM recordings WHERE id=?", (r["id"],))
        _purge_session_derived(sid)   # 点评 + 顾客标签 + 价值预测缓存一起清
        db_write("DELETE FROM sessions WHERE id=?", (sid,))
        deleted += 1
    return jsonify({"ok": True, "deleted": deleted, "skipped": skipped})


@app.route("/api/admin/recordings/batch_delete", methods=["POST"])
@admin_required
def api_admin_recordings_batch_delete():
    """批量删除录音(OSS+DB)。Body: {ids:[rid,...]}。用于未绑定录音批量删。"""
    data = request.get_json(silent=True) or {}
    ids = data.get("ids") or []
    if not isinstance(ids, list) or not ids:
        return jsonify({"error": "ids 为空"}), 400
    try:
        ids = [int(x) for x in ids]
    except (ValueError, TypeError):
        return jsonify({"error": "ids 含非法值"}), 400
    is_super = session.get("role") == "super"
    cid = session.get("company_id")
    deleted, skipped, affected_sids = 0, [], set()
    for rid in ids:
        rec = db_fetchone("SELECT id, oss_key, company_id, session_id, uploader_user_id, pen_file, recorded_at FROM recordings WHERE id=?", (rid,))
        if not rec:
            skipped.append(rid); continue
        if not is_super and rec["company_id"] != cid:
            skipped.append(rid); continue
        try:
            oss_bucket.delete_object(rec["oss_key"])
        except Exception as e:
            app.logger.warning("[batch_delete_rec] OSS %s: %s", rec["oss_key"], e)
        _add_pen_tombstone(rec)
        db_write("DELETE FROM delete_requests WHERE recording_id=?", (rid,))
        db_write("DELETE FROM recordings WHERE id=?", (rid,))
        if rec["session_id"]:
            affected_sids.add(rec["session_id"])
        deleted += 1
    for sid in affected_sids:
        _clear_session_if_empty(sid)  # 删空则连派生(点评/标签/价值预测)一起清
    return jsonify({"ok": True, "deleted": deleted, "skipped": skipped})


@app.route("/api/session/<int:sid>/recordings/delete", methods=["DELETE"])
@admin_required
def api_session_recordings_delete(sid):
    """管理员删除某 session 下所有录音（OSS + DB）"""
    recs = db_fetchall("SELECT id, oss_key, company_id, uploader_user_id, pen_file, recorded_at FROM recordings WHERE session_id=?", (sid,))
    if not recs:
        return jsonify({"error": "该接诊没有录音"}), 404
    if session.get("role") != "super":
        for r in recs:
            if r["company_id"] != session.get("company_id"):
                return jsonify({"error": "无权操作"}), 403
    for r in recs:
        try:
            oss_bucket.delete_object(r["oss_key"])
        except Exception as e:
            print(f"[session_del_rec] OSS {r['oss_key']}: {e}")
        _add_pen_tombstone(r)   # 防"从录音笔同步"把已删录音又拉回复活
        db_write("DELETE FROM delete_requests WHERE recording_id=?", (r["id"],))
        db_write("DELETE FROM recordings WHERE id=?", (r["id"],))
    _clear_session_if_empty(sid)  # 删空则连派生(点评/标签/价值预测)一起清
    return jsonify({"ok": True, "deleted": len(recs)})


@app.route("/api/session/<int:sid>/delete-request", methods=["POST"])
@login_required
def api_session_delete_request(sid):
    """顾问申请删除某 session 下所有录音；总时长<5分钟的接诊免审批直接删（连接诊记录一起删）。"""
    role = session.get("role")
    if role in ("admin", "super"):
        return jsonify({"error": "管理员请直接删除"}), 400
    recs = db_fetchall("SELECT id, session_id, company_id, oss_key, duration_label, uploader_user_id, pen_file, recorded_at FROM recordings WHERE session_id=?", (sid,))
    if not recs:
        return jsonify({"error": "该接诊没有录音"}), 404
    for r in recs:
        if r["company_id"] != session.get("company_id"):
            return jsonify({"error": "无权操作"}), 403
    # 免审批：所有录音时长都可解析且总时长 <5 分钟 → 直接彻底删除整次接诊（录音+评价+标签+接诊记录）
    secs_list = [_duration_label_seconds(r["duration_label"]) for r in recs]
    if all(s is not None for s in secs_list) and sum(secs_list) < FREE_DELETE_MAX_SEC:
        for r in recs:
            _hard_delete_recording(r)
        _purge_session_derived(sid)   # 点评 + 顾客标签 + 价值预测缓存一起清
        db_write("DELETE FROM sessions WHERE id=?", (sid,))
        return jsonify({"ok": True, "deleted": True})
    data = request.get_json(silent=True) or {}
    reason = (data.get("reason") or "").strip()
    user_id = session.get("user_id")
    advisor_name = session.get("advisor_name") or session.get("username")
    added = 0
    for r in recs:
        existing = db_fetchone(
            "SELECT id FROM delete_requests WHERE recording_id=? AND status='pending'", (r["id"],)
        )
        if not existing:
            db_write(
                """INSERT INTO delete_requests
                   (recording_id, session_id, requester_user_id, requester_name, reason)
                   VALUES (?,?,?,?,?)""",
                (r["id"], sid, user_id, advisor_name, reason),
            )
            added += 1
    if added == 0:
        return jsonify({"error": "该接诊所有录音已有待审批申请"}), 400
    return jsonify({"ok": True, "added": added})


@app.route("/api/session/<int:sid>/delete-request/withdraw", methods=["POST"])
@login_required
def api_session_delete_request_withdraw(sid):
    """顾问撤回本接诊未审批的删除申请（撤销该 session 下所有自己提交、仍 pending 的申请）。"""
    user_id = session.get("user_id")
    is_mgr = session.get("role") in ("admin", "super")
    drs = db_fetchall(
        "SELECT id, requester_user_id FROM delete_requests WHERE session_id=? AND status='pending'", (sid,)
    )
    if not drs:
        return jsonify({"error": "没有待审批的删除申请"}), 404
    withdrawn = 0
    for dr in drs:
        if dr["requester_user_id"] != user_id and not is_mgr:
            continue
        db_write(
            "UPDATE delete_requests SET status='withdrawn', reviewed_at=datetime('now','localtime') WHERE id=?",
            (dr["id"],),
        )
        withdrawn += 1
    if withdrawn == 0:
        return jsonify({"error": "只能撤回自己的申请"}), 403
    return jsonify({"ok": True, "withdrawn": withdrawn})


@app.route("/api/admin/delete-requests")
@admin_required
def api_admin_delete_requests():
    """管理员查看删除申请列表"""
    status = request.args.get("status", "pending")
    company_id = session.get("company_id")
    role = session.get("role")
    if role == "super":
        rows = db_fetchall(
            """SELECT dr.*, r.oss_key, r.recorded_at, r.advisor, r.customer, r.session_id as rec_session_id
               FROM delete_requests dr
               JOIN recordings r ON r.id = dr.recording_id
               WHERE dr.status=?
               ORDER BY dr.created_at DESC""",
            (status,),
        )
    else:
        rows = db_fetchall(
            """SELECT dr.*, r.oss_key, r.recorded_at, r.advisor, r.customer, r.session_id as rec_session_id
               FROM delete_requests dr
               JOIN recordings r ON r.id = dr.recording_id
               WHERE dr.status=? AND r.company_id=?
               ORDER BY dr.created_at DESC""",
            (status, company_id),
        )
    out = []
    for r in rows:
        d = dict(r)
        try:
            d["audio_url"] = oss_signed_url(d["oss_key"], expires=7200) if d.get("oss_key") else None
        except Exception:
            d["audio_url"] = None
        out.append(d)
    return jsonify({"requests": out})


def _approve_delete_request_row(dr, reviewer_user_id, reviewer_name):
    """审批通过单条删除申请：删录音(OSS) + 记墓碑(防同步复活) + 标 approved + 删空则清派生。
    dr 需含 id/recording_id/session_id/status。返回 (ok, err)。
    company 权限：单条端点已查 dr；批量端点 SELECT 已按 company 过滤；这里再对 rec 做一次防御。"""
    if dr["status"] != "pending":
        return False, "该申请已处理"
    rec = db_fetchone("SELECT id, oss_key, company_id, uploader_user_id, pen_file, recorded_at FROM recordings WHERE id=?", (dr["recording_id"],))
    if not rec:
        # 录音已不存在，直接更新申请状态
        db_write(
            "UPDATE delete_requests SET status='approved', reviewer_user_id=?, reviewer_name=?, reviewed_at=datetime('now','localtime') WHERE id=?",
            (reviewer_user_id, reviewer_name, dr["id"]),
        )
        return True, None
    if session.get("role") != "super" and rec["company_id"] != session.get("company_id"):
        return False, "无权操作"
    try:
        oss_bucket.delete_object(rec["oss_key"])
    except Exception as e:
        print(f"[approve_delete] OSS delete failed: {e}")
    affected_sid = dr["session_id"] if "session_id" in dr.keys() else None
    _add_pen_tombstone(rec)   # ★审批通过删除也要记墓碑，否则"从录音笔同步"会把已删录音当"未传"又拉回复活(其它删除路径都记了，唯独这里漏了)
    db_write("DELETE FROM recordings WHERE id=?", (rec["id"],))
    db_write(
        "UPDATE delete_requests SET status='approved', reviewer_user_id=?, reviewer_name=?, reviewed_at=datetime('now','localtime') WHERE id=?",
        (reviewer_user_id, reviewer_name, dr["id"]),
    )
    # 若所属 session 因此变空：解锁并清空分析状态 + 清派生(点评/标签/价值预测)（保留 session
    # + daily_reception，让顾问能继续给同一顾客绑新录音、重新分析）
    _clear_session_if_empty(affected_sid)
    return True, None


@app.route("/api/admin/delete-requests/<int:req_id>/approve", methods=["POST"])
@admin_required
def api_admin_delete_request_approve(req_id):
    """管理员审批通过：删除录音"""
    dr = db_fetchone("SELECT * FROM delete_requests WHERE id=?", (req_id,))
    if not dr:
        return jsonify({"error": "申请不存在"}), 404
    ok, err = _approve_delete_request_row(
        dr, session.get("user_id"), session.get("advisor_name") or session.get("username"))
    if not ok:
        return jsonify({"error": err}), (403 if err == "无权操作" else 400)
    return jsonify({"ok": True})


@app.route("/api/admin/delete-requests/approve-all", methods=["POST"])
@admin_required
def api_admin_delete_requests_approve_all():
    """一键批准【全部】待审批删除申请：逐条删录音+清派生。
    按管理员所属公司过滤(super 则全量)，与列表/徽章口径一致。"""
    role = session.get("role")
    company_id = session.get("company_id")
    if role == "super":
        rows = db_fetchall(
            "SELECT dr.* FROM delete_requests dr WHERE dr.status='pending' ORDER BY dr.id")
    else:
        rows = db_fetchall(
            """SELECT dr.* FROM delete_requests dr
               JOIN recordings r ON r.id = dr.recording_id
               WHERE dr.status='pending' AND r.company_id=? ORDER BY dr.id""",
            (company_id,))
    reviewer = session.get("advisor_name") or session.get("username")
    uid = session.get("user_id")
    approved, failed = 0, 0
    for dr in rows:
        ok, _err = _approve_delete_request_row(dr, uid, reviewer)
        if ok:
            approved += 1
        else:
            failed += 1
    return jsonify({"ok": True, "approved": approved, "failed": failed})


@app.route("/api/admin/delete-requests/<int:req_id>/reject", methods=["POST"])
@admin_required
def api_admin_delete_request_reject(req_id):
    """管理员拒绝删除申请"""
    dr = db_fetchone("SELECT * FROM delete_requests WHERE id=?", (req_id,))
    if not dr:
        return jsonify({"error": "申请不存在"}), 404
    if dr["status"] != "pending":
        return jsonify({"error": "该申请已处理"}), 400
    data = request.get_json(silent=True) or {}
    reject_reason = (data.get("reason") or "").strip()
    reviewer = session.get("advisor_name") or session.get("username")
    db_write(
        "UPDATE delete_requests SET status='rejected', reviewer_user_id=?, reviewer_name=?, reviewed_at=datetime('now','localtime'), reject_reason=? WHERE id=?",
        (session.get("user_id"), reviewer, reject_reason, req_id),
    )
    return jsonify({"ok": True})


@app.route("/api/models")
@login_required
def api_models():
    """前端下拉用：列出支持的分析模型及当前默认"""
    available = []
    for m in SUPPORTED_MODELS:
        provider = m["provider"]
        # DeepSeek 没 key 时禁用
        if provider == "deepseek":
            disabled = not DEEPSEEK_API_KEY
        # 豆包：API key 或该档接入点ID 未配置时禁用
        elif provider == "doubao":
            disabled = not (DOUBAO_API_KEY and DOUBAO_ENDPOINTS.get(m["id"]))
        else:
            disabled = False
        available.append({**m, "disabled": disabled})
    return jsonify({"models": available, "default": DEFAULT_MODEL})


@app.route("/api/session/<int:sid>/analyze", methods=["POST"])
@login_required
def api_session_analyze(sid):
    """老板手动重跑分析（可指定模型）。

    自动选择两种语义：
    - failed 且只缺部分任务（典型场景：服务重启打断 T11）→ 只补跑缺失/失败任务，
      复用已生成的 shared_context 和 T1-T10 结果，省钱。
    - 其它（outdated / 换绑后 / done 强制重跑 / 全部失败 等）→ 全量从 0 跑，
      并强制重建 shared_context。
    """
    sess = db_fetchone("SELECT id, analysis_status FROM sessions WHERE id=?", (sid,))
    if not sess:
        return jsonify({"error": "not found"}), 404

    data = request.get_json(silent=True) or {}
    model = (data.get("model") or "").strip() or None
    if model and model not in MODEL_PROVIDER:
        return jsonify({"error": f"不支持的模型: {model}"}), 400

    missing = get_missing_tasks(sid)
    only_failed_mode = (
        sess["analysis_status"] == "failed"
        and 0 < len(missing) < len(TASK_REGISTRY)
    )

    if only_failed_mode:
        targets = expand_to_call_chunk(missing)
        for tid in targets:
            set_task_status(sid, tid, "running")
        db_write(
            """UPDATE sessions SET analysis_status='queued',
               analysis_error=NULL,
               analysis_progress='排队中…（仅补跑失败任务）'
               WHERE id=?""",
            (sid,),
        )
        submit_analysis(
            sid, compute_session_signature(sid),
            model or DEFAULT_MODEL,
            only_tasks=targets,
        )
        cur = db_fetchone("SELECT analysis_status, analysis_progress FROM sessions WHERE id=?", (sid,))
        return jsonify({
            "status": cur["analysis_status"] if cur else "queued",
            "progress": cur["analysis_progress"] if cur else None,
            "model": model or DEFAULT_MODEL,
            "mode": "only_failed",
            "tasks": targets,
        })

    # 全量重跑（无视 signature，并刷新 shared_context）
    db_write(
        """UPDATE sessions SET analysis_status='pending',
           analysis_signature=NULL, analysis_error=NULL,
           analysis_progress='排队中…' WHERE id=?""",
        (sid,),
    )
    maybe_trigger_session_analysis(sid, model=model, force_refresh_shared=True)
    cur = db_fetchone("SELECT analysis_status, analysis_progress FROM sessions WHERE id=?", (sid,))
    return jsonify({
        "status": cur["analysis_status"] if cur else "pending",
        "progress": cur["analysis_progress"] if cur else None,
        "model": model or DEFAULT_MODEL,
        "mode": "full",
    })


@app.route("/api/sessions/batch_analyze", methods=["POST"])
@admin_required
def api_sessions_batch_analyze():
    """批量重跑分析。Body: {ids: [int, ...], model?: str}"""
    data = request.get_json(silent=True) or {}
    ids = data.get("ids") or []
    if not isinstance(ids, list) or not ids:
        return jsonify({"error": "ids 为空"}), 400
    try:
        ids = [int(x) for x in ids]
    except (ValueError, TypeError):
        return jsonify({"error": "ids 含非法值"}), 400
    model = (data.get("model") or "").strip() or None
    if model and model not in MODEL_PROVIDER:
        return jsonify({"error": f"不支持的模型: {model}"}), 400
    force_confirm = bool(data.get("force_confirm_speakers"))

    ok, skipped, failed = [], [], []
    forced_recs = 0
    for sid in ids:
        row = db_fetchone("SELECT id FROM sessions WHERE id=?", (sid,))
        if not row:
            skipped.append({"id": sid, "reason": "not_found"})
            continue
        try:
            if force_confirm:
                # 视 ASR 警告为误判，把本接诊下所有未确认的警告录音批量确认
                pre = db_fetchone(
                    """SELECT COUNT(*) AS n FROM recordings
                       WHERE session_id=? AND asr_speaker_warning=1
                         AND COALESCE(speaker_confirmed,0)=0""",
                    (sid,),
                )
                if pre and pre["n"] > 0:
                    db_write(
                        """UPDATE recordings SET speaker_confirmed=1
                           WHERE session_id=? AND asr_speaker_warning=1
                             AND COALESCE(speaker_confirmed,0)=0""",
                        (sid,),
                    )
                    forced_recs += pre["n"]
            db_write(
                """UPDATE sessions SET analysis_status='pending',
                   analysis_signature=NULL, analysis_error=NULL,
                   analysis_progress='排队中…' WHERE id=?""",
                (sid,),
            )
            maybe_trigger_session_analysis(sid, model=model, force_refresh_shared=True)
            # 触发后再读一次状态，确认是否真的进了队列
            after = db_fetchone(
                "SELECT analysis_status FROM sessions WHERE id=?", (sid,)
            )
            new_st = (after or {}).get("analysis_status") if hasattr(after, "get") else (after["analysis_status"] if after else None)
            if new_st in ("queued", "running", "done"):
                ok.append(sid)
            else:
                # 仍是 pending —— 说明被 trigger 前置条件挡住
                reason = "asr_not_done"
                warn = db_fetchone(
                    """SELECT COUNT(*) AS n FROM recordings
                       WHERE session_id=? AND asr_speaker_warning=1
                         AND COALESCE(speaker_confirmed,0)=0""",
                    (sid,),
                )
                if warn and warn["n"] > 0:
                    reason = "speaker_unconfirmed"
                else:
                    recs = db_fetchall(
                        "SELECT asr_status FROM recordings WHERE session_id=?", (sid,)
                    )
                    if not recs:
                        reason = "no_recording"
                    elif all(r["asr_status"] == "done" for r in recs):
                        reason = "unknown"
                skipped.append({"id": sid, "reason": reason})
                # 让它别留 "排队中…" 假象，回到 stuck 之前的状态（仍 pending 但清掉假提示）
                db_write(
                    "UPDATE sessions SET analysis_progress=? WHERE id=?",
                    (f"未触发：{reason}", sid),
                )
        except Exception as e:
            failed.append({"id": sid, "error": str(e)})
    return jsonify({"ok": ok, "skipped": skipped, "failed": failed,
                    "total": len(ids), "queued": len(ok),
                    "forced_confirmed_recordings": forced_recs})


@app.route("/api/session/<int:sid>/evaluate", methods=["POST"])
@login_required
def api_session_evaluate(sid):
    """老板点评：每次提交追加一条"""
    data = request.get_json(silent=True) or {}
    comment = (data.get("comment") or "").strip()
    if not comment:
        return jsonify({"error": "请填写点评内容"}), 400

    sess = db_fetchone("SELECT id FROM sessions WHERE id=?", (sid,))
    if not sess:
        return jsonify({"error": "not found"}), 404

    new_id = db_write(
        "INSERT INTO evaluations (session_id, comment) VALUES (?, ?)",
        (sid, comment),
    )
    db_write("UPDATE sessions SET has_evaluation=1 WHERE id=?", (sid,))
    row = db_fetchone("SELECT * FROM evaluations WHERE id=?", (new_id,))
    return jsonify({"ok": True, "evaluation": dict(row) if row else None})


@app.route("/api/session/<int:sid>/evaluations")
@login_required
def api_session_evaluations(sid):
    evs = db_fetchall(
        "SELECT * FROM evaluations WHERE session_id=? ORDER BY id DESC", (sid,)
    )
    return jsonify({"evaluations": [dict(e) for e in evs]})


@app.route("/api/evaluation/<int:eid>", methods=["DELETE"])
@login_required
def api_session_evaluation_delete(eid):
    row = db_fetchone("SELECT session_id FROM evaluations WHERE id=?", (eid,))
    if not row:
        return jsonify({"error": "not found"}), 404
    db_write("DELETE FROM evaluations WHERE id=?", (eid,))
    # 重算 has_evaluation
    left = db_fetchone(
        "SELECT COUNT(*) AS c FROM evaluations WHERE session_id=?", (row["session_id"],)
    )
    if left and left["c"] == 0:
        db_write("UPDATE sessions SET has_evaluation=0 WHERE id=?", (row["session_id"],))
    return jsonify({"ok": True})


@app.route("/api/session/<int:sid>/tasks")
@login_required
def api_session_tasks(sid):
    """查询所有任务状态"""
    # 读之前先按实际结果回填一次，防止显示旧的 failed
    try:
        reconcile_task_status_from_result(sid)
    except Exception:
        pass
    ts = get_task_status(sid)
    result = []
    for tid, meta in TASK_REGISTRY.items():
        s = ts.get(tid, {})
        result.append({
            "task_id": tid,
            "name": meta["name"],
            "call": meta["call"],
            "status": s.get("status", "missing"),
            "updated_at": s.get("updated_at"),
            "error": s.get("error"),
        })
    return jsonify({"tasks": result})


@app.route("/api/session/<int:sid>/task/<task_id>/rerun", methods=["POST"])
@login_required
def api_task_rerun(sid, task_id):
    """单独重跑一个任务"""
    if task_id not in TASK_REGISTRY:
        return jsonify({"error": f"未知任务 {task_id}"}), 400
    sess = db_fetchone("SELECT id FROM sessions WHERE id=?", (sid,))
    if not sess:
        return jsonify({"error": "session not found"}), 404

    data = request.get_json(silent=True) or {}
    model = (data.get("model") or "").strip() or DEFAULT_MODEL

    # 立即把同 chunk 的目标任务标 running，前端能立刻看到状态翻转。
    # 不在此处标的话，shared_preflight 阶段（耗时几十秒）期间 task_status
    # 仍是上一次的 failed，用户体验为"点了重跑没反应"。
    targets = expand_to_call_chunk([task_id])
    for tid in targets:
        set_task_status(sid, tid, "running")

    submit_analysis(
        sid, compute_session_signature(sid), model,
        only_tasks=targets,
    )

    return jsonify({"status": "started", "task_id": task_id})


@app.route("/api/session/<int:sid>/tasks/fill-missing", methods=["POST"])
@login_required
def api_tasks_fill_missing(sid):
    """补跑所有缺失/失败的任务"""
    sess = db_fetchone("SELECT id FROM sessions WHERE id=?", (sid,))
    if not sess:
        return jsonify({"error": "session not found"}), 404

    missing = get_missing_tasks(sid)
    if not missing:
        return jsonify({"status": "all_done", "missing": []})

    data = request.get_json(silent=True) or {}
    model = (data.get("model") or "").strip() or DEFAULT_MODEL

    targets = expand_to_call_chunk(missing)
    for tid in targets:
        set_task_status(sid, tid, "running")

    submit_analysis(
        sid, compute_session_signature(sid), model,
        only_tasks=targets,
    )

    return jsonify({"status": "started", "missing": missing})


@app.route("/api/recalc_all_scoring", methods=["POST"])
def api_recalc_all_scoring():
    """遍历所有 session，把 analysis_result.scoring 用 recalc_scoring 重算后写回。"""
    rows = db_fetchall(
        "SELECT id, analysis_result FROM sessions WHERE analysis_result IS NOT NULL"
    )
    updated, skipped, failed = 0, 0, 0
    details = []
    for r in rows:
        sid = r["id"]
        try:
            data = json.loads(r["analysis_result"]) if r["analysis_result"] else None
        except (json.JSONDecodeError, TypeError):
            failed += 1
            continue
        if not data or not isinstance(data, dict):
            skipped += 1
            continue
        scoring = data.get("scoring")
        if not scoring or not (scoring.get("stages") if isinstance(scoring, dict) else None):
            skipped += 1
            continue
        old_overall = scoring.get("overall")
        recalc_scoring(scoring)
        new_overall = scoring.get("overall")
        # 同步 overview.quality_score.score
        ov = data.get("overview")
        if isinstance(ov, dict) and isinstance(ov.get("quality_score"), dict) and new_overall is not None:
            ov["quality_score"]["score"] = new_overall
        analysis_scores_json = json.dumps({"overall": new_overall}, ensure_ascii=False)
        db_write(
            "UPDATE sessions SET analysis_result=?, analysis_scores=? WHERE id=?",
            (json.dumps(data, ensure_ascii=False), analysis_scores_json, sid),
        )
        updated += 1
        details.append({"id": sid, "old_overall": old_overall, "new_overall": new_overall})
    return jsonify({
        "updated": updated, "skipped": skipped, "failed": failed,
        "total": len(rows), "details": details,
    })


# ============ 当前用户 ============
@app.route("/api/me")
@login_required
def api_me():
    u = current_user()
    if not u:
        return jsonify({"error": "未登录"}), 401
    return jsonify({
        "id": u["id"], "username": u["username"], "role": u["role"],
        "company_id": u["company_id"], "advisor_name": u["advisor_name"],
        "employee_id": u["employee_id"], "phone": u["phone"],
        "store_id": u["store_id"],
    })


# ============ 管理员：顾问账号 ============
# ============ 管理员统计（轻量缓存，避免每次切 tab 都查库）============
_admin_stats_cache = {"data": None, "ts": 0.0}
_admin_stats_lock = threading.Lock()


def _invalidate_admin_stats():
    with _admin_stats_lock:
        _admin_stats_cache["data"] = None
        _admin_stats_cache["ts"] = 0.0


def _compute_admin_stats(cid, is_super, store_filter=None):
    # store_filter：店长本店 / admin 选店时，把顾问数、高风险数收口到该门店
    _stc = " AND store_id=?" if store_filter is not None else ""
    _stp = [store_filter] if store_filter is not None else []
    if is_super:
        c1 = db_fetchone(f"SELECT COUNT(*) AS n FROM users WHERE role='consultant'{_stc}", tuple(_stp))["n"]
        c2 = db_fetchone("SELECT COUNT(*) AS n FROM company_customers")["n"]
        c3 = db_fetchone("SELECT COUNT(*) AS n FROM delete_requests WHERE status='pending'")["n"]
    else:
        c1 = db_fetchone(
            f"SELECT COUNT(*) AS n FROM users WHERE role='consultant' AND company_id=?{_stc}",
            tuple([cid] + _stp),
        )["n"]
        c2 = db_fetchone(
            "SELECT COUNT(*) AS n FROM company_customers WHERE company_id=?",
            (cid,),
        )["n"]
        c3 = db_fetchone(
            """SELECT COUNT(*) AS n FROM delete_requests dr
               JOIN recordings r ON r.id = dr.recording_id
               WHERE dr.status='pending' AND r.company_id=?""",
            (cid,),
        )["n"]
    uid = session.get("user_id") or 0
    risk_where = (
        "json_extract(s.analysis_result, '$.deal_diagnosis.risk_alert') = 1 "
        "OR json_extract(s.analysis_result, '$.deal_diagnosis.risk_level') = 'high'"
    )
    _s4c = " AND s.store_id=?" if store_filter is not None else ""
    if is_super:
        c4_row = db_fetchone(
            f"""SELECT COUNT(*) AS n FROM sessions s
                LEFT JOIN high_risk_views v
                  ON v.session_id = s.id AND v.user_id = ?
                WHERE s.analysis_result IS NOT NULL
                  AND ({risk_where}){_s4c}
                  AND v.session_id IS NULL""",
            tuple([uid] + _stp),
        )
    else:
        c4_row = db_fetchone(
            f"""SELECT COUNT(*) AS n FROM sessions s
                LEFT JOIN high_risk_views v
                  ON v.session_id = s.id AND v.user_id = ?
                WHERE s.analysis_result IS NOT NULL
                  AND (s.company_id IS NULL OR s.company_id = ?)
                  AND ({risk_where}){_s4c}
                  AND v.session_id IS NULL""",
            tuple([uid, cid] + _stp),
        )
    c4 = c4_row["n"] if c4_row else 0
    return {"consultants": c1, "customers": c2,
            "delete_requests_pending": c3,
            "high_risk_unread": c4}


@app.route("/api/admin/high_risk_sessions")
@manager_required
def api_admin_high_risk_sessions():
    """差评高风险预警列表。管理员每日浏览：unread=true 表示当前管理员尚未点过"已查看"。"""
    cid = session.get("company_id")
    is_super = session.get("role") == "super"
    uid = session.get("user_id") or 0
    only_unread = (request.args.get("unread") or "").strip() == "1"
    level = (request.args.get("level") or "").strip().lower()

    where = ["s.analysis_result IS NOT NULL"]
    if level in ("high", "medium", "low"):
        where.append("json_extract(s.analysis_result, '$.deal_diagnosis.risk_level') = ?")
        params_extra = [level]
    else:
        where.append(
            "(json_extract(s.analysis_result, '$.deal_diagnosis.risk_alert') = 1 "
            "OR json_extract(s.analysis_result, '$.deal_diagnosis.risk_level') = 'high')"
        )
        params_extra = []
    params: list = [uid] + params_extra
    if not is_super and cid:
        where.append("(s.company_id IS NULL OR s.company_id = ?)")
        params.append(cid)
    _sc, _scp = store_scope_sql("s.store_id")
    if _sc:
        where.append(_sc); params.extend(_scp)
    if only_unread:
        where.append("v.session_id IS NULL")
    where_sql = "WHERE " + " AND ".join(where)

    rows = db_fetchall(
        f"""SELECT s.id, s.advisor, s.customer, s.service_date, s.created_at,
                   s.analysis_finished_at, s.analysis_result,
                   v.viewed_at AS viewed_at
            FROM sessions s
            LEFT JOIN high_risk_views v
              ON v.session_id = s.id AND v.user_id = ?
            {where_sql}
            ORDER BY s.analysis_finished_at DESC, s.id DESC
            LIMIT 500""",
        tuple(params),
    )
    out = []
    for r in rows:
        d = dict(r)
        try:
            ar = json.loads(d.pop("analysis_result") or "null") or {}
        except (json.JSONDecodeError, TypeError):
            ar = {}
        diag = (ar.get("deal_diagnosis") or {})
        d["risk_level"] = diag.get("risk_level")
        d["risk_text"] = diag.get("risk_text") or ""
        d["deal_result"] = bool(diag.get("deal_result"))
        d["deal_amount"] = diag.get("deal_amount") or ""
        d["unread"] = d.get("viewed_at") is None
        out.append(d)
    return jsonify({"sessions": out, "total": len(out)})


@app.route("/api/admin/high_risk_sessions/<int:sid>/view", methods=["POST"])
@manager_required
def api_admin_high_risk_mark_viewed(sid):
    uid = session.get("user_id")
    if not uid:
        return jsonify({"error": "no user"}), 400
    db_write(
        "INSERT OR IGNORE INTO high_risk_views (session_id, user_id) VALUES (?, ?)",
        (sid, uid),
    )
    _invalidate_admin_stats()
    return jsonify({"ok": True})


# ============ 顾问报告查看埋点 ============

def _log_view_event(session_id, source, part_key, event, duration_ms=0):
    u = current_user()
    if not u:
        return
    try:
        role = u["role"]
    except Exception:
        role = None
    try:
        cid = u["company_id"]
    except Exception:
        cid = None
    db_write(
        """INSERT INTO report_view_events
           (user_id, username, role, company_id, session_id, source, part_key, event, duration_ms)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
        (u["id"], u["username"], role, cid,
         session_id, source, part_key, event, int(duration_ms or 0)),
    )


@app.route("/api/report_view/enter", methods=["POST"])
@login_required
def api_report_view_enter():
    data = request.get_json(silent=True) or {}
    sid = data.get("session_id")
    source = (data.get("source") or "unknown")[:32]
    if not sid:
        return jsonify({"error": "missing session_id"}), 400
    _log_view_event(sid, source, "overall", "enter", 0)
    # 查看即递减：顾问点开报告 → 该 session 的个人 inapp 提醒 + 升级项(escalation)立刻关闭，
    # 店长/管理员看板上的升级项一并消失，顾问 badge 即时 -1。
    try:
        db_write(
            "UPDATE reminder_log SET processed=1, processed_at=datetime('now','localtime') "
            "WHERE ref_type='session' AND ref_id=? AND processed=0 "
            "AND kind='unviewed' AND channel IN ('inapp','escalation')",
            (sid,),
        )
    except Exception as _e:
        app.logger.warning("report_view_enter 关闭提醒失败: %s", _e)
    return jsonify({"ok": True})


@app.route("/api/report_view/part", methods=["POST"])
@login_required
def api_report_view_part():
    # 兼容 sendBeacon：body 可能是 text/plain JSON
    data = request.get_json(silent=True)
    if data is None:
        try:
            data = json.loads(request.get_data(as_text=True) or "{}")
        except Exception:
            data = {}
    sid = data.get("session_id")
    part_key = (data.get("part_key") or "")[:32]
    event = (data.get("event") or "")[:16]
    duration_ms = data.get("duration_ms") or 0
    if not sid or not part_key or event not in ("enter", "duration"):
        return jsonify({"error": "bad params"}), 400
    _log_view_event(sid, "detail", part_key, event, duration_ms)
    return jsonify({"ok": True})


# P0.5 可展开章节白名单：part2~part11(10) + extra_老板点评(1) = 11 个。
# 排除 overall（整体进入，非章节）和 part1（默认展开，不计为"展开"）。
# 既做"展开章节数"的分子(COUNT DISTINCT 命中本集合)，也做 n/11 的分母。
EXPANDABLE_PARTS = [
    "part2", "part3", "part4", "part5", "part6",
    "part7", "part8", "part9", "part10", "part11",
    "extra_老板点评",
]


@app.route("/api/admin/report_view_stats")
@manager_required
def api_admin_report_view_stats():
    # P1.3：看板由"按用户"改为"按客人(顾问×客人×报告session)分行"。
    #   每行 = 某查看者(顾问)对某个报告(session)的累计查看。
    #   一个报告被多人看则多行；展示以顾问视角。
    cid = session.get("company_id")
    is_super = session.get("role") == "super"
    days = int(request.args.get("days", "30"))
    since = (datetime.now() - timedelta(days=days)).strftime("%Y-%m-%d %H:%M:%S")

    # 事件表别名 e；门店过滤口径沿用现有（按查看者门店）。
    where = "e.created_at >= ?"
    params = [since]
    if not is_super:
        where += " AND e.company_id=?"
        params.append(cid)
    # 只统计顾问/admin 自己看（super 看的不计入对外汇总，但 super 视角下显示全部）
    where += " AND e.role IN ('consultant','admin','super','store_manager')"
    # 店长只看本店成员；admin/super 可选门店筛选（按查看者所属门店）
    _sf = current_store_filter()
    if _sf is not None:
        where += " AND e.user_id IN (SELECT id FROM users WHERE store_id=?)"
        params.append(_sf)

    # P0.5 去重展开章节：COUNT(DISTINCT part_key) 且 part_key 命中白名单。
    #   用占位符把 EXPANDABLE_PARTS 注入 IN(...)，反复展开同一章只算 1。
    in_ph = ",".join(["?"] * len(EXPANDABLE_PARTS))
    expand_expr = (
        f"COUNT(DISTINCT CASE WHEN e.event='enter' "
        f"AND e.part_key IN ({in_ph}) THEN e.part_key END)"
    )
    total_expandable = len(EXPANDABLE_PARTS)

    # 主查询：JOIN sessions 取客人/顾问/服务日期；GROUP BY 查看者×报告。
    # 参数顺序：expand_expr 的 IN 列表在 SELECT 中先出现，故先拼进 params。
    sql = f"""
        SELECT
            e.user_id                                AS user_id,
            e.username                               AS username,
            e.role                                   AS role,
            e.session_id                             AS session_id,
            s.customer                               AS customer,
            s.advisor                                AS advisor,
            s.service_date                           AS service_date,
            MAX(e.created_at)                        AS last_view,
            {expand_expr}                            AS expanded_chapters,
            SUM(CASE WHEN e.event='enter' AND e.part_key='overall' THEN 1 ELSE 0 END) AS enter_count,
            SUM(CASE WHEN e.event='duration' THEN e.duration_ms ELSE 0 END)           AS total_ms
        FROM report_view_events e
        JOIN sessions s ON e.session_id = s.id
        WHERE {where}
        GROUP BY e.user_id, e.session_id
        ORDER BY last_view DESC
    """
    rows = db_fetchall(sql, tuple(EXPANDABLE_PARTS) + tuple(params))

    out = []
    for r in rows:
        total_ms = int(r["total_ms"] or 0)
        expanded = int(r["expanded_chapters"] or 0)
        out.append({
            # —— P1.3 新维度字段（按客人）——
            "customer": r["customer"],
            "advisor": r["advisor"],
            "service_date": r["service_date"],
            "last_view": r["last_view"],
            "expanded_chapters": expanded,        # 去重命中白名单的章节数
            "total_expandable": total_expandable,  # 固定 11，供前端显示 n/11
            "total_minutes": round(total_ms / 60000, 1),
            # 查看者（顾问）信息，便于前端展示/区分多人查看
            "viewer": r["username"],
            "user_id": r["user_id"],
            "session_id": r["session_id"],
            "role": r["role"],
            # —— 向后兼容旧字段（旧前端仍可读取，不报错）——
            "username": r["username"],
            "sessions_viewed": 1,                 # 每行即一份报告
            "enter_count": int(r["enter_count"] or 0),
            "part_expand_count": expanded,         # 旧名→新去重值
            "favorite_part": None,
        })
    return jsonify({
        "days": days,
        "rows": out,
        "total_expandable": total_expandable,
        "expandable_parts": EXPANDABLE_PARTS,
    })


@app.route("/api/admin/ops_dashboard")
@manager_required
def api_admin_ops_dashboard():
    cid = session.get("company_id")
    is_super = session.get("role") == "super"
    _sf = current_store_filter()  # 店长=本店；admin/super 可选 ?store_id=

    today = datetime.now().strftime("%Y-%m-%d")
    week_start = (datetime.now() - timedelta(days=datetime.now().weekday())).strftime("%Y-%m-%d")

    def _session_stats(where_extra, params_today, params_week, params_fail):
        def _counts(date_cond, p):
            rows = db_fetchall(f"""
                SELECT
                    analysis_status,
                    COUNT(*) AS n,
                    AVG(CASE
                        WHEN analysis_finished_at IS NOT NULL AND analysis_started_at IS NOT NULL
                        THEN (julianday(analysis_finished_at) - julianday(analysis_started_at)) * 1440
                        ELSE NULL END) AS avg_min
                FROM sessions
                WHERE {date_cond} {where_extra}
                GROUP BY analysis_status
            """, p)
            out = {"total": 0, "done": 0, "failed": 0, "pending": 0, "avg_min": None}
            for r in rows:
                st = r["analysis_status"] or "pending"
                cnt = r["n"]
                out["total"] += cnt
                if st == "done":
                    out["done"] += cnt
                    if r["avg_min"] is not None:
                        out["avg_min"] = round(r["avg_min"], 1)
                elif st == "failed":
                    out["failed"] += cnt
                else:
                    out["pending"] += cnt
            out["rate"] = round(out["done"] / out["total"] * 100) if out["total"] else 0
            return out

        today_stats = _counts("REPLACE(service_date,'-','') >= REPLACE(?,'-','')", params_today)
        week_stats  = _counts("REPLACE(service_date,'-','') >= REPLACE(?,'-','')", params_week)

        failures = db_fetchall(f"""
            SELECT id, advisor, customer, service_date, analysis_error
            FROM sessions
            WHERE analysis_status='failed' {where_extra}
            ORDER BY COALESCE(analysis_finished_at, analysis_started_at) DESC
            LIMIT 15
        """, params_fail)

        return today_stats, week_stats, [dict(r) for r in failures]

    if is_super:
        companies = db_fetchall("SELECT id, name FROM companies ORDER BY id")
        result = []
        for co in companies:
            co_id = co["id"]
            today_s, week_s, fails = _session_stats(
                "AND company_id=?",
                (today, co_id), (week_start, co_id), (co_id,)
            )
            advisors_rows = db_fetchall("""
                SELECT
                    advisor,
                    COUNT(*) AS total,
                    SUM(analysis_status='done') AS done,
                    SUM(analysis_status='failed') AS failed,
                    AVG(CASE
                        WHEN analysis_finished_at IS NOT NULL AND analysis_started_at IS NOT NULL
                        THEN (julianday(analysis_finished_at) - julianday(analysis_started_at)) * 1440
                        ELSE NULL END) AS avg_min
                FROM sessions
                WHERE company_id=?
                  AND REPLACE(service_date,'-','') >= REPLACE(?,'-','')
                GROUP BY advisor
                ORDER BY total DESC
            """, (co_id, week_start))
            advisors = []
            for a in advisors_rows:
                total = a["total"] or 0
                done = a["done"] or 0
                failed = a["failed"] or 0
                advisors.append({
                    "name": a["advisor"],
                    "week_total": total,
                    "week_done": done,
                    "week_failed": failed,
                    "week_pending": total - done - failed,
                    "week_rate": round(done / total * 100) if total else 0,
                    "avg_min": round(a["avg_min"], 1) if a["avg_min"] else None,
                })
            result.append({
                "company_id": co_id,
                "company_name": co["name"],
                "today": today_s,
                "week": week_s,
                "advisors": advisors,
                "recent_failures": fails,
            })
        return jsonify({"is_super": True, "today": today, "week_start": week_start, "companies": result})
    else:
        # 店长/按门店筛选：在公司过滤基础上再叠加 store_id 条件
        store_cond = " AND store_id=?" if _sf is not None else ""
        store_p = [_sf] if _sf is not None else []
        today_s, week_s, fails = _session_stats(
            "AND (company_id IS NULL OR company_id=?)" + store_cond,
            tuple([today, cid] + store_p),
            tuple([week_start, cid] + store_p),
            tuple([cid] + store_p),
        )
        advisors_rows = db_fetchall(f"""
            SELECT
                advisor,
                COUNT(*) AS total,
                SUM(analysis_status='done') AS done,
                SUM(analysis_status='failed') AS failed,
                AVG(CASE
                    WHEN analysis_finished_at IS NOT NULL AND analysis_started_at IS NOT NULL
                    THEN (julianday(analysis_finished_at) - julianday(analysis_started_at)) * 1440
                    ELSE NULL END) AS avg_min
            FROM sessions
            WHERE (company_id IS NULL OR company_id=?){store_cond}
              AND REPLACE(service_date,'-','') >= REPLACE(?,'-','')
            GROUP BY advisor
            ORDER BY total DESC
        """, tuple([cid] + store_p + [week_start]))
        advisors = []
        for a in advisors_rows:
            total = a["total"] or 0
            done = a["done"] or 0
            failed = a["failed"] or 0
            advisors.append({
                "name": a["advisor"],
                "week_total": total,
                "week_done": done,
                "week_failed": failed,
                "week_pending": total - done - failed,
                "week_rate": round(done / total * 100) if total else 0,
                "avg_min": round(a["avg_min"], 1) if a["avg_min"] else None,
            })
        return jsonify({
            "is_super": False,
            "today": today,
            "week_start": week_start,
            "today_stats": today_s,
            "week_stats": week_s,
            "advisors": advisors,
            "recent_failures": fails,
        })


@app.route("/api/admin/stats")
@manager_required
def api_admin_stats():
    cid = session.get("company_id")
    is_super = session.get("role") == "super"
    uid = session.get("user_id") or 0
    sf = current_store_filter()
    cache_key = ("super", uid, sf) if is_super else ("cid", cid, uid, sf)
    import time as _time
    now = _time.time()
    with _admin_stats_lock:
        cached = _admin_stats_cache.get("data")
        ts = _admin_stats_cache.get("ts", 0.0)
        if cached and cached.get("_key") == cache_key and now - ts < 30:
            out = {k: v for k, v in cached.items() if k != "_key"}
            return jsonify(out)
    data = _compute_admin_stats(cid, is_super, sf)
    data["_key"] = cache_key
    with _admin_stats_lock:
        _admin_stats_cache["data"] = data
        _admin_stats_cache["ts"] = now
    return jsonify({k: v for k, v in data.items() if k != "_key"})


def _validate_store(raw, company_id):
    """校验 store_id 属于该公司。返回：int(合法) / None(未指定) / False(非法)。"""
    if raw in (None, "", 0, "0"):
        return None
    try:
        sid = int(raw)
    except (ValueError, TypeError):
        return False
    row = db_fetchone("SELECT id FROM stores WHERE id=? AND company_id=?", (sid, company_id))
    return sid if row else False


@app.route("/api/admin/consultants", methods=["GET"])
@admin_required
def api_admin_consultants_list():
    cid = session.get("company_id")
    q = (request.args.get("q") or "").strip()
    store_filter = (request.args.get("store_id") or "").strip()
    is_super = session.get("role") == "super"
    # 员工 = 顾问 + 店长（统一在一个列表管理；店长由此处分配角色）
    base_where = "u.role IN ('consultant','store_manager')"
    base_params = []
    if not is_super:
        base_where += " AND u.company_id=?"; base_params.append(cid)
    if store_filter:
        base_where += " AND u.store_id=?"; base_params.append(int(store_filter))
    if q:
        where = base_where + " AND (u.advisor_name LIKE ? OR u.employee_id LIKE ? OR u.phone LIKE ? OR u.username LIKE ?)"
        params = base_params + [f"%{q}%"] * 4
    else:
        where = base_where
        params = base_params
    rows = db_fetchall(
        f"SELECT u.id, u.username, u.role, u.company_id, u.advisor_name, u.employee_id, "
        f"u.phone, u.created_at, u.store_id, u.pen_sn, st.name AS store_name "
        f"FROM users u LEFT JOIN stores st ON st.id=u.store_id "
        f"WHERE {where} ORDER BY u.id DESC LIMIT 100",
        tuple(params),
    )
    cons = [dict(r) for r in rows]
    # 给每个员工附上"连过/用过的录音笔SN"列表(最近在前)，供绑定时下拉选
    if cons:
        ids = [c["id"] for c in cons]
        ph = ",".join("?" * len(ids))
        sights = db_fetchall(
            f"SELECT user_id, sn FROM pen_sn_sightings "
            f"WHERE user_id IN ({ph}) ORDER BY last_seen_at DESC", tuple(ids),
        )
        by_user = {}
        for s in sights:
            by_user.setdefault(s["user_id"], []).append(s["sn"])
        for c in cons:
            c["recent_sns"] = by_user.get(c["id"], [])
    total = db_fetchone(
        f"SELECT COUNT(*) AS n FROM users u WHERE {where}", tuple(params)
    )["n"]
    total_all = db_fetchone(
        f"SELECT COUNT(*) AS n FROM users u WHERE {base_where}", tuple(base_params)
    )["n"]
    return jsonify({
        "consultants": cons,
        "total": total,
        "total_all": total_all,
    })


@app.route("/api/admin/consultants", methods=["POST"])
@admin_required
def api_admin_consultants_create():
    data = request.get_json(silent=True) or {}
    advisor_name = (data.get("advisor_name") or "").strip()
    employee_id = (data.get("employee_id") or "").strip()
    phone = (data.get("phone") or "").strip()
    password = (data.get("password") or "").strip()
    # 登录用户名 = 手机号；若无手机号则退回工号
    username = phone or employee_id or (data.get("username") or "").strip()
    if not advisor_name or not username or not password:
        return jsonify({"error": "姓名、登录账号（手机号或工号）、密码必填"}), 400
    role = (data.get("role") or "consultant").strip()
    if role not in ("consultant", "store_manager"):
        return jsonify({"error": "角色只能是 顾问 或 店长"}), 400
    cid = session.get("company_id") or 1
    if session.get("role") == "super":
        cid = int(data.get("company_id") or cid)
    store_id = _validate_store(data.get("store_id"), cid)
    if store_id is False:
        return jsonify({"error": "所选门店不属于本公司"}), 400
    try:
        uid = db_write(
            """INSERT INTO users (username, password_hash, role, company_id,
                                  advisor_name, employee_id, phone, store_id)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
            (username, _hash_pw(password), role, cid, advisor_name,
             employee_id or None, phone or None, store_id),
        )
    except sqlite3.IntegrityError:
        return jsonify({"error": f"账号 {username} 已存在"}), 409
    return jsonify({"id": uid, "username": username})


@app.route("/api/admin/consultants/<int:uid>", methods=["PATCH"])
@admin_required
def api_admin_consultants_update(uid):
    row = db_fetchone("SELECT id, company_id, role FROM users WHERE id=?", (uid,))
    if not row or row["role"] not in ("consultant", "store_manager"):
        return jsonify({"error": "员工不存在"}), 404
    if session.get("role") != "super" and row["company_id"] != session.get("company_id"):
        return jsonify({"error": "无权操作"}), 403
    data = request.get_json(silent=True) or {}
    sets, params = [], []
    if data.get("password"):
        sets.append("password_hash=?")
        params.append(_hash_pw(data["password"]))
    for f in ("advisor_name", "employee_id", "phone", "pen_sn"):
        if f in data:
            sets.append(f"{f}=?")
            params.append((data[f] or "").strip() or None)
    if "role" in data:
        nr = (data["role"] or "").strip()
        if nr not in ("consultant", "store_manager"):
            return jsonify({"error": "角色只能是 顾问 或 店长"}), 400
        sets.append("role=?"); params.append(nr)
    if "store_id" in data:
        sv = _validate_store(data.get("store_id"), row["company_id"])
        if sv is False:
            return jsonify({"error": "所选门店不属于本公司"}), 400
        sets.append("store_id=?"); params.append(sv)
    if not sets:
        return jsonify({"error": "无修改字段"}), 400
    params.append(uid)
    db_write(f"UPDATE users SET {', '.join(sets)} WHERE id=?", tuple(params))
    return jsonify({"ok": True})


@app.route("/api/admin/consultants/<int:uid>", methods=["DELETE"])
@admin_required
def api_admin_consultants_delete(uid):
    row = db_fetchone("SELECT id, company_id, role FROM users WHERE id=?", (uid,))
    if not row or row["role"] not in ("consultant", "store_manager"):
        return jsonify({"error": "员工不存在"}), 404
    if session.get("role") != "super" and row["company_id"] != session.get("company_id"):
        return jsonify({"error": "无权操作"}), 403
    db_write("DELETE FROM users WHERE id=?", (uid,))
    return jsonify({"ok": True})


# ============ CSV/XLSX 解析工具 ============
def _parse_table_upload(file_storage):
    """支持 CSV / XLSX，返回二维列表（每行去首尾空白，去全空行）。"""
    import io as _io
    import csv as _csv
    fn = (file_storage.filename or "").lower()
    data = file_storage.read()
    rows = []
    if fn.endswith(".xlsx") or fn.endswith(".xls"):
        import openpyxl
        wb = openpyxl.load_workbook(_io.BytesIO(data), data_only=True, read_only=True)
        ws = wb.active
        for r in ws.iter_rows(values_only=True):
            rows.append([("" if v is None else str(v)).strip() for v in r])
    else:
        # 试 utf-8-sig，再退到 gbk
        text = None
        for enc in ("utf-8-sig", "utf-8", "gbk"):
            try:
                text = data.decode(enc)
                break
            except UnicodeDecodeError:
                continue
        if text is None:
            text = data.decode("utf-8", errors="ignore")
        for r in _csv.reader(_io.StringIO(text)):
            rows.append([(c or "").strip() for c in r])
    # 去全空行
    return [r for r in rows if any(c for c in r)]


def _looks_like_header(row, keywords):
    s = "".join(row).lower()
    return any(k in s for k in keywords)


@app.route("/api/admin/import/consultants", methods=["POST"])
@admin_required
def api_admin_import_consultants():
    f = request.files.get("file")
    if not f:
        return jsonify({"error": "未上传文件"}), 400
    try:
        rows = _parse_table_upload(f)
    except Exception as e:
        return jsonify({"error": f"解析失败：{e}"}), 400
    if rows and _looks_like_header(rows[0], ["姓名", "工号", "手机", "name", "phone"]):
        rows = rows[1:]
    cid = session.get("company_id") or 1
    imp_store = _validate_store(request.form.get("store_id"), cid)
    if imp_store is False:
        return jsonify({"error": "所选门店不属于本公司"}), 400
    created, skipped, failed = 0, 0, []
    for r in rows:
        name = (r[0] if len(r) > 0 else "").strip()
        emp = (r[1] if len(r) > 1 else "").strip()
        phone = (r[2] if len(r) > 2 else "").strip()
        pwd = (r[3] if len(r) > 3 else "").strip()
        if not name or not (phone or emp):
            failed.append({"row": r, "reason": "缺少姓名或手机号/工号"})
            continue
        username = phone or emp
        if not pwd:
            pwd = (phone[-6:] if len(phone) >= 6 else (phone or emp or "123456"))
        try:
            db_write(
                """INSERT INTO users (username, password_hash, role, company_id,
                                      advisor_name, employee_id, phone, store_id)
                   VALUES (?, ?, 'consultant', ?, ?, ?, ?, ?)""",
                (username, _hash_pw(pwd), cid, name, emp or None, phone or None, imp_store),
            )
            created += 1
        except sqlite3.IntegrityError:
            skipped += 1
    return jsonify({"created": created, "skipped": skipped, "failed": failed})


# ============ 管理员：顾客库 ============
@app.route("/api/admin/customers", methods=["GET"])
@admin_required
def api_admin_customers_list():
    cid = session.get("company_id")
    q = (request.args.get("q") or "").strip()
    is_super = session.get("role") == "super"
    # 排除已被合并的客人（merged_into IS NOT NULL），避免合并后还出现两条
    base_where = ("1=1" if is_super else "company_id=?") + " AND merged_into IS NULL"
    base_params = [] if is_super else [cid]
    if q:
        where = base_where + " AND (name LIKE ? OR member_card LIKE ?)"
        params = base_params + [f"%{q}%", f"%{q}%"]
    else:
        where = base_where
        params = base_params
    rows = db_fetchall(
        f"SELECT id, name, member_card, phone_tail, company_id, created_at "
        f"FROM company_customers WHERE {where} ORDER BY id DESC LIMIT 100",
        tuple(params),
    )
    total = db_fetchone(
        f"SELECT COUNT(*) AS n FROM company_customers WHERE {where}",
        tuple(params),
    )["n"]
    # 顺便给个总数（不带搜索）
    total_all = db_fetchone(
        f"SELECT COUNT(*) AS n FROM company_customers WHERE {base_where}",
        tuple(base_params),
    )["n"]
    return jsonify({
        "customers": [dict(r) for r in rows],
        "total": total,
        "total_all": total_all,
    })


@app.route("/api/admin/customer_profiles", methods=["GET"])
@manager_required
def api_admin_customer_profiles():
    """顾客档案 Tab 专用：分页聚合每个客人的最新接待人 / 最新服务时间 / 是否建档。
    不改 /api/admin/customers（那是客户管理列表）。
    - latest_advisor：该客人最近一次 session（按 service_date desc）的 advisor。
    - latest_service_date：最近 session.service_date；为空则回退该 session 对应录音 recorded_at（精确到年月日）。
    - has_profile：customer_value_cache 是否存在该客人缓存行 → bool。
    用相关子查询匹配每客最新 session（沿用 _customer_session_clause：优先 customer_id，旧客 customer_id 为空时按姓名+公司兜底），
    利用 idx_sessions_cust_date 索引；LEFT JOIN customer_value_cache 判断建档。分页 LIMIT/OFFSET。"""
    cid = session.get("company_id")
    is_super = session.get("role") == "super"
    q = (request.args.get("q") or "").strip()
    try:
        page = max(1, int(request.args.get("page", 1)))
    except (TypeError, ValueError):
        page = 1
    try:
        page_size = int(request.args.get("page_size", 100))
    except (TypeError, ValueError):
        page_size = 100
    page_size = max(1, min(page_size, 500))
    offset = (page - 1) * page_size

    base_where = ("1=1" if is_super else "c.company_id=?") + " AND c.merged_into IS NULL"
    base_params = [] if is_super else [cid]
    if q:
        where = base_where + " AND (c.name LIKE ? OR c.member_card LIKE ? OR c.phone_tail LIKE ?)"
        like = f"%{q}%"
        params = base_params + [like, like, like]
    else:
        where = base_where
        params = base_params

    total = db_fetchone(
        f"SELECT COUNT(*) AS n FROM company_customers c WHERE {where}",
        tuple(params),
    )["n"]

    # 每客最新一条 session：相关子查询沿用 _customer_session_clause 思路
    # （优先 customer_id；customer_id 为空的老客户按 name + 公司兜底）。
    sess_match = (
        "((s.customer_id IS NOT NULL AND s.customer_id=c.id) OR "
        "(s.customer_id IS NULL AND s.customer=c.name AND (s.company_id IS NULL OR s.company_id=c.company_id)))"
    )
    latest_sess_sql = (
        f"SELECT s.id FROM sessions s WHERE {sess_match} "
        "ORDER BY s.service_date DESC, s.id DESC LIMIT 1"
    )
    rows = db_fetchall(
        f"""SELECT c.id, c.name, c.member_card, c.phone_tail,
                   ls.advisor AS latest_advisor,
                   COALESCE(ls.service_date, substr(lr.recorded_at, 1, 10)) AS latest_service_date,
                   CASE WHEN cvc.id IS NOT NULL THEN 1 ELSE 0 END AS has_profile
            FROM company_customers c
            LEFT JOIN sessions ls ON ls.id = ({latest_sess_sql})
            LEFT JOIN recordings lr ON lr.session_id = ls.id
            LEFT JOIN customer_value_cache cvc
                   ON cvc.company_id = c.company_id AND cvc.customer_id = c.id
            WHERE {where}
            GROUP BY c.id
            ORDER BY c.id DESC
            LIMIT ? OFFSET ?""",
        tuple(params + [page_size, offset]),
    )
    items = [{
        "id": r["id"],
        "name": r["name"],
        "member_card": r["member_card"],
        "phone_tail": r["phone_tail"],
        "latest_advisor": r["latest_advisor"],
        "latest_service_date": r["latest_service_date"],
        "has_profile": bool(r["has_profile"]),
    } for r in rows]
    return jsonify({
        "items": items,
        "page": page,
        "page_size": page_size,
        "total": total,
    })


def _generate_member_card(company_id):
    """格式：M + YYMMDD + 4位随机数字。本公司内唯一，冲突重试。"""
    from random import randint
    today = datetime.now().strftime("%y%m%d")
    for _ in range(20):
        code = f"M{today}{randint(0, 9999):04d}"
        exists = db_fetchone(
            "SELECT 1 FROM company_customers WHERE company_id=? AND member_card=?",
            (company_id, code),
        )
        if not exists:
            return code
    # 极端 fallback：加 uuid 短串
    return f"M{today}{uuid.uuid4().hex[:6]}"


@app.route("/api/admin/customers", methods=["POST"])
@admin_required
def api_admin_customers_create():
    data = request.get_json(silent=True) or {}
    name = (data.get("name") or "").strip()
    card = (data.get("member_card") or "").strip()
    phone_tail = (data.get("phone_tail") or "").strip()
    if not name:
        return jsonify({"error": "姓名必填"}), 400
    cid = session.get("company_id") or 1
    # 手机尾号校验：可选；若填则必须是 4 位数字
    if phone_tail and not re.fullmatch(r"\d{4}", phone_tail):
        return jsonify({"error": "手机尾号必须是 4 位数字"}), 400
    # 会员号校验：填了就查公司内唯一
    if card:
        dup = db_fetchone(
            "SELECT id FROM company_customers WHERE company_id=? AND member_card=?",
            (cid, card),
        )
        if dup:
            return jsonify({"error": "会员号已存在，请改一个或留空让系统生成"}), 409
    else:
        card = _generate_member_card(cid)
    rid = db_write(
        "INSERT INTO company_customers (company_id, name, member_card, phone_tail) VALUES (?, ?, ?, ?)",
        (cid, name, card, phone_tail or None),
    )
    return jsonify({"id": rid, "name": name, "member_card": card, "phone_tail": phone_tail or None})


@app.route("/api/admin/customers/<int:cid>", methods=["DELETE"])
@admin_required
def api_admin_customers_delete(cid):
    row = db_fetchone("SELECT id, company_id FROM company_customers WHERE id=?", (cid,))
    if not row:
        return jsonify({"error": "不存在"}), 404
    if session.get("role") != "super" and row["company_id"] != session.get("company_id"):
        return jsonify({"error": "无权操作"}), 403
    db_write("DELETE FROM company_customers WHERE id=?", (cid,))
    return jsonify({"ok": True})


# ============ 客户合并 / 拆分（公司级数据治理） ============
@app.route("/api/admin/customers/merge", methods=["POST"])
@admin_required
def api_admin_customers_merge():
    """把误判为两个人的客人 A(from) 合并进 B(into)。
    - 改挂 sessions / customer_tags / daily_reception 到 B；
    - 删冲突的 daily_reception 行（UNIQUE 约束）；
    - 删 A、B 的 customer_value_cache（历史变了，缓存作废）；
    - A.merged_into=B；写 customer_merge_log（含可还原快照）。"""
    data = request.get_json(silent=True) or {}
    from_id = data.get("from_id")
    into_id = data.get("into_id")
    reason = (data.get("reason") or "").strip()
    try:
        from_id = int(from_id)
        into_id = int(into_id)
    except (TypeError, ValueError):
        return jsonify({"error": "缺少有效的 from_id / into_id"}), 400
    if from_id == into_id:
        return jsonify({"error": "不能把客人合并到自己"}), 400
    is_super = session.get("role") == "super"
    my_cid = session.get("company_id")
    a = db_fetchone("SELECT id, company_id, name, merged_into FROM company_customers WHERE id=?", (from_id,))
    b = db_fetchone("SELECT id, company_id, name, merged_into FROM company_customers WHERE id=?", (into_id,))
    if not a or not b:
        return jsonify({"error": "客人不存在"}), 404
    if a["company_id"] != b["company_id"]:
        return jsonify({"error": "不允许跨公司合并"}), 400
    if not is_super and (a["company_id"] != my_cid or b["company_id"] != my_cid):
        return jsonify({"error": "无权操作"}), 403
    if a["merged_into"] is not None:
        return jsonify({"error": "源客人已被合并，请先撤销"}), 409
    if b["merged_into"] is not None:
        return jsonify({"error": "目标客人已被合并，不能作为合并目标"}), 409

    cid = a["company_id"]
    from_name = a["name"]
    into_name = b["name"]
    u = current_user()
    operator_user_id = u["id"] if u else session.get("user_id")
    operator_name = (u["advisor_name"] or u["username"]) if u else session.get("username")

    # 快照：将要改动的行 id
    sess_rows = db_fetchall("SELECT id FROM sessions WHERE customer_id=?", (from_id,))
    sess_ids = [r["id"] for r in sess_rows]
    # customer_tags 精确归属（不再按姓名整体改写，避免误伤同名客户）：
    #  (a) 已有 customer_id 的标签：customer_id=from_id 的直接迁移；
    #  (b) customer_id 为 NULL 的历史标签：仅当能通过 source_session_id 确认其 session
    #      当前 customer_id=from_id 时才迁移；否则留着不动（无法精确归属）。
    # 注意：此处必须在 _do 里 repoint sessions.customer_id 之前采集，
    #      否则 sessions 已改挂到 into_id，(b) 条件会落空。
    tag_rows = db_fetchall(
        """SELECT id FROM customer_tags
           WHERE customer_id=?
              OR (customer_id IS NULL AND source_session_id IS NOT NULL
                  AND source_session_id IN (
                      SELECT id FROM sessions WHERE customer_id=?
                  ))""",
        (from_id, from_id),
    )
    tag_ids = [r["id"] for r in tag_rows]
    dr_rows = db_fetchall(
        "SELECT id, advisor_user_id, service_date FROM daily_reception WHERE customer_id=?",
        (from_id,),
    )

    affected = {
        "session_ids": sess_ids,
        "customer_tag_ids": tag_ids,  # 被迁移的 customer_tags.id 精确集合
        "from_id": from_id,           # unmerge 据此精确还原 customer_id
        "into_id": into_id,
        "from_name": from_name,
        "into_name": into_name,
        "daily_reception": [],   # 改挂成功的行 id
        "daily_reception_deleted": [],  # 因 UNIQUE 冲突删除的行 id
    }

    def _do(conn):
        conn.execute("BEGIN")
        # daily_reception：逐行改挂，冲突则删除该行（避免 IntegrityError）
        kept, deleted = [], []
        for r in dr_rows:
            dr_id = r["id"]
            conflict = conn.execute(
                "SELECT id FROM daily_reception "
                "WHERE advisor_user_id=? AND customer_id=? AND service_date=? AND id<>?",
                (r["advisor_user_id"], into_id, r["service_date"], dr_id),
            ).fetchone()
            if conflict:
                conn.execute("DELETE FROM daily_reception WHERE id=?", (dr_id,))
                deleted.append(dr_id)
            else:
                conn.execute(
                    "UPDATE daily_reception SET customer_id=? WHERE id=?",
                    (into_id, dr_id),
                )
                kept.append(dr_id)
        # sessions：改 customer_id 并刷新姓名快照
        if sess_ids:
            conn.execute(
                "UPDATE sessions SET customer_id=?, customer=? WHERE customer_id=?",
                (into_id, into_name, from_id),
            )
        # customer_tags 按 customer_id 精确迁移（tag_ids 已在 repoint sessions 前采集，
        # 含 customer_id=from_id 的行 + 能经 source_session 确认属于 from 的 NULL 行）。
        # 绝不再用 "WHERE customer_name=from_name" 按名整体改写。
        if tag_ids:
            conn.executemany(
                "UPDATE customer_tags SET customer_id=?, customer_name=? WHERE id=?",
                [(into_id, into_name, tid) for tid in tag_ids],
            )
        # 价值预测缓存作废（A、B 都删）
        conn.execute(
            "DELETE FROM customer_value_cache WHERE customer_id IN (?, ?)",
            (from_id, into_id),
        )
        # 标记已合并
        conn.execute(
            "UPDATE company_customers SET merged_into=? WHERE id=?",
            (into_id, from_id),
        )
        affected["daily_reception"] = kept
        affected["daily_reception_deleted"] = deleted
        log_id = conn.execute(
            "INSERT INTO customer_merge_log "
            "(company_id, from_customer_id, from_name, into_customer_id, into_name, "
            " operator_user_id, operator_name, reason, affected) "
            "VALUES (?,?,?,?,?,?,?,?,?)",
            (cid, from_id, from_name, into_id, into_name,
             operator_user_id, operator_name, reason, json.dumps(affected, ensure_ascii=False)),
        ).lastrowid
        conn.commit()
        return log_id

    log_id = db_batch(_do)
    return jsonify({
        "ok": True,
        "log_id": log_id,
        "moved_sessions": len(sess_ids),
        "moved_tags": len(tag_ids),
        "moved_daily_reception": len(affected["daily_reception"]),
        "deleted_daily_reception": len(affected["daily_reception_deleted"]),
    })


@app.route("/api/admin/customers/unmerge", methods=["POST"])
@admin_required
def api_admin_customers_unmerge():
    """按 log.affected 快照撤销一次合并：把 sessions/daily_reception/customer_tags 还原到 from。
    注意：UNIQUE 冲突删除的 daily_reception 行无法还原（数据已不在）。"""
    data = request.get_json(silent=True) or {}
    log_id = data.get("log_id")
    try:
        log_id = int(log_id)
    except (TypeError, ValueError):
        return jsonify({"error": "缺少有效的 log_id"}), 400
    log = db_fetchone("SELECT * FROM customer_merge_log WHERE id=?", (log_id,))
    if not log:
        return jsonify({"error": "合并记录不存在"}), 404
    if log["undone"]:
        return jsonify({"error": "该合并已撤销"}), 409
    is_super = session.get("role") == "super"
    if not is_super and log["company_id"] != session.get("company_id"):
        return jsonify({"error": "无权操作"}), 403

    from_id = log["from_customer_id"]
    into_id = log["into_customer_id"]
    try:
        affected = json.loads(log["affected"] or "{}")
    except Exception:
        affected = {}
    from_name = affected.get("from_name") or log["from_name"]
    into_name = affected.get("into_name") or log["into_name"]
    sess_ids = affected.get("session_ids") or []
    tag_ids = affected.get("customer_tag_ids") or []
    dr_kept = affected.get("daily_reception") or []

    def _do(conn):
        conn.execute("BEGIN")
        # sessions 还原（仅快照里的那些行）
        for sid in sess_ids:
            conn.execute(
                "UPDATE sessions SET customer_id=?, customer=? WHERE id=?",
                (from_id, from_name, sid),
            )
        # customer_tags 还原（按快照里的 tag id 精确还原 customer_id 与姓名）：
        # 把 customer_id 还原为 from_id、customer_name 还原为 from_name。不按姓名还原。
        for tid in tag_ids:
            conn.execute(
                "UPDATE customer_tags SET customer_id=?, customer_name=? WHERE id=?",
                (from_id, from_name, tid),
            )
        # daily_reception 还原（成功改挂过的那些行；UNIQUE 冲突删掉的无法恢复）
        for dr_id in dr_kept:
            # 还原时也可能与 from_id 现存行冲突，谨慎跳过
            row = conn.execute(
                "SELECT advisor_user_id, service_date FROM daily_reception WHERE id=?",
                (dr_id,),
            ).fetchone()
            if not row:
                continue
            conflict = conn.execute(
                "SELECT id FROM daily_reception "
                "WHERE advisor_user_id=? AND customer_id=? AND service_date=? AND id<>?",
                (row[0], from_id, row[1], dr_id),
            ).fetchone()
            if conflict:
                conn.execute("DELETE FROM daily_reception WHERE id=?", (dr_id,))
            else:
                conn.execute(
                    "UPDATE daily_reception SET customer_id=? WHERE id=?",
                    (from_id, dr_id),
                )
        # 清 merged_into
        conn.execute(
            "UPDATE company_customers SET merged_into=NULL WHERE id=?",
            (from_id,),
        )
        # 缓存作废（两边都删）
        conn.execute(
            "DELETE FROM customer_value_cache WHERE customer_id IN (?, ?)",
            (from_id, into_id),
        )
        conn.execute(
            "UPDATE customer_merge_log SET undone=1, undone_at=datetime('now','localtime') WHERE id=?",
            (log_id,),
        )
        conn.commit()

    db_batch(_do)
    return jsonify({"ok": True})


@app.route("/api/admin/customers/duplicates", methods=["GET"])
@admin_required
def api_admin_customers_duplicates():
    """同公司内、未被合并的客人中，找疑似同一人的配对：
    同名、或 member_card 相同(非空)、或 phone_tail 相同(非空)。返回分组。"""
    is_super = session.get("role") == "super"
    if is_super:
        cid = request.args.get("company_id", type=int)
    else:
        cid = session.get("company_id")

    # 分页参数：page（默认1）、page_size（默认20，单位=组）
    page = request.args.get("page", default=1, type=int) or 1
    page_size = request.args.get("page_size", default=20, type=int) or 20
    if page < 1:
        page = 1
    if page_size < 1:
        page_size = 20

    if not cid:
        return jsonify({
            "groups": [],
            "page": page,
            "page_size": page_size,
            "total_groups": 0,
            "error": None,
        })

    rows = db_fetchall(
        "SELECT id, name, member_card, phone_tail FROM company_customers "
        "WHERE company_id=? AND merged_into IS NULL",
        (cid,),
    )
    # 接诊次数（按 customer_id 聚合）
    cnt_rows = db_fetchall(
        "SELECT customer_id, COUNT(*) AS n FROM sessions "
        "WHERE company_id=? AND customer_id IS NOT NULL GROUP BY customer_id",
        (cid,),
    )
    cnt_map = {r["customer_id"]: r["n"] for r in cnt_rows}

    def _cust(r):
        return {
            "id": r["id"],
            "name": r["name"],
            "member_card": r["member_card"],
            "phone_tail": r["phone_tail"],
            "session_count": cnt_map.get(r["id"], 0),
        }

    # 按三种 key 分桶
    by_name, by_card, by_phone = {}, {}, {}
    for r in rows:
        by_name.setdefault((r["name"] or "").strip(), []).append(r)
        mc = (r["member_card"] or "").strip()
        if mc:
            by_card.setdefault(mc, []).append(r)
        pt = (r["phone_tail"] or "").strip()
        if pt:
            by_phone.setdefault(pt, []).append(r)

    groups = []
    seen_signatures = set()
    for reason, bucket in (("同名", by_name), ("会员卡号相同", by_card), ("手机尾号相同", by_phone)):
        for key, members in bucket.items():
            if not key or len(members) < 2:
                continue
            sig = (reason, key, tuple(sorted(m["id"] for m in members)))
            if sig in seen_signatures:
                continue
            seen_signatures.add(sig)
            groups.append({
                "reason": reason,
                "key": key,
                "candidates": [_cust(m) for m in members],
            })

    # 注意：分桶仍全量加载（上面 SELECT 拉全表后在 Python 内存分桶），
    # 此处分页只对已算好的 groups 做切片，纯展示/未来增长用途。
    # 当前实测仅约 11 组、性能无忧；组数大增时需改 SQL（用 GROUP BY ... HAVING COUNT(*)>1
    # 在数据库侧筛分组并分页），不能再依赖内存全量分桶。
    total_groups = len(groups)
    start = (page - 1) * page_size
    page_groups = groups[start:start + page_size]
    return jsonify({
        "groups": page_groups,
        "page": page,
        "page_size": page_size,
        "total_groups": total_groups,
        "company_id": cid,
    })


@app.route("/api/admin/customers/merge_log", methods=["GET"])
@admin_required
def api_admin_customers_merge_log():
    """合并历史。admin/super 看本公司；super 可带 ?company_id。"""
    is_super = session.get("role") == "super"
    if is_super:
        cid = request.args.get("company_id", type=int)
        if cid:
            rows = db_fetchall(
                "SELECT * FROM customer_merge_log WHERE company_id=? ORDER BY id DESC LIMIT 200",
                (cid,),
            )
        else:
            rows = db_fetchall(
                "SELECT * FROM customer_merge_log ORDER BY id DESC LIMIT 200"
            )
    else:
        rows = db_fetchall(
            "SELECT * FROM customer_merge_log WHERE company_id=? ORDER BY id DESC LIMIT 200",
            (session.get("company_id"),),
        )
    out = []
    for r in rows:
        d = dict(r)
        try:
            aff = json.loads(d.get("affected") or "{}")
        except Exception:
            aff = {}
        d["affected_summary"] = {
            "sessions": len(aff.get("session_ids") or []),
            "tags": len(aff.get("customer_tag_ids") or []),
            "daily_reception": len(aff.get("daily_reception") or []),
            "daily_reception_deleted": len(aff.get("daily_reception_deleted") or []),
        }
        d.pop("affected", None)
        out.append(d)
    return jsonify({"logs": out})


# 导入任务进度（in-memory；gunicorn -w 1 单 worker 够用）
_import_jobs = {}
_import_jobs_lock = threading.Lock()


def _run_customer_import(job_id, cid, seen):
    job = _import_jobs[job_id]
    try:
        total = len(seen)
        def _do_import(conn):
            existing_rows = conn.execute(
                "SELECT name, id, member_card FROM company_customers WHERE company_id=?",
                (cid,),
            ).fetchall()
            existing_map = {row[0]: (row[1], row[2]) for row in existing_rows}
            conn.execute("BEGIN")
            _processed = 0
            _created = 0
            _skipped = 0
            for name, card in seen.items():
                if name in existing_map:
                    old_id, old_card = existing_map[name]
                    if card and card != (old_card or ""):
                        conn.execute(
                            "UPDATE company_customers SET member_card=? WHERE id=?",
                            (card, old_id),
                        )
                    _skipped += 1
                else:
                    conn.execute(
                        "INSERT INTO company_customers (company_id, name, member_card) VALUES (?, ?, ?)",
                        (cid, name, card or None),
                    )
                    _created += 1
                _processed += 1
                if _processed % 500 == 0:
                    with _import_jobs_lock:
                        job["processed"] = _processed
                        job["created"] = _created
                        job["skipped"] = _skipped
            conn.commit()
            return _processed, _created, _skipped

        processed, created, skipped = db_batch(_do_import, timeout=120)
        with _import_jobs_lock:
            job["status"] = "done"
            job["processed"] = total
            job["created"] = created
            job["skipped"] = skipped
            job["finished_at"] = datetime.now().isoformat()
    except Exception as e:
        with _import_jobs_lock:
            job["status"] = "failed"
            job["error"] = str(e)[:500]
            job["finished_at"] = datetime.now().isoformat()


@app.route("/api/admin/import/customers", methods=["POST"])
@admin_required
def api_admin_import_customers():
    f = request.files.get("file")
    if not f:
        return jsonify({"error": "未上传文件"}), 400
    try:
        rows = _parse_table_upload(f)
    except Exception as e:
        return jsonify({"error": f"解析失败：{e}"}), 400
    if rows and _looks_like_header(rows[0], ["姓名", "会员", "name", "card"]):
        rows = rows[1:]
    cid = session.get("company_id") or 1
    seen = {}
    for r in rows:
        name = (r[0] if len(r) > 0 else "").strip()
        card = (r[1] if len(r) > 1 else "").strip()
        if not name:
            continue
        if name in seen and not card:
            continue
        seen[name] = card
    job_id = uuid.uuid4().hex[:12]
    with _import_jobs_lock:
        _import_jobs[job_id] = {
            "status": "running",
            "total": len(seen),
            "processed": 0,
            "created": 0,
            "skipped": 0,
            "error": None,
            "started_at": datetime.now().isoformat(),
            "finished_at": None,
        }
    threading.Thread(
        target=_run_customer_import, args=(job_id, cid, seen), daemon=True
    ).start()
    return jsonify({"job_id": job_id, "total": len(seen)})


@app.route("/api/admin/import/customers/jobs/<job_id>")
@admin_required
def api_admin_import_customers_status(job_id):
    with _import_jobs_lock:
        job = _import_jobs.get(job_id)
        if not job:
            return jsonify({"error": "任务不存在或已过期"}), 404
        return jsonify(dict(job))


# ============ 公司管理（super） ============
@app.route("/api/admin/companies", methods=["GET"])
@super_required
def api_admin_companies_list():
    comps = db_fetchall("SELECT id, name, created_at FROM companies ORDER BY id")
    out = []
    for c in comps:
        admins = db_fetchall(
            "SELECT username, advisor_name FROM users WHERE company_id=? AND role='admin'",
            (c["id"],),
        )
        d = dict(c)
        d["admins"] = [dict(a) for a in admins]
        out.append(d)
    return jsonify({"companies": out})


@app.route("/api/admin/companies", methods=["POST"])
@super_required
def api_admin_companies_create():
    data = request.get_json(silent=True) or {}
    name = (data.get("name") or "").strip()
    au = (data.get("admin_username") or "").strip()
    an = (data.get("admin_name") or "").strip() or "管理员"
    ap = (data.get("admin_password") or "").strip()
    if not name or not au or not ap:
        return jsonify({"error": "公司名、管理员用户名、密码必填"}), 400
    try:
        cid = db_write("INSERT INTO companies (name) VALUES (?)", (name,))
    except sqlite3.IntegrityError:
        return jsonify({"error": "公司名已存在"}), 409
    # 新公司自动建「默认门店」，管理员（admin 看全公司，门店仅作占位）也归入
    store_id = db_write(
        "INSERT OR IGNORE INTO stores (company_id, name) VALUES (?, '默认门店')", (cid,)
    )
    srow = db_fetchone("SELECT id FROM stores WHERE company_id=? AND name='默认门店'", (cid,))
    store_id = srow["id"] if srow else None
    try:
        db_write(
            """INSERT INTO users (username, password_hash, role, company_id, advisor_name, store_id)
               VALUES (?, ?, 'admin', ?, ?, ?)""",
            (au, _hash_pw(ap), cid, an, store_id),
        )
    except sqlite3.IntegrityError:
        return jsonify({"error": "管理员用户名已存在（公司已创建，请单独再加管理员）"}), 409
    return jsonify({"id": cid})


# ============ 门店管理 ============
@app.route("/api/admin/stores", methods=["GET"])
@manager_required
def api_admin_stores_list():
    """门店列表（含每店顾问数/店长名）。super 看全部公司；admin/店长 看本公司。"""
    is_super = session.get("role") == "super"
    cid = session.get("company_id")
    if is_super:
        where, params = "1=1", []
    else:
        where, params = "s.company_id=?", [cid]
    rows = db_fetchall(
        f"""SELECT s.id, s.name, s.region, s.company_id, s.created_at,
                   co.name AS company_name,
                   (SELECT COUNT(*) FROM users u WHERE u.store_id=s.id AND u.role='consultant') AS consultant_count,
                   (SELECT COUNT(*) FROM users u WHERE u.store_id=s.id AND u.role='store_manager') AS manager_count,
                   (SELECT GROUP_CONCAT(u.advisor_name, '、') FROM users u
                      WHERE u.store_id=s.id AND u.role='store_manager') AS managers
            FROM stores s LEFT JOIN companies co ON co.id=s.company_id
            WHERE {where} ORDER BY s.company_id, s.id""",
        tuple(params),
    )
    return jsonify({"stores": [dict(r) for r in rows]})


@app.route("/api/admin/stores", methods=["POST"])
@admin_required
def api_admin_stores_create():
    data = request.get_json(silent=True) or {}
    name = (data.get("name") or "").strip()
    region = (data.get("region") or "").strip() or None
    if not name:
        return jsonify({"error": "门店名必填"}), 400
    cid = session.get("company_id") or 1
    if session.get("role") == "super" and data.get("company_id"):
        cid = int(data["company_id"])
    try:
        sid = db_write(
            "INSERT INTO stores (company_id, name, region) VALUES (?, ?, ?)",
            (cid, name, region),
        )
    except sqlite3.IntegrityError:
        return jsonify({"error": f"门店「{name}」已存在"}), 409
    return jsonify({"id": sid, "name": name})


@app.route("/api/admin/stores/<int:sid>", methods=["PATCH"])
@admin_required
def api_admin_stores_update(sid):
    row = db_fetchone("SELECT id, company_id FROM stores WHERE id=?", (sid,))
    if not row:
        return jsonify({"error": "门店不存在"}), 404
    if session.get("role") != "super" and row["company_id"] != session.get("company_id"):
        return jsonify({"error": "无权操作"}), 403
    data = request.get_json(silent=True) or {}
    sets, params = [], []
    if "name" in data:
        nm = (data["name"] or "").strip()
        if not nm:
            return jsonify({"error": "门店名不能为空"}), 400
        sets.append("name=?"); params.append(nm)
    if "region" in data:
        sets.append("region=?"); params.append((data["region"] or "").strip() or None)
    if not sets:
        return jsonify({"error": "无修改字段"}), 400
    params.append(sid)
    try:
        db_write(f"UPDATE stores SET {', '.join(sets)} WHERE id=?", tuple(params))
    except sqlite3.IntegrityError:
        return jsonify({"error": "门店名重复"}), 409
    return jsonify({"ok": True})


@app.route("/api/admin/stores/<int:sid>", methods=["DELETE"])
@admin_required
def api_admin_stores_delete(sid):
    row = db_fetchone("SELECT id, company_id FROM stores WHERE id=?", (sid,))
    if not row:
        return jsonify({"error": "门店不存在"}), 404
    if session.get("role") != "super" and row["company_id"] != session.get("company_id"):
        return jsonify({"error": "无权操作"}), 403
    # 有员工或历史接诊挂在该门店时禁止删除（先把人/数据迁走）
    n_users = db_fetchone("SELECT COUNT(*) AS n FROM users WHERE store_id=?", (sid,))["n"]
    if n_users:
        return jsonify({"error": f"该门店下还有 {n_users} 个账号，请先把他们调到别的门店"}), 409
    n_sess = db_fetchone("SELECT COUNT(*) AS n FROM sessions WHERE store_id=?", (sid,))["n"]
    if n_sess:
        return jsonify({"error": f"该门店下有 {n_sess} 条历史接诊，禁止删除"}), 409
    db_write("DELETE FROM stores WHERE id=?", (sid,))
    return jsonify({"ok": True})


# ============ 管理员账号管理 ============
@app.route("/api/admin/admins", methods=["GET"])
@admin_required
def api_admin_admins_list():
    """管理员账号列表。admin 看本公司；super 看全部（带公司名）。"""
    is_super = session.get("role") == "super"
    if is_super:
        rows = db_fetchall(
            """SELECT u.id, u.username, u.advisor_name, u.company_id, u.created_at,
                      co.name AS company_name
               FROM users u LEFT JOIN companies co ON co.id=u.company_id
               WHERE u.role='admin' ORDER BY u.company_id, u.id DESC""")
    else:
        rows = db_fetchall(
            """SELECT u.id, u.username, u.advisor_name, u.company_id, u.created_at,
                      co.name AS company_name
               FROM users u LEFT JOIN companies co ON co.id=u.company_id
               WHERE u.role='admin' AND u.company_id=? ORDER BY u.id DESC""",
            (session.get("company_id"),))
    return jsonify({"admins": [dict(r) for r in rows]})


@app.route("/api/admin/admins", methods=["POST"])
@admin_required
def api_admin_admins_create():
    data = request.get_json(silent=True) or {}
    username = (data.get("username") or "").strip()
    name = (data.get("advisor_name") or "").strip() or "管理员"
    password = (data.get("password") or "").strip()
    if not username or not password:
        return jsonify({"error": "登录用户名、密码必填"}), 400
    cid = session.get("company_id") or 1
    if session.get("role") == "super" and data.get("company_id"):
        cid = int(data["company_id"])
    try:
        uid = db_write(
            """INSERT INTO users (username, password_hash, role, company_id, advisor_name)
               VALUES (?, ?, 'admin', ?, ?)""",
            (username, _hash_pw(password), cid, name))
    except sqlite3.IntegrityError:
        return jsonify({"error": f"用户名 {username} 已存在"}), 409
    return jsonify({"id": uid, "username": username})


@app.route("/api/admin/admins/<int:uid>", methods=["PATCH"])
@admin_required
def api_admin_admins_update(uid):
    row = db_fetchone("SELECT id, company_id, role FROM users WHERE id=?", (uid,))
    if not row or row["role"] != "admin":
        return jsonify({"error": "管理员不存在"}), 404
    if session.get("role") != "super" and row["company_id"] != session.get("company_id"):
        return jsonify({"error": "无权操作"}), 403
    data = request.get_json(silent=True) or {}
    sets, params = [], []
    if data.get("password"):
        sets.append("password_hash=?"); params.append(_hash_pw(data["password"]))
    if "advisor_name" in data:
        sets.append("advisor_name=?"); params.append((data["advisor_name"] or "").strip() or "管理员")
    if not sets:
        return jsonify({"error": "无修改字段"}), 400
    params.append(uid)
    db_write(f"UPDATE users SET {', '.join(sets)} WHERE id=?", tuple(params))
    return jsonify({"ok": True})


@app.route("/api/admin/admins/<int:uid>", methods=["DELETE"])
@admin_required
def api_admin_admins_delete(uid):
    row = db_fetchone("SELECT id, company_id, role FROM users WHERE id=?", (uid,))
    if not row or row["role"] != "admin":
        return jsonify({"error": "管理员不存在"}), 404
    if session.get("role") != "super" and row["company_id"] != session.get("company_id"):
        return jsonify({"error": "无权操作"}), 403
    if uid == session.get("user_id"):
        return jsonify({"error": "不能删除自己"}), 400
    n = db_fetchone(
        "SELECT COUNT(*) AS n FROM users WHERE role='admin' AND company_id=?",
        (row["company_id"],))["n"]
    if n <= 1:
        return jsonify({"error": "这是该公司最后一个管理员，不能删除"}), 400
    db_write("DELETE FROM users WHERE id=?", (uid,))
    return jsonify({"ok": True})


# ============ 标签归一化词典（管理端） ============
def _dict_company_id():
    """词典操作针对哪个公司：admin=本公司；super=?company_id 或 1。"""
    if session.get("role") == "super":
        try:
            return int(request.args.get("company_id") or (request.get_json(silent=True) or {}).get("company_id") or 1)
        except (ValueError, TypeError):
            return 1
    return session.get("company_id") or 1


def renormalize_company_tags(company_id):
    """词典变更后，把该公司所有 customer_tags 重新归一：
    黑名单→删除该标签记录；命中标准词→写 canonical_tag；未命中→canonical_tag=NULL（待归类）。"""
    invalidate_tag_dict(company_id)
    m = get_tag_dict_map(company_id)
    rows = db_fetchall(
        """SELECT ct.id, ct.tag FROM customer_tags ct
           JOIN sessions s ON s.id = ct.source_session_id
           WHERE (s.company_id IS NULL AND ?=1) OR s.company_id = ?""",
        (company_id, company_id),
    )
    to_del, to_set = [], []
    for r in rows:
        hit = m.get(_norm_key(r["tag"]))
        if hit and hit[1] == "blacklist":
            to_del.append((r["id"],))
        else:
            to_set.append((hit[0] if hit else None, r["id"]))

    def _apply(conn):
        if to_del:
            conn.executemany("DELETE FROM customer_tags WHERE id=?", to_del)
        if to_set:
            conn.executemany("UPDATE customer_tags SET canonical_tag=? WHERE id=?", to_set)
        conn.commit()
    db_batch(_apply)
    return {"updated": len(to_set), "deleted": len(to_del)}


@app.route("/api/admin/tag_dictionary", methods=["GET"])
@admin_required
def api_tag_dict_list():
    cid = _dict_company_id()
    rows = db_fetchall(
        "SELECT id, category, canonical_tag, synonyms, status FROM tag_dictionary "
        "WHERE company_id=? ORDER BY (status='blacklist'), category, canonical_tag",
        (cid,),
    )
    out = []
    for r in rows:
        d = dict(r)
        try:
            d["synonyms"] = json.loads(r["synonyms"] or "[]") or []
        except (json.JSONDecodeError, TypeError):
            d["synonyms"] = []
        out.append(d)
    cats = sorted({(r["category"] or "其他") for r in rows})
    return jsonify({"entries": out, "categories": cats, "company_id": cid})


@app.route("/api/admin/tag_dictionary", methods=["POST"])
@admin_required
def api_tag_dict_create():
    data = request.get_json(silent=True) or {}
    cid = _dict_company_id()
    canon = (data.get("canonical_tag") or "").strip()
    category = (data.get("category") or "其他").strip() or "其他"
    status = (data.get("status") or "active").strip()
    if status not in ("active", "blacklist"):
        status = "active"
    syns = data.get("synonyms") or []
    if not canon:
        return jsonify({"error": "标准词必填"}), 400
    syns = [s.strip() for s in syns if isinstance(s, str) and s.strip() and s.strip() != canon]
    try:
        did = db_write(
            "INSERT INTO tag_dictionary (company_id, category, canonical_tag, synonyms, status) VALUES (?, ?, ?, ?, ?)",
            (cid, category, canon, json.dumps(syns, ensure_ascii=False), status),
        )
    except sqlite3.IntegrityError:
        return jsonify({"error": f"标准词「{canon}」已存在"}), 409
    stats = renormalize_company_tags(cid)
    return jsonify({"id": did, "renormalized": stats})


@app.route("/api/admin/tag_dictionary/<int:did>", methods=["PATCH"])
@admin_required
def api_tag_dict_update(did):
    row = db_fetchone("SELECT id, company_id FROM tag_dictionary WHERE id=?", (did,))
    if not row:
        return jsonify({"error": "词典项不存在"}), 404
    if session.get("role") != "super" and row["company_id"] != session.get("company_id"):
        return jsonify({"error": "无权操作"}), 403
    data = request.get_json(silent=True) or {}
    sets, params = [], []
    if "category" in data:
        sets.append("category=?"); params.append((data["category"] or "其他").strip() or "其他")
    if "canonical_tag" in data:
        nm = (data["canonical_tag"] or "").strip()
        if not nm:
            return jsonify({"error": "标准词不能为空"}), 400
        sets.append("canonical_tag=?"); params.append(nm)
    if "synonyms" in data:
        syns = [s.strip() for s in (data["synonyms"] or []) if isinstance(s, str) and s.strip()]
        sets.append("synonyms=?"); params.append(json.dumps(syns, ensure_ascii=False))
    if "status" in data:
        st = (data["status"] or "active").strip()
        if st not in ("active", "blacklist"):
            return jsonify({"error": "状态非法"}), 400
        sets.append("status=?"); params.append(st)
    if not sets:
        return jsonify({"error": "无修改字段"}), 400
    params.append(did)
    try:
        db_write(f"UPDATE tag_dictionary SET {', '.join(sets)} WHERE id=?", tuple(params))
    except sqlite3.IntegrityError:
        return jsonify({"error": "标准词重复"}), 409
    stats = renormalize_company_tags(row["company_id"])
    return jsonify({"ok": True, "renormalized": stats})


@app.route("/api/admin/tag_dictionary/<int:did>", methods=["DELETE"])
@admin_required
def api_tag_dict_delete(did):
    row = db_fetchone("SELECT id, company_id FROM tag_dictionary WHERE id=?", (did,))
    if not row:
        return jsonify({"error": "词典项不存在"}), 404
    if session.get("role") != "super" and row["company_id"] != session.get("company_id"):
        return jsonify({"error": "无权操作"}), 403
    db_write("DELETE FROM tag_dictionary WHERE id=?", (did,))
    stats = renormalize_company_tags(row["company_id"])
    return jsonify({"ok": True, "renormalized": stats})


@app.route("/api/admin/tag_dictionary/<int:did>/merge", methods=["POST"])
@admin_required
def api_tag_dict_merge(did):
    """把 from_id 并入 did：from 的标准词+同义词都变成 did 的同义词，删除 from。"""
    data = request.get_json(silent=True) or {}
    from_id = data.get("from_id")
    tgt = db_fetchone("SELECT id, company_id, canonical_tag, synonyms FROM tag_dictionary WHERE id=?", (did,))
    src = db_fetchone("SELECT id, company_id, canonical_tag, synonyms FROM tag_dictionary WHERE id=?", (from_id,))
    if not tgt or not src:
        return jsonify({"error": "词典项不存在"}), 404
    if tgt["company_id"] != src["company_id"]:
        return jsonify({"error": "不能跨公司合并"}), 400
    if session.get("role") != "super" and tgt["company_id"] != session.get("company_id"):
        return jsonify({"error": "无权操作"}), 403
    def _parse(s):
        try:
            return json.loads(s or "[]") or []
        except (json.JSONDecodeError, TypeError):
            return []
    merged = _parse(tgt["synonyms"]) + [src["canonical_tag"]] + _parse(src["synonyms"])
    # 去重 + 去掉与目标标准词同名的
    seen, syns = set(), []
    for s in merged:
        s = (s or "").strip()
        if s and s != tgt["canonical_tag"] and s.lower() not in seen:
            seen.add(s.lower()); syns.append(s)
    db_write("UPDATE tag_dictionary SET synonyms=? WHERE id=?",
             (json.dumps(syns, ensure_ascii=False), did))
    db_write("DELETE FROM tag_dictionary WHERE id=?", (src["id"],))
    stats = renormalize_company_tags(tgt["company_id"])
    return jsonify({"ok": True, "renormalized": stats})


@app.route("/api/admin/tag_unclassified", methods=["GET"])
@admin_required
def api_tag_unclassified():
    """待归类池：canonical_tag 为空的原始标签，按服务次数(去重 session)排序。"""
    cid = _dict_company_id()
    rows = db_fetchall(
        """SELECT ct.tag AS tag,
                  COUNT(DISTINCT ct.source_session_id) AS service_count,
                  COUNT(*) AS row_count
           FROM customer_tags ct
           JOIN sessions s ON s.id = ct.source_session_id
           WHERE ct.canonical_tag IS NULL
             AND ((s.company_id IS NULL AND ?=1) OR s.company_id = ?)
           GROUP BY ct.tag
           ORDER BY service_count DESC, row_count DESC
           LIMIT 300""",
        (cid, cid),
    )
    return jsonify({"items": [dict(r) for r in rows], "company_id": cid})


@app.route("/api/admin/tag_unclassified/assign", methods=["POST"])
@admin_required
def api_tag_unclassified_assign():
    """把一个待归类原始标签处理掉：
    - action=merge: 加为 target_id 词典项的同义词
    - action=new:   新建标准词（category + canonical_tag，原词作同义词）
    - action=blacklist: 建一个黑名单词条（原词作标准词），该标签今后不再入库/统计"""
    data = request.get_json(silent=True) or {}
    cid = _dict_company_id()
    raw = (data.get("raw_tag") or "").strip()
    action = (data.get("action") or "").strip()
    if not raw:
        return jsonify({"error": "缺少 raw_tag"}), 400

    def _parse(s):
        try:
            return json.loads(s or "[]") or []
        except (json.JSONDecodeError, TypeError):
            return []

    if action == "merge":
        tid = data.get("target_id")
        row = db_fetchone("SELECT id, company_id, canonical_tag, synonyms FROM tag_dictionary WHERE id=?", (tid,))
        if not row or row["company_id"] != cid:
            return jsonify({"error": "目标标准词不存在"}), 404
        syns = _parse(row["synonyms"])
        if raw != row["canonical_tag"] and raw.lower() not in {x.lower() for x in syns}:
            syns.append(raw)
        db_write("UPDATE tag_dictionary SET synonyms=? WHERE id=?",
                 (json.dumps(syns, ensure_ascii=False), tid))
    elif action == "new":
        canon = (data.get("canonical_tag") or raw).strip()
        category = (data.get("category") or "其他").strip() or "其他"
        syns = [raw] if raw != canon else []
        try:
            db_write(
                "INSERT INTO tag_dictionary (company_id, category, canonical_tag, synonyms, status) VALUES (?, ?, ?, ?, 'active')",
                (cid, category, canon, json.dumps(syns, ensure_ascii=False)),
            )
        except sqlite3.IntegrityError:
            return jsonify({"error": f"标准词「{canon}」已存在，请改用合并"}), 409
    elif action == "blacklist":
        try:
            db_write(
                "INSERT INTO tag_dictionary (company_id, category, canonical_tag, synonyms, status) VALUES (?, '竞品', ?, '[]', 'blacklist')",
                (cid, raw),
            )
        except sqlite3.IntegrityError:
            db_write("UPDATE tag_dictionary SET status='blacklist' WHERE company_id=? AND canonical_tag=?", (cid, raw))
    else:
        return jsonify({"error": "未知 action"}), 400

    stats = renormalize_company_tags(cid)
    return jsonify({"ok": True, "renormalized": stats})


# ============ Wave5 AI 归并建议（审核后应用，不自动改库） ============

TOOL_TAG_MERGE_SUGGEST = {
    "name": "submit_tag_merge_groups",
    "description": "把同义/近义的碎片顾客标签聚成若干归并组，每组给出标准词、成员、分类。",
    "input_schema": {
        "type": "object",
        "required": ["groups"],
        "properties": {
            "groups": {
                "type": "array",
                "description": "归并组数组；只把语义相同/高度近义的碎片词放进同一组，"
                               "语义不同的词不要硬凑。单独成义、找不到同义伙伴的词可以不输出。",
                "items": {
                    "type": "object",
                    "required": ["canonical", "members", "category"],
                    "properties": {
                        "canonical": {
                            "type": "string",
                            "description": "该组最规范、最通用的标准词（<=10 字，从成员里选最好的，"
                                           "或归纳一个更标准的词）",
                        },
                        "members": {
                            "type": "array",
                            "items": {"type": "string"},
                            "description": "本组所有碎片词（必须原样来自给定候选词清单，不要改写、不要新增清单外的词）",
                        },
                        "category": {
                            "type": "string",
                            "enum": list(TAG_CATEGORIES),
                            "description": "归到 7 类之一：客人新老/顾客类型/顾客画像/顾客痛点/顾客状态/需求类型/竞品",
                        },
                    },
                },
            }
        },
    },
}

_TAG_MERGE_SYSTEM = (
    "你是医美/美容机构的顾客标签治理助手。给你一批同一公司里『尚未归一』的顾客标签碎片词"
    "（每个带出现次数），你的任务是把语义相同或高度近义的词聚成组，便于人工审核后合并为统一标准词。\n"
    "要求：\n"
    "1) 只把语义确实相同/高度近义的词放进同一组（如『怕痛体质/怕疼/忍痛弱/拒绝痛感』→ 同组）；"
    "语义不同的词不要硬凑一组。\n"
    "2) 每组的 canonical 选最规范、最通用的一个标准词（<=10 字）；members 必须原样来自候选清单。\n"
    "3) category 必须归到 7 类之一：客人新老/顾客类型/顾客画像/顾客痛点/顾客状态/需求类型/竞品。\n"
    "4) 只输出值得合并的组（成员>=2 个才有合并价值）；孤词、无同义伙伴的词不要输出。\n"
    "5) 必须调用 submit_tag_merge_groups 提交结构化结果。"
)


def _suggest_merge_company_id():
    """归并建议针对哪个公司（与 _dict_company_id 同口径）。"""
    return _dict_company_id()


@app.route("/api/admin/tags/suggest_merge", methods=["POST"])
@manager_required
def api_tags_suggest_merge():
    """对该公司 customer_tags 里 canonical_tag IS NULL 的碎片词，分批调 LLM 聚成归并组，
    写入 tag_merge_suggestions(status='pending')。不直接改 tag_dictionary/customer_tags。
    可带 ?category= 限定 LLM 归类倾向，并优先取与该分类词典相关的碎片词。

    成本约束（避免一次全量烧 ~16 批 LLM）：
      - max_batches: 单次最多处理多少批（默认 3），按出现次数高优先。
      - limit:       候选碎片词上限（默认 360）；与 max_batches 取更严的那个。
    返回里含 remaining（本次未处理的碎片词数），供前端提示"继续生成"。"""
    cid = _suggest_merge_company_id()
    body = request.get_json(silent=True) or {}
    only_category = (request.args.get("category")
                     or body.get("category") or "").strip()

    def _as_int(name, default, lo, hi):
        raw = request.args.get(name)
        if raw is None:
            raw = body.get(name)
        try:
            v = int(raw)
        except (TypeError, ValueError):
            return default
        return max(lo, min(hi, v))

    # 运营成本约束默认值：单次最多 3 批 / 360 个候选词
    MAX_BATCHES = _as_int("max_batches", 3, 1, 16)
    LIMIT = _as_int("limit", 360, 1, 5000)

    # 取碎片词：未归一(canonical 为空)的 distinct 原始 tag + 出现次数
    rows = db_fetchall(
        """SELECT ct.tag AS tag, COUNT(*) AS cnt
           FROM customer_tags ct
           JOIN sessions s ON s.id = ct.source_session_id
           WHERE (ct.canonical_tag IS NULL OR ct.canonical_tag='')
             AND ((s.company_id IS NULL AND ?=1) OR s.company_id = ?)
             AND ct.tag IS NOT NULL AND ct.tag <> ''
           GROUP BY ct.tag
           ORDER BY cnt DESC""",
        (cid, cid),
    )
    frag_all = [(r["tag"], r["cnt"]) for r in rows]
    if not frag_all:
        return jsonify({"ok": True, "groups_created": 0, "fragments": 0,
                        "remaining": 0, "message": "没有待归一的碎片标签"})

    # 传了 category 时：优先把与该分类词典相关的碎片词排到前面，让"按分类分批"真正可行。
    # 相关性判定：碎片词与该分类下 tag_dictionary 的 canonical/synonyms 互相包含（子串）。
    if only_category:
        cat_rows = db_fetchall(
            "SELECT canonical_tag, synonyms FROM tag_dictionary "
            "WHERE company_id=? AND category=? AND status='active'",
            (cid, only_category),
        )
        cat_terms = set()
        for r in cat_rows:
            ct = (r["canonical_tag"] or "").strip()
            if ct:
                cat_terms.add(ct)
            try:
                for s in (json.loads(r["synonyms"] or "[]") or []):
                    s = (s or "").strip() if isinstance(s, str) else ""
                    if s:
                        cat_terms.add(s)
            except (ValueError, TypeError):
                pass

        def _cat_related(tag):
            if not cat_terms:
                return False
            for term in cat_terms:
                if term and (term in tag or tag in term):
                    return True
            return False

        if cat_terms:
            # 稳定排序：相关词在前（仍按出现次数降序），其余保持原顺序在后
            frag_all.sort(key=lambda tc: (0 if _cat_related(tc[0]) else 1, -tc[1]))

    total_frag = len(frag_all)
    cnt_map = {t: c for t, c in frag_all}

    # 应用成本上限：先按候选词上限截断，再限制批数。
    BATCH = 120
    frag = frag_all[:LIMIT]
    frag = frag[:MAX_BATCHES * BATCH]
    processed = len(frag)
    remaining = total_frag - processed

    model = DEFAULT_MODEL
    created = 0
    batches = 0
    errors = []

    for i in range(0, len(frag), BATCH):
        batch = frag[i:i + BATCH]
        batches += 1
        word_lines = "\n".join(f"- {t}（出现 {c} 次）" for t, c in batch)
        hint = (f"\n本批请尽量往『{only_category}』这一类归。\n" if only_category else "")
        user_prompt = (
            f"候选碎片标签清单（共 {len(batch)} 个，只能用清单里的原词作为 members）：\n"
            f"{word_lines}\n{hint}\n"
            "请把同义/近义的词聚成组并调用工具提交。"
        )
        try:
            result = _call_llm_with_retry(
                model, _TAG_MERGE_SYSTEM, user_prompt,
                tool=TOOL_TAG_MERGE_SUGGEST, max_tokens=8000,
                stage_label=f"tag_merge_suggest_b{batches}", max_attempts=3,
            )
        except Exception as e:
            errors.append(str(e))
            print(f"[suggest_merge] cid={cid} batch{batches} 失败: {e}")
            continue

        groups = (result or {}).get("groups") or []
        batch_word_set = {t for t, _ in batch}
        to_insert = []
        for g in groups:
            if not isinstance(g, dict):
                continue
            canon = (g.get("canonical") or "").strip()
            cat = (g.get("category") or "其他").strip() or "其他"
            if cat not in TAG_CATEGORIES:
                cat = "其他"
            members_raw = g.get("members") or []
            # 只保留确实来自本批候选词的成员，去重
            seen, members = set(), []
            for m in members_raw:
                m = (m or "").strip() if isinstance(m, str) else ""
                if m and m in batch_word_set and m.lower() not in seen:
                    seen.add(m.lower()); members.append(m)
            # 没选 canonical 时退化为出现最多的成员
            if not canon and members:
                canon = max(members, key=lambda x: cnt_map.get(x, 0))
            # 至少 2 个成员才有合并价值
            if not canon or len(members) < 2:
                continue
            sample = sum(cnt_map.get(m, 0) for m in members)
            to_insert.append((cid, cat, canon,
                              json.dumps(members, ensure_ascii=False), sample))

        if to_insert:
            def _ins(conn, rows=to_insert):
                conn.executemany(
                    "INSERT INTO tag_merge_suggestions "
                    "(company_id, category, canonical, members_json, sample_count, status) "
                    "VALUES (?, ?, ?, ?, ?, 'pending')",
                    rows,
                )
                conn.commit()
            db_batch(_ins)
            created += len(to_insert)

    resp = {"ok": True, "company_id": cid,
            "fragments": total_frag,        # 待归一碎片词总数
            "processed": processed,         # 本次实际送入 LLM 的候选词数
            "remaining": remaining,         # 尚未处理的碎片词数，供前端"继续生成"
            "batches": batches,
            "max_batches": MAX_BATCHES, "limit": LIMIT,
            "category": only_category or None,
            "groups_created": created}
    if errors:
        resp["errors"] = errors[:5]
    return jsonify(resp)


@app.route("/api/admin/tags/suggestions", methods=["GET"])
@manager_required
def api_tags_suggestions_list():
    """列出归并建议组。默认 status=pending；可传 ?status=applied|rejected|all。"""
    cid = _suggest_merge_company_id()
    status = (request.args.get("status") or "pending").strip()
    where = ["company_id=?"]
    params = [cid]
    if status != "all":
        where.append("status=?"); params.append(status)
    rows = db_fetchall(
        "SELECT id, category, canonical, members_json, sample_count, status, "
        "created_at, applied_at FROM tag_merge_suggestions "
        f"WHERE {' AND '.join(where)} "
        "ORDER BY (status='pending') DESC, sample_count DESC, id DESC",
        tuple(params),
    )
    out = []
    for r in rows:
        d = dict(r)
        try:
            d["members"] = json.loads(r["members_json"] or "[]") or []
        except (json.JSONDecodeError, TypeError):
            d["members"] = []
        d.pop("members_json", None)
        out.append(d)
    return jsonify({"company_id": cid, "status": status, "suggestions": out})


def _upsert_tag_dict_with_members(cid, category, canonical, members):
    """把一组归并应用进 tag_dictionary：canonical 为标准词，members 并入其 synonyms。
    - canonical 已存在 → 合并 members 进 synonyms（去重、剔除与标准词同名）；如原为黑名单则转 active。
    - 不存在 → 新建 active 词条。
    members 中若已有别的 active 标准词条，会被吸收为同义词（删除该独立词条）。"""
    def _parse(s):
        try:
            return json.loads(s or "[]") or []
        except (json.JSONDecodeError, TypeError):
            return []

    # members 里若本身是其它标准词条 → 收编（把它们的同义词也搬过来，再删掉）
    extra_syn = []
    for m in members:
        if m == canonical:
            continue
        row = db_fetchone(
            "SELECT id, synonyms FROM tag_dictionary WHERE company_id=? AND canonical_tag=?",
            (cid, m),
        )
        if row:
            extra_syn.extend(_parse(row["synonyms"]))
            db_write("DELETE FROM tag_dictionary WHERE id=?", (row["id"],))

    tgt = db_fetchone(
        "SELECT id, synonyms FROM tag_dictionary WHERE company_id=? AND canonical_tag=?",
        (cid, canonical),
    )
    all_syn = (_parse(tgt["synonyms"]) if tgt else []) + list(members) + extra_syn
    seen, syns = set(), []
    for s in all_syn:
        s = (s or "").strip()
        if s and s != canonical and s.lower() not in seen:
            seen.add(s.lower()); syns.append(s)
    syn_json = json.dumps(syns, ensure_ascii=False)

    if tgt:
        db_write(
            "UPDATE tag_dictionary SET synonyms=?, category=?, status='active' WHERE id=?",
            (syn_json, category, tgt["id"]),
        )
    else:
        try:
            db_write(
                "INSERT INTO tag_dictionary (company_id, category, canonical_tag, synonyms, status) "
                "VALUES (?, ?, ?, ?, 'active')",
                (cid, category, canonical, syn_json),
            )
        except sqlite3.IntegrityError:
            # 并发或大小写差异：退回 UPDATE
            db_write(
                "UPDATE tag_dictionary SET synonyms=?, category=?, status='active' "
                "WHERE company_id=? AND canonical_tag=?",
                (syn_json, category, cid, canonical),
            )


@app.route("/api/admin/tags/suggestions/<int:sid>/apply", methods=["POST"])
@manager_required
def api_tags_suggestion_apply(sid):
    """审核通过一条归并建议：upsert 进 tag_dictionary，再 renormalize 回填 customer_tags。
    可在 body 传 canonical/members/category 覆盖建议内容（管理员编辑后应用）。"""
    row = db_fetchone(
        "SELECT id, company_id, category, canonical, members_json, status "
        "FROM tag_merge_suggestions WHERE id=?", (sid,))
    if not row:
        return jsonify({"error": "建议不存在"}), 404
    cid = row["company_id"]
    if session.get("role") != "super" and cid != session.get("company_id"):
        return jsonify({"error": "无权操作"}), 403
    if row["status"] == "applied":
        return jsonify({"error": "该建议已应用"}), 409

    data = request.get_json(silent=True) or {}
    canonical = (data.get("canonical") or row["canonical"] or "").strip()
    category = (data.get("category") or row["category"] or "其他").strip() or "其他"
    if category not in TAG_CATEGORIES:
        category = "其他"
    if "members" in data and isinstance(data["members"], list):
        members_src = data["members"]
    else:
        try:
            members_src = json.loads(row["members_json"] or "[]") or []
        except (json.JSONDecodeError, TypeError):
            members_src = []
    seen, members = set(), []
    for m in members_src:
        m = (m or "").strip() if isinstance(m, str) else ""
        if m and m.lower() not in seen:
            seen.add(m.lower()); members.append(m)
    if not canonical:
        return jsonify({"error": "缺少 canonical 标准词"}), 400

    _upsert_tag_dict_with_members(cid, category, canonical, members)
    stats = renormalize_company_tags(cid)  # 内部已 invalidate_tag_dict
    db_write(
        "UPDATE tag_merge_suggestions SET status='applied', "
        "category=?, canonical=?, members_json=?, applied_at=datetime('now','localtime') "
        "WHERE id=?",
        (category, canonical, json.dumps(members, ensure_ascii=False), sid),
    )
    return jsonify({"ok": True, "applied": {"canonical": canonical,
                    "category": category, "members": members},
                    "renormalized": stats})


@app.route("/api/admin/tags/suggestions/<int:sid>/reject", methods=["POST"])
@manager_required
def api_tags_suggestion_reject(sid):
    """拒绝一条归并建议（不改词典/标签）。"""
    row = db_fetchone(
        "SELECT id, company_id, status FROM tag_merge_suggestions WHERE id=?", (sid,))
    if not row:
        return jsonify({"error": "建议不存在"}), 404
    if session.get("role") != "super" and row["company_id"] != session.get("company_id"):
        return jsonify({"error": "无权操作"}), 403
    if row["status"] == "applied":
        return jsonify({"error": "已应用的建议不能拒绝"}), 409
    db_write("UPDATE tag_merge_suggestions SET status='rejected' WHERE id=?", (sid,))
    return jsonify({"ok": True})


@app.route("/api/admin/tag_stats")
@manager_required
def api_admin_tag_stats():
    """标签统计：scope=customer(顾客标签/店里流行什么) | competitor(竞品纵览)。
    口径：按 COALESCE(canonical_tag,tag) 归一，服务次数 = COUNT(DISTINCT source_session_id)。
    支持门店(store_manager锁本店/admin可选) + 起止日期(service_date) 筛选。"""
    scope = (request.args.get("scope") or "customer").strip()
    is_super = session.get("role") == "super"
    EFF = "COALESCE(NULLIF(ct.canonical_tag,''), ct.tag)"
    # 竞品判定：已归竞品类，或 待归类(无词典项)且以"用户"结尾（兼容存量未归类的竞品标签）
    # 用 COALESCE 避免 d.category 为 NULL 时三值逻辑把"待归类顾客标签"误排除
    COMP = "(COALESCE(d.category,'')='竞品' OR (d.id IS NULL AND ct.tag LIKE '%用户'))"

    where = ["s.service_date LIKE '____-__-__'", "COALESCE(d.status,'active') <> 'blacklist'"]
    params = []
    if not is_super:
        where.append("(s.company_id IS NULL OR s.company_id=?)")
        params.append(session.get("company_id"))
    elif request.args.get("company_id"):
        where.append("s.company_id=?"); params.append(int(request.args["company_id"]))
    sf = current_store_filter()
    if sf is not None:
        where.append("s.store_id=?"); params.append(sf)
    frm = (request.args.get("from") or "").strip()
    to = (request.args.get("to") or "").strip()
    if frm:
        where.append("s.service_date >= ?"); params.append(frm)
    if to:
        where.append("s.service_date <= ?"); params.append(to)
    where.append(COMP if scope == "competitor" else f"NOT {COMP}")

    # 客人数口径：优先按 customer_id 去重；customer_id 为空的行回退按 customer_name。
    # 用前缀拼键避免 id 与 name 命名空间冲突（Wave2 customer_id 仅部分回填，故需回退）。
    CUST = "COALESCE('I'||ct.customer_id, 'N'||ct.customer_name)"
    rows = db_fetchall(
        f"""SELECT {EFF} AS tag, MAX(d.category) AS category,
                   COUNT(DISTINCT ct.source_session_id) AS service_count,
                   COUNT(DISTINCT {CUST}) AS customer_count
            FROM customer_tags ct
            JOIN sessions s ON s.id = ct.source_session_id
            LEFT JOIN tag_dictionary d
                   ON d.company_id = s.company_id AND d.canonical_tag = {EFF}
            WHERE {' AND '.join(where)}
            GROUP BY {EFF}
            ORDER BY service_count DESC, customer_count DESC
            LIMIT 200""",
        tuple(params),
    )
    return jsonify({"scope": scope, "rows": [dict(r) for r in rows]})


# ============ 画像-4 当月重点项目 ============
def _this_month():
    return datetime.now().strftime("%Y-%m")


@app.route("/api/admin/monthly_projects", methods=["GET"])
@manager_required
def api_monthly_projects_list():
    cid = session.get("company_id") or 1
    if session.get("role") == "super":
        cid = int(request.args.get("company_id") or cid)
    month = (request.args.get("month") or _this_month()).strip()
    rows = db_fetchall(
        "SELECT id, month, name, keywords FROM monthly_projects WHERE company_id=? AND month=? ORDER BY id",
        (cid, month))
    out = []
    for r in rows:
        d = dict(r)
        try:
            d["keywords"] = json.loads(r["keywords"] or "[]") or []
        except (json.JSONDecodeError, TypeError):
            d["keywords"] = []
        out.append(d)
    return jsonify({"month": month, "projects": out})


@app.route("/api/admin/monthly_projects", methods=["POST"])
@admin_required
def api_monthly_projects_create():
    data = request.get_json(silent=True) or {}
    cid = session.get("company_id") or 1
    if session.get("role") == "super" and data.get("company_id"):
        cid = int(data["company_id"])
    month = (data.get("month") or _this_month()).strip()
    name = (data.get("name") or "").strip()
    kws = data.get("keywords") or []
    kws = [k.strip() for k in kws if isinstance(k, str) and k.strip()]
    if not name:
        return jsonify({"error": "项目名必填"}), 400
    if not kws:
        kws = [name]  # 没填关键词就用项目名做命中词
    pid = db_write(
        "INSERT INTO monthly_projects (company_id, month, name, keywords) VALUES (?, ?, ?, ?)",
        (cid, month, name, json.dumps(kws, ensure_ascii=False)))
    return jsonify({"id": pid})


@app.route("/api/admin/monthly_projects/<int:pid>", methods=["DELETE"])
@admin_required
def api_monthly_projects_delete(pid):
    row = db_fetchone("SELECT id, company_id FROM monthly_projects WHERE id=?", (pid,))
    if not row:
        return jsonify({"error": "不存在"}), 404
    if session.get("role") != "super" and row["company_id"] != session.get("company_id"):
        return jsonify({"error": "无权操作"}), 403
    db_write("DELETE FROM monthly_projects WHERE id=?", (pid,))
    return jsonify({"ok": True})


def _project_hits(transcript, projects):
    """transcript 命中了 projects 里哪些项目（关键词子串匹配）。projects: [{name, keywords}]。"""
    if not transcript:
        return []
    low = transcript.lower()
    hits = []
    for p in projects:
        for kw in (p.get("keywords") or []):
            if kw and kw.lower() in low:
                hits.append(p["name"])
                break
    return hits


# ============ 画像-3 顾客档案 ============
def _customer_session_clause(customer_id, name, company_id):
    """匹配某客人的所有 session：优先 customer_id，兼容旧的按姓名。"""
    clause = "((s.customer_id IS NOT NULL AND s.customer_id=?) OR (s.customer_id IS NULL AND s.customer=? AND (s.company_id IS NULL OR s.company_id=?)))"
    return clause, [customer_id, name, company_id]


@app.route("/api/admin/customer_profile")
@login_required  # P1.7：档案"看"对所有登录角色开放；下方 company_id 作用域仍生效（顾问不能跨公司，super 跨公司）
def api_customer_profile():
    """客人粒度档案：基本信息 + 累积标签(按服务次数,可时间区间) + 服务时间线(每次接诊:日期/顾问/标签/当月项目命中)。"""
    cid = session.get("company_id")
    is_super = session.get("role") == "super"
    try:
        customer_id = int(request.args.get("customer_id"))
    except (TypeError, ValueError):
        return jsonify({"error": "缺少 customer_id"}), 400
    cust = db_fetchone(
        "SELECT id, company_id, name, member_card, phone_tail FROM company_customers WHERE id=?",
        (customer_id,))
    if not cust:
        return jsonify({"error": "顾客不存在"}), 404
    if not is_super and cust["company_id"] != cid:
        return jsonify({"error": "无权查看"}), 403
    comp = cust["company_id"]

    sclause, sparams = _customer_session_clause(customer_id, cust["name"], comp)
    where = [sclause]
    params = list(sparams)
    sc, scp = store_scope_sql("s.store_id")
    if sc:
        where.append(sc); params.extend(scp)
    sess_rows = db_fetchall(
        f"""SELECT s.id, s.service_date, s.advisor, s.store_id, s.analysis_status,
                   st.name AS store_name
            FROM sessions s LEFT JOIN stores st ON st.id=s.store_id
            WHERE {' AND '.join(where)}
            ORDER BY s.service_date DESC, s.id DESC""",
        tuple(params))
    sess_ids = [r["id"] for r in sess_rows]

    # 当月项目：按各 session 所属月份加载项目清单
    months = {(r["service_date"] or "")[:7] for r in sess_rows if r["service_date"]}
    proj_by_month = {}
    if months:
        qmarks = ",".join("?" * len(months))
        prows = db_fetchall(
            f"SELECT month, name, keywords FROM monthly_projects WHERE company_id=? AND month IN ({qmarks})",
            tuple([comp] + list(months)))
        for pr in prows:
            try:
                kws = json.loads(pr["keywords"] or "[]") or []
            except (json.JSONDecodeError, TypeError):
                kws = []
            proj_by_month.setdefault(pr["month"], []).append({"name": pr["name"], "keywords": kws})

    EFF = "COALESCE(NULLIF(canonical_tag,''), tag)"
    sessions = []
    for r in sess_rows:
        sid = r["id"]
        tag_rows = db_fetchall(
            f"SELECT DISTINCT {EFF} AS t FROM customer_tags WHERE source_session_id=?", (sid,))
        tags = [x["t"] for x in tag_rows if x["t"]]
        # 当月项目命中
        month = (r["service_date"] or "")[:7]
        hits = []
        projs = proj_by_month.get(month)
        if projs:
            tr_rows = db_fetchall("SELECT asr_transcript FROM recordings WHERE session_id=?", (sid,))
            transcript = "\n".join((x["asr_transcript"] or "") for x in tr_rows)
            hits = _project_hits(transcript, projs)
        sessions.append({
            "id": sid, "service_date": r["service_date"], "advisor": r["advisor"],
            "store_name": r["store_name"], "analysis_status": r["analysis_status"],
            "tags": tags, "project_hits": hits,
        })

    # 累积标签（按服务次数，时间区间）
    rng = (request.args.get("range") or "all").strip()
    days_map = {"30": 30, "90": 90, "180": 180, "365": 365}
    acc = []
    if sess_ids:
        qmarks = ",".join("?" * len(sess_ids))
        tw = [f"source_session_id IN ({qmarks})"]
        tp = list(sess_ids)
        if rng in days_map:
            cutoff = (datetime.now() - timedelta(days=days_map[rng])).strftime("%Y-%m-%d")
            tw.append("service_date IS NOT NULL AND service_date >= ?"); tp.append(cutoff)
        acc_rows = db_fetchall(
            f"""SELECT {EFF} AS t, COUNT(DISTINCT source_session_id) AS cnt
                FROM customer_tags WHERE {' AND '.join(tw)}
                GROUP BY {EFF} ORDER BY cnt DESC, MAX(created_at) DESC LIMIT 80""",
            tuple(tp))
        acc = [{"tag": x["t"], "count": x["cnt"]} for x in acc_rows if x["t"]]

    return jsonify({
        "info": {"id": cust["id"], "name": cust["name"],
                 "member_card": cust["member_card"], "phone_tail": cust["phone_tail"]},
        "accumulated_tags": acc, "sessions": sessions,
        "session_count": len(sess_ids), "range": rng,
    })


# ============ 画像-5 客户价值预测（按需生成 + 缓存）============
VALUE_TOOL = {
    "name": "customer_value",
    "description": "基于历史接待综合产出的客户价值多维分析",
    "input_schema": {
        "type": "object",
        "properties": {
            "value_rebuild": {"type": "string", "description": "客户价值评估：基于历次真实画像重建，这个客人的价值、消费潜力、忠诚度判断"},
            "battle_plan": {"type": "string", "description": "可攻破痛点 + 完整作战方案：下次怎么攻坚这个客人"},
            "project_plan": {"type": "string", "description": "竞品分析 + 曾做过/可做的项目 + 顾问售后学习清单"},
            "biz_plan": {"type": "string", "description": "下一步动作 + 回店规划（经营规划）"},
            "advisor_match": {
                "type": "array",
                "description": "按接待过的顾问分别评估：该顾问与这个客人的匹配度（喜不喜欢、接得怎么样）",
                "items": {"type": "object", "properties": {
                    "advisor": {"type": "string"}, "assessment": {"type": "string"}},
                    "required": ["advisor", "assessment"]},
            },
        },
        "required": ["value_rebuild", "battle_plan", "project_plan", "biz_plan", "advisor_match"],
    },
}

# 手动生成 / 自动生成 共用同一段 system prompt，保证两条路输出一致
_VALUE_GEN_SYS_PROMPT = (
    "你是高端医美/美容院的客户经营顾问。基于该客人历次接待的分析记录，"
    "综合产出对这个客人的价值评估与经营规划。要具体、可执行，不要空话套话。"
    "按顾问分别评估匹配度时，只评估实际接待过的顾问。\n"
    "【输出格式要求】内容给不懂技术的老板看，每个字段都用以下轻量 markdown，保持简洁、重点突出：\n"
    "- 用 `## 小标题` 分小节（每字段 2-4 个小节即可，不要长篇大论）；\n"
    "- 关键结论用 `**加粗**`；要点用 `- ` 列表，步骤用 `1. ` 编号；\n"
    "- 涉及评级/程度时用 ★ 星级（如 消费力 ★★★★☆）；\n"
    "- 每个要点一句话讲透，避免大段文字堆砌。")


def _value_signature(sess_rows):
    """历史已分析 session 的指纹：id+完成时间。变了则缓存过期。"""
    parts = [f"{r['id']}:{r['analysis_finished_at'] or ''}" for r in sess_rows]
    return hashlib.md5("|".join(parts).encode("utf-8")).hexdigest()


def _gather_value_input(sess_rows):
    """把客人历次 done 分析的关键字段拼成 LLM 输入（控制 token，取近 20 次）。"""
    blocks = []
    for r in sess_rows[:20]:
        try:
            ar = json.loads(r["analysis_result"] or "null") or {}
        except (json.JSONDecodeError, TypeError):
            ar = {}
        persona = (ar.get("persona") or {})
        diag = (ar.get("deal_diagnosis") or {})
        pains = ar.get("pain_points") or {}
        ext = ar.get("external_signals") or {}
        seg = [f"### {r['service_date'] or '?'} · 顾问：{r['advisor'] or '?'}"]
        if persona.get("summary"):
            seg.append(f"画像：{persona['summary']}")
        if pains:
            seg.append(f"痛点：{json.dumps(pains, ensure_ascii=False)[:600]}")
        if diag:
            seg.append(f"成交诊断：成交={diag.get('deal_result')} 金额={diag.get('deal_amount')} 风险={diag.get('risk_level')}")
        if ext:
            seg.append(f"外部信号/竞品：{json.dumps(ext, ensure_ascii=False)[:500]}")
        blocks.append("\n".join(seg))
    return "\n\n".join(blocks)


def _load_customer_done_sessions(customer_id, name, company_id):
    sclause, sparams = _customer_session_clause(customer_id, name, company_id)
    return db_fetchall(
        f"""SELECT s.id, s.service_date, s.advisor, s.analysis_status,
                   s.analysis_finished_at, s.analysis_result
            FROM sessions s
            WHERE {sclause} AND s.analysis_status='done' AND s.analysis_result IS NOT NULL
            ORDER BY s.service_date DESC, s.id DESC""",
        tuple(sparams))


@app.route("/api/admin/customer_value", methods=["GET"])
@login_required  # P1.7：价值预测/作战方案"看"对所有登录角色开放；company_id 作用域仍在下方收口
def api_customer_value_get():
    cid = session.get("company_id")
    is_super = session.get("role") == "super"
    try:
        customer_id = int(request.args.get("customer_id"))
    except (TypeError, ValueError):
        return jsonify({"error": "缺少 customer_id"}), 400
    cust = db_fetchone("SELECT id, company_id, name FROM company_customers WHERE id=?", (customer_id,))
    if not cust:
        return jsonify({"error": "顾客不存在"}), 404
    if not is_super and cust["company_id"] != cid:
        return jsonify({"error": "无权查看"}), 403
    done = _load_customer_done_sessions(customer_id, cust["name"], cust["company_id"])
    cur_sig = _value_signature(done) if done else None
    cache = db_fetchone(
        "SELECT content, source_signature, model, generated_at FROM customer_value_cache "
        "WHERE company_id=? AND customer_id=?", (cust["company_id"], customer_id))
    content = None
    if cache and cache["content"]:
        try:
            content = json.loads(cache["content"])
        except (json.JSONDecodeError, TypeError):
            content = None
    stale = bool(cache) and cache["source_signature"] != cur_sig
    return jsonify({
        "content": content,
        "generated_at": cache["generated_at"] if cache else None,
        "model": cache["model"] if cache else None,
        "stale": stale,
        "done_count": len(done),
        "has_cache": content is not None,
    })


def _value_generate_and_store(cust, done, model):
    """核心：调一次 LLM 生成价值预测并 upsert 缓存。手动按钮与自动路径共用。
    返回 (result_dict, generated_at)；LLM 结果异常返回 (None, None)。不做并发去重/成本闸（由调用方决定）。"""
    name = cust["name"]
    company_id = cust["company_id"]
    customer_id = cust["id"]
    user_prompt = (
        f"客人：{name}（历史接待 {len(done)} 次）\n\n"
        f"以下是历次接待的关键分析：\n\n{_gather_value_input(done)}")
    result = _call_llm_with_retry(model, _VALUE_GEN_SYS_PROMPT, user_prompt, tool=VALUE_TOOL,
                                  max_tokens=6000, stage_label="客户价值预测")
    if not isinstance(result, dict):
        return None, None
    sig = _value_signature(done)
    now = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    db_write(
        """INSERT INTO customer_value_cache (company_id, customer_id, customer_name, content, source_signature, model, generated_at)
           VALUES (?, ?, ?, ?, ?, ?, ?)
           ON CONFLICT(company_id, customer_id, customer_name)
           DO UPDATE SET content=excluded.content, source_signature=excluded.source_signature,
                         model=excluded.model, generated_at=excluded.generated_at""",
        (company_id, customer_id, name, json.dumps(result, ensure_ascii=False),
         sig, model, now))
    return result, now


@app.route("/api/admin/customer_value", methods=["POST"])
@login_required  # 价值预测"点击即生成"对所有登录角色开放（含顾问）；company_id 作用域仍在下方收口。自动生成也在后台跑。
def api_customer_value_generate():
    """生成/刷新客户价值预测（强制重算）。任何登录用户可对本公司顾客触发；接诊分析完成后台亦自动重算。"""
    data = request.get_json(silent=True) or {}
    cid = session.get("company_id")
    is_super = session.get("role") == "super"
    try:
        customer_id = int(data.get("customer_id"))
    except (TypeError, ValueError):
        return jsonify({"error": "缺少 customer_id"}), 400
    cust = db_fetchone("SELECT id, company_id, name FROM company_customers WHERE id=?", (customer_id,))
    if not cust:
        return jsonify({"error": "顾客不存在"}), 404
    if not is_super and cust["company_id"] != cid:
        return jsonify({"error": "无权操作"}), 403
    done = _load_customer_done_sessions(customer_id, cust["name"], cust["company_id"])
    if not done:
        return jsonify({"error": "该客人还没有已完成的接诊分析，无法生成"}), 400

    model = (data.get("model") or DEFAULT_MODEL)
    if model not in MODEL_PROVIDER:
        model = DEFAULT_MODEL
    try:
        result, now = _value_generate_and_store(dict(cust), done, model)
    except Exception as e:
        return jsonify({"error": f"生成失败：{str(e)[:200]}"}), 502
    if not isinstance(result, dict):
        return jsonify({"error": "生成结果异常"}), 502
    return jsonify({"content": result, "generated_at": now, "model": model, "stale": False})


# ─── 画像-5 自动生成：即时触发（接诊完成）+ 后台扫描兜底 ───────────────────
# 用户选定：不设每日上限、即时+扫描兜底。成本闸保留为可调环境变量（默认 0=不限）。
VALUE_AUTOGEN_ENABLED = os.environ.get("VALUE_AUTOGEN_ENABLED", "1") != "0"
VALUE_AUTOGEN_CONCURRENCY = max(1, int(os.environ.get("VALUE_AUTOGEN_CONCURRENCY", "3")))
VALUE_AUTOGEN_INTERVAL = max(60, int(os.environ.get("VALUE_AUTOGEN_INTERVAL", "600")))  # 扫描周期(秒)
VALUE_AUTOGEN_DAILY_CAP = int(os.environ.get("VALUE_AUTOGEN_DAILY_CAP", "0"))  # 0=不限
_value_inflight_lock = threading.Lock()
_value_inflight = set()  # {(company_id, customer_id)} 正在生成，避免即时触发与扫描重复
_value_gen_semaphore = threading.Semaphore(VALUE_AUTOGEN_CONCURRENCY)  # 自动路径全局并发上限
_value_cap_lock = threading.Lock()
_value_cap_state = {"date": "", "count": 0}


def _value_daily_cap_ok():
    if VALUE_AUTOGEN_DAILY_CAP <= 0:
        return True
    today = datetime.now().strftime("%Y-%m-%d")
    with _value_cap_lock:
        if _value_cap_state["date"] != today:
            _value_cap_state["date"], _value_cap_state["count"] = today, 0
        return _value_cap_state["count"] < VALUE_AUTOGEN_DAILY_CAP


def _value_daily_cap_inc():
    if VALUE_AUTOGEN_DAILY_CAP <= 0:
        return
    today = datetime.now().strftime("%Y-%m-%d")
    with _value_cap_lock:
        if _value_cap_state["date"] != today:
            _value_cap_state["date"], _value_cap_state["count"] = today, 0
        _value_cap_state["count"] += 1


def _value_autogen_one(cust, model=None):
    """自动生成一个客人的价值预测：并发去重 + 仅在缓存缺失/过期时生成 + 成本闸 + 并发上限。
    cust: dict(id, company_id, name)。返回 result 或 None（跳过/失败）。"""
    if not VALUE_AUTOGEN_ENABLED:
        return None
    customer_id, company_id, name = cust["id"], cust["company_id"], cust["name"]
    key = (company_id, customer_id)
    with _value_inflight_lock:
        if key in _value_inflight:
            return None
        _value_inflight.add(key)
    try:
        done = _load_customer_done_sessions(customer_id, name, company_id)
        if not done:
            return None
        sig = _value_signature(done)
        cache = db_fetchone(
            "SELECT source_signature FROM customer_value_cache WHERE company_id=? AND customer_id=?",
            (company_id, customer_id))
        if cache and cache["source_signature"] == sig:
            return None  # 已是最新，免烧
        if not _value_daily_cap_ok():
            print(f"[value_autogen] 达每日上限({VALUE_AUTOGEN_DAILY_CAP})，跳过 customer={customer_id}")
            return None
        mdl = model or DEFAULT_MODEL
        if mdl not in MODEL_PROVIDER:
            mdl = DEFAULT_MODEL
        with _value_gen_semaphore:
            result, _now = _value_generate_and_store(cust, done, mdl)
        if isinstance(result, dict):
            _value_daily_cap_inc()
            print(f"[value_autogen] 已生成 customer={customer_id}（{name}）")
            return result
        return None
    except Exception as e:
        print(f"[value_autogen] customer={customer_id} 生成失败: {e}")
        return None
    finally:
        with _value_inflight_lock:
            _value_inflight.discard(key)


def _trigger_value_after_analysis(session_id):
    """接诊分析完成后，异步给该客人重算价值预测（散客/无档案则跳过）。"""
    if not VALUE_AUTOGEN_ENABLED:
        return
    try:
        s = db_fetchone("SELECT customer_id, customer, company_id FROM sessions WHERE id=?", (session_id,))
        if not s:
            return
        company_id = s["company_id"] or 1
        cust = None
        if s["customer_id"]:
            cust = db_fetchone(
                "SELECT id, company_id, name FROM company_customers WHERE id=? AND merged_into IS NULL",
                (s["customer_id"],))
        if not cust and s["customer"]:
            cust = db_fetchone(
                "SELECT id, company_id, name FROM company_customers WHERE name=? AND company_id=? AND merged_into IS NULL",
                (s["customer"], company_id))
        if not cust:
            return
        threading.Thread(target=_value_autogen_one, args=(dict(cust),), daemon=True,
                         name=f"value-gen-s{session_id}").start()
    except Exception as e:
        print(f"[value_autogen] 触发失败 session={session_id}: {e}")


def _value_autogen_candidates():
    """扫描"有 done 接诊、但价值缓存缺失或已过期(signature 变了)"的客人。"""
    rows = db_fetchall(
        """SELECT id, company_id, name FROM company_customers cc
           WHERE merged_into IS NULL AND EXISTS (
             SELECT 1 FROM sessions s
             WHERE s.analysis_status='done' AND s.analysis_result IS NOT NULL
               AND ((s.customer_id IS NOT NULL AND s.customer_id=cc.id)
                 OR (s.customer_id IS NULL AND s.customer=cc.name
                     AND (s.company_id IS NULL OR s.company_id=cc.company_id))))""")
    if not rows:
        return []
    cache_sigs = {cr["customer_id"]: cr["source_signature"]
                  for cr in db_fetchall("SELECT customer_id, source_signature FROM customer_value_cache")}
    out = []
    for r in rows:
        sclause, sparams = _customer_session_clause(r["id"], r["name"], r["company_id"])
        done_lite = db_fetchall(
            f"""SELECT s.id, s.analysis_finished_at FROM sessions s
                WHERE {sclause} AND s.analysis_status='done' AND s.analysis_result IS NOT NULL
                ORDER BY s.service_date DESC, s.id DESC""", tuple(sparams))
        if not done_lite:
            continue
        if cache_sigs.get(r["id"]) != _value_signature(done_lite):
            out.append({"id": r["id"], "company_id": r["company_id"], "name": r["name"]})
    return out


def _value_autogen_loop():
    """后台扫描兜底：周期性补齐缺失/过期的价值预测。即时触发漏掉的、历史存量都在这里补。"""
    import time as _t
    import concurrent.futures as _cf
    _t.sleep(60)  # 启动后等服务稳了再开扫，避免和 startup_kick/接诊分析抢资源
    while True:
        try:
            cands = _value_autogen_candidates()
            if cands:
                print(f"[value_autogen] 扫描发现 {len(cands)} 个待生成/过期客人，开始补齐…")
                with _cf.ThreadPoolExecutor(max_workers=VALUE_AUTOGEN_CONCURRENCY,
                                            thread_name_prefix="value-sweep") as ex:
                    list(ex.map(_value_autogen_one, cands))
                print("[value_autogen] 本轮扫描补齐完成")
        except Exception as e:
            print(f"[value_autogen] 扫描循环出错: {e}")
        _t.sleep(VALUE_AUTOGEN_INTERVAL)


if VALUE_AUTOGEN_ENABLED:
    threading.Thread(target=_value_autogen_loop, daemon=True, name="value-autogen").start()


# ============ 顾客下拉搜索（顾问/管理员通用） ============
@app.route("/api/customers/search")
@login_required
def api_customers_search():
    q = (request.args.get("q") or "").strip()
    cid = session.get("company_id") or 1
    sql = "SELECT id, name, member_card FROM company_customers WHERE company_id=? AND merged_into IS NULL"
    params = [cid]
    if q:
        sql += " AND (name LIKE ? OR member_card LIKE ?)"
        params += [f"%{q}%", f"%{q}%"]
    sql += " ORDER BY name LIMIT 30"
    rows = db_fetchall(sql, tuple(params))
    return jsonify({"customers": [dict(r) for r in rows]})


# ============ 顾问端：录音上传 / 未入库 / 绑定 / 分析 ============
import uuid as _uuid


def _consultant_required():
    # 店长(store_manager)也是一线服务者，拥有全部顾问功能（上传/绑定/换绑/今日接诊）；
    # 纯管理员(admin/super)不做一线接诊，故不放行。
    if session.get("role") not in ("consultant", "store_manager"):
        return jsonify({"error": "仅顾问 / 店长账号可用"}), 403
    return None


def _detect_truncate_note(data, ext, reported_sec):
    """（已下线）断流截断告警——录音笔暂停/继续会让墙钟时长 > 实测音频而【误报】，故不再产生此提醒。
    保留函数与调用点，直接返回 None。时长列已用 ffprobe 实测真实值，无需此告警。"""
    return None
    try:  # noqa: 以下为旧逻辑，保留备查，不再执行
        if not reported_sec or reported_sec < 60:
            return None
        import tempfile, subprocess
        fd, p = tempfile.mkstemp(suffix="." + (ext or "bin"))
        try:
            with os.fdopen(fd, "wb") as fo:
                fo.write(data)
            out = subprocess.run(
                ["ffprobe", "-v", "error", "-show_entries", "format=duration",
                 "-of", "default=noprint_wrappers=1:nokey=1", p],
                capture_output=True, text=True, timeout=15)
            real = float((out.stdout or "").strip())
        finally:
            try: os.remove(p)
            except OSError: pass
        if real > 0 and real < reported_sec * 0.5:
            app.logger.warning("[truncate] upload 上报%ss 实测%.1fs (%.1fx) → 疑似断流截断",
                               reported_sec, real, reported_sec / max(real, 0.1))
            return (f"上报{_format_duration_label(reported_sec)}、实际只录到{_format_duration_label(real)}，"
                    f"疑似蓝牙断流丢失，请核对/重录")
    except Exception as e:
        app.logger.info("[truncate] upload 探测跳过: %s", e)
    return None


def _record_pen_sn_sighting(user_id, sn):
    """记录某顾问连过/用过的录音笔 SN（供管理员绑定时从下拉里选，不用手抄）。"""
    sn = (sn or "").strip()
    if not user_id or not sn:
        return
    try:
        db_write(
            "INSERT INTO pen_sn_sightings (user_id, sn, last_seen_at) "
            "VALUES (?,?,datetime('now','localtime')) "
            "ON CONFLICT(user_id, sn) DO UPDATE SET last_seen_at=datetime('now','localtime')",
            (user_id, sn),
        )
    except Exception as e:
        app.logger.info("[pen_sn] sighting 记录失败: %s", e)


def _add_pen_tombstone(rec):
    """录音被删时记墓碑：防"从录音笔同步"把已删的录音又拉回来复活。
    rec: sqlite Row，含 uploader_user_id / pen_file / recorded_at。"""
    try:
        keys = rec.keys()
        uid = rec["uploader_user_id"] if "uploader_user_id" in keys else None
        pf = rec["pen_file"] if "pen_file" in keys else None
        ra = rec["recorded_at"] if "recorded_at" in keys else None
        if not uid or (not pf and not ra):
            return
        db_write("INSERT INTO pen_tombstone (uploader_user_id, pen_file, recorded_at) VALUES (?,?,?)",
                 (uid, pf, ra))
    except Exception as e:
        app.logger.info("[tombstone] 记录失败: %s", e)


def _probe_seconds(data, ext):
    """ffprobe 上传字节的真实时长(秒)；失败返回 None。客户端没传时长(手动同步)时用它补，防 00分00秒。"""
    try:
        import tempfile, subprocess
        fd, p = tempfile.mkstemp(suffix="." + (ext or "bin"))
        try:
            with os.fdopen(fd, "wb") as fo:
                fo.write(data)
            out = subprocess.run(
                ["ffprobe", "-v", "error", "-show_entries", "format=duration",
                 "-of", "default=noprint_wrappers=1:nokey=1", p],
                capture_output=True, text=True, timeout=15)
            return float((out.stdout or "").strip())
        except Exception:
            return None
        finally:
            try: os.remove(p)
            except OSError: pass
    except Exception:
        return None


@app.route("/api/consultant/upload", methods=["POST"])
@login_required
def api_consultant_upload():
    err = _consultant_required()
    if err:
        return err
    u = current_user()
    f = request.files.get("file") or request.files.get("audio")
    if not f:
        return jsonify({"error": "未上传音频"}), 400
    advisor = u["advisor_name"] or u["username"]
    company_id = u["company_id"] or 1
    # OSS key: consultant-uploads/<company>/<user>/<uuid>.<ext>
    raw_name = f.filename or "audio.webm"
    ext = raw_name.rsplit(".", 1)[-1].lower() if "." in raw_name else "webm"
    if ext not in ("webm", "mp3", "wav", "m4a", "mp4", "ogg", "aac", "amr"):
        ext = "webm"
    dur_label = _format_duration_label(request.form.get("duration_sec"))
    now = datetime.now()
    # ★录音真实开始时间：安卓传 'YYYY-MM-DD HH:MM:SS'(可选)。连录/补传必须用真实时间，
    # 否则多段会挤在"上传时刻"，跨零点还会错日期。未传则回退服务器当前时间。
    recorded_at_raw = (request.form.get("recorded_at") or "").strip()
    recorded_at_form = None  # 表单解析出的真实时间；未传保持 None（用于判断是否覆盖占位行时间）
    if recorded_at_raw:
        for fmt in ("%Y-%m-%d %H:%M:%S", "%Y-%m-%dT%H:%M:%S", "%Y-%m-%d %H:%M", "%Y-%m-%dT%H:%M"):
            try:
                recorded_at_form = datetime.strptime(recorded_at_raw, fmt).strftime("%Y-%m-%d %H:%M:%S")
                break
            except ValueError:
                continue
        if recorded_at_form is None:
            recorded_at_form = recorded_at_raw  # 兜底：原样存，别丢
    recorded_at = recorded_at_form or now.strftime("%Y-%m-%d %H:%M:%S")
    ts14 = now.strftime("%Y%m%d%H%M%S")  # 仅用于 oss_key 文件名，继续用 now，避免连录补传撞名
    # 新格式：顾客未知 → 用 "未命名" 占位，绑定时再重命名
    oss_key = _build_consultant_oss_key(company_id, u['id'], None, advisor, ts14, dur_label, ext)
    if _oss_key_exists(oss_key):
        stem, _, ex = oss_key.rpartition(".")
        oss_key = f"{stem}_{_uuid.uuid4().hex[:4]}.{ex}"
    data = f.read()
    # ★断流截断兜底(上传即测)：上报墙钟时长 vs ffprobe 实测音频时长，差距大就打标，未归档当场提醒。
    try:
        reported_sec = int(float(request.form.get("duration_sec") or 0))
    except (TypeError, ValueError):
        reported_sec = 0
    # 客户端没传时长(手动从录音笔同步时机身列表 durationSec=0) → ffprobe 实测补上，防"00分00秒"
    if reported_sec <= 0:
        _real = _probe_seconds(data, ext)
        if _real and _real > 0.2:
            reported_sec = int(_real)
            dur_label = _format_duration_label(_real)
    # ★手机录音等"录完即传"路径不带 recorded_at → 上面默认用了 now(=按停止那一刻=录音结束时刻)，
    #   会让未归档列表「时间段」整体后移(开始被当成结束、结束跑到未来：11分钟录音停在 03:39 却显示 03:39–03:50)。
    #   没传 recorded_at 时改用 now − 时长 = 真实开始时刻(录完即传，误差仅几秒上传耗时)。录音笔路径会传 recorded_at，不受影响。
    if recorded_at_form is None and reported_sec > 0:
        recorded_at = (now - timedelta(seconds=reported_sec)).strftime("%Y-%m-%d %H:%M:%S")
    truncate_note = _detect_truncate_note(data, ext, reported_sec)
    # ★录音笔SN：app 上传时带 sn(=getMacAddress)。存到录音上做审计，并记一条"该顾问用过此SN"供管理员绑定。
    device_sn = (request.form.get("sn") or "").strip() or None
    if device_sn:
        _record_pen_sn_sighting(u["id"], device_sn)
    # 回填占位：结束录音时已先建了 processing 占位记录，这里把真音频补上，不新建行
    placeholder_id = request.form.get("placeholder_id")
    # ★录音笔文件去重：扫描补传/重连补传可能把同一支笔文件再传一次。命中 (上传人,pen_file)
    #   或 (上传人,recorded_at 同秒) 已存在的真录音 → 跳过，不重复传 OSS/入库（清掉本次占位）。
    pen_file = (request.form.get("pen_file") or "").strip() or None
    dup = None
    if pen_file:
        dup = db_fetchone(
            "SELECT id, truncate_note, oss_key, session_id FROM recordings "
            "WHERE uploader_user_id=? AND pen_file=? "
            "AND upload_status!='processing' LIMIT 1", (u["id"], pen_file))
    if not dup and recorded_at_form:
        dup = db_fetchone(
            "SELECT id, truncate_note, oss_key, session_id FROM recordings "
            "WHERE uploader_user_id=? AND recorded_at=? "
            "AND source LIKE 'consultant-pen%' AND upload_status!='processing' LIMIT 1",
            (u["id"], recorded_at_form))
    if dup:
        # ★命中的旧记录是「后段乱码/损坏」段、且还没绑定顾客 → 本次多半是从笔重新下载的【完整版】。
        #   不再当重复跳过(那样完整版会被白白丢弃)，而是用完整版替换旧损坏版、清掉损坏标记。
        #   已绑定(session_id 非空)的不动，避免破坏既有归属/命名，走原去重跳过。
        if dup["truncate_note"] and not dup["session_id"] and data:
            try:
                oss_bucket.put_object(oss_key, data)
            except Exception as e:
                app.logger.exception("顾问端上传 OSS 失败(替换损坏段)")
                return jsonify({"error": _friendly_oss_error(e)}), 500
            db_write(
                """UPDATE recordings SET oss_key=?, size_bytes=?, duration_label=?,
                   source='consultant-pen', upload_status='done', truncate_note=NULL,
                   recorded_at=COALESCE(?, recorded_at),
                   asr_status='awaiting_intake'
                   WHERE id=?""",
                (oss_key, len(data), dur_label, recorded_at_form, dup["id"]),
            )
            if dup["oss_key"] and dup["oss_key"] != oss_key:
                _oss_delete_quiet(dup["oss_key"])
            if pen_file:
                db_write("UPDATE recordings SET pen_file=? WHERE id=?", (pen_file, dup["id"]))
            if device_sn:
                db_write("UPDATE recordings SET device_sn=? WHERE id=?", (device_sn, dup["id"]))
            if placeholder_id:
                db_write("DELETE FROM recordings WHERE id=? AND upload_status='processing' AND uploader_user_id=?",
                         (placeholder_id, u["id"]))
            _kick_clean_audio_async(dup["id"])
            app.logger.info("[upload] 替换损坏段 rec %s pen_file=%s ra=%s", dup["id"], pen_file, recorded_at_form)
            return jsonify({"id": dup["id"], "replaced": True})
        if placeholder_id:
            db_write("DELETE FROM recordings WHERE id=? AND upload_status='processing' AND uploader_user_id=?",
                     (placeholder_id, u["id"]))
        app.logger.info("[upload] 去重跳过 pen_file=%s ra=%s → 已存在 rec %s", pen_file, recorded_at_form, dup["id"])
        return jsonify({"id": dup["id"], "deduped": True})
    if placeholder_id:
        prow = db_fetchone(
            "SELECT id, uploader_user_id, upload_status FROM recordings WHERE id=?",
            (placeholder_id,),
        )
        if prow and prow["uploader_user_id"] == u["id"] and prow["upload_status"] == "processing":
            try:
                oss_bucket.put_object(oss_key, data)
            except Exception as e:
                app.logger.exception("顾问端上传 OSS 失败")
                return jsonify({"error": _friendly_oss_error(e)}), 500
            # 占位行建占位时已带真实 recorded_at；仅当本次上传也带了 recorded_at 才覆盖，
            # 否则别用上传时刻 now 覆盖掉占位的真实开始时间。
            # ★asr_status 同步置 awaiting_intake：和手机录音(ingest orphan=True)一致——
            #   未绑定顾客前不跑 ASR(省钱)，且「待整理」里状态统一；绑定时会翻回 pending 起流水线。
            #   只动还没跑过 ASR 的(pending)，别覆盖已在跑/已完成的。
            if recorded_at_form:
                db_write(
                    """UPDATE recordings SET oss_key=?, size_bytes=?, duration_label=?,
                       recorded_at=?, source='consultant-pen', upload_status='done',
                       asr_status=CASE WHEN asr_status='pending' THEN 'awaiting_intake' ELSE asr_status END
                       WHERE id=?""",
                    (oss_key, len(data), dur_label, recorded_at_form, prow["id"]),
                )
            else:
                db_write(
                    """UPDATE recordings SET oss_key=?, size_bytes=?, duration_label=?,
                       source='consultant-pen', upload_status='done',
                       asr_status=CASE WHEN asr_status='pending' THEN 'awaiting_intake' ELSE asr_status END
                       WHERE id=?""",
                    (oss_key, len(data), dur_label, prow["id"]),
                )
            if truncate_note:
                db_write("UPDATE recordings SET truncate_note=? WHERE id=?", (truncate_note, prow["id"]))
            if device_sn:
                db_write("UPDATE recordings SET device_sn=? WHERE id=?", (device_sn, prow["id"]))
            if pen_file:
                db_write("UPDATE recordings SET pen_file=? WHERE id=?", (pen_file, prow["id"]))
            _kick_clean_audio_async(prow["id"])  # 后台转带头 wav：校正时长 + 让试听器可显时长/拖动
            return jsonify({"id": prow["id"], "oss_key": oss_key})
    try:
        oss_bucket.put_object(oss_key, data)
    except Exception as e:
        app.logger.exception("顾问端上传 OSS 失败")
        return jsonify({"error": _friendly_oss_error(e)}), 500
    rid = ingest_recording(
        oss_key, source="consultant-upload", size_bytes=len(data),
        advisor=advisor, customer=None,
        recorded_at=recorded_at, service_date=recorded_at[:10],
        duration_label=dur_label,
        company_id=company_id, uploader_user_id=u["id"], orphan=True,
    )
    if truncate_note:
        db_write("UPDATE recordings SET truncate_note=? WHERE id=?", (truncate_note, rid))
    if device_sn:
        db_write("UPDATE recordings SET device_sn=? WHERE id=?", (device_sn, rid))
    if pen_file:
        db_write("UPDATE recordings SET pen_file=? WHERE id=?", (pen_file, rid))
    _kick_clean_audio_async(rid)  # 后台转带头 wav：校正时长 + 让试听器可显时长/拖动
    return jsonify({"id": rid, "oss_key": oss_key})


@app.route("/api/consultant/pen/binding", methods=["GET"])
@login_required
def api_consultant_pen_binding():
    """App 登录后查自己被管理员绑定的录音笔 SN。空=未绑定。"""
    err = _consultant_required()
    if err:
        return err
    u = current_user()
    row = db_fetchone("SELECT pen_sn FROM users WHERE id=?", (u["id"],))
    return jsonify({"pen_sn": (row["pen_sn"] if row and row["pen_sn"] else None)})


@app.route("/api/consultant/pen/report-sn", methods=["POST"])
@login_required
def api_consultant_pen_report_sn():
    """App 一连上录音笔就上报它的 SN。后端做两件事：
      1. 记一条 sighting（供管理员从下拉里绑）；
      2. 返回【准/拒】决策（策略集中在后端，改策略不用更新 App）：
         - 我绑了SN → 只准用我绑的那台；连到别的 → 拒。
         - 我没绑 → 准用"没被别人绑"的任意一台；连到别人绑的 → 拒。
    App 收到 decision=deny 就断开 + 弹 message，不让录。"""
    err = _consultant_required()
    if err:
        return err
    u = current_user()
    body = request.get_json(silent=True) or {}
    sn = (request.form.get("sn") or body.get("sn") or "").strip()
    if not sn:
        return jsonify({"error": "缺少 sn"}), 400
    _record_pen_sn_sighting(u["id"], sn)
    row = db_fetchone("SELECT pen_sn, company_id FROM users WHERE id=?", (u["id"],))
    bound = row["pen_sn"] if row and row["pen_sn"] else None
    cid = row["company_id"] if row else None
    decision, reason, message = "allow", "free", ""
    if bound:
        if sn == bound:
            decision, reason = "allow", "my_pen"
        else:
            decision, reason = "deny", "have_other_binding"
            message = f"这不是你绑定的录音笔。你绑定的是 {bound}，请连那台。"
    else:
        # 没绑 → 看这台是不是被【本公司其他人】绑了
        if cid is not None:
            owner = db_fetchone(
                "SELECT advisor_name, username FROM users WHERE pen_sn=? AND company_id=? AND id!=? LIMIT 1",
                (sn, cid, u["id"]),
            )
        else:
            owner = db_fetchone(
                "SELECT advisor_name, username FROM users WHERE pen_sn=? AND id!=? LIMIT 1",
                (sn, u["id"]),
            )
        if owner:
            who = owner["advisor_name"] or owner["username"] or "其他顾问"
            decision, reason = "deny", "bound_other"
            message = f"这台录音笔已分配给 {who}，请改用未分配的录音笔。"
        else:
            decision, reason = "allow", "free"
    return jsonify({
        "ok": True,
        "bound_sn": bound,
        "match": (bound is not None and bound == sn),
        "decision": decision,   # allow | deny
        "reason": reason,       # my_pen | free | have_other_binding | bound_other
        "message": message,     # deny 时给 App 弹的话
    })


@app.route("/api/consultant/pen/sync-preview", methods=["POST"])
@login_required
def api_consultant_pen_sync_preview():
    """手动"从录音笔同步"：给机身文件列表(每条 name + ra录音时刻)，返回每条状态：
       uploaded(已传) / deleted(已删, 别复活) / new(没传过, 可勾选上传)。"""
    err = _consultant_required()
    if err:
        return err
    u = current_user()
    body = request.get_json(silent=True) or {}
    items = body.get("items") or []
    out = []
    for it in items[:500]:
        name = (it.get("name") or "").strip()
        ra = (it.get("ra") or "").strip() or None
        if not name:
            continue
        status, eid = "new", None
        rec = db_fetchone(
            "SELECT id FROM recordings WHERE uploader_user_id=? AND pen_file=? LIMIT 1", (u["id"], name))
        if not rec and ra:
            rec = db_fetchone(
                "SELECT id FROM recordings WHERE uploader_user_id=? "
                "AND ABS(strftime('%s',recorded_at)-strftime('%s',?))<=90 "
                "AND (source LIKE 'consultant-pen%' OR pen_file IS NOT NULL) LIMIT 1",
                (u["id"], ra))
        if rec:
            status, eid = "uploaded", rec["id"]
        else:
            tomb = db_fetchone(
                "SELECT id FROM pen_tombstone WHERE uploader_user_id=? AND pen_file=? LIMIT 1", (u["id"], name))
            if not tomb and ra:
                tomb = db_fetchone(
                    "SELECT id FROM pen_tombstone WHERE uploader_user_id=? AND recorded_at IS NOT NULL "
                    "AND ABS(strftime('%s',recorded_at)-strftime('%s',?))<=90 LIMIT 1", (u["id"], ra))
            if tomb:
                status = "deleted"
        out.append({"name": name, "status": status, "existing_id": eid})
    return jsonify({"items": out})


@app.route("/api/consultant/placeholder", methods=["POST"])
@login_required
def api_consultant_placeholder():
    err = _consultant_required()
    if err:
        return err
    u = current_user()
    advisor = u["advisor_name"] or u["username"]
    company_id = u["company_id"] or 1
    now = datetime.now()
    # ★录音真实开始时间(原生传)，用于未归档列表的服务日期/时段；没传则用现在
    recorded_at = request.form.get("recorded_at") or now.strftime("%Y-%m-%d %H:%M:%S")
    ts14 = now.strftime("%Y%m%d%H%M%S")
    # 占位 oss_key：唯一、不真传 OSS，上传完成时会被换成真 key
    placeholder_key = f"pending-uploads/{company_id}/{u['id']}/{ts14}_{_uuid.uuid4().hex[:8]}.pending"
    # 直接底层 INSERT，不走 ingest_recording（避免建 session / 起分析流水线）
    rid = db_write(
        """INSERT INTO recordings
           (session_id, oss_key, advisor, customer, recorded_at,
            duration_label, size_bytes, source, company_id, uploader_user_id,
            upload_status, created_at)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
        (None, placeholder_key, advisor, None, recorded_at,
         None, 0, "consultant-pen-placeholder", company_id, u["id"],
         "processing", recorded_at),
    )
    return jsonify({"id": rid})


@app.route("/api/consultant/placeholder/cancel", methods=["POST"])
@login_required
def api_consultant_placeholder_cancel():
    # 上传最终失败时清掉占位，别让"处理中"占位永远残留在未归档。只删本人、仍处于 processing 的占位。
    err = _consultant_required()
    if err:
        return err
    u = current_user()
    pid = request.form.get("placeholder_id")
    if not pid:
        return jsonify({"error": "缺少 placeholder_id"}), 400
    row = db_fetchone(
        "SELECT id, uploader_user_id, upload_status, session_id FROM recordings WHERE id=?",
        (pid,),
    )
    if row and row["uploader_user_id"] == u["id"] and row["upload_status"] == "processing" \
            and row["session_id"] is None:
        db_write("DELETE FROM recordings WHERE id=?", (row["id"],))
        return jsonify({"ok": True})
    return jsonify({"ok": False})


@app.route("/api/consultant/diag/upload", methods=["POST"])
@login_required
def api_consultant_diag_upload():
    """App 一键诊断上传：收 penlog.txt + last_result.txt + 设备信息(meta)，存服务器供远程排查。
    顾问在 App 里点「上传诊断」即可，不用 adb/连电脑。落到 diag_uploads/<user>_<id>_<ts>/。"""
    err = _consultant_required()
    if err:
        return err
    u = current_user()
    ts = datetime.now().strftime("%Y%m%d_%H%M%S")
    safe_user = re.sub(r"[^0-9A-Za-z_]+", "_", str(u["username"] or u["id"]))
    base = Path(__file__).parent / "diag_uploads" / f"{safe_user}_{u['id']}_{ts}"
    try:
        base.mkdir(parents=True, exist_ok=True)
        meta = request.form.get("meta") or ""
        if meta:
            (base / "meta.json").write_text(meta, encoding="utf-8")
        saved = []
        for field in ("penlog", "last_result"):
            f = request.files.get(field)
            if f and f.filename:
                f.save(str(base / (field + ".txt")))
                saved.append(field)
        app.logger.info("[diag] user=%s id=%s saved=%s dir=%s meta=%s",
                        safe_user, u["id"], saved, base.name, meta[:300])
        return jsonify({"ok": True, "saved": saved})
    except Exception as e:
        app.logger.warning("[diag] 保存失败 user=%s: %s", u["id"], e)
        return jsonify({"ok": False, "error": str(e)}), 500


@app.route("/api/consultant/recordings/pending")
@login_required
def api_consultant_recordings_pending():
    err = _consultant_required()
    if err:
        return err
    u = current_user()
    advisor = u["advisor_name"] or u["username"]
    # ★自愈孤儿占位：录音结束时建的 processing 占位(0字节、无 session、source 含 placeholder)，
    #   真实音频却作为「新行」done 入库(同一 uploader + 同一 recorded_at)而没回填/删占位——
    #   占位就永远卡在「后台同步中，传完后补时段/时长」。这里凡是已有同时间 done 录音存在的占位即删，
    #   不会误删真正在传的占位(那种此刻还没有同时间的 done 兄弟)。每次拉列表顺手清，幂等。
    try:
        db_write(
            """DELETE FROM recordings
               WHERE uploader_user_id=? AND upload_status='processing' AND session_id IS NULL
                 AND COALESCE(size_bytes,0)=0
                 AND IFNULL(recorded_at,'')<>''
                 AND IFNULL(source,'') LIKE '%placeholder%'
                 AND EXISTS (
                     SELECT 1 FROM recordings d
                     WHERE d.uploader_user_id=recordings.uploader_user_id
                       AND d.recorded_at=recordings.recorded_at
                       AND d.id<>recordings.id
                       AND d.upload_status='done'
                 )""",
            (u["id"],),
        )
    except Exception as _e:
        app.logger.warning("orphan placeholder self-heal failed: %s", _e)
    rows = db_fetchall(
        """SELECT id, oss_key, recorded_at, duration_label, size_bytes,
                  asr_status, asr_error, customer, created_at,
                  asr_speaker_count, asr_speaker_warning, upload_status, truncate_note, pen_file
           FROM recordings
           WHERE uploader_user_id=? AND session_id IS NULL
           ORDER BY id DESC LIMIT 200""",
        (u["id"],),
    )
    # 兼容老数据：advisor 同名但 uploader_user_id 为空的也算上
    rows2 = db_fetchall(
        """SELECT id, oss_key, recorded_at, duration_label, size_bytes,
                  asr_status, asr_error, customer, created_at,
                  asr_speaker_count, asr_speaker_warning, upload_status, truncate_note, pen_file
           FROM recordings
           WHERE advisor=? AND uploader_user_id IS NULL AND session_id IS NULL
           ORDER BY id DESC LIMIT 200""",
        (advisor,),
    )
    seen = set()
    out = []
    for r in list(rows) + list(rows2):
        if r["id"] in seen:
            continue
        seen.add(r["id"])
        d = dict(r)
        d["upload_status"] = r["upload_status"] if "upload_status" in r.keys() else "done"
        if d["upload_status"] == "processing":
            # 占位 key 不是真 OSS 对象，签名会坏，不给试听
            d["audio_url"] = None
        else:
            try:
                d["audio_url"] = oss_signed_url(d["oss_key"], expires=3600)
            except Exception:
                d["audio_url"] = None
        sd, sh, eh, dm = _rec_time_fields(r)
        d["service_date"] = sd
        d["start_hm"] = sh
        d["end_hm"] = eh
        d["duration_min"] = dm
        d["rec_date"] = sd  # 兼容旧字段
        # 删除申请状态：优先展示 pending，否则取最近一条未关闭的 rejected
        dreq = db_fetchone(
            """SELECT id, status, reason, reject_reason, created_at FROM delete_requests
               WHERE recording_id=? AND status='pending' ORDER BY id DESC LIMIT 1""",
            (r["id"],),
        )
        if not dreq:
            dreq = db_fetchone(
                """SELECT id, status, reason, reject_reason, created_at FROM delete_requests
                   WHERE recording_id=? AND status='rejected' AND dismissed_at IS NULL
                   ORDER BY id DESC LIMIT 1""",
                (r["id"],),
            )
        if dreq:
            d["delete_request_id"] = dreq["id"]
            d["delete_request_status"] = dreq["status"]
            d["delete_reject_reason"] = dreq["reject_reason"]
        else:
            d["delete_request_id"] = None
            d["delete_request_status"] = None
            d["delete_reject_reason"] = None
        out.append(d)
    # 惰性补转存量：旧片段(无时长头 ogg/webm)还没转成带头 wav → 后台逐步转，下次刷新即显真实时长/可拖动。
    # 每次最多 kick 8 条(配合 in-flight 去重 + 池限流 2 路)，不阻塞本次响应，列表 5s 自动刷新会逐步清完。
    _kicked = 0
    for d in out:
        if _kicked >= 8:
            break
        ok = (d.get("upload_status") == "done") and d.get("oss_key")
        if ok:
            _ek = d["oss_key"].rsplit(".", 1)[-1].lower() if "." in d["oss_key"] else ""
            if _ek not in ("wav", "mp3", "m4a"):
                _kick_clean_audio_async(d["id"])
                _kicked += 1
    return jsonify({"recordings": out})


def _parse_duration_label_sec(label):
    """解析 '23分26秒' / '00分51秒' / '01时02分03秒' → 秒数；失败返回 0。"""
    if not label:
        return 0
    import re as _re
    h = m = s = 0
    mh = _re.search(r"(\d+)\s*时", label)
    mm = _re.search(r"(\d+)\s*分", label)
    ms = _re.search(r"(\d+)\s*秒", label)
    if mh: h = int(mh.group(1))
    if mm: m = int(mm.group(1))
    if ms: s = int(ms.group(1))
    return h * 3600 + m * 60 + s


def _rec_time_fields(rec_row):
    """统一从 recorded_at + duration_label 算出 (service_date, start_hm, end_hm, duration_min)。"""
    from datetime import datetime as _dt, timedelta as _td
    ra = (rec_row["recorded_at"] or "").strip() if "recorded_at" in rec_row.keys() else ""
    ca = (rec_row["created_at"] or "").strip() if "created_at" in rec_row.keys() else ""
    base = ra or ca
    start_dt = None
    for fmt, n in (("%Y-%m-%d %H:%M:%S", 19), ("%Y-%m-%dT%H:%M:%S", 19), ("%Y%m%d%H%M%S", 14)):
        try:
            start_dt = _dt.strptime(base[:n], fmt)
            break
        except Exception:
            continue
    if start_dt is None:
        # 尽量退化为日期
        sd = ca[:10] if len(ca) >= 10 else _today_str()
        return sd, "—", "—", 0
    dur_sec = _parse_duration_label_sec(rec_row["duration_label"] if "duration_label" in rec_row.keys() else "")
    end_dt = start_dt + _td(seconds=dur_sec)
    return (
        start_dt.strftime("%Y-%m-%d"),
        start_dt.strftime("%H:%M"),
        end_dt.strftime("%H:%M"),
        int(round(dur_sec / 60)),
    )


def _today_str():
    return datetime.now().strftime("%Y-%m-%d")


def _norm_date(d):
    """把 YYYY-MM-DD / YYYYMMDD 统一成 YYYY-MM-DD。"""
    if not d:
        return None
    d = str(d)
    if len(d) == 8 and d.isdigit():
        return f"{d[:4]}-{d[4:6]}-{d[6:]}"
    return d


def _check_phone_tail(s):
    s = (s or "").strip()
    if len(s) != 4 or not s.isdigit():
        return None
    return s


# 补登/改日期范围
BACKFILL_DAYS_BACK = 7   # 补登：今天 ~ 今天-7
EDIT_DAYS_RANGE = 7      # 改接诊日期：±7天且不超今天


def _parse_ymd(s):
    try:
        return datetime.strptime(s, "%Y-%m-%d").date()
    except Exception:
        return None


def _has_pending_delete_request(recording_id):
    return db_fetchone(
        "SELECT id FROM delete_requests WHERE recording_id=? AND status='pending'",
        (recording_id,),
    ) is not None


def _rec_date_of(rec_row):
    """录音的实际日期 YYYY-MM-DD：优先 recorded_at（兼容 'YYYY-MM-DD HH:MM:SS' / 'YYYYMMDD...'），
    否则 created_at[:10]，否则今天。"""
    if rec_row is None:
        return _today_str()
    ra = (rec_row["recorded_at"] or "").strip() if "recorded_at" in rec_row.keys() else ""
    if ra:
        if len(ra) >= 10 and ra[4] == "-" and ra[7] == "-":
            return ra[:10]
        if len(ra) >= 8 and ra[:8].isdigit():
            return f"{ra[:4]}-{ra[4:6]}-{ra[6:8]}"
    ca = rec_row["created_at"] if "created_at" in rec_row.keys() else None
    if ca and len(ca) >= 10:
        return ca[:10]
    return _today_str()


def _advisor_received_customer_ids(advisor_user_id, advisor_name, company_id):
    """该顾问【本人接待过的客人】id 集合（风控候选集，老板拍板：换绑只能选本人接待过的）。
    口径 = daily_reception(advisor_user_id=本人, 任意日期)
          ∪ sessions(advisor=本人 advisor_name, 同公司) 关联到的 company_customers。
    sessions 侧优先用 customer_id；为空时按 (company_id, customer 姓名) 回填匹配。
    返回有效（未合并）客人 id 的 set。"""
    ids = set()
    for r in db_fetchall(
        "SELECT DISTINCT customer_id FROM daily_reception WHERE advisor_user_id=?",
        (advisor_user_id,),
    ):
        if r["customer_id"]:
            ids.add(r["customer_id"])
    # sessions：本人接诊（advisor 名字匹配，限同公司或历史 NULL 公司）
    sess_rows = db_fetchall(
        """SELECT customer_id, customer FROM sessions
           WHERE advisor=? AND (company_id IS NULL OR company_id=?)""",
        (advisor_name, company_id),
    )
    miss_names = set()
    for r in sess_rows:
        if r["customer_id"]:
            ids.add(r["customer_id"])
        elif r["customer"]:
            miss_names.add(r["customer"])
    # 历史 session 无 customer_id 的，按公司+姓名回填到客人档案
    for nm in miss_names:
        crow = db_fetchone(
            "SELECT id FROM company_customers WHERE company_id=? AND name=? AND merged_into IS NULL",
            (company_id, nm),
        )
        if crow:
            ids.add(crow["id"])
    # 过滤掉已被合并的客人 id
    if ids:
        qm = ",".join(["?"] * len(ids))
        valid = db_fetchall(
            f"SELECT id FROM company_customers WHERE id IN ({qm}) AND merged_into IS NULL",
            tuple(ids),
        )
        ids = {r["id"] for r in valid}
    return ids


@app.route("/api/consultant/rebind_candidates")
@login_required
def api_consultant_rebind_candidates():
    """换绑弹窗"选已有客人"候选源：**仅该顾问本人接待过的客人**（老板收窄，不放开全公司）。
    候选集 = daily_reception(本人,任意日期) ∪ sessions(本人) 关联的 company_customers。
    参数：rid=录音id（用于标注是否已在该录音当天接诊）、q=模糊词（姓名/会员卡号/手机尾号）。
    返回每个候选含 in_day（是否已在该录音当天本人 daily_reception，前端可提示"已在当日/将自动补登"）。"""
    err = _consultant_required()
    if err:
        return err
    u = current_user()
    cid = u["company_id"] or 1
    advisor = u["advisor_name"] or u["username"]
    rid = request.args.get("rid")
    q = (request.args.get("q") or "").strip()
    rec_date = None
    if rid:
        rec = db_fetchone("SELECT * FROM recordings WHERE id=?", (rid,))
        if rec:
            rec_date = _rec_date_of(rec)
    cand_ids = _advisor_received_customer_ids(u["id"], advisor, cid)
    if not cand_ids:
        return jsonify({"items": [], "service_date": rec_date})
    # 当天本人 daily_reception 客人 id（用于标注 in_day）
    day_ids = set()
    if rec_date:
        for r in db_fetchall(
            "SELECT customer_id FROM daily_reception WHERE advisor_user_id=? AND service_date=?",
            (u["id"], rec_date),
        ):
            day_ids.add(r["customer_id"])
    qm = ",".join(["?"] * len(cand_ids))
    params = list(cand_ids)
    sql = (f"SELECT id, name, member_card, phone_tail FROM company_customers "
           f"WHERE id IN ({qm}) AND merged_into IS NULL")
    if q:
        like = f"%{q}%"
        sql += " AND (name LIKE ? OR member_card LIKE ? OR phone_tail LIKE ?)"
        params += [like, like, like]
    sql += " ORDER BY id DESC LIMIT 50"
    rows = db_fetchall(sql, tuple(params))
    items = [{"customer_id": r["id"], "name": r["name"], "member_card": r["member_card"],
              "phone_tail": r["phone_tail"], "in_day": r["id"] in day_ids} for r in rows]
    # 已在当日接诊的排前面，方便直接选
    items.sort(key=lambda x: (not x["in_day"],))
    return jsonify({"items": items, "service_date": rec_date})


@app.route("/api/consultant/recordings/<int:rid>/bind", methods=["POST"])
@login_required
def api_consultant_recording_bind(rid):
    """新版绑定：必须传 customer_id，且 customer 必须在该顾问"今日接诊"白名单内。"""
    err = _consultant_required()
    if err:
        return err
    u = current_user()
    data = request.get_json(silent=True) or {}
    customer_id = data.get("customer_id")
    if not customer_id:
        return jsonify({"error": "请从今日接诊列表选择顾客"}), 400
    rec = db_fetchone("SELECT * FROM recordings WHERE id=?", (rid,))
    if not rec:
        return jsonify({"error": "录音不存在"}), 404
    if rec["uploader_user_id"] and rec["uploader_user_id"] != u["id"]:
        return jsonify({"error": "无权绑定他人录音"}), 403
    if _has_pending_delete_request(rid):
        return jsonify({"error": "该录音正在申请删除，请先撤回删除申请再操作"}), 409
    cid = u["company_id"] or 1
    # 用录音日期（默认今天）当 service_date，再校验白名单
    rec_date = _rec_date_of(rec)
    cust = db_fetchone(
        "SELECT id, name FROM company_customers WHERE id=? AND company_id=?",
        (customer_id, cid),
    )
    if not cust:
        return jsonify({"error": "顾客不存在"}), 404
    dr = db_fetchone(
        """SELECT id FROM daily_reception
           WHERE advisor_user_id=? AND customer_id=? AND service_date=?""",
        (u["id"], customer_id, rec_date),
    )
    if not dr:
        return jsonify({"error": "该顾客未在今日接诊列表，请先加入"}), 400
    advisor = u["advisor_name"] or u["username"]
    sid = get_or_create_session(advisor, cust["name"], rec_date, company_id=cid, customer_id=customer_id)
    if not sid:
        return jsonify({"error": "创建/查找接诊包失败"}), 500
    # 锁定 session 不允许再绑录音
    locked_row = db_fetchone("SELECT locked FROM sessions WHERE id=?", (sid,))
    if locked_row and locked_row["locked"]:
        return jsonify({"error": "该接诊包已锁定，无法再添加录音"}), 409
    # 先尝试 OSS 重命名（新格式录音），失败则回滚整个绑定操作
    try:
        new_key, old_key_to_del = _maybe_rename_consultant_oss(rec, cust["name"], advisor)
    except Exception as e:
        return jsonify({"error": f"OSS 重命名失败：{e}"}), 500
    try:
        db_write(
            """UPDATE recordings SET session_id=?, customer=?, advisor=?, oss_key=?,
               asr_status=CASE WHEN asr_status='awaiting_intake' THEN 'pending' ELSE asr_status END
               WHERE id=?""",
            (sid, cust["name"], advisor, new_key, rid),
        )
    except Exception as e:
        if old_key_to_del and new_key != old_key_to_del:
            _oss_delete_quiet(new_key)  # 回滚刚 copy 出的新 key
        return jsonify({"error": f"绑定失败：{e}"}), 500
    if old_key_to_del and new_key != old_key_to_del:
        _oss_delete_quiet(old_key_to_del)
    trigger_pipeline_for_recording(rid)
    return jsonify({"ok": True, "session_id": sid})


# ============ 今日接诊白名单 ============
@app.route("/api/consultant/today_reception")
@login_required
def api_consultant_today_reception():
    err = _consultant_required()
    if err:
        return err
    u = current_user()
    cid = u["company_id"] or 1
    date = _norm_date(request.args.get("date")) or _today_str()
    rows = db_fetchall(
        """SELECT dr.id AS dr_id, dr.customer_id, dr.service_date,
                  c.name, c.phone_tail, c.member_card
           FROM daily_reception dr
           JOIN company_customers c ON c.id=dr.customer_id
           WHERE dr.advisor_user_id=? AND dr.service_date=?
           ORDER BY dr.id DESC""",
        (u["id"], date),
    )
    advisor = u["advisor_name"] or u["username"]
    out = []
    for r in rows:
        # 统计该顾客今日已绑录音 / session 状态
        sess = db_fetchone(
            """SELECT id, locked, analysis_status FROM sessions
               WHERE advisor=? AND customer=? AND service_date=?
                 AND (company_id IS NULL OR company_id=?)""",
            (advisor, r["name"], date, cid),
        )
        rec_count = 0
        pending_rebind = 0
        if sess:
            cnt = db_fetchone(
                "SELECT COUNT(*) AS n FROM recordings WHERE session_id=?", (sess["id"],)
            )
            rec_count = cnt["n"] if cnt else 0
            prb = db_fetchone(
                """SELECT COUNT(*) AS n FROM rebind_requests
                   WHERE status='pending' AND from_session_id=?""",
                (sess["id"],),
            )
            pending_rebind = prb["n"] if prb else 0
        out.append({
            "id": r["dr_id"],
            "customer_id": r["customer_id"],
            "name": r["name"],
            "phone_tail": r["phone_tail"],
            "member_card": r["member_card"],
            "service_date": r["service_date"],
            "session_id": sess["id"] if sess else None,
            "locked": bool(sess["locked"]) if sess else False,
            "analysis_status": sess["analysis_status"] if sess else None,
            "recording_count": rec_count,
            "pending_rebind_count": pending_rebind,
        })
    return jsonify({"items": out, "date": date})


@app.route("/api/consultant/today_reception/add", methods=["POST"])
@login_required
def api_consultant_today_reception_add():
    err = _consultant_required()
    if err:
        return err
    u = current_user()
    cid = u["company_id"] or 1
    data = request.get_json(silent=True) or {}
    date = _norm_date(data.get("date")) or _today_str()
    # 补登历史客人：日期不能晚于今天，也不能早于今天-7
    today_d = _parse_ymd(_today_str())
    d_obj = _parse_ymd(date)
    if not d_obj:
        return jsonify({"error": "日期格式不正确"}), 400
    if d_obj > today_d:
        return jsonify({"error": "接诊日期不能晚于今天"}), 400
    if (today_d - d_obj).days > BACKFILL_DAYS_BACK:
        return jsonify({"error": f"补登只能选最近 {BACKFILL_DAYS_BACK} 天内的日期"}), 400
    customer_id = data.get("customer_id")
    # 新增顾客
    if not customer_id:
        name = (data.get("name") or "").strip()
        phone_tail = _check_phone_tail(data.get("phone_tail"))
        # 会员号留空时自动生成（对齐管理端 add_day_customer / _generate_member_card）
        member_card = (data.get("member_card") or "").strip() or None
        if not name:
            return jsonify({"error": "请填写顾客姓名"}), 400
        if not phone_tail:
            return jsonify({"error": "请填写手机尾号（4 位数字）"}), 400
        # 同 company + 同 name + 同 phone_tail 视为同一人，复用
        existed = db_fetchone(
            """SELECT id FROM company_customers
               WHERE company_id=? AND name=? AND COALESCE(phone_tail,'')=?""",
            (cid, name, phone_tail),
        )
        if existed:
            customer_id = existed["id"]
        else:
            if not member_card:
                member_card = _generate_member_card(cid)
            try:
                customer_id = db_write(
                    """INSERT INTO company_customers (company_id, name, phone_tail, member_card)
                       VALUES (?, ?, ?, ?)""",
                    (cid, name, phone_tail, member_card),
                )
            except sqlite3.IntegrityError:
                # 旧 UNIQUE(company_id, name) 撞了：补 phone_tail 后复用
                row = db_fetchone(
                    "SELECT id, phone_tail FROM company_customers WHERE company_id=? AND name=?",
                    (cid, name),
                )
                if not row:
                    return jsonify({"error": "新增失败"}), 500
                if not row["phone_tail"]:
                    db_write("UPDATE company_customers SET phone_tail=?, member_card=COALESCE(member_card,?) WHERE id=?",
                             (phone_tail, member_card, row["id"]))
                customer_id = row["id"]
    else:
        # 选已有顾客
        cust = db_fetchone(
            "SELECT id FROM company_customers WHERE id=? AND company_id=?",
            (customer_id, cid),
        )
        if not cust:
            return jsonify({"error": "顾客不存在"}), 404

    advisor_name = u["advisor_name"] or u["username"]
    try:
        db_write(
            """INSERT OR IGNORE INTO daily_reception
               (company_id, advisor_user_id, advisor_name, customer_id, service_date, store_id)
               VALUES (?, ?, ?, ?, ?, ?)""",
            (cid, u["id"], advisor_name, customer_id, date, u["store_id"]),
        )
    except sqlite3.IntegrityError:
        pass
    return jsonify({"ok": True, "customer_id": customer_id})


@app.route("/api/consultant/today_reception/<int:dr_id>", methods=["DELETE"])
@login_required
def api_consultant_today_reception_remove(dr_id):
    err = _consultant_required()
    if err:
        return err
    u = current_user()
    row = db_fetchone(
        "SELECT * FROM daily_reception WHERE id=? AND advisor_user_id=?",
        (dr_id, u["id"]),
    )
    if not row:
        return jsonify({"error": "不存在或无权操作"}), 404
    # 若该顾客今天已绑过录音，禁止移除
    advisor = u["advisor_name"] or u["username"]
    cust = db_fetchone("SELECT name FROM company_customers WHERE id=?", (row["customer_id"],))
    if cust:
        sess = db_fetchone(
            """SELECT s.id, COUNT(r.id) AS n FROM sessions s
               LEFT JOIN recordings r ON r.session_id=s.id
               WHERE s.advisor=? AND s.customer=? AND s.service_date=?
               GROUP BY s.id""",
            (advisor, cust["name"], row["service_date"]),
        )
        if sess and sess["n"]:
            return jsonify({"error": "已有录音绑定到该顾客，先移除/换绑录音再删除"}), 409
    db_write("DELETE FROM daily_reception WHERE id=?", (dr_id,))
    return jsonify({"ok": True})


@app.route("/api/consultant/today_reception/<int:dr_id>/date", methods=["PATCH"])
@login_required
def api_consultant_today_reception_change_date(dr_id):
    """修改接诊日期。允许范围：当前日期 ±EDIT_DAYS_RANGE 天，且不超过今天。
    挂在该接诊下的录音会自动解绑（录音真实日期不动）。已锁定 / 已分析的 session 不允许改。"""
    err = _consultant_required()
    if err:
        return err
    u = current_user()
    data = request.get_json(silent=True) or {}
    new_date = _norm_date(data.get("date"))
    if not new_date:
        return jsonify({"error": "缺少日期"}), 400
    new_d = _parse_ymd(new_date)
    if not new_d:
        return jsonify({"error": "日期格式不正确"}), 400
    row = db_fetchone(
        "SELECT * FROM daily_reception WHERE id=? AND advisor_user_id=?",
        (dr_id, u["id"]),
    )
    if not row:
        return jsonify({"error": "不存在或无权操作"}), 404
    if row["service_date"] == new_date:
        return jsonify({"ok": True, "unchanged": True})
    cur_d = _parse_ymd(row["service_date"])
    today_d = _parse_ymd(_today_str())
    if not cur_d:
        return jsonify({"error": "原日期异常，无法修改"}), 400
    if new_d > today_d:
        return jsonify({"error": "不能改到未来日期"}), 400
    if abs((new_d - cur_d).days) > EDIT_DAYS_RANGE:
        return jsonify({"error": f"只能在原日期前后 {EDIT_DAYS_RANGE} 天内修改"}), 400
    cid = u["company_id"] or 1
    advisor = u["advisor_name"] or u["username"]
    cust = db_fetchone("SELECT name FROM company_customers WHERE id=?", (row["customer_id"],))
    if not cust:
        return jsonify({"error": "顾客不存在"}), 404
    # 当前 session（如有）—— 已锁定/已分析不允许改
    sess = db_fetchone(
        """SELECT id, locked, analysis_status FROM sessions
           WHERE advisor=? AND customer=? AND service_date=?
             AND (company_id IS NULL OR company_id=?)""",
        (advisor, cust["name"], row["service_date"], cid),
    )
    if sess and (sess["locked"] or (sess["analysis_status"] and sess["analysis_status"] not in ("pending", None))):
        return jsonify({"error": "已开始分析或已锁定，不能改日期"}), 409
    # 目标日期是否已有同顾客接诊记录？避免 UNIQUE 冲突
    dup = db_fetchone(
        """SELECT id FROM daily_reception
           WHERE advisor_user_id=? AND customer_id=? AND service_date=?""",
        (u["id"], row["customer_id"], new_date),
    )
    if dup:
        return jsonify({"error": "目标日期已有该顾客的接诊记录"}), 409
    # 解绑录音 + 删除老 session（如有）
    unbound_count = 0
    if sess:
        recs = db_fetchall("SELECT id FROM recordings WHERE session_id=?", (sess["id"],))
        unbound_count = len(recs)
        for r in recs:
            db_write(
                "UPDATE recordings SET session_id=NULL, customer=NULL, speaker_confirmed=0 WHERE id=?",
                (r["id"],),
            )
        db_write("DELETE FROM sessions WHERE id=?", (sess["id"],))
    # 改 daily_reception 日期
    db_write("UPDATE daily_reception SET service_date=? WHERE id=?", (new_date, dr_id))
    return jsonify({"ok": True, "unbound_count": unbound_count})


@app.route("/api/consultant/recordings/pending_dates")
@login_required
def api_consultant_pending_dates():
    """当前顾问的未绑定录音中，**非今天** 的日期集合（用于决定是否展示"补登历史客人"按钮）。"""
    err = _consultant_required()
    if err:
        return err
    u = current_user()
    today = _today_str()
    rows = db_fetchall(
        """SELECT recorded_at, created_at FROM recordings
           WHERE uploader_user_id=? AND session_id IS NULL""",
        (u["id"],),
    )
    dates = set()
    for r in rows:
        d = _rec_date_of(r)
        if d and d != today:
            dates.add(d)
    return jsonify({"dates": sorted(dates, reverse=True)})


@app.route("/api/consultant/recordings/<int:rid>/direct_rebind", methods=["POST"])
@login_required
def api_consultant_direct_rebind(rid):
    """顾问直接换绑（无需审批），写入 rebind_requests 审计日志（action='rebind', status='done'）。
    放宽后约束（老板拍板）：目标顾客若不在录音当天接诊白名单，但属于【本人接待过的客人】，
    则自动补登进当天 daily_reception（算一次接诊）再换绑；若目标完全陌生（从没本人接待过）则拒绝（保留风控）。
    旧/新 session 若已分析会被作废（标 outdated）。"""
    err = _consultant_required()
    if err:
        return err
    u = current_user()
    data = request.get_json(silent=True) or {}
    to_customer_id = data.get("to_customer_id")
    reason = (data.get("reason") or "").strip()
    if not to_customer_id:
        return jsonify({"error": "请选择换绑目标顾客"}), 400
    try:
        to_customer_id = int(to_customer_id)  # 统一成 int：风控用 int 集合成员判断，字符串会被误判为陌生客人
    except (TypeError, ValueError):
        return jsonify({"error": "换绑目标顾客无效"}), 400
    rec = db_fetchone("SELECT * FROM recordings WHERE id=?", (rid,))
    if not rec:
        return jsonify({"error": "录音不存在"}), 404
    if rec["uploader_user_id"] and rec["uploader_user_id"] != u["id"]:
        return jsonify({"error": "无权操作他人录音"}), 403
    if _has_pending_delete_request(rid):
        return jsonify({"error": "该录音正在申请删除，请先撤回删除申请再操作"}), 409
    if not rec["session_id"]:
        return jsonify({"error": "该录音尚未绑定，请直接绑定即可"}), 400
    old_sess = db_fetchone("SELECT * FROM sessions WHERE id=?", (rec["session_id"],))
    cid = u["company_id"] or 1
    rec_date = _rec_date_of(rec)
    to_cust = db_fetchone(
        "SELECT id, name FROM company_customers WHERE id=? AND company_id=? AND merged_into IS NULL",
        (to_customer_id, cid),
    )
    if not to_cust:
        return jsonify({"error": "目标顾客不存在"}), 404
    advisor = u["advisor_name"] or u["username"]
    dr = db_fetchone(
        """SELECT id FROM daily_reception
           WHERE advisor_user_id=? AND customer_id=? AND service_date=?""",
        (u["id"], to_customer_id, rec_date),
    )
    if not dr:
        # 放宽：不直接报错。仅当目标属于【本人接待过的客人】才自动补登进当天接诊（算一次接诊）。
        # 完全陌生（从没本人接待过）则拒绝，保留风控。
        cand_ids = _advisor_received_customer_ids(u["id"], advisor, cid)
        if to_customer_id not in cand_ids:
            return jsonify({"error": "只能换绑到您本人接待过的客人；该客人不在您的接待记录里，请改用“新增客人”"}), 400
        db_write(
            """INSERT OR IGNORE INTO daily_reception
               (company_id, advisor_user_id, advisor_name, customer_id, service_date, store_id)
               VALUES (?,?,?,?,?,?)""",
            (cid, u["id"], advisor, to_customer_id, rec_date, u["store_id"]),
        )
    new_sid = get_or_create_session(advisor, to_cust["name"], rec_date, company_id=cid)
    if not new_sid:
        return jsonify({"error": "创建目标接诊包失败"}), 500
    try:
        new_key, old_key_to_del = _maybe_rename_consultant_oss(rec, to_cust["name"], advisor)
    except Exception as e:
        return jsonify({"error": f"OSS 重命名失败：{e}"}), 500
    try:
        db_write(
            """UPDATE recordings SET session_id=?, customer=?, oss_key=?, speaker_confirmed=0,
               asr_status=CASE WHEN asr_status='awaiting_intake' THEN 'pending' ELSE asr_status END
               WHERE id=?""",
            (new_sid, to_cust["name"], new_key, rid),
        )
    except Exception as e:
        if old_key_to_del and new_key != old_key_to_del:
            _oss_delete_quiet(new_key)
        return jsonify({"error": f"换绑失败：{e}"}), 500
    if old_key_to_del and new_key != old_key_to_del:
        _oss_delete_quiet(old_key_to_del)
    # 作废新 session 的旧分析：只对真正"分析过"的状态打 outdated，避免把全新 pending 错标成失败
    db_write(
        """UPDATE sessions SET locked=0,
                              analysis_status=CASE WHEN analysis_status IN ('done','failed') THEN 'outdated' ELSE analysis_status END
           WHERE id=?""",
        (new_sid,),
    )
    # 老 session：变空则解锁 + 清掉分析；不删 session、不删 daily_reception（顾客仍在当日列表里）
    old_sid = old_sess["id"] if old_sess else None
    from_cust_id = None
    from_cust_name = rec["customer"] or ""
    if old_sid:
        old_cust = db_fetchone(
            "SELECT id FROM company_customers WHERE company_id=? AND name=?",
            (cid, old_sess["customer"]),
        )
        from_cust_id = old_cust["id"] if old_cust else None
        cnt = db_fetchone("SELECT COUNT(*) AS n FROM recordings WHERE session_id=?", (old_sid,))
        if not cnt or not cnt["n"]:
            db_write(
                """UPDATE sessions SET locked=0,
                       analysis_status=NULL, analysis_result=NULL, analysis_error=NULL,
                       analysis_started_at=NULL, analysis_finished_at=NULL,
                       analysis_signature=NULL, analysis_scores=NULL,
                       analysis_progress=NULL, task_status=NULL
                   WHERE id=?""",
                (old_sid,),
            )
        else:
            db_write(
                """UPDATE sessions SET locked=0,
                                      analysis_status=CASE WHEN analysis_status IN ('done','failed') THEN 'outdated' ELSE analysis_status END
                   WHERE id=?""",
                (old_sid,),
            )
    # 写审计日志
    db_write(
        """INSERT INTO rebind_requests
           (company_id, recording_id, rec_date, requester_user_id, requester_name,
            from_session_id, from_customer_id, from_customer_name,
            to_customer_id, to_customer_name, reason, status, action,
            reviewer_user_id, reviewer_name, reviewed_at)
           VALUES (?,?,?,?,?,?,?,?,?,?,?,'done','rebind',?,?,datetime('now','localtime'))""",
        (cid, rid, rec_date, u["id"], advisor,
         old_sid, from_cust_id, from_cust_name,
         to_cust["id"], to_cust["name"], reason or "(顾问直接换绑)",
         u["id"], advisor),
    )
    return jsonify({"ok": True, "new_session_id": new_sid})


@app.route("/api/consultant/recordings/<int:rid>/add_day_customer", methods=["POST"])
@login_required
def api_consultant_recording_add_day_customer(rid):
    """换绑弹窗"选不到就＋新增客人"：为本顾问、该录音当天新建一个客人并补登进当日接诊（算一次接诊），
    返回 customer_id 供前端直接作为换绑目标。会员号留空时自动生成（对齐 _generate_member_card）。"""
    err = _consultant_required()
    if err:
        return err
    u = current_user()
    cid = u["company_id"] or 1
    data = request.get_json(silent=True) or {}
    name = (data.get("name") or "").strip()
    phone_tail = (data.get("phone_tail") or "").strip()
    if not name:
        return jsonify({"error": "请填写客人姓名"}), 400
    if phone_tail and not re.fullmatch(r"\d{4}", phone_tail):
        return jsonify({"error": "手机尾号必须是 4 位数字"}), 400
    rec = db_fetchone("SELECT * FROM recordings WHERE id=?", (rid,))
    if not rec:
        return jsonify({"error": "录音不存在"}), 404
    if rec["uploader_user_id"] and rec["uploader_user_id"] != u["id"]:
        return jsonify({"error": "无权操作他人录音"}), 403
    rec_date = _rec_date_of(rec)
    advisor = u["advisor_name"] or u["username"]
    # 同 company+name+phone_tail 视为同一人，复用（避免重复建档）；否则新建并自动发会员号
    existed = None
    if phone_tail:
        existed = db_fetchone(
            """SELECT id, name, member_card, phone_tail FROM company_customers
               WHERE company_id=? AND name=? AND COALESCE(phone_tail,'')=? AND merged_into IS NULL""",
            (cid, name, phone_tail),
        )
    if existed:
        new_cust_id = existed["id"]
        member_card = existed["member_card"]
    else:
        member_card = _generate_member_card(cid)
        try:
            new_cust_id = db_write(
                "INSERT INTO company_customers (company_id, name, member_card, phone_tail) VALUES (?, ?, ?, ?)",
                (cid, name, member_card, phone_tail or None),
            )
        except sqlite3.IntegrityError:
            # 旧 UNIQUE(company_id, name) 撞了：复用同名档案
            row = db_fetchone(
                "SELECT id, member_card FROM company_customers WHERE company_id=? AND name=? AND merged_into IS NULL",
                (cid, name),
            )
            if not row:
                return jsonify({"error": "新增失败"}), 500
            new_cust_id = row["id"]
            member_card = row["member_card"]
    # 补登进【录音当天】本人 daily_reception（算一次接诊）
    db_write(
        """INSERT OR IGNORE INTO daily_reception
           (company_id, advisor_user_id, advisor_name, customer_id, service_date, store_id)
           VALUES (?,?,?,?,?,?)""",
        (cid, u["id"], advisor, new_cust_id, rec_date, u["store_id"]),
    )
    return jsonify({"ok": True, "customer_id": new_cust_id, "name": name,
                    "phone_tail": phone_tail or None, "member_card": member_card,
                    "service_date": rec_date})


@app.route("/api/consultant/recordings/<int:rid>/remove_day_customer", methods=["POST"])
@login_required
def api_consultant_recording_remove_day_customer(rid):
    """删除换绑时误建的客人（如名字填错）：仅当该客人名下没有任何录音时允许，
    同时删掉其当日接诊登记、空 session 和客人档案；名下有录音则拒绝（安全闸，防误删历史数据）。
    收窄：只允许删本人当天接诊里的客人（避免删到别人 / 历史档案）。"""
    err = _consultant_required()
    if err:
        return err
    u = current_user()
    cid = u["company_id"] or 1
    data = request.get_json(silent=True) or {}
    customer_id = data.get("customer_id")
    if not customer_id:
        return jsonify({"error": "缺少 customer_id"}), 400
    rec = db_fetchone("SELECT * FROM recordings WHERE id=?", (rid,))
    if not rec:
        return jsonify({"error": "录音不存在"}), 404
    if rec["uploader_user_id"] and rec["uploader_user_id"] != u["id"]:
        return jsonify({"error": "无权操作他人录音"}), 403
    rec_date = _rec_date_of(rec)
    cust = db_fetchone(
        "SELECT id, name FROM company_customers WHERE id=? AND company_id=?",
        (customer_id, cid),
    )
    if not cust:
        return jsonify({"error": "客人不存在"}), 404
    # 收窄：只能删本人当天接诊登记里的客人
    dr = db_fetchone(
        "SELECT id FROM daily_reception WHERE advisor_user_id=? AND customer_id=? AND service_date=?",
        (u["id"], customer_id, rec_date),
    )
    if not dr:
        return jsonify({"error": "该客人不在您当天的接诊登记里，无法删除"}), 400
    # 安全闸：名下任一 session 只要有录音就不许删（防误删有历史数据的客人）
    rec_cnt = db_fetchone(
        """SELECT COUNT(*) AS n FROM recordings r
           JOIN sessions s ON s.id=r.session_id WHERE s.customer_id=?""",
        (customer_id,),
    )
    if rec_cnt and rec_cnt["n"]:
        return jsonify({"error": "该客人名下已有录音/接诊数据，不能删除"}), 409
    db_write("DELETE FROM sessions WHERE customer_id=?", (customer_id,))
    db_write("DELETE FROM daily_reception WHERE customer_id=?", (customer_id,))
    db_write("DELETE FROM company_customers WHERE id=?", (customer_id,))
    return jsonify({"ok": True})


@app.route("/api/consultant/recordings/<int:rid>/unbind", methods=["POST"])
@login_required
def api_consultant_recording_unbind(rid):
    """顾问把已绑录音"退回未归档片段"。session_id 置 NULL；写审计日志（action='unbind', status='done'）；
    原 session 若清空则删除，非空则作废旧分析。"""
    err = _consultant_required()
    if err:
        return err
    u = current_user()
    data = request.get_json(silent=True) or {}
    reason = (data.get("reason") or "").strip()
    if not reason:
        return jsonify({"error": "请填写退回理由"}), 400
    rec = db_fetchone("SELECT * FROM recordings WHERE id=?", (rid,))
    if not rec:
        return jsonify({"error": "录音不存在"}), 404
    if rec["uploader_user_id"] and rec["uploader_user_id"] != u["id"]:
        return jsonify({"error": "无权操作他人录音"}), 403
    if _has_pending_delete_request(rid):
        return jsonify({"error": "该录音正在申请删除，请先撤回删除申请再操作"}), 409
    if not rec["session_id"]:
        return jsonify({"error": "该录音本来就未归档"}), 400
    old_sess = db_fetchone("SELECT * FROM sessions WHERE id=?", (rec["session_id"],))
    cid = u["company_id"] or 1
    rec_date = _rec_date_of(rec)
    advisor = u["advisor_name"] or u["username"]
    old_sid = old_sess["id"] if old_sess else None
    from_cust_name = rec["customer"] or ""
    from_cust = db_fetchone(
        "SELECT id FROM company_customers WHERE company_id=? AND name=?",
        (cid, from_cust_name),
    )
    from_cust_id = from_cust["id"] if from_cust else None
    # 解绑
    db_write(
        """UPDATE recordings SET session_id=NULL, customer=NULL, speaker_confirmed=0,
           asr_status=CASE WHEN asr_status IN ('pending','running','failed') THEN 'awaiting_intake' ELSE asr_status END
           WHERE id=?""",
        (rid,),
    )
    # 老 session：变空则解锁+清分析（不删 session/daily_reception，顾客仍在当日列表里）
    if old_sid:
        cnt = db_fetchone("SELECT COUNT(*) AS n FROM recordings WHERE session_id=?", (old_sid,))
        if not cnt or not cnt["n"]:
            db_write(
                """UPDATE sessions SET locked=0,
                       analysis_status=NULL, analysis_result=NULL, analysis_error=NULL,
                       analysis_started_at=NULL, analysis_finished_at=NULL,
                       analysis_signature=NULL, analysis_scores=NULL,
                       analysis_progress=NULL, task_status=NULL
                   WHERE id=?""",
                (old_sid,),
            )
        else:
            db_write(
                """UPDATE sessions SET locked=0,
                                      analysis_status=CASE WHEN analysis_status IN ('done','failed') THEN 'outdated' ELSE analysis_status END
                   WHERE id=?""",
                (old_sid,),
            )
    # 审计日志：to_customer_id 用 0 表示"未归档"
    db_write(
        """INSERT INTO rebind_requests
           (company_id, recording_id, rec_date, requester_user_id, requester_name,
            from_session_id, from_customer_id, from_customer_name,
            to_customer_id, to_customer_name, reason, status, action,
            reviewer_user_id, reviewer_name, reviewed_at)
           VALUES (?,?,?,?,?,?,?,?,0,'(未归档)',?,'done','unbind',?,?,datetime('now','localtime'))""",
        (cid, rid, rec_date, u["id"], advisor,
         old_sid, from_cust_id, from_cust_name,
         reason, u["id"], advisor),
    )
    return jsonify({"ok": True})


@app.route("/api/consultant/recordings/<int:rid>/rebind_request", methods=["POST"])
@login_required
def api_consultant_rebind_request(rid):
    """顾问申请换绑。目标顾客必须在录音当天的接诊白名单内；理由必填；
    同一录音已有 pending 申请时不可重复。已分析的录音也允许申请（同意后会作废旧分析）。"""
    err = _consultant_required()
    if err:
        return err
    u = current_user()
    data = request.get_json(silent=True) or {}
    to_customer_id = data.get("to_customer_id")
    reason = (data.get("reason") or "").strip()
    if not to_customer_id:
        return jsonify({"error": "请选择换绑目标顾客"}), 400
    if not reason:
        return jsonify({"error": "请填写换绑理由"}), 400
    rec = db_fetchone("SELECT * FROM recordings WHERE id=?", (rid,))
    if not rec:
        return jsonify({"error": "录音不存在"}), 404
    if rec["uploader_user_id"] and rec["uploader_user_id"] != u["id"]:
        return jsonify({"error": "无权操作他人录音"}), 403
    if not rec["session_id"]:
        return jsonify({"error": "该录音尚未绑定，请直接绑定即可"}), 400
    cid = u["company_id"] or 1
    rec_date = _rec_date_of(rec)
    # 目标顾客必须在录音当天的接诊白名单
    to_cust = db_fetchone(
        "SELECT id, name FROM company_customers WHERE id=? AND company_id=?",
        (to_customer_id, cid),
    )
    if not to_cust:
        return jsonify({"error": "目标顾客不存在"}), 404
    dr = db_fetchone(
        """SELECT id FROM daily_reception
           WHERE advisor_user_id=? AND customer_id=? AND service_date=?""",
        (u["id"], to_customer_id, rec_date),
    )
    if not dr:
        return jsonify({"error": "目标顾客不在录音当天的接诊列表，请先补登再申请"}), 400
    # 已有 pending 申请？
    exist = db_fetchone(
        "SELECT id FROM rebind_requests WHERE recording_id=? AND status='pending'",
        (rid,),
    )
    if exist:
        return jsonify({"error": "该录音已有换绑申请正在审批中"}), 409
    # from 信息
    from_sess = db_fetchone("SELECT id FROM sessions WHERE id=?", (rec["session_id"],))
    from_cust_name = rec["customer"] or ""
    from_cust = db_fetchone(
        "SELECT id FROM company_customers WHERE company_id=? AND name=?",
        (cid, from_cust_name),
    )
    advisor = u["advisor_name"] or u["username"]
    req_id = db_write(
        """INSERT INTO rebind_requests
           (company_id, recording_id, rec_date, requester_user_id, requester_name,
            from_session_id, from_customer_id, from_customer_name,
            to_customer_id, to_customer_name, reason)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
        (cid, rid, rec_date, u["id"], advisor,
         from_sess["id"] if from_sess else None,
         from_cust["id"] if from_cust else None, from_cust_name,
         to_cust["id"], to_cust["name"], reason),
    )
    return jsonify({"ok": True, "request_id": req_id})


# ============ 管理动线：未绑定录音 / 代绑 / 详情页换绑 ============
def _advisor_user_for_manager(advisor_user_id, cid):
    """解析代绑目标顾问 user 行，并校验权限：
    - admin/super：本公司任意 consultant / store_manager。
    - store_manager：仅限本店 consultant / store_manager。
    返回 (user_row, error_jsonify_tuple)；成功时 error 为 None。"""
    au = db_fetchone(
        "SELECT id, username, role, company_id, advisor_name, store_id FROM users WHERE id=?",
        (advisor_user_id,),
    )
    if not au or au["role"] not in ("consultant", "store_manager"):
        return None, (jsonify({"error": "所选顾问不存在"}), 404)
    if au["company_id"] != cid:
        return None, (jsonify({"error": "所选顾问不属于本公司"}), 403)
    if session.get("role") == "store_manager":
        my_store = session.get("store_id")
        if not my_store or au["store_id"] != my_store:
            return None, (jsonify({"error": "店长只能代本店顾问操作"}), 403)
    return au, None


@app.route("/api/admin/unbound_recordings")
@manager_required
def api_admin_unbound_recordings():
    """未绑定录音列表（session_id IS NULL）。admin/super 看本公司；
    store_manager 收口到本店（recordings.store_id）。"""
    cid = session.get("company_id") or 1
    is_super = session.get("role") == "super"
    where = "r.session_id IS NULL"
    params = []
    if not is_super:
        where += " AND r.company_id=?"
        params.append(cid)
    store_filter = current_store_filter()
    if store_filter is not None:
        where += " AND r.store_id=?"
        params.append(store_filter)
    # 按顾问名/上传人筛选（recording.advisor 或 上传人的 advisor_name/username）
    q = (request.args.get("q") or "").strip()
    if q:
        where += " AND (r.advisor LIKE ? OR u.advisor_name LIKE ? OR u.username LIKE ?)"
        like = f"%{q}%"
        params.extend([like, like, like])
    rows = db_fetchall(
        f"""SELECT r.id, r.advisor, r.recorded_at, r.duration_label, r.asr_status,
                   r.oss_key, r.uploader_user_id, r.store_id, r.truncate_note,
                   u.advisor_name AS uploader_advisor_name, u.username AS uploader_username,
                   st.name AS store_name
            FROM recordings r
            LEFT JOIN users u ON u.id=r.uploader_user_id
            LEFT JOIN stores st ON st.id=r.store_id
            WHERE {where}
            ORDER BY r.recorded_at DESC, r.id DESC LIMIT 300""",
        tuple(params),
    )
    out = []
    for r in rows:
        # 上传人/顾问：优先 recording.advisor，否则取 uploader 的 advisor_name/username
        who = r["advisor"] or r["uploader_advisor_name"] or r["uploader_username"] or ""
        try:
            url = oss_signed_url(r["oss_key"]) if r["oss_key"] else None
        except Exception:
            url = None
        out.append({
            "id": r["id"],
            "uploader": who,
            "recorded_at": r["recorded_at"],
            "duration_label": r["duration_label"],
            "asr_status": r["asr_status"],
            "store_id": r["store_id"],
            "store_name": r["store_name"],
            "audio_url": url,
            "rec_date": _rec_date_of(r),
            "truncate_note": r["truncate_note"],
        })
    return jsonify({"items": out})


@app.route("/api/admin/recordings/<int:rid>/admin_bind", methods=["POST"])
@manager_required
def api_admin_recording_admin_bind(rid):
    """管理员/店长代绑：把未绑定录音绑给"某顾问当日的客人"。
    - 选顾问 + 服务日期 + 客人（已有 daily_reception 客人 或 新增客人）。
    - 校验客人必须在该顾问该日 daily_reception 内（新增则先写入）。
    - 设 recording 的 advisor/customer/store_id/session_id，触发流水线。
    - 审计 action='admin_bind' status='done'。"""
    u = current_user()
    cid = u["company_id"] or 1
    data = request.get_json(silent=True) or {}
    advisor_user_id = data.get("advisor_user_id")
    if not advisor_user_id:
        return jsonify({"error": "请选择顾问"}), 400
    au, err = _advisor_user_for_manager(advisor_user_id, cid)
    if err:
        return err
    rec = db_fetchone("SELECT * FROM recordings WHERE id=?", (rid,))
    if not rec:
        return jsonify({"error": "录音不存在"}), 404
    if rec["company_id"] and rec["company_id"] != cid and session.get("role") != "super":
        return jsonify({"error": "无权操作其他公司录音"}), 403
    if rec["session_id"]:
        return jsonify({"error": "该录音已绑定，请用换绑"}), 400
    if session.get("role") == "store_manager":
        if rec["store_id"] and rec["store_id"] != session.get("store_id"):
            return jsonify({"error": "店长只能操作本店录音"}), 403
    if _has_pending_delete_request(rid):
        return jsonify({"error": "该录音正在申请删除，请先处理删除申请"}), 409

    # 服务日期：默认录音日期
    date = _norm_date(data.get("date")) or _rec_date_of(rec)
    if not _parse_ymd(date):
        return jsonify({"error": "日期格式不正确"}), 400

    customer_id = data.get("customer_id")
    # 新增客人加入该顾问当日接诊
    if not customer_id:
        name = (data.get("name") or "").strip()
        phone_tail = _check_phone_tail(data.get("phone_tail"))
        member_card = (data.get("member_card") or "").strip() or None
        if not name:
            return jsonify({"error": "请填写顾客姓名"}), 400
        if not phone_tail:
            return jsonify({"error": "请填写手机尾号（4 位数字）"}), 400
        existed = db_fetchone(
            """SELECT id FROM company_customers
               WHERE company_id=? AND name=? AND COALESCE(phone_tail,'')=?""",
            (cid, name, phone_tail),
        )
        if existed:
            customer_id = existed["id"]
        else:
            try:
                customer_id = db_write(
                    """INSERT INTO company_customers (company_id, name, phone_tail, member_card)
                       VALUES (?, ?, ?, ?)""",
                    (cid, name, phone_tail, member_card),
                )
            except sqlite3.IntegrityError:
                row = db_fetchone(
                    "SELECT id, phone_tail FROM company_customers WHERE company_id=? AND name=?",
                    (cid, name),
                )
                if not row:
                    return jsonify({"error": "新增顾客失败"}), 500
                if not row["phone_tail"]:
                    db_write("UPDATE company_customers SET phone_tail=?, member_card=COALESCE(member_card,?) WHERE id=?",
                             (phone_tail, member_card, row["id"]))
                customer_id = row["id"]
    cust = db_fetchone(
        "SELECT id, name FROM company_customers WHERE id=? AND company_id=?",
        (customer_id, cid),
    )
    if not cust:
        return jsonify({"error": "顾客不存在"}), 404

    advisor_name = au["advisor_name"] or au["username"]
    # 确保客人在该顾问该日 daily_reception 内（新增/已选都执行 INSERT OR IGNORE）
    try:
        db_write(
            """INSERT OR IGNORE INTO daily_reception
               (company_id, advisor_user_id, advisor_name, customer_id, service_date, store_id)
               VALUES (?, ?, ?, ?, ?, ?)""",
            (cid, au["id"], advisor_name, customer_id, date, au["store_id"]),
        )
    except sqlite3.IntegrityError:
        pass
    dr = db_fetchone(
        """SELECT id FROM daily_reception
           WHERE advisor_user_id=? AND customer_id=? AND service_date=?""",
        (au["id"], customer_id, date),
    )
    if not dr:
        return jsonify({"error": "客人未在该顾问当日接诊列表"}), 400

    sid = get_or_create_session(advisor_name, cust["name"], date, company_id=cid, customer_id=customer_id)
    if not sid:
        return jsonify({"error": "创建/查找接诊包失败"}), 500
    locked_row = db_fetchone("SELECT locked FROM sessions WHERE id=?", (sid,))
    if locked_row and locked_row["locked"]:
        return jsonify({"error": "该接诊包已锁定，无法再添加录音"}), 409

    # OSS 重命名（顾问端新格式录音），失败回滚
    try:
        new_key, old_key_to_del = _maybe_rename_consultant_oss(rec, cust["name"], advisor_name)
    except Exception as e:
        return jsonify({"error": f"OSS 重命名失败：{e}"}), 500
    try:
        db_write(
            """UPDATE recordings SET session_id=?, customer=?, advisor=?, oss_key=?, store_id=?,
               asr_status=CASE WHEN asr_status='awaiting_intake' THEN 'pending' ELSE asr_status END
               WHERE id=?""",
            (sid, cust["name"], advisor_name, new_key, au["store_id"], rid),
        )
    except Exception as e:
        if old_key_to_del and new_key != old_key_to_del:
            _oss_delete_quiet(new_key)
        return jsonify({"error": f"绑定失败：{e}"}), 500
    if old_key_to_del and new_key != old_key_to_del:
        _oss_delete_quiet(old_key_to_del)
    trigger_pipeline_for_recording(rid)

    db_write(
        """INSERT INTO rebind_requests
           (company_id, recording_id, rec_date, requester_user_id, requester_name,
            from_session_id, from_customer_id, from_customer_name,
            to_customer_id, to_customer_name, reason, status, action,
            reviewer_user_id, reviewer_name, reviewed_at)
           VALUES (?,?,?,?,?,NULL,NULL,'(未绑定)',?,?,?,'done','admin_bind',?,?,datetime('now','localtime'))""",
        (cid, rid, date, u["id"], u["advisor_name"] or u["username"],
         cust["id"], cust["name"], data.get("reason") or "(管理员代绑)",
         u["id"], u["advisor_name"] or u["username"]),
    )
    return jsonify({"ok": True, "session_id": sid})


def _admin_rec_scope_check(rid):
    """按录音操作的公共前置：取录音 + 原 session，校验公司/店长权限。
    返回 (rec, old_sess, scid, error_tuple)；成功时 error 为 None。"""
    rec = db_fetchone("SELECT * FROM recordings WHERE id=?", (rid,))
    if not rec:
        return None, None, None, (jsonify({"error": "录音不存在"}), 404)
    if not rec["session_id"]:
        return None, None, None, (jsonify({"error": "该录音尚未绑定，请直接绑定即可"}), 400)
    old_sess = db_fetchone("SELECT * FROM sessions WHERE id=?", (rec["session_id"],))
    if not old_sess:
        return None, None, None, (jsonify({"error": "原接诊包不存在"}), 404)
    cid = session.get("company_id") or 1
    scid = old_sess["company_id"] or cid
    if scid != cid and session.get("role") != "super":
        return None, None, None, (jsonify({"error": "无权操作其他公司录音"}), 403)
    if session.get("role") == "store_manager":
        if old_sess["store_id"] and old_sess["store_id"] != session.get("store_id"):
            return None, None, None, (jsonify({"error": "店长只能操作本店录音"}), 403)
    return rec, old_sess, scid, None


@app.route("/api/admin/recordings/<int:rid>/rebind", methods=["POST"])
@manager_required
def api_admin_recording_rebind(rid):
    """管理员/店长在详情页【按单条录音】换绑到该顾问当日的另一个客人。
    仿顾问 direct_rebind，但 manager 权限、顾问从录音所属 session 解析、店长限本店。
    只搬这一条录音；作废旧/新 session 分析；写审计日志 action='rebind'。"""
    u = current_user()
    data = request.get_json(silent=True) or {}
    to_customer_id = data.get("to_customer_id")
    reason = (data.get("reason") or "").strip()
    if not to_customer_id:
        return jsonify({"error": "请选择换绑目标顾客"}), 400
    if not reason:
        return jsonify({"error": "请填写换绑理由"}), 400
    rec, old_sess, scid, err = _admin_rec_scope_check(rid)
    if err:
        return err
    if _has_pending_delete_request(rid):
        return jsonify({"error": "该录音正在申请删除，请先处理删除申请再操作"}), 409

    advisor = old_sess["advisor"]
    service_date = old_sess["service_date"]
    au = db_fetchone(
        "SELECT id, advisor_name, store_id FROM users WHERE advisor_name=? AND company_id=? "
        "AND role IN ('consultant','store_manager') ORDER BY id LIMIT 1",
        (advisor, scid),
    )
    if not au:
        return jsonify({"error": "无法定位该接诊顾问账号，无法校验当日接诊"}), 400
    to_cust = db_fetchone(
        "SELECT id, name FROM company_customers WHERE id=? AND company_id=? AND merged_into IS NULL",
        (to_customer_id, scid),
    )
    if not to_cust:
        return jsonify({"error": "目标顾客不存在"}), 404
    # 目标可以是任意已有客人；若当天还没登记到该顾问名下，自动补登当日接诊
    dr = db_fetchone(
        """SELECT id FROM daily_reception
           WHERE advisor_user_id=? AND customer_id=? AND service_date=?""",
        (au["id"], to_customer_id, service_date),
    )
    if not dr:
        db_write(
            """INSERT OR IGNORE INTO daily_reception
               (company_id, advisor_user_id, advisor_name, customer_id, service_date, store_id)
               VALUES (?,?,?,?,?,?)""",
            (scid, au["id"], au["advisor_name"] or advisor, to_customer_id, service_date, au["store_id"]),
        )
    new_sid = get_or_create_session(advisor, to_cust["name"], service_date,
                                    company_id=scid, customer_id=to_customer_id)
    if not new_sid:
        return jsonify({"error": "创建目标接诊包失败"}), 500
    if new_sid == rec["session_id"]:
        return jsonify({"error": "目标顾客与当前一致，无需换绑"}), 400
    new_locked = db_fetchone("SELECT locked FROM sessions WHERE id=?", (new_sid,))
    if new_locked and new_locked["locked"]:
        return jsonify({"error": "目标接诊包已锁定，无法换绑"}), 409

    try:
        new_key, old_key_to_del = _maybe_rename_consultant_oss(rec, to_cust["name"], advisor)
    except Exception as e:
        return jsonify({"error": f"OSS 重命名失败：{e}"}), 500
    try:
        db_write(
            """UPDATE recordings SET session_id=?, customer=?, oss_key=?, speaker_confirmed=0,
               asr_status=CASE WHEN asr_status='awaiting_intake' THEN 'pending' ELSE asr_status END
               WHERE id=?""",
            (new_sid, to_cust["name"], new_key, rid),
        )
    except Exception as e:
        if old_key_to_del and new_key != old_key_to_del:
            _oss_delete_quiet(new_key)
        return jsonify({"error": f"换绑失败：{e}"}), 500
    if old_key_to_del and new_key != old_key_to_del:
        _oss_delete_quiet(old_key_to_del)

    # 新 session：作废旧分析（仅 done/failed 标 outdated）
    db_write(
        """UPDATE sessions SET locked=0,
               analysis_status=CASE WHEN analysis_status IN ('done','failed') THEN 'outdated' ELSE analysis_status END
           WHERE id=?""",
        (new_sid,),
    )
    # 旧 session：变空则清分析，非空则作废
    old_sid = rec["session_id"]
    from_cust_name = old_sess["customer"] or ""
    from_cust = db_fetchone(
        "SELECT id FROM company_customers WHERE company_id=? AND name=?",
        (scid, from_cust_name),
    )
    from_cust_id = from_cust["id"] if from_cust else None
    cnt = db_fetchone("SELECT COUNT(*) AS n FROM recordings WHERE session_id=?", (old_sid,))
    if not cnt or not cnt["n"]:
        db_write(
            """UPDATE sessions SET locked=0,
                   analysis_status=NULL, analysis_result=NULL, analysis_error=NULL,
                   analysis_started_at=NULL, analysis_finished_at=NULL,
                   analysis_signature=NULL, analysis_scores=NULL,
                   analysis_progress=NULL, task_status=NULL
               WHERE id=?""",
            (old_sid,),
        )
    else:
        db_write(
            """UPDATE sessions SET locked=0,
                   analysis_status=CASE WHEN analysis_status IN ('done','failed') THEN 'outdated' ELSE analysis_status END
               WHERE id=?""",
            (old_sid,),
        )
    # 审计日志
    db_write(
        """INSERT INTO rebind_requests
           (company_id, recording_id, rec_date, requester_user_id, requester_name,
            from_session_id, from_customer_id, from_customer_name,
            to_customer_id, to_customer_name, reason, status, action,
            reviewer_user_id, reviewer_name, reviewed_at)
           VALUES (?,?,?,?,?,?,?,?,?,?,?,'done','rebind',?,?,datetime('now','localtime'))""",
        (scid, rid, service_date, u["id"], u["advisor_name"] or u["username"],
         old_sid, from_cust_id, from_cust_name,
         to_cust["id"], to_cust["name"], reason,
         u["id"], u["advisor_name"] or u["username"]),
    )
    # 触发新 session 分析（录音 ASR 完成后才会真正开跑）
    maybe_trigger_session_analysis(new_sid)
    return jsonify({"ok": True, "new_session_id": new_sid})


@app.route("/api/admin/recordings/<int:rid>/add_day_customer", methods=["POST"])
@manager_required
def api_admin_recording_add_day_customer(rid):
    """换绑弹窗里"＋新增当日客人"：为该录音所属顾问、当日建一个客人并登记进当日接诊，
    返回 customer_id 供前端直接作为换绑目标。"""
    data = request.get_json(silent=True) or {}
    name = (data.get("name") or "").strip()
    phone_tail = (data.get("phone_tail") or "").strip()
    if not name:
        return jsonify({"error": "请填写客人姓名"}), 400
    if phone_tail and not re.fullmatch(r"\d{4}", phone_tail):
        return jsonify({"error": "手机尾号必须是 4 位数字"}), 400
    rec, old_sess, scid, err = _admin_rec_scope_check(rid)
    if err:
        return err
    advisor = old_sess["advisor"]
    service_date = old_sess["service_date"]
    au = db_fetchone(
        "SELECT id, advisor_name, store_id FROM users WHERE advisor_name=? AND company_id=? "
        "AND role IN ('consultant','store_manager') ORDER BY id LIMIT 1",
        (advisor, scid),
    )
    if not au:
        return jsonify({"error": "无法定位该接诊顾问账号"}), 400
    member_card = _generate_member_card(scid)
    new_cust_id = db_write(
        "INSERT INTO company_customers (company_id, name, member_card, phone_tail) VALUES (?, ?, ?, ?)",
        (scid, name, member_card, phone_tail or None),
    )
    db_write(
        """INSERT OR IGNORE INTO daily_reception
           (company_id, advisor_user_id, advisor_name, customer_id, service_date, store_id)
           VALUES (?,?,?,?,?,?)""",
        (scid, au["id"], au["advisor_name"] or advisor, new_cust_id, service_date, au["store_id"]),
    )
    return jsonify({"ok": True, "customer_id": new_cust_id, "name": name,
                    "phone_tail": phone_tail or None, "member_card": member_card})


@app.route("/api/admin/recordings/<int:rid>/customer_options")
@manager_required
def api_admin_recording_customer_options(rid):
    """换绑弹窗：列出本公司全部可选客人（排除已合并），标出哪些已在该顾问当日接诊。
    选中非当日客人换绑时，后端会自动把他补登进该顾问当日接诊。"""
    rec, old_sess, scid, err = _admin_rec_scope_check(rid)
    if err:
        return err
    advisor = old_sess["advisor"]
    service_date = old_sess["service_date"]
    au = db_fetchone(
        "SELECT id FROM users WHERE advisor_name=? AND company_id=? "
        "AND role IN ('consultant','store_manager') ORDER BY id LIMIT 1",
        (advisor, scid),
    )
    day_ids = set()
    if au:
        for r in db_fetchall(
            "SELECT customer_id FROM daily_reception WHERE advisor_user_id=? AND service_date=?",
            (au["id"], service_date)):
            day_ids.add(r["customer_id"])
    # 默认只显示该顾问当日接诊客人；要绑别人则搜索姓名/会员号/尾号（LIKE，LIMIT 50）。
    # 客人量可能上万，绝不一次全返回。
    q = (request.args.get("q") or "").strip()
    if q:
        like = f"%{q}%"
        rows = db_fetchall(
            "SELECT id, name, member_card, phone_tail FROM company_customers "
            "WHERE company_id=? AND merged_into IS NULL "
            "AND (name LIKE ? OR member_card LIKE ? OR phone_tail LIKE ?) "
            "ORDER BY id DESC LIMIT 50",
            (scid, like, like, like),
        )
    elif day_ids:
        qm = ",".join(["?"] * len(day_ids))
        rows = db_fetchall(
            f"SELECT id, name, member_card, phone_tail FROM company_customers "
            f"WHERE id IN ({qm}) AND merged_into IS NULL ORDER BY id DESC",
            tuple(day_ids),
        )
    else:
        rows = []
    items = [{"customer_id": r["id"], "name": r["name"], "member_card": r["member_card"],
              "phone_tail": r["phone_tail"], "in_day": r["id"] in day_ids} for r in rows]
    # 当日接诊的排前面，方便选
    items.sort(key=lambda x: (not x["in_day"],))
    return jsonify({"advisor": advisor, "service_date": service_date,
                    "current_customer": old_sess["customer"],
                    "current_customer_id": old_sess["customer_id"],
                    "items": items})


@app.route("/api/admin/recordings/<int:rid>/remove_day_customer", methods=["POST"])
@manager_required
def api_admin_recording_remove_day_customer(rid):
    """删除误建的客人（如换绑时名字填错）：仅当该客人名下没有任何录音时允许，
    同时删掉其当日接诊登记、空 session 和客人档案；有数据则拒绝，防误删。"""
    data = request.get_json(silent=True) or {}
    customer_id = data.get("customer_id")
    if not customer_id:
        return jsonify({"error": "缺少 customer_id"}), 400
    rec, old_sess, scid, err = _admin_rec_scope_check(rid)
    if err:
        return err
    cust = db_fetchone(
        "SELECT id, name FROM company_customers WHERE id=? AND company_id=?",
        (customer_id, scid),
    )
    if not cust:
        return jsonify({"error": "客人不存在"}), 404
    # 安全闸：名下任一 session 只要有录音就不许删（防误删有历史数据的客人）
    rec_cnt = db_fetchone(
        """SELECT COUNT(*) AS n FROM recordings r
           JOIN sessions s ON s.id=r.session_id WHERE s.customer_id=?""",
        (customer_id,),
    )
    if rec_cnt and rec_cnt["n"]:
        return jsonify({"error": "该客人名下已有录音/接诊数据，不能删除"}), 409
    db_write("DELETE FROM sessions WHERE customer_id=?", (customer_id,))
    db_write("DELETE FROM daily_reception WHERE customer_id=?", (customer_id,))
    db_write("DELETE FROM company_customers WHERE id=?", (customer_id,))
    return jsonify({"ok": True})


def _ffprobe_duration(path):
    """返回音频时长秒数（float）；失败返回 None。"""
    import subprocess
    try:
        out = subprocess.run(
            ["ffprobe", "-v", "error", "-show_entries", "format=duration",
             "-of", "default=noprint_wrappers=1:nokey=1", path],
            capture_output=True, text=True, timeout=60)
        return float((out.stdout or "").strip())
    except Exception:
        return None


class _SplitGoneError(Exception):
    """分割提交时原录音已不存在/已被分割（重复触发或并发），用于幂等中止。"""
    pass


def _run_ffmpeg(args):
    import subprocess
    r = subprocess.run(["ffmpeg", "-y", "-loglevel", "error"] + args,
                       capture_output=True, text=True, timeout=600)
    if r.returncode != 0:
        raise RuntimeError(f"ffmpeg 失败: {(r.stderr or '')[:300]}")


def _split_asr_at(asr_json_str, t_ms):
    """把已有 ASR 结果按 t_ms 切成前后两份，避免重新转写。
    返回 [(json1,transcript1,spk1),(json2,transcript2,spk2)]；无法解析返回 None。
    句子按 begin_time 归属；part2 的时间戳整体左移 t_ms。"""
    import copy
    try:
        full = json.loads(asr_json_str) if asr_json_str else None
    except (json.JSONDecodeError, TypeError):
        full = None
    if not full:
        return None

    def build(which):  # which: 1=前段, 2=后段
        spk, lines, out = set(), [], []
        for detailed in full:
            d2 = copy.deepcopy(detailed)
            for tr in d2.get("transcripts", []):
                kept = []
                for s in tr.get("sentences", []):
                    bt = s.get("begin_time", 0) or 0
                    if (which == 1) != (bt < t_ms):
                        continue
                    s2 = dict(s)
                    if which == 2:
                        s2["begin_time"] = max(0, (s.get("begin_time", 0) or 0) - t_ms)
                        s2["end_time"] = max(0, (s.get("end_time", 0) or 0) - t_ms)
                    kept.append(s2)
                    sp = s.get("speaker_id")
                    if sp is not None:
                        spk.add(sp)
                    speaker = f"说话人{sp}" if sp is not None else "说话人?"
                    lines.append(f"[{(s2['begin_time'] or 0)/1000.0:.2f}s - "
                                 f"{(s2['end_time'] or 0)/1000.0:.2f}s] {speaker}: {s.get('text','')}")
                tr["sentences"] = kept
                if "text" in tr:
                    tr["text"] = "".join(x.get("text", "") for x in kept)
            out.append(d2)
        return out, "\n".join(lines), len(spk)
    return [build(1), build(2)]


@app.route("/api/admin/recordings/<int:rid>/split", methods=["POST"])
@manager_required
def api_admin_recording_split(rid):
    """把一条录音在指定秒数处切成两段（转码 mp3），都留在原接诊里，
    切完用「按录音换绑」分别绑定。默认按已有 ASR 转录切分、不重新转写；
    原录音若还没转写完才回退重跑 ASR。原录音删除（OSS+DB）。"""
    import tempfile, shutil
    data = request.get_json(silent=True) or {}
    try:
        at = float(data.get("at_seconds"))
    except (TypeError, ValueError):
        return jsonify({"error": "切分位置无效"}), 400
    if at <= 0.5:
        return jsonify({"error": "切分位置太靠近开头，请往后一点"}), 400
    rec, old_sess, scid, err = _admin_rec_scope_check(rid)
    if err:
        return err
    if _has_pending_delete_request(rid):
        return jsonify({"error": "该录音正在申请删除，请先处理删除申请再操作"}), 409
    if not rec["oss_key"]:
        return jsonify({"error": "录音文件缺失"}), 400
    base = rec["oss_key"].rsplit("/", 1)[-1]
    src_ext = (base.rsplit(".", 1)[-1] if "." in base else "webm").lower()
    tmpdir = tempfile.mkdtemp(prefix="recsplit_")
    src_path = os.path.join(tmpdir, f"src.{src_ext}")
    p1 = os.path.join(tmpdir, "p1.wav")
    p2 = os.path.join(tmpdir, "p2.wav")
    k1 = k2 = None
    parts_created = False
    try:
        oss_bucket.get_object_to_file(rec["oss_key"], src_path)
        # 不读源文件时长（浏览器 webm 常无时长头会返回 N/A）。直接切，再读切出的 mp3 时长校验。
        _run_ffmpeg(["-i", src_path, "-t", f"{at:.3f}", "-vn", "-acodec", "pcm_s16le", p1])
        _run_ffmpeg(["-i", src_path, "-ss", f"{at:.3f}", "-vn", "-acodec", "pcm_s16le", p2])
        d1, d2 = _ffprobe_duration(p1), _ffprobe_duration(p2)
        if not d1 or d1 < 0.3:
            return jsonify({"error": "切分位置太靠近开头，请往后一点"}), 400
        if not d2 or d2 < 0.3:
            return jsonify({"error": "切分位置超出了录音末尾，请往前一点"}), 400

        # 优先按已有转录切分，避免重新 ASR；原录音没转写完才回退重跑
        split_asr = None
        if rec["asr_status"] == "done" and rec["asr_result_json"]:
            split_asr = _split_asr_at(rec["asr_result_json"], int(round(at * 1000)))
        reasr = split_asr is None

        stem = rec["oss_key"][:-(len(src_ext) + 1)] if "." in base else rec["oss_key"]
        k1 = f"{stem}_p1_{_uuid.uuid4().hex[:8]}.wav"
        k2 = f"{stem}_p2_{_uuid.uuid4().hex[:8]}.wav"
        # 仅 OSS 双写在 DB 提交前发生；失败会在 except 里清掉 k1/k2
        oss_bucket.put_object_from_file(k1, p1)
        oss_bucket.put_object_from_file(k2, p2)

        size1, size2 = os.path.getsize(p1), os.path.getsize(p2)

        def _insert_sql(cur, oss_key, size, dur, idx):
            label = _format_duration_label(dur)
            if split_asr:
                j, tx, spk = split_asr[idx]
                return cur.execute(
                    """INSERT INTO recordings
                       (session_id, oss_key, advisor, customer, recorded_at, duration_label,
                        size_bytes, source, company_id, uploader_user_id, store_id,
                        asr_status, asr_result_json, asr_transcript, asr_speaker_count,
                        asr_speaker_warning, speaker_confirmed, asr_finished_at)
                       VALUES (?,?,?,?,?,?,?,?,?,?,?,'done',?,?,?,0,?,datetime('now','localtime'))""",
                    (rec["session_id"], oss_key, old_sess["advisor"], old_sess["customer"],
                     rec["recorded_at"], label, size, "split", scid, rec["uploader_user_id"],
                     rec["store_id"], json.dumps(j, ensure_ascii=False), tx, spk,
                     rec["speaker_confirmed"] or 0),
                ).lastrowid
            return cur.execute(
                """INSERT INTO recordings
                   (session_id, oss_key, advisor, customer, recorded_at, duration_label,
                    size_bytes, source, company_id, uploader_user_id, store_id, asr_status)
                   VALUES (?,?,?,?,?,?,?,?,?,?,?,'pending')""",
                (rec["session_id"], oss_key, old_sess["advisor"], old_sess["customer"],
                 rec["recorded_at"], label, size, "split", scid, rec["uploader_user_id"],
                 rec["store_id"]),
            ).lastrowid

        # ===== 单一提交点：两条新录音 INSERT + 删原行 + session 标过期，全在同一事务 =====
        # 要么都成功提交、要么整体回滚；杜绝"原行已删但新行未提交"或"原行残留+两段并存"。
        def _commit_split(conn):
            cur = conn.cursor()
            # 事务内复核原录音仍在：防并发/重复触发（第二个请求删不到行→幂等中止）。
            still = cur.execute(
                "SELECT 1 FROM recordings WHERE id=?", (rid,)
            ).fetchone()
            if still is None:
                raise _SplitGoneError()
            nid1 = _insert_sql(cur, k1, size1, d1, 0)
            nid2 = _insert_sql(cur, k2, size2, d2, 1)
            cur.execute("DELETE FROM delete_requests WHERE recording_id=?", (rid,))
            # 用 rowcount 兜底：原行此刻被并发删掉则视为已被分割，回滚整事务。
            dr = cur.execute("DELETE FROM recordings WHERE id=?", (rid,)).rowcount
            if not dr:
                raise _SplitGoneError()
            cur.execute(
                """UPDATE sessions SET analysis_status=CASE WHEN analysis_status IN ('done','failed')
                       THEN 'outdated' ELSE analysis_status END WHERE id=?""",
                (rec["session_id"],),
            )
            conn.commit()
            return nid1, nid2

        id1, id2 = db_batch(_commit_split)
        parts_created = True  # 已提交：k1/k2 现由新行引用，不可再清理
        # 提交成功后才排 ASR（避免为回滚掉的行起转写）
        trigger_pipeline_for_recording(id1)
        trigger_pipeline_for_recording(id2)
        # 删原 OSS 对象：失败仅记日志、不影响主流程（孤儿对象可接受，远好于 DB 不一致）
        _oss_delete_quiet(rec["oss_key"])
        return jsonify({"ok": True, "parts": [id1, id2],
                        "session_id": rec["session_id"], "reasr": reasr})
    except _SplitGoneError:
        # 幂等：原录音已不存在/已被分割（重复触发或并发）。清掉本次刚传的 k1/k2。
        if not parts_created:
            if k1:
                _oss_delete_quiet(k1)
            if k2:
                _oss_delete_quiet(k2)
        return jsonify({"error": "该录音已被分割或已不存在，无需重复操作"}), 409
    except Exception as e:
        # DB 提交前的任何失败（下载/ffmpeg/OSS 上传/事务）都走这里清理 k1/k2。
        if not parts_created:
            if k1:
                _oss_delete_quiet(k1)
            if k2:
                _oss_delete_quiet(k2)
        app.logger.exception("录音分割失败")
        return jsonify({"error": f"分割失败：{str(e)[:200]}"}), 500
    finally:
        shutil.rmtree(tmpdir, ignore_errors=True)


@app.route("/api/admin/bind_advisors")
@manager_required
def api_admin_bind_advisors():
    """代绑弹窗：可选顾问列表（本公司 consultant/store_manager；店长仅本店）。"""
    cid = session.get("company_id") or 1
    is_super = session.get("role") == "super"
    where = "role IN ('consultant','store_manager')"
    params = []
    if not is_super:
        where += " AND company_id=?"
        params.append(cid)
    if session.get("role") == "store_manager":
        where += " AND store_id=?"
        params.append(session.get("store_id") or 0)
    rows = db_fetchall(
        f"SELECT id, advisor_name, username, store_id FROM users WHERE {where} ORDER BY advisor_name",
        tuple(params),
    )
    out = [{"id": r["id"], "name": r["advisor_name"] or r["username"], "store_id": r["store_id"]} for r in rows]
    return jsonify({"items": out})


@app.route("/api/admin/advisor_reception")
@manager_required
def api_admin_advisor_reception():
    """代绑弹窗：某顾问某日已有的接诊客人列表。"""
    cid = session.get("company_id") or 1
    advisor_user_id = request.args.get("advisor_user_id")
    date = _norm_date(request.args.get("date"))
    if not advisor_user_id or not date:
        return jsonify({"items": []})
    au, err = _advisor_user_for_manager(advisor_user_id, cid)
    if err:
        return err
    rows = db_fetchall(
        """SELECT dr.customer_id, c.name, c.phone_tail, c.member_card
           FROM daily_reception dr JOIN company_customers c ON c.id=dr.customer_id
           WHERE dr.advisor_user_id=? AND dr.service_date=?
           ORDER BY dr.id DESC""",
        (au["id"], date),
    )
    items = [{"customer_id": r["customer_id"], "name": r["name"],
              "phone_tail": r["phone_tail"], "member_card": r["member_card"]} for r in rows]
    return jsonify({"items": items})


@app.route("/api/admin/rebind_requests")
@login_required
def api_admin_rebind_requests():
    if session.get("role") not in ("admin", "super"):
        return jsonify({"error": "仅管理员可查看"}), 403
    status = request.args.get("status", "pending")
    role = session.get("role")
    my_cid = session.get("company_id") or 1
    if role == "super":
        rows = db_fetchall(
            """SELECT rr.*, r.oss_key, r.recorded_at, r.duration_label
               FROM rebind_requests rr
               LEFT JOIN recordings r ON r.id=rr.recording_id
               WHERE rr.status=? ORDER BY rr.id DESC LIMIT 200""",
            (status,),
        )
    else:
        rows = db_fetchall(
            """SELECT rr.*, r.oss_key, r.recorded_at, r.duration_label
               FROM rebind_requests rr
               LEFT JOIN recordings r ON r.id=rr.recording_id
               WHERE rr.status=? AND rr.company_id=? ORDER BY rr.id DESC LIMIT 200""",
            (status, my_cid),
        )
    out = []
    for r in rows:
        d = dict(r)
        try:
            d["audio_url"] = oss_signed_url(d["oss_key"], expires=3600) if d.get("oss_key") else None
        except Exception:
            d["audio_url"] = None
        out.append(d)
    return jsonify({"requests": out})


@app.route("/api/admin/rebind_logs")
@login_required
def api_admin_rebind_logs():
    """换绑/解绑日志（合并历史申请数据）。管理员可看本公司，super 看全部。"""
    if session.get("role") not in ("admin", "super"):
        return jsonify({"error": "仅管理员可查看"}), 403
    role = session.get("role")
    my_cid = session.get("company_id") or 1
    action = request.args.get("action", "")  # '' / 'rebind' / 'unbind'
    where = []
    params = []
    if role != "super":
        where.append("rr.company_id=?"); params.append(my_cid)
    if action in ("rebind", "unbind"):
        where.append("COALESCE(rr.action,'rebind')=?"); params.append(action)
    sql = """SELECT rr.*, COALESCE(rr.action,'rebind') AS action_eff,
                    r.oss_key, r.recorded_at, r.duration_label,
                    c.name AS company_name
             FROM rebind_requests rr
             LEFT JOIN recordings r ON r.id=rr.recording_id
             LEFT JOIN companies c ON c.id=rr.company_id"""
    if where:
        sql += " WHERE " + " AND ".join(where)
    sql += " ORDER BY rr.id DESC LIMIT 500"
    rows = db_fetchall(sql, tuple(params))
    out = []
    for r in rows:
        d = dict(r)
        try:
            d["audio_url"] = oss_signed_url(d["oss_key"], expires=3600) if d.get("oss_key") else None
        except Exception:
            d["audio_url"] = None
        out.append(d)
    return jsonify({"logs": out})


@app.route("/api/admin/rebind_requests/<int:req_id>/approve", methods=["POST"])
@login_required
def api_admin_rebind_approve(req_id):
    """同意换绑：移动 recording 到新 session，作废涉及到的旧/新 session 的分析，删除空的旧接诊登记。"""
    if session.get("role") not in ("admin", "super"):
        return jsonify({"error": "仅管理员可操作"}), 403
    rr = db_fetchone("SELECT * FROM rebind_requests WHERE id=?", (req_id,))
    if not rr:
        return jsonify({"error": "申请不存在"}), 404
    if rr["status"] != "pending":
        return jsonify({"error": "该申请已处理"}), 409
    if session.get("role") == "admin":
        if (rr["company_id"] or 1) != (session.get("company_id") or 1):
            return jsonify({"error": "无权操作他公司申请"}), 403
    rec = db_fetchone("SELECT * FROM recordings WHERE id=?", (rr["recording_id"],))
    if not rec:
        return jsonify({"error": "录音不存在"}), 404
    cid = rr["company_id"] or 1
    advisor = rr["requester_name"]
    # 目标顾客 daily_reception 是否还在？
    to_cust = db_fetchone(
        "SELECT id, name FROM company_customers WHERE id=? AND company_id=?",
        (rr["to_customer_id"], cid),
    )
    if not to_cust:
        return jsonify({"error": "目标顾客已不存在，无法换绑"}), 400
    dr_to = db_fetchone(
        """SELECT id FROM daily_reception
           WHERE advisor_user_id=? AND customer_id=? AND service_date=?""",
        (rr["requester_user_id"], rr["to_customer_id"], rr["rec_date"]),
    )
    if not dr_to:
        return jsonify({"error": "目标顾客已不在录音当天接诊列表，无法换绑"}), 400
    old_session_id = rec["session_id"]
    # 目标 session：找到 / 创建
    new_sid = get_or_create_session(advisor, to_cust["name"], rr["rec_date"], company_id=cid)
    if not new_sid:
        return jsonify({"error": "创建目标接诊包失败"}), 500
    # OSS 重命名（新格式录音）；失败 → 回滚（不改 DB，申请保持 pending 待重试）
    try:
        new_key, old_key_to_del = _maybe_rename_consultant_oss(rec, to_cust["name"], advisor)
    except Exception as e:
        return jsonify({"error": f"OSS 重命名失败：{e}"}), 500
    # 把 recording 移到 new session
    try:
        db_write(
            """UPDATE recordings SET session_id=?, customer=?, oss_key=?, speaker_confirmed=0,
               asr_status=CASE WHEN asr_status='awaiting_intake' THEN 'pending' ELSE asr_status END
               WHERE id=?""",
            (new_sid, to_cust["name"], new_key, rr["recording_id"]),
        )
    except Exception as e:
        if old_key_to_del and new_key != old_key_to_del:
            _oss_delete_quiet(new_key)
        return jsonify({"error": f"换绑失败：{e}"}), 500
    if old_key_to_del and new_key != old_key_to_del:
        _oss_delete_quiet(old_key_to_del)
    # 作废目标 session 的旧分析（解锁 + 标记 outdated），便于重新分析
    db_write(
        """UPDATE sessions SET locked=0,
                              analysis_status=CASE WHEN analysis_status IS NULL OR analysis_status='' THEN NULL ELSE 'outdated' END
           WHERE id=?""",
        (new_sid,),
    )
    # 老 session：若清空则删除；否则同样作废分析
    if old_session_id:
        cnt = db_fetchone("SELECT COUNT(*) AS n FROM recordings WHERE session_id=?", (old_session_id,))
        if not cnt or not cnt["n"]:
            # 删除老 session 及对应 daily_reception
            old_sess = db_fetchone("SELECT advisor, customer, service_date FROM sessions WHERE id=?", (old_session_id,))
            db_write("DELETE FROM sessions WHERE id=?", (old_session_id,))
            if old_sess and rr["from_customer_id"]:
                db_write(
                    """DELETE FROM daily_reception
                       WHERE advisor_user_id=? AND customer_id=? AND service_date=?""",
                    (rr["requester_user_id"], rr["from_customer_id"], rr["rec_date"]),
                )
        else:
            db_write(
                """UPDATE sessions SET locked=0,
                                      analysis_status=CASE WHEN analysis_status IS NULL OR analysis_status='' THEN NULL ELSE 'outdated' END
                   WHERE id=?""",
                (old_session_id,),
            )
    # 标记申请
    reviewer = session.get("username") or "管理员"
    db_write(
        """UPDATE rebind_requests
           SET status='approved', reviewer_user_id=?, reviewer_name=?, reviewed_at=datetime('now','localtime')
           WHERE id=?""",
        (session.get("user_id"), reviewer, req_id),
    )
    return jsonify({"ok": True, "new_session_id": new_sid})


@app.route("/api/admin/rebind_requests/<int:req_id>/reject", methods=["POST"])
@login_required
def api_admin_rebind_reject(req_id):
    if session.get("role") not in ("admin", "super"):
        return jsonify({"error": "仅管理员可操作"}), 403
    data = request.get_json(silent=True) or {}
    reject_reason = (data.get("reject_reason") or "").strip()
    if not reject_reason:
        return jsonify({"error": "请填写驳回原因"}), 400
    rr = db_fetchone("SELECT * FROM rebind_requests WHERE id=?", (req_id,))
    if not rr:
        return jsonify({"error": "申请不存在"}), 404
    if rr["status"] != "pending":
        return jsonify({"error": "该申请已处理"}), 409
    if session.get("role") == "admin":
        if (rr["company_id"] or 1) != (session.get("company_id") or 1):
            return jsonify({"error": "无权操作他公司申请"}), 403
    reviewer = session.get("username") or "管理员"
    db_write(
        """UPDATE rebind_requests
           SET status='rejected', reviewer_user_id=?, reviewer_name=?, reviewed_at=datetime('now','localtime'),
               reject_reason=?
           WHERE id=?""",
        (session.get("user_id"), reviewer, reject_reason, req_id),
    )
    return jsonify({"ok": True})


@app.route("/api/consultant/customer_lookup")
@login_required
def api_consultant_customer_lookup():
    """顾问端搜索本公司顾客（用于"加入今日接诊"时找已有顾客）"""
    err = _consultant_required()
    if err:
        return err
    u = current_user()
    cid = u["company_id"] or 1
    q = (request.args.get("q") or "").strip()
    if q:
        # 有关键字：全公司按 姓名/手机尾号/会员号 搜（加入接诊找已有顾客 / 客户tab 搜索）
        like = f"%{q}%"
        rows = db_fetchall(
            """SELECT id, name, phone_tail, member_card FROM company_customers
               WHERE company_id=?
                 AND (name LIKE ? OR phone_tail LIKE ? OR member_card LIKE ?)
               ORDER BY id DESC LIMIT 30""",
            (cid, like, like, like),
        )
    else:
        # 空关键字（客户 tab 默认）：本顾问【最近一个月接待过】的客户，最近接待优先
        advisor = u["advisor_name"] or u["username"]
        rows = db_fetchall(
            """SELECT cc.id, cc.name, cc.phone_tail, cc.member_card,
                      MAX(s.service_date) AS last_date
               FROM sessions s
               JOIN company_customers cc ON cc.id = s.customer_id
               WHERE s.company_id=? AND s.advisor=? AND s.customer_id IS NOT NULL
                 AND s.service_date >= date('now','localtime','-30 days')
               GROUP BY cc.id, cc.name, cc.phone_tail, cc.member_card
               ORDER BY last_date DESC, cc.id DESC LIMIT 50""",
            (cid, advisor),
        )
    return jsonify({"customers": [dict(r) for r in rows]})


@app.route("/api/consultant/recordings/needs_confirm")
@login_required
def api_consultant_recordings_needs_confirm():
    """绑定后 ASR 检测到 >2 个说话人、尚未顾问确认的录音"""
    err = _consultant_required()
    if err:
        return err
    u = current_user()
    rows = db_fetchall(
        """SELECT id, oss_key, recorded_at, customer, advisor, session_id,
                  asr_speaker_count, asr_status, created_at
           FROM recordings
           WHERE uploader_user_id=? AND session_id IS NOT NULL
             AND asr_speaker_warning=1 AND COALESCE(speaker_confirmed,0)=0
           ORDER BY id DESC LIMIT 50""",
        (u["id"],),
    )
    out = []
    for r in rows:
        d = dict(r)
        try:
            d["audio_url"] = oss_signed_url(d["oss_key"], expires=3600)
        except Exception:
            d["audio_url"] = None
        out.append(d)
    return jsonify({"recordings": out})


@app.route("/api/consultant/recordings/<int:rid>/confirm_speakers", methods=["POST"])
@login_required
def api_consultant_confirm_speakers(rid):
    """action=keep 表示顾问确认照常分析；action=unbind 表示解绑回未入库以便拆分重传"""
    err = _consultant_required()
    if err:
        return err
    u = current_user()
    data = request.get_json(silent=True) or {}
    action = (data.get("action") or "").strip()
    rec = db_fetchone("SELECT * FROM recordings WHERE id=?", (rid,))
    if not rec:
        return jsonify({"error": "录音不存在"}), 404
    if rec["uploader_user_id"] and rec["uploader_user_id"] != u["id"]:
        return jsonify({"error": "无权操作他人录音"}), 403
    if action == "keep":
        db_write("UPDATE recordings SET speaker_confirmed=1 WHERE id=?", (rid,))
        if rec["session_id"]:
            try:
                maybe_trigger_session_analysis(rec["session_id"])
            except Exception as e:
                app.logger.warning("auto-trigger after confirm failed: %s", e)
        return jsonify({"ok": True})
    if action == "unbind":
        db_write(
            """UPDATE recordings SET session_id=NULL, customer=NULL,
               speaker_confirmed=0 WHERE id=?""",
            (rid,),
        )
        return jsonify({"ok": True})
    return jsonify({"error": "未知 action"}), 400


@app.route("/api/recording/<int:rid>/confirm_speakers", methods=["POST"])
@admin_required
def api_admin_confirm_speakers(rid):
    """管理员/超管：确认说话人（照常分析）或解绑录音。
    action=keep: speaker_confirmed=1，并自动触发分析。
    action=unbind: 解绑回未入库。"""
    data = request.get_json(silent=True) or {}
    action = (data.get("action") or "").strip()
    rec = db_fetchone("SELECT * FROM recordings WHERE id=?", (rid,))
    if not rec:
        return jsonify({"error": "录音不存在"}), 404
    # 公司隔离：admin 只能操作本公司
    if session.get("role") == "admin":
        cid = session.get("company_id")
        if rec["company_id"] and cid and rec["company_id"] != cid:
            return jsonify({"error": "无权操作他公司录音"}), 403
    if action == "keep":
        db_write("UPDATE recordings SET speaker_confirmed=1 WHERE id=?", (rid,))
        triggered = False
        if rec["session_id"]:
            try:
                maybe_trigger_session_analysis(rec["session_id"])
                after = db_fetchone(
                    "SELECT analysis_status FROM sessions WHERE id=?",
                    (rec["session_id"],),
                )
                triggered = after and after["analysis_status"] in ("queued", "running")
            except Exception as e:
                app.logger.warning("auto-trigger after confirm failed: %s", e)
        return jsonify({"ok": True, "analysis_triggered": bool(triggered)})
    if action == "unbind":
        db_write(
            """UPDATE recordings SET session_id=NULL, customer=NULL,
               speaker_confirmed=0 WHERE id=?""",
            (rid,),
        )
        return jsonify({"ok": True})
    return jsonify({"error": "未知 action"}), 400


@app.route("/api/consultant/customer_recordings")
@login_required
def api_consultant_customer_recordings():
    """列出本顾问 + 指定顾客的所有录音，按 service_date 分组，给"按顾客分析"前预览用"""
    err = _consultant_required()
    if err:
        return err
    u = current_user()
    customer = (request.args.get("customer") or "").strip()
    if not customer:
        return jsonify({"error": "缺少 customer"}), 400
    advisor = u["advisor_name"] or u["username"]
    cid = u["company_id"] or 1
    rows = db_fetchall(
        """SELECT r.id, r.oss_key, r.recorded_at, r.duration_label,
                  r.asr_status, r.asr_speaker_count, r.asr_speaker_warning,
                  r.speaker_confirmed,
                  s.id AS session_id, s.service_date, s.analysis_status
           FROM recordings r
           JOIN sessions s ON s.id = r.session_id
           WHERE s.advisor=? AND s.customer=?
             AND (s.company_id IS NULL OR s.company_id=?)
           ORDER BY COALESCE(r.recorded_at,''), r.id""",
        (advisor, customer, cid),
    )
    groups = {}
    for r in rows:
        d = dict(r)
        try:
            d["audio_url"] = oss_signed_url(d["oss_key"], expires=3600)
        except Exception:
            d["audio_url"] = None
        sd = (d.get("service_date") or "")
        # 归一化到 YYYY-MM-DD
        if len(sd) == 8 and sd.isdigit():
            sd = f"{sd[:4]}-{sd[4:6]}-{sd[6:]}"
        d["service_date_norm"] = sd
        groups.setdefault(sd, []).append(d)
    out = [{"service_date": k, "recordings": groups[k]} for k in sorted(groups.keys())]
    return jsonify({"groups": out})


# ============ 接诊包：预览 / 移除 / 换绑 / 开始分析（锁定）============
@app.route("/api/consultant/session/preview")
@login_required
def api_consultant_session_preview():
    """按 customer_id + date 查接诊包：候选录音 + 未绑定可加入的录音。"""
    err = _consultant_required()
    if err:
        return err
    u = current_user()
    cid = u["company_id"] or 1
    customer_id = request.args.get("customer_id", type=int)
    date = _norm_date(request.args.get("date")) or _today_str()
    if not customer_id:
        return jsonify({"error": "缺少 customer_id"}), 400
    cust = db_fetchone(
        "SELECT id, name FROM company_customers WHERE id=? AND company_id=?",
        (customer_id, cid),
    )
    if not cust:
        return jsonify({"error": "顾客不存在"}), 404
    advisor = u["advisor_name"] or u["username"]
    sess = db_fetchone(
        """SELECT id, locked, analysis_status, analysis_progress, analysis_started_at
           FROM sessions
           WHERE advisor=? AND customer=? AND service_date=?
             AND (company_id IS NULL OR company_id=?)""",
        (advisor, cust["name"], date, cid),
    )
    bound = []
    if sess:
        rows = db_fetchall(
            """SELECT id, oss_key, recorded_at, duration_label,
                      asr_status, asr_speaker_count, asr_speaker_warning, speaker_confirmed
               FROM recordings WHERE session_id=? ORDER BY COALESCE(recorded_at,''), id""",
            (sess["id"],),
        )
        for r in rows:
            d = dict(r)
            try:
                d["audio_url"] = oss_signed_url(d["oss_key"], expires=3600)
            except Exception:
                d["audio_url"] = None
            prb = db_fetchone(
                "SELECT id FROM rebind_requests WHERE recording_id=? AND status='pending'",
                (d["id"],),
            )
            d["pending_rebind_request_id"] = prb["id"] if prb else None
            bound.append(d)
    # 未绑定录音：只列出实际日期 == 本接诊包 service_date 的（不跨天）
    unbound_rows = db_fetchall(
        """SELECT id, oss_key, recorded_at, duration_label, asr_status, created_at
           FROM recordings
           WHERE uploader_user_id=? AND session_id IS NULL
           ORDER BY id DESC LIMIT 200""",
        (u["id"],),
    )
    unbound = []
    for r in unbound_rows:
        if _rec_date_of(r) != date:
            continue
        d = dict(r)
        try:
            d["audio_url"] = oss_signed_url(d["oss_key"], expires=3600)
        except Exception:
            d["audio_url"] = None
        unbound.append(d)
    # 任务级进度（11 个任务的分桶 + 当前正在跑的任务名 + 失败列表）
    task_progress = None
    if sess:
        ts = get_task_status(sess["id"])
        done_names = []
        running_names = []
        failed_names = []
        pending_names = []
        for tid, meta in TASK_REGISTRY.items():
            st = (ts.get(tid) or {}).get("status") or "pending"
            label = meta.get("name") or tid
            if st == "done":
                done_names.append(label)
            elif st == "running":
                running_names.append(label)
            elif st == "failed":
                failed_names.append(label)
            else:
                pending_names.append(label)
        # 已等待时长
        started_at = None
        if "analysis_started_at" in sess.keys():
            started_at = sess["analysis_started_at"]
        else:
            row = db_fetchone("SELECT analysis_started_at FROM sessions WHERE id=?", (sess["id"],))
            started_at = row["analysis_started_at"] if row else None
        wait_sec = None
        if started_at and sess["analysis_status"] in ("running", "queued"):
            try:
                dt = datetime.strptime(started_at, "%Y-%m-%d %H:%M:%S")
                wait_sec = int((datetime.now() - dt).total_seconds())
                if wait_sec < 0:
                    wait_sec = 0
            except Exception:
                wait_sec = None
        task_progress = {
            "total": len(TASK_REGISTRY),
            "done": len(done_names),
            "running": len(running_names),
            "failed": len(failed_names),
            "pending": len(pending_names),
            "done_names": done_names,
            "running_names": running_names,
            "failed_names": failed_names,
            "pending_names": pending_names,
            "wait_sec": wait_sec,
            "progress_text": sess["analysis_progress"] if "analysis_progress" in sess.keys() else None,
        }
    return jsonify({
        "customer": dict(cust),
        "service_date": date,
        "session_id": sess["id"] if sess else None,
        "locked": bool(sess["locked"]) if sess else False,
        "analysis_status": sess["analysis_status"] if sess else None,
        "task_progress": task_progress,
        "bound": bound,
        "unbound": unbound,
    })


def _assert_session_unlocked_for_consultant(session_id):
    s = db_fetchone("SELECT id, locked FROM sessions WHERE id=?", (session_id,))
    if not s:
        return jsonify({"error": "session 不存在"}), 404
    if s["locked"]:
        return jsonify({"error": "该接诊包已锁定，如需修改请联系管理员"}), 409
    return None


@app.route("/api/consultant/session/preview/remove", methods=["POST"])
@login_required
def api_consultant_session_preview_remove():
    """把某段录音从接诊包剔除（session_id 置空，customer 也清掉）"""
    err = _consultant_required()
    if err:
        return err
    u = current_user()
    data = request.get_json(silent=True) or {}
    rid = data.get("recording_id")
    if not rid:
        return jsonify({"error": "缺少 recording_id"}), 400
    rec = db_fetchone("SELECT * FROM recordings WHERE id=?", (rid,))
    if not rec:
        return jsonify({"error": "录音不存在"}), 404
    if rec["uploader_user_id"] and rec["uploader_user_id"] != u["id"]:
        return jsonify({"error": "无权操作他人录音"}), 403
    if rec["session_id"]:
        err2 = _assert_session_unlocked_for_consultant(rec["session_id"])
        if err2:
            return err2
    db_write(
        "UPDATE recordings SET session_id=NULL, customer=NULL, speaker_confirmed=0 WHERE id=?",
        (rid,),
    )
    return jsonify({"ok": True})


@app.route("/api/consultant/session/start_analysis", methods=["POST"])
@login_required
def api_consultant_session_start_analysis():
    """锁定 session + 智能触发分析（跳过已成功 task + 防滥用）。"""
    err = _consultant_required()
    if err:
        return err
    u = current_user()
    cid = u["company_id"] or 1
    data = request.get_json(silent=True) or {}
    customer_id = data.get("customer_id")
    date = _norm_date(data.get("date")) or _today_str()
    if not customer_id:
        return jsonify({"error": "缺少 customer_id"}), 400
    cust = db_fetchone(
        "SELECT id, name FROM company_customers WHERE id=? AND company_id=?",
        (customer_id, cid),
    )
    if not cust:
        return jsonify({"error": "顾客不存在"}), 404
    advisor = u["advisor_name"] or u["username"]
    sess = db_fetchone(
        """SELECT id, locked FROM sessions
           WHERE advisor=? AND customer=? AND service_date=?
             AND (company_id IS NULL OR company_id=?)""",
        (advisor, cust["name"], date, cid),
    )
    if not sess:
        return jsonify({"error": "尚无录音，无法分析"}), 400
    cnt = db_fetchone("SELECT COUNT(*) AS n FROM recordings WHERE session_id=?", (sess["id"],))
    if not cnt or not cnt["n"]:
        return jsonify({"error": "接诊包内没有录音"}), 400

    # ── 防滥用 + 智能分析逻辑 ──
    sess_detail = db_fetchone(
        "SELECT locked, analysis_status, analysis_signature FROM sessions WHERE id=?",
        (sess["id"],),
    )

    # ① 正在跑的不允许重复提交
    if sess_detail["analysis_status"] in ("running", "queued"):
        return jsonify({"error": "分析正在进行中，请等待完成"}), 409

    sig = compute_session_signature(sess["id"])

    # ★转写未完成时：顾问点了开始分析 = 已表达分析意图。不能还显示"待分析"让人以为没点上。
    #   改为：锁定 + 标 queued("分析中…"前端就这么显示) + 记下"已请求分析,等转写"，
    #   并兜底触发漏启动(awaiting_intake)的转写；run_asr 转完最后一段会自动开始真分析(见 _maybe_autostart_analysis_after_asr)。
    asr_wait = db_fetchall(
        "SELECT id, asr_status FROM recordings WHERE session_id=? "
        "AND asr_status IN ('pending','running','awaiting_intake')",
        (sess["id"],),
    )
    if asr_wait:
        for rec in asr_wait:
            if rec["asr_status"] == "awaiting_intake":   # 漏触发的补一刀，别只干等
                try:
                    trigger_pipeline_for_recording(rec["id"])
                except Exception:
                    pass
        # 锁定 + 标 queued("已请求分析,等转写完自动跑")。analysis_signature 记下,自动跑时对比。
        # analysis_started_at=NULL：转写期间还没真开始分析,清掉它防 task_health_check 把"等转写的queued"
        #   按"卡running/queued超30分钟"误标 failed(它要求 analysis_started_at IS NOT NULL 才判超时)。
        db_write(
            """UPDATE sessions SET locked=1, analysis_status='queued',
               analysis_progress=?, analysis_error=NULL, analysis_signature=?,
               analysis_started_at=NULL
               WHERE id=?""",
            (f"⏳ 转写中（剩 {len(asr_wait)} 段），完成后自动开始分析", sig, sess["id"]),
        )
        return jsonify({
            "ok": True, "session_id": sess["id"], "asr_pending": True, "pending_count": len(asr_wait),
            "msg": f"已开始 ✅ 录音还在转写中（剩 {len(asr_wait)} 段），转写完成后会自动开始分析，无需再点",
        })

    # ② 已锁定 + signature 没变 + 已完成 → 不允许重跑
    if (sess_detail["locked"]
            and sess_detail["analysis_signature"] == sig
            and sess_detail["analysis_status"] == "done"):
        return jsonify({"error": "分析已完成，录音未变化，无需重新分析"}), 409

    # 锁定 session 并入队（重置 progress / error，确保 worker 后续 running 状态能落库）
    db_write(
        """UPDATE sessions SET locked=1, analysis_status='queued',
           analysis_progress='排队中…', analysis_error=NULL
           WHERE id=?""",
        (sess["id"],),
    )

    # ③ signature 没变 + 已有分析结果 → 只补跑失败/缺失的 task
    if (sess_detail["analysis_signature"] == sig
            and sess_detail["analysis_status"] in ("done", "failed", "cancelled")):
        missing = get_missing_tasks(sess["id"])
        if not missing:
            return jsonify({"ok": True, "session_id": sess["id"],
                            "msg": "所有任务已完成，无需重跑"})
        targets = expand_to_call_chunk(missing)
        for tid in targets:
            set_task_status(sess["id"], tid, "running")
        try:
            submit_analysis(sess["id"], sig, only_tasks=targets)
        except Exception as e:
            return jsonify({"error": f"触发分析失败：{e}"}), 500
    else:
        # ④ signature 变了或首次分析 → 全量跑
        try:
            submit_analysis(sess["id"], sig)
        except Exception as e:
            return jsonify({"error": f"触发分析失败：{e}"}), 500

    return jsonify({"ok": True, "session_id": sess["id"]})


def _do_cancel_analysis(session_id, company_id=None):
    """取消分析的公共逻辑。company_id 不为 None 时做公司隔离校验（super 传 None 跳过）。"""
    row = db_fetchone(
        "SELECT id, company_id, analysis_status FROM sessions WHERE id=?",
        (session_id,),
    )
    if not row:
        return jsonify({"error": "session 不存在"}), 404
    if company_id is not None and (row["company_id"] or 1) != company_id:
        return jsonify({"error": "无权操作"}), 403
    if row["analysis_status"] not in ("running", "queued"):
        return jsonify({"error": "当前不在分析中，无法取消"}), 409
    db_write(
        """UPDATE sessions SET analysis_status='cancelled',
           analysis_error='已手动中断',
           analysis_progress=NULL,
           analysis_finished_at=datetime('now','localtime')
           WHERE id=? AND analysis_status IN ('running','queued')""",
        (session_id,),
    )
    return jsonify({"ok": True})


@app.route("/api/consultant/session/cancel_analysis", methods=["POST"])
@login_required
def api_consultant_session_cancel_analysis():
    """顾问取消自己公司内正在分析中的 session。"""
    err = _consultant_required()
    if err:
        return err
    u = current_user()
    cid = u["company_id"] or 1
    data = request.get_json(silent=True) or {}
    session_id = data.get("session_id")
    if not session_id:
        return jsonify({"error": "缺少 session_id"}), 400
    return _do_cancel_analysis(session_id, company_id=cid)


@app.route("/api/admin/sessions/<int:sid>/cancel_analysis", methods=["POST"])
@login_required
def api_admin_session_cancel_analysis(sid):
    """管理员/超管取消任意分析中的 session。"""
    role = session.get("role")
    if role not in ("admin", "super"):
        return jsonify({"error": "仅管理员可操作"}), 403
    cid = session.get("company_id") or 1 if role == "admin" else None
    return _do_cancel_analysis(sid, company_id=cid)


@app.route("/api/admin/sessions/<int:sid>/unlock", methods=["POST"])
@login_required
def api_admin_session_unlock(sid):
    if session.get("role") not in ("admin", "super"):
        return jsonify({"error": "仅管理员可解锁"}), 403
    row = db_fetchone("SELECT id, company_id FROM sessions WHERE id=?", (sid,))
    if not row:
        return jsonify({"error": "session 不存在"}), 404
    if session.get("role") == "admin":
        if (row["company_id"] or 1) != (session.get("company_id") or 1):
            return jsonify({"error": "无权操作他公司"}), 403
    db_write("UPDATE sessions SET locked=0 WHERE id=?", (sid,))
    return jsonify({"ok": True})


@app.route("/api/consultant/analyze", methods=["POST"])
@login_required
def api_consultant_analyze():
    err = _consultant_required()
    if err:
        return err
    u = current_user()
    data = request.get_json(silent=True) or {}
    customer = (data.get("customer") or "").strip()
    start_date = (data.get("start_date") or "").strip()
    end_date = (data.get("end_date") or start_date).strip()
    if not customer or not start_date:
        return jsonify({"error": "请填写顾客和起始日期"}), 400
    advisor = u["advisor_name"] or u["username"]
    cid = u["company_id"] or 1
    # 时间段内该顾问+顾客的所有 session（service_date 在 [start, end]）
    # service_date 既有 YYYY-MM-DD 也有 YYYYMMDD；都规范化匹配
    def _norm(d):
        d = d.replace("-", "")
        return d
    s = _norm(start_date); e = _norm(end_date)
    rows = db_fetchall(
        """SELECT id, service_date FROM sessions
           WHERE advisor=? AND customer=?
             AND (company_id IS NULL OR company_id=?)
             AND REPLACE(service_date,'-','') >= ?
             AND REPLACE(service_date,'-','') <= ?""",
        (advisor, customer, cid, s, e),
    )
    if not rows:
        return jsonify({"error": "时间段内没有该顾客的录音"}), 404
    triggered = []
    skipped = []
    pending_asr = []
    for r in rows:
        sid = r["id"]
        sess_detail = db_fetchone(
            "SELECT analysis_status, analysis_signature FROM sessions WHERE id=?",
            (sid,),
        )
        if not sess_detail:
            continue
        if sess_detail["analysis_status"] in ("running", "queued"):
            continue
        # ★转写未完成的 session 不分析(同 start_analysis)，加入 pending_asr 让前端提示稍候
        _aw = db_fetchone(
            "SELECT COUNT(*) AS c FROM recordings WHERE session_id=? "
            "AND asr_status IN ('pending','running','awaiting_intake')", (sid,))
        if _aw and _aw["c"] > 0:
            pending_asr.append(sid)
            continue
        sig = compute_session_signature(sid)
        if (sess_detail["analysis_status"] == "done"
                and sess_detail["analysis_signature"] == sig):
            skipped.append(sid)
            continue
        try:
            submit_analysis(sid, sig)
            triggered.append(sid)
        except Exception:
            pass
    return jsonify({"ok": True, "session_ids": triggered, "skipped": skipped, "pending_asr": pending_asr})


@app.route("/healthz")
def healthz():
    with _analysis_inflight_lock:
        inflight = _analysis_inflight
    return jsonify({
        "ok": True,
        "ts": datetime.now().isoformat(),
        "analysis_inflight": inflight,
        "analysis_max_concurrency": ANALYSIS_MAX_CONCURRENCY,
    })


# ============ 启动 ============
init_db()


def _selfcheck_analysis_statuses():
    """启动自检：扫库找未识别的 analysis_status 值并告警。"""
    try:
        conn = sqlite3.connect(DB_PATH)
        rows = conn.execute(
            "SELECT analysis_status, COUNT(*) FROM sessions GROUP BY analysis_status"
        ).fetchall()
        conn.close()
        bad = [(s, c) for s, c in rows if s not in _VALID_DB_STATUSES]
        if bad:
            app.logger.warning(
                "[startup] sessions.analysis_status contains unknown values: %s "
                "(legal=%s)", bad, sorted(x for x in _VALID_DB_STATUSES if x)
            )
        else:
            app.logger.info("[startup] analysis_status self-check OK: %s",
                            {s: c for s, c in rows})
    except Exception as e:
        app.logger.warning("[startup] analysis_status self-check failed: %s", e)


_selfcheck_analysis_statuses()


def startup_kick():
    """启动时扫描 OSS，新文件入库 + 触发流水线；
    对已有但未完成的录音/session 补跑。"""
    try:
        scan_oss_bucket()
    except Exception as e:
        print(f"[startup_kick] scan failed: {e}")

    # 补跑未完成的 ASR
    pending = db_fetchall(
        "SELECT id FROM recordings WHERE asr_status IN ('pending', 'running', 'failed')"
    )
    for r in pending:
        trigger_pipeline_for_recording(r["id"])

    # ⚠ startup_kick 不再自动触发任何 session 分析。
    # 之前规则会把 running/failed 状态当成"中途被打断"自动补跑，但实际效果是
    # 每次 restart 都会意外烧钱。所有 LLM 分析改为完全手动触发（点"⟳ 重跑"按钮）。
    # 把上次 restart 时还在 running 的 session 标记为 failed，避免误以为还在跑。
    db_write(
        """UPDATE sessions SET analysis_status='failed',
           analysis_progress=NULL,
           analysis_error=COALESCE(analysis_error, '服务重启时被打断，未自动恢复，请手动重跑')
           WHERE analysis_status IN ('running','queued')"""
    )

    # ── 新增：修正"pending+排队中"的异常卡死状态 ──
    stale_pending = db_fetchall("""
        SELECT id FROM sessions
        WHERE analysis_status = 'pending'
          AND analysis_progress = '排队中…'
          AND analysis_started_at IS NOT NULL
    """)
    for s in stale_pending:
        ts_row = db_fetchone("SELECT task_status FROM sessions WHERE id=?", (s["id"],))
        ts = {}
        if ts_row and ts_row["task_status"]:
            try:
                ts = json.loads(ts_row["task_status"])
            except (json.JSONDecodeError, TypeError):
                pass
        all_ids = list(TASK_REGISTRY.keys())
        done_cnt = sum(1 for tid in all_ids if ts.get(tid, {}).get("status") == "done")
        if done_cnt == len(all_ids):
            db_write(
                """UPDATE sessions SET analysis_status='done',
                   analysis_progress=? WHERE id=?""",
                (f"完成 {done_cnt}/{len(all_ids)} 任务（启动修复）", s["id"]),
            )
            print(f"[startup_kick] session {s['id']} task 全 done，修正为 done")
        else:
            db_write(
                """UPDATE sessions SET analysis_status='failed',
                   analysis_progress=NULL,
                   analysis_error='启动时发现状态异常（pending+排队中），请手动重跑'
                   WHERE id=?""",
                (s["id"],),
            )
            print(f"[startup_kick] session {s['id']} 状态异常，标记 failed")

    # ── 新增：自动恢复"零成本失败"的 session（从未发过 LLM 调用） ──
    zero_cost_failed = db_fetchall("""
        SELECT id FROM sessions
        WHERE analysis_status = 'failed'
          AND (task_status IS NULL OR task_status = '{}')
          AND analysis_error LIKE '%interpreter shutdown%'
    """)
    if zero_cost_failed:
        print(f"[startup_kick] 发现 {len(zero_cost_failed)} 个零成本失败 session，自动恢复")
    for s in zero_cost_failed:
        maybe_trigger_session_analysis(s["id"])

    # ── 新增：触发 pending 且 ASR 全完成的 session ──
    pending_ready = db_fetchall("""
        SELECT s.id FROM sessions s
        WHERE (s.analysis_status = 'pending' OR s.analysis_status IS NULL)
          AND analysis_progress IS NULL
          AND NOT EXISTS (
            SELECT 1 FROM recordings r
            WHERE r.session_id = s.id AND r.asr_status != 'done'
          )
          AND EXISTS (
            SELECT 1 FROM recordings r WHERE r.session_id = s.id
          )
    """)
    if pending_ready:
        print(f"[startup_kick] 发现 {len(pending_ready)} 个 pending+ASR就绪 session，自动触发")
    for s in pending_ready:
        maybe_trigger_session_analysis(s["id"])

    # ── 新增：0 录音却卡在 pending/queued（排队中…）的空会话 → 标记 failed「无录音」 ──
    # 根因：录音被换绑/移走后老会话变空，却仍是 pending；worker 因「无 recordings」跳过它(见上 EXISTS 守卫)，
    # 于是永远显示"排队中…"(如林春华/演练王群 session 100198)。这里一次性收口，给出可行动的明确原因。
    empty_stuck = db_fetchall("""
        SELECT id FROM sessions
        WHERE analysis_status IN ('pending','queued')
          AND NOT EXISTS (SELECT 1 FROM recordings r WHERE r.session_id = sessions.id)
    """)
    if empty_stuck:
        db_write("""
            UPDATE sessions SET analysis_status='failed', analysis_progress=NULL,
                   analysis_error='该接诊暂无录音，无法分析（请补录音后重试，或删除该接诊）'
            WHERE analysis_status IN ('pending','queued')
              AND NOT EXISTS (SELECT 1 FROM recordings r WHERE r.session_id = sessions.id)
        """)
        print(f"[startup_kick] 收口 {len(empty_stuck)} 个无录音却卡 pending/queued 的空会话 → failed(无录音)")


# gunicorn 启动时也触发
threading.Thread(target=startup_kick, daemon=True).start()


def backfill_task_status():
    """启动时回填：对 task_status 为空但 analysis_result 有数据的旧 session，
    根据 analysis_result 里已有的字段把对应任务标为 done。"""
    try:
        rows = db_fetchall(
            """SELECT id, analysis_result, task_status FROM sessions
               WHERE analysis_status='done' AND analysis_result IS NOT NULL"""
        )
        count = 0
        for r in rows:
            # 已有 task_status 的跳过
            if r["task_status"]:
                try:
                    ts = json.loads(r["task_status"])
                    if ts:
                        continue
                except (json.JSONDecodeError, TypeError):
                    pass
            try:
                result = json.loads(r["analysis_result"])
            except (json.JSONDecodeError, TypeError):
                continue
            if not result:
                continue

            ts = {}
            now_str = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
            for tid, meta in TASK_REGISTRY.items():
                # T3 特殊：external_signals 有数据即算 done（customer_tags 是新字段，旧数据可能没有）
                if tid == "T3":
                    keys_ok = result.get("external_signals") not in (None, {}, [])
                else:
                    keys_ok = all(
                        result.get(k) not in (None, {}, [], "")
                        for k in meta["result_keys"]
                    )
                if keys_ok:
                    ts[tid] = {"status": "done", "updated_at": now_str, "error": None}

            if ts:
                db_write(
                    "UPDATE sessions SET task_status=? WHERE id=?",
                    (json.dumps(ts, ensure_ascii=False), r["id"]),
                )
                count += 1

        print(f"[backfill_task_status] 回填完成，共处理 {count} 个 session")
    except Exception as e:
        print(f"[backfill_task_status] 出错: {e}")


threading.Thread(target=backfill_task_status, daemon=True).start()


def task_health_check_loop():
    """每 15 分钟扫描一次：
    1. 把卡死在 running 超过 30 分钟的 task 标记 failed
    2. 把 session 级别卡在 running/queued 超 30 分钟的标 failed
    3. 修正 task 全 done 但 session 状态不是 done 的不一致
    """
    import time as _time
    while True:
        try:
            # ── 原有逻辑：task 级别 running 超时 ──
            rows = db_fetchall(
                "SELECT id, task_status FROM sessions WHERE analysis_status='done'"
            )
            for r in rows:
                if not r["task_status"]:
                    continue
                try:
                    ts = json.loads(r["task_status"])
                except (json.JSONDecodeError, TypeError):
                    continue
                changed = False
                now = datetime.now()
                for tid, s in ts.items():
                    if s.get("status") == "running":
                        ts_str = s.get("updated_at", "")
                        try:
                            t = datetime.strptime(ts_str, "%Y-%m-%d %H:%M:%S")
                            if (now - t).total_seconds() > 1800:
                                s["status"] = "failed"
                                s["error"] = "卡在 running 状态超过 30 分钟，自动标记失败"
                                changed = True
                        except (ValueError, TypeError):
                            pass
                if changed:
                    db_write(
                        "UPDATE sessions SET task_status=? WHERE id=?",
                        (json.dumps(ts, ensure_ascii=False), r["id"]),
                    )

            # ── 新增：session 级别卡死修复 ──
            stale = db_fetchall("""
                SELECT id FROM sessions
                WHERE analysis_status IN ('running', 'queued')
                  AND analysis_started_at IS NOT NULL
                  AND (julianday('now','localtime') - julianday(analysis_started_at)) * 1440 > 30
            """)
            for s in stale:
                db_write(
                    """UPDATE sessions SET analysis_status='failed',
                       analysis_error='卡在运行状态超过 30 分钟，自动标记失败',
                       analysis_progress=NULL,
                       analysis_finished_at=datetime('now','localtime')
                       WHERE id=?""",
                    (s["id"],),
                )
                print(f"[task_health_check] session {s['id']} 卡死超时，标记 failed")

            # ── 新增：task 全 done 但 session 状态不一致 ──
            inconsistent = db_fetchall("""
                SELECT id, task_status FROM sessions
                WHERE analysis_status NOT IN ('done', 'running', 'queued')
                  AND task_status IS NOT NULL AND task_status != '{}'
            """)
            for s in inconsistent:
                try:
                    ts = json.loads(s["task_status"])
                    all_ids = list(TASK_REGISTRY.keys())
                    done_cnt = sum(1 for tid in all_ids
                                   if ts.get(tid, {}).get("status") == "done")
                    if done_cnt == len(all_ids):
                        db_write(
                            """UPDATE sessions SET analysis_status='done',
                               analysis_progress=?,
                               analysis_finished_at=COALESCE(analysis_finished_at, datetime('now','localtime'))
                               WHERE id=?""",
                            (f"完成 {done_cnt}/{len(all_ids)} 任务（状态修复）", s["id"]),
                        )
                        print(f"[task_health_check] session {s['id']} task 全 done，修正状态为 done")
                except (json.JSONDecodeError, TypeError):
                    pass

            # ── 新增：recording 级 ASR 卡死兜底（防 run_asr 线程挂死，如下载转写结果 hang）。
            #    原来 task_health_check 只管 sessions，不碰 recordings.asr_status → 卡 running 唯一自愈是重启进程。 ──
            asr_stale = db_fetchall("""
                SELECT id FROM recordings
                WHERE asr_status='running' AND asr_started_at IS NOT NULL
                  AND (julianday('now','localtime') - julianday(asr_started_at)) * 1440 > 30
            """)
            for r in asr_stale:
                db_write(
                    """UPDATE recordings SET asr_status='failed',
                       asr_error='转写卡在 running 超 30 分钟，自动标记失败（音频在，可重试）',
                       asr_finished_at=datetime('now','localtime')
                       WHERE id=? AND asr_status='running'""",
                    (r["id"],),
                )
                print(f"[task_health_check] recording {r['id']} ASR 卡 running 超时，标 failed")

            # ── 新增：processing 占位僵尸兜底（App 卸载/换机/永久离线 → 占位永远"上传中"，服务器原本无 TTL）。
            #    阈值 3h > App 端 2h 自愈窗口，只兜底 App 再也起不来的情况，不与还活着的 App 抢。
            #    音频仍在录音笔机身上，顾问可日后「取回小伙伴」重新导入。 ──
            ph_stale = db_fetchall("""
                SELECT id FROM recordings
                WHERE upload_status='processing' AND source='consultant-pen-placeholder'
                  AND (julianday('now','localtime') - julianday(created_at)) * 24 > 3
            """)
            for r in ph_stale:
                db_write(
                    "DELETE FROM recordings WHERE id=? AND upload_status='processing' "
                    "AND source='consultant-pen-placeholder'",
                    (r["id"],),
                )
                print(f"[task_health_check] processing 占位 {r['id']} 超 3h 未回填，删除（音频在笔上可重新取回）")

        except Exception as e:
            print(f"[task_health_check] {e}")
        _time.sleep(900)


threading.Thread(target=task_health_check_loop, daemon=True).start()


# ============================================================================
# 模块4：提醒系统（状态机 + 卡点看板 + 分级提醒调度器 + 站内提醒）
# ----------------------------------------------------------------------------
# 渠道取舍：
#   - 站内系统消息(inapp) / 看板标红(board)：真实落库实现。
#   - 电话(phone)/短信(sms)/企业微信(wecom)：仅做可插拔渠道 stub，不接任何
#     真实运营商（无账号/凭证）。send_phone_reminder() 只把"本应外呼"写进
#     reminder_log(result='stub_logged') 并打日志。真实接入点见该函数注释。
# ============================================================================

# 状态枚举（统一口径）
PIPELINE_STATES = ("orphan", "bound_unanalyzed", "analyzing", "done_unviewed", "viewed", "error")
PIPELINE_STATE_LABELS = {
    "orphan": "未绑定",
    "bound_unanalyzed": "已绑定未分析",
    "analyzing": "分析中",
    "done_unviewed": "分析完成未查看",
    "viewed": "已查看",
    "error": "异常",
}


def get_reminder_config(company_id, store_id=None):
    """取某公司/门店的提醒配置：优先门店级，回退公司默认，再回退硬编码默认。
    返回 dict（始终非空，带默认值）。"""
    defaults = {
        "enable_phone": 0, "remind_after_hours": 2, "max_per_day": 3,
        "avoid_offwork": 1, "work_start": "09:00", "work_end": "21:00",
        "retry_on_fail": 0, "log_results": 1,
    }
    row = None
    if store_id is not None:
        row = db_fetchone(
            "SELECT * FROM reminder_config WHERE company_id=? AND store_id=?",
            (company_id, store_id),
        )
    if not row:
        row = db_fetchone(
            "SELECT * FROM reminder_config WHERE company_id=? AND store_id IS NULL",
            (company_id,),
        )
    if not row:
        return dict(defaults)
    d = dict(row)
    for k, v in defaults.items():
        if d.get(k) is None:
            d[k] = v
    return d


def _user_for_advisor(company_id, advisor_name):
    """按 advisor_name 找该顾问的 user 行（用于定位提醒目标 + 门店）。找不到返回 None。"""
    if not advisor_name:
        return None
    return db_fetchone(
        "SELECT id, advisor_name, store_id FROM users "
        "WHERE company_id=? AND advisor_name=? AND role IN ('consultant','store_manager') "
        "ORDER BY id LIMIT 1",
        (company_id, advisor_name),
    )


def _within_work_hours(now, cfg):
    """是否在工作时间窗口内（用于电话级避让）。窗口跨午夜也兼容。"""
    try:
        ws = datetime.strptime(cfg["work_start"], "%H:%M").time()
        we = datetime.strptime(cfg["work_end"], "%H:%M").time()
    except Exception:
        return True
    t = now.time()
    if ws <= we:
        return ws <= t <= we
    return t >= ws or t <= we  # 跨午夜窗口


def send_phone_reminder(company_id, store_id, target_user_id, target_name,
                        kind, ref_type, ref_id, message, cfg):
    """电话提醒「可插拔渠道」stub。

    !!! 真实接入点 !!!
    目前不接任何运营商：没有阿里云/腾讯云语音的 AppKey/Token/被叫号绑定关系。
    要真实外呼时，在此处替换为：
      - 阿里云语音通知 SingleCallByTts（dyvmsapi）/ 腾讯云语音 VoiceMessage；
      - 或绑定虚拟号(AXB)做真人回呼。
    需要：账号 AK/SK、已报备的语音模板 ID、被叫人手机号（users.phone）。
    现在仅把"本应外呼"这件事落库 + 打日志，result='stub_logged'。
    """
    phone = None
    if target_user_id:
        urow = db_fetchone("SELECT phone FROM users WHERE id=?", (target_user_id,))
        phone = urow["phone"] if urow else None
    result = f"stub_logged (would call {phone or 'N/A'})"
    print(f"[reminder][phone-stub] company={company_id} target={target_name}({phone}) "
          f"kind={kind} ref={ref_type}:{ref_id} msg={message}", flush=True)
    if int(cfg.get("log_results") or 1):
        db_write(
            """INSERT INTO reminder_log
               (company_id, store_id, target_user_id, target_name, kind, level, channel,
                ref_type, ref_id, message, result)
               VALUES (?, ?, ?, ?, ?, 2, 'phone', ?, ?, ?, ?)""",
            (company_id, store_id, target_user_id, target_name, kind,
             ref_type, ref_id, message, result),
        )
    return result


def _already_reminded(kind, ref_type, ref_id, level, channel, day, target_user_id=None):
    """幂等去重。
    - channel='inapp' / 'escalation'：一对象一行，未处理(processed=0)即视为已存在，不带 date。
      （inapp 还按 target_user_id 区分，保证每个收件人各 1 行；escalation 是全店共享 1 行，
       target_user_id 仅作标识，不参与判重。）
    - channel='phone'（及其它外呼类）：保留按天判重，用于每天重拨。
    """
    if channel in ("inapp", "escalation"):
        if channel == "inapp" and target_user_id is not None:
            row = db_fetchone(
                "SELECT 1 FROM reminder_log WHERE kind=? AND ref_type=? AND ref_id=? "
                "AND level=? AND channel='inapp' AND target_user_id=? AND processed=0 LIMIT 1",
                (kind, ref_type, ref_id, level, target_user_id),
            )
        else:
            # escalation：同 session 升级阶段只保留 1 行（不带 level/target/date 约束，
            # 按 kind+ref_type+ref_id+channel 判重，processed=0 才算占位）
            row = db_fetchone(
                "SELECT 1 FROM reminder_log WHERE kind=? AND ref_type=? AND ref_id=? "
                "AND channel=? AND processed=0 LIMIT 1",
                (kind, ref_type, ref_id, channel),
            )
        return row is not None
    # phone / sms / wecom / board：按天判重
    row = db_fetchone(
        "SELECT 1 FROM reminder_log WHERE kind=? AND ref_type=? AND ref_id=? "
        "AND level=? AND channel=? AND date(created_at)=? LIMIT 1",
        (kind, ref_type, ref_id, level, channel, day),
    )
    return row is not None


def _reminders_today_count(target_user_id, ref_type, ref_id, day):
    """某对象当天已产生的"主动提醒"条数（用于 max_per_day 限流；不含 board/processed 修正）。"""
    row = db_fetchone(
        "SELECT COUNT(*) AS c FROM reminder_log WHERE target_user_id=? AND ref_type=? "
        "AND ref_id=? AND channel IN ('inapp','phone','sms','wecom') AND date(created_at)=?",
        (target_user_id, ref_type, ref_id, day),
    )
    return row["c"] if row else 0


def _emit_reminder(company_id, store_id, target_user_id, target_name, kind, level,
                   channel, ref_type, ref_id, message, result="generated"):
    """写一条提醒（已假定过了去重 + 限流判断）。返回 lastrowid。"""
    return db_write(
        """INSERT INTO reminder_log
           (company_id, store_id, target_user_id, target_name, kind, level, channel,
            ref_type, ref_id, message, result)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
        (company_id, store_id, target_user_id, target_name, kind, level, channel,
         ref_type, ref_id, message, result),
    )


def _emit_escalation(company_id, store_id, advisor_user_id, advisor_name,
                     ref_id, message, manager_own=False):
    """写一条「升级项」(channel='escalation')。
    - 一个 session 升级阶段只保留 1 行（调用前用 _already_reminded(...,'escalation') 判重）。
    - target_user_id 保留为「报告所属顾问」（标识是谁的报告），不代表收件人。
    - 升级项同时被本店店长收件箱 + 管理员看板读取，无需给每个店长/管理员各写一行。
    - manager_own=True：报告所属人本身是店长 → level=3 + result='manager_own'，
      店长收件箱不显示自己的，仅管理员看板显示。
    """
    level = 3 if manager_own else 2
    result = "manager_own" if manager_own else "escalated"
    return db_write(
        """INSERT INTO reminder_log
           (company_id, store_id, target_user_id, target_name, kind, level, channel,
            ref_type, ref_id, message, result)
           VALUES (?, ?, ?, ?, 'unviewed', ?, 'escalation', 'session', ?, ?, ?)""",
        (company_id, store_id, advisor_user_id, advisor_name, level, ref_id, message, result),
    )


def _fmt_rec_dt(ts_str):
    """录音时间 '2026-06-14 21:29:08' → '6月14日 21:29'（供提醒文案具体到哪天几点）。解析失败回退原串。"""
    if not ts_str:
        return ""
    s = str(ts_str)[:26]
    for fmt in ("%Y-%m-%d %H:%M:%S.%f", "%Y-%m-%d %H:%M:%S", "%Y-%m-%dT%H:%M:%S", "%Y%m%d%H%M%S"):
        try:
            dt = datetime.strptime(s[:len(fmt) + 6] if "%f" in fmt else s[:19], fmt)
            return f"{dt.month}月{dt.day}日 {dt.hour:02d}:{dt.minute:02d}"
        except ValueError:
            continue
    return str(ts_str)[:16]


def _hours_since(ts_str, now):
    """ts_str（localtime 字符串）距 now 的小时数；无法解析返回 None。"""
    if not ts_str:
        return None
    for fmt in ("%Y-%m-%d %H:%M:%S", "%Y-%m-%d %H:%M:%S.%f", "%Y-%m-%dT%H:%M:%S"):
        try:
            dt = datetime.strptime(str(ts_str)[:26], fmt)
            return (now - dt).total_seconds() / 3600.0
        except ValueError:
            continue
    return None


def run_reminder_scan(now=None):
    """扫描所有公司，按 reminder_config 产生分级提醒并写 reminder_log（幂等）。
    可直接调用（自测/定时器都用它）。返回统计 dict。

    规则：
      未绑定录音(unbound)：阈值 H=remind_after_hours
        L1 >H → inapp（提醒该顾问）
        L2 当日结束仍未绑定 → phone(stub，需 enable_phone)
        L3 >24h → board（通知店长/管理员，看板标红）
      报告未查看(unviewed)：
        L1 完成 >H → inapp（提醒顾问本人，一对象一行）
        升级 报告生成"第二天"自然日仍未看 → 1 条 channel='escalation'（store_id=报告门店）
             普通顾问报告 level=2：本店店长收件箱 + 管理员看板都可见
             店长本人报告 level=3,result='manager_own'：仅管理员看板，店长收件箱不显示自己
    去重：inapp/escalation 一对象一行（未处理即不重复，不带 date）；phone 仍按天（重拨）。
    闭环：对象已不满足条件（报告已被查看）→ 把该对象旧的未处理 inapp/escalation 提醒置 processed=1。
    """
    now = now or datetime.now()
    today = now.strftime("%Y-%m-%d")
    stats = {"unbound_l1": 0, "unbound_l2": 0, "unbound_l3": 0,
             "unviewed_l1": 0, "unviewed_l2": 0, "unviewed_l3": 0,
             "closed": 0, "scanned_companies": 0}

    companies = db_fetchall("SELECT id FROM companies")
    for crow in companies:
        cid = crow["id"]
        stats["scanned_companies"] += 1

        # ---------- 1) 未绑定录音 ----------
        # 未绑定 = session_id IS NULL 且非 ASR 失败（失败属"异常"，不催绑）
        orphans = db_fetchall(
            """SELECT id, advisor, uploader_user_id, store_id, created_at, recorded_at, asr_status
               FROM recordings
               WHERE company_id=? AND session_id IS NULL
                 AND IFNULL(asr_status,'') NOT IN ('failed')""",
            (cid,),
        )
        active_orphan_ids = set()
        for r in orphans:
            active_orphan_ids.add(r["id"])
            base_ts = r["created_at"] or r["recorded_at"]
            hrs = _hours_since(base_ts, now)
            if hrs is None:
                continue
            # 定位目标顾问 + 门店
            tu_id, tu_name, store_id = None, (r["advisor"] or "未知顾问"), r["store_id"]
            if r["uploader_user_id"]:
                u = db_fetchone("SELECT id, advisor_name, store_id FROM users WHERE id=?",
                                (r["uploader_user_id"],))
                if u:
                    tu_id, tu_name = u["id"], (u["advisor_name"] or tu_name)
                    store_id = store_id or u["store_id"]
            if tu_id is None:
                u = _user_for_advisor(cid, r["advisor"])
                if u:
                    tu_id, tu_name = u["id"], (u["advisor_name"] or tu_name)
                    store_id = store_id or u["store_id"]
            cfg = get_reminder_config(cid, store_id)
            H = int(cfg.get("remind_after_hours") or 2)

            _rec_when = _fmt_rec_dt(r["recorded_at"] or r["created_at"])
            # L1：>H 小时未绑定 → 站内提醒顾问（一对象一行，未处理即不重复）
            if hrs >= H and tu_id:
                if not _already_reminded("unbound", "recording", r["id"], 1, "inapp", today,
                                         target_user_id=tu_id):
                    _emit_reminder(cid, store_id, tu_id, tu_name, "unbound", 1, "inapp",
                                   "recording", r["id"],
                                   f"{_rec_when}的一条陪伴录音已超过 {H} 小时未绑定客人，请尽快去绑定归档。")
                    stats["unbound_l1"] += 1

            # L2：当日结束仍未绑定（录音不是今天产生的 → 已跨过当日）→ 电话提醒(stub)
            rec_day = (str(base_ts)[:10]) if base_ts else None
            if rec_day and rec_day < today and tu_id and int(cfg.get("enable_phone") or 0):
                offwork_ok = (not int(cfg.get("avoid_offwork") or 0)) or _within_work_hours(now, cfg)
                if (offwork_ok
                        and not _already_reminded("unbound", "recording", r["id"], 2, "phone", today)
                        and _reminders_today_count(tu_id, "recording", r["id"], today) < int(cfg.get("max_per_day") or 3)):
                    send_phone_reminder(cid, store_id, tu_id, tu_name, "unbound",
                                        "recording", r["id"],
                                        "您有当日录音始终未绑定客人，请尽快处理。", cfg)
                    stats["unbound_l2"] += 1

            # L3：>24h → 看板标红（通知店长/管理员）
            if hrs >= 24:
                if not _already_reminded("unbound", "recording", r["id"], 3, "board", today):
                    _emit_reminder(cid, store_id, tu_id, tu_name, "unbound", 3, "board",
                                   "recording", r["id"],
                                   f"{_rec_when}的录音超过 24 小时未绑定（顾问：{tu_name}），看板标红。")
                    stats["unbound_l3"] += 1

        # 闭环：已绑定/已消失的录音 → 关掉旧的未处理 inapp/phone 未绑定提醒
        closed = _close_resolved_reminders(cid, "unbound", "recording", active_orphan_ids)
        stats["closed"] += closed

        # ---------- 2) 报告未查看 ----------
        # done 且无 report_view_events(enter) = 完成未查看
        unviewed = db_fetchall(
            """SELECT s.id, s.advisor, s.customer, s.store_id, s.analysis_finished_at
               FROM sessions s
               WHERE s.company_id=? AND s.analysis_status='done'
                 AND NOT EXISTS (
                     SELECT 1 FROM report_view_events rve
                     WHERE rve.session_id=s.id AND rve.event='enter'
                 )""",
            (cid,),
        )
        active_unviewed_ids = set()
        for s in unviewed:
            active_unviewed_ids.add(s["id"])
            hrs = _hours_since(s["analysis_finished_at"], now)
            if hrs is None:
                continue
            u = _user_for_advisor(cid, s["advisor"])
            tu_id = u["id"] if u else None
            tu_name = (u["advisor_name"] if u else None) or (s["advisor"] or "未知顾问")
            cust = (s["customer"] or "").strip() or "某位顾客"
            store_id = s["store_id"] or (u["store_id"] if u else None)
            cfg = get_reminder_config(cid, store_id)
            H = int(cfg.get("remind_after_hours") or 2)
            # 报告生成日（自然日）；"第二天判定"= 报告生成日 < 今天日期
            fin_day = (str(s["analysis_finished_at"])[:10]) if s["analysis_finished_at"] else None
            is_next_day = bool(fin_day and fin_day < today)
            # 报告所属人是否为店长本人（决定升级项是否对店长隐藏）
            owner_is_manager = False
            if tu_id:
                _orow = db_fetchone("SELECT role FROM users WHERE id=?", (tu_id,))
                owner_is_manager = bool(_orow and _orow["role"] == "store_manager")

            # L1：完成 >H 未查看 → 站内提醒顾问本人（一对象一行，未处理即不重复）
            if hrs >= H and tu_id:
                if not _already_reminded("unviewed", "session", s["id"], 1, "inapp", today,
                                         target_user_id=tu_id):
                    _emit_reminder(cid, store_id, tu_id, tu_name, "unviewed", 1, "inapp",
                                   "session", s["id"],
                                   f"{cust} 的分析报告已生成超过 {H} 小时尚未查看，请及时复盘。")
                    stats["unviewed_l1"] += 1

            # 升级：报告生成的"第二天"自然日仍未查看 → 写 1 条 channel='escalation'
            #   - 普通顾问报告：本店店长收件箱 + 管理员看板都能看到（level=2）
            #   - 店长本人报告：仅管理员看板（level=3, result='manager_own'），店长收件箱不显示自己
            if is_next_day:
                if not _already_reminded("unviewed", "session", s["id"], None, "escalation", today):
                    if owner_is_manager:
                        _emit_escalation(cid, store_id, tu_id, tu_name, s["id"],
                                         f"店长（{tu_name}）{cust} 的报告隔日仍未查看，请管理员关注。",
                                         manager_own=True)
                    else:
                        _emit_escalation(cid, store_id, tu_id, tu_name, s["id"],
                                         f"{cust} 的报告隔日仍未查看（顾问：{tu_name}），请店长跟进。",
                                         manager_own=False)
                    stats["unviewed_l2"] += 1

        # 闭环：已查看的报告 → 关掉旧的未处理 inapp / escalation 未查看提醒
        stats["closed"] += _close_resolved_reminders(cid, "unviewed", "session", active_unviewed_ids)

    return stats


def _close_resolved_reminders(company_id, kind, ref_type, active_ids):
    """把"对象已不满足条件"的旧未处理站内/电话/升级提醒置 processed=1。
    active_ids = 当前仍满足该 kind 条件的 ref_id 集合；不在其中的即已解决。
    （unviewed 的 escalation 升级项也在此随报告被查看而关闭。）"""
    rows = db_fetchall(
        "SELECT id, ref_id FROM reminder_log WHERE company_id=? AND kind=? AND ref_type=? "
        "AND channel IN ('inapp','phone','sms','wecom','escalation') AND processed=0",
        (company_id, kind, ref_type),
    )
    n = 0
    for r in rows:
        if r["ref_id"] not in active_ids:
            db_write(
                "UPDATE reminder_log SET processed=1, processed_at=datetime('now','localtime') WHERE id=?",
                (r["id"],),
            )
            n += 1
    return n


def reminder_scheduler_loop():
    """后台 daemon 线程：周期（每 10 分钟）跑一次 run_reminder_scan。
    不用 APScheduler，just while True + sleep。"""
    import time as _t
    _t.sleep(60)  # 启动后稍等，避开 init 高峰
    while True:
        try:
            st = run_reminder_scan()
            print(f"[reminder_scheduler] scan done: {st}", flush=True)
        except Exception as e:
            print(f"[reminder_scheduler] error: {e}", flush=True)
        _t.sleep(600)


threading.Thread(target=reminder_scheduler_loop, daemon=True, name="reminder-scheduler").start()


# ---------- 接诊卡点：状态分桶 + 卡住项 ----------
@app.route("/api/admin/pipeline_status")
@manager_required
def api_admin_pipeline_status():
    cid = session.get("company_id")
    is_super = session.get("role") == "super"
    sf, sf_params = store_scope_sql("store_id")

    def _co(prefix=""):
        # 公司过滤（super 看全部）
        if is_super:
            return "", []
        col = f"{prefix}company_id" if prefix else "company_id"
        return f"{col}=?", [cid]

    buckets = {k: 0 for k in PIPELINE_STATES}

    # orphan：未绑定录音
    co, cop = _co()
    where = [w for w in [co, sf] if w]
    params = cop + sf_params
    row = db_fetchone(
        "SELECT COUNT(*) AS c FROM recordings WHERE session_id IS NULL "
        "AND IFNULL(asr_status,'') NOT IN ('failed')"
        + ("".join(" AND " + w for w in where)),
        params,
    )
    buckets["orphan"] = row["c"] if row else 0

    # asr failed 录音算异常
    row = db_fetchone(
        "SELECT COUNT(*) AS c FROM recordings WHERE session_id IS NULL AND asr_status='failed'"
        + ("".join(" AND " + w for w in where)),
        params,
    )
    err_rec = row["c"] if row else 0

    # session 维度：按 analysis_status + 是否查看分桶
    sco, scop = _co()
    swhere = [w for w in [sco, sf] if w]
    sparams = scop + sf_params
    sql = (
        "SELECT analysis_status AS st, "
        "  EXISTS(SELECT 1 FROM report_view_events r WHERE r.session_id=sessions.id AND r.event='enter') AS viewed, "
        "  COUNT(*) AS c "
        "FROM sessions WHERE service_date NOT LIKE '?-%' "
        + ("".join(" AND " + w for w in swhere))
        + " GROUP BY st, viewed"
    )
    for r in db_fetchall(sql, sparams):
        st = r["st"] or "pending"
        viewed = r["viewed"]
        c = r["c"]
        if st in ("queued", "running"):
            buckets["analyzing"] += c
        elif st == "done":
            buckets["done_unviewed" if not viewed else "viewed"] += c
        elif st in ("failed", "outdated"):
            buckets["error"] += c
        else:  # pending 等：已绑定未分析
            buckets["bound_unanalyzed"] += c
    buckets["error"] += err_rec

    # 卡住项明细：未绑定 >2h、完成未查看 >2h，超 24h 标红
    now = datetime.now()
    stuck = []
    orphan_rows = db_fetchall(
        "SELECT id, advisor, recorded_at, created_at, store_id FROM recordings "
        "WHERE session_id IS NULL AND IFNULL(asr_status,'') NOT IN ('failed')"
        + ("".join(" AND " + w for w in where))
        + " ORDER BY id DESC LIMIT 500",
        params,
    )
    for r in orphan_rows:
        hrs = _hours_since(r["created_at"] or r["recorded_at"], now)
        if hrs is not None and hrs >= 2:
            stuck.append({
                "kind": "unbound", "ref_type": "recording", "ref_id": r["id"],
                "advisor": r["advisor"] or "未知顾问",
                "hours": round(hrs, 1), "red": hrs >= 24,
                "when": r["created_at"] or r["recorded_at"],
            })
    unviewed_rows = db_fetchall(
        "SELECT id, advisor, analysis_finished_at FROM sessions "
        "WHERE analysis_status='done' AND service_date NOT LIKE '?-%' "
        "AND NOT EXISTS (SELECT 1 FROM report_view_events r WHERE r.session_id=sessions.id AND r.event='enter')"
        + ("".join(" AND " + w for w in swhere))
        + " ORDER BY id DESC LIMIT 500",
        sparams,
    )
    for r in unviewed_rows:
        hrs = _hours_since(r["analysis_finished_at"], now)
        if hrs is not None and hrs >= 2:
            stuck.append({
                "kind": "unviewed", "ref_type": "session", "ref_id": r["id"],
                "advisor": r["advisor"] or "未知顾问",
                "hours": round(hrs, 1), "red": hrs >= 24,
                "when": r["analysis_finished_at"],
            })
    stuck.sort(key=lambda x: x["hours"], reverse=True)

    return jsonify({
        "buckets": [{"key": k, "label": PIPELINE_STATE_LABELS[k], "count": buckets[k]}
                    for k in PIPELINE_STATES],
        "stuck": stuck[:200],
        "labels": PIPELINE_STATE_LABELS,
    })


# ---------- 提醒设置：读写 reminder_config ----------
@app.route("/api/admin/reminder_config", methods=["GET"])
@manager_required
def api_admin_reminder_config_get():
    cid = session.get("company_id")
    sid = request.args.get("store_id")
    sid = int(sid) if (sid and sid.strip().isdigit()) else None
    # store_manager 锁本店
    if session.get("role") == "store_manager":
        sid = session.get("store_id")
    cfg = get_reminder_config(cid, sid)
    cfg["store_id"] = sid
    return jsonify(cfg)


@app.route("/api/admin/reminder_config", methods=["POST"])
@admin_required
def api_admin_reminder_config_post():
    cid = session.get("company_id")
    data = request.get_json(silent=True) or {}
    raw_sid = data.get("store_id")
    sid = int(raw_sid) if (raw_sid not in (None, "", "null")) else None

    def _i(key, default):
        try:
            return int(data.get(key, default))
        except (TypeError, ValueError):
            return default

    enable_phone = 1 if data.get("enable_phone") else 0
    remind_after_hours = max(1, _i("remind_after_hours", 2))
    max_per_day = max(1, _i("max_per_day", 3))
    avoid_offwork = 1 if data.get("avoid_offwork") else 0
    work_start = (data.get("work_start") or "09:00")[:5]
    work_end = (data.get("work_end") or "21:00")[:5]
    retry_on_fail = 1 if data.get("retry_on_fail") else 0
    log_results = 1 if data.get("log_results", True) else 0

    existing = db_fetchone(
        "SELECT id FROM reminder_config WHERE company_id=? AND IFNULL(store_id,-1)=IFNULL(?,-1)",
        (cid, sid),
    )
    if existing:
        db_write(
            """UPDATE reminder_config SET enable_phone=?, remind_after_hours=?, max_per_day=?,
               avoid_offwork=?, work_start=?, work_end=?, retry_on_fail=?, log_results=?,
               updated_at=datetime('now','localtime') WHERE id=?""",
            (enable_phone, remind_after_hours, max_per_day, avoid_offwork, work_start,
             work_end, retry_on_fail, log_results, existing["id"]),
        )
    else:
        db_write(
            """INSERT INTO reminder_config
               (company_id, store_id, enable_phone, remind_after_hours, max_per_day,
                avoid_offwork, work_start, work_end, retry_on_fail, log_results)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            (cid, sid, enable_phone, remind_after_hours, max_per_day, avoid_offwork,
             work_start, work_end, retry_on_fail, log_results),
        )
    return jsonify({"ok": True})


# ---------- 提醒日志（管理端查看） ----------
@app.route("/api/admin/reminder_log")
@manager_required
def api_admin_reminder_log():
    cid = session.get("company_id")
    is_super = session.get("role") == "super"
    sf, sf_params = store_scope_sql("store_id")
    where, params = [], []
    if not is_super:
        where.append("company_id=?")
        params.append(cid)
    if sf:
        where.append(sf)
        params += sf_params
    kind = request.args.get("kind")
    if kind in ("unbound", "unviewed"):
        where.append("kind=?")
        params.append(kind)
    sql = "SELECT * FROM reminder_log"
    if where:
        sql += " WHERE " + " AND ".join(where)
    sql += " ORDER BY id DESC LIMIT 300"
    rows = [dict(r) for r in db_fetchall(sql, params)]
    return jsonify({"items": rows})


# ---------- 管理员升级看板：所有 channel='escalation' 升级项 + 店长已读/已处理状态 ----------
@app.route("/api/admin/escalations")
@manager_required
def api_admin_escalations():
    """升级项看板。
    - super：看全部公司；admin：本公司；store_manager：本店（store_scope_sql 收口）。
    - 每条附：顾问名 / 门店名 / session / 报告生成时间(analysis_finished_at) /
      店长已读(read_at) / 店长已处理(handled_at + 处理人姓名) / 是否已查看关闭(processed)。
    - ?pending=1 仅看「待跟进」(processed=0 且 handled_at IS NULL)。
    """
    cid = session.get("company_id")
    role = session.get("role")
    is_super = role == "super"
    sf, sf_params = store_scope_sql("rl.store_id")
    where = ["rl.channel='escalation'"]
    params = []
    if not is_super:
        where.append("rl.company_id=?")
        params.append(cid)
    # 店长本人报告的升级项(manager_own)仅管理员可见：店长在本店升级看板上也看不到自己的报告升级项。
    if role == "store_manager":
        where.append("COALESCE(rl.result,'') <> 'manager_own'")
    if sf:
        where.append(sf)
        params += sf_params
    pending = (request.args.get("pending") or "").strip()
    if pending in ("1", "true", "yes"):
        where.append("rl.processed=0 AND rl.handled_at IS NULL")
    sql = (
        "SELECT rl.id, rl.company_id, rl.store_id, rl.target_user_id AS advisor_user_id, "
        "       rl.target_name AS advisor_name, rl.kind, rl.level, rl.channel, "
        "       rl.ref_type, rl.ref_id AS session_id, rl.message, rl.result, "
        "       rl.processed, rl.processed_at, rl.read_at, rl.handled_at, rl.handled_by, "
        "       rl.created_at, "
        "       st.name AS store_name, "
        "       s.analysis_finished_at AS report_finished_at, "
        "       hu.advisor_name AS handled_by_name, hu.username AS handled_by_username "
        "FROM reminder_log rl "
        "LEFT JOIN stores st ON st.id=rl.store_id "
        "LEFT JOIN sessions s ON s.id=rl.ref_id "
        "LEFT JOIN users hu ON hu.id=rl.handled_by "
    )
    if where:
        sql += "WHERE " + " AND ".join(where) + " "
    sql += "ORDER BY rl.id DESC LIMIT 300"
    rows = []
    for r in db_fetchall(sql, params):
        d = dict(r)
        d["manager_own"] = (d.get("result") == "manager_own")
        d["is_read"] = bool(d.get("read_at"))
        d["is_handled"] = bool(d.get("handled_at"))
        d["is_closed"] = bool(d.get("processed"))
        d["handled_by_name"] = d.get("handled_by_name") or d.get("handled_by_username")
        rows.append(d)
    return jsonify({"items": rows, "count": len(rows)})


# ---------- 顾问端站内提醒 ----------
@app.route("/api/consultant/reminders")
@login_required
def api_consultant_reminders():
    err = _consultant_required()
    if err:
        return err
    u = current_user()
    # (1) 个人提醒：给本人的未处理 inapp/phone（顾问 / 店长本人都各自有）
    personal = db_fetchall(
        """SELECT id, kind, level, channel, ref_type, ref_id, message, created_at
           FROM reminder_log
           WHERE target_user_id=? AND processed=0 AND channel IN ('inapp','phone')
           ORDER BY id DESC LIMIT 100""",
        (u["id"],),
    )
    items = []
    for r in personal:
        d = dict(r)
        d["scope"] = "personal"
        items.append(d)

    escalations = []
    # (2) 店长：本店所有未处理升级项（escalation）；不含 manager_own（店长本人的报告）。
    try:
        role = u["role"]
        store_id = u["store_id"]
    except Exception:
        role, store_id = None, None
    if role == "store_manager" and store_id is not None:
        erows = db_fetchall(
            """SELECT id, kind, level, channel, ref_type, ref_id, target_user_id,
                      target_name, message, result, read_at, handled_at, created_at
               FROM reminder_log
               WHERE channel='escalation' AND processed=0 AND store_id=?
                 AND IFNULL(result,'') <> 'manager_own'
               ORDER BY id DESC LIMIT 200""",
            (store_id,),
        )
        unread_ids = []
        for r in erows:
            d = dict(r)
            d["scope"] = "escalation"
            d["advisor_user_id"] = d.get("target_user_id")
            d["advisor_name"] = d.get("target_name")
            d["session_id"] = d.get("ref_id")
            d["is_read"] = bool(d.get("read_at"))
            d["is_handled"] = bool(d.get("handled_at"))
            if not d.get("read_at"):
                unread_ids.append(d["id"])
            escalations.append(d)
        # 店长拉取到某条升级项 → 若未读则置 read_at=now（表示已读）
        for rid in unread_ids:
            try:
                db_write(
                    "UPDATE reminder_log SET read_at=datetime('now','localtime') "
                    "WHERE id=? AND read_at IS NULL",
                    (rid,),
                )
            except Exception as _e:
                app.logger.warning("escalation 标记已读失败 id=%s: %s", rid, _e)

    items.extend(escalations)
    return jsonify({
        "count": len(items),
        "personal_count": len(personal),
        "escalation_count": len(escalations),
        "items": items,
    })


# ---------- 店长「已跟进」升级项 ----------
@app.route("/api/manager/reminder/<int:rid>/handle", methods=["POST"])
@manager_required
def api_manager_reminder_handle(rid):
    """店长（或管理员）在看板点「已跟进」→ 置 handled_at / handled_by。
    store_manager 只能处理本店升级项；admin/super 可处理本公司任意升级项。"""
    u = current_user()
    if not u:
        return jsonify({"error": "no user"}), 400
    row = db_fetchone(
        "SELECT id, channel, store_id, company_id, processed FROM reminder_log WHERE id=?",
        (rid,),
    )
    if not row or row["channel"] != "escalation":
        return jsonify({"error": "升级项不存在"}), 404
    role = session.get("role")
    if role == "store_manager":
        if u["store_id"] is None or row["store_id"] != u["store_id"]:
            return jsonify({"error": "无权处理其它门店的升级项"}), 403
    else:  # admin / super
        if role != "super" and row["company_id"] != u["company_id"]:
            return jsonify({"error": "无权处理其它公司的升级项"}), 403
    db_write(
        "UPDATE reminder_log SET handled_at=datetime('now','localtime'), handled_by=?, "
        "read_at=COALESCE(read_at, datetime('now','localtime')) WHERE id=?",
        (u["id"], rid),
    )
    return jsonify({"ok": True, "id": rid})


def reap_running_on_boot():
    """启动时回收所有 running 任务：进程重启会把后台分析线程一起 SIGTERM 掉，
    DB 里残留的 running 状态没人写回 → 任务永远挂着。boot 时统一标 failed，
    让用户在前端能直接重跑。"""
    try:
        rows = db_fetchall("SELECT id, task_status FROM sessions WHERE task_status IS NOT NULL")
        reaped = 0
        for r in rows:
            try:
                ts = json.loads(r["task_status"])
            except (json.JSONDecodeError, TypeError):
                continue
            changed = False
            for tid, s in ts.items():
                if isinstance(s, dict) and s.get("status") == "running":
                    s["status"] = "failed"
                    s["error"] = "服务重启中断，请重跑"
                    s["updated_at"] = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
                    changed = True
            if changed:
                db_write(
                    "UPDATE sessions SET task_status=? WHERE id=?",
                    (json.dumps(ts, ensure_ascii=False), r["id"]),
                )
                reaped += 1
        print(f"[reap_running_on_boot] 回收了 {reaped} 个 session 的卡 running 任务")
    except Exception as e:
        print(f"[reap_running_on_boot] 出错: {e}")


reap_running_on_boot()


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5058, debug=True)
