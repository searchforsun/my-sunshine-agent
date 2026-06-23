#!/usr/bin/env bash
# 示例：上传后校验包内关键路径是否存在
set -euo pipefail

ROOT="${1:-.}"

required=(
  "SKILL.md"
  "references/workflow-guide.md"
  "scripts/normalize_input.py"
  "templates/analysis-output.md"
  "assets/sample-todo.json"
)

for path in "${required[@]}"; do
  if [[ ! -f "${ROOT}/${path}" ]]; then
    echo "missing: ${path}" >&2
    exit 1
  fi
done

echo "ok: demo-full-pack structure valid"
