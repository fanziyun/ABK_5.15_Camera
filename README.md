# ABK 5.15 UVC Camera Module

This module adds an Android USB UVC camera path to an ABK 5.15 LTS build while
preserving the existing ABK FIDO key.  The kernel side is intentionally small:
it exposes a sysfs control/state surface and a namespaced configfs observation
hook.  Camera2 capture, test-pattern generation, and USB runtime coordination
live in the companion app.

## Layout

- `files/drivers/abk_uvc_camera/` — kernel driver, Kconfig and Makefile.
- `files/include/linux/abk_uvc_camera.h` — exported configfs hook.
- `scripts/install.py` — idempotent 5.15 source installer/verifier.
- `scripts/patch_configfs_for_abk_uvc_camera.py` — configfs hook patch.
- `profiles/profiles.json` — dynamic profile capability model.
- `app/` — Android companion application.
- `tests/` — installer and profile tests.

## Kernel build integration

ABK runs each external module's `setup.sh` during `after_patch` and
`before_build`.  This module:

1. copies `files/drivers/abk_uvc_camera` into `common/drivers`;
2. copies `abk_uvc_camera.h` into `common/include/linux`;
3. appends `source "drivers/abk_uvc_camera/Kconfig"` to `drivers/Kconfig`
   and the matching `obj-...` line to `drivers/Makefile`;
4. applies the namespaced configfs hook patch once;
5. sets `CONFIG_ABK_UVC_CAMERA=y` in the defconfig on `before_build`.

The installer rejects kernels outside the 5.15 line and is safe to run twice.

## USB modes and fallback

The runtime coordinator uses the fixed priority:

1. `FIDO` stays online;
2. try `UVC + FIDO + ADB`;
3. try adding `MTP` (`UVC + FIDO + MTP + ADB`);
4. if MTP fails, keep `UVC + FIDO + ADB`;
5. if FIDO fails, restore the original USB config and stop camera output.

The module does not modify the vendor `init.qcom.usb.rc`; MTP+UVC is achieved
through root runtime coordination and is expected to degrade cleanly.

## Profile / bandwidth model

USB 2.0 has a theoretical limit of about 480 Mbps (60 MB/s).  Raw YUY2 at
1080p60 is about 1.99 Gbps and 4K60 is about 7.96 Gbps, both impossible on
USB 2.0.  The first version therefore:

- keeps stable YUY2/MJPEG 360p and 720p profiles;
- keeps 1080p30 MJPEG as a stable target;
- treats 1080p60 MJPEG as the main experimental target;
- treats 4K15/30 compressed output as an optional target;
- treats 4K60 H.264/MJPEG as experimental only;
- never advertises raw YUY2 1080p60/4K60.

See `profiles/profiles.json` for the authoritative list.

## Companion app

- Package: `com.abk.extension.camera`
- Extension ID: `abk_uvc_camera`
- Foreground service owns Camera2 capture and USB coordination.
- `DeviceAsWebcamAdapter` prefers the system `com.android.DeviceAsWebcam`
  package when a public entry point exists; `Camera2Backend` is the fallback.
- `TestPatternFrameSource` provides colour bars with timestamp/frame number
  for Windows enumeration tests.

The app does not expose arbitrary third-party shell commands.

## Building

Host checks:

```bash
python -m unittest discover -s tests -v
```

Companion APK (Windows, Java 17 required):

```powershell
& .\gradlew.bat :app:assembleDebug
```

## Device test sequence

Device actions are performed only after explicit per-step confirmation.  The
recommended order is:

1. read UDC `maximum_speed`/`current_speed` and confirm USB High-Speed;
2. enumerate `UVC + FIDO + ADB`;
3. output test pattern;
4. verify 360p/720p existing profiles;
5. verify 1080p30 MJPEG;
6. attempt 1080p60 MJPEG;
7. measure frame loss, queue blocking, average/peak bitrate;
8. attempt 4K15/30;
9. attempt 4K60 H.264 last;
10. verify front/back switching does not re-enumerate USB;
11. verify FIDO stays online;
12. verify MTP failure degrades to UVC+FIDO+ADB;
13. verify app kill / USB unplug / service restart recovery.
