#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR=$(dirname "$0")

# ===================================================
# Creates a root certificate and client certificates.
# Does nothing if root certificate already exists.
# ===================================================

# eclipse-temurin:25-jre-alpine already ships the openssl CLI this script drives,
# but install it when missing so the script still works on a leaner base image
# (a no-op when openssl is already present).
command -v openssl >/dev/null 2>&1 || apk add --no-cache openssl

# Add additional hosts here
HOSTS='myapp.lan'

if [ -e "rootCA.key" ]; then
    echo "============ NO ACTION REQUIRED ============"
    echo "Exiting due to certificates already created."
    echo "============================================"
    exit 0
fi

echo "Creating root CA"
openssl genrsa -out rootCA.key 4096
openssl req -x509 -new -nodes -key rootCA.key -sha256 -days 825 -out rootCA.crt -subj "/C=NL/ST=Utrecht/L=Amersfoort/O=myapp/OU=IT/CN=myapp development CA" -addext "basicConstraints = CA:TRUE" -addext "keyUsage = keyCertSign, cRLSign"
openssl x509 -in rootCA.crt -text -noout
rm -f rootCA.jks
keytool -import -file rootCA.crt -alias development -noprompt -trustcacerts -keystore rootCA.jks -storepass changeit

for CN in $HOSTS
do
echo "Creating certificate for $CN"
mkdir -p "$CN"
openssl req -new \
-newkey rsa:2048 -nodes -keyout "$CN/$CN.key" \
-out "$CN/$CN.csr" \
-subj "/C=NL/ST=Utrecht/L=Amersfoort/O=myapp/OU=IT/CN=$CN" \
-addext "subjectAltName = DNS:$CN"

echo "Signing certificate with root CA for $CN"
# Build the SAN extension file without process substitution: this script runs
# under Alpine's /bin/ash (busybox), which does not support <(...).
ext="$CN/ext.cnf"
cat "$SCRIPT_DIR/openssl.cnf" > "$ext"
printf '\nDNS.1 = %s\n' "$CN" >> "$ext"
openssl x509 -req -in "$CN/$CN.csr" -CA rootCA.crt -CAkey rootCA.key -CAcreateserial -out "$CN/$CN.crt" -days 500 -sha256 -extfile "$ext"

echo "Check generated certificate"
openssl verify -CAfile rootCA.crt -verify_hostname $CN "$CN/$CN.crt"
openssl x509 -in "$CN/$CN.crt" -text -noout


echo "Adding to jks file"
openssl pkcs12 -export -out "$CN/keystore.p12" -inkey "$CN/$CN.key" -in "$CN/$CN.crt" -password pass:
keytool -noprompt -importkeystore -destkeystore "$CN/keystore.jks" -srcstoretype PKCS12 -srckeystore "$CN/keystore.p12" -storepass changeit -srcstorepass ""
done