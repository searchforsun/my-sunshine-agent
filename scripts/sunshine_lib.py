"""Sunshine 运维脚本公共库（跨平台）。"""
from __future__ import annotations

import glob
import os
import platform
import shutil
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def unwrap_r(body: dict, *, context: str = "request") -> dict | list | None:
    """解析后端 R<T> 响应；业务失败抛 RuntimeError。"""
    code = body.get("code")
    if code != 200:
        msg = body.get("msg") or f"code={code}"
        raise RuntimeError(f"[{context}] {msg}")
    return body.get("data")


def java_bin() -> str:
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        name = "java.exe" if platform.system() == "Windows" else "java"
        return str(Path(java_home) / "bin" / name)
    return shutil.which("java") or "java"


def find_jar(module: str, artifact: str) -> Path:
    pattern = ROOT / module / "target" / f"{artifact}-*.jar"
    jars = [
        p for p in glob.glob(str(pattern))
        if not p.endswith(".original.jar")
    ]
    if not jars:
        raise FileNotFoundError(
            f"JAR not found: {module}/target/{artifact}-*.jar — run: mvn package -DskipTests"
        )
    return Path(jars[0])


def package_java_modules(modules: list[str], *, skip_tests: bool = True) -> None:
    """重启前打包指定 Maven 模块（含 -am 依赖）。"""
    ordered = list(dict.fromkeys(m for m in modules if m))
    if not ordered:
        return
    pl = ",".join(ordered)
    cmd = ["mvn", "package", "-pl", pl, "-am", "-q"]
    if skip_tests:
        cmd.append("-DskipTests")
    print(f">> mvn package -pl {pl} -am -DskipTests ...")
    subprocess.run(cmd, cwd=str(ROOT), check=True)
    print("   package done")


def skywalking_agent() -> Path:
    return ROOT / "docker" / "skywalking-agent" / "skywalking-agent.jar"


def skywalking_java_opts(service_name: str) -> list[str]:
    agent = skywalking_agent()
    if not agent.is_file():
        return []
    return [
        f"-javaagent:{agent}",
        f"-Dskywalking.agent.service_name=sunshine-{service_name}",
        "-Dskywalking.collector.backend_service=ecs4c16g:11800",
    ]


def start_java_detached(
    module: str,
    artifact: str,
    *,
    service_name: str | None = None,
    wait_sec: float = 3.0,
) -> subprocess.Popen | None:
    """后台启动 Java 服务，stdout/stderr 写入 module/logs。"""
    jar = find_jar(module, artifact)
    log_dir = ROOT / module / "logs"
    log_dir.mkdir(parents=True, exist_ok=True)
    svc = service_name or module
    args = [*skywalking_java_opts(svc), "-jar", str(jar)]
    stdout = open(log_dir / "startup.log", "w", encoding="utf-8")
    stderr = open(log_dir / "startup.err.log", "w", encoding="utf-8")
    creationflags = 0
    if platform.system() == "Windows":
        creationflags = subprocess.CREATE_NO_WINDOW  # type: ignore[attr-defined]
    proc = subprocess.Popen(
        [java_bin(), *args],
        stdout=stdout,
        stderr=stderr,
        cwd=str(ROOT),
        creationflags=creationflags,
    )
    time.sleep(wait_sec)
    return proc


def force_kill_pid(pid: int) -> bool:
    """SIGKILL 指定 PID（Windows 用 taskkill /F）。"""
    if pid <= 0:
        return False
    if platform.system() == "Windows":
        proc = subprocess.run(
            ["taskkill", "/F", "/PID", str(pid)],
            check=False,
            capture_output=True,
        )
        return proc.returncode == 0
    proc = subprocess.run(["kill", "-9", str(pid)], check=False, capture_output=True)
    return proc.returncode == 0


def force_stop_java_jar(jar: Path | str) -> list[int]:
    """按 JAR 路径 SIGKILL 匹配的 Java 进程（兜底：端口已释放但旧 JVM 仍存活）。"""
    jar_path = Path(jar)
    jar_name = jar_path.name
    jar_resolved = str(jar_path.resolve()) if jar_path.exists() else str(jar)
    killed: list[int] = []
    try:
        import psutil
    except ImportError:
        return _force_stop_java_jar_fallback(jar_name)

    for proc in psutil.process_iter(["pid", "cmdline"]):
        try:
            cmdline = proc.info.get("cmdline") or []
            if not cmdline:
                continue
            joined = " ".join(cmdline)
            if jar_name not in joined and jar_resolved not in joined:
                continue
            pid = proc.info["pid"]
            if pid and force_kill_pid(pid):
                killed.append(pid)
        except (psutil.NoSuchProcess, psutil.AccessDenied):
            pass
    if killed:
        time.sleep(2)
    return killed


