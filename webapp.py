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
    conn.executescript(SCHEMA_SQL)
    # 轻量迁移：补缺失的列
    existing = {r[1] for r in conn.execute("PRAGMA table_info(sessions)").fetchall()}
    if "analysis_model" not in existing:
        conn.execute("ALTER TABLE sessions ADD COLUMN analysis_model TEXT")
    if "analysis_progress" not in existing:
        conn.execute("ALTER TABLE sessions ADD COLUMN analysis_progress TEXT")
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
        conn = sqlite3.connect(DB_PATH)
        try:
            cur = conn.execute(sql, params)
            conn.commit()
            return cur.lastrowid
        finally:
            conn.close()


def db_fetchone(sql, params=()):
    with _db_lock:
        conn = sqlite3.connect(DB_PATH)
        conn.row_factory = sqlite3.Row
        try:
            return conn.execute(sql, params).fetchone()
        finally:
            conn.close()


def db_fetchall(sql, params=()):
    with _db_lock:
        conn = sqlite3.connect(DB_PATH)
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


# ============ OSS ============
AUDIO_EXTS = {".mp3", ".wav", ".m4a", ".flac", ".aac", ".ogg", ".opus"}


def oss_signed_url(oss_key, expires=7200):
    return oss_bucket.sign_url("GET", oss_key, expires, slash_safe=True)


# ============ Session 管理 ============
def get_or_create_session(advisor, customer, service_date):
    """根据 (advisor, customer, service_date) 找或创建 session。返回 session_id。
    如果三个字段任一为空，归到一个独立 session（按 oss_key 区分）。"""
    if not (advisor and customer and service_date):
        return None  # 调用方决定怎么处理（一般用 oss_key 兜底建独立 session）

    row = db_fetchone(
        "SELECT id FROM sessions WHERE advisor=? AND customer=? AND service_date=?",
        (advisor, customer, service_date),
    )
    if row:
        return row["id"]
    return db_write(
        "INSERT INTO sessions (advisor, customer, service_date) VALUES (?, ?, ?)",
        (advisor, customer, service_date),
    )


