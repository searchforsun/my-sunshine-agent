#!/usr/bin/env python3
"""AS2 P3 回滚验收（spec 5）：TaskList 替换 TaskBoard 后删除项零残留 + 编译。

退出码：0=通过，1=失败。
检查项：
  1. 删除项零残留：manage_tasks 工具 / ManageTasksTool 类 / as2.tasklistNative flag。
  2. 原生装配在位：enableTaskList + TodoTools + TodoTasksBridge（todo_write 桥）。
  3. 2.0 编译绿。
  4. 单测全绿。
P3 为「删自研 + 官方装配」的净删改动，行为等价由 verify_tasklist_native_live.py（N1-N3）守护；
本脚本为轻量零残留 + 编译门禁，与 P1/P2 回滚脚本对齐（不做 git revert——P3 提交与 P1/P2 交织，
revert 语义见 verify_rollback_p2_checkpoint.py）。
"""
import os
import subprocess
import sys

ROOT = "/usr/local/gitproj/my-sunshine-agent"


def sh(cmd):
    return subprocess.run(cmd, shell=True, cwd=ROOT, capture_output=True, text=True)


def check_no_residual():
    bad = []
    # manage_tasks 工具名残留——只查非注释代码行（剔除 // 与 javadoc * 开头行）
    r = sh(
        "grep -rnE 'manage_tasks|ManageTasksTool' orchestrator/src/main/java --include='*.java' "
        "| grep -vE ':\\s*(//|\\*|/\\*)' || true")
    if r.stdout.strip():
        bad.append("manage_tasks / ManageTasksTool 残留:\n" + r.stdout)
    r = sh("grep -rnE 'tasklistNative|tasklist-native' orchestrator/src/main/java --include='*.java' || true")
    if r.stdout.strip():
        bad.append("as2.tasklistNative flag 残留:\n" + r.stdout)
    return bad


def check_native_in_place():
    bad = []
    r = sh("grep -rln 'enableTaskList' orchestrator/src/main/java --include='*.java' || true")
    if not r.stdout.strip():
        bad.append("enableTaskList 装配缺失")
    r = sh("grep -rln 'TodoTools' orchestrator/src/main/java --include='*.java' || true")
    if not r.stdout.strip():
        bad.append("TodoTools 装配缺失")
    r = sh("test -f orchestrator/src/main/java/com/sunshine/orchestrator/taskboard/TodoTasksBridge.java && echo ok || true")
    if "ok" not in r.stdout:
        bad.append("TodoTasksBridge 缺失")
    return bad


def main() -> int:
    print("[1/4] 检查删除项零残留...")
    residual = check_no_residual()
    if residual:
        print("[FAIL] 删除项残留:")
        for b in residual:
            print(b)
        return 1
    print("[OK] manage_tasks / ManageTasksTool / tasklistNative flag 零残留")

    print("[2/4] 检查原生 TaskList 装配在位...")
    missing = check_native_in_place()
    if missing:
        print("[FAIL] 原生装配缺失:")
        for b in missing:
            print(b)
        return 1
    print("[OK] enableTaskList + TodoTools + TodoTasksBridge 在位")

    print("[3/4] 编译验证...")
    r = sh("mvn -pl orchestrator -am compile -q 2>&1 | tail -3")
    if r.returncode != 0:
        print("[FAIL] 编译失败\n" + r.stdout)
        return 1
    print("[OK] 2.0 编译绿")

    print("[4/4] 单测验证...")
    r = sh("mvn -pl orchestrator test 2>&1 | grep 'Tests run:' | tail -1")
    if "Failures: 0, Errors: 0" not in r.stdout:
        print("[FAIL] 单测失败\n" + r.stdout)
        return 1
    print("[OK] 单测全绿 " + r.stdout.strip())
    return 0


if __name__ == "__main__":
    sys.exit(main())
