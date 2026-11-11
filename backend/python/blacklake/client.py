"""One transport gate for login and all allowlisted read-only POST requests."""
import hashlib
import base64
import json
import time
from copy import deepcopy
from typing import Callable

import httpx

from .contracts import BlacklakeError, ENDPOINTS, LOGIN, Settings, at, put


class BlacklakeConnector:
    def __init__(self, settings: Settings, contract: dict, coordination,
                 transport=None, sleep: Callable = time.sleep):
        self.settings, self.contract, self.coordination = settings, contract, coordination
        self.sleep = sleep
        self.factory_verified = False
        self.last_business_error = None
        self.http = httpx.Client(base_url=settings.base_url, transport=transport,
                                 timeout=30, follow_redirects=False, trust_env=False)

    def close(self):
        self.http.close()

    def _send(self, path, body, token=None, *, login_diagnostic=False):
        if path not in {LOGIN, *ENDPOINTS.values()}:
            raise BlacklakeError("READ_ONLY_ALLOWLIST_REJECTED")
        if login_diagnostic and path != LOGIN:
            raise BlacklakeError("DIAGNOSTIC_LOGIN_ONLY")
        self.coordination.check_lease()
        self.coordination.acquire(path, body, login=path == LOGIN)
        headers = {"X-CLIENT": "lite-web", "Content-Type": "application/json"}
        if token:
            headers["X-AUTH"] = token
        try:
            response = self.http.post(path, json=body, headers=headers)
        except httpx.HTTPError:
            raise BlacklakeError("UPSTREAM_TRANSPORT_ERROR") from None
        if response.status_code == 401:
            return None
        if response.status_code in (429, 502, 503, 504):
            raise BlacklakeError("UPSTREAM_RETRY_NEXT_RUN")
        if response.status_code != 200:
            raise BlacklakeError("UPSTREAM_HTTP_ERROR")
        try:
            data = response.json()
        except ValueError:
            raise BlacklakeError("UPSTREAM_INVALID_JSON") from None
        if login_diagnostic:
            if path != LOGIN:
                raise BlacklakeError("DIAGNOSTIC_LOGIN_ONLY")
            return data
        code_path = self.contract.get("login_code_path", self.contract.get("code_path")) if path == LOGIN else self.contract["code_path"]
        code = at(data, code_path)
        if str(code) in self.contract.get("auth_expired_codes", []):
            return None
        success_code = self.contract.get("login_success_code", "01000000") if path == LOGIN else "01000000"
        if code != success_code:
            self.last_business_error = data
            safe_code = str(code)
            suffix = ":" + safe_code if safe_code.isdecimal() and len(safe_code) <= 8 else ""
            raise BlacklakeError("UPSTREAM_BUSINESS_ERROR" + suffix)
        return data

    def _token(self):
        token = self.coordination.get_token()
        factory_path = self.contract.get("login_factory_path")
        factory_claim = self.contract.get("login_factory_claim")
        if token and (factory_path or factory_claim):
            if self.coordination.get_factory() != self.settings.source_factory_id:
                self.coordination.clear_token()
                token = None
            else:
                self.factory_verified = True
        if token:
            return token
        result = self._send(LOGIN, {**self.settings.login_body_identity,
                                  "password": hashlib.sha3_224(
                                      self.settings.password.encode("utf-8")).hexdigest()})
        if result is None:
            raise BlacklakeError("LOGIN_REJECTED")
        token = at(result, self.contract["token_path"])
        if not isinstance(token, str) or not token:
            raise BlacklakeError("LOGIN_TOKEN_MISSING")
        factory = None
        if factory_path:
            factory = str(at(result, factory_path))
        elif factory_claim:
            # Identity metadata ONLY from token just issued over pinned HTTPS login.
            # Never used to authenticate a Cretas caller or verify a supplied JWT.
            try:
                encoded = token.split(".")[1]
                claims = json.loads(base64.urlsafe_b64decode(encoded + "=" * (-len(encoded) % 4)))
                factory = str(at(claims, factory_claim))
            except (ValueError, IndexError):
                raise BlacklakeError("LOGIN_FACTORY_CLAIM_INVALID") from None
        if factory_path or factory_claim:
            if factory != self.settings.source_factory_id:
                raise BlacklakeError("LOGIN_FACTORY_MISMATCH")
            self.factory_verified = True
        self.coordination.set_token(token, factory)
        return token

    def query(self, resource: str, body: dict):
        if resource not in ENDPOINTS:
            raise BlacklakeError("READ_ONLY_ALLOWLIST_REJECTED")
        if resource == "stock_order" and (
                type(body.get("orderType")) is not int or body["orderType"] not in (10, 20)):
            raise BlacklakeError("STOCK_ORDER_TYPE_REQUIRED")
        result = self._send(ENDPOINTS[resource], body, self._token())
        if result is None:
            self.coordination.clear_token()
            result = self._send(ENDPOINTS[resource], body, self._token())
        if result is None:
            raise BlacklakeError("AUTH_REFRESH_EXHAUSTED")
        return result

    def pages(self, resource: str, body: dict, page_size=100, max_pages=10000):
        if type(page_size) is not int or not 1 <= page_size <= 100:
            raise BlacklakeError("INVALID_PAGE_SIZE")
        spec = self.contract["resources"][resource]
        seen, expected, count = set(), None, 0
        for page in range(spec["first_page"], spec["first_page"] + max_pages):
            payload = deepcopy(body)
            put(payload, spec["page_key"], page)
            put(payload, spec["size_key"], page_size)
            response = self.query(resource, payload)
            rows, total = at(response, spec["items_path"]), at(response, spec["total_path"])
            # Verified 2026-11-03 against the production read-only negative
            # time-filter control: output uses data=null with page.total=0.
            if resource == "output" and rows is None and type(total) is int and total == 0:
                rows = []
            if (not isinstance(rows, list) or type(total) is not int or total < 0
                    or len(rows) > page_size or any(not isinstance(row, dict) for row in rows)):
                raise BlacklakeError("PAGINATION_SHAPE_INVALID")
            if expected is not None and total != expected:
                raise BlacklakeError("PAGINATION_TOTAL_CHANGED")
            expected = total
            fingerprint = hashlib.sha256(json.dumps(rows, sort_keys=True).encode()).hexdigest()
            if rows and fingerprint in seen:
                raise BlacklakeError("PAGINATION_REPEATED_PAGE")
            seen.add(fingerprint)
            count += len(rows)
            if count > total or (not rows and count < total):
                raise BlacklakeError("PAGINATION_INCOMPLETE")
            yield rows
            if count == total:
                return
        raise BlacklakeError("PAGINATION_LIMIT_EXCEEDED")