def get_or_create_orphan_session(advisor, customer, oss_key):
    """元数据不全的录音：以 oss_key 当 service_date 创建独立 session（保证唯一）"""
    fake_date = "?-" + hashlib.md5(oss_key.encode()).hexdigest()[:8]
    row = db_fetchone(
        "SELECT id FROM sessions WHERE service_date=?",
        (fake_date,),
    )
    if row:
        return row["id"]
    return db_write(
        "INSERT INTO sessions (advisor, customer, service_date) VALUES (?, ?, ?)",
        (advisor or "(未填顾问)", customer or "(未填顾客)", fake_date),
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


def trigger_pipeline_for_recording(recording_id):
    """为新录音排 ASR；ASR 完成后自动检查 session 是否需要分析"""
    threading.Thread(
        target=_asr_then_maybe_analyze, args=(recording_id,), daemon=True
    ).start()


def _asr_then_maybe_analyze(recording_id):
    rec = db_fetchone("SELECT session_id, asr_status FROM recordings WHERE id=?", (recording_id,))
    if not rec:
        return
    if rec["asr_status"] != "done":
        run_asr(recording_id)
    # 重新读一遍
    rec = db_fetchone("SELECT session_id, asr_status FROM recordings WHERE id=?", (recording_id,))
    if rec and rec["asr_status"] == "done":
        maybe_trigger_session_analysis(rec["session_id"])


def maybe_trigger_session_analysis(session_id, model=None):
    """所有录音 ASR done 且分析过期/未完成 → 触发分析。

    model: 指定分析模型 id（来自 SUPPORTED_MODELS）；为 None 则沿用上次或默认。
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
        if sess["analysis_status"] == "running":
            return  # 已在跑
        new_sig = compute_session_signature(session_id)
        chosen_model = model or sess["analysis_model"] or DEFAULT_MODEL
        if chosen_model not in MODEL_PROVIDER:
            chosen_model = DEFAULT_MODEL
        if (sess["analysis_status"] == "done"
                and sess["analysis_signature"] == new_sig
                and model is None):
            return  # 已是最新且没强制换模型
        # 立即标 running，避免重复触发
        db_write(
            """UPDATE sessions SET analysis_status='running',
               analysis_model=?,
               analysis_started_at=datetime('now','localtime'),
               analysis_error=NULL WHERE id=?""",
            (chosen_model, session_id),
        )

    threading.Thread(
        target=run_session_analysis, args=(session_id, new_sig, chosen_model), daemon=True
    ).start()


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
                    speaker_count=2,
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
                    speaker = f"说话人{spk}" if spk is not None else "说话人?"
                    start_sec = (s.get("begin_time", 0) or 0) / 1000.0
                    end_sec = (s.get("end_time", 0) or 0) / 1000.0
                    text = s.get("text", "")
                    transcript_lines.append(
                        f"[{start_sec:.2f}s - {end_sec:.2f}s] {speaker}: {text}"
                    )

        transcript = "\n".join(transcript_lines)
        db_write(
            """UPDATE recordings SET asr_status='done',
               asr_result_json=?, asr_transcript=?,
               asr_finished_at=datetime('now','localtime') WHERE id=?""",
            (json.dumps(full_json, ensure_ascii=False), transcript, recording_id),
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
    if _kb_cache is None:
        with open(KB_PATH, "r", encoding="utf-8") as f:
            _kb_cache = json.load(f)
    return _kb_cache


def build_kb_brief():
    kb = load_kb()
    lines = []
    for item in kb:
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
你是申美美容院的顾客洞察分析师。
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

调用 submit_call1 工具提交结果，不要输出其他任何文字。
"""

SYSTEM_PROMPT_CALL2 = """\
你是申美美容院的接诊质检专家。
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

SYSTEM_PROMPT_CALL3 = """\
你是申美美容院的接诊报告撰写专家。
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
        "required": ["persona", "pain_points", "external_signals", "deal_diagnosis"],
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
                "required": ["medical_aesthetics", "other_institutions",
                             "lifestyle_habits", "self_care", "external_brands"],
                "properties": {
                    cat: {
                        "type": "array",
                        "items": {
                            "type": "object",
                            "required": ["item", "insight"],
                            "properties": {
                                "item": {"type": "string"},
                                "insight": {"type": "string"},
                            },
                        },
                    }
                    for cat in ["medical_aesthetics", "other_institutions",
                                "lifestyle_habits", "self_care", "external_brands"]
                },
            },
            "deal_diagnosis": {
                "type": "object",
                "required": ["deal_result", "dimensions", "risk_alert", "risk_text"],
                "properties": {
                    "deal_result": {"type": "boolean"},
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
    "description": "提交接诊评判：质检评分、Case复盘、失分根因",
    "input_schema": {
        "type": "object",
        "required": ["scoring", "cases", "cases_summary", "root_cause"],
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
                                 "description": "深层原因或值得保留的原因"},
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
                                 "description": "一句话根因，要尖锐"},
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
    tool_result = next(
        (blk.input for blk in message.content if getattr(blk, "type", "") == "tool_use"),
        None,
    )
    if not tool_result:
        raise RuntimeError(f"Claude 未调用 {tool['name']} 工具")
    return tool_result


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


def _call_deepseek(model, system_prompt, user_prompt, tool=None, max_tokens=None):
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
        "temperature": 0.3,
    }
    # V4 系列开启深度思考（reasoning_effort + thinking），按官方示例
    if is_v4:
        payload["reasoning_effort"] = "high"
        payload["thinking"] = {"type": "enabled"}
        # V4 的 thinking token 计入 max_tokens：给思考预留 24K buffer
        # （实测复杂 schema 时思考会吃 15-22K，预留 24K 留余量）
        payload["max_tokens"] = max(out_budget + 24000, 28000)
    # trust_env=False 关键：服务器有 http_proxy=7890，DeepSeek 不能走代理
    # 深度思考耗时较长，read timeout 上调到 20 分钟
    with httpx.Client(
        trust_env=False,
        timeout=httpx.Timeout(connect=15.0, read=1200.0, write=60.0, pool=15.0),
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
    data = resp.json()
    try:
        msg = data["choices"][0]["message"]
        tool_calls = msg.get("tool_calls") or []
        if not tool_calls:
            raise RuntimeError(f"DeepSeek 未返回 tool_calls；content={msg.get('content','')[:200]}")
        args_str = tool_calls[0]["function"]["arguments"]
        result = json.loads(args_str)
        return _deep_json_unwrap(result)
    except (KeyError, IndexError, json.JSONDecodeError) as e:
        # 出错时把 finish_reason 显式带出来，方便判断是否是 length 截断
        finish = None
        try:
            finish = data["choices"][0].get("finish_reason")
        except Exception:
            pass
        raise RuntimeError(
            f"DeepSeek 响应解析失败: {e}; finish_reason={finish}; "
            f"body_head={json.dumps(data, ensure_ascii=False)[:400]}"
        )


def _call_llm(model, system_prompt, user_prompt, tool, max_tokens=None):
    """统一入口：根据 model 的 provider 调 Claude 或 DeepSeek。"""
    provider = MODEL_PROVIDER.get(model)
    if provider == "anthropic":
        return _call_anthropic(model, system_prompt, user_prompt, tool=tool,
                                max_tokens=max_tokens or 16000)
    if provider == "deepseek":
        return _call_deepseek(model, system_prompt, user_prompt, tool=tool,
                              max_tokens=max_tokens)
    raise RuntimeError(f"未知 provider for model {model}")


def _set_progress(session_id, msg):
    db_write("UPDATE sessions SET analysis_progress=? WHERE id=?", (msg, session_id))


def run_session_analysis(session_id, signature, model=None):
    """整段接诊（多录音拼接）→ 三次 LLM tool_use 拆分 → 合并存库。

    调用1（顾客理解）+ 调用2（接诊评判）并行，调用3（综合输出）串行在后。
    model: 模型 id（见 SUPPORTED_MODELS）；为 None 则用 DEFAULT_MODEL。
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

    try:
        _set_progress(session_id, "加载知识库…")
        kb_text = build_kb_brief()

        # ── 调用1 user_prompt：仅录音 ──
        user_prompt_1 = f"""顾客姓名：{customer_name}
顾问姓名：{advisor_name}
服务日期：{sess['service_date'] or '未知'}
录音段数：{len(recs)} 段

### 接诊录音
{full_transcript}

---
请完成以下 4 个任务，调用 submit_call1 提交：

【任务1】顾客真实画像
从顾客发言中提取 5 条以上"对话信号→解读"（填入 persona.signals）。
**persona.summary 字段必填**（不能省略！），写一句尖锐的综合判断：消费类型（主动/被动） + 决策驱动力（专业信任/价格/情感） + 当前状态评估。例如："被动决策型——给了三个痛点信号没有一个被顾问接住，处于'惯性回头客'状态，随时可能沉默流失。"

【任务2】可攻破痛点 + 完整作战方案
从录音中识别 2-4 个可攻破痛点，三种 badge 尽量都覆盖：最强突破口 / 最佳情感连接点 / 最高价值突破口。
每个痛点的四步话术（label 严格枚举）：
  第一步 建立专业诊断感：说出顾客不知道的专业判断
  第二步 放大连锁影响：这个问题不解决会引发什么
  第三步 说出为什么之前没解决：区分我们和之前的方法
  第四步 给系统方案 + 预期：几次、多久、什么效果
每步 body 必须含"{customer_name}"，30-60 字。

【任务3】外部信号提取
分 5 类：medical_aesthetics / other_institutions / lifestyle_habits / self_care / external_brands
每条写 item + insight，没有的类别输出空数组 []。

【任务4】成交诊断 + 风险预警
判断 5 维度（status 取 ok/partial/missing，note 引用原话）：
customer_moved / customer_agreed / effect_satisfied / price_matched / urgency_built
规则：deal_result=false 且前三项都不是 ok → risk_alert=true，risk_text 写明风险点。
"""

        # ── 调用2 user_prompt：录音 + 知识库 ──
        user_prompt_2 = f"""顾客姓名：{customer_name}
顾问姓名：{advisor_name}
服务日期：{sess['service_date'] or '未知'}

### 接诊录音
{full_transcript}

### 判断知识库（参考用，输出不带 L 编号；共 {len(load_kb())} 条）
{kb_text}

---
请完成以下 3 个任务，调用 submit_call2 提交：

【任务1】质检评分 · 三大接诊阶段
综合评分 0-10，必须有区分度（差 2-3 分，一般 4-5 分，好 7-8 分，很好 9 分）。
按以下固定子项评分，detail 只写一句本次事实（不写定义不写建议，20-40 字）：

壹 一咨找需求：
  1.1 档案掌握与破冰
  1.2 快速找到痛点
  1.3 做检测
  1.4 解决原理和方向（不提项目）
  1.5 解决方案

贰 确认加大意愿：
  2.1 重述痛点原理
  2.2 客人做对比效果感受（图片/动作/拉筋/拍照）
  2.3 提供情绪价值

叁 成交阶段：
  3.1 效果确认
  3.2 顾客当下结论评估
  3.3 本店解决方向和方案
  3.4 报价（提到价格即算触发）
  3.5 异议处理
  3.6 好评 + 返邀约

【任务2】关键 Case 复盘
5-8 条，按时间顺序，good / miss / bad 都有。每条 kind / title / quote（「」15 字内）/ segment / timestamp_seconds / timestamp_label / surface / deep / improve。
improve 必须含"{customer_name}"的具体话术。

【任务3】接诊失分根因
headline（一句话尖锐根因）+ product_dimension（顾问实际说的 2-3 条）+ problem_dimension（顾客需要听到的，一一对应）+ gap_note（差距本质）。

时间戳：录音转录每行 `[Xs - Ys] 说话人N: ...`。第 2 段的 [134s-142s] → segment=2, timestamp_seconds=134, timestamp_label="0:02:14"。
"""

        prompt12_kchars = (len(user_prompt_1) + len(user_prompt_2)
                           + len(SYSTEM_PROMPT_CALL1) + len(SYSTEM_PROMPT_CALL2)) // 1000
        _set_progress(
            session_id,
            f"调用 1+2 并行（输入 ~{prompt12_kchars}K 字符），{model} 深度思考中…",
        )

        def _run_call1():
            return _call_llm(model, SYSTEM_PROMPT_CALL1, user_prompt_1,
                             tool=TOOL_CALL1, max_tokens=6000)

        def _run_call2():
            return _call_llm(model, SYSTEM_PROMPT_CALL2, user_prompt_2,
                             tool=TOOL_CALL2, max_tokens=8000)

        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as ex:
            f1 = ex.submit(_run_call1)
            f2 = ex.submit(_run_call2)
            call1_result = f1.result()
            call2_result = f2.result()

        # ── 调用3：串行，仅用前两次结果摘要，不重传录音 ──
        stage_label["v"] = "调用 3"
        _set_progress(session_id, "汇总前两次结果，准备生成总览与下一步…")
        c1_summary, c2_summary = build_call_summaries(call1_result, call2_result)

        user_prompt_3 = f"""顾客姓名：{customer_name}
顾问姓名：{advisor_name}
服务日期：{sess['service_date'] or '未知'}

### 调用1 的分析结果（顾客理解）
{c1_summary}

### 调用2 的分析结果（接诊评判）
{c2_summary}

---
请完成以下 3 个任务，调用 submit_call3 提交：

【任务1】PART1 全维度评估总览
- customer_value：顾客价值评级（tag 简短 + tag_kind + note 一句话引用关键信号）
- pain_summary：痛点识别（tag 如"3 个核心可攻破点" + items 列每个痛点）
- sales_diagnosis：销售问题诊断（tag 标签化根因 + note 复用 headline）
- quality_score：质检评分（score 复用 overall + note 一句话）
- suggestions：2-4 条可操作建议，指向报告具体内容

【任务2】能力训练路径
五个阶段固定：破冰 / 需求挖掘 / 产品推荐 / 异议处理 / 项目结束后
每阶段：stage + issue（本次问题 20 字内）+ skill（需要训练的能力）
另给：bad_chain（顾问实际思维链，用 → 连接）+ good_chain（正确思维链）+ missing_step（缺失关键一步）

【任务3】下一步动作 · 回店规划
- return_scripts：至少 2 条回店话术，含"{customer_name}"，以"上次你提到..."开头，50-80 字
- priority_projects：按成交难度从低到高 2-3 条
- pain_entry_scripts：针对每个痛点的四步话术（entry 含{customer_name} + principle 30 字 + direction 30 字 + sales_link 20 字）
- medical_objections：至少 3 条泛医疗异议应答，60-100 字，严格遵循 ①承认医院 → ②分工边界 → ③我们位置 → ④互补不冲突
"""

        call3_result = _call_llm(model, SYSTEM_PROMPT_CALL3, user_prompt_3,
                                  tool=TOOL_CALL3, max_tokens=8000)

        stop_heartbeat.set()
        _set_progress(session_id, "解析返回的结构化报告…")

        # ── 合并三次结果 ──
        full_report = {
            # 调用 3
            "overview":         call3_result.get("overview", {}),
            "logic_chain":      call3_result.get("logic_chain", {}),
            "next_steps":       call3_result.get("next_steps", {}),
            # 调用 1
            "persona":          call1_result.get("persona", {}),
            "pain_points":      call1_result.get("pain_points", []),
            "external_signals": call1_result.get("external_signals", {}),
            "deal_diagnosis":   call1_result.get("deal_diagnosis", {}),
            # 调用 2
            "scoring":          call2_result.get("scoring", {}),
            "cases":            call2_result.get("cases", []),
            "cases_summary":    call2_result.get("cases_summary", ""),
            "root_cause":       call2_result.get("root_cause", {}),
        }

        report_json = json.dumps(full_report, ensure_ascii=False)
        scoring = full_report["scoring"] or {}
        scores_summary = {
            "overall": scoring.get("overall"),
            "stages": [
                {"name": st.get("name"), "score": st.get("score")}
                for st in (scoring.get("stages") or [])
            ],
            "good_highlights": scoring.get("good_highlights", []),
            "bad_highlights":  scoring.get("bad_highlights", []),
        }

        elapsed = int(_t.time() - t0)
        mm, ss = divmod(elapsed, 60)
        db_write(
            """UPDATE sessions SET analysis_status='done',
               analysis_result=?, analysis_scores=?, analysis_signature=?,
               analysis_model=?, analysis_progress=?,
               analysis_finished_at=datetime('now','localtime') WHERE id=?""",
            (report_json, json.dumps(scores_summary, ensure_ascii=False), signature,
             model, f"完成（耗时 {mm}:{ss:02d}）", session_id),
        )
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
                     service_date=None, duration_label=None):
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
    if advisor and customer and service_date:
        session_id = get_or_create_session(advisor, customer, service_date)
    else:
        session_id = get_or_create_orphan_session(advisor, customer, oss_key)

    if size_bytes is None:
        try:
            size_bytes = oss_bucket.head_object(oss_key).content_length
        except Exception:
            size_bytes = 0

    rid = db_write(
        """INSERT INTO recordings
           (session_id, oss_key, advisor, customer, recorded_at,
            duration_label, size_bytes, source)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
        (session_id, oss_key, advisor, customer, recorded_at,
         duration_label, size_bytes, source),
    )

    # 新增录音会让 session 之前的分析结果过期；标 pending 等流水线
    db_write(
        """UPDATE sessions SET analysis_status='pending',
           analysis_error=NULL WHERE id=?
           AND analysis_status='done' AND analysis_signature != ?""",
        (session_id, compute_session_signature(session_id)),
    )

    # 启动流水线
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
        if u == BOSS_USERNAME and p == BOSS_PASSWORD:
            session["logged_in"] = True
            session["username"] = u
            session.permanent = True
            return redirect(_safe_next(request.args.get("next")) or url_for("index"))
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
    return render_template("index.html", username=session.get("username"))


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
               asr_started_at, asr_finished_at
        FROM recordings WHERE session_id=?
        ORDER BY COALESCE(recorded_at, ''), id
    """, (sid,))
    recordings = []
    for r in recs:
        d = dict(r)
        try:
            d["audio_url"] = oss_signed_url(r["oss_key"], expires=7200)
        except Exception:
            d["audio_url"] = ""
        recordings.append(d)
    evals = [dict(e) for e in db_fetchall(
        "SELECT * FROM evaluations WHERE session_id=? ORDER BY id DESC", (sid,)
    )]
    try:
        report = json.loads(sess_d.get("analysis_result") or "null")
    except (json.JSONDecodeError, TypeError):
        report = None
    sess_d["display_status"] = _display_status(
        sess_d.get("analysis_status"), sess_d.get("analysis_progress"),
        bool(sess_d.get("analysis_result")),
    )
    return render_template(
        "report.html",
        sess=sess_d,
        recordings=recordings,
        report=report,
        evaluations=evals,
        username=session.get("username"),
    )


# ============ API ============
def _display_status(status, progress, has_result):
    """把 (analysis_status, analysis_progress, 是否有结果) 映射成用户友好的四态。"""
    if status == "done":
        return "done"
    if status == "failed":
        return "failed"
    if status == "running":
        return "running"
    # pending
    if not progress and not has_result:
        return "idle"
    return "queued"


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
    if advisor:
        where.append("s.advisor LIKE ?")
        params.append(f"%{advisor}%")
    if customer:
        where.append("s.customer LIKE ?")
        params.append(f"%{customer}%")
    if date:
        where.append("s.service_date = ?")
        params.append(date)
    where_sql = ("WHERE " + " AND ".join(where)) if where else ""

    total_row = db_fetchone(
        f"SELECT COUNT(*) AS c FROM sessions s {where_sql}", tuple(params)
    )
    total = total_row["c"] if total_row else 0

    offset = (page - 1) * page_size
    rows = db_fetchall(f"""
        SELECT s.id, s.advisor, s.customer, s.service_date,
               s.analysis_status, s.analysis_scores, s.analysis_progress,
               s.analysis_result, s.has_evaluation, s.created_at,
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
        # 列表不需要把完整的 analysis_result 回传
        d.pop("analysis_result", None)
        out_rows.append(d)
    return jsonify({
        "sessions": out_rows,
        "total": total,
        "page": page,
        "page_size": page_size,
    })


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
               asr_started_at, asr_finished_at
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
    maybe_trigger_session_analysis(sid, model=model)
    # 检查是否真正进入 running（ASR 未完成则仍是 pending）
    cur = db_fetchone("SELECT analysis_status, analysis_progress FROM sessions WHERE id=?", (sid,))
    return jsonify({
        "status": cur["analysis_status"] if cur else "pending",
        "progress": cur["analysis_progress"] if cur else None,
        "model": model or DEFAULT_MODEL,
    })


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


@app.route("/healthz")
def healthz():
    return jsonify({"ok": True, "ts": datetime.now().isoformat()})


# ============ 启动 ============
init_db()


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

    # 仅"中途被打断"的 session 才自动补跑：
    # - failed 状态需要重试
    # - running 状态说明被 restart 打断了
    # - pending 且 analysis_signature 不为 NULL（说明之前完成过、因新增录音重新排队）
    # 从未分析过的 session（signature IS NULL AND result IS NULL）不自动跑，等用户手动触发
    sids = db_fetchall("""
        SELECT id FROM sessions
        WHERE analysis_status IN ('running', 'failed')
           OR (analysis_status='pending'
               AND (analysis_signature IS NOT NULL OR analysis_result IS NOT NULL))
    """)
    for s in sids:
        maybe_trigger_session_analysis(s["id"])


# gunicorn 启动时也触发
threading.Thread(target=startup_kick, daemon=True).start()


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5058, debug=True)
