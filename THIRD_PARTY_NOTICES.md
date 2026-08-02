# Third-Party Notices

Wheelstop is distributed under the MIT License (see `LICENSE`). The application
**bundles and redistributes** several third-party programs as native executables under
`lib/arm64-v8a/` in the APK. They are packaged as `lib*.so` but are standalone binaries,
**run as separate processes** (see `app/tunnel-binaries.lock` and the `*Launcher` classes) —
not linked into the app's own process. Redistributing them carries obligations that MIT does
not, and this file exists to meet them.

| Component | Upstream | Version | License |
|---|---|---|---|
| tailscale (`libtailscale.so`) | tailscale/tailscale | v1.98.10 | BSD-3-Clause |
| cloudflared (`libcloudflared.so`) | cloudflare/cloudflared | 2026.7.3 | Apache-2.0 |
| zrok (`libzrok.so`) | openziti/zrok | v1.1.11 | Apache-2.0 |
| sing-box (`libsingbox.so`) | sagernet/sing-box | **v1.12.14** | **GPL-3.0-or-later** |

Exact upstream versions, commits and digests for each are pinned in
`app/tunnel-binaries.lock`. This notice is published as an asset on every Wheelstop release,
alongside the APK it describes.

---

## tailscale — BSD-3-Clause

Copyright (c) 2020 Tailscale Inc & contributors.

```
BSD 3-Clause License

Copyright (c) 2020 Tailscale Inc & contributors.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this
   list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.

3. Neither the name of the copyright holder nor the names of its
   contributors may be used to endorse or promote products derived from
   this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

Tailscale is redistributed as the unmodified upstream build (aside from UPX packing and
stripping — see `app/tunnel-binaries.lock`). BSD-3-Clause requires only this notice.

---

## cloudflared — Apache License 2.0

Copyright Cloudflare, Inc. Licensed under the Apache License, Version 2.0. The full
license text is in `LICENSES/Apache-2.0.txt`. Wheelstop redistributes the official upstream
`cloudflared-linux-arm64` release binary unmodified (aside from UPX packing and stripping);
no source changes are made.

## zrok — Apache License 2.0

Copyright NetFoundry, Inc. and the OpenZiti project. Licensed under the Apache License,
Version 2.0 (full text in `LICENSES/Apache-2.0.txt`). Wheelstop redistributes the official
upstream `zrok_*_linux_arm64` release binary unmodified (aside from UPX packing and stripping).

---

## sing-box — GNU General Public License v3.0-or-later

Copyright (C) 2022 by nekohasekai <contact-sagernet@sekai.icu> and the sing-box contributors.
The full license text is in `LICENSES/GPL-3.0.txt`. Per §4 of sing-box's terms, no derivative
work may use its name or imply association without prior consent — Wheelstop does neither.

**What we ship, and why the MIT app is unaffected.** `libsingbox.so` is the *unmodified*
upstream `sing-box-1.12.14-android-arm64` release binary (only UPX-packed and stripped for
packaging — see `app/tunnel-binaries.lock`; no source is changed). It is executed as a
**separate process** through a command-line / socket interface, never linked into Wheelstop's
own process. That is mere aggregation under GPLv3 §5, so it does **not** make Wheelstop a
derivative work and does **not** relicense Wheelstop's MIT code. The one obligation it does
create is to convey sing-box's *Corresponding Source* — which the following offer satisfies.

### Written offer of Corresponding Source (GPLv3 §6)

The Corresponding Source for the exact sing-box binary Wheelstop distributes —
**v1.12.14**, upstream commit `f56d9ab94569d46a48bfc2bcd97eea566ad365c8` — is made available,
at no charge, from the same place as the object code:

- **Mirror (authoritative for this project):** the file
  `sing-box-1.12.14-corresponding-source.tar.gz`, attached to every Wheelstop release
  (including the rolling `alpha` release) next to the APK.
  `sha256 = f19761d09f88e2d33aadfdb3c4ff471654f34b28561826e4786b9859654ca887`
- **Upstream source:** <https://github.com/SagerNet/sing-box/tree/v1.12.14>
- **Upstream binary we redistribute:** <https://github.com/SagerNet/sing-box/releases/tag/v1.12.14>

The packaging steps applied to the binary (UPX pack, then `llvm-strip`) and their exact
invocations are recorded in `app/tunnel-binaries.lock`, which also lets you re-derive the
shipped bytes from the readable audit artifact.

This is a standing written offer, valid for at least three years from the distribution of any
release containing this binary: on request via the project's issue tracker
(<https://github.com/wheelstop-app/wheelstop/issues>) we will provide the Corresponding Source
by any means permitted under GPLv3 §6. When the pinned sing-box version changes, the mirror
and this notice are updated in the same commit.
