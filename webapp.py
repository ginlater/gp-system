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
from datetime import datetime
from functools import wraps
from http import HTTPStatus
from pathlib import Path
from urllib import request as urllib_request

import oss2
from flask import (Flask, abort, g, jsonify, redirect, render_template,
                   request, session, url_for)

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

DB_PATH = os.environ.get("DB_PATH", str(Path(__file__).parent / "recordings.db"))
KB_PATH = os.environ.get("KNOWLEDGE_BASE_PATH",
                         str(Path(__file__).parent / "output" / "logic_library.json"))

DEFAULT_MODEL = os.environ.get("ANALYSIS_MODEL", "deepseek-v4-pro")
ANTHROPIC_PROXY = os.environ.get("ANTHROPIC_PROXY", "http://127.0.0.1:7890")

# 可选分析模型表（前端下拉用）
# provider: anthropic 走代理；deepseek 直连国内不走代理
SUPPORTED_MODELS = [
    {"id": "deepseek-v4-pro",   "provider": "deepseek",
     "label": "DeepSeek V4 Pro（国内直连，含深度思考，默认）"},
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
_db_lock = threading.Lock()

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
    role TEXT NOT NULL DEFAULT 'consultant',  -- super | admin | consultant
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

    # 2026-05-25 接诊包改造：phone_tail / locked / customer_id / daily_reception
    cc_cols = {r[1] for r in conn.execute("PRAGMA table_info(company_customers)").fetchall()}
    if "phone_tail" not in cc_cols:
        conn.execute("ALTER TABLE company_customers ADD COLUMN phone_tail TEXT")
    sess_cols2 = {r[1] for r in conn.execute("PRAGMA table_info(sessions)").fetchall()}
    if "locked" not in sess_cols2:
        conn.execute("ALTER TABLE sessions ADD COLUMN locked INTEGER DEFAULT 0")
    if "customer_id" not in sess_cols2:
        conn.execute("ALTER TABLE sessions ADD COLUMN customer_id INTEGER")

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
    conn.commit()
    conn.close()


def db_exec(sql, params=()):
    """线程安全的写/读单条"""
    with _db_lock:
        conn = sqlite3.connect(DB_PATH)
        conn.row_factory = sqlite3.Row
        try:
            cur = conn.execute(sql, params)
            conn.commit()
            return cur
        finally:
            pass  # connection 仍可用直到 GC，但我们已 commit


def db_write(sql, params=()):
    with _db_lock:
        conn = sqlite3.connect(DB_PATH, timeout=10.0)
        try:
            cur = conn.execute(sql, params)
            conn.commit()
            return cur.lastrowid
        finally:
            conn.close()


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


def oss_signed_url(oss_key, expires=7200):
    url = oss_bucket.sign_url("GET", oss_key, expires, slash_safe=True)
    # 站点跑在 https，OSS endpoint 未带 scheme 时 sign_url 默认拼 http://，
    # 浏览器会按 mixed content 静默拦截（音频加载失败 / 地址栏不安全提示）。
    if url.startswith("http://"):
        url = "https://" + url[len("http://"):]
    return url


# ============ Session 管理 ============
def get_or_create_session(advisor, customer, service_date, company_id=1):
    """根据 (advisor, customer, service_date) 找或创建 session。返回 session_id。
    如果三个字段任一为空，归到一个独立 session（按 oss_key 区分）。"""
    if not (advisor and customer and service_date):
        return None  # 调用方决定怎么处理（一般用 oss_key 兜底建独立 session）

    row = db_fetchone(
        "SELECT id FROM sessions WHERE advisor=? AND customer=? AND service_date=? AND company_id=?",
        (advisor, customer, service_date, company_id or 1),
    )
    if row:
        return row["id"]
    # UNIQUE 索引兜底：两个并发请求同时 INSERT 时，后者会 IGNORE 并重新查到先入库的那条
    db_write(
        "INSERT OR IGNORE INTO sessions (advisor, customer, service_date, company_id) VALUES (?, ?, ?, ?)",
        (advisor, customer, service_date, company_id or 1),
    )
    row = db_fetchone(
        "SELECT id FROM sessions WHERE advisor=? AND customer=? AND service_date=? AND company_id=?",
        (advisor, customer, service_date, company_id or 1),
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
        "INSERT INTO sessions (advisor, customer, service_date, company_id) VALUES (?, ?, ?, ?)",
        (advisor or "(未填顾问)", customer or "(未填顾客)", fake_date, company_id or 1),
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
        # 有"说话人>2"且顾问未确认的录音 → 不自动分析，等顾问处理
        warn = db_fetchone(
            """SELECT COUNT(*) AS n FROM recordings
               WHERE session_id=? AND asr_speaker_warning=1
                 AND COALESCE(speaker_confirmed,0)=0""",
            (session_id,),
        )
        if warn and warn["n"] > 0:
            return
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


def run_asr(recording_id):
    rec = db_fetchone("SELECT oss_key FROM recordings WHERE id = ?", (recording_id,))
    if not rec:
        return
    oss_key = rec["oss_key"]
    db_write(
        """UPDATE recordings SET asr_status='running',
           asr_started_at=datetime('now','localtime'), asr_error=NULL WHERE id=?""",
        (recording_id,),
    )
    try:
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
                urllib_request.urlopen(transcription["transcription_url"]).read().decode("utf8")
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
        warning = 1 if spk_count > 2 else 0
        db_write(
            """UPDATE recordings SET asr_status='done',
               asr_result_json=?, asr_transcript=?,
               asr_speaker_count=?, asr_speaker_warning=?,
               asr_finished_at=datetime('now','localtime') WHERE id=?""",
            (json.dumps(full_json, ensure_ascii=False), transcript,
             spk_count, warning, recording_id),
        )
    except Exception as e:
        db_write(
            """UPDATE recordings SET asr_status='failed', asr_error=?,
               asr_finished_at=datetime('now','localtime') WHERE id=?""",
            (str(e)[:2000], recording_id),
        )


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
                             "quality_score", "suggestions"],
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
                    "suggestions": {
                        "type": "array",
                        "minItems": 2, "maxItems": 5,
                        "items": {"type": "string",
                                  "description": "1 条精炼的可操作建议"},
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
你会收到前两步的分析结果（顾客画像、痛点、成交诊断、质检评分、Case复盘、失分根因），
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
                "description": "从录音中提取的顾客所有特征标签，不限来源（消费/习惯/身体状况/心理特征/品牌偏好），每个 10 字以内",
                "minItems": 3,
                "items": {"type": "string"},
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
    "description": "提交接诊评判：质检评分、Case复盘、失分根因、收割四步",
    "input_schema": {
        "type": "object",
        "required": ["scoring", "cases", "cases_summary", "root_cause", "harvest"],
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
            "root_cause": {
                "type": "object",
                "required": ["headline", "product_dimension",
                             "problem_dimension", "gap_note"],
                "properties": {
                    "headline": {"type": "string",
                                 "description": "从所有 case 里往上抽象一层，找到背后唯一的思维模式根因，不是某句话的问题，是整体思维模式，如'始终在产品维度对话而非问题维度'"},
                    "lead": {"type": "string"},
                    "product_dimension": {
                        "type": "array", "items": {"type": "string"},
                        "description": "顾问实际说的（2-3 条，简短）",
                    },
                    "problem_dimension": {
                        "type": "array", "items": {"type": "string"},
                        "description": "顾客需要听到的（2-3 条，与上面一一对应）",
                    },
                    "gap_note": {"type": "string", "description": "差距本质，一句话"},
                },
            },
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
                            "required": ["title", "body"],
                            "properties": {
                                "title": {"type": "string",
                                          "description": "步骤标题，如 '引导顾客说出效果' / '解释今天效果的原理' / '埋下下次的钩子' / '自然过渡到方案'"},
                                "body": {"type": "string",
                                         "description": "具体话术 + 操作说明（话术用「」或斜体），必须含顾客真名"},
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
                             "quality_score", "suggestions"],
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
                    "suggestions": {
                        "type": "array",
                        "minItems": 2, "maxItems": 4,
                        "items": {"type": "string"},
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
                            "required": ["title", "body"],
                            "properties": {
                                "title": {"type": "string"},
                                "body": {"type": "string", "minLength": 40,
                                         "description": "含顾客真名，以'上次你提到...'开头，50-80 字"},
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
                            "required": ["pain_name", "entry", "principle",
                                         "direction", "sales_link"],
                            "properties": {
                                "pain_name": {"type": "string"},
                                "entry": {"type": "string",
                                          "description": "切入话，含顾客真名，以'上次你提到...'开头"},
                                "principle": {"type": "string",
                                              "description": "原理话，身体机制解释，30 字"},
                                "direction": {"type": "string",
                                              "description": "方向话，医美和我们的分工边界，30 字"},
                                "sales_link": {"type": "string",
                                               "description": "回销售衔接句，20 字"},
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
比如顾客经常熬夜 → customer_tags 加"长期熬夜"
比如顾客在用益生菌 → customer_tags 加"益生菌用户"

【(B) customer_tags 数组（外层字段，绝对不能省略！）】

从整段录音里提炼顾客特征标签（每个 10 字以内），至少 5 个。
来源不限：消费类型 + 生活习惯 + 身体状况 + 心理特征 + 品牌偏好。
示例：["医美深度用户", "C级潜力客户", "长期熬夜", "理性克制型", "活细胞用户"]

⚠ 再次强调：customer_tags 是 external_signals 的**同级**字段，不是包在里面。两个字段都要填，缺一个就是失败。
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
    "T7": {
        "name": "失分根因",
        "call": 2,
        "result_keys": ["root_cause"],
        "schema_keys": ["root_cause"],
        "depends_on": [],
        "prompt_snippet": """【任务7】接诊失分根因
⚠ root_cause 对象的所有字段必填，不能为空：
- headline：思维模式根因（整体抽象，如"始终在产品维度对话而非问题维度"，不是某句话的问题）
- product_dimension：顾问实际说的（2-3 条字符串）
- problem_dimension：顾客需要听到的（2-3 条字符串，与上面一一对应）
- gap_note：差距本质，一句话

注意：不要重复 Case 复盘里的具体问题，要从所有 case 往上抽象一层，找到背后唯一的思维模式根因。
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

⚠ harvest 对象的所有字段必填：
- intro：导语一句，强调黄金窗口为什么重要
- steps：3-5 步，每步：title（步骤名）+ body（具体话术 + 操作说明，话术用「」或斜体，必须含"{customer_name}"）

注意：harvest 是给顾问的"下次怎么做"指引，不是复盘本次。
""",
    },

    # ───── 调用3：用前两次结果摘要 ─────
    "T9": {
        "name": "PART1总览",
        "call": 3,
        "result_keys": ["overview"],
        "schema_keys": ["overview"],
        "depends_on": ["T1", "T2", "T3", "T4", "T5", "T6", "T7"],
        "prompt_snippet": """【任务9】PART1 全维度评估总览
⚠ overview 对象必含 5 个子字段，都不能省略：
- customer_value：顾客价值评级（tag 简短 + tag_kind + note 一句话引用关键信号）
- pain_summary：痛点识别（tag 如"3 个核心可攻破点" + items 列每个痛点，color 用 red/blue/teal/orange）
- sales_diagnosis：销售问题诊断（tag 标签化根因 + tag_kind + note 复用 headline）
- quality_score：质检评分（score 复用 overall + note 一句话）
- suggestions：2-4 条可操作建议字符串数组（不能空数组），指向报告具体内容
""",
    },
    "T10": {
        "name": "能力训练路径",
        "call": 3,
        "result_keys": ["logic_chain"],
        "schema_keys": ["logic_chain"],
        "depends_on": ["T6", "T7"],
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
- return_scripts：至少 2 条回店话术，含"{customer_name}"，以"上次你提到..."开头，50-80 字
- priority_projects：按成交难度从低到高 2-3 条（name + desc）
- pain_entry_scripts：针对每个痛点的四步话术（pain_name + entry 含{customer_name} + principle 30 字 + direction 30 字 + sales_link 20 字）
- medical_objections：至少 3 条泛医疗异议应答，60-100 字，严格遵循 ①承认医院 → ②分工边界 → ③我们位置 → ④互补不冲突
""",
    },
}


CALL_GROUPS = {
    1: ["T1", "T2", "T3", "T4"],
    2: ["T5", "T6", "T7", "T8"],
    3: ["T9", "T10", "T11"],
}

# 在同一个 Call 内部，每个 chunk 走一次独立的 LLM 小调用，输出字段少、不易被
# DeepSeek V4 tool_call 的字符串化 JSON 撑爆 max_tokens。Call 1/2 走 shared
# preflight 先产判断底稿，再按下面分块各自基于底稿组装正式字段；Call 3 输入
# 本就是结构化摘要，不做 preflight，直接分块。
CALL_CHUNKS = {
    1: [["T1"], ["T2"], ["T3", "T4"]],
    2: [["T5"], ["T6"], ["T7", "T8"]],
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
    "good_highlights", "bad_highlights", "suggestions",
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

    root = call2_result.get("root_cause", {}) or {}
    c2.append(f"【失分根因】{root.get('headline','')}")
    pd = root.get("product_dimension") or []
    prd = root.get("problem_dimension") or []
    for i in range(min(len(pd), len(prd))):
        c2.append(f"  顾问说：{pd[i]} → 应该说：{prd[i]}")
    c2.append(f"差距本质：{root.get('gap_note','')}")

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
                   enable_thinking=False, stage_label="", temperature=0.3):
    """调用 DeepSeek（OpenAI 兼容），不走代理，返回 tool_calls[0].function.arguments dict。

    max_tokens 语义是"输出 token 预算"；对 V4 思考模式会在内部加 16K thinking buffer。
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
    payload = {
        "model": model,
        "messages": [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_prompt},
        ],
        "tools": [openai_tool],
        # V4 思考模式只支持 "auto"，不支持指定具体 function；V3 仍可强制
        "tool_choice": "auto" if is_v4 else {
            "type": "function",
            "function": {"name": tool["name"]},
        },
        "max_tokens": out_budget,
        "temperature": temperature,
    }
    # V4 thinking 模式：只在显式开启时启用（默认关闭，避免 token 截断）
    if is_v4 and enable_thinking:
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


def _call_llm(model, system_prompt, user_prompt, tool, max_tokens=None,
              enable_thinking=False, stage_label="", temperature=0.3):
    """统一入口：根据 model 的 provider 调 Claude 或 DeepSeek。"""
    provider = MODEL_PROVIDER.get(model)
    if provider == "anthropic":
        return _call_anthropic(model, system_prompt, user_prompt, tool=tool,
                                max_tokens=max_tokens or 16000)
    if provider == "deepseek":
        return _call_deepseek(model, system_prompt, user_prompt, tool=tool,
                              max_tokens=max_tokens, enable_thinking=enable_thinking,
                              stage_label=stage_label, temperature=temperature)
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
        try:
            result = _call_llm(model, system_prompt, user_prompt, tool=tool,
                               max_tokens=max_tokens,
                               enable_thinking=enable_thinking,
                               stage_label=stage_label,
                               temperature=temperature)
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


def save_customer_tags(session_id, customer_name, advisor_name, call1_result):
    """把本次分析生成的顾客标签写入 customer_tags 表（去重，先删本 session 来源的旧记录）"""
    if not customer_name:
        return
    ext = call1_result.get("external_signals", {}) or {}
    tags = set()

    # 1) 外层 customer_tags 数组
    for tag in call1_result.get("customer_tags") or []:
        if tag and isinstance(tag, str):
            tags.add(tag.strip())

    # 2) lifestyle_habits / self_care 的 customer_tag
    for cat in ("lifestyle_habits", "self_care"):
        for item in ext.get(cat) or []:
            tag = (item.get("customer_tag") if isinstance(item, dict) else "") or ""
            if tag:
                tags.add(tag.strip())

    # 3) 三类竞品 → "XX用户" 标签
    for cat in ("medical_aesthetics", "other_institutions", "external_brands"):
        for item in ext.get(cat) or []:
            brand = (item.get("item") if isinstance(item, dict) else "") or ""
            if brand:
                tags.add(f"{brand.strip()}用户")

    tags.discard("")
    if not tags:
        return

    # 重跑时先清掉本 session 之前写入的标签，避免重复累计
    db_write(
        "DELETE FROM customer_tags WHERE source_session_id=?", (session_id,)
    )
    with _db_lock:
        conn = sqlite3.connect(DB_PATH)
        try:
            conn.executemany(
                """INSERT INTO customer_tags
                   (customer_name, advisor_name, tag, source_session_id)
                   VALUES (?, ?, ?, ?)""",
                [(customer_name, advisor_name, t, session_id) for t in tags],
            )
            conn.commit()
        finally:
            conn.close()


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
        "SELECT advisor, customer, service_date FROM sessions WHERE id=?", (session_id,)
    )
    if not sess:
        return
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
            stage_label=stage, max_attempts=2)
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

        tool_name = {1: "submit_call1", 2: "submit_call2", 3: "submit_call3"}[call_no]
        user_prompt = (
            f"顾客姓名：{customer_name}\n"
            f"顾问姓名：{advisor_name}\n"
            f"服务日期：{sess['service_date'] or '未知'}\n\n"
            f"{input_section}\n\n"
            f"---\n请完成以下 {len(tids)} 个任务，调用 {tool_name} 提交：\n\n"
            + "\n\n".join(task_prompts)
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
                stage_label=stage, max_attempts=2)
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
                     orphan=False):
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
        session_id = get_or_create_session(advisor, customer, service_date, company_id)
    else:
        session_id = get_or_create_orphan_session(advisor, customer, oss_key, company_id)

    if size_bytes is None:
        try:
            size_bytes = oss_bucket.head_object(oss_key).content_length
        except Exception:
            size_bytes = 0

    rid = db_write(
        """INSERT INTO recordings
           (session_id, oss_key, advisor, customer, recorded_at,
            duration_label, size_bytes, source, company_id, uploader_user_id)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
        (session_id, oss_key, advisor, customer, recorded_at,
         duration_label, size_bytes, source, company_id or 1, uploader_user_id),
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
        "SELECT id, username, role, company_id, advisor_name, employee_id, phone FROM users WHERE id=?",
        (uid,),
    )


def current_company_id():
    u = current_user()
    return u["company_id"] if u else None


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
            "SELECT id, username, password_hash, role, company_id, advisor_name FROM users WHERE username=?",
            (u,),
        )
        if row and row["password_hash"] == _hash_pw(p):
            session["logged_in"] = True
            session["user_id"] = row["id"]
            session["username"] = row["username"]
            session["role"] = row["role"]
            session["company_id"] = row["company_id"]
            session["advisor_name"] = row["advisor_name"] or ""
            session.permanent = True
            # 顾问默认跳 consultant 页
            nxt = _safe_next(request.args.get("next"))
            if nxt:
                return redirect(nxt)
            if row["role"] == "consultant":
                return redirect(url_for("consultant_page"))
            return redirect(url_for("index"))
        error = "用户名或密码错误"
    return render_template("login.html", error=error)


@app.route("/logout")
def logout():
    session.clear()
    return redirect(url_for("login"))


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
@admin_required
def admin_page():
    return render_template(
        "admin.html",
        username=session.get("username"),
        role=session.get("role"),
    )


@app.route("/consultant")
@login_required
def consultant_page():
    u = current_user()
    if not u:
        return redirect(url_for("logout"))
    return render_template(
        "consultant.html",
        username=u["username"],
        advisor_name=u["advisor_name"] or "",
        role=u["role"],
    )


@app.route("/session/<int:sid>")
@login_required
def session_detail(sid):
    sess = db_fetchone("SELECT * FROM sessions WHERE id=?", (sid,))
    if not sess:
        abort(404)
    sess_d = dict(sess)
    recs = db_fetchall("""
        SELECT id, oss_key, recorded_at, duration_label, size_bytes, source,
               asr_status, asr_transcript, asr_error,
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

    # 该顾客历次接诊累积标签（去重，按出现次数倒序）
    customer_tags_history = []
    if sess_d.get("customer"):
        tag_rows = db_fetchall(
            """SELECT tag, COUNT(*) AS cnt
               FROM customer_tags
               WHERE customer_name=?
               GROUP BY tag
               ORDER BY cnt DESC, MAX(created_at) DESC
               LIMIT 30""",
            (sess_d["customer"],),
        )
        customer_tags_history = [{"tag": r["tag"], "count": r["cnt"]} for r in tag_rows]

    resp = app.make_response(render_template(
        "report.html",
        sess=sess_d,
        recordings=recordings,
        report=report,
        evaluations=evals,
        customer_tags_history=customer_tags_history,
        username=session.get("username"),
        role=session.get("role"),
    ))
    resp.headers["Cache-Control"] = "no-store, no-cache, must-revalidate, max-age=0"
    resp.headers["Pragma"] = "no-cache"
    resp.headers["Expires"] = "0"
    return resp


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
    elif role == "admin":
        if cid:
            where.append("(s.company_id IS NULL OR s.company_id = ?)")
            params.append(cid)
    # super 不过滤
    if advisor:
        where.append("s.advisor LIKE ?")
        params.append(f"%{advisor}%")
    if customer:
        where.append("s.customer LIKE ?")
        params.append(f"%{customer}%")
    if date:
        where.append("s.service_date = ?")
        params.append(date)
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
               s.analysis_finished_at,
               COUNT(r.id) AS recording_count,
               SUM(CASE WHEN r.asr_status='done' THEN 1 ELSE 0 END) AS asr_done_count,
               SUM(CASE WHEN r.asr_status='running' THEN 1 ELSE 0 END) AS asr_running_count,
               SUM(CASE WHEN r.asr_status='failed' THEN 1 ELSE 0 END) AS asr_failed_count
        FROM sessions s
        LEFT JOIN recordings r ON r.session_id = s.id
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
    """给列表页 5 个状态 pill 用的计数。受角色/公司隔离约束。"""
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
    elif role == "admin":
        if cid:
            where.append("(s.company_id IS NULL OR s.company_id = ?)")
            params.append(cid)
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
    out = dict(sess)
    recs = db_fetchall("""
        SELECT id, oss_key, recorded_at, duration_label, size_bytes, source,
               asr_status, asr_transcript, asr_error,
               asr_started_at, asr_finished_at,
               asr_speaker_count, asr_speaker_warning, speaker_confirmed
        FROM recordings WHERE session_id=?
        ORDER BY COALESCE(recorded_at, ''), id
    """, (sid,))
    out["recordings"] = []
    for r in recs:
        d = dict(r)
        d["audio_url"] = oss_signed_url(r["oss_key"], expires=7200)
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
    out["display_status"] = _display_status(
        out.get("analysis_status"), out.get("analysis_progress"),
        bool(out.get("analysis_result")),
    )
    return jsonify(out)


@app.route("/api/scan", methods=["POST"])
@login_required
def api_scan():
    added = scan_oss_bucket()
    return jsonify({"added": added})


@app.route("/api/upload", methods=["POST"])
@login_required
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
    recorded_at_raw = (request.form.get("recorded_at") or "").strip()
    duration_label = (request.form.get("duration_label") or "").strip() or None

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

    created = []
    for f in files:
        if not f or not f.filename:
            continue
        orig_name = f.filename
        # 默认 oss_key 直接用原文件名（保留命名约定）
        oss_key = orig_name

        # 如果名字含路径分隔符或不安全字符，转义
        if "/" in oss_key or "\\" in oss_key:
            oss_key = oss_key.replace("/", "_").replace("\\", "_")

        # 重名 → 加 uuid 前缀避免覆盖
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
            duration_label=duration_label,
        )
        rec = db_fetchone("SELECT session_id FROM recordings WHERE id=?", (rid,))
        created.append({"id": rid, "oss_key": oss_key, "session_id": rec["session_id"]})

    return jsonify({"created": created})


@app.route("/api/recording/<int:rid>/url")
@login_required
def api_recording_url(rid):
    """按需签名：返回新鲜的 OSS 播放 URL，1 小时有效。"""
    rec = db_fetchone("SELECT oss_key FROM recordings WHERE id=?", (rid,))
    if not rec or not rec["oss_key"]:
        return jsonify({"error": "not found"}), 404
    try:
        url = oss_signed_url(rec["oss_key"], expires=3600)
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
    rec = db_fetchone("SELECT id, oss_key, session_id, company_id FROM recordings WHERE id=?", (rid,))
    if not rec:
        return jsonify({"error": "录音不存在"}), 404
    if session.get("role") != "super" and rec["company_id"] != session.get("company_id"):
        return jsonify({"error": "无权操作"}), 403
    try:
        oss_bucket.delete_object(rec["oss_key"])
    except Exception as e:
        print(f"[delete_recording] OSS delete failed: {e}")
    db_write("DELETE FROM delete_requests WHERE recording_id=?", (rid,))
    db_write("DELETE FROM recordings WHERE id=?", (rid,))
    return jsonify({"ok": True})


@app.route("/api/recording/<int:rid>/delete-request", methods=["POST"])
@login_required
def api_recording_delete_request(rid):
    """顾问申请删除录音"""
    rec = db_fetchone("SELECT id, session_id, company_id FROM recordings WHERE id=?", (rid,))
    if not rec:
        return jsonify({"error": "录音不存在"}), 404
    role = session.get("role")
    if role in ("admin", "super"):
        return jsonify({"error": "管理员请直接删除"}), 400
    # 顾问只能申请自己公司的录音
    if rec["company_id"] != session.get("company_id"):
        return jsonify({"error": "无权操作"}), 403
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


@app.route("/api/session/<int:sid>/report/delete", methods=["DELETE"])
@admin_required
def api_session_report_delete(sid):
    """管理员清除分析报告（接诊记录和录音保留）"""
    sess = db_fetchone("SELECT id, company_id FROM sessions WHERE id=?", (sid,))
    if not sess:
        return jsonify({"error": "接诊不存在"}), 404
    if session.get("role") != "super" and sess["company_id"] != session.get("company_id"):
        return jsonify({"error": "无权操作"}), 403
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
    recs = db_fetchall("SELECT id, oss_key FROM recordings WHERE session_id=?", (sid,))
    for r in recs:
        try:
            oss_bucket.delete_object(r["oss_key"])
        except Exception as e:
            print(f"[session_full_delete] OSS {r['oss_key']}: {e}")
        db_write("DELETE FROM delete_requests WHERE recording_id=?", (r["id"],))
        db_write("DELETE FROM recordings WHERE id=?", (r["id"],))
    db_write("DELETE FROM evaluations WHERE session_id=?", (sid,))
    db_write("DELETE FROM customer_tags WHERE source_session_id=?", (sid,))
    db_write("DELETE FROM sessions WHERE id=?", (sid,))
    return jsonify({"ok": True})


@app.route("/api/session/<int:sid>/recordings/delete", methods=["DELETE"])
@admin_required
def api_session_recordings_delete(sid):
    """管理员删除某 session 下所有录音（OSS + DB）"""
    recs = db_fetchall("SELECT id, oss_key, company_id FROM recordings WHERE session_id=?", (sid,))
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
        db_write("DELETE FROM delete_requests WHERE recording_id=?", (r["id"],))
        db_write("DELETE FROM recordings WHERE id=?", (r["id"],))
    return jsonify({"ok": True, "deleted": len(recs)})


@app.route("/api/session/<int:sid>/delete-request", methods=["POST"])
@login_required
def api_session_delete_request(sid):
    """顾问申请删除某 session 下所有录音"""
    role = session.get("role")
    if role in ("admin", "super"):
        return jsonify({"error": "管理员请直接删除"}), 400
    recs = db_fetchall("SELECT id, session_id, company_id FROM recordings WHERE session_id=?", (sid,))
    if not recs:
        return jsonify({"error": "该接诊没有录音"}), 404
    for r in recs:
        if r["company_id"] != session.get("company_id"):
            return jsonify({"error": "无权操作"}), 403
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
    return jsonify({"requests": [dict(r) for r in rows]})


@app.route("/api/admin/delete-requests/<int:req_id>/approve", methods=["POST"])
@admin_required
def api_admin_delete_request_approve(req_id):
    """管理员审批通过：删除录音"""
    dr = db_fetchone("SELECT * FROM delete_requests WHERE id=?", (req_id,))
    if not dr:
        return jsonify({"error": "申请不存在"}), 404
    if dr["status"] != "pending":
        return jsonify({"error": "该申请已处理"}), 400
    rec = db_fetchone("SELECT id, oss_key, company_id FROM recordings WHERE id=?", (dr["recording_id"],))
    if not rec:
        # 录音已不存在，直接更新申请状态
        db_write(
            "UPDATE delete_requests SET status='approved', reviewer_user_id=?, reviewer_name=?, reviewed_at=datetime('now','localtime') WHERE id=?",
            (session.get("user_id"), session.get("advisor_name") or session.get("username"), req_id),
        )
        return jsonify({"ok": True})
    if session.get("role") != "super" and rec["company_id"] != session.get("company_id"):
        return jsonify({"error": "无权操作"}), 403
    reviewer = session.get("advisor_name") or session.get("username")
    try:
        oss_bucket.delete_object(rec["oss_key"])
    except Exception as e:
        print(f"[approve_delete] OSS delete failed: {e}")
    db_write("DELETE FROM recordings WHERE id=?", (rec["id"],))
    db_write(
        "UPDATE delete_requests SET status='approved', reviewer_user_id=?, reviewer_name=?, reviewed_at=datetime('now','localtime') WHERE id=?",
        (session.get("user_id"), reviewer, req_id),
    )
    return jsonify({"ok": True})


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
        # DeepSeek 没 key 时禁用
        disabled = (m["provider"] == "deepseek" and not DEEPSEEK_API_KEY)
        available.append({**m, "disabled": disabled})
    return jsonify({"models": available, "default": DEFAULT_MODEL})


@app.route("/api/session/<int:sid>/analyze", methods=["POST"])
@login_required
def api_session_analyze(sid):
    """老板手动重跑分析（可指定模型）"""
    sess = db_fetchone("SELECT id FROM sessions WHERE id=?", (sid,))
    if not sess:
        return jsonify({"error": "not found"}), 404

    data = request.get_json(silent=True) or {}
    model = (data.get("model") or "").strip() or None
    if model and model not in MODEL_PROVIDER:
        return jsonify({"error": f"不支持的模型: {model}"}), 400

    # 强制重新分析（无视 signature）
    db_write(
        """UPDATE sessions SET analysis_status='pending',
           analysis_signature=NULL, analysis_error=NULL,
           analysis_progress='排队中…' WHERE id=?""",
        (sid,),
    )
    # 顶部"⟳ 重跑"语义：必须重读完整录音、重新生成 shared_context
    maybe_trigger_session_analysis(sid, model=model, force_refresh_shared=True)
    # 检查是否真正进入 running（ASR 未完成则仍是 pending）
    cur = db_fetchone("SELECT analysis_status, analysis_progress FROM sessions WHERE id=?", (sid,))
    return jsonify({
        "status": cur["analysis_status"] if cur else "pending",
        "progress": cur["analysis_progress"] if cur else None,
        "model": model or DEFAULT_MODEL,
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
    })


# ============ 管理员：顾问账号 ============
# ============ 管理员统计（轻量缓存，避免每次切 tab 都查库）============
_admin_stats_cache = {"data": None, "ts": 0.0}
_admin_stats_lock = threading.Lock()


def _invalidate_admin_stats():
    with _admin_stats_lock:
        _admin_stats_cache["data"] = None
        _admin_stats_cache["ts"] = 0.0


def _compute_admin_stats(cid, is_super):
    if is_super:
        c1 = db_fetchone("SELECT COUNT(*) AS n FROM users WHERE role='consultant'")["n"]
        c2 = db_fetchone("SELECT COUNT(*) AS n FROM company_customers")["n"]
        c3 = db_fetchone("SELECT COUNT(*) AS n FROM delete_requests WHERE status='pending'")["n"]
    else:
        c1 = db_fetchone(
            "SELECT COUNT(*) AS n FROM users WHERE role='consultant' AND company_id=?",
            (cid,),
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
    return {"consultants": c1, "customers": c2, "delete_requests_pending": c3}


@app.route("/api/admin/stats")
@admin_required
def api_admin_stats():
    cid = session.get("company_id")
    is_super = session.get("role") == "super"
    cache_key = ("super",) if is_super else ("cid", cid)
    import time as _time
    now = _time.time()
    with _admin_stats_lock:
        cached = _admin_stats_cache.get("data")
        ts = _admin_stats_cache.get("ts", 0.0)
        if cached and cached.get("_key") == cache_key and now - ts < 30:
            out = {k: v for k, v in cached.items() if k != "_key"}
            return jsonify(out)
    data = _compute_admin_stats(cid, is_super)
    data["_key"] = cache_key
    with _admin_stats_lock:
        _admin_stats_cache["data"] = data
        _admin_stats_cache["ts"] = now
    return jsonify({k: v for k, v in data.items() if k != "_key"})


@app.route("/api/admin/consultants", methods=["GET"])
@admin_required
def api_admin_consultants_list():
    cid = session.get("company_id")
    q = (request.args.get("q") or "").strip()
    is_super = session.get("role") == "super"
    base_where = "role='consultant'" if is_super else "role='consultant' AND company_id=?"
    base_params = [] if is_super else [cid]
    if q:
        where = base_where + " AND (advisor_name LIKE ? OR employee_id LIKE ? OR phone LIKE ? OR username LIKE ?)"
        params = base_params + [f"%{q}%"] * 4
    else:
        where = base_where
        params = base_params
    rows = db_fetchall(
        f"SELECT id, username, role, company_id, advisor_name, employee_id, phone, created_at "
        f"FROM users WHERE {where} ORDER BY id DESC LIMIT 100",
        tuple(params),
    )
    total = db_fetchone(
        f"SELECT COUNT(*) AS n FROM users WHERE {where}", tuple(params)
    )["n"]
    total_all = db_fetchone(
        f"SELECT COUNT(*) AS n FROM users WHERE {base_where}", tuple(base_params)
    )["n"]
    return jsonify({
        "consultants": [dict(r) for r in rows],
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
    cid = session.get("company_id") or 1
    if session.get("role") == "super":
        cid = int(data.get("company_id") or cid)
    try:
        uid = db_write(
            """INSERT INTO users (username, password_hash, role, company_id,
                                  advisor_name, employee_id, phone)
               VALUES (?, ?, 'consultant', ?, ?, ?, ?)""",
            (username, _hash_pw(password), cid, advisor_name, employee_id or None, phone or None),
        )
    except sqlite3.IntegrityError:
        return jsonify({"error": f"账号 {username} 已存在"}), 409
    return jsonify({"id": uid, "username": username})


@app.route("/api/admin/consultants/<int:uid>", methods=["PATCH"])
@admin_required
def api_admin_consultants_update(uid):
    row = db_fetchone("SELECT id, company_id, role FROM users WHERE id=?", (uid,))
    if not row or row["role"] != "consultant":
        return jsonify({"error": "顾问不存在"}), 404
    if session.get("role") != "super" and row["company_id"] != session.get("company_id"):
        return jsonify({"error": "无权操作"}), 403
    data = request.get_json(silent=True) or {}
    sets, params = [], []
    if data.get("password"):
        sets.append("password_hash=?")
        params.append(_hash_pw(data["password"]))
    for f in ("advisor_name", "employee_id", "phone"):
        if f in data:
            sets.append(f"{f}=?")
            params.append((data[f] or "").strip() or None)
    if not sets:
        return jsonify({"error": "无修改字段"}), 400
    params.append(uid)
    db_write(f"UPDATE users SET {', '.join(sets)} WHERE id=?", tuple(params))
    return jsonify({"ok": True})


@app.route("/api/admin/consultants/<int:uid>", methods=["DELETE"])
@admin_required
def api_admin_consultants_delete(uid):
    row = db_fetchone("SELECT id, company_id, role FROM users WHERE id=?", (uid,))
    if not row or row["role"] != "consultant":
        return jsonify({"error": "顾问不存在"}), 404
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
                                      advisor_name, employee_id, phone)
                   VALUES (?, ?, 'consultant', ?, ?, ?, ?)""",
                (username, _hash_pw(pwd), cid, name, emp or None, phone or None),
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
    base_where = "1=1" if is_super else "company_id=?"
    base_params = [] if is_super else [cid]
    if q:
        where = base_where + " AND (name LIKE ? OR member_card LIKE ?)"
        params = base_params + [f"%{q}%", f"%{q}%"]
    else:
        where = base_where
        params = base_params
    rows = db_fetchall(
        f"SELECT id, name, member_card, company_id, created_at "
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


@app.route("/api/admin/customers", methods=["POST"])
@admin_required
def api_admin_customers_create():
    data = request.get_json(silent=True) or {}
    name = (data.get("name") or "").strip()
    card = (data.get("member_card") or "").strip()
    if not name:
        return jsonify({"error": "姓名必填"}), 400
    cid = session.get("company_id") or 1
    try:
        rid = db_write(
            "INSERT INTO company_customers (company_id, name, member_card) VALUES (?, ?, ?)",
            (cid, name, card or None),
        )
    except sqlite3.IntegrityError:
        return jsonify({"error": "该顾客已存在"}), 409
    return jsonify({"id": rid})


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


# 导入任务进度（in-memory；gunicorn -w 1 单 worker 够用）
_import_jobs = {}
_import_jobs_lock = threading.Lock()


def _run_customer_import(job_id, cid, seen):
    job = _import_jobs[job_id]
    try:
        total = len(seen)
        with _db_lock:
            conn = sqlite3.connect(DB_PATH)
            try:
                existing_rows = conn.execute(
                    "SELECT name, id, member_card FROM company_customers WHERE company_id=?",
                    (cid,),
                ).fetchall()
                existing_map = {row[0]: (row[1], row[2]) for row in existing_rows}
                conn.execute("BEGIN")
                processed = 0
                created = 0
                skipped = 0
                for name, card in seen.items():
                    if name in existing_map:
                        old_id, old_card = existing_map[name]
                        if card and card != (old_card or ""):
                            conn.execute(
                                "UPDATE company_customers SET member_card=? WHERE id=?",
                                (card, old_id),
                            )
                        skipped += 1
                    else:
                        conn.execute(
                            "INSERT INTO company_customers (company_id, name, member_card) VALUES (?, ?, ?)",
                            (cid, name, card or None),
                        )
                        created += 1
                    processed += 1
                    # 每 500 行刷一次进度
                    if processed % 500 == 0:
                        with _import_jobs_lock:
                            job["processed"] = processed
                            job["created"] = created
                            job["skipped"] = skipped
                conn.commit()
            finally:
                conn.close()
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
    try:
        db_write(
            """INSERT INTO users (username, password_hash, role, company_id, advisor_name)
               VALUES (?, ?, 'admin', ?, ?)""",
            (au, _hash_pw(ap), cid, an),
        )
    except sqlite3.IntegrityError:
        return jsonify({"error": "管理员用户名已存在（公司已创建，请单独再加管理员）"}), 409
    return jsonify({"id": cid})


# ============ 顾客下拉搜索（顾问/管理员通用） ============
@app.route("/api/customers/search")
@login_required
def api_customers_search():
    q = (request.args.get("q") or "").strip()
    cid = session.get("company_id") or 1
    sql = "SELECT id, name, member_card FROM company_customers WHERE company_id=?"
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
    if session.get("role") != "consultant":
        return jsonify({"error": "仅顾问账号可用"}), 403
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
    recorded_at = datetime.now().strftime("%Y%m%d%H%M%S")
    # 新格式：顾客未知 → 用 "未命名" 占位，绑定时再重命名
    oss_key = _build_consultant_oss_key(company_id, u['id'], None, advisor, recorded_at, dur_label, ext)
    if _oss_key_exists(oss_key):
        stem, _, ex = oss_key.rpartition(".")
        oss_key = f"{stem}_{_uuid.uuid4().hex[:4]}.{ex}"
    data = f.read()
    try:
        oss_bucket.put_object(oss_key, data)
    except Exception as e:
        return jsonify({"error": f"上传 OSS 失败：{e}"}), 500
    rid = ingest_recording(
        oss_key, source="consultant-upload", size_bytes=len(data),
        advisor=advisor, customer=None,
        recorded_at=recorded_at, service_date=recorded_at[:8],
        duration_label=dur_label,
        company_id=company_id, uploader_user_id=u["id"], orphan=True,
    )
    return jsonify({"id": rid, "oss_key": oss_key})


@app.route("/api/consultant/recordings/pending")
@login_required
def api_consultant_recordings_pending():
    err = _consultant_required()
    if err:
        return err
    u = current_user()
    advisor = u["advisor_name"] or u["username"]
    rows = db_fetchall(
        """SELECT id, oss_key, recorded_at, duration_label, size_bytes,
                  asr_status, asr_error, customer, created_at,
                  asr_speaker_count, asr_speaker_warning
           FROM recordings
           WHERE uploader_user_id=? AND session_id IS NULL
           ORDER BY id DESC LIMIT 200""",
        (u["id"],),
    )
    # 兼容老数据：advisor 同名但 uploader_user_id 为空的也算上
    rows2 = db_fetchall(
        """SELECT id, oss_key, recorded_at, duration_label, size_bytes,
                  asr_status, asr_error, customer, created_at,
                  asr_speaker_count, asr_speaker_warning
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
        try:
            d["audio_url"] = oss_signed_url(d["oss_key"], expires=3600)
        except Exception:
            d["audio_url"] = None
        d["rec_date"] = _rec_date_of(r)
        out.append(d)
    return jsonify({"recordings": out})


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


def _rec_date_of(rec_row):
    """录音的实际日期 YYYY-MM-DD：优先 recorded_at 前 8 位，否则 created_at，否则今天"""
    if rec_row is None:
        return _today_str()
    ra = (rec_row["recorded_at"] or "") if "recorded_at" in rec_row.keys() else ""
    d = _norm_date(ra[:8] if len(ra) >= 8 else "")
    if d:
        return d
    ca = rec_row["created_at"] if "created_at" in rec_row.keys() else None
    if ca and len(ca) >= 10:
        return ca[:10]
    return _today_str()


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
    cid = u["company_id"] or 1
    # 用录音日期（默认今天）当 service_date，再校验白名单
    rec_date = _norm_date((rec["recorded_at"] or "")[:8]) or _today_str()
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
    sid = get_or_create_session(advisor, cust["name"], rec_date, company_id=cid)
    # 把 customer_id 也写到 session（新字段，便于后续判断）
    db_write("UPDATE sessions SET customer_id=? WHERE id=? AND COALESCE(customer_id,0)=0",
             (customer_id, sid))
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
               (company_id, advisor_user_id, advisor_name, customer_id, service_date)
               VALUES (?, ?, ?, ?, ?)""",
            (cid, u["id"], advisor_name, customer_id, date),
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
    """未开始分析时，直接换绑（无需审批）。
    约束：session 必须未锁定；目标顾客必须在录音当天接诊白名单内。"""
    err = _consultant_required()
    if err:
        return err
    u = current_user()
    data = request.get_json(silent=True) or {}
    to_customer_id = data.get("to_customer_id")
    if not to_customer_id:
        return jsonify({"error": "请选择换绑目标顾客"}), 400
    rec = db_fetchone("SELECT * FROM recordings WHERE id=?", (rid,))
    if not rec:
        return jsonify({"error": "录音不存在"}), 404
    if rec["uploader_user_id"] and rec["uploader_user_id"] != u["id"]:
        return jsonify({"error": "无权操作他人录音"}), 403
    if not rec["session_id"]:
        return jsonify({"error": "该录音尚未绑定，请直接绑定即可"}), 400
    old_sess = db_fetchone("SELECT * FROM sessions WHERE id=?", (rec["session_id"],))
    if old_sess and old_sess["locked"]:
        return jsonify({"error": "已开始分析，无法直接换绑，请走申请换绑"}), 409
    cid = u["company_id"] or 1
    rec_date = _rec_date_of(rec)
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
        return jsonify({"error": "目标顾客不在录音当天接诊列表，请先补登"}), 400
    advisor = u["advisor_name"] or u["username"]
    new_sid = get_or_create_session(advisor, to_cust["name"], rec_date, company_id=cid)
    if not new_sid:
        return jsonify({"error": "创建目标接诊包失败"}), 500
    # 目标 session 不能是锁定的
    new_sess = db_fetchone("SELECT locked FROM sessions WHERE id=?", (new_sid,))
    if new_sess and new_sess["locked"]:
        return jsonify({"error": "目标接诊包已锁定，不能直接换绑（请改为申请换绑）"}), 409
    # OSS 重命名（新格式录音）；失败 → 回滚（DB 未动，告知前端重试）
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
    # 老 session 清空则删除（并删 daily_reception）
    old_sid = old_sess["id"] if old_sess else None
    if old_sid:
        cnt = db_fetchone("SELECT COUNT(*) AS n FROM recordings WHERE session_id=?", (old_sid,))
        if not cnt or not cnt["n"]:
            old_cust = db_fetchone(
                "SELECT id FROM company_customers WHERE company_id=? AND name=?",
                (cid, old_sess["customer"]),
            )
            db_write("DELETE FROM sessions WHERE id=?", (old_sid,))
            if old_cust:
                db_write(
                    """DELETE FROM daily_reception
                       WHERE advisor_user_id=? AND customer_id=? AND service_date=?""",
                    (u["id"], old_cust["id"], rec_date),
                )
    return jsonify({"ok": True, "new_session_id": new_sid})


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
    sql = "SELECT id, name, phone_tail, member_card FROM company_customers WHERE company_id=?"
    params = [cid]
    if q:
        sql += " AND (name LIKE ? OR phone_tail LIKE ? OR member_card LIKE ?)"
        like = f"%{q}%"
        params += [like, like, like]
    sql += " ORDER BY id DESC LIMIT 30"
    rows = db_fetchall(sql, tuple(params))
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
        """SELECT id, locked, analysis_status FROM sessions
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
    return jsonify({
        "customer": dict(cust),
        "service_date": date,
        "session_id": sess["id"] if sess else None,
        "locked": bool(sess["locked"]) if sess else False,
        "analysis_status": sess["analysis_status"] if sess else None,
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

    # ② 已锁定 + signature 没变 + 已完成 → 不允许重跑
    if (sess_detail["locked"]
            and sess_detail["analysis_signature"] == sig
            and sess_detail["analysis_status"] == "done"):
        return jsonify({"error": "分析已完成，录音未变化，无需重新分析"}), 409

    # 锁定 session
    db_write("UPDATE sessions SET locked=1 WHERE id=?", (sess["id"],))

    # ③ signature 没变 + 已有分析结果 → 只补跑失败/缺失的 task
    if (sess_detail["analysis_signature"] == sig
            and sess_detail["analysis_status"] in ("done", "failed")):
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
    return jsonify({"ok": True, "session_ids": triggered, "skipped": skipped})


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

        except Exception as e:
            print(f"[task_health_check] {e}")
        _time.sleep(900)


threading.Thread(target=task_health_check_loop, daemon=True).start()


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
