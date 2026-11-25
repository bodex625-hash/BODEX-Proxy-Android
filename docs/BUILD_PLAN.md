# Build plan

1. Verify Proxy-Seller API v2 support for the exact residential rotating GB product, order creation, credentials, and usage reporting.
2. Implement backend authentication, order state machine, payment verification, supplier purchase, and usage ledger.
3. Implement Android account and package screens.
4. Integrate a real Android VpnService tunnel backed by a SOCKS5 engine supporting UDP ASSOCIATE.
5. Test TCP and UDP end-to-end through the actual supplier endpoint and tunnel on physical Android devices.
6. Add abuse controls, rate limits, redacted logging, and monitoring.
7. Build a signed release APK only after acceptance tests pass.

## Acceptance tests
- Unconfirmed payment never creates a supplier order or user credit.
- Each paid order is idempotent and cannot be fulfilled twice.
- SOCKS5 TCP and UDP pass end-to-end tests.
- Usage is measured and reconciled with supplier reporting.
- API secrets are absent from APK, logs, and client responses.
- VPN disconnect restores normal device routing.
