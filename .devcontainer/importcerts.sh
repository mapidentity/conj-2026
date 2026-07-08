#!/bin/bash
set -euo pipefail

CERT_FILE="/certificates/rootCA.crt"

# This script imports the $CERT_FILE file into
# the java trust store, system CA store (linux)
# and NSS database (Google Chrome/ium browser).
# This makes them trust the self signed certificates.
#
# !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
# !! SELF GENERATED ROOT CERTIFICATES         !!
# !! SHOULD BE CONSIDERED UNSAFE!             !!
# !!                                          !!
# !! RUN THIS ONLY IN ISOLATED *DEVELOPMENT*  !!
# !! SYSTEMS LIKE DOCKER CONTAINERS OR        !!
# !! VIRTUAL MACHINES,                        !!
# !! *NOT* YOUR PRODUCTION OR DESKTOP SYSTEM! !!
# !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!

if [ ! -f "$CERT_FILE" ]; then
    echo "No root CA certificate found at $CERT_FILE, skipping import."
    exit 0
fi

echo "Importing root CA into Java trust store..."
keytool -import -file "$CERT_FILE" -alias development -noprompt \
    -trustcacerts -keystore "$JAVA_HOME/lib/security/cacerts" \
    -storepass changeit || true

echo "Importing root CA into system CA store..."
cp "$CERT_FILE" /usr/local/share/ca-certificates/myapp-dev.crt
update-ca-certificates

echo "Importing root CA into NSS database (Chromium)..."
rm -rf "$HOME/.pki/nssdb"
mkdir -p "$HOME/.pki/nssdb"
certutil -d "sql:$HOME/.pki/nssdb" -N --empty-password
certutil -d "sql:$HOME/.pki/nssdb" -A -t 'C,,' -n 'myapp Dev CA' -i "$CERT_FILE" -f /dev/null

echo "All certificates imported successfully."
