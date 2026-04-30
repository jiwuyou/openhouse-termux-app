PRIMARY_URL="__OPENCODE_INSTALL_PRIMARY_URL__"
PRIMARY_LABEL="__OPENCODE_INSTALL_PRIMARY_LABEL__"
SECONDARY_URL="__OPENCODE_INSTALL_SECONDARY_URL__"
SECONDARY_LABEL="__OPENCODE_INSTALL_SECONDARY_LABEL__"
ALLOW_FALLBACK="__OPENCODE_INSTALL_ALLOW_FALLBACK__"

require_ubuntu

install_opencode_from() {
  local source_label="$1"
  local source_url="$2"

  log "正在通过 $source_label 安装 OpenCode（如尚未安装）"
  run_logged proot-distro login ubuntu -- env OPENHOUSE_OPENCODE_INSTALL_URL="$source_url" bash -lc 'set -euo pipefail; export PATH="$HOME/.opencode/bin:$HOME/.local/bin:$PATH"; if command -v opencode >/dev/null 2>&1 || test -x "$HOME/.opencode/bin/opencode"; then echo "OpenCode 已安装。"; else curl -fsSL "$OPENHOUSE_OPENCODE_INSTALL_URL" | bash; fi; export PATH="$HOME/.opencode/bin:$HOME/.local/bin:$PATH"; if command -v opencode >/dev/null 2>&1; then command -v opencode; elif test -x "$HOME/.opencode/bin/opencode"; then echo "$HOME/.opencode/bin/opencode"; else echo "OpenCode 安装后仍未找到可执行文件。" >&2; exit 4; fi'
}

if install_opencode_from "$PRIMARY_LABEL" "$PRIMARY_URL"; then
  log "OpenCode 主下载源安装已完成。"
else
  primary_status=$?
  if [ "$ALLOW_FALLBACK" = "1" ] && [ "$SECONDARY_URL" != "$PRIMARY_URL" ]; then
    log "主下载源失败，正在切换到 $SECONDARY_LABEL 重试。"
    install_opencode_from "$SECONDARY_LABEL" "$SECONDARY_URL" || exit $?
  else
    exit "$primary_status"
  fi
fi

log "正在 Ubuntu 主目录内写入产品路径辅助文件"
run_logged proot-distro login ubuntu -- bash -lc 'set -euo pipefail; mkdir -p "$HOME/product-links"; printf "%s\n" "/data/data/com.termux/files/home/product-docs" > "$HOME/product-links/docs-path.txt"; printf "%s\n" "/data/data/com.termux/files/home/workspace" > "$HOME/product-links/workspace-path.txt"; echo "文档路径：$(cat "$HOME/product-links/docs-path.txt")"; echo "工作区路径：$(cat "$HOME/product-links/workspace-path.txt")"'

log "OpenCode 安装阶段已完成。"
