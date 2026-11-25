# Security requirements

- Keep Proxy-Seller credentials and payment secrets only in backend secret storage.
- Never put secrets in Android resources, BuildConfig, source code, APK assets, or GitHub.
- Hash passwords server-side with a modern password-hashing algorithm; never store plaintext.
- Use TLS for all client/backend communication.
- Verify Binance payments server-side before crediting accounts.
- Make order fulfillment idempotent and audit state transitions.
- Redact credentials, tokens, and payment identifiers from logs.
- Do not collect device model or Android version unless strictly required and disclosed.
