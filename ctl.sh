#!/bin/bash
set -e

case "$1" in
  start)
    systemctl start ruslan-cams.service
    ;;
  stop)
    systemctl stop ruslan-cams.service
    ;;
  restart)
    systemctl restart ruslan-cams.service
    ;;
  status)
    systemctl status ruslan-cams.service
    ;;
  logs)
    journalctl -u ruslan-cams.service -n 50 -f
    ;;
  health)
    tail -f /opt/ruslan-cams/logs/cams.log
    ;;
  *)
    echo "Использование: $0 {start|stop|restart|status|logs|health}"
    exit 1
    ;;
esac
