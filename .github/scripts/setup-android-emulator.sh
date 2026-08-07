#!/usr/bin/env bash
# Pre-launch emulator tweaks (from BTT-Writer CI). Cuts gfxstream/Vulkan flakes on GHA.
set -euo pipefail

mkdir -p "${HOME}/.android"
cat > "${HOME}/.android/advancedFeatures.ini" <<'EOF'
Vulkan = off
GLDirectMem = on
EOF

echo "Configured ${HOME}/.android/advancedFeatures.ini:"
cat "${HOME}/.android/advancedFeatures.ini"
