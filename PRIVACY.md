# Privacy Policy

**Portfolio Helper** is self-hosted, personal-use software licensed under the
[PolyForm Noncommercial License 1.0.0](LICENSE).

## What this software does not do

- It does not collect, transmit, or store any data on servers operated by the
  developer.
- It does not include analytics, telemetry, crash reporting, or any form of
  usage tracking.
- It does not create user accounts or require registration.

## Data you store locally

All portfolio data (holdings, cash balances, settings) is stored on your own
devices — in flat files (`stocks.csv`, `cash.txt`) and a local SQLite database.
The desktop application stores these in your OS data directory. The Android
client stores its copy in app-private storage on your device.

Sensitive configuration (such as pairing secrets) is encrypted with AES-GCM
before being written to disk.

## Network connections this software makes

1. **Yahoo Finance** — the desktop server and Android client fetch market price
   data from Yahoo Finance's public API. Your ticker symbols are sent as part of
   these requests. Yahoo Finance's own privacy policy governs that data.
2. **Your own desktop server** — the Android app connects to the Portfolio
   Helper server you run on your own machine or local network. No third-party
   relay is involved.
3. **Interactive Brokers TWS** — if you configure a TWS connection, the desktop
   server connects to your local TWS instance. No data leaves your machine via
   this path.

## Android permissions

| Permission | Purpose |
|---|---|
| `INTERNET` | Fetch market data from Yahoo Finance; connect to your desktop server |
| `ACCESS_NETWORK_STATE` / `ACCESS_WIFI_STATE` | Detect network availability |
| `CHANGE_WIFI_MULTICAST_STATE` | mDNS discovery of your desktop server on the local network |
| `POST_NOTIFICATIONS` | Show margin alerts |
| `RECEIVE_BOOT_COMPLETED` | Restart background worker after reboot |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC` | Run sync and margin-check worker in the background |

## Contact

Questions can be filed as issues on the project repository.

> Copyright (c) 2026 KumaTheta
