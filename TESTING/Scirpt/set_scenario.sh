cd ~

cat > set_scenario.sh << 'EOF'
#!/usr/bin/env bash

# Traffic shaping for CMPE 482 scenarios
# Assumes main interface is eth0 (WSL2 default).
# Change IF below if your interface name is different.
IF=eth0

reset_qdisc() {
  sudo tc qdisc del dev "$IF" root 2>/dev/null || true
}

case "$1" in
  baseline)
    echo "[Scenario 1] Baseline (Full speed & fast RTT)"
    echo " -> Removing all shaping."
    reset_qdisc
    ;;

  stable_low)
    echo "[Scenario 2] Stable Low bandwidth (1000kbps, ~200ms RTT)"
    reset_qdisc

    # 1000 kbps, ~200 ms RTT (≈100 ms one-way delay)
    sudo tc qdisc add dev "$IF" root handle 1: htb default 10
    sudo tc class add dev "$IF" parent 1: classid 1:10 htb rate 1000kbit
    sudo tc qdisc add dev "$IF" parent 1:10 handle 10: netem delay 100ms

    tc qdisc show dev "$IF"
    ;;

  sudden_drop)
    echo "[Scenario 3] Sudden Drop"
    echo " -> Phase 1 (0–30s): 10Mbps, ~50ms RTT"
    echo " -> Phase 2 (30s+): 2Mbps, ~150ms RTT"

    reset_qdisc

    # Phase 1: 10 Mbps, ~50 ms RTT (≈25 ms one-way)
    sudo tc qdisc add dev "$IF" root handle 1: htb default 10
    sudo tc class add dev "$IF" parent 1: classid 1:10 htb rate 10mbit
    sudo tc qdisc add dev "$IF" parent 1:10 handle 10: netem delay 25ms

    echo "Phase 1 active: 10Mbps, ~50ms RTT for 30 seconds..."
    sleep 30

    echo "Switching to Phase 2: 2Mbps, ~150ms RTT (until changed)."
    reset_qdisc

    # Phase 2: 2 Mbps, ~150 ms RTT (≈75 ms one-way)
    sudo tc qdisc add dev "$IF" root handle 1: htb default 10
    sudo tc class add dev "$IF" parent 1: classid 1:10 htb rate 2mbit
    sudo tc qdisc add dev "$IF" parent 1:10 handle 10: netem delay 75ms

    tc qdisc show dev "$IF"
    ;;

  jitter)
    echo "[Scenario 4] Jitter"
    echo " -> Alternates every 10s:"
    echo "    * High: 6Mbps, ~50ms RTT (720p-capable)"
    echo "    * Low : 600kbps, ~300ms RTT (low qualities)"
    echo "Press Ctrl+C to stop, then run 'baseline' or another scenario."

    while true; do
      echo "High phase: 6Mbps, ~50ms RTT"
      reset_qdisc
      # High: 6 Mbps, ~50 ms RTT (≈25 ms one-way)
      sudo tc qdisc add dev "$IF" root handle 1: htb default 10
      sudo tc class add dev "$IF" parent 1: classid 1:10 htb rate 6mbit
      sudo tc qdisc add dev "$IF" parent 1:10 handle 10: netem delay 25ms
      sleep 10

      echo "Low phase: 600kbps, ~300ms RTT"
      reset_qdisc
      # Low: 600 kbps, ~300 ms RTT (≈150 ms one-way)
      sudo tc qdisc add dev "$IF" root handle 1: htb default 10
      sudo tc class add dev "$IF" parent 1: classid 1:10 htb rate 600kbit
      sudo tc qdisc add dev "$IF" parent 1:10 handle 10: netem delay 150ms
      sleep 10
    done
    ;;

  off)
    echo "Removing all shaping (same as 'baseline')."
    reset_qdisc
    ;;

  *)
    echo "Usage: $0 {baseline|stable_low|sudden_drop|jitter|off}"
    exit 1
    ;;
esac
EOF
