#!/usr/bin/env bash
# Builds the headless CNCVerse Bridge server and packages it as:
#   1. A portable tar.gz  → dist/cncverse-bridge-server-<version>.tar.gz
#   2. A Debian package   → dist/cncverse-bridge-server_<version>_all.deb
#
# Usage:  serverApp/packaging/build.sh [--no-deb]
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

VERSION="$(sed -n 's/^desktop\.versionName=//p' version.properties | head -1 | tr -d '[:space:]')"
[ -n "${VERSION}" ] || VERSION="0.0.1"
STAGE="serverApp/build/packaging/cncverse-bridge-server"
OUT="serverApp/dist"
NAME="cncverse-bridge-server"
INSTALL_DIR="/usr/lib/${NAME}"
GRADLE="${GRADLE_CMD:-./gradlew}"

echo "==> Building installDist (this compiles the server + shared module)…"
${GRADLE} :serverApp:installDist --console=plain

echo "==> Assembling package tree…"
rm -rf "serverApp/build/packaging"
mkdir -p "${STAGE}${INSTALL_DIR}" "${STAGE}/usr/bin" "${STAGE}/lib/systemd/system" "${STAGE}/DEBIAN" "${OUT}"

cp -r serverApp/build/install/cncverse-bridge-server/* "${STAGE}${INSTALL_DIR}/"
cp serverApp/packaging/cncverse-bridge-server.service "${STAGE}/lib/systemd/system/"
cp serverApp/packaging/README-server.md "${STAGE}${INSTALL_DIR}/" 2>/dev/null || true

ln -sf "${INSTALL_DIR}/bin/${NAME}" "${STAGE}/usr/bin/${NAME}"

# postinst: create the service user, enable + start the unit
cat > "${STAGE}/DEBIAN/postinst" <<'EOF'
#!/bin/bash
set -e
if ! id -u cncverse >/dev/null 2>&1; then
    useradd --system --create-home --shell /usr/sbin/nologin cncverse
fi
chown -R cncverse:cncverse /usr/lib/cncverse-bridge-server
if command -v systemctl >/dev/null 2>&1; then
    systemctl daemon-reload || true
    systemctl enable cncverse-bridge-server || true
fi
echo ""
echo "CNCVerse Bridge Server installed."
echo "  systemctl start cncverse-bridge-server"
echo "  Web admin: http://<server-ip>:8080/admin"
exit 0
EOF
chmod 755 "${STAGE}/DEBIAN/postinst"

cat > "${STAGE}/DEBIAN/control" <<EOF
Package: ${NAME}
Version: ${VERSION}
Section: video
Priority: optional
Architecture: all
Depends: default-jre (>= 11) | java11-runtime-headless | java11-runtime
Maintainer: NivinCNC <nivincnc@users.noreply.github.com>
Description: Headless Stremio addon server powered by CNCVerse CS3 plugins
 Runs the CNCVerse Bridge without a desktop UI. All management (server
 control, extension install/settings, tunnel, logs) is available from the
 embedded web admin panel at http://<host>:8080/admin.
Homepage: https://github.com/NivinCNC/CNCVerse-Bridge
EOF

cat > "${STAGE}/DEBIAN/prerm" <<'EOF'
#!/bin/bash
set -e
if command -v systemctl >/dev/null 2>&1; then
    systemctl stop cncverse-bridge-server || true
    systemctl disable cncverse-bridge-server || true
fi
exit 0
EOF
chmod 755 "${STAGE}/DEBIAN/prerm"

echo "==> Building tar.gz…"
tar -czf "${OUT}/${NAME}-${VERSION}.tar.gz" -C "${STAGE}${INSTALL_DIR}" .
echo "    ${OUT}/${NAME}-${VERSION}.tar.gz"

if [ "${1:-}" != "--no-deb" ]; then
    echo "==> Building .deb…"
    if command -v fakeroot >/dev/null 2>&1; then
        fakeroot dpkg-deb --root-owner-group --build "${STAGE}" "${OUT}/${NAME}_${VERSION}_all.deb"
    else
        dpkg-deb --root-owner-group --build "${STAGE}" "${OUT}/${NAME}_${VERSION}_all.deb"
    fi
    echo "    ${OUT}/${NAME}_${VERSION}_all.deb"
fi

echo "==> Done. Install with:  sudo apt install ${OUT}/${NAME}_${VERSION}_all.deb"
echo "    Then start:           sudo systemctl start cncverse-bridge-server"
