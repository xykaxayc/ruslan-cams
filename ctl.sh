#!/bin/bash
# Управление сервисом камер: start | stop | status. Вызывается из «Камеры Руслана.app».
export PATH="/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin"
DIR="$HOME/ruslan-cams"
URL="http://127.0.0.1:8090/grid.html"
cd "$DIR" || exit 1

running() { pgrep -f " $DIR/start.py$" >/dev/null; }

case "$1" in
  status)
    running && echo running || echo stopped ;;
  start)
    if ! running; then
      # Redline Client держит тот же порт службы 59528 — закрываем, иначе туннели не наши
      pkill -f "MacOS/Redline Client" 2>/dev/null
      python3 - "$DIR" <<'PYTHON'
import os, subprocess, sys
folder = sys.argv[1]
with open(os.path.join(folder, "run.log"), "w") as out:
    subprocess.Popen([sys.executable, "-u", os.path.join(folder, "start.py")],
                     cwd=folder, stdin=subprocess.DEVNULL, stdout=out, stderr=subprocess.STDOUT,
                     start_new_session=True)
PYTHON
      # ждём, пока сетка соберётся (туннели + API регистраторов)
      for i in $(seq 1 90); do
        sleep 1
        grep -q "Камер в сетке" "$DIR/run.log" && break
        running || break
      done
    fi
    if grep -q "Камер в сетке" "$DIR/run.log"; then
      open "$URL"; echo ok
    else
      echo "fail"; tail -5 "$DIR/run.log"
    fi ;;
  speed)
    # скорость каждой камеры за 15 с: speed ≥ 1.0x — успевает в реальном времени
    for s in $(python3 -c 'import json; d=json.load(open("www/grid.json")); print(" ".join(c["streams"][c["default"]] + "_src" for c in d["cams"]))'); do
      ( r=$(ffmpeg -hide_banner -rtsp_transport tcp -i "rtsp://127.0.0.1:8554/$s" -t 15 -map 0:v -c copy -f null - 2>&1 \
            | tr '\r' '\n' | grep -oE 'speed=[ 0-9.]+x' | tail -1); echo "${s%_src}: ${r:-нет данных}" ) &
    done; wait ;;
  stop)
    pids=$(pgrep -f " $DIR/start.py$")
    [ -z "$pids" ] || kill $pids 2>/dev/null
    # даём сервису закрыть туннели (/StopP2P), потом добиваем остатки
    for i in $(seq 1 15); do running || break; sleep 1; done
    # Last resort: an old/stuck orchestrator must not block the following start.
    if running; then
      pids=$(pgrep -f " $DIR/start.py$")
      [ -z "$pids" ] || kill -KILL $pids 2>/dev/null
    fi
    pkill -f "ruslan-cams/bin/go2rtc"; pkill -f "http.server 8090 --bind 127.0.0.1 --directory $DIR/www$"; pkill -f "ruslan-cams/p2p/RSP2PServer"
    echo stopped ;;
esac
