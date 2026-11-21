#!/bin/sh
# Checks that live updates cross instances in the prod stack (PLAN Phase 9: "app x2 to prove fan-out").
# A patient's stream is held open on app1 while staff, talking to app2 only, call that patient.
# The stream on app1 must receive the CALLED update, which can only arrive through Redis pub/sub.
#
#   sh ops/check-fanout.sh        (stack started with ops/demo.env: demo clinic and logins)
#
# Uses the "general" queue, which the demo driver leaves alone, and needs nobody else waiting there.
set -eu
COMPOSE="docker compose -f ops/docker-compose.prod.yml --env-file ops/demo.env"
on_app2() { $COMPOSE exec -T app2 sh -c "$1"; }

# Everything below on app2 goes to localhost:8080 inside the app2 container, bypassing Caddy.
queue_id=$(on_app2 'curl -fsS localhost:8080/api/public/queues/sharma-clinic/general' | sed 's/.*"queueId":"\([^"]*\)".*/\1/')
echo "queue general: $queue_id"

# login USER PASSWORD: a session in /tmp/USER.jar and the console's CSRF token in /tmp/USER.csrf
login() {
  on_app2 "
    set -e
    cd /tmp && rm -f $1.jar
    csrf=\$(curl -fsS -c $1.jar localhost:8080/login | sed -n 's/.*name=\"_csrf\" value=\"\([^\"]*\)\".*/\1/p')
    curl -fsS -b $1.jar -c $1.jar -o /dev/null -d \"username=$1&password=$2&_csrf=\$csrf\" localhost:8080/login
    curl -fsS -b $1.jar localhost:8080/staff/queues/$queue_id | sed -n 's/.*name=\"csrf-token\" content=\"\([^\"]*\)\".*/\1/p' > $1.csrf
    test -s $1.csrf
  "
}
# staff_post USER PATH: POST on app2 as USER, prints the HTTP status
staff_post() {
  on_app2 "cd /tmp && curl -sS -o /dev/null -w '%{http_code}' -b $1.jar -X POST -H \"X-CSRF-TOKEN: \$(cat $1.csrf)\" localhost:8080$2"
}

login reception dev-reception-pass
login dr-sharma dev-doctor-pass
echo "open today on app2: HTTP $(staff_post reception /api/staff/queues/$queue_id/days/today/open)"

# Call anyone still waiting (left by an earlier run), so the next call-next takes our patient.
for i in $(seq 1 30); do
  board=$(on_app2 "curl -fsS -b /tmp/dr-sharma.jar localhost:8080/api/staff/queues/$queue_id/board")
  case "$board" in *'"waiting":[]'*) break ;; esac
  staff_post dr-sharma /api/staff/queues/$queue_id/call-next > /dev/null
done

code=$(on_app2 "curl -fsS -H 'Content-Type: application/json' \
  -d '{\"name\":\"Fan-out check\",\"visitType\":\"NEW\",\"consentVersion\":\"v1\",\"idempotencyKey\":\"'\$(cat /proc/sys/kernel/random/uuid)'\"}' \
  localhost:8080/api/public/queues/$queue_id/tokens" | sed 's/.*"publicCode":"\([^"]*\)".*/\1/')
echo "joined on app2"

# Hold the patient's stream open on app1 for 12 s, in the background.
out=$(mktemp)
$COMPOSE exec -T app1 sh -c "curl -sN -m 12 -H 'Accept: text/event-stream' localhost:8080/api/public/tokens/$code/stream" > "$out" 2>/dev/null &
stream=$!
sleep 3

# The doctor calls next on app2 (a doctor calls as themselves, so the pool needs no doctor id).
echo "call-next on app2: HTTP $(staff_post dr-sharma /api/staff/queues/$queue_id/call-next)"
wait $stream || true

if grep -q '"status":"CALLED"' "$out"; then
  echo "PASS: the stream on app1 received CALLED, made on app2"
else
  echo "FAIL: no CALLED event on app1; stream output:"
  cat "$out"
  exit 1
fi
