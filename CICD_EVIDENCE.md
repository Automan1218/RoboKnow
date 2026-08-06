# RoboKnow CI/CD security and compliance evidence

This repository treats GitHub Actions outputs as auditable evidence, not as an
unverifiable statement that a test was run. Every Actions run is linked to a
commit SHA and keeps its raw report files as artifacts.

## Required evidence by delivery stage

| Control | Workflow/tool | Evidence artifact | Release gate |
| --- | --- | --- | --- |
| Unit tests and coverage | Maven Surefire + JaCoCo | `backend-test-reports` | Required CI job |
| Integration tests | Maven + MySQL, Redis, Elasticsearch and MinIO | `backend-integration-test-reports`, service logs | Required CI job |
| Frontend quality | pnpm, vue-tsc, ESLint | GitHub Actions job log | Required CI job |
| SAST | Checkstyle, SpotBugs, Trivy | `backend-static-analysis-reports`, `trivy-scan-reports`, `sast-remediation-deltas` | Required CI job; Trivy blocks High/Critical findings |
| IaC/container policy | Docker Compose render + `verify_compose_policy.py` | `iac-compliance-evidence` | Required CI job |
| DAST | OWASP ZAP baseline scan after CD | `dast-reports-<run>` (HTML, JSON, XML and delta) | High/Critical findings block CD |
| Active DAST | OWASP ZAP full scan, manual only | Same DAST artifact | Requires dedicated authorized test target and `DAST_ACTIVE_SCAN_ENABLED=true` |
| Performance/load | k6, manual only | `k6-performance-report-<run>` | Requires authorized target and low-privilege test-account secrets |

## Finding -> fix -> rescan closure

Trivy SARIF and ZAP JSON reports are converted to remediation deltas. The delta
lists new, resolved, and persistent findings; commit SHA and Actions run link
the fix to the subsequent rescan. After an assessment team approves a baseline,
store the raw approved report at one of these versioned paths. Git records who
accepted it and when; the Actions artifact remains the immutable source output
for the same run:

```text
security/baselines/trivy-backend-image.sarif
security/baselines/trivy-frontend-fs.sarif
security/baselines/zap-report.json
```

The next scan then reports resolved findings as concrete closure evidence. Do
not replace an approved baseline without a review commit. A ZAP finding
fingerprint contains its plugin ID, alert title, HTTP method, and URL, so
separate checks from the same plugin (such as individual CSP directives) are
not merged in the remediation totals.

### Recorded DAST remediation cycle

The first approved DAST baseline is the OWASP ZAP JSON report from CD run
[`31069181976`](https://github.com/Automan1218/RoboKnow/actions/runs/31069181976),
artifact `dast-reports-20`, copied to `security/baselines/zap-report.json`.
It recorded 13 alert types and no High/Critical finding. This commit adds the
Nginx response headers that address baseline alerts: CSP (10038),
anti-clickjacking/X-Frame-Options (10020), X-Content-Type-Options (10021),
Permissions-Policy (10063), server version exposure (10036), and the
cross-origin policy header family (90004). The next CD DAST artifact's
`remediation-delta.md` is the required rescan evidence; its `Resolved
findings` section must be screenshot alongside this baseline link.

The first rescan, CD run
[`31076877686`](https://github.com/Automan1218/RoboKnow/actions/runs/31076877686),
completed its DAST gate but truthfully reported 0 resolved and 33 persistent
findings. Its raw response showed the Nginx distribution default page (557
bytes) and `Server: nginx/1.24.0 (Ubuntu)`, proving the deployed virtual host
was not the one exposed to the DAST target. This follow-up change makes the
RoboKnow server the explicit `default_server` and makes CD fail unless all
expected hardening headers are observable on the EC2 host. This failed-closed
verification is retained as evidence rather than relabelling the prior scan as
a successful remediation.

The next CD run, [`31077611908`](https://github.com/Automan1218/RoboKnow/actions/runs/31077611908),
was intentionally stopped by that verification before DAST ran. Its `nginx -T`
evidence identified the exact cause: the stale enabled virtual-host link
`/etc/nginx/sites-enabled/paismart` used the same `server_name _` and routed
requests to `/var/www/paismart` before the RoboKnow host. The deployment now
removes only that confirmed legacy enabled link before Nginx validation. This
gives the presentation a complete trace: baseline finding, attempted fix,
effective-configuration proof, failed-closed gate, targeted correction, and
the next rescan.

Runtime evidence from the successful deployment is also checked against the
Compose port-binding policy. The deploy script now recreates the six managed
infrastructure containers from the versioned Compose file while preserving
their named volumes, then fails if Docker reports a `0.0.0.0` or IPv6 wildcard
published binding. This closes the gap between IaC policy output and the
actual `docker ps` evidence.

The remaining informational findings, and any finding not listed as resolved
by that report, are retained as risk records rather than silently hidden. In
particular, public static assets may remain cacheable and third-party bundle
comments/timestamp strings need source-level review before changing them.

## Screenshot checklist for assessment packs

Use the same commit SHA in every screenshot:

1. GitHub Actions CI graph with all required jobs green.
2. JaCoCo HTML coverage report and Surefire test summary from the downloaded artifact.
3. Integration test report and Elasticsearch/MinIO logs artifact.
4. Checkstyle, SpotBugs, Trivy, and OWASP ZAP report summaries.
5. SAST and DAST remediation-delta report before and after a fix.
6. CD deployment summary, DAST job result, `docker ps -a`, and container health state.
7. k6 JSON summary/terminal output for the authorized performance run.
8. This section, the `zap-report.json` baseline file, and the subsequent
   `remediation-delta.md` with its resolved/persistent totals.

## Container operation evidence

The deployment uses versioned `docs/docker-compose.yaml`, `deploy/deploy.sh`,
`deploy/paismart.service`, and `deploy/nginx.conf` as lightweight IaC. The
resolved Compose artifact records images, ports, health checks, networks, and
named volumes. Operational inspection commands are:

```bash
docker ps -a
docker inspect ocr-service
docker exec -it ocr-service sh
docker logs --tail 200 ocr-service
docker logs -f ocr-service
```

The CI backend image is built as `roboknow-backend:<commit-sha>`, scanned by
Trivy, and on `main` published to GHCR with both the immutable commit-SHA tag
and the moving `main` tag. OCR is built on the EC2 host from the versioned
Dockerfile; persistent models live in the named `ocr-data` volume.

## Compliance mapping

The controls provide implementation evidence for OWASP ASVS/Top 10 (SAST,
DAST, authentication and headers), CIS Docker Benchmark themes (loopback-only
infrastructure ports, volumes, health checks and image scanning), and common
change/audit requirements from ISO 27001 or MLPS 2.0. This is an evidence map,
not a claim of certification.
