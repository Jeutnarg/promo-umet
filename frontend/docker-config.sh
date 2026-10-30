#!/bin/sh
set -eu
cat > /usr/share/nginx/html/config.js <<EOF
window.__CONFIG__ = {app:"${APP_MODE:-customer}", api:"${API_URL:-http://localhost:8081}", authority:"${OIDC_AUTHORITY:-http://localhost:8080/realms/sandbox}", clientId:"${OIDC_CLIENT_ID:-sandbox-web}", scope:"${OIDC_SCOPE:-openid profile email}", adminUrl:"${ADMIN_URL:-http://localhost:3001}", customerUrl:"${CUSTOMER_URL:-http://localhost:3000}"};
EOF
