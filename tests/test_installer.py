#!/usr/bin/env python3
from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
INSTALLER = ROOT / "scripts" / "install.py"
PATCHER = ROOT / "scripts" / "patch_configfs_for_abk_uvc_camera.py"


CONFIGFS_STUB = '''#include <linux/configfs.h>
#include "u_os_desc.h"

static void gadget_config_attr_release(struct config_item *item)
{
	struct config_usb_cfg *cfg = to_config_usb_cfg(item);
}

static void purge_configs_funcs(struct gadget_info *gi)
{
	struct usb_configuration *c;
	struct usb_function *f, *tmp;

	list_for_each_entry(c, &gi->cdev.configs, list) {
		struct config_usb_cfg *cfg = container_of(c, struct config_usb_cfg, c);

		list_for_each_entry_safe_reverse(f, tmp, &c->functions, list) {
			list_move(&f->list, &cfg->func_list);
		}
		c->next_interface_id = 0;
	}
}

static int configfs_composite_bind(struct usb_gadget *gadget,
		struct usb_gadget_driver *gdriver)
{
	struct usb_composite_dev *cdev = to_cdev(gdriver);
	struct usb_configuration *c;
	struct usb_function *f, *tmp;
	int ret = 0;

		list_for_each_entry_safe(f, tmp, &cfg->func_list, list) {
		ret = 0;
	}
	return ret;
}
'''


class InstallerTests(unittest.TestCase):
    def make_tree(self, version: str = "5.15.211") -> tuple[Path, Path]:
        temp = Path(tempfile.mkdtemp())
        common = temp / "common"
        (common / "drivers" / "usb" / "gadget").mkdir(parents=True)
        (common / "include" / "linux").mkdir(parents=True)
        (common / "drivers" / "Kconfig").write_text('menu "Drivers"\nendmenu\n')
        (common / "drivers" / "Makefile").write_text("obj-y += base/\n")
        (common / "drivers" / "usb" / "gadget" / "configfs.c").write_text(CONFIGFS_STUB)
        major, minor, sub = version.split(".")
        (common / "Makefile").write_text(
            f"VERSION = {major}\nPATCHLEVEL = {minor}\nSUBLEVEL = {sub}\n"
        )
        defconfig = temp / "defconfig"
        defconfig.write_text(
            "CONFIG_USB_CONFIGFS=y\nCONFIG_USB_CONFIGFS_F_UVC=y\n"
            "CONFIG_USB_F_UVC=y\nCONFIG_VIDEO_DEV=y\nCONFIG_VIDEO_V4L2=y\n"
            "CONFIG_MEDIA_SUPPORT=y\n"
        )
        return temp, defconfig

    def run_installer(self, command: str, tree: Path, defconfig: Path, *extra: str):
        return subprocess.run(
            [sys.executable, str(INSTALLER), command, "--kernel-root", str(tree),
             "--module-root", str(ROOT), "--defconfig", str(defconfig), *extra],
            capture_output=True, text=True, check=False,
        )

    def test_install_is_idempotent_and_enables_config(self) -> None:
        tree, defconfig = self.make_tree()
        first = self.run_installer("install", tree, defconfig, "--enable-config")
        self.assertEqual(first.returncode, 0, first.stdout + first.stderr)
        second = self.run_installer("install", tree, defconfig, "--enable-config")
        self.assertEqual(second.returncode, 0, second.stdout + second.stderr)
        verify = self.run_installer("verify", tree, defconfig)
        self.assertEqual(verify.returncode, 0, verify.stdout + verify.stderr)
        self.assertEqual(defconfig.read_text().count("CONFIG_ABK_UVC_CAMERA=y"), 1)
        self.assertEqual((tree / "common/drivers/Kconfig").read_text().count("abk_uvc_camera/Kconfig"), 1)
        self.assertEqual((tree / "common/drivers/Makefile").read_text().count("CONFIG_ABK_UVC_CAMERA"), 1)
        patched = (tree / "common/drivers/usb/gadget/configfs.c").read_text()
        self.assertEqual(patched.count("abk_uvc_camera_prepare_config"), 1)
        self.assertEqual(patched.count("abk_uvc_camera_release_config"), 1)
        self.assertEqual(patched.count("abk_uvc_camera_drop_injected"), 1)

    def test_patch_works_after_fido_marker(self) -> None:
        tree, defconfig = self.make_tree()
        configfs = tree / "common/drivers/usb/gadget/configfs.c"
        fido = '#include <linux/abk_fido_key.h>\n'
        configfs.write_text(configfs.read_text().replace('#include "u_os_desc.h"\n', '#include "u_os_desc.h"\n' + fido))
        result = self.run_installer("install", tree, defconfig)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        result = subprocess.run([sys.executable, str(PATCHER), str(configfs)], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        text = configfs.read_text()
        self.assertEqual(text.count("abk_uvc_camera_prepare_config"), 1)
        self.assertEqual(text.count("abk_uvc_camera_drop_injected"), 1)

    def test_rejects_non_515_kernel(self) -> None:
        tree, defconfig = self.make_tree("6.1.100")
        result = self.run_installer("install", tree, defconfig)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("unsupported kernel line", result.stderr)


if __name__ == "__main__":
    unittest.main()
