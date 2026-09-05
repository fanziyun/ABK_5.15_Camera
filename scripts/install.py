#!/usr/bin/env python3
"""Install and verify the ABK 5.15 UVC camera source integration.

The installer is deliberately idempotent.  It only copies this module's own
files, appends namespaced Kbuild/Kconfig entries once, and applies the
namespaced configfs hook patch.  It never edits the FIDO module or the ABK
checkout directly.
"""

from __future__ import annotations

import argparse
import json
import re
import shutil
import subprocess
import sys
from pathlib import Path


MARKER = "ABK_UVC_CAMERA_V1"
SUPPORTED_KERNEL_LINE = "5.15"
KCONFIG_SOURCE = 'source "drivers/abk_uvc_camera/Kconfig"'
KBUILD_LINE = "obj-$(CONFIG_ABK_UVC_CAMERA) += abk_uvc_camera/"
REQUIRED_CONFIGS = (
    "CONFIG_USB_CONFIGFS",
    "CONFIG_USB_CONFIGFS_F_UVC",
    "CONFIG_USB_F_UVC",
    "CONFIG_VIDEO_DEV",
    "CONFIG_VIDEO_V4L2",
    "CONFIG_MEDIA_SUPPORT",
)


class InstallError(RuntimeError):
    pass


def require(path: Path, kind: str = "file") -> None:
    if kind == "file" and not path.is_file():
        raise InstallError(f"required file not found: {path}")
    if kind == "dir" and not path.is_dir():
        raise InstallError(f"required directory not found: {path}")


def common_dir(kernel_root: Path) -> Path:
    return kernel_root / "common"


def kernel_version(common: Path) -> str:
    makefile = common / "Makefile"
    require(makefile)
    values: dict[str, str] = {}
    for line in makefile.read_text(encoding="utf-8", errors="replace").splitlines():
        match = re.match(r"^(VERSION|PATCHLEVEL|SUBLEVEL)\s*=\s*(\S+)", line)
        if match:
            values[match.group(1)] = match.group(2)
    missing = [key for key in ("VERSION", "PATCHLEVEL", "SUBLEVEL") if key not in values]
    if missing:
        raise InstallError(f"kernel Makefile is missing version keys: {', '.join(missing)}")
    return f"{values['VERSION']}.{values['PATCHLEVEL']}.{values['SUBLEVEL']}"


def check_kernel_line(common: Path) -> str:
    version = kernel_version(common)
    if ".".join(version.split(".")[:2]) != SUPPORTED_KERNEL_LINE:
        raise InstallError(
            f"unsupported kernel line {version}; this module currently targets {SUPPORTED_KERNEL_LINE}.x"
        )
    return version


def append_once(path: Path, line: str) -> None:
    require(path)
    text = path.read_text(encoding="utf-8", errors="replace")
    if line not in text.splitlines():
        newline = "" if text.endswith("\n") or not text else "\n"
        path.write_text(text + newline + line + "\n", encoding="utf-8")


def set_defconfig(path: Path, symbol: str, value: str = "y") -> None:
    require(path)
    clean = symbol.removeprefix("CONFIG_")
    lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
    pattern = re.compile(rf"^(CONFIG_{re.escape(clean)}=|# CONFIG_{re.escape(clean)} is not set$)")
    lines = [line for line in lines if not pattern.match(line)]
    lines.append(f"CONFIG_{clean}={value}" if value != "n" else f"# CONFIG_{clean} is not set")
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def config_value(path: Path, symbol: str) -> str | None:
    clean = symbol.removeprefix("CONFIG_")
    pattern = re.compile(rf"^CONFIG_{re.escape(clean)}=(.+)$")
    unset = f"# CONFIG_{clean} is not set"
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        match = pattern.match(line)
        if match:
            return match.group(1).strip()
        if line.strip() == unset:
            return "n"
    return None


def install_kernel_files(module_root: Path, common: Path) -> None:
    drivers = common / "drivers"
    include_linux = common / "include" / "linux"
    require(drivers, "dir")
    require(include_linux, "dir")
    require(drivers / "Kconfig")
    require(drivers / "Makefile")

    source_dir = module_root / "files" / "drivers" / "abk_uvc_camera"
    source_header = module_root / "files" / "include" / "linux" / "abk_uvc_camera.h"
    require(source_dir, "dir")
    require(source_header)

    destination = drivers / "abk_uvc_camera"
    destination.mkdir(parents=True, exist_ok=True)
    for source in source_dir.iterdir():
        if source.is_file():
            shutil.copy2(source, destination / source.name)
    shutil.copy2(source_header, include_linux / source_header.name)

    append_once(drivers / "Kconfig", KCONFIG_SOURCE)
    append_once(drivers / "Makefile", KBUILD_LINE)


