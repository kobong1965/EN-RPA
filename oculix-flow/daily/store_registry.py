"""Explicit account-to-shop bindings shared by planning and collection."""
from __future__ import annotations

import json
from pathlib import Path
import re
import unicodedata

CONFIG_PATH = Path(__file__).resolve().with_name("stores.json")
DEFAULT_STORE_ID = "neutcut"


class StoreConfigurationError(ValueError):
    pass


def normalized_text(value: str) -> str:
    return "".join(unicodedata.normalize("NFKC", str(value)).split())


def configured_stores(path: Path = CONFIG_PATH) -> list[dict]:
    try:
        payload = json.loads(path.read_text(encoding="utf-8-sig"))
        stores = payload["stores"]
        if not isinstance(stores, list) or not stores:
            raise ValueError("stores must be nonempty")
        identifiers, accounts = set(), set()
        for store in stores:
            identifier, account = store["storeId"], store["targetAccount"]
            if not re.fullmatch(r"[a-z][a-z0-9_-]{0,63}", identifier) or not account.strip():
                raise ValueError("invalid identifier or account")
            account = unicodedata.normalize("NFKC", account).strip()
            if identifier in identifiers or account in accounts:
                raise ValueError("duplicate store or account")
            identifiers.add(identifier)
            accounts.add(account)
            if store.get("confirmed") is True and (not store.get("storeName", "").strip() or not store.get("pageStoreName", "").strip()):
                raise ValueError("confirmed shop requires exact name")
        return stores
    except (OSError, KeyError, TypeError, ValueError) as exc:
        raise StoreConfigurationError("店铺配置无效，请检查 stores.json 中的唯一店铺与完整账号绑定") from exc


def get_store(store_id: str = DEFAULT_STORE_ID) -> dict:
    matches = [store for store in configured_stores() if store["storeId"] == store_id]
    if len(matches) != 1 or matches[0].get("confirmed") is not True:
        raise StoreConfigurationError("店铺尚未确认账号与店名绑定，不能采集或导入：" + str(store_id))
    return matches[0]


def validated_request_store(request: dict) -> dict:
    store = get_store(request.get("storeId", ""))
    for name in ("targetAccount", "storeName", "pageStoreName"):
        # Legacy 示例店铺A requests omit names; the confirmed registry supplies them.
        if name == "targetAccount" or name in request:
            if request.get(name) != store[name]:
                raise StoreConfigurationError("采集请求与已确认的店铺绑定不一致：" + name)
    return store


def verify_page_store(text: str, store: dict) -> None:
    expected = normalized_text(store["pageStoreName"]) + "主店"
    if expected not in {normalized_text(line) for line in text.splitlines() if line.strip()}:
        raise StoreConfigurationError("复制原文没有确认 " + store["pageStoreName"] + "主店，未生成导入数据")
