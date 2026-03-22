import tinytuya

d = tinytuya.Device(
    dev_id="d76bde5d392473b313toiy",
    address="192.168.0.100",
    local_key="tG:LKqa>K^HCJhi?",
)

d.set_version(3.5)

# turn ON
d.set_status(True, 20)

# turn OFF
d.set_status(False, 20)
