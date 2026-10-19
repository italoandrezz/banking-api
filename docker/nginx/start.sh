#!/bin/sh
set -eu
# Validate before envsubst: no directive injection or silently disabled limits.
for name in AUTH_LOGIN_RATE AUTH_REGISTER_RATE AUTH_TOTAL_RATE AUTH_LOGIN_BURST AUTH_REGISTER_BURST AUTH_TOTAL_BURST; do
    value=$(printenv "$name")
    case "$value" in
        ''|*[!0-9]*|0*) echo '{"event":"proxy_configuration_invalid"}' >&2; exit 1 ;;
    esac
    if [ "${#value}" -gt 5 ] || [ "$value" -gt 60000 ]; then
        echo '{"event":"proxy_configuration_invalid"}' >&2; exit 1
    fi
done
total_wait=$(( (60 + AUTH_TOTAL_RATE - 1) / AUTH_TOTAL_RATE ))
LOGIN_RETRY_AFTER=$(( (60 + AUTH_LOGIN_RATE - 1) / AUTH_LOGIN_RATE ))
REGISTER_RETRY_AFTER=$(( (60 + AUTH_REGISTER_RATE - 1) / AUTH_REGISTER_RATE ))
[ "$LOGIN_RETRY_AFTER" -ge "$total_wait" ] || LOGIN_RETRY_AFTER=$total_wait
[ "$REGISTER_RETRY_AFTER" -ge "$total_wait" ] || REGISTER_RETRY_AFTER=$total_wait
export LOGIN_RETRY_AFTER REGISTER_RETRY_AFTER
envsubst '${AUTH_LOGIN_RATE} ${AUTH_REGISTER_RATE} ${AUTH_TOTAL_RATE} ${AUTH_LOGIN_BURST} ${AUTH_REGISTER_BURST} ${AUTH_TOTAL_BURST} ${LOGIN_RETRY_AFTER} ${REGISTER_RETRY_AFTER}' \
    < /etc/banking-nginx/nginx.conf.template > /tmp/nginx.conf
if ! nginx -t -c /tmp/nginx.conf >/dev/null 2>&1; then
    echo '{"event":"proxy_configuration_invalid"}' >&2; exit 1
fi
exec nginx -c /tmp/nginx.conf -g 'daemon off;'
