#!/usr/bin/env python3
"""Apply the namespaced ABK UVC configfs observation hooks once.

The hook deliberately does not fabricate a UVC descriptor tree.  The Android
companion links the device's preconfigured uvc.0 function, while this hook
records the function/FIDO state during configfs bind and release.  This keeps
it compatible with the ABK FIDO hook in either installation order.
"""

from __future__ import annotations

import sys
from pathlib import Path


MARKER = "ABK_UVC_CAMERA_CONFIGFS_V1"
INCLUDE_ANCHOR = '#include "u_os_desc.h"'
INCLUDE_LINE = "#include <linux/abk_uvc_camera.h>"
PREPARE_NEEDLE = "\t\tlist_for_each_entry_safe(f, tmp, &cfg->func_list, list) {"
PREPARE_BLOCK = (
    f'\t\t/* {MARKER}: camera state hook; never creates an unconfigured UVC tree. */\n'
    "#ifdef CONFIG_ABK_UVC_CAMERA\n"
    "\t\tret = abk_uvc_camera_prepare_config(cdev, c, &cfg->func_list);\n"
    "\t\tif (ret) {\n"
    '\t\t\tpr_warn("abk_uvc_camera: prepare_config failed: %d\\n", ret);\n'
    "\t\t\tret = 0; /* camera state must not take FIDO/ADB down */\n"
    "\t\t}\n"
    "#endif\n\n"
    + PREPARE_NEEDLE
)
RELEASE_NEEDLE = (
    "static void gadget_config_attr_release(struct config_item *item)\n"
    "{\n"
    "\tstruct config_usb_cfg *cfg = to_config_usb_cfg(item);\n"
)
RELEASE_BLOCK = (
    "static void gadget_config_attr_release(struct config_item *item)\n"
    "{\n"
    "\tstruct config_usb_cfg *cfg = to_config_usb_cfg(item);\n\n"
    f'\t/* {MARKER}: release only camera-owned state; never remove FIDO. */\n'
    "#ifdef CONFIG_ABK_UVC_CAMERA\n"
    "\tabk_uvc_camera_release_config(&cfg->func_list);\n"
    "#endif\n"
)


def fail(message: str) -> None:
    raise SystemExit(message)


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: patch_configfs_for_abk_uvc_camera.py <configfs.c>", file=sys.stderr)
        return 1
    path = Path(sys.argv[1])
    if not path.is_file():
        fail(f"configfs source not found: {path}")
    text = path.read_text(encoding="utf-8").replace("\r\n", "\n")
    updated = text

    if INCLUDE_LINE not in updated:
        if INCLUDE_ANCHOR not in updated:
            fail(f"include anchor not found in {path}")
        updated = updated.replace(INCLUDE_ANCHOR, INCLUDE_ANCHOR + "\n" + INCLUDE_LINE, 1)

    if "abk_uvc_camera_prepare_config" not in updated:
        if PREPARE_NEEDLE not in updated:
            fail(f"prepare injection point not found in {path}")
        updated = updated.replace(PREPARE_NEEDLE, PREPARE_BLOCK, 1)

    if "abk_uvc_camera_release_config" not in updated:
        if RELEASE_NEEDLE not in updated:
            fail(f"release injection point not found in {path}")
        updated = updated.replace(RELEASE_NEEDLE, RELEASE_BLOCK, 1)

    if updated != text:
        path.write_text(updated, encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
