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

DEFAULT_MODEL = os.environ.get("ANALYSIS_MODEL", "deepseek-chat")
ANTHROPIC_PROXY = os.environ.get("ANTHROPIC_PROXY", "http://127.0.0.1:7890")

# 可选分析模型表（前端下拉用）
# provider: anthropic 走代理；deepseek 直连国内不走代理
SUPPORTED_MODELS = [
    {"id": "deepseek-chat",     "provider": "deepseek",
     "label": "DeepSeek V3（国内直连，快/便宜，默认）"},
    {"id": "claude-sonnet-4-6", "provider": "anthropic",
     "label": "Claude Sonnet 4.6（更强但贵且慢）"},
]
MODEL_PROVIDER = {m["id"]: m["provider"] for m in SUPPORTED_MODELS}

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
    session_id INTEGER NOT NULL UNIQUE,
    wrong_points TEXT,
    improvement TEXT,
    correct_practice TEXT,
    created_at TEXT DEFAULT (datetime('now', 'localtime')),
    updated_at TEXT,
    FOREIGN KEY (session_id) REFERENCES sessions(id)
);
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


# ============ 评分维度定义 ============
SCORE_DIMENSIONS = {
    "pre_room": {
        "label": "进房前",
        "dims": ["档案掌握与破冰", "情感连接与共情", "今日方案铺垫"],
    },
    "in_room": {
        "label": "房中",
        "dims": ["需求洞察与挖掘", "专业讲解与检测解读", "节奏与情绪维护"],
    },
    "post_room": {
        "label": "房后",
        "dims": ["效果确认与价值放大", "成交引导", "异议处理", "客情收尾与售后交代"],
    },
}

_dim_schema = {
    dim: {
        "type": "object",
        "required": ["score", "evaluation", "improvement"],
        "properties": {
            "score":       {"type": "integer", "minimum": 1, "maximum": 10,
                            "description": "1-10 整数，区分度要明显"},
            "evaluation":  {"type": "string",
                            "description": "本次评价：结合录音内容说明为何给此分（2-3 句）"},
            "improvement": {"type": "string",
                            "description": "改进建议：针对此维度可立即执行的具体做法（1-2 句）"},
        },
    }
    for s in SCORE_DIMENSIONS.values() for dim in s["dims"]
}

SCORE_TOOL = {
    "name": "submit_analysis",
    "description": "提交接诊复盘报告（含评分和 Markdown 分析正文）",
    "input_schema": {
        "type": "object",
        "required": ["scores", "highlights", "weaknesses", "report_markdown"],
        "properties": {
            "scores": {
                "type": "object",
                "description": "10 个子维度的评分对象",
                "required": list(_dim_schema.keys()),
                "properties": _dim_schema,
            },
            "highlights": {"type": "string", "description": "亮点：1-2句，只说哪个维度得高分及原因（如：情感连接8分，关怀自然到位），不写情景叙述"},
            "weaknesses": {"type": "string", "description": "短板：1-2句，只说哪个维度得低分及原因（如：效果确认5分，出房未做复盘），不写情景叙述"},
            "report_markdown": {
                "type": "string",
                "description": "复盘报告正文（Markdown），从「做得好的点」开始，不要重复写总体评价"
            },
        },
    },
}


def compute_stage_scores(raw_dims: dict) -> dict:
    """从 10 个子维度（现在每个是 {score, evaluation, improvement}）计算阶段分和综合分"""
    # 兼容旧格式（纯整数）和新格式（对象）
    def get_score(v):
        return v["score"] if isinstance(v, dict) else int(v)

    stages = {}
    for stage_key, stage_info in SCORE_DIMENSIONS.items():
        vals = [get_score(raw_dims.get(d, {"score": 5})) for d in stage_info["dims"]]
        stages[stage_key] = round(sum(vals) / len(vals), 1)
    overall = round(sum(stages.values()) / len(stages), 1)
    return {
        "overall": overall,
        "stages": stages,
        "dimensions": raw_dims,  # 保留完整对象（含 evaluation/improvement）
    }


