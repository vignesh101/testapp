#!/bin/bash
# =============================================================================
# Certificate Generation Script for mTLS + MLE (Visa-like setup)
# =============================================================================
# This script generates:
#   1. CA certificate (self-signed)
#   2. Server TLS certificate (signed by CA) + PKCS12 keystore
#   3. Client TLS certificate (signed by CA) + PKCS12 keystore
#   4. Server & Client truststores (containing CA cert)
#   5. MLE Server RSA key pair (PEM files)
#   6. MLE Client RSA key pair (PEM files)
# =============================================================================

set -e

# Prevent Git Bash from converting /C=US style paths to Windows paths
export MSYS_NO_PATHCONV=1

CERTS_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$CERTS_DIR"

KEYSTORE_PASS="changeit"
KEY_SIZE=2048
VALIDITY_DAYS=365

echo "============================================================"
echo "  Generating Certificates for mTLS + MLE"
echo "============================================================"

# Clean previous certs
rm -f *.pem *.p12 *.crt *.csr *.key *.srl

# ---------------------------------------------------------
# 1. Certificate Authority (CA)
# ---------------------------------------------------------
echo ""
echo "[1/8] Generating CA key and self-signed certificate..."
openssl genrsa -out ca.key $KEY_SIZE 2>/dev/null
openssl req -new -x509 -key ca.key -out ca.crt -days $VALIDITY_DAYS \
  -subj "/C=US/ST=California/L=Foster City/O=Visa Inc/OU=Visa CA/CN=Visa Local CA"

# ---------------------------------------------------------
# 2. Server TLS Certificate
# ---------------------------------------------------------
echo "[2/8] Generating Server TLS key and certificate..."
openssl genrsa -out server-tls.key $KEY_SIZE 2>/dev/null
openssl req -new -key server-tls.key -out server-tls.csr \
  -subj "/C=US/ST=California/L=Foster City/O=Visa Inc/OU=Server/CN=localhost"

# Create extensions file for SAN
cat > server-ext.cnf <<EOF
authorityKeyIdentifier=keyid,issuer
basicConstraints=CA:FALSE
keyUsage=digitalSignature, keyEncipherment
extendedKeyUsage=serverAuth
subjectAltName=DNS:localhost,IP:127.0.0.1
EOF

openssl x509 -req -in server-tls.csr -CA ca.crt -CAkey ca.key -CAcreateserial \
  -out server-tls.crt -days $VALIDITY_DAYS -extfile server-ext.cnf 2>/dev/null

# Create Server PKCS12 keystore
openssl pkcs12 -export -in server-tls.crt -inkey server-tls.key -certfile ca.crt \
  -out server-keystore.p12 -name server -passout pass:$KEYSTORE_PASS

echo "  -> server-keystore.p12 created"

# ---------------------------------------------------------
# 3. Client TLS Certificate
# ---------------------------------------------------------
echo "[3/8] Generating Client TLS key and certificate..."
openssl genrsa -out client-tls.key $KEY_SIZE 2>/dev/null
openssl req -new -key client-tls.key -out client-tls.csr \
  -subj "/C=US/ST=California/L=Foster City/O=Visa Inc/OU=Client/CN=visa-client"

cat > client-ext.cnf <<EOF
authorityKeyIdentifier=keyid,issuer
basicConstraints=CA:FALSE
keyUsage=digitalSignature, keyEncipherment
extendedKeyUsage=clientAuth
EOF

openssl x509 -req -in client-tls.csr -CA ca.crt -CAkey ca.key -CAcreateserial \
  -out client-tls.crt -days $VALIDITY_DAYS -extfile client-ext.cnf 2>/dev/null

# Create Client PKCS12 keystore
openssl pkcs12 -export -in client-tls.crt -inkey client-tls.key -certfile ca.crt \
  -out client-keystore.p12 -name client -passout pass:$KEYSTORE_PASS

echo "  -> client-keystore.p12 created"

# ---------------------------------------------------------
# 4. Server Truststore (trusts CA, thus trusts client certs)
# ---------------------------------------------------------
echo "[4/8] Creating Server truststore..."
keytool -importcert -alias ca -file ca.crt \
  -keystore server-truststore.p12 -storetype PKCS12 \
  -storepass $KEYSTORE_PASS -noprompt 2>/dev/null

echo "  -> server-truststore.p12 created"

# ---------------------------------------------------------
# 5. Client Truststore (trusts CA, thus trusts server cert)
# ---------------------------------------------------------
echo "[5/8] Creating Client truststore..."
keytool -importcert -alias ca -file ca.crt \
  -keystore client-truststore.p12 -storetype PKCS12 \
  -storepass $KEYSTORE_PASS -noprompt 2>/dev/null

echo "  -> client-truststore.p12 created"

# ---------------------------------------------------------
# 6. MLE Server RSA Key Pair (for request encryption)
#    - Client encrypts requests with server's public cert
#    - Server decrypts requests with server's private key
# ---------------------------------------------------------
echo "[6/8] Generating MLE Server RSA key pair..."
openssl genrsa -out mle-server-private.key $KEY_SIZE 2>/dev/null
openssl req -new -x509 -key mle-server-private.key -out mle-server-public.pem \
  -days $VALIDITY_DAYS \
  -subj "/C=US/ST=California/L=Foster City/O=Visa Inc/OU=MLE Server/CN=mle-server"

# Convert private key to PKCS#1 format (BEGIN RSA PRIVATE KEY)
openssl rsa -in mle-server-private.key -out mle-server-private.pem 2>/dev/null

echo "  -> mle-server-private.pem (server keeps this secret)"
echo "  -> mle-server-public.pem  (shared with client)"

# ---------------------------------------------------------
# 7. MLE Client RSA Key Pair (for response encryption)
#    - Server encrypts responses with client's public cert
#    - Client decrypts responses with client's private key
# ---------------------------------------------------------
echo "[7/8] Generating MLE Client RSA key pair..."
openssl genrsa -out mle-client-private.key $KEY_SIZE 2>/dev/null
openssl req -new -x509 -key mle-client-private.key -out mle-client-public.pem \
  -days $VALIDITY_DAYS \
  -subj "/C=US/ST=California/L=Foster City/O=Visa Inc/OU=MLE Client/CN=mle-client"

# Convert private key to PKCS#1 format (BEGIN RSA PRIVATE KEY)
openssl rsa -in mle-client-private.key -out mle-client-private.pem 2>/dev/null

echo "  -> mle-client-private.pem (client keeps this secret)"
echo "  -> mle-client-public.pem  (shared with server)"

# ---------------------------------------------------------
# 8. Cleanup temporary files
# ---------------------------------------------------------
echo "[8/8] Cleaning up temporary files..."
rm -f *.csr *.cnf *.srl *.key

echo ""
echo "============================================================"
echo "  Certificate Generation Complete!"
echo "============================================================"
echo ""
echo "  mTLS Files:"
echo "    Server keystore:    certs/server-keystore.p12"
echo "    Server truststore:  certs/server-truststore.p12"
echo "    Client keystore:    certs/client-keystore.p12"
echo "    Client truststore:  certs/client-truststore.p12"
echo "    CA certificate:     certs/ca.crt"
echo ""
echo "  MLE Files:"
echo "    Server private key: certs/mle-server-private.pem"
echo "    Server public cert: certs/mle-server-public.pem"
echo "    Client private key: certs/mle-client-private.pem"
echo "    Client public cert: certs/mle-client-public.pem"
echo ""
echo "  Keystore password:    $KEYSTORE_PASS"
echo "============================================================"
