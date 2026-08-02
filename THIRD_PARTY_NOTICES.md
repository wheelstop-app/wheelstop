# Third-Party Notices

<!-- GENERATED from app/tunnel-binaries.lock by scripts/gen-third-party-notices.py.
     Do not edit by hand — change the lock and regenerate. CI enforces this. -->

Wheelstop is distributed under the MIT License (see `LICENSE`). The application
**bundles and redistributes** several third-party programs as native executables under
`lib/arm64-v8a/` in the APK. They are packaged as `lib*.so` but are standalone binaries,
**run as separate processes** (see `app/tunnel-binaries.lock` and the `*Launcher` classes) —
not linked into the app's own process. Redistributing them carries obligations that MIT
does not, and this file exists to meet them.

| Component | Upstream | Version | License |
|---|---|---|---|
| tailscale (`libtailscale.so`) | tailscale/tailscale | v1.98.10 | BSD-3-Clause |
| cloudflared (`libcloudflared.so`) | cloudflare/cloudflared | 2026.7.3 | Apache-2.0 |
| zrok (`libzrok.so`) | openziti/zrok | v1.1.11 | Apache-2.0 |
| sing-box (`libsingbox.so`) | sagernet/sing-box | **v1.12.14** | **GPL-3.0-or-later** |

Exact versions, commits and digests are pinned in `app/tunnel-binaries.lock`. This
notice is generated from that file and published as an asset on every Wheelstop release,
alongside the APK it describes.

---

## tailscale — BSD-3-Clause

Built from upstream source at `github.com/tailscale/tailscale` tag **v1.98.10** (commit `36550d57f4a4055246ef7412f4e650a012a465f1`), then
UPX-packed and stripped for packaging (see `app/tunnel-binaries.lock`); no source is
changed.

Full licence text: `LICENSES/BSD-3-Clause.txt`. Redistribution requires only this attribution.

---

## cloudflared — Apache-2.0

The *unmodified* upstream `github.com/cloudflare/cloudflared` **2026.7.3** release binary (only UPX-packed and
stripped for packaging — see `app/tunnel-binaries.lock`; no source is changed).

Full licence text: `LICENSES/Apache-2.0.txt`. Redistribution requires only this attribution.

---

## zrok — Apache-2.0

The *unmodified* upstream `github.com/openziti/zrok` **v1.1.11** release binary (only UPX-packed and
stripped for packaging — see `app/tunnel-binaries.lock`; no source is changed).

Full licence text: `LICENSES/Apache-2.0.txt`. Redistribution requires only this attribution.

---

## sing-box — GPL-3.0-or-later

The *unmodified* upstream `github.com/sagernet/sing-box` **v1.12.14** release binary (only UPX-packed and
stripped for packaging — see `app/tunnel-binaries.lock`; no source is changed).

Full licence text: `LICENSES/GPL-3.0.txt` (and `LICENSES/sing-box.LICENSE` for the §4 naming
clause). It runs as a **separate process** through a CLI/socket interface, never linked
into Wheelstop's process — mere aggregation under GPLv3 §5 — so it does **not** make
Wheelstop a derivative work or relicense its MIT code. The one obligation it creates is
to convey its *Corresponding Source*, which the following offer satisfies.

### Written offer of Corresponding Source (GPLv3 §6)

The Corresponding Source for the exact binary Wheelstop distributes — **v1.12.14**,
upstream commit `f56d9ab94569d46a48bfc2bcd97eea566ad365c8` — is provided, at no charge, from the same place as the
object code:

- **Mirror (authoritative):** `sing-box-1.12.14-corresponding-source.tar.gz`, attached to every Wheelstop release
  (including the rolling `alpha` release) next to the APK.
  `sha256 = f19761d09f88e2d33aadfdb3c4ff471654f34b28561826e4786b9859654ca887`
- **Upstream source:** <https://github.com/sagernet/sing-box/tree/v1.12.14>
- **Upstream binary redistributed:** <https://github.com/sagernet/sing-box/releases/tag/v1.12.14>

The packaging steps applied to the binary (UPX pack, then `llvm-strip`) are recorded in
`app/tunnel-binaries.lock`, which also lets you re-derive the shipped bytes from the
readable audit artifact. This is a standing written offer, valid for at least three
years from the distribution of any release containing this binary: on request via the
issue tracker (<https://github.com/wheelstop-app/wheelstop/issues>) we will provide the
Corresponding Source by any means permitted under GPLv3 §6.
