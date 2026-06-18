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


def stop_listening_port(port: int) -> bool:
    """停止占用端口的进程。"""
    try:
        import psutil
    except ImportError:
        print("提示: pip install psutil 以支持跨平台停进程", file=sys.stderr)
        return _stop_port_fallback(port)

    stopped = False
    for conn in psutil.net_connections(kind="inet"):
        if conn.laddr and conn.laddr.port == port and conn.status == psutil.CONN_LISTEN:
            if conn.pid:
                try:
                    psutil.Process(conn.pid).terminate()
                    stopped = True
                except (psutil.NoSuchProcess, psutil.AccessDenied):
                    pass
    if stopped:
        time.sleep(2)
    return stopped


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
