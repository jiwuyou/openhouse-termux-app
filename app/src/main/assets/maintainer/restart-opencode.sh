PORT="__PORT__"

require_ubuntu

log "正在通过端口 $PORT 重启 OpenCode 网页服务"
run_logged proot-distro login ubuntu -- bash -lc "set -euo pipefail; export PATH=\"\$HOME/.opencode/bin:\$HOME/.local/bin:\$PATH\"; pkill -f 'opencode web --hostname 127.0.0.1 --port $PORT' >/dev/null 2>&1 || true; sleep 1; if ! command -v opencode >/dev/null 2>&1 && ! test -x \"\$HOME/.opencode/bin/opencode\"; then echo '尚未安装 OpenCode，请先执行“安装 OpenCode”。' >&2; exit 3; fi; nohup opencode web --hostname 127.0.0.1 --port $PORT >\"\$HOME/.opencode-web.log\" 2>&1 < /dev/null &"

for _ in $(seq 1 20); do
  if curl -fsS --max-time 2 "http://127.0.0.1:$PORT" >/dev/null 2>&1; then
    log "OpenCode 已可通过端口 $PORT 访问。"
    exit 0
  fi
  log "正在等待 OpenCode 监听端口 $PORT"
  sleep 1
done

log "OpenCode 未能在端口 $PORT 上成功重启。"
exit 1
