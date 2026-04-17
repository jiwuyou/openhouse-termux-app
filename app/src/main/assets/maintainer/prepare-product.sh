TERMUX_HOME="${HOME:-/data/data/com.termux/files/home}"
DOC_DIR="$TERMUX_HOME/product-docs"
WORKSPACE_DIR="$TERMUX_HOME/workspace"
TERMUX_CONFIG_DIR="$TERMUX_HOME/.termux"
TERMUX_PROPERTIES_FILE="$TERMUX_CONFIG_DIR/termux.properties"

log "正在确保 Termux 配置目录存在。"
mkdir -p "$DOC_DIR" "$WORKSPACE_DIR" "$TERMUX_CONFIG_DIR"
chmod 700 "$DOC_DIR" "$WORKSPACE_DIR" "$TERMUX_CONFIG_DIR" || true

log "正在在 $TERMUX_PROPERTIES_FILE 中启用 allow-external-apps"
touch "$TERMUX_PROPERTIES_FILE"
if grep -q '^[[:space:]]*allow-external-apps' "$TERMUX_PROPERTIES_FILE"; then
  sed -i 's/^[[:space:]]*allow-external-apps[[:space:]]*=.*/allow-external-apps = true/' "$TERMUX_PROPERTIES_FILE"
else
  printf '\nallow-external-apps = true\n' >> "$TERMUX_PROPERTIES_FILE"
fi

log "正在将产品文档写入 $DOC_DIR"
cat > "$DOC_DIR/README.md" <<'EOF'
# Product Docs

This directory is visible to OpenCode. Put stable product notes here.
EOF

cat > "$DOC_DIR/USER_GUIDE.md" <<'EOF'
# User Guide

1. Open the browser page served by OpenCode.
2. Ask the agent to read AI_GUIDE.md before starting work.
3. Keep your projects under ~/workspace.
EOF

cat > "$DOC_DIR/AI_GUIDE.md" <<'EOF'
# AI Guide

You are operating inside a local product workspace.

Rules:
- Read README.md and this AI_GUIDE.md before making changes.
- Treat ~/workspace as the writable area for user projects.
- Keep generated artifacts organized and explain what was changed.
- Prefer publishing completed projects through git when the user requests it.
EOF

log "文档路径：$DOC_DIR"
log "工作区路径：$WORKSPACE_DIR"
log "配置文件：$TERMUX_PROPERTIES_FILE"
