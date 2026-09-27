"""Check or install the supported Windows WeChat JSON exporters.

This is the lightweight workbench counterpart of the current she-love-me
workflow. It never prints keys or chat content; it only reports executable
availability and versions.
"""

from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
from typing import Any


if sys.platform == "win32":
    # Keep structured status readable in Windows terminals using a legacy
    # code page; the JSON itself remains UTF-8.
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")


PROVIDERS = {
    "weflow-cli": {"package": "weflow-cli", "command": "weflow-cli"},
    "ciphertalk": {"package": "ciphertalk-cli", "command": "miyu"},
}
ORDER = ("weflow-cli", "ciphertalk")


def command_path(name: str) -> str | None:
    return shutil.which(name) or shutil.which(f"{name}.cmd")


def run(command: list[str], timeout: int = 60) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        command,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=timeout,
        check=False,
    )


def status(provider: str) -> dict[str, Any]:
    spec = PROVIDERS[provider]
    executable = command_path(spec["command"])
    node = command_path("node")
    npm = command_path("npm")
    report: dict[str, Any] = {
        "provider": provider,
        "platform": sys.platform,
        "supported_platform": sys.platform == "win32",
        "command": executable,
        "installed": bool(executable),
        "node": node,
        "npm": npm,
        "ready": False,
    }
    if node:
        version = run([node, "--version"], timeout=15)
        text = (version.stdout or version.stderr).strip().lstrip("v")
        report["node_version"] = text
        try:
            report["node_supported"] = tuple(int(part) for part in text.split(".")[:3]) >= (18, 0, 0)
        except (TypeError, ValueError):
            report["node_supported"] = False
    else:
        report["node_supported"] = False
    if executable:
        version = run([executable, "--version"], timeout=20)
        report["command_version"] = (version.stdout or version.stderr).strip()[:120]
        report["command_works"] = version.returncode == 0
    else:
        report["command_works"] = False
    report["ready"] = bool(
        report["supported_platform"]
        and report["installed"]
        and report["command_works"]
        and report["node_supported"]
        and npm
    )
    return report


def install(provider: str) -> dict[str, Any]:
    if sys.platform != "win32":
        raise RuntimeError("自动安装微信导出器当前仅支持 Windows")
    npm = command_path("npm")
    node = command_path("node")
    if not node or not npm:
        raise RuntimeError("未找到 Node.js/npm；请先安装 Node.js 18 或更高版本")
    node_status = status(provider)
    if not node_status.get("node_supported"):
        raise RuntimeError(f"Node.js 版本不满足 {provider} 的要求")
    result = run([npm, "install", "-g", PROVIDERS[provider]["package"]], timeout=900)
    if result.returncode != 0:
        detail = (result.stderr or result.stdout or "npm 安装失败").strip()[-800:]
        raise RuntimeError(detail)
    return status(provider)


def main() -> None:
    parser = argparse.ArgumentParser(description="检查或安装 Windows 微信 JSON 导出工具")
    parser.add_argument("--provider", choices=["auto", *ORDER], default="auto")
    parser.add_argument("--install", action="store_true")
    args = parser.parse_args()
    order = list(ORDER) if args.provider == "auto" else [args.provider]
    attempts = []
    for provider in order:
        try:
            report = status(provider)
            if args.install and not report["ready"]:
                report = install(provider)
                report["changed"] = True
            else:
                report["changed"] = False
            attempts.append(report)
            if report["ready"]:
                print(json.dumps({**report, "attempts": attempts}, ensure_ascii=False, indent=2))
                return
        except (OSError, RuntimeError, subprocess.SubprocessError) as exc:
            attempts.append({"provider": provider, "status": "error", "error": str(exc)})
    print(json.dumps({
        "status": "error",
        "error": "没有可用的微信导出器",
        "attempts": attempts,
    }, ensure_ascii=False, indent=2))
    raise SystemExit(1)


if __name__ == "__main__":
    main()
