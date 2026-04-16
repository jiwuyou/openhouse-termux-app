require_ubuntu

log "正在 Ubuntu 内安装 OpenCode（如尚未安装）"
run_logged proot-distro login ubuntu -- bash -lc 'set -euo pipefail; export PATH="$HOME/.opencode/bin:$HOME/.local/bin:$PATH"; if command -v opencode >/dev/null 2>&1 || test -x "$HOME/.opencode/bin/opencode"; then echo "OpenCode 已安装。"; else curl -fsSL https://opencode.ai/install | bash; fi; export PATH="$HOME/.opencode/bin:$HOME/.local/bin:$PATH"; if command -v opencode >/dev/null 2>&1; then command -v opencode; elif test -x "$HOME/.opencode/bin/opencode"; then echo "$HOME/.opencode/bin/opencode"; else echo "OpenCode 安装后仍未找到可执行文件。" >&2; exit 4; fi'

log "正在 Ubuntu 主目录内写入产品路径辅助文件"
run_logged proot-distro login ubuntu -- bash -lc 'set -euo pipefail; mkdir -p "$HOME/product-links"; printf "%s\n" "/data/data/com.termux/files/home/product-docs" > "$HOME/product-links/docs-path.txt"; printf "%s\n" "/data/data/com.termux/files/home/workspace" > "$HOME/product-links/workspace-path.txt"; echo "文档路径：$(cat "$HOME/product-links/docs-path.txt")"; echo "工作区路径：$(cat "$HOME/product-links/workspace-path.txt")"'

log "OpenCode 安装阶段已完成。"