def patch_configfs(module_root: Path, common: Path) -> None:
    configfs = common / "drivers" / "usb" / "gadget" / "configfs.c"
    require(configfs)
    patcher = module_root / "scripts" / "patch_configfs_for_abk_uvc_camera.py"
    require(patcher)
    result = subprocess.run(
        [sys.executable, str(patcher), str(configfs)],
        check=False,
        capture_output=True,
        text=True,
    )
    if result.returncode:
        raise InstallError((result.stdout + result.stderr).strip() or "configfs patch failed")


def warn_dependency_state(defconfig: Path) -> list[str]:
    warnings: list[str] = []
    if not defconfig.is_file():
        return warnings
    for symbol in REQUIRED_CONFIGS:
        value = config_value(defconfig, symbol)
        if value not in {"y", "m"}:
            warnings.append(f"{symbol} is {value or 'unset'}; Kconfig dependency resolution must enable UVC/V4L2")
    return warnings


def do_install(args: argparse.Namespace) -> int:
    module_root = args.module_root.resolve()
    kernel_root = args.kernel_root.resolve()
    common = common_dir(kernel_root)
    version = check_kernel_line(common)
    install_kernel_files(module_root, common)
    patch_configfs(module_root, common)
    if args.enable_config:
        set_defconfig(args.defconfig, "CONFIG_ABK_UVC_CAMERA", "y")
    for warning in warn_dependency_state(args.defconfig):
        print(f"[ABK UVC camera][warn] {warning}", file=sys.stderr)
    print(json.dumps({"installed": True, "kernel_version": version, "marker": MARKER}))
    return 0


def verify(args: argparse.Namespace) -> int:
    module_root = args.module_root.resolve()
    common = common_dir(args.kernel_root.resolve())
    version = check_kernel_line(common)
    for path in (
        common / "drivers" / "abk_uvc_camera" / "Kconfig",
        common / "drivers" / "abk_uvc_camera" / "Makefile",
        common / "drivers" / "abk_uvc_camera" / "core.c",
        common / "include" / "linux" / "abk_uvc_camera.h",
    ):
        require(path)
    for path, needle in (
        (common / "drivers" / "Kconfig", KCONFIG_SOURCE),
        (common / "drivers" / "Makefile", KBUILD_LINE),
        (common / "drivers" / "usb" / "gadget" / "configfs.c", "abk_uvc_camera_prepare_config"),
        (common / "drivers" / "usb" / "gadget" / "configfs.c", "abk_uvc_camera_release_config"),
    ):
        require(path)
        text = path.read_text(encoding="utf-8", errors="replace")
        if text.count(needle) != 1:
            raise InstallError(f"expected exactly one {needle!r} in {path}, found {text.count(needle)}")
    if args.defconfig.is_file() and config_value(args.defconfig, "CONFIG_ABK_UVC_CAMERA") != "y":
        raise InstallError("CONFIG_ABK_UVC_CAMERA=y is missing from defconfig")
    print(json.dumps({"verified": True, "kernel_version": version, "marker": MARKER}))
    return 0


def detect(args: argparse.Namespace) -> int:
    common = common_dir(args.kernel_root.resolve())
    print(json.dumps({"kernel_version": check_kernel_line(common), "supported": True}))
    return 0


def parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description=__doc__)
    sub = p.add_subparsers(dest="command", required=True)
    for name in ("install", "verify", "detect"):
        child = sub.add_parser(name)
        child.add_argument("--kernel-root", type=Path, required=True)
        child.add_argument("--module-root", type=Path, required=True)
        child.add_argument("--defconfig", type=Path, required=True)
        if name == "install":
            child.add_argument("--enable-config", action="store_true")
    return p


def main(argv: list[str] | None = None) -> int:
    args = parser().parse_args(argv)
    try:
        if args.command == "install":
            return do_install(args)
        if args.command == "verify":
            return verify(args)
        return detect(args)
    except InstallError as exc:
        print(f"[ABK UVC camera][error] {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
