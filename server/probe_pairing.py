"""Check owner-authorized HTTPS device pairing on the deployed VPS.

Consumes its own disposable pairing code; never prints the bearer token.
"""
import json
import os
from urllib.request import Request, urlopen
from urllib.error import HTTPError
from server.pairing import issue_code

URL = "https://max-vpn.mask-0f-darkness.ru/api/pair"


def main():
    code = issue_code()
    body = json.dumps({"code": code}).encode("ascii")
    def post():
        return urlopen(Request(URL, method="POST", data=body,
                       headers={"Content-Type": "application/json"}), timeout=20)

    with post() as response:
        payload = json.loads(response.read())
        assert response.status == 200
        assert payload["mode"] == "direct_vps_https"
        assert payload["token"] == os.environ["MAXVPN_RELAY_TOKEN"]
    print("VPS_DEVICE_PAIR_REDEEM_PASS", flush=True)
    try:
        post()
    except HTTPError as error:
        if error.code == 403:
            print("VPS_DEVICE_PAIR_REPLAY_REJECTED_PASS", flush=True)
            return
        raise
    raise AssertionError("already redeemed code accepted again")


if __name__ == "__main__":
    main()
