# 工牌接诊分析系统 · 交接文档

---

## 服务器信息

| 项 | 值 |
|---|---|
| 服务器 IP | 43.136.130.133（腾讯云成都） |
| 访问入口 | http://43.136.130.133/gp/ |
| 备用域名 | http://www.aibeautyfulwomen.cn/（.cn 未备案，可能不稳定） |
| 老板账号 | `diajie` / `Diajie2026!` |
| Flask 端口 | 5058（gunicorn，绑定 127.0.0.1） |
| 部署路径 | /app/gp-system/ |
| systemd 服务 | `gongpai.service`（`sudo systemctl restart gongpai`） |
| nginx 配置 | /etc/nginx/sites-available/followup-agent（IP:80 的 /gp/ 块） |
| 服务日志 | /var/log/gongpai-access.log, /var/log/gongpai-error.log |
| OSS | bucket=gongpai-asr, region=oss-cn-chengdu |
| ASR | DashScope fun-asr（阿里云百炼，API Key 在 .env） |
| Claude | claude-opus-4-7，**必须走代理** http://127.0.0.1:7890 |
---

