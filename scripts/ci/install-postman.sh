#!/usr/bin/env bash
set -euo pipefail
# Pinned official Linux x64 distribution; never execute the mutable installer.
test "$(uname -m)" = x86_64
version=1.74.0
checksum=b3a35679a210769cf8b5e6e86e16b93af1f3e5648b983d83b3305fe7f6d86a9e
install_dir=$(mktemp -d "${RUNNER_TEMP:?}/postman-cli.XXXXXXXX")
curl --fail --silent --show-error --location --retry 3 --proto '=https' --proto-redir '=https' \
  "https://dl-cli.pstmn.io/download/version/$version/linux64" -o "$install_dir/archive.tar.gz"
printf '%s  %s\n' "$checksum" "$install_dir/archive.tar.gz" | sha256sum --check --status
tar -xzf "$install_dir/archive.tar.gz" -C "$install_dir"
mv "$install_dir/postman-cli" "$install_dir/postman"
chmod 755 "$install_dir/postman"
test "$("$install_dir/postman" --version)" = "$version"
rm "$install_dir/archive.tar.gz"
printf '%s\n' "$install_dir" >> "${GITHUB_PATH:?}"
printf 'Postman CLI %s: SHA-256 and executable version verified.\n' "$version"
