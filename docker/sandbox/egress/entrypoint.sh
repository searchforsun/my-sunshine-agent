#!/bin/sh
# 从 ALLOW（逗号分隔 host）生成 tinyproxy Filter ACL，再前台启动。
set -eu
FILTER=/etc/tinyproxy/filter
: > "$FILTER"
if [ -n "${ALLOW:-}" ]; then
  OLD_IFS=$IFS
  IFS=,
  for h in $ALLOW; do
    h=$(printf '%s' "$h" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')
    [ -n "$h" ] && printf '%s\n' "$h" >> "$FILTER"
  done
  IFS=$OLD_IFS
fi
exec tinyproxy -d -c /etc/tinyproxy/tinyproxy.conf
