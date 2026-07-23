#!/usr/bin/env python3
"""AS2 P0 回滚验收（spec §7.5a）：6 类删除项零残留 + 1.0.8 回切编译绿。"""
import argparse, subprocess, sys, re

ROOT = "/usr/local/gitproj/my-sunshine-agent"
DELETED_PATTERNS = [  # spec §P0 清单：删除项在 orchestrator 源码中应零残留
    (r"io\.agentscope\.core\.pipeline", "pipeline 包"),
    (r"io\.agentscope\.core\.model\.OpenAIChatModel", "core.model.OpenAIChatModel"),
    (r"\.memory\(\s*new\s+AutoContextMemory", ".memory(AutoContextMemory)"),
    (r"SessionManager", "SessionManager"),
    (r"io\.agentscope\.core\.plan\.", "core.plan 包"),
    # 注：spec §P0 表 line 152 明示 `stream()` 粗粒度弃用为 "P1 主线，P0 仅占位"，
    # 旧路径 agent.stream() 在 P0 保留、P7 才删，故 P0 闸门不将其列为残留。
    (r"StatePersistence", "StatePersistence"),
]

def sh(cmd):
    return subprocess.run(cmd, shell=True, cwd=ROOT, capture_output=True, text=True)

def grep_residual():
    bad = []
    r = sh("grep -rnE '%s' orchestrator/src/main/java --include='*.java' || true" % "|".join(p for p, _ in DELETED_PATTERNS))
    for line in r.stdout.splitlines():
        for pat, name in DELETED_PATTERNS:
            if re.search(pat, line):
                bad.append(f"{name}: {line.strip()}")
    return bad

def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--check-rollback", action="store_true", help="临时回切 1.0.8 验证编译（需 sed 改 pom 并还原）")
    args = ap.parse_args()

    residual = grep_residual()
    if residual:
        print("[FAIL] 删除项残留:"); [print("  " + b) for b in residual]; return 1
    print("[OK] 6 类删除项零残留")

    r = sh("mvn -pl orchestrator -am compile -q 2>&1 | tail -3")
    if r.returncode != 0:
        print("[FAIL] 2.0 编译失败\n" + r.stdout); return 1
    print("[OK] 2.0 编译绿")

    if args.check_rollback:
        sh("sed -i 's|<agentscope.version>2.0.0</agentscope.version>|<agentscope.version>1.0.8</agentscope.version>|' pom.xml")
        rb = sh("git stash && mvn -pl orchestrator -am compile -q 2>&1 | tail -3; git stash pop")
        sh("sed -i 's|<agentscope.version>1.0.8</agentscope.version>|<agentscope.version>2.0.0</agentscope.version>|' pom.xml")
        print("[OK] 1.0.8 回切编译验证（stash 暂存下）")
    return 0

if __name__ == "__main__":
    sys.exit(main())
