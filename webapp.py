"""工牌接诊分析系统 - Web 主应用

功能：
- 老板登录 → 录音列表（DB + OSS 扫描）
- 上传录音到 OSS / 选择已有 OSS 录音
- 触发 ASR（DashScope fun-asr + 说话人分离）
- 触发 Claude 知识库分析（基于 logic_library.json）
- 提交人工点评（错的点 / 改进建议 / 正确做法）

数据：SQLite (recordings.db)
存储：OSS (gongpai-asr)
"""
import json
import os
import re
import sqlite3
import threading
import time
import uuid
from datetime import datetime
from functools import wraps
from http import HTTPStatus
from pathlib import Path
from urllib import request as urllib_request
from urllib.parse import quote

import oss2
from flask import (Flask, abort, flash, g, jsonify, redirect, render_template,
                   request, send_from_directory, session, url_for)

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

DB_PATH = os.environ.get("DB_PATH", str(Path(__file__).parent / "recordings.db"))
KB_PATH = os.environ.get("KNOWLEDGE_BASE_PATH",
                         str(Path(__file__).parent / "output" / "logic_library.json"))

CLAUDE_MODEL = os.environ.get("CLAUDE_MODEL", "claude-opus-4-7")
# Claude API 走美国节点，需要代理；OSS/DashScope 是国内直连，不能走代理
ANTHROPIC_PROXY = os.environ.get("ANTHROPIC_PROXY", "http://127.0.0.1:7890")

# OSS 客户端
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
app.config["MAX_CONTENT_LENGTH"] = 300 * 1024 * 1024  # 300MB


class PrefixMiddleware:
    """让 Flask 在被反向代理到子路径（如 /gp/）下时，
    url_for() 和 静态资源 URL 都自动带上前缀。
    需要 nginx 配 proxy_set_header X-Forwarded-Prefix /gp;
    """

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
CREATE TABLE IF NOT EXISTS recordings (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
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

    analysis_status TEXT DEFAULT 'pending',
    analysis_result TEXT,
    analysis_error TEXT,
    analysis_started_at TEXT,
    analysis_finished_at TEXT,

    has_evaluation INTEGER DEFAULT 0
);

