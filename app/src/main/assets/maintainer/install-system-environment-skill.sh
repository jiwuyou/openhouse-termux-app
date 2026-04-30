require_ubuntu

log "正在检查 OpenCode 是否已安装"
run_logged proot-distro login ubuntu -- bash -lc 'set -euo pipefail; export PATH="$HOME/.opencode/bin:$HOME/.local/bin:$PATH"; if command -v opencode >/dev/null 2>&1 || test -x "$HOME/.opencode/bin/opencode"; then echo "OpenCode 已安装。"; else echo "尚未安装 OpenCode，请先执行“安装 OpenCode”。" >&2; exit 4; fi'

log "正在写入 OpenCode 用户级 skill：系统环境说明与 Agent 安装配置"
TEMP_SCRIPT="$HOME/.maintainer-logs/install-system-environment-skill-inner.sh"
cat > "$TEMP_SCRIPT" <<'__OPENHOUSE_INSTALL_SYSTEM_ENV_SKILL__'
set -euo pipefail
SYSTEM_ENV_SKILL_TARGET_DIR="$HOME/.config/opencode/skills/system-environment-description"
AGENT_INSTALL_SKILL_TARGET_DIR="$HOME/.config/opencode/skills/install-ai-agents"

SKILL_TARGET_DIR="$SYSTEM_ENV_SKILL_TARGET_DIR"
mkdir -p "$SKILL_TARGET_DIR"
__BUNDLED_SYSTEM_ENV_SKILL__
echo "系统环境说明技能目录：$SKILL_TARGET_DIR"
echo "系统环境说明技能文件：$SKILL_TARGET_DIR/SKILL.md"

SKILL_TARGET_DIR="$AGENT_INSTALL_SKILL_TARGET_DIR"
mkdir -p "$SKILL_TARGET_DIR"
__BUNDLED_OPENCODE_AGENT_INSTALL_SKILL__
echo "Agent 安装配置技能目录：$SKILL_TARGET_DIR"
echo "Agent 安装配置技能文件：$SKILL_TARGET_DIR/SKILL.md"
__OPENHOUSE_INSTALL_SYSTEM_ENV_SKILL__

run_logged proot-distro login ubuntu -- bash "$TEMP_SCRIPT"
rm -f "$TEMP_SCRIPT"

log "OpenCode 用户级 skill 写入阶段已完成。"
