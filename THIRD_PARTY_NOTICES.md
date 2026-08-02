# Third-Party Notices

OverDrive is distributed under the MIT License (see `LICENSE`). The application
**bundles and redistributes** several third-party programs as native executables under
`lib/arm64-v8a/` in the APK (they are packaged as `lib*.so` but are standalone binaries,
run as separate processes — see `app/tunnel-binaries.lock`). Redistributing them carries
obligations that MIT does not, and this file exists to meet them.

> **⚠️ sing-box is licensed under the GNU GPL v3.** Bundling a GPL-3 binary into this
> otherwise-MIT application has consequences the other three do not — see the dedicated
> section below. This file records the attribution required for redistribution; it does
> **not** by itself resolve the GPL-3 compatibility question.

| Component | Upstream | License |
|---|---|---|
| tailscale (`libtailscale.so`) | tailscale/tailscale | BSD-3-Clause |
| cloudflared (`libcloudflared.so`) | cloudflare/cloudflared | Apache-2.0 |
| zrok (`libzrok.so`) | openziti/zrok | Apache-2.0 |
| sing-box (`libsingbox.so`) | sagernet/sing-box | **GPL-3.0-or-later** |

Exact upstream versions, commits and digests for each are pinned in
`app/tunnel-binaries.lock`.

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

---

## cloudflared — Apache License 2.0

Copyright Cloudflare, Inc. Licensed under the Apache License, Version 2.0. The full
license text is reproduced in `LICENSES/Apache-2.0.txt`. OverDrive redistributes the
official upstream `cloudflared-linux-arm64` release binary unmodified (aside from UPX
packing and stripping — see `app/tunnel-binaries.lock`); no source changes are made.

## zrok — Apache License 2.0

Copyright NetFoundry, Inc. and the OpenZiti project. Licensed under the Apache License,
Version 2.0 (full text in `LICENSES/Apache-2.0.txt`). OverDrive redistributes the official
upstream `zrok_*_linux_arm64` release binary unmodified (aside from UPX packing and
stripping).

---

## sing-box — GNU General Public License v3.0-or-later

Copyright (C) 2022 by nekohasekai <contact-sagernet@sekai.icu>

sing-box is Free Software under the GPL v3 (or later). The full license text is in
`LICENSES/GPL-3.0.txt`. Per §4 of the sing-box license, no derivative work may use its
name or imply association without prior consent.

**This is a redistribution-compliance record, not a resolution.** The GPL is copyleft:
conveying a GPL-3 binary as part of a larger work has requirements — including a written
offer of the binary's *corresponding source* and constraints on the license of the
conveyed whole — that are in tension with this application's MIT license and its shipped
APK. That question is tracked upstream and must be resolved by the project, not papered
over by this file. Corresponding source for the exact binary shipped:
https://github.com/sagernet/sing-box (tag pinned in `app/tunnel-binaries.lock`).
