#!/bin/sh
# Restarts cncverse-bridge-server when it stops answering HTTP (hung, not
# crashed — systemd's Restart= only covers exits). Run every minute by
# cncverse-bridge-healthcheck.timer; restarts after 3 consecutive failures
# and first asks the JVM for a thread dump (goes to the journal) so the
# cause can be diagnosed.
SERVICE=cncverse-bridge-server
PORT="${CNC_PORT:-$(systemctl show -p Environment --value "$SERVICE" | tr ' ' '\n' | sed -n 's/^CNC_PORT=//p')}"
PORT="${PORT:-8080}"
STATE=/run/cncverse-bridge-healthcheck.failures

systemctl is-active --quiet "$SERVICE" || { rm -f "$STATE"; exit 0; }

# Give a fresh start time to load plugins
STARTED=$(systemctl show -p ActiveEnterTimestampMonotonic --value "$SERVICE")
NOW=$(awk '{print int($1 * 1000000)}' /proc/uptime)
[ $(( (NOW - STARTED) / 1000000 )) -lt 180 ] && { rm -f "$STATE"; exit 0; }

if curl -fsS -o /dev/null -m 20 "http://127.0.0.1:${PORT}/manifest.json"; then
    rm -f "$STATE"
    exit 0
fi

FAILS=$(( $(cat "$STATE" 2>/dev/null || echo 0) + 1 ))
echo "$FAILS" > "$STATE"
echo "health check failed ($FAILS/3) on port $PORT"
if [ "$FAILS" -ge 3 ]; then
    PID=$(systemctl show -p MainPID --value "$SERVICE")
    [ "$PID" -gt 0 ] 2>/dev/null && kill -3 "$PID" && sleep 2
    echo "restarting $SERVICE (unresponsive)"
    rm -f "$STATE"
    systemctl restart "$SERVICE"
fi
