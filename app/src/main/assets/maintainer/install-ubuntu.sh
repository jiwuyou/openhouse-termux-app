if ! command -v proot-distro >/dev/null 2>&1; then
  log "缺少 proot-distro，请先执行“更新 Termux 软件包”。"
  exit 2
fi

if proot-distro login ubuntu -- true >/dev/null 2>&1; then
  log "Ubuntu 已安装。"
  exit 0
fi

log "正在执行 proot-distro install ubuntu"
run_logged proot-distro install ubuntu

if proot-distro login ubuntu -- true >/dev/null 2>&1; then
  log "Ubuntu 安装完成。"
else
  log "Ubuntu 安装后未生成可用的 rootfs。"
  exit 1
fi
