"""Real disposable FastAPI + browser acceptance; never production credentials."""

import json
import sys
from pathlib import Path

from playwright.sync_api import expect, sync_playwright

BASE = "http://127.0.0.1:5184"
KEY = {"X-Fixture-Key": "fixture-only-18084"}
OUT = Path(".local/system-settings-worker/evidence")
OUT.mkdir(parents=True, exist_ok=True)
with sync_playwright() as p:
    browser = p.chromium.launch(
        headless=True,
        executable_path="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
    )
    ctx = browser.new_context(viewport={"width": 1440, "height": 1000})
    ctx.add_init_script(
        "localStorage.setItem('finora.language','en');if(!localStorage.getItem('finora.appearance'))localStorage.setItem('finora.appearance','light')"
    )
    request = ctx.request
    if "--after-restart" not in sys.argv:
        assert request.post(BASE + "/api/__fixture/reset", headers=KEY).ok
    page = ctx.new_page()
    page.goto(BASE + "/#system-settings")
    page.locator('input[autocomplete="username"]').fill("systemadmin")
    page.locator('input[autocomplete="current-password"]').fill("Fixture-Only-18084!")
    with page.expect_response(lambda r: "/api/auth/login" in r.url) as login:
        page.locator('form button[type="submit"]').click()
    assert login.value.ok
    page.wait_for_timeout(400)
    page.goto(BASE + "/#system-settings")
    page.locator('[data-group="ai"]').wait_for()
    if "--after-restart" not in sys.argv:
        ai = page.locator('[data-group="ai"]')
        timeout = page.locator("#system-ai_timeout")
        timeout.fill("43")
        ai.get_by_role("button", name="Save section", exact=True).click()
        page.wait_for_timeout(700)
        page.screenshot(path=str(OUT / "first-save.png"), full_page=True)
        expect(
            ai.get_by_role("button", name="Save section", exact=True)
        ).to_be_disabled()
        expect(timeout).to_have_value("43")
        state = request.get(BASE + "/api/__fixture/state", headers=KEY).json()
        assert state["overrides"]["ai_timeout"] == 43, state
        page.reload()
        page.locator('[data-group="ai"]').wait_for()
        expect(timeout).to_have_value("43")
        # Real CAS conflict: stale form must not overwrite another editor.
        timeout.fill("45")
        snap = request.get(BASE + "/api/system/settings").json()
        me = request.get(BASE + "/api/auth/me").json()
        headers = {"X-CSRF-Token": me["csrf"], "X-Finora-Client": "web"}
        assert request.put(
            BASE + "/api/system/settings",
            headers=headers,
            data={
                "revision": snap["revision"],
                "changes": {"ai_cpu_threads": 2},
                "secrets": {},
            },
        ).ok
        ai.get_by_role("button", name="Save section", exact=True).click()
        expect(page.locator(".system-settings")).to_contain_text(
            "Settings have changed"
        )
        assert (
            request.get(BASE + "/api/__fixture/state", headers=KEY).json()["overrides"][
                "ai_timeout"
            ]
            == 43
        )
        page.reload()
        page.locator('[data-group="ai"]').wait_for()
        # Synthetic credential replacement, then explicit disable and inherit; no screenshot with input.
        action = page.locator("#system-global_openai_key-action")
        action.select_option("replace")
        page.locator("#system-global_openai_key").fill("fixture-synthetic-openai-only")
        ai.get_by_role("button", name="Save section", exact=True).click()
        expect(action).to_have_value("keep")
        assert page.locator("#system-global_openai_key").count() == 0
        assert request.get(BASE + "/api/__fixture/state", headers=KEY).json()[
            "secret_configured"
        ]["global_openai_key"]
        for mode in ("disable", "inherit"):
            action.select_option(mode)
            field = page.locator('[data-field="global_openai_key"]')
            field.locator('input[type="checkbox"]').check()
            ai.get_by_role("button", name="Save section", exact=True).click()
            expect(action).to_have_value("keep")
            assert (
                request.get(BASE + "/api/__fixture/state", headers=KEY).json()[
                    "secret_modes"
                ]["global_openai_key"]
                == mode
            )
        # Real backend validation failure must clear newly entered credential.
        action.select_option("replace")
        page.locator("#system-global_openai_key").fill("invalid-fixture-key")
        page.locator("#system-ai_timeout").fill("44")
        original_revision = request.get(BASE + "/api/system/settings").json()[
            "revision"
        ]

        # Force actual backend 422 through a known invalid field while retaining real transport.
        def invalid_put(route):
            body = route.request.post_data_json
            body["changes"]["ai_timeout"] = 0
            response = route.fetch(post_data=json.dumps(body))
            route.fulfill(response=response)

        page.route(
            "**/api/system/settings",
            lambda route: (
                invalid_put(route)
                if route.request.method == "PUT"
                else route.continue_()
            ),
        )
        ai.get_by_role("button", name="Save section", exact=True).click()
        expect(page.locator(".system-settings")).to_contain_text(
            "Settings were not saved"
        )
        expect(action).to_have_value("keep")
        assert page.locator("#system-global_openai_key").count() == 0
        assert (
            request.get(BASE + "/api/system/settings").json()["revision"]
            == original_revision
        )
        page.unroute("**/api/system/settings")
        page.reload()
        page.locator('[data-group="ai"]').wait_for()
        # Cancel is local only.
        timeout.fill("46")
        ai.get_by_role("button", name="Cancel changes", exact=True).click()
        expect(timeout).to_have_value("43")
        assert (
            request.get(BASE + "/api/system/settings").json()["revision"]
            == original_revision
        )
        state = request.get(BASE + "/api/__fixture/state", headers=KEY).json()
        # Auth and CSRF safeguards use the real API, not a mock response.
        assert (
            request.put(
                BASE + "/api/system/settings",
                data={
                    "revision": state["revision"],
                    "changes": {"ai_timeout": 44},
                    "secrets": {},
                },
            ).status
            == 403
        )
        for role in ("orgadmin", "member"):
            other = browser.new_context()
            assert other.request.post(
                BASE + "/api/auth/login",
                headers={"X-Finora-Client": "web"},
                data={"username": role, "password": "Fixture-Only-18084!"},
            ).ok
            assert other.request.get(BASE + "/api/system/settings").status == 403
            tab = other.new_page()
            tab.goto(BASE + "/#system-settings")
            tab.wait_for_timeout(1500)
            assert tab.locator('[data-group="ai"]').count() == 0
            other.close()
    else:
        state = request.get(BASE + "/api/__fixture/state", headers=KEY).json()
        assert (
            state["overrides"]["ai_timeout"] == 43
            and state["overrides"]["ai_cpu_threads"] == 2
        ), state
        expect(page.locator("#system-ai_timeout")).to_have_value("43")
        assert request.post(BASE + "/api/__fixture/consume-worker", headers=KEY).ok
        worker = request.get(BASE + "/api/__fixture/state", headers=KEY).json()
        assert any(
            x["service"] == "worker" and x["revision"] == state["revision"]
            for x in worker["observations"]
        )
        print("PASS controlled process restart durableDB→UI+freshworker revision ack")
    for width in (1440, 390, 320):
        page.set_viewport_size(
            {"width": width, "height": 1000 if width == 1440 else 844}
        )
        for theme in ("light", "dark", "material"):
            page.evaluate('(t)=>localStorage.setItem("finora.appearance",t)', theme)
            page.reload()
            page.locator('[data-group="ai"]').wait_for()
            page.screenshot(path=str(OUT / f"{width}-{theme}.png"), full_page=True)
            print(
                "OVERFLOW",
                width,
                theme,
                page.evaluate(
                    'Array.from(document.querySelectorAll("*")).filter(e=>e.getBoundingClientRect().right>innerWidth+1).map(e=>[e.tagName,e.className,Math.round(e.getBoundingClientRect().right)]).slice(0,15)'
                ),
            )
            assert page.evaluate(
                "document.documentElement.scrollWidth<=window.innerWidth"
            ), (width, theme)
            page.screenshot(path=str(OUT / f"{width}-{theme}.png"), full_page=True)
    (
        OUT
        / (
            "state-after-restart.json"
            if "--after-restart" in sys.argv
            else "state-before-restart.json"
        )
    ).write_text(
        json.dumps(
            request.get(BASE + "/api/__fixture/state", headers=KEY).json(), indent=2
        )
    )
    print(
        "PASS nine viewport/theme screenshots; "
        + (
            "restart persistence verified"
            if "--after-restart" in sys.argv
            else "save/DB/reload, CAS409, secretclear/422/inherit/disable, cancel, orgadmin/member gate and CSRF403 verified"
        )
    )
    browser.close()
