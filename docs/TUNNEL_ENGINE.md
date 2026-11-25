# Android tunnel engine decision

## Selected engine
Use **HevSocks5Tunnel** through its Android AAR/JNI binding, integrated behind Android's `VpnService`.

Upstream:
- https://github.com/heiher/hev-socks5-tunnel
- Android app example: https://github.com/heiher/sockstun

The upstream project documents IPv4/IPv6, TCP redirection, UDP redirection, and Android support. Its UDP modes include UDP-in-UDP and UDP-in-TCP. The upstream Android AAR exposes start, stop, running-state, and traffic-statistics JNI methods.

## Integration requirements
1. Pin a reviewed upstream release; do not depend on an unpinned snapshot.
2. Confirm the AAR package, native ABIs, JNI class/method signatures, and license before integration.
3. Build for the Android ABIs supported by the release and test on physical devices.
4. Create the TUN interface using Android `VpnService.Builder`; pass its file descriptor to the engine.
5. Configure the SOCKS5 endpoint, port, username/password, DNS, and UDP mode without logging credentials.
6. Exclude the proxy server endpoint from VPN routing to avoid a tunnel loop.
7. Implement foreground-service lifecycle, reconnect handling, and safe teardown.
8. Read engine traffic statistics and reconcile with server-side GB accounting.

## Critical compatibility gate
A provider saying that its residential SOCKS5 product supports UDP is not sufficient. Before release, test the actual purchased endpoint with SOCKS5 `UDP ASSOCIATE` and validate TCP and UDP end-to-end through the Android tunnel. If the provider's UDP relay address is unreachable or the endpoint does not implement UDP ASSOCIATE, the app must report that honestly rather than claiming UDP support.

## Current project status
This is an engineering selection and integration plan, not an implemented tunnel. The current Kotlin `ProxyVpnService` remains intentionally inert until the native engine is integrated and tested.