CREATE TABLE IF NOT EXISTS evaluations (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    recording_id INTEGER NOT NULL UNIQUE,
    wrong_points TEXT,
    improvement TEXT,
    correct_practice TEXT,
    created_at TEXT DEFAULT (datetime('now', 'localtime')),
    updated_at TEXT,
    FOREIGN KEY (recording_id) REFERENCES recordings(id)
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
    conn.commit()
    conn.close()


def db_write(sql, params=()):
    """线程安全的写操作（后台线程用）"""
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


# ============ 文件名解析 ============
# 形如 "代素英-伏琳-录音20260422125856-11分钟17秒.mp3"
FILENAME_RE = re.compile(
    r"^(?P<advisor>[^-/]+)-(?P<customer>[^-/]+)-录音(?P<ts>\d{14})-(?P<dur>[^.]+)\.[A-Za-z0-9]+$"
)


def parse_filename(filename):
    """从文件名解析顾问/顾客/时间/时长。返回 dict 或 None。"""
    name = filename.split("/")[-1]
    m = FILENAME_RE.match(name)
    if not m:
        return None
    ts = m.group("ts")
    try:
        dt = datetime.strptime(ts, "%Y%m%d%H%M%S")
        recorded_at = dt.strftime("%Y-%m-%d %H:%M:%S")
    except ValueError:
        recorded_at = None
    return {
        "advisor": m.group("advisor"),
        "customer": m.group("customer"),
        "recorded_at": recorded_at,
        "duration_label": m.group("dur"),
    }


# ============ OSS 工具 ============
AUDIO_EXTS = {".mp3", ".wav", ".m4a", ".flac", ".aac", ".ogg", ".opus"}


def oss_signed_url(oss_key, expires=3600):
    """生成可在公网访问的 OSS 签名 URL（用于浏览器播放 + DashScope 拉取）"""
    return oss_bucket.sign_url("GET", oss_key, expires, slash_safe=True)


def scan_oss_bucket():
    """扫描 OSS bucket，把新文件写入 recordings。返回新增条数。"""
    added = 0
    for obj in oss2.ObjectIterator(oss_bucket):
        key = obj.key
        if not any(key.lower().endswith(ext) for ext in AUDIO_EXTS):
            continue
        existing = db_fetchone("SELECT id FROM recordings WHERE oss_key = ?", (key,))
        if existing:
            continue
        meta = parse_filename(key) or {}
        db_write(
            """INSERT INTO recordings
               (oss_key, advisor, customer, recorded_at, duration_label,
                size_bytes, source)
               VALUES (?, ?, ?, ?, ?, ?, ?)""",
            (key, meta.get("advisor"), meta.get("customer"),
             meta.get("recorded_at"), meta.get("duration_label"),
             obj.size, "oss-scan"),
        )
        added += 1
    return added


# ============ ASR（DashScope fun-asr）============
def run_asr(recording_id):
    """后台任务：调用 fun-asr，写回 DB"""
    rec = db_fetchone("SELECT oss_key FROM recordings WHERE id = ?", (recording_id,))
    if not rec:
        return
    oss_key = rec["oss_key"]
    db_write(
        "UPDATE recordings SET asr_status='running', asr_started_at=datetime('now','localtime'), asr_error=NULL WHERE id=?",
        (recording_id,),
    )
    try:
        audio_url = oss_signed_url(oss_key, expires=7200)
        task_response = Transcription.async_call(
            model="fun-asr",
            file_urls=[audio_url],
            diarization_enabled=True,
            speaker_count=2,
            language_hints=["zh", "en"],
        )
        task_id = task_response.output.task_id
        transcription_response = Transcription.wait(task=task_id)
        if transcription_response.status_code != HTTPStatus.OK:
            raise RuntimeError(f"DashScope 请求失败: {transcription_response.output.message}")

        full_json = []
        transcript_lines = []
        for transcription in transcription_response.output["results"]:
            if transcription["subtask_status"] != "SUCCEEDED":
                raise RuntimeError(f"识别失败: {transcription}")
            result_url = transcription["transcription_url"]
            detailed = json.loads(urllib_request.urlopen(result_url).read().decode("utf8"))
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
            """UPDATE recordings SET asr_status='done', asr_result_json=?, asr_transcript=?,
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
    """构造发给 Claude 的知识库摘要文本"""
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


def run_claude_analysis(recording_id):
    """后台任务：调用 Claude，基于知识库分析转录文本"""
    from anthropic import Anthropic

    rec = db_fetchone(
        "SELECT asr_transcript, advisor, customer, recorded_at FROM recordings WHERE id=?",
        (recording_id,),
    )
    if not rec or not rec["asr_transcript"]:
        db_write(
            "UPDATE recordings SET analysis_status='failed', analysis_error='缺少转录文本，请先完成 ASR' WHERE id=?",
            (recording_id,),
        )
        return

    db_write(
        "UPDATE recordings SET analysis_status='running', analysis_started_at=datetime('now','localtime'), analysis_error=NULL WHERE id=?",
        (recording_id,),
    )
    try:
        import httpx
        client = Anthropic(
            api_key=ANTHROPIC_API_KEY,
            http_client=httpx.Client(
                proxy=ANTHROPIC_PROXY,
                http2=False,  # 代理对 HTTP/2 TLS 握手不友好
                timeout=httpx.Timeout(connect=15.0, read=300.0, write=60.0, pool=15.0),
            ) if ANTHROPIC_PROXY else None,
        )
        kb_text = build_kb_brief()

        system_prompt = (
            "你是身美医美的资深接诊分析专家，使用刁姐（老板）蒸馏出的逻辑库对一次顾问-顾客的接诊录音进行复盘点评。\n"
            "你必须严格基于「知识库」中的逻辑（L0001…）来判断哪些做得好、哪些做错、哪些遗漏。\n"
            "输出必须中文、结构化、可执行；引用知识库时使用 [LXXXX] 标签。"
        )

        user_prompt = f"""# 接诊录音元数据
- 顾问：{rec['advisor'] or '未知'}
- 顾客：{rec['customer'] or '未知'}
- 录音时间：{rec['recorded_at'] or '未知'}

# 转录对话（含说话人分离）
{rec['asr_transcript']}

# 知识库（刁姐蒸馏，共 {len(load_kb())} 条逻辑）
{kb_text}

# 任务
按以下结构输出 Markdown 复盘报告：

## 一、总体评价（3-5 句）
对本次接诊的整体把控、节奏、温度、专业度做一句话定性。

## 二、做得好的点
按知识库逻辑列出 3-6 条，每条格式：
- **[L????] 简称**：顾问在 [时间段] 做了什么 → 命中了哪条逻辑。

## 三、做错 / 遗漏的点（核心）
列出 3-8 条，每条格式：
- **[L????] 简称**：[时间段] 顾问说/做了什么（或没做什么）→ 违反了哪条逻辑 / 遗漏了哪个关键动作。
- **影响**：对成交、信任、复诊的影响。
- **正确做法**：1-2 句话给出怎么做才对（要可立即上手）。

## 四、阶段流程检查
进房间前 / 中途到房间里 / 从房间出来 三个阶段，分别列出关键执行点是否到位。

## 五、给顾问的 3 条最关键改进建议
1. …
2. …
3. …

注意：
- 知识库 ID 必须真实引用，不要瞎编。
- 时间段请引用转录中的 [Xs - Ys]。
- 不要说"建议进一步分析"这种废话，每条都要落地。
"""

        message = client.messages.create(
            model=CLAUDE_MODEL,
            max_tokens=8000,
            system=system_prompt,
            messages=[{"role": "user", "content": user_prompt}],
        )
        result_text = "".join(
            blk.text for blk in message.content if getattr(blk, "type", "") == "text"
        )
        db_write(
            """UPDATE recordings SET analysis_status='done', analysis_result=?,
               analysis_finished_at=datetime('now','localtime') WHERE id=?""",
            (result_text, recording_id),
        )
    except Exception as e:
        db_write(
            """UPDATE recordings SET analysis_status='failed', analysis_error=?,
               analysis_finished_at=datetime('now','localtime') WHERE id=?""",
            (str(e)[:2000], recording_id),
        )


# ============ 认证 ============
def login_required(f):
    @wraps(f)
    def wrapped(*args, **kwargs):
        if not session.get("logged_in"):
            if request.path.startswith("/api/"):
                return jsonify({"error": "未登录", "code": "auth_required"}), 401
            return redirect(url_for("login", next=request.path))
        return f(*args, **kwargs)

    return wrapped


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
            return redirect(request.args.get("next") or url_for("index"))
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


@app.route("/recording/<int:rid>")
@login_required
def recording_detail(rid):
    rec = db_fetchone("SELECT * FROM recordings WHERE id=?", (rid,))
    if not rec:
        abort(404)
    return render_template("detail.html", rec=dict(rec), username=session.get("username"))


# ============ API：录音列表 / 详情 / 操作 ============
@app.route("/api/recordings")
@login_required
def api_list():
    db = get_db()
    rows = db.execute(
        """SELECT r.id, r.oss_key, r.advisor, r.customer, r.recorded_at,
                  r.duration_label, r.size_bytes, r.source,
                  r.asr_status, r.analysis_status, r.has_evaluation,
                  r.created_at
           FROM recordings r
           ORDER BY r.recorded_at DESC, r.id DESC"""
    ).fetchall()
    return jsonify({"recordings": [dict(r) for r in rows]})


@app.route("/api/recording/<int:rid>")
@login_required
def api_get(rid):
    db = get_db()
    rec = db.execute("SELECT * FROM recordings WHERE id=?", (rid,)).fetchone()
    if not rec:
        return jsonify({"error": "not found"}), 404
    out = dict(rec)
    # 不发完整 ASR JSON 给前端
    out.pop("asr_result_json", None)
    ev = db.execute("SELECT * FROM evaluations WHERE recording_id=?", (rid,)).fetchone()
    out["evaluation"] = dict(ev) if ev else None
    out["audio_url"] = oss_signed_url(rec["oss_key"], expires=7200)
    return jsonify(out)


@app.route("/api/scan", methods=["POST"])
@login_required
def api_scan():
    added = scan_oss_bucket()
    return jsonify({"added": added})


@app.route("/api/upload", methods=["POST"])
@login_required
def api_upload():
    f = request.files.get("file")
    if not f or not f.filename:
        return jsonify({"error": "没有文件"}), 400

    advisor = (request.form.get("advisor") or "").strip()
    customer = (request.form.get("customer") or "").strip()
    recorded_at_raw = (request.form.get("recorded_at") or "").strip()
    duration_label = (request.form.get("duration_label") or "").strip()

    # 规范化 recorded_at：接受 YYYY-MM-DDTHH:MM 或 YYYY-MM-DD HH:MM:SS
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

    # 优先尝试从用户元数据生成符合约定的 key，否则用 uuid 兜底
    orig_name = f.filename
    ext = os.path.splitext(orig_name)[1].lower() or ".mp3"
    if advisor and customer and recorded_at:
        ts = re.sub(r"\D", "", recorded_at)[:14]
        dur = duration_label or "未知时长"
        oss_key = f"{advisor}-{customer}-录音{ts}-{dur}{ext}"
    else:
        oss_key = f"upload/{datetime.now().strftime('%Y%m%d')}/{uuid.uuid4().hex}_{orig_name}"

    # 避免重名 → 加 uuid 前缀
    if oss_bucket.object_exists(oss_key):
        oss_key = f"upload/{datetime.now().strftime('%Y%m%d')}/{uuid.uuid4().hex}_{orig_name}"

    # 流式上传
    f.stream.seek(0)
    oss_bucket.put_object(oss_key, f.stream)

    # 试图从文件名补全空字段
    parsed = parse_filename(oss_key) or {}
    advisor = advisor or parsed.get("advisor")
    customer = customer or parsed.get("customer")
    recorded_at = recorded_at or parsed.get("recorded_at")
    duration_label = duration_label or parsed.get("duration_label")

    size_bytes = oss_bucket.head_object(oss_key).content_length

    rid = db_write(
        """INSERT INTO recordings
           (oss_key, advisor, customer, recorded_at, duration_label, size_bytes, source)
           VALUES (?, ?, ?, ?, ?, ?, 'upload')""",
        (oss_key, advisor, customer, recorded_at, duration_label, size_bytes),
    )
    return jsonify({"id": rid, "oss_key": oss_key})


@app.route("/api/recording/<int:rid>/asr", methods=["POST"])
@login_required
def api_run_asr(rid):
    rec = db_fetchone("SELECT asr_status FROM recordings WHERE id=?", (rid,))
    if not rec:
        return jsonify({"error": "not found"}), 404
    if rec["asr_status"] == "running":
        return jsonify({"status": "running"})
    threading.Thread(target=run_asr, args=(rid,), daemon=True).start()
    return jsonify({"status": "started"})


@app.route("/api/recording/<int:rid>/analyze", methods=["POST"])
@login_required
def api_run_analyze(rid):
    rec = db_fetchone("SELECT asr_status, analysis_status FROM recordings WHERE id=?", (rid,))
    if not rec:
        return jsonify({"error": "not found"}), 404
    if rec["asr_status"] != "done":
        return jsonify({"error": "请先完成 ASR 转录"}), 400
    if rec["analysis_status"] == "running":
        return jsonify({"status": "running"})
    threading.Thread(target=run_claude_analysis, args=(rid,), daemon=True).start()
    return jsonify({"status": "started"})


@app.route("/api/recording/<int:rid>/evaluate", methods=["POST"])
@login_required
def api_evaluate(rid):
    data = request.get_json(silent=True) or {}
    wrong = (data.get("wrong_points") or "").strip()
    improve = (data.get("improvement") or "").strip()
    correct = (data.get("correct_practice") or "").strip()
    if not (wrong or improve or correct):
        return jsonify({"error": "请至少填写一项"}), 400

    rec = db_fetchone("SELECT id FROM recordings WHERE id=?", (rid,))
    if not rec:
        return jsonify({"error": "not found"}), 404

    with _db_lock:
        conn = sqlite3.connect(DB_PATH)
        try:
            existing = conn.execute(
                "SELECT id FROM evaluations WHERE recording_id=?", (rid,)
            ).fetchone()
            if existing:
                conn.execute(
                    """UPDATE evaluations
                       SET wrong_points=?, improvement=?, correct_practice=?,
                           updated_at=datetime('now','localtime')
                       WHERE recording_id=?""",
                    (wrong, improve, correct, rid),
                )
            else:
                conn.execute(
                    """INSERT INTO evaluations
                       (recording_id, wrong_points, improvement, correct_practice)
                       VALUES (?, ?, ?, ?)""",
                    (rid, wrong, improve, correct),
                )
            conn.execute(
                "UPDATE recordings SET has_evaluation=1 WHERE id=?", (rid,)
            )
            conn.commit()
        finally:
            conn.close()

    return jsonify({"ok": True})


# ============ 健康检查 ============
@app.route("/healthz")
def healthz():
    return jsonify({"ok": True, "ts": datetime.now().isoformat()})


# ============ 启动初始化 ============
init_db()


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5058, debug=True)