def _force_stop_java_jar_fallback(jar_name: str) -> list[int]:
    if platform.system() == "Windows":
        out = subprocess.run(
            ["wmic", "process", "where",
             f"CommandLine like '%{jar_name}%'", "get", "ProcessId"],
            capture_output=True,
            text=True,
            check=False,
        )
        killed: list[int] = []
        for line in out.stdout.splitlines():
            line = line.strip()
            if line.isdigit():
                pid = int(line)
                if force_kill_pid(pid):
                    killed.append(pid)
        if killed:
            time.sleep(2)
        return killed
    out = subprocess.run(
        ["pgrep", "-f", jar_name],
        capture_output=True,
        text=True,
        check=False,
    )
    killed = []
    for line in out.stdout.splitlines():
        line = line.strip()
        if line.isdigit():
            pid = int(line)
            if force_kill_pid(pid):
                killed.append(pid)
    if killed:
        time.sleep(2)
    return killed


def stop_listening_port(port: int) -> bool:
    """SIGKILL 占用端口的进程，确保旧 JVM 释放。"""
    try:
        import psutil
    except ImportError:
        print("提示: pip install psutil 以支持跨平台停进程", file=sys.stderr)
        return _stop_port_fallback(port)

    pids: set[int] = set()
    for conn in psutil.net_connections(kind="inet"):
        if conn.laddr and conn.laddr.port == port and conn.status == psutil.CONN_LISTEN:
            if conn.pid:
                pids.add(conn.pid)
    if not pids:
        return False
    for pid in pids:
        force_kill_pid(pid)
    time.sleep(2)
    return True


def stop_java_service(module: str, artifact: str, port: int) -> list[int]:
    """停服：先 SIGKILL 占端口进程，再按 JAR 名清理残留 Java 进程。"""
    if stop_listening_port(port):
        print(f"  [KILL] :{port} released")
    try:
        jar = find_jar(module, artifact)
    except FileNotFoundError:
        jar = f"{artifact}-"
    killed = force_stop_java_jar(jar)
    for pid in killed:
        print(f"  [KILL] {artifact} pid={pid}")
    return killed


def _stop_port_fallback(port: int) -> bool:
    system = platform.system()
    if system == "Windows":
        out = subprocess.run(
            ["netstat", "-ano"],
            capture_output=True,
            text=True,
            check=False,
        )
        for line in out.stdout.splitlines():
            if "LISTENING" not in line or f":{port}" not in line:
                continue
            parts = line.split()
            if parts and parts[-1].isdigit():
                subprocess.run(
                    ["taskkill", "/F", "/PID", parts[-1]],
                    check=False,
                    capture_output=True,
                )
                time.sleep(2)
                return True
        return False
    out = subprocess.run(
        ["lsof", "-ti", f":{port}"],
        capture_output=True,
        text=True,
        check=False,
    )
    pids = [p.strip() for p in out.stdout.splitlines() if p.strip()]
    if not pids:
        return False
    for pid in pids:
        subprocess.run(["kill", "-9", pid], check=False)
    time.sleep(2)
    return True


def run_mysql(sql: str, *, host: str, port: int, user: str, password: str) -> None:
    mysql = shutil.which("mysql")
    if not mysql:
        raise RuntimeError("mysql client not found in PATH")
    proc = subprocess.run(
        [mysql, "-h", host, "-P", str(port), "-u", user, f"-p{password}"],
        input=sql,
        text=True,
        capture_output=True,
    )
    if proc.returncode != 0:
        raise RuntimeError(f"MySQL failed: {proc.stderr or proc.stdout}")


def ensure_redis():
    try:
        import redis  # noqa: F401
    except ImportError:
        subprocess.run(
            [sys.executable, "-m", "pip", "install", "redis", "-q"],
            check=True,
        )


def redis_delete_patterns(host: str, port: int, password: str, patterns: list[str]) -> int:
    ensure_redis()
    import redis

    client = redis.Redis(
        host=host, port=port, password=password or None, decode_responses=True
    )
    total = 0
    for pattern in patterns:
        keys = list(client.scan_iter(pattern))
        if keys:
            total += client.delete(*keys)
    return total


BROWSER_LOCALSTORAGE_JS = """
[
  'sunshine-conv-index',
  'sunshine-current-conversation-id',
  'sunshine-active-generation'
].forEach(k => localStorage.removeItem(k));
Object.keys(localStorage)
  .filter(k => k.startsWith('sunshine-conv-msgs:'))
  .forEach(k => localStorage.removeItem(k));
console.log('Sunshine local session cache cleared');
""".strip()
