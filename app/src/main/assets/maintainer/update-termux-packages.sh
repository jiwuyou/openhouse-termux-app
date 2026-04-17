log "正在执行 pkg update -y"
run_logged pkg update -y

log "正在执行 pkg install -y proot-distro curl"
run_logged pkg install -y proot-distro curl

log "Termux 软件包阶段已完成。"
