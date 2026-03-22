from flask import Flask, request, jsonify
from flask_cors import CORS
import tinytuya
import json

app = Flask(__name__)
CORS(app)

bulb = None


@app.route("/cmd", methods=["POST"])
def cmd():
    global bulb
    data = request.json
    action = data.get("action")

    try:
        if action == "connect":
            bulb = tinytuya.BulbDevice(
                dev_id=data["id"],
                address=data["ip"],
                local_key=data["key"],
                version=data["version"],
            )
            bulb.set_socketPersistent(True)
            return jsonify(ok=True, msg=f"connected to {data['ip']}")

        if bulb is None:
            return jsonify(ok=False, msg="not connected")

        if action == "power":
            bulb.set_value(20, data["on"])
            return jsonify(ok=True, msg="power " + ("on" if data["on"] else "off"))

        if action == "mode":
            bulb.set_value(21, data["mode"])
            return jsonify(ok=True, msg="mode → " + data["mode"])

        if action == "colour":
            h, s, v = data["h"], data["s"], data["v"]
            colour = json.dumps({"h": h, "s": s, "v": v}, separators=(",", ":"))
            bulb.set_value(21, "colour")
            bulb.set_value(24, colour)
            return jsonify(ok=True, msg=f"colour H:{h} S:{s} V:{v}")

        if action == "white":
            bulb.set_value(21, "white")
            bulb.set_value(22, data.get("brightness", 1000))
            bulb.set_value(23, data.get("temp", 500))
            return jsonify(ok=True, msg="white mode")

        return jsonify(ok=False, msg="unknown action")

    except Exception as e:
        return jsonify(ok=False, msg=str(e))


if __name__ == "__main__":
    app.run(port=5000)
