#!/usr/bin/env bash
# Generate throwaway credentials so payment-gateway and bank-simulator can talk SNAP BI locally.
# Writes services/bank-simulator/dev/.keys/dev.env (git-ignored). Never use these outside your machine.
#
#   bash services/bank-simulator/dev/dev-keys.sh
#   set -a; source services/bank-simulator/dev/.keys/dev.env; set +a
#   java -jar services/bank-simulator/target/bank-simulator.jar &
#   java -jar services/payment-gateway/target/payment-gateway.jar &
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)/.keys"
mkdir -p "$DIR"
chmod 700 "$DIR"
cd "$DIR"
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out gateway.key 2>/dev/null
openssl pkey -in gateway.key -pubout -out gateway.pub
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out bank.key 2>/dev/null
openssl pkey -in bank.key -pubout -out bank.pub
q() { printf "'%s'" "$(cat "$1")"; }
{
  echo "BNI_ENABLED=true"
  echo "BNI_BASE_URL=http://localhost:8103/bni"
  echo "BNI_CLIENT_KEY=local-gateway"
  echo "BNI_CLIENT_SECRET=$(openssl rand -hex 32)"
  echo "BNI_PRIVATE_KEY=$(q gateway.key)"
  echo "BNI_PARTNER_ID=LOCALPARTNER"
  echo "BNI_CHANNEL_ID=95221"
  echo "BNI_VA_PARTNER_SERVICE_ID=98829"
  echo "BNI_INBOUND_CLIENT_KEY=local-bni"
  echo "BNI_INBOUND_CLIENT_SECRET=$(openssl rand -hex 32)"
  echo "BNI_INBOUND_PUBLIC_KEY=$(q bank.pub)"
  echo "SIMULATOR_PARTNER_PUBLIC_KEY=$(q gateway.pub)"
  echo "SIMULATOR_BANK_PRIVATE_KEY=$(q bank.key)"
  echo "SIMULATOR_SLOW_DELAY=35s"
} > dev.env
chmod 600 dev.env ./*.key
echo "Wrote $DIR/dev.env"
