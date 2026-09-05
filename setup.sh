#!/usr/bin/env bash
set -euo pipefail

MODULE_DIR="$(cd -- "$(dirname -- "\${BASH_SOURCE[0]}")" && pwd)"

if [ -f "$MODULE_DIR/module.conf" ]; then
  # shellcheck disable=SC1091
  source "$MODULE_DIR/module.conf"
fi

# shellcheck disable=SC1091
source "$MODULE_DIR/scripts/libabk.sh"

abk_require_env KERNEL_ROOT DEFCONFIG CUSTOM_EXTERNAL_MODULE_STAGE

abk_log "module: \${ABK_MODULE_NAME:-ABK USB UVC Camera}"
abk_log "version: \${ABK_MODULE_VERSION:-unknown}"
abk_log "stage: $CUSTOM_EXTERNAL_MODULE_STAGE"
abk_log "kernel root: $KERNEL_ROOT"

case "$CUSTOM_EXTERNAL_MODULE_STAGE" in
  after_patch)
    python3 "$MODULE_DIR/scripts/install.py" install \\
      --kernel-root "$KERNEL_ROOT" \\
      --module-root "$MODULE_DIR" \\
      --defconfig "$DEFCONFIG"
    ;;
  before_build)
    python3 "$MODULE_DIR/scripts/install.py" install \\
      --kernel-root "$KERNEL_ROOT" \\
      --module-root "$MODULE_DIR" \\
      --defconfig "$DEFCONFIG" \\
      --enable-config
    python3 "$MODULE_DIR/scripts/install.py" verify \\
      --kernel-root "$KERNEL_ROOT" \\
      --module-root "$MODULE_DIR" \\
      --defconfig "$DEFCONFIG"
    ;;
  *)
    abk_die "unsupported CUSTOM_EXTERNAL_MODULE_STAGE: $CUSTOM_EXTERNAL_MODULE_STAGE"
    ;;
esac

abk_log "done"
