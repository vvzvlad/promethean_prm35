#!/system/bin/sh
# Promethean PRM-35 projector on the box's ethernet port.
# The port is taken away from Android, the projector gets 192.168.77.2 over DHCP,
# and its control port is published on the box's Wi-Fi address:
# <box Wi-Fi IP>:8000 -> 192.168.77.2:8000 (port 23 is closed on this unit).
# Started by /system/etc/init/projector-net.rc; idempotent, ends in dnsmasq.

IF=lan0
PROJ=192.168.77.2

# Android's EthernetService only manages eth<N>: renaming the port keeps it from
# running a DHCP client there and flushing our address.
if [ -e /sys/class/net/eth0 ]; then
    ip link set eth0 down
    ip link set eth0 name $IF
fi
ip link set $IF up
ip addr replace 192.168.77.1/24 dev $IF

# netd routes by per-network tables: reach the projector subnet through main,
# send replies from it through the Wi-Fi table (wait until netd has created it).
i=0
while ! ip route show table wlan0 2>/dev/null | grep -q default && [ $i -lt 120 ]; do
    sleep 1; i=$((i + 1))
done
ip rule del pref 9000 2>/dev/null; ip rule add pref 9000 to 192.168.77.0/24 lookup main
ip rule del pref 9001 2>/dev/null; ip rule add pref 9001 iif $IF lookup wlan0

echo 1 > /proc/sys/net/ipv4/ip_forward
iptables -t nat -C oem_nat_pre -i wlan0 -p tcp --dport 8000 -j DNAT --to-destination $PROJ:8000 2>/dev/null ||
    iptables -t nat -A oem_nat_pre -i wlan0 -p tcp --dport 8000 -j DNAT --to-destination $PROJ:8000
iptables -t nat -C POSTROUTING -o $IF -j MASQUERADE 2>/dev/null ||
    iptables -t nat -A POSTROUTING -o $IF -j MASQUERADE
# tetherctrl_FORWARD ends in DROP, so accept our flows earlier, in oem_fwd.
iptables -C oem_fwd -i wlan0 -o $IF -d $PROJ -p tcp --dport 8000 -j ACCEPT 2>/dev/null ||
    iptables -A oem_fwd -i wlan0 -o $IF -d $PROJ -p tcp --dport 8000 -j ACCEPT
iptables -C oem_fwd -i $IF -o wlan0 -m state --state ESTABLISHED,RELATED -j ACCEPT 2>/dev/null ||
    iptables -A oem_fwd -i $IF -o wlan0 -m state --state ESTABLISHED,RELATED -j ACCEPT

# Android's dnsmasq polls stdin for netd's control commands; on /dev/null that read returns
# EOF at once and dnsmasq spins at 100% CPU. A FIFO opened read-write never has data and
# never reports EOF.
FIFO=/data/local/tmp/projector-dnsmasq.stdin
[ -p $FIFO ] || mkfifo $FIFO

# A single-address pool, so the projector always gets the same IP; no gateway, no DNS.
exec /system/bin/dnsmasq --keep-in-foreground --conf-file=/dev/null --port=0 \
    --user=root --group=root --pid-file= \
    --interface=$IF --bind-interfaces --dhcp-authoritative \
    --dhcp-range=$PROJ,$PROJ,255.255.255.0,12h \
    --dhcp-option=3 --dhcp-option=6 \
    --dhcp-leasefile=/data/local/tmp/projector-dnsmasq.leases <>$FIFO
