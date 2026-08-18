# Changelog

## [0.3.0](https://github.com/wheelstop-app/wheelstop/compare/v0.2.0...v0.3.0) (2026-08-18)


### Features

* **camera:** learn the HAL's real frame-rate ceiling and declare it ([#24](https://github.com/wheelstop-app/wheelstop/issues/24)) ([4fb1475](https://github.com/wheelstop-app/wheelstop/commit/4fb1475738d6915173cba971839ae375e6e50c27))
* **diag:** sentinel-gated AVM frame-rate sweep probe ([#25](https://github.com/wheelstop-app/wheelstop/issues/25)) ([e1d7a79](https://github.com/wheelstop-app/wheelstop/commit/e1d7a79722b91edeb54c334535ee812f7dd46417))
* **overlay:** make the camera's real frame rate visible ([#29](https://github.com/wheelstop-app/wheelstop/issues/29)) ([8082c45](https://github.com/wheelstop-app/wheelstop/commit/8082c4575c15f51ee6ab4d1a6d2450cfffa72aa1))
* **sync:** upstream-sync tooling — normalize, key-level locale merge, de-shadowing, invariants guard ([#15](https://github.com/wheelstop-app/wheelstop/issues/15)) ([9c70a51](https://github.com/wheelstop-app/wheelstop/commit/9c70a51e6feeca523c18a4d637eb11ef5676183a))


### Bug Fixes

* **daemons:** detect and reset daemons left running a deleted APK ([#27](https://github.com/wheelstop-app/wheelstop/issues/27)) ([67983c3](https://github.com/wheelstop-app/wheelstop/commit/67983c378109d3cc3f7e93ae74355c5317786d85))
* **daemons:** scope the stale reset to APK-backed daemons, and stop reading watchdog shells as daemons ([#31](https://github.com/wheelstop-app/wheelstop/issues/31)) ([b2a614f](https://github.com/wheelstop-app/wheelstop/commit/b2a614fd92516db34af6d3c4b55e2c0c10f7cb87))
* restore XML comments and the safety-gate message to the locale merge ([f18ef2e](https://github.com/wheelstop-app/wheelstop/commit/f18ef2ed2f029f0db59ed82bac73250e61676a15))
* **stream:** apply the live-view quality picker to the running stream ([#22](https://github.com/wheelstop-app/wheelstop/issues/22)) ([5a87f7f](https://github.com/wheelstop-app/wheelstop/commit/5a87f7f2cfc78d2f5741a6c3686ce7ad612f0ca7))
* **stream:** declare blind-spot clarity uniforms before odBlend() uses them ([#19](https://github.com/wheelstop-app/wheelstop/issues/19)) ([c77530a](https://github.com/wheelstop-app/wheelstop/commit/c77530a13f40c8ab661ca6a76c7c261aebcb8af4))
* **tailscale:** decide the proxy route by probing the port, not by pgrep ([#30](https://github.com/wheelstop-app/wheelstop/issues/30)) ([ad67fb0](https://github.com/wheelstop-app/wheelstop/commit/ad67fb059364c04d855d9e42202f8ffa7351e2a9))

## [0.2.0](https://github.com/wheelstop-app/wheelstop/compare/v0.1.0...v0.2.0) (2026-08-02)


### Features

* **brand:** finish the colour rebrand + de-Overdrive the web UI + bundle licences ([#4](https://github.com/wheelstop-app/wheelstop/issues/4)) ([2c181c2](https://github.com/wheelstop-app/wheelstop/commit/2c181c29f5ed280e151e1fa66085f2a6dc0cefe5))
* **brand:** replace launcher icon + sidebar logo with the Wheelstop mark ([#2](https://github.com/wheelstop-app/wheelstop/issues/2)) ([ed5c5c1](https://github.com/wheelstop-app/wheelstop/commit/ed5c5c16acc982483a2fc8bdf55f7ec87f1c0116))


### Bug Fixes

* **licensing:** offer sing-box GPLv3 Corresponding Source with every release ([37afd28](https://github.com/wheelstop-app/wheelstop/commit/37afd289ca7f6c01ac584f7cfd386f60c8cb79a6))

## 0.1.0 (2026-08-02)


### Features

* **blindspot:** accept contrast/sharpen on /api/stream/bs for live tuning ([28882b6](https://github.com/wheelstop-app/wheelstop/commit/28882b6791985afb1b77804ec795a68effea02f2))
* **blindspot:** add contrast/sharpen config + raise activeFps default to 25 ([b2ecfee](https://github.com/wheelstop-app/wheelstop/commit/b2ecfee5a49d1853f7462d5e4bbb15e83f423d32))
* **blindspot:** apply configured card clarity when the lane arms ([3a41ce0](https://github.com/wheelstop-app/wheelstop/commit/3a41ce03f2cbb4f659e07862cea2007ee277a03c))
* **blindspot:** contrast + sharpen sliders in the blind-spot editor ([f829f23](https://github.com/wheelstop-app/wheelstop/commit/f829f234b6578ab297c92c3605afa649f51a8d2b))
* **blindspot:** reimplement the stitch-coefficient math in open Kotlin ([b468fd9](https://github.com/wheelstop-app/wheelstop/commit/b468fd900d186257d1f8e1d136c083fc19054be8))
* **blindspot:** shader contrast + guarded unsharp on the stitched card (neutral by default) ([9bf58f7](https://github.com/wheelstop-app/wheelstop/commit/9bf58f74edf054845c760dbcc74a13b5fcdc507f))
* **brand:** rebrand user-facing display text OverDrive -&gt; Wheelstop ([ca86544](https://github.com/wheelstop-app/wheelstop/commit/ca86544f42033edf3caabfc251cdf3622c74ae8a))
* Enable RTL (right-to-left) support for Arabic language ([3070641](https://github.com/wheelstop-app/wheelstop/commit/30706411e57e39e66d4fa2ecb2fbdcbba60cc323))
* Enable RTL (right-to-left) support for Arabic language ([b2c74f5](https://github.com/wheelstop-app/wheelstop/commit/b2c74f513929e70a99533a62fbdeeba5f54632de))
* implement dynamic clip segment duration (2, 5, and 10 mins) ([45d93d5](https://github.com/wheelstop-app/wheelstop/commit/45d93d57d45f8ed39850e0f184da846542f5f162))
* implement dynamic clip segment duration configurations (2, 5, and 10 mins) ([0170b26](https://github.com/wheelstop-app/wheelstop/commit/0170b268a00b2f873b5fbe96dd035798c5693f35))
* **keymap:** add configurable instant replay clips ([d0cef36](https://github.com/wheelstop-app/wheelstop/commit/d0cef36e69367674d1050bf87d7225e9d316b8c6))
* **keymap:** add configurable instant replay clips ([8e9b368](https://github.com/wheelstop-app/wheelstop/commit/8e9b368ecabdd9855789b888ea12f8456b9abce2))
* **mqtt:** rebrand telemetry topic root to wheelstop/vehicle/telemetry ([3caa2e6](https://github.com/wheelstop-app/wheelstop/commit/3caa2e61cf37eb7e0cf3b31239fb25e8deeeee47))
* **preflight:** refuse to co-run with Overdrive; offer stop/disable/uninstall ([6c24ad0](https://github.com/wheelstop-app/wheelstop/commit/6c24ad0142f2dec8a072c41fa7c8d4895663fc71))
* redesign Live as a camera-first dashboard ([46a61e6](https://github.com/wheelstop-app/wheelstop/commit/46a61e6c5f44d83ee9f4a110a69650fca2fdb40a))
* redesign Live as camera-first dashboard ([e30a4a8](https://github.com/wheelstop-app/wheelstop/commit/e30a4a8efad84ee7b2406e357e1b68a99dd9ac46))
* **replay:** overlay clip indicator, dedicated Replays tab, bind reliability fixes ([9d53e36](https://github.com/wheelstop-app/wheelstop/commit/9d53e36e5429c67040926f2f1493bc5c552f2126))
* **replay:** overlay clip indicator, dedicated Replays tab, bind reliability fixes ([54ac148](https://github.com/wheelstop-app/wheelstop/commit/54ac148057cdfdaab9b5371099f81d059493973b))
* **signing:** adopt a dedicated Wheelstop release key (CI + local share it) ([28f9447](https://github.com/wheelstop-app/wheelstop/commit/28f9447909b24c73e39db1b2c51805445ad76eaf))
* **updater:** check the fork for releases + verify APK digest ([0a01d14](https://github.com/wheelstop-app/wheelstop/commit/0a01d14274bf443149c671e6fa609055b2762d77))


### Bug Fixes

* Fix:  ([cb09bd3](https://github.com/wheelstop-app/wheelstop/commit/cb09bd35e7f51b260f697ff10cb17b352791d63d))
* **blindspot:** drop derivatives dependency (fixed offset), re-apply clarity on view select, guard contrast, document merge-mode scope ([19ad725](https://github.com/wheelstop-app/wheelstop/commit/19ad7259adace1bb59575f01e1fbcfd8c445d01f))
* **blindspot:** drop unresolved data-i18n keys on injected clarity labels ([f08dcec](https://github.com/wheelstop-app/wheelstop/commit/f08dcec553fbe55d6903ac8a42a243c143c86de1))
* **brand:** rebrand remaining plural quantities + storage-path folder token ([397ea46](https://github.com/wheelstop-app/wheelstop/commit/397ea46c313bb974e54407c626070abed45aef0d))
* **brand:** rebrand translated/all-caps brand tokens the literal regex missed ([3a2d51a](https://github.com/wheelstop-app/wheelstop/commit/3a2d51acf65d87a0a5b55c8096d5b1b777666b5c))
* **camera:** auto-recover when the panoramic camera slot opens but never streams ([#170](https://github.com/wheelstop-app/wheelstop/issues/170)) ([151cf2a](https://github.com/wheelstop-app/wheelstop/commit/151cf2a9a0a10c86c468ab4335da8862ed161ad1))
* **camera:** escape a panoramic slot that opens but never streams ([1143382](https://github.com/wheelstop-app/wheelstop/commit/11433825103f13b999dcc9dceaa8f282bae72b21)), closes [#170](https://github.com/wheelstop-app/wheelstop/issues/170)
* clarify RoadSense overlay settings copy ([21cdb63](https://github.com/wheelstop-app/wheelstop/commit/21cdb6322a9e60c3bbb18bf403d99ab3e7d285a8))
* clear Live connecting state when frames arrive ([005fed8](https://github.com/wheelstop-app/wheelstop/commit/005fed87ed0f0ca959c6b0ccc20546a20fa030bb))
* clear live-view connecting state on frame delivery ([a3cee06](https://github.com/wheelstop-app/wheelstop/commit/a3cee060fac04a79043d1cf115e22ea1189809b9))
* corrects cloudflared tunnel command. ([14df80d](https://github.com/wheelstop-app/wheelstop/commit/14df80d0350284803ec1b099ee1291c663aa9d75))
* final-review polish — release-please tag prefix, locale brand leaks, cosmetic ([0f64a7c](https://github.com/wheelstop-app/wheelstop/commit/0f64a7cd26e9322e03b9031595887939a0a31204))
* **i18n:** escape lone apostrophes so translated placeholders still substitute ([5fc5f5e](https://github.com/wheelstop-app/wheelstop/commit/5fc5f5e3cd75b1a871810df27705f54703040167)), closes [#177](https://github.com/wheelstop-app/wheelstop/issues/177)
* **i18n:** escape lone apostrophes so translated placeholders still substitute ([#177](https://github.com/wheelstop-app/wheelstop/issues/177)) ([ed87303](https://github.com/wheelstop-app/wheelstop/commit/ed87303f73c9b2a2b85eba0179951d2c8d41d6d0))
* improve live camera selector contrast ([1bacfda](https://github.com/wheelstop-app/wheelstop/commit/1bacfda30bd9e34f1e69c8d6403fa704b064bd9f))
* improve Live camera selector contrast ([e27b911](https://github.com/wheelstop-app/wheelstop/commit/e27b9111f374c03b2dd7d7abe2d3e7fce6d3bd0d))
* improve vehicle screen layout and in-car rendering ([170c10d](https://github.com/wheelstop-app/wheelstop/commit/170c10d955d64f9a01963d5e3fcbdf0723a05188))
* keep tunnel status badge truthful ([55e49ba](https://github.com/wheelstop-app/wheelstop/commit/55e49ba640cdbef96163e7ee17315643bf775976))
* make camera onboarding vehicle and restore aware ([a560d42](https://github.com/wheelstop-app/wheelstop/commit/a560d42108924dfb426e38a0847bd7fa75919f82))
* make camera onboarding vehicle and restore aware ([be1276f](https://github.com/wheelstop-app/wheelstop/commit/be1276f93f96e3a766469a8786bb404ed3bfb43f))
* make tunnel status badge reflect actual daemon state ([d0b5153](https://github.com/wheelstop-app/wheelstop/commit/d0b5153c62f48252d740a10c5b042e34f3388741))
* **mqtt:** recover the HA link instead of stranding when the Tailscale proxy isn't up ([#182](https://github.com/wheelstop-app/wheelstop/issues/182)) ([bddd3d6](https://github.com/wheelstop-app/wheelstop/commit/bddd3d658f991e7a83c4841f43ad88fe79efe9aa))
* **mqtt:** recover the HA link instead of stranding when the Tailscale proxy isn't up ([#182](https://github.com/wheelstop-app/wheelstop/issues/182)) ([bc94148](https://github.com/wheelstop-app/wheelstop/commit/bc94148796824a92b27b2f5a6fe4b57802531c3a))
* **preflight:** cancel a pending daemon-start on a later CONTENDED verdict; bound the blocker's action calls with a fail-safe timeout ([0c6e14b](https://github.com/wheelstop-app/wheelstop/commit/0c6e14b96b010cfd522a69300bd076a544435ce7))
* **preflight:** sweep old app's UID-2000 daemons on stop/disable/uninstall; bound the exclusivity probe with a fail-open timeout ([53e14ae](https://github.com/wheelstop-app/wheelstop/commit/53e14aeeb10ba95781ea424a8b9d15fb003e82d4))
* preserve selector labels in app shell ([d8925af](https://github.com/wheelstop-app/wheelstop/commit/d8925af9b46df4a323e67e4e532f8e21629cdbc7))
* Prevents prematurely cancelling the ghostview's finish. ([781c2c9](https://github.com/wheelstop-app/wheelstop/commit/781c2c9c28f15d6cf1f2d6516081d0e8530f783d))
* Prevents prematurely cancelling the ghostview's finish. ([cddf7c6](https://github.com/wheelstop-app/wheelstop/commit/cddf7c60faa5c86a08b685389994c1c9b7ce9224))
* **replay:** isolate per-instance pre-record retention ([183bc4e](https://github.com/wheelstop-app/wheelstop/commit/183bc4e4df274d586c54a335fc84b21a33ebae19))
* **replay:** keep OEM pre-record settings off the shared ring ([17fad4b](https://github.com/wheelstop-app/wheelstop/commit/17fad4b65bc54986fb2ea563fee7aa3af7783088))
* restore the selected Live camera after app resume ([661e438](https://github.com/wheelstop-app/wheelstop/commit/661e438458b65b5b82feb16e1cac4ad489dca3f3))
* resume live camera after backgrounding ([f436f5b](https://github.com/wheelstop-app/wheelstop/commit/f436f5b6f35f9387d04b20764a90fded247c4ba0))
* Some users deleted trafficmonitor from the system. ([eec167e](https://github.com/wheelstop-app/wheelstop/commit/eec167e742c7db782fe85b90ccf4e8a2ae682c37))
* Some users deleted trafficmonitor from the system. ([a2b185b](https://github.com/wheelstop-app/wheelstop/commit/a2b185b4322ec85edecd77febc84a3e953fca148))
* speed up live camera selector vehicle art ([547ee9a](https://github.com/wheelstop-app/wheelstop/commit/547ee9a30f1186061da76497ce3349d0d37178cb))
* sync RoadSense overlay lifecycle ([dc58642](https://github.com/wheelstop-app/wheelstop/commit/dc58642fae26695fc710b9b251c4fcd14f038312))
* synchronise RoadSense overlay with its master switch ([f66941a](https://github.com/wheelstop-app/wheelstop/commit/f66941a93d4dd6af49f78287da6041a9ff1b5491))
* **ui:** improve vehicle model and tyre cards ([5d0870b](https://github.com/wheelstop-app/wheelstop/commit/5d0870b1c72904a1d16170cfc4123b040c3df4ae))
* **ui:** optimize in-car vehicle rendering ([b8d386a](https://github.com/wheelstop-app/wheelstop/commit/b8d386a66aaab01ae4da0a48471af68726a5051e))


### Performance Improvements

* **daemon:** skip re-granting permissions the app already holds ([66f6304](https://github.com/wheelstop-app/wheelstop/commit/66f6304975c3a63acac7faf8b6dcee8c8b217661)), closes [#178](https://github.com/wheelstop-app/wheelstop/issues/178)
* **daemon:** skip re-granting permissions the app already holds ([#178](https://github.com/wheelstop-app/wheelstop/issues/178)) ([0a7e4d2](https://github.com/wheelstop-app/wheelstop/commit/0a7e4d20bf1e40528126beff7665e37155fdd4f4))
* load Live camera selector artwork immediately ([023aeeb](https://github.com/wheelstop-app/wheelstop/commit/023aeeb50989022149e308c11a7192503e99966a))
* skip hidden sidebar vehicle renderer in app ([5188432](https://github.com/wheelstop-app/wheelstop/commit/5188432b38230d00cd369e0d9b0c5c8a4dd627ca))
