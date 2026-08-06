#!/usr/bin/env python3
"""Fail CI when the versioned Docker Compose infrastructure violates its baseline."""

import argparse
import json
from pathlib import Path


REQUIRED_SERVICES = {"mysql", "redis", "kafka", "es", "minio", "ocr-service"}
HEALTHCHECK_SERVICES = {"mysql", "redis", "kafka", "es", "ocr-service"}
PERSISTENT_SERVICES = {"mysql", "redis", "es", "minio", "ocr-service"}
LOCAL_ONLY_PORT_SERVICES = {"mysql", "redis", "kafka", "es", "minio", "ocr-service"}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--compose", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()

    raw_compose = Path(args.compose).read_bytes()
    try:
        compose_text = raw_compose.decode("utf-8-sig")
    except UnicodeDecodeError:
        # PowerShell's redirection may emit UTF-16, while GitHub's Bash emits UTF-8.
        compose_text = raw_compose.decode("utf-16")
    compose = json.loads(compose_text)
    services = compose.get("services", {})
    failures: list[str] = []
    checks: list[str] = []

    missing = sorted(REQUIRED_SERVICES - services.keys())
    if missing:
        failures.append(f"Missing required services: {', '.join(missing)}")
    else:
        checks.append("All required infrastructure services are declared.")

    for name in sorted(HEALTHCHECK_SERVICES):
        if name in services and not services[name].get("healthcheck"):
            failures.append(f"{name} must define a healthcheck.")
    if not any("healthcheck" in item.lower() for item in failures):
        checks.append("Required runtime services define health checks.")

    for name in sorted(PERSISTENT_SERVICES):
        service = services.get(name, {})
        if name in services and not any(volume.get("type") == "volume" for volume in service.get("volumes", [])):
            failures.append(f"{name} must use a named Docker volume for persistent state.")
    if not any("named Docker volume" in item for item in failures):
        checks.append("Stateful services use named Docker volumes.")

    for name in sorted(LOCAL_ONLY_PORT_SERVICES):
        for port in services.get(name, {}).get("ports", []):
            if port.get("host_ip") != "127.0.0.1":
                failures.append(f"{name} publishes {port.get('published')} without a 127.0.0.1 host binding.")
    if not any("host binding" in item for item in failures):
        checks.append("Infrastructure ports are bound to loopback only.")

    report = ["# IaC and container policy report", "", "## Passed checks"]
    report.extend(f"- {item}" for item in checks)
    report.extend(["", "## Violations"])
    report.extend(f"- {item}" for item in failures) if failures else report.append("- None")
    Path(args.output).write_text("\n".join(report) + "\n", encoding="utf-8")

    if failures:
        print("\n".join(failures))
        return 1
    print("IaC and container policy checks passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
