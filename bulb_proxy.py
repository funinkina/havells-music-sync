from flask import Flask, request, jsonify
from flask_cors import CORS
import tinytuya
import json

app = Flask(__name__)
CORS(app)

bulb = None


def clamp(value, low, high):
    return max(low, min(high, value))


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
            bulb.set_socketPersistent(True)
            return jsonify(ok=True, msg=f"connected to {data['ip']} (v{version})")

        if bulb is None:
            return jsonify(ok=False, msg="not connected")

        if action == "power":
            bulb.set_value(20, data["on"])
            return jsonify(ok=True, msg="power " + ("on" if data["on"] else "off"))

        if action == "mode":
            bulb.set_value(21, data["mode"])
            return jsonify(ok=True, msg="mode → " + data["mode"])

        if action == "colour":
            h = clamp(int(data.get("h", 0)), 0, 360)
            s = clamp(int(data.get("s", 0)), 0, 1000)
            v = clamp(int(data.get("v", 0)), 0, 1000)

            # Send multiple DPs at once, use exact string format that Tuya expects for this bulb
            color_str = f'{{"h":{h},"s":{s},"v":{v}}}'
            payload = {"21": "colour", "24": color_str}
            res = bulb.set_multiple_values(payload)
            # fallback if set_multiple_values fails:
            if not res:
                bulb.set_value(21, "colour")
                bulb.set_value(24, color_str)

            return jsonify(ok=True, msg=f"colour H:{h} S:{s} V:{v}", res=res)

        if action == "white":
            brightness = clamp(int(data.get("brightness", 1000)), 10, 1000)
            temp = clamp(int(data.get("temp", 500)), 0, 1000)

            payload = {"21": "white", "22": brightness, "23": temp}
            res = bulb.set_multiple_values(payload)
            # fallback:
            if not res:
                bulb.set_value(21, "white")
                bulb.set_value(22, brightness)
                bulb.set_value(23, temp)
            return jsonify(ok=True, msg=f"white B:{brightness} T:{temp}", res=res)

        return jsonify(ok=False, msg="unknown action")

    except Exception as e:
        return jsonify(ok=False, msg=str(e))


if __name__ == "__main__":
    app.run(port=5000)
