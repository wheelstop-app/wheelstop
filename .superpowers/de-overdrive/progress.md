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
Task C (recording folder): DONE 58f9e8b9 — write paths (ConfigManager/StorageManager/StorageSetup/EventCommandHandler) -> Wheelstop; RecordingsIndex de-Overdrive'd (broad scan keeps legacy). Preserved /data/local/tmp paths intact (118 hits).
Task D (prose/comments + i18n folder text + HA origin + geocoding UA): DONE — web/i18n
  (33 files, 99 lines) + strings.xml (34 files, 66 lines) + server-i18n Telegram
  messages (en/pt-BR) -> Wheelstop/; HomeAssistantDiscovery origin.name + device-name
  fallback -> Wheelstop; GeocodingResolver UA -> Wheelstop; ~120 prose comment/doc/
  log/notification-text lines across ~55 files -> Wheelstop. Left untouched (flagged
  in report): legacy-Overdrive-app detection feature (com.overdrive.app is a real
  separate APK, not this product), zrok/Tailscale hostname-gen, HA node/entity id
  prefixes, PREFS_NAME/CHANNEL_ID/DB-name/storage-key identifiers, X-Overdrive-*
  headers, and two functional gaps for a follow-up: StorageManager's SD/USB volume
  dirs + SettingsAboutFragment's backup folder still literally write "Overdrive"
  (only the internal recordings/surveillance/proximity base dir was migrated by
  Task C). Preserved-token count unchanged (35). Remaining grep: 103 hits, all
  reviewed and categorized in the report.
Next: build gate + whole-branch review + PR (per line above).
