PORT="__PORT__"

is_port_ready() {
  if exec 3<>"/dev/tcp/127.0.0.1/$PORT"; then
    exec 3>&-
    exec 3<&-
    return 0
  fi
  return 1
}

if is_port_ready; then
  log "OpenCode 已可通过端口 $PORT 访问。"
  exit 0
fi

require_ubuntu

log "正在通过端口 $PORT 启动 OpenCode 网页服务"
run_logged proot-distro login ubuntu -- bash -lc "set -euo pipefail; export PATH=\"\$HOME/.opencode/bin:\$HOME/.local/bin:\$PATH\"; export BROWSER=/bin/true; if ! command -v opencode >/dev/null 2>&1 && ! test -x \"\$HOME/.opencode/bin/opencode\"; then echo '尚未安装 OpenCode，请先执行“安装 OpenCode”。' >&2; exit 3; fi; nohup opencode web --hostname 127.0.0.1 --port $PORT --print-logs >\"\$HOME/.opencode-web.log\" 2>&1 < /dev/null &"

for _ in $(seq 1 30); do
  if is_port_ready; then
    log "OpenCode 已可通过端口 $PORT 访问。"
    exit 0
  fi
  log "正在等待 OpenCode 监听端口 $PORT"
  sleep 1
done

log "OpenCode 未能在端口 $PORT 上成功启动。"
run_logged proot-distro login ubuntu -- bash -lc 'if test -f "$HOME/.opencode-web.log"; then echo "==== OpenCode 运行日志 ===="; tail -n 80 "$HOME/.opencode-web.log"; else echo "未找到 OpenCode 运行日志。"; fi' || true
exit 1
