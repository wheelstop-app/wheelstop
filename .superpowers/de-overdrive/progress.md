# De-Overdrive cleanup ledger (branch cleanup/de-overdrive)
PRESERVE (never touch): overdrive_config(.json/.bak), overdrive_prefs, overdrive-byd-cred-v1,
  overdrive-config-bundle, .overdrive_device_id, .overdrive_pin_reset, .overdrive/audio,
  overdrive/models, any /data/local/tmp/*overdrive* path, zrok uniqueName/prefix "overdrive",
  security-audit/ (historical).
Task A (Android resources): DONE 00bbe773 — Theme.Overdrive/Widget.Overdrive/overdrive_status_* +
  sibling style families -> Wheelstop; themes_overdrive.xml -> themes_wheelstop.xml. Also renamed
  NotificationChannel id "overdrive_status_summary" -> wheelstop (harmless: fresh package). 0 residual.
Task B (JS globals/events/keys): DONE 4ba15a80 — OverdriveAppShell->Wheelstop, OverdrivePush->Wheelstop,
  overdrive:vehicle-changed->wheelstop:, overdrive.* localStorage keys->wheelstop. 0 residual for those.
  Follow-up in flight: OverdriveEvCard3D/EvSpriteCache/DisableEvCard3D + data-overdrive-* attrs.
Remaining passes: C = recording folder (DCIM/Overdrive, emulated/0/Overdrive, i18n "saved to Overdrive/…")
  -> Wheelstop (NOT /data/local/tmp paths). D = comment prose "OverDrive/Overdrive" -> Wheelstop (excl
  preserved-token lines) + HA discovery origin.name + geocoding User-Agent.
Then: build gate + whole-branch review + PR.
Task B follow-up: DONE a73b7104 — OverdriveEvCard3D/EvSpriteCache/DisableEvCard3D + __overdriveEvCard3d* + data-overdrive-* -> Wheelstop. All JS identifiers clean.
