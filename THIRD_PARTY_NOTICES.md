# Third-party notices

This project builds on the work of others. The notices below are reproduced
as required by their licences.

---

## Remote for Apple TV

Source: https://github.com/msthind04/AppleTV-Remote

The Companion Link and AirPlay/MRP protocol implementation under
`app/src/main/java/com/example/appletvremote/protocol/` is derived from this
project (packages renamed):

- Companion Link framing, encryption and request/response handling
- HAP pair-setup / pair-verify (SRP-6a, TLV8, HKDF, Ed25519, X25519)
- OPACK and binary plist codecs
- RTI (remote text input) payloads and the high-level remote API
- AirPlay 2 control/data/event channels and HAP stream encryption
- MRP message encoding and now-playing metadata parsing
- Pointer-ballistics gain curve for the touch surface

Copyright 2026 The Remote for Apple TV contributors.

Licensed under the Apache License, Version 2.0. The full licence text is in
the `LICENSE` file.

---

## pyatv

Source: https://github.com/postlund/pyatv

Parts of the protocol work were verified against pyatv, a Python library for
controlling Apple TV devices, including:

- Companion Link frame types and OPACK type tags
- HomeKit pairing HKDF salt/info strings and ChaCha20 nonce labels
- RTI `NSKeyedArchiver` payload structure

MIT License

Copyright (c) 2020 Pierre Ståhl

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

---

## Runtime dependencies

| Component | Licence |
|---|---|
| Bouncy Castle (`bcprov-jdk18on`) | Bouncy Castle Licence (MIT-style) |
| Kotlin standard library, kotlinx.coroutines | Apache License 2.0 |
| AndroidX / Jetpack Compose | Apache License 2.0 |

---

## Trademarks

This project is not affiliated with, authorized by, endorsed by, or in any way
officially connected with Apple Inc. "Apple", "Apple TV", "AirPlay" and "Siri"
are trademarks of Apple Inc., registered in the U.S. and other countries.
They are used here solely to describe compatibility.
