## Agent skills

### Issue tracker

Issues and PRDs are tracked in GitHub Issues. See `docs/agents/issue-tracker.md`.

### Triage labels

Use the default five-label triage vocabulary. See `docs/agents/triage-labels.md`.

### Domain docs

This is a single-context repository. See `docs/agents/domain.md`.

## Test Environment

In PowerShell, start the test server with HTTP explicitly enabled:

```powershell
$env:PORTFOLIO_HELPER_PORT='9093'
$env:PORTFOLIO_HELPER_HTTP_PORT='9090'
.\gradlew.bat run --args='--http'
```

Open `http://localhost:9090`. HTTPS remains available on port 9093, and sync endpoints require HTTPS. Without `--http`, port 9090 redirects to HTTPS.

### Verifying fixes on the test server

Frontend changes on port 9090 require a full server restart; reloading the browser is
not enough. To verify a fix:

1. Stop and restart the server using the command above.
2. Reload `http://localhost:9090` and reproduce the bug.
