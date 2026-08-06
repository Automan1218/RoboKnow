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
store the raw approved report at one of these versioned paths:

```text
security/baselines/trivy-backend-image.sarif
security/baselines/trivy-frontend-fs.sarif
security/baselines/zap-report.json
```

The next scan then reports resolved findings as concrete closure evidence. Do
not replace an approved baseline without a review commit.

## Screenshot checklist for assessment packs

Use the same commit SHA in every screenshot:

1. GitHub Actions CI graph with all required jobs green.
2. JaCoCo HTML coverage report and Surefire test summary from the downloaded artifact.
3. Integration test report and Elasticsearch/MinIO logs artifact.
4. Checkstyle, SpotBugs, Trivy, and OWASP ZAP report summaries.
5. SAST and DAST remediation-delta report before and after a fix.
6. CD deployment summary, DAST job result, `docker ps -a`, and container health state.
7. k6 JSON summary/terminal output for the authorized performance run.

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

The CI backend image is built as `roboknow-backend:<commit-sha>` and scanned by
Trivy. OCR is built on the EC2 host from the versioned Dockerfile; persistent
models live in the named `ocr-data` volume. A registry such as GHCR should be
configured before claiming centralized image distribution or retention.

## Compliance mapping

The controls provide implementation evidence for OWASP ASVS/Top 10 (SAST,
DAST, authentication and headers), CIS Docker Benchmark themes (loopback-only
infrastructure ports, volumes, health checks and image scanning), and common
change/audit requirements from ISO 27001 or MLPS 2.0. This is an evidence map,
not a claim of certification.
