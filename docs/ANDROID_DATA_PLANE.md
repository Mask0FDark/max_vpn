# Android data plane — experimental

Architecture: Android VpnService IPv4 TUN -> hev-socks5-tunnel 2.18.0 rebuilt for Android API 24 -> localhost SOCKS5 TCP CONNECT / UDP ASSOCIATE -> protected TLS WebSocket -> VPS Nginx relay -> public Internet.

This is a DIRECT HTTPS-to-VPS transport, NOT a transport using MAX messages. The MAX WebView login in the Android app is not connected to this data plane. No Android device has yet confirmed live DNS and browser connectivity through this tunnel.

An unshared random pairing key of 40+ characters is required by the VPS relay. Public APKs and the public repo contain no access key. The app has an optional advanced screen to store the personal key encrypted with Android Keystore (AES-GCM). The app checks an authenticated WebSocket before starting TUN to avoid blackholing the device's internet. Do NOT embed a private token in public release assets.

The VPS relay allows at most 20 concurrent connections with a ten-minute lifetime, enforces public destination IP addresses, and denies all connections when not paired. These limits are preliminary and not a security audit.

Limitations: no live Android end-to-end test yet; Android 7 emulator smoke tests are being prepared for the native library and Android Keystore. UDP/DNS support needs device verification. App direct-VPS mode does not work during MAX-only network allowlists. The MAX chat transport is separately verified on PC, but not connected to Android.

Native library: HevSocks5Tunnel 2.18.0 (MIT), compiled with Android NDK for Android 7 (API 24) and packaged as android/app/libs/hev-socks5-tunnel.aar with all four ABIs. See HEV-LICENSE.txt. No shared production APK signing key has been configured.
