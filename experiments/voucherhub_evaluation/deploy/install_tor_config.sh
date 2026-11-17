#!/usr/bin/env bash
set -euo pipefail

config=${1:?usage: install_tor_config.sh CONFIG_FILE}

sudo install -d -m 755 /etc/tor/torrc.d
sudo install -m 644 "$config" /etc/tor/torrc.d/voucherhub-payee.conf
if ! sudo grep -q '^%include /etc/tor/torrc.d/\*\.conf$' /etc/tor/torrc; then
  sudo sed -i '$a %include /etc/tor/torrc.d/*.conf' /etc/tor/torrc
fi
sudo systemctl restart tor@default
sudo systemctl restart tor

for _ in $(seq 1 60); do
  if sudo test -s /var/lib/tor/voucherhub-payee/hostname; then
    sudo cat /var/lib/tor/voucherhub-payee/hostname
    exit 0
  fi
  sleep 1
done

echo 'Tor onion hostname was not created' >&2
exit 1
