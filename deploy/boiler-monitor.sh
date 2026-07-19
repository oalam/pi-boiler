#!/bin/bash
# Chien de garde applicatif de la régulation chaudière.
#
# Lancé chaque minute par boiler-monitor.timer :
#   1. API de régulation muette  -> redémarre boiler.service + alerte ntfy
#   2. Sonde 1-Wire muette       -> alerte ntfy (la régulation est aveugle)
#      Température >= TEMP_MAX   -> alerte ntfy urgente
#   3. Heartbeat healthchecks.io -> détection externe d'un Pi mort/injoignable
#
# Anti-spam : n'alerte qu'aux changements d'état, flags dans /run (tmpfs,
# remis à zéro au boot). Config dans /etc/boiler-monitor.conf.

CONF=/etc/boiler-monitor.conf
[ -f "$CONF" ] && . "$CONF"

API="http://192.168.1.12:9000/api/v0.1/action?type=none"
W1=/sys/bus/w1/devices/28-021581cc38ff/w1_slave
STATE=/run/boiler-monitor
TEMP_MAX=${TEMP_MAX:-90}
mkdir -p "$STATE"

notify() { # $1 priorité, $2 titre, $3 message
    [ -n "$NTFY_TOPIC" ] || return 0
    curl -s -m 10 -H "Priority: $1" -H "Title: $2" -d "$3" \
        "https://ntfy.sh/$NTFY_TOPIC" > /dev/null || true
}

# retourne 0 uniquement lors de la transition (pose/retrait du flag)
flag_raise() { [ -f "$STATE/$1" ] && return 1; touch "$STATE/$1"; }
flag_clear() { [ -f "$STATE/$1" ] || return 1; rm -f "$STATE/$1"; }

# 1. API de régulation
if curl -s -m 8 "$API" | grep -q ok; then
    flag_clear api_down && notify default "Chaudière" "API de régulation rétablie"
else
    systemctl restart boiler.service
    flag_raise api_down && notify high "Chaudière" \
        "API de régulation muette — service redémarré automatiquement"
fi

# 2. sonde de température + surchauffe
w1=$(cat "$W1" 2>/dev/null)
if echo "$w1" | head -1 | grep -q YES; then
    flag_clear sonde && notify default "Chaudière" "Sonde de température rétablie"
    temp=$(( $(echo "$w1" | sed -n 's/.*t=//p') / 1000 ))
    if [ "$temp" -ge "$TEMP_MAX" ]; then
        flag_raise surchauffe && notify urgent "Chaudière SURCHAUFFE" \
            "Température eau : ${temp} °C (seuil ${TEMP_MAX} °C)"
    else
        flag_clear surchauffe && notify high "Chaudière" "Fin de surchauffe : ${temp} °C"
    fi
else
    flag_raise sonde && notify urgent "Chaudière" \
        "Sonde de température muette — régulation aveugle"
fi

# 3. heartbeat (dead man's switch) — silencieux, l'alerte vient de healthchecks.io
if [ -n "$HEALTHCHECKS_URL" ]; then
    curl -fsS -m 10 -o /dev/null "$HEALTHCHECKS_URL" || true
fi
