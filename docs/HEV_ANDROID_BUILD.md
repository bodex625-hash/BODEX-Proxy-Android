# HevSocks5Tunnel Android native engine

## Upstream and pin

- Upstream: https://github.com/heiher/hev-socks5-tunnel
- Pinned upstream commit: `867e1e59dcf30ed75c0ee840991e441539fafb92`
- Upstream Android JNI declaration: `android/hev/htproxy/TProxyService.java`
- Upstream JNI library name: `hev-socks5-tunnel`

The upstream README documents Android compilation through the Android NDK:

```sh
git clone --recursive https://github.com/heiher/hev-socks5-tunnel jni
ndk-build
```

This upstream does not provide a verified Maven/AAR dependency in its README. Do not add a made-up Maven coordinate or package a placeholder shared library.

## App-side JNI contract

The app contains a Java declaration at:

`android/app/src/main/java/hev/htproxy/TProxyService.java`

Its native method signatures must stay aligned with the pinned upstream source:

- `TProxyStartService(String configPath, int tunFileDescriptor): boolean`
- `TProxyStopService(): boolean`
- `TProxyIsRunning(): boolean`
- `TProxyGetStats(): long[]`

The native output must be packaged under the matching Android ABI directory, for example:

- `android/app/src/main/jniLibs/arm64-v8a/libhev-socks5-tunnel.so`
- `android/app/src/main/jniLibs/armeabi-v7a/libhev-socks5-tunnel.so`

Do not call the JNI methods until the matching shared library is packaged. Otherwise class initialization will fail with `UnsatisfiedLinkError`.

## Required integration sequence

1. Build upstream at the pinned commit with its recursive submodules and a compatible Android NDK.
2. Verify the produced shared library ABI with Android NDK tooling.
3. Package the shared library for each supported ABI in `app/src/main/jniLibs/<abi>/`.
4. Add the upstream Java JNI declaration (or keep the app-side matching declaration) without changing method names or signatures.
5. Implement `VpnService.Builder.establish()`, retain the returned TUN file descriptor, and pass its descriptor to `TProxyStartService`.
6. Generate a private per-session YAML config with the SOCKS5 endpoint and credentials. Never commit credentials or generated configs.
7. Stop the native tunnel before closing the TUN descriptor and clean up on service revocation/destruction.
8. Test TCP, DNS, IPv4/IPv6 routing, reconnect, Android process death, and UDP separately against a SOCKS5 endpoint that explicitly supports UDP ASSOCIATE.

## UDP acceptance criteria

Setting `socks5.udp: 'udp'` only selects the tunnel's UDP relay mode. It does not prove that a provider endpoint supports UDP ASSOCIATE. UDP is accepted only after real end-to-end tests for DNS and representative UDP traffic. If the upstream provider supports TCP only, the app must report UDP as unavailable for that endpoint rather than claim full UDP support.

## Current status

The app-side JNI declaration is committed. The native `.so` files are not yet built or packaged, and the VPN service is not yet connected to this engine. No APK should be described as operational until the native build and device tests pass.

## VPN routing diagnostic update

The Android service excludes the BODEX Proxy package from its own VPN routes so the native SOCKS5 engine can reach the upstream proxy outside the TUN interface. The generated Hev configuration uses YAML line-oriented entries and writes a debug log to the app cache directory. A successful APK build is not proof of a working proxy: verify the device tunnel, upstream authentication, DNS, and TCP/UDP behavior on a real device before release.
