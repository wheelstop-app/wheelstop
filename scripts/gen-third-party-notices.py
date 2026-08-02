#!/usr/bin/env python3
"""Generate THIRD_PARTY_NOTICES.md from app/tunnel-binaries.lock.

The lock is the single source of truth for the vendored tunnel binaries' versions,
licences and (for GPL components) corresponding-source provenance. This script renders
the notice from it so a version bump in one place propagates to the notice — including
the sing-box GPLv3 written offer.

Usage:
  scripts/gen-third-party-notices.py           # write THIRD_PARTY_NOTICES.md
  scripts/gen-third-party-notices.py --check    # exit 1 if the committed file is stale
"""
import sys
import pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
LOCK = ROOT / "app" / "tunnel-binaries.lock"
OUT = ROOT / "THIRD_PARTY_NOTICES.md"

# lib stem -> (display name, so-name, LICENSE file in LICENSES/)
COMPONENTS = [
    ("libtailscale", "tailscale", "libtailscale.so", "LICENSES/BSD-3-Clause.txt"),
    ("libcloudflared", "cloudflared", "libcloudflared.so", "LICENSES/Apache-2.0.txt"),
    ("libzrok", "zrok", "libzrok.so", "LICENSES/Apache-2.0.txt"),
    ("libsingbox", "sing-box", "libsingbox.so", "LICENSES/GPL-3.0.txt"),
]


def read_lock():
    d = {}
    for line in LOCK.read_text().splitlines():
        line = line.split("#", 1)[0].strip()
        if "=" in line:
            k, v = line.split("=", 1)
            d[k.strip()] = v.strip()
    return d


def upstream(lock, stem):
    """Parse `<kind> <repo> <version> [asset|commit] ...` from `<stem>.upstream`."""
    parts = lock[f"{stem}.upstream"].split()
    kind, repo, version = parts[0], parts[1], parts[2]
    extra = parts[3] if len(parts) > 3 else ""
    return kind, repo, version, extra


def render(lock):
    L = []
    a = L.append
    a("# Third-Party Notices")
    a("")
    a("<!-- GENERATED from app/tunnel-binaries.lock by scripts/gen-third-party-notices.py.")
    a("     Do not edit by hand — change the lock and regenerate. CI enforces this. -->")
    a("")
    a("Wheelstop is distributed under the MIT License (see `LICENSE`). The application")
    a("**bundles and redistributes** several third-party programs as native executables under")
    a("`lib/arm64-v8a/` in the APK. They are packaged as `lib*.so` but are standalone binaries,")
    a("**run as separate processes** (see `app/tunnel-binaries.lock` and the `*Launcher` classes) —")
    a("not linked into the app's own process. Redistributing them carries obligations that MIT")
    a("does not, and this file exists to meet them.")
    a("")
    a("| Component | Upstream | Version | License |")
    a("|---|---|---|---|")
    for stem, name, so, _ in COMPONENTS:
        _, repo, version, _e = upstream(lock, stem)
        lic = lock[f"{stem}.license"]
        org = "/".join(repo.split("/")[-2:])
        gpl = lic.startswith("GPL")
        a(f"| {name} (`{so}`) | {org} | {'**'+version+'**' if gpl else version} | {'**'+lic+'**' if gpl else lic} |")
    a("")
    a("Exact versions, commits and digests are pinned in `app/tunnel-binaries.lock`. This")
    a("notice is generated from that file and published as an asset on every Wheelstop release,")
    a("alongside the APK it describes.")
    a("")
    a("---")
    a("")

    for stem, name, so, licfile in COMPONENTS:
        kind, repo, version, extra = upstream(lock, stem)
        lic = lock[f"{stem}.license"]
        a(f"## {name} — {lic}")
        a("")
        if kind == "source":
            a(f"Built from upstream source at `{repo}` tag **{version}** (commit `{extra}`), then")
            a("UPX-packed and stripped for packaging (see `app/tunnel-binaries.lock`); no source is")
            a("changed.")
        else:
            a(f"The *unmodified* upstream `{repo}` **{version}** release binary (only UPX-packed and")
            a("stripped for packaging — see `app/tunnel-binaries.lock`; no source is changed).")
        a("")
        if lic.startswith("GPL"):
            src_commit = lock[f"{stem}.source_commit"]
            src_sha = lock[f"{stem}.source_sha256"]
            org = repo.split("/", 1)[1]  # e.g. sagernet/sing-box
            up_org = "/".join(repo.split("/")[-2:])
            gh_org = up_org  # github path
            src_asset = f"{name}-{version.lstrip('v')}-corresponding-source.tar.gz"
            a(f"Full licence text: `{licfile}` (and `LICENSES/sing-box.LICENSE` for the §4 naming")
            a("clause). It runs as a **separate process** through a CLI/socket interface, never linked")
            a(f"into Wheelstop's process — mere aggregation under GPLv3 §5 — so it does **not** make")
            a("Wheelstop a derivative work or relicense its MIT code. The one obligation it creates is")
            a("to convey its *Corresponding Source*, which the following offer satisfies.")
            a("")
            a("### Written offer of Corresponding Source (GPLv3 §6)")
            a("")
            a(f"The Corresponding Source for the exact binary Wheelstop distributes — **{version}**,")
            a(f"upstream commit `{src_commit}` — is provided, at no charge, from the same place as the")
            a("object code:")
            a("")
            a(f"- **Mirror (authoritative):** `{src_asset}`, attached to every Wheelstop release")
            a("  (including the rolling `alpha` release) next to the APK.")
            a(f"  `sha256 = {src_sha}`")
            a(f"- **Upstream source:** <https://github.com/{gh_org}/tree/{version}>")
            a(f"- **Upstream binary redistributed:** <https://github.com/{gh_org}/releases/tag/{version}>")
            a("")
            a("The packaging steps applied to the binary (UPX pack, then `llvm-strip`) are recorded in")
            a("`app/tunnel-binaries.lock`, which also lets you re-derive the shipped bytes from the")
            a("readable audit artifact. This is a standing written offer, valid for at least three")
            a("years from the distribution of any release containing this binary: on request via the")
            a("issue tracker (<https://github.com/wheelstop-app/wheelstop/issues>) we will provide the")
            a("Corresponding Source by any means permitted under GPLv3 §6.")
        else:
            a(f"Full licence text: `{licfile}`. Redistribution requires only this attribution.")
        a("")
        a("---")
        a("")

    while L and L[-1] in ("", "---"):
        L.pop()
    return "\n".join(L) + "\n"


def main():
    lock = read_lock()
    text = render(lock)
    if "--check" in sys.argv:
        current = OUT.read_text() if OUT.exists() else ""
        if current != text:
            sys.stderr.write(
                "THIRD_PARTY_NOTICES.md is out of date with app/tunnel-binaries.lock.\n"
                "Run: python3 scripts/gen-third-party-notices.py\n"
            )
            sys.exit(1)
        print("THIRD_PARTY_NOTICES.md is up to date.")
    else:
        OUT.write_text(text)
        print(f"wrote {OUT.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
