from flask import Flask, request, jsonify
from flask_cors import CORS
import tinytuya

app = Flask(__name__)
CORS(app)

bulb = None

# Type-B RGB+CCT (DP 20–26): cloud docs say colour_data_v2 is JSON, but local LAN uses
# 12 hex chars = hhhhssssvvvv (hue 0–360, s/v 0–1000) — see tinytuya BulbDevice hsv16.
WORK_MODES = frozenset({"white", "colour", "scene", "music"})


def clamp(value, low, high):
    return max(low, min(high, value))


def colour_data_v2_hex(h: int, s: int, v: int) -> str:
    """Encode colour_data_v2 for local protocol (matches device status format)."""
    h = clamp(int(h), 0, 360)
    s = clamp(int(s), 0, 1000)
    v = clamp(int(v), 0, 1000)
    return f"{h:04x}{s:04x}{v:04x}"


def _tuya_command_ok(res):
    """tinytuya returns False/None or a dict with Error on failure."""
    if res is None or res is False:
        return False
    if isinstance(res, dict) and res.get("Error"):
        return False
    return True


def _tuya_fail_reason(res):
    if isinstance(res, dict) and res.get("Error"):
        return str(res.get("Error"))
    return "no valid response from device (check LAN IP, local key, protocol version, or power)"


@app.route("/cmd", methods=["POST"])
def cmd():
    global bulb
    data = request.json
    action = data.get("action")

    try:
        if action == "connect":
            version = float(data.get("version", 3.5))
            bulb = tinytuya.BulbDevice(
                dev_id=data["id"],
                address=data["ip"],
                local_key=data["key"],
                version=version,
            )
            bulb.set_version(version)
            bulb.set_socketPersistent(False)
            bulb.set_socketRetryLimit(2)
            bulb.set_socketTimeout(5.0)
            bulb.set_bulb_type("B")
            bulb.dpset["music"] = "27"

            bulb.set_socketRetryLimit(1)
            bulb.set_socketTimeout(3.0)
            st = bulb.status()
            bulb.set_socketRetryLimit(2)
            bulb.set_socketTimeout(5.0)

            if not _tuya_command_ok(st):
                bulb = None
                return jsonify(
                    ok=False,
                    msg=_tuya_fail_reason(st),
                    tuya=st if isinstance(st, dict) else None,
                )
            bulb.dpset["music"] = "27"
            return jsonify(
                ok=True,
                msg=f"reachable at {data['ip']} (v{version})",
                dps=st.get("dps") if isinstance(st, dict) else None,
            )

        if bulb is None:
            return jsonify(ok=False, msg="not connected")

        if action == "power":
            res = bulb.set_value(20, data["on"])
            ok = _tuya_command_ok(res)
            return jsonify(
                ok=ok,
                msg="power " + ("on" if data["on"] else "off")
                if ok
                else _tuya_fail_reason(res),
                tuya=res if isinstance(res, dict) else None,
            )

        if action == "mode":
            mode = data["mode"]
            if mode not in WORK_MODES:
                return jsonify(ok=False, msg=f"invalid work_mode: {mode!r}")
            res = bulb.set_value(21, mode)
            ok = _tuya_command_ok(res)
            return jsonify(
                ok=ok,
                msg="mode → " + mode if ok else _tuya_fail_reason(res),
                tuya=res if isinstance(res, dict) else None,
            )

        if action == "colour":
            h = int(data.get("h", 0))
            s = int(data.get("s", 0))
            v = int(data.get("v", 0))
            colour_hex = colour_data_v2_hex(h, s, v)
            payload = {
                "20": True,
                "21": "colour",
                "24": colour_hex,
            }
            res = bulb.set_multiple_values(payload)
            ok = _tuya_command_ok(res)
            if not ok:
                r1 = bulb.set_value(20, True)
                r2 = bulb.set_value(21, "colour")
                r3 = bulb.set_value(24, colour_hex)
                ok = (
                    _tuya_command_ok(r1)
                    and _tuya_command_ok(r2)
                    and _tuya_command_ok(r3)
                )
            msg_ok = f"colour H:{clamp(h,0,360)} S:{clamp(s,0,1000)} V:{clamp(v,0,1000)}"
            return jsonify(
                ok=ok,
                msg=msg_ok if ok else _tuya_fail_reason(res),
                res=res,
            )

        if action == "white":
            brightness = clamp(int(data.get("brightness", 1000)), 10, 1000)
            temp = clamp(int(data.get("temp", 500)), 0, 1000)
            payload = {
                "20": True,
                "21": "white",
                "22": brightness,
                "23": temp,
            }
            res = bulb.set_multiple_values(payload)
            ok = _tuya_command_ok(res)
            if not ok:
                r1 = bulb.set_value(20, True)
                r2 = bulb.set_value(21, "white")
                r3 = bulb.set_value(22, brightness)
                r4 = bulb.set_value(23, temp)
                ok = (
                    _tuya_command_ok(r1)
                    and _tuya_command_ok(r2)
                    and _tuya_command_ok(r3)
                    and _tuya_command_ok(r4)
                )
            msg_ok = f"white B:{brightness} T:{temp}"
            return jsonify(ok=ok, msg=msg_ok if ok else _tuya_fail_reason(res), res=res)

        return jsonify(ok=False, msg="unknown action")

    except Exception as e:
        return jsonify(ok=False, msg=str(e))


if __name__ == "__main__":
    app.run(port=5000)
