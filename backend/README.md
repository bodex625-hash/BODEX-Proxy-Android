# BODEX Proxy backend — first integration step

This Worker starts with a **read-only** Proxy-Seller connection. It does not place orders, spend balance, or create customer packages.

## Required Cloudflare Worker secrets

Set these under the `bodex-proxy-api` Worker > Settings > Variables and Secrets:

- `ADMIN_TOKEN`: a long random admin-only bearer token.
- `PROXY_SELLER_API_KEY`: the Proxy-Seller API key for the residential proxy product.

Never put either value in GitHub source code, the Android APK, screenshots, or chat messages.

## Endpoint

- `GET /health` — public service health check.
- `GET /admin/seller/status` — requires header `Authorization: Bearer <ADMIN_TOKEN>`. Reads Proxy-Seller account balance and residential package status. It returns only balance and traffic summary; it does not return API keys, package keys, or proxy passwords.

Example request:

```sh
curl -H "Authorization: Bearer YOUR_ADMIN_TOKEN" https://YOUR-WORKER-DOMAIN/admin/seller/status
```

## Next stages

1. Verify the read-only supplier connection.
2. Add customer accounts, package catalogue, and pending manual-payment orders.
3. Add admin approval with idempotent supplier provisioning (buy traffic only when needed, then allocate a subpackage and create a SOCKS5 list).
4. Add customer usage and credentials screens.

All paid supplier actions remain disabled until the read-only connection and account balance are verified.
