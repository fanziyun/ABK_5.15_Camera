#!/usr/bin/env bash

abk_log() {
  printf '[ABK UVC camera] %s\\n' "$*"
}

abk_warn() {
  printf '[ABK UVC camera][warn] %s\\n' "$*" >&2
}

abk_die() {
  printf '[ABK UVC camera][error] %s\\n' "$*" >&2
  exit 1
}

abk_require_env() {
  local name
  for name in "$@"; do
    if [ -z "\${!name:-}" ]; then
      abk_die "required environment variable is empty: $name"
    fi
  done
}

abk_require_file() {
  local path="$1"
  [ -f "$path" ] || abk_die "required file not found: $path"
}

abk_require_dir() {
  local path="$1"
  [ -d "$path" ] || abk_die "required directory not found: $path"
}

abk_common_dir() {
  abk_require_env KERNEL_ROOT
  printf '%s/common\\n' "$KERNEL_ROOT"
}

abk_append_line_once() {
  local file="$1"
  local line="$2"
  abk_require_file "$file"
  grep -qF -- "$line" "$file" || printf '%s\\n' "$line" >> "$file"
}

abk_kernel_make_value() {
  local key="$1"
  local makefile
  makefile="$(abk_common_dir)/Makefile"
  abk_require_file "$makefile"
  awk -v key="$key" '$1 == key && $2 == "=" { print $3; exit }' "$makefile"
}

abk_kernel_version() {
  printf '%s.%s.%s\\n' \\
    "$(abk_kernel_make_value VERSION)" \\
    "$(abk_kernel_make_value PATCHLEVEL)" \\
    "$(abk_kernel_make_value SUBLEVEL)"
}

abk_set_config() {
  local symbol="$1"
  local value="$2"
  local file="\${3:-\${DEFCONFIG:-}}"
  local clean_symbol tmp

  [ -n "$file" ] || abk_die "DEFCONFIG is empty"
  abk_require_file "$file"
  clean_symbol="\${symbol#CONFIG_}"
  tmp="$(mktemp)"
  grep -v -E "^(CONFIG_\${clean_symbol}=|# CONFIG_\${clean_symbol} is not set$)" "$file" > "$tmp" || true
  if [ "$value" = n ]; then
    printf '# CONFIG_%s is not set\\n' "$clean_symbol" >> "$tmp"
  else
    printf 'CONFIG_%s=%s\\n' "$clean_symbol" "$value" >> "$tmp"
  fi
  mv "$tmp" "$file"
  abk_log "set CONFIG_\${clean_symbol}=$value in $file"
}

abk_enable_config() {
  abk_set_config "$1" y "\${2:-\${DEFCONFIG:-}}"
}