def _call_anthropic(model, system_prompt, user_prompt):
    """调用 Claude 走代理，返回 tool_use 的 input dict"""
    from anthropic import Anthropic
    import httpx

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
        max_tokens=8000,
        system=system_prompt,
        tools=[SCORE_TOOL],
        tool_choice={"type": "any"},
        messages=[{"role": "user", "content": user_prompt}],
    )
    tool_result = next(
        (blk.input for blk in message.content if getattr(blk, "type", "") == "tool_use"),
        None,
    )
    if not tool_result:
        raise RuntimeError("Claude 未调用 submit_analysis 工具")
    return tool_result


def _call_deepseek(model, system_prompt, user_prompt):
    """调用 DeepSeek（OpenAI 兼容），不走代理，返回 tool_calls[0].function.arguments dict"""
    import httpx

    if not DEEPSEEK_API_KEY:
        raise RuntimeError("未配置 DEEPSEEK_API_KEY")

    # SCORE_TOOL 是 Anthropic 格式（input_schema），转 OpenAI 格式（function.parameters）
    openai_tool = {
        "type": "function",
        "function": {
            "name": SCORE_TOOL["name"],
            "description": SCORE_TOOL["description"],
            "parameters": SCORE_TOOL["input_schema"],
        },
    }

    payload = {
        "model": model,
        "messages": [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_prompt},
        ],
        "tools": [openai_tool],
        "tool_choice": {"type": "function",
                        "function": {"name": SCORE_TOOL["name"]}},
        "max_tokens": 8000,
        "temperature": 0.3,
    }
    # trust_env=False 关键：服务器有 http_proxy=7890，DeepSeek 不能走代理
    with httpx.Client(
        trust_env=False,
        timeout=httpx.Timeout(connect=15.0, read=600.0, write=60.0, pool=15.0),
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
        return json.loads(args_str)
    except (KeyError, IndexError, json.JSONDecodeError) as e:
        raise RuntimeError(f"DeepSeek 响应解析失败: {e}; body={json.dumps(data)[:500]}")


def run_session_analysis(session_id, signature, model=None):
    """整段接诊（多录音拼接）→ LLM tool_use → 评分 + 复盘报告。

    model: 模型 id（见 SUPPORTED_MODELS）；为 None 则用 DEFAULT_MODEL。
    """
    model = model or DEFAULT_MODEL
    provider = MODEL_PROVIDER.get(model)
    if provider is None:
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

    blocks = []
    for i, r in enumerate(recs, 1):
        header = f"### 录音段 {i}/{len(recs)}"
        if r["recorded_at"]:
            header += f"  录音时间 {r['recorded_at']}"
        if r["duration_label"]:
            header += f"  时长 {r['duration_label']}"
        blocks.append(f"{header}\n{r['asr_transcript']}")
    full_transcript = "\n\n".join(blocks)

    dim_list = "\n".join(
        f"  【{s['label']}】 " + "、".join(s["dims"])
        for s in SCORE_DIMENSIONS.values()
    )

    try:
        kb_text = build_kb_brief()

        system_prompt = (
            "你是身美医美的资深接诊分析专家，使用刁姐（老板）蒸馏出的逻辑库对一次完整接诊进行复盘点评与评分。\n"
            "你必须严格基于「知识库」中的逻辑（L0001…）来判断表现，评分 1-10 整数，评分要区分度明显、不要全给中间分。\n"
            "最终通过调用 submit_analysis 工具输出结构化结果。"
        )

        user_prompt = f"""# 接诊元数据
- 顾问：{sess['advisor'] or '未知'}
- 顾客：{sess['customer'] or '未知'}
- 服务日期：{sess['service_date'] or '未知'}
- 录音段数：{len(recs)} 段

# 完整接诊转录（{len(recs)} 段拼接，含说话人分离）
{full_transcript}

# 知识库（刁姐蒸馏，共 {len(load_kb())} 条逻辑）
{kb_text}

# 任务
完成复盘后，调用 submit_analysis 工具提交结果，包含：

1. **评分**（10 个子维度，1-10 整数，严格区分优劣）：
{dim_list}

2. **亮点**：1-2 句，点出高分维度（如"情感连接7分…"）

3. **短板**：1-2 句，点出低分维度（如"异议处理2分…需…"）

4. **report_markdown**（复盘报告正文，Markdown）：

## 一、总体评价（3-5 句）
叙述本次接诊的情景背景、成交情况、整体判断（**不要提具体维度分数**，分数在评分卡里，这里只说叙事）。

## 二、做得好的点（3-6 条）
- **[L????] 简称**：顾问在【录音段X的 [时间段]】做了什么 → 命中哪条逻辑

## 二、做错 / 遗漏的点（核心，3-8 条）
- **[L????] 简称**：【录音段X的 [时间段]】做了/没做什么 → 违反/遗漏哪条逻辑
- **影响**：对成交/信任/复诊的影响
- **正确做法**：1-2 句可立即上手的指导

## 三、阶段流程检查（从录音内容自行判断三阶段）
进房前 / 房中 / 房后，各阶段关键执行点是否到位

## 四、给顾问的 3 条最关键改进建议

注意：知识库 ID 必须真实引用；时间段引用 [Xs - Ys] 并注明第几段；每条建议要落地可执行。
"""

        if provider == "anthropic":
            tool_result = _call_anthropic(model, system_prompt, user_prompt)
        elif provider == "deepseek":
            tool_result = _call_deepseek(model, system_prompt, user_prompt)
        else:
            raise RuntimeError(f"未知 provider: {provider}")

        raw_dims = tool_result.get("scores", {})
        scores = compute_stage_scores(raw_dims)
        scores["highlights"] = tool_result.get("highlights", "")
        scores["weaknesses"] = tool_result.get("weaknesses", "")
        report_text = tool_result.get("report_markdown", "")

        db_write(
            """UPDATE sessions SET analysis_status='done',
               analysis_result=?, analysis_scores=?, analysis_signature=?,
               analysis_model=?,
               analysis_finished_at=datetime('now','localtime') WHERE id=?""",
            (report_text, json.dumps(scores, ensure_ascii=False), signature,
             model, session_id),
        )
    except Exception as e:
        db_write(
            """UPDATE sessions SET analysis_status='failed', analysis_error=?,
               analysis_finished_at=datetime('now','localtime') WHERE id=?""",
            (f"[{model}] {str(e)[:1900]}", session_id),
        )


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
    """剥离 [L0001]、[L0001/L0008] 以及裸露的 L0001 引用"""
    s = re.sub(r'\[L\d+(?:/L\d+)*\]', '', s)
    # ——L0108/L0274 是本次最大短板
    s = re.sub(r'——L\d+(?:/L\d+)*[^。\n]*', '', s)
    # （含 L code 的括号注释）如：（违反L0002精神）
    s = re.sub(r'（[^）]*L\d+[^）]*）', '', s)
    s = re.sub(r'，?违反L\d+[^，。]*', '', s)
    s = re.sub(r'，?命中L\d+[^，。]*', '', s)
    return s.strip()


def _clean(s):
    s = _strip_l_codes(s)
    s = re.sub(r'[ \t]+', ' ', s).strip()
    return s


def _extract_timestamps(s):
    """提取 '段N [XXXs-YYYs]' → [{segment, startSec, endSec}]"""
    pat = re.compile(r'段\s*(\d+)\s*\[(\d+(?:\.\d+)?)s\s*[-–]\s*(\d+(?:\.\d+)?)s\]')
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
            # body 可能直接以 "### 1." 开头，用 re.MULTILINE 处理
            for chunk in re.split(r'(?:^|\n)###\s*', body, flags=re.MULTILINE):
                if not chunk.strip():
                    continue
                first_nl = chunk.find('\n')
                if first_nl == -1:
                    continue
                raw_title = re.sub(r'^\d+\.\s*', '', chunk[:first_nl]).strip()
                chunk_body = chunk[first_nl + 1:]

                def _extract_field(pattern, text):
                    m = re.search(pattern, text, re.DOTALL)
                    return _clean(m.group(1)) if m else ''

                result['weaknesses'].append({
                    'title': _clean(raw_title),
                    'problem': _extract_field(r'\*\*位置\*\*[：:](.*?)(?=\n\s*-\s*\*\*|$)', chunk_body),
                    'risk': _extract_field(r'\*\*影响\*\*[：:](.*?)(?=\n\s*-\s*\*\*|$)', chunk_body),
                    'correctAction': _extract_field(r'\*\*正确做法\*\*[：:](.*?)(?=\n\s*-\s*\*\*|\n###|$)', chunk_body),
                    'timestamps': _extract_timestamps(chunk_body),
                })

        # ── 阶段流程检查 ──
        elif '阶段流程' in title or '阶段' in title:
            key_map = {'进房前': 'before', '房中': 'during', '房后': 'after'}
            for stage_chunk in re.split(r'\n###\s*', body):
                if not stage_chunk.strip():
                    continue
                first_nl = stage_chunk.find('\n')
                stage_title = stage_chunk[:first_nl] if first_nl != -1 else stage_chunk
                stage_body = stage_chunk[first_nl + 1:] if first_nl != -1 else ''
                key = next((v for k, v in key_map.items() if k in stage_title), None)
                if not key:
                    continue
                items = []
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
    return render_template("detail.html", sess=dict(sess), username=session.get("username"))


# ============ API ============
@app.route("/api/sessions")
@login_required
def api_sessions():
    rows = db_fetchall("""
        SELECT s.id, s.advisor, s.customer, s.service_date,
               s.analysis_status, s.analysis_scores, s.has_evaluation, s.created_at,
               COUNT(r.id) AS recording_count,
               SUM(CASE WHEN r.asr_status='done' THEN 1 ELSE 0 END) AS asr_done_count,
               SUM(CASE WHEN r.asr_status='running' THEN 1 ELSE 0 END) AS asr_running_count,
               SUM(CASE WHEN r.asr_status='failed' THEN 1 ELSE 0 END) AS asr_failed_count
        FROM sessions s
        LEFT JOIN recordings r ON r.session_id = s.id
        GROUP BY s.id
        ORDER BY s.service_date DESC, s.id DESC
    """)
    return jsonify({"sessions": [dict(r) for r in rows]})


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
    ev = db_fetchone("SELECT * FROM evaluations WHERE session_id=?", (sid,))
    out["evaluation"] = dict(ev) if ev else None
    # 解析结构化分析（纯字符串处理，不调 LLM）
    out["parsed_analysis"] = parse_analysis(out.get("analysis_result") or "")
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
           analysis_signature=NULL, analysis_error=NULL WHERE id=?""",
        (sid,),
    )
    maybe_trigger_session_analysis(sid, model=model)
    return jsonify({"status": "started", "model": model or DEFAULT_MODEL})


@app.route("/api/session/<int:sid>/evaluate", methods=["POST"])
@login_required
def api_session_evaluate(sid):
    data = request.get_json(silent=True) or {}
    wrong = (data.get("wrong_points") or "").strip()
    improve = (data.get("improvement") or "").strip()
    correct = (data.get("correct_practice") or "").strip()
    if not (wrong or improve or correct):
        return jsonify({"error": "请至少填写一项"}), 400

    sess = db_fetchone("SELECT id FROM sessions WHERE id=?", (sid,))
    if not sess:
        return jsonify({"error": "not found"}), 404

    with _db_lock:
        conn = sqlite3.connect(DB_PATH)
        try:
            existing = conn.execute(
                "SELECT id FROM evaluations WHERE session_id=?", (sid,)
            ).fetchone()
            if existing:
                conn.execute(
                    """UPDATE evaluations SET
                       wrong_points=?, improvement=?, correct_practice=?,
                       updated_at=datetime('now','localtime')
                       WHERE session_id=?""",
                    (wrong, improve, correct, sid),
                )
            else:
                conn.execute(
                    """INSERT INTO evaluations
                       (session_id, wrong_points, improvement, correct_practice)
                       VALUES (?, ?, ?, ?)""",
                    (sid, wrong, improve, correct),
                )
            conn.execute("UPDATE sessions SET has_evaluation=1 WHERE id=?", (sid,))
            conn.commit()
        finally:
            conn.close()

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

    # 补跑未完成的 session 分析（ASR 已都 done 的）
    sids = db_fetchall("SELECT id FROM sessions WHERE analysis_status != 'done'")
    for s in sids:
        maybe_trigger_session_analysis(s["id"])


# gunicorn 启动时也触发
threading.Thread(target=startup_kick, daemon=True).start()


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5058, debug=True)
