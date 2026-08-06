#!/usr/bin/env python3
"""Create auditable discovered -> fixed -> rescanned deltas for SARIF and ZAP JSON reports."""

import argparse
import json
from pathlib import Path


def read_json(path: str | None) -> dict:
    if not path or not Path(path).is_file():
        return {}
    return json.loads(Path(path).read_text(encoding="utf-8-sig"))


def sarif_findings(report: dict) -> dict[str, dict]:
    findings = {}
    for run in report.get("runs", []):
        for result in run.get("results", []):
            location = (result.get("locations") or [{}])[0].get("physicalLocation", {})
            artifact = location.get("artifactLocation", {}).get("uri", "")
            line = location.get("region", {}).get("startLine", "")
            rule = result.get("ruleId", "unknown-rule")
            key = f"{rule}|{artifact}|{line}"
            findings[key] = {
                "severity": result.get("level", "unknown"),
                "title": result.get("message", {}).get("text", rule),
            }
    return findings


def zap_findings(report: dict) -> dict[str, dict]:
    findings = {}
    for site in report.get("site", []):
        for alert in site.get("alerts", []):
            plugin = str(alert.get("pluginid", alert.get("alertRef", "unknown-plugin")))
            risk = str(alert.get("riskcode", alert.get("risk", "unknown")))
            instances = alert.get("instances") or [{}]
            for instance in instances:
                uri = instance.get("uri", site.get("@name", ""))
                method = instance.get("method", "")
                key = f"{plugin}|{method}|{uri}"
                findings[key] = {"severity": risk, "title": alert.get("alert", plugin)}
    return findings


def is_high(finding: dict, report_format: str) -> bool:
    severity = str(finding.get("severity", "")).lower()
    if report_format == "zap":
        return severity in {"3", "high", "critical"}
    return severity in {"error", "high", "critical"}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--format", choices=("sarif", "zap"), required=True)
    parser.add_argument("--current", required=True)
    parser.add_argument("--baseline")
    parser.add_argument("--output", required=True)
    parser.add_argument("--output-json")
    parser.add_argument("--fail-on-high", action="store_true")
    args = parser.parse_args()

    current_report = read_json(args.current)
    baseline_exists = bool(args.baseline and Path(args.baseline).is_file())
    extractor = sarif_findings if args.format == "sarif" else zap_findings
    current = extractor(current_report)
    baseline = extractor(read_json(args.baseline))

    current_keys, baseline_keys = set(current), set(baseline)
    new = sorted(current_keys - baseline_keys)
    resolved = sorted(baseline_keys - current_keys)
    persistent = sorted(current_keys & baseline_keys)
    high = sorted(key for key in current if is_high(current[key], args.format))

    lines = [
        f"# {args.format.upper()} remediation delta",
        "",
        f"- Current report: `{args.current}`",
        f"- Baseline report: `{args.baseline}`" if baseline_exists else "- Baseline report: not yet approved (all current findings are treated as new).",
        f"- New findings: {len(new)}",
        f"- Resolved findings: {len(resolved)}",
        f"- Persistent findings: {len(persistent)}",
        f"- Current high/critical findings: {len(high)}",
    ]
    for heading, keys, source in (("New findings", new, current), ("Resolved findings", resolved, baseline), ("Persistent findings", persistent, current)):
        lines.extend(["", f"## {heading}"])
        if keys:
            lines.extend(f"- [{source[key]['severity']}] {source[key]['title']} (`{key}`)" for key in keys[:100])
        else:
            lines.append("- None")

    result = {
        "format": args.format,
        "baseline_present": baseline_exists,
        "new": len(new),
        "resolved": len(resolved),
        "persistent": len(persistent),
        "high_or_critical": len(high),
    }
    Path(args.output).write_text("\n".join(lines) + "\n", encoding="utf-8")
    if args.output_json:
        Path(args.output_json).write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")

    if not current_report:
        print(f"Current {args.format} report is missing or invalid: {args.current}")
        return 1
    if args.fail_on_high and high:
        print(f"High/critical {args.format} findings detected: {len(high)}")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
