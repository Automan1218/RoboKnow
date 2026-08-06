# Approved security-report baselines

Place reviewed raw Trivy SARIF and OWASP ZAP JSON reports here only after a
security review. CI/CD compares each new report with the approved baseline and
archives a delta containing new, resolved, and persistent findings.

Expected filenames:

```text
trivy-backend-image.sarif
trivy-frontend-fs.sarif
zap-report.json
```

Never update a baseline to hide an unresolved finding; commit the remediation
first, retain the vulnerable report as an Actions artifact, then approve the
clean rescan with a separate reviewable commit.
