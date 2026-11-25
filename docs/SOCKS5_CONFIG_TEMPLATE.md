# SOCKS5 tunnel configuration template

This is a template for the native HevSocks5Tunnel engine. It contains placeholders only.
Never commit real proxy credentials.

```yaml
tunnel:
  name: tun0
  mtu: 1500
  ipv4: 198.18.0.1
  ipv6: 'fc00::1'
  icmp: 'off'

socks5:
  address: PROXY_HOST
  port: 1080
  udp: 'udp'
  username: PROXY_USERNAME
  password: PROXY_PASSWORD

misc:
  log-level: warn
```

## Android JNI contract

The upstream Android AAR documents this Java binding:
- Class: `hev.htproxy.TProxyService`
- `TProxyStartService(String configPath, int fd)`
- `TProxyStopService()`
- `TProxyIsRunning()`
- `TProxyGetStats()`

The AAR bundles the JNI class and native library. Confirm the exact artifact and version used by the build before wiring these calls into the app.

## VpnService lifecycle
1. Obtain user consent with `VpnService.prepare()`.
2. Build the TUN interface with `VpnService.Builder`.
3. Establish the interface and obtain its file descriptor.
4. Write a private config file into app-internal storage with restrictive permissions.
5. Start the native engine with the config path and TUN file descriptor.
6. Exclude the upstream SOCKS5 endpoint from VPN routes to prevent a routing loop.
7. Keep the service foreground while connected.
8. On disconnect, stop the engine first, close the TUN interface, remove temporary config, and stop foreground state.

Do not route user traffic until native startup succeeds and the engine reports running. If startup fails, tear down the interface and leave normal connectivity intact.

## UDP validation
- Confirm the server supports RFC 1928 UDP ASSOCIATE.
- Test both UDP relay modes only if supported by the supplier.
- Validate DNS, QUIC/HTTP3 datagrams, and a controlled UDP echo test through the tunnel.
- Record packet loss and usage counters.
- Do not display a UDP-supported status until real endpoint tests pass.
