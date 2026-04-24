package com.sailpoint.poc.uiagent.browser;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.BoundingBox;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitUntilState;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Playwright-driven browser with Skyvern-inspired robustness:
 *
 * <ul>
 *   <li>Tag every visible interactable with a stable {@code data-skyvern-id} attribute so the LLM-supplied id maps
 *       deterministically to a Playwright {@link Locator} (not a brittle DOM index).</li>
 *   <li>Click / type / select fall back through several strategies (Playwright → coordinate → JS) like
 *       {@code SkyvernElement.click} / {@code input_sequentially}.</li>
 *   <li>Each action is followed by a "settle" wait (load state + animation pause) before returning so the next
 *       observation sees a stable DOM.</li>
 * </ul>
 */
public final class BrowserSession implements AutoCloseable {

    private static final String ATTR = "data-skyvern-id";

    private static final int DEFAULT_ACTION_TIMEOUT_MS = 8_000;
    private static final int TYPE_CHAR_DELAY_MS = 15;
    private static final int POST_ACTION_SETTLE_MS = 250;

    /**
     * Tags visible interactables with {@code data-skyvern-id} and returns a JSON array describing them. Re-tags from
     * scratch on every call so the IDs match what the LLM will see this turn.
     */
    private static final String SCRAPE_AND_TAG_JS =
            """
            () => {
              const ATTR = 'data-skyvern-id';
              document.querySelectorAll('[' + ATTR + ']').forEach(el => el.removeAttribute(ATTR));
              const sel = [
                'a[href]', 'button', 'input:not([type="hidden"])', 'select', 'textarea',
                '[role="button"]', '[role="link"]', '[role="menuitem"]', '[role="checkbox"]',
                '[role="radio"]', '[role="tab"]', '[role="option"]', '[role="combobox"]',
                '[role="textbox"]', '[contenteditable=""]', '[contenteditable="true"]'
              ].join(',');
              const all = Array.from(document.querySelectorAll(sel));
              function isVisible(el) {
                const r = el.getBoundingClientRect();
                if (r.width < 2 || r.height < 2) return false;
                const st = window.getComputedStyle(el);
                if (st.visibility === 'hidden' || st.display === 'none' || parseFloat(st.opacity) === 0) return false;
                if (el.hasAttribute('disabled') || el.getAttribute('aria-hidden') === 'true') return false;
                const cx = r.left + r.width / 2;
                const cy = r.top + r.height / 2;
                if (cx < 0 || cy < 0 || cx > window.innerWidth || cy > window.innerHeight) return false;
                return true;
              }
              const visible = all.filter(isVisible);
              const out = [];
              visible.forEach((el, idx) => {
                el.setAttribute(ATTR, String(idx));
                const tag = el.tagName.toLowerCase();
                const role = el.getAttribute('role') || '';
                const type = (el.getAttribute('type') || '').toLowerCase();
                const ariaLabel = el.getAttribute('aria-label') || '';
                const placeholder = el.getAttribute('placeholder') || '';
                const name = el.getAttribute('name') || '';
                const href = el.getAttribute('href') || '';
                const value = ('value' in el) ? (el.value || '') : '';
                const text = (el.innerText || el.textContent || '').replace(/\\s+/g, ' ').trim();
                const label = (ariaLabel || text || placeholder || el.getAttribute('title') || name || '').slice(0, 200);
                let optionsArr;
                if (tag === 'select') {
                  optionsArr = Array.from(el.options || []).slice(0, 50).map(o => ({
                    value: o.value, label: (o.label || o.text || '').trim()
                  }));
                }
                out.push({
                  id: idx,
                  tag,
                  role,
                  htmlType: type,
                  name,
                  placeholder,
                  ariaLabel,
                  href: href ? href.slice(0, 200) : '',
                  value: typeof value === 'string' ? value.slice(0, 200) : '',
                  text: label,
                  options: optionsArr
                });
              });
              return JSON.stringify(out);
            }
            """;

    /** Last-resort JS click for elements where Playwright + coordinate clicks both fail. */
    private static final String JS_CLICK =
            """
            (id) => {
              const el = document.querySelector('[data-skyvern-id="' + id + '"]');
              if (!el) return JSON.stringify({ ok: false, err: 'no element for id' });
              try { el.click(); } catch (e) { return JSON.stringify({ ok: false, err: String(e) }); }
              return JSON.stringify({ ok: true });
            }
            """;

    /**
     * Force a value + dispatch input/change events. Used as a final fallback for legacy/uncontrolled inputs when
     * Playwright fill + pressSequentially both fail to land the text.
     */
    private static final String JS_FORCE_VALUE =
            """
            (args) => {
              const el = document.querySelector('[data-skyvern-id="' + args.id + '"]');
              if (!el) return JSON.stringify({ ok: false, err: 'no element' });
              el.focus();
              if ('value' in el) {
                const setter = Object.getOwnPropertyDescriptor(el.__proto__ || {}, 'value');
                if (setter && setter.set) {
                  setter.set.call(el, args.text);
                } else {
                  el.value = args.text;
                }
              } else if (el.isContentEditable) {
                el.textContent = args.text;
              }
              el.dispatchEvent(new Event('input', { bubbles: true }));
              el.dispatchEvent(new Event('change', { bubbles: true }));
              return JSON.stringify({ ok: true });
            }
            """;

    private final Playwright playwright;
    private final Browser browser;
    private final BrowserContext context;
    /**
     * Active page. Mutable because OAuth/SSO/popup flows often push the user onto a freshly opened tab — we follow
     * focus to the newest page so the agent keeps interacting with what the user sees.
     */
    private Page page;

    public BrowserSession(boolean headless, int slowMoMs) {
        this.playwright = Playwright.create();
        BrowserType.LaunchOptions launchOptions = new BrowserType.LaunchOptions().setHeadless(headless);
        if (slowMoMs > 0) {
            launchOptions.setSlowMo(slowMoMs);
        }
        this.browser = playwright.chromium().launch(launchOptions);
        this.context = browser.newContext(new Browser.NewContextOptions().setViewportSize(1280, 800));
        this.page = context.newPage();
        page.setDefaultTimeout(DEFAULT_ACTION_TIMEOUT_MS);

        // Auto-dismiss native dialogs (alert/confirm/prompt). Without this they block the JS execution context until a
        // human responds.
        context.onPage(this::wireUpNewPage);
        wireUpNewPage(page);
    }

    private void wireUpNewPage(Page newPage) {
        try {
            newPage.onDialog(d -> {
                try {
                    d.dismiss();
                } catch (PlaywrightException ignored) {
                }
            });
            // When the site opens a popup (e.g. SSO consent, OAuth), follow the new page so the next observation is
            // taken on what the user is actually looking at.
            newPage.onPopup(this::switchToPage);
        } catch (PlaywrightException ignored) {
        }
        if (newPage != page) {
            switchToPage(newPage);
        }
    }

    private void switchToPage(Page newPage) {
        if (newPage == null || newPage.isClosed()) return;
        try {
            newPage.bringToFront();
            newPage.setDefaultTimeout(DEFAULT_ACTION_TIMEOUT_MS);
            this.page = newPage;
        } catch (PlaywrightException ignored) {
        }
    }

    public Page page() {
        return page;
    }

    public String currentUrl() {
        try {
            return page.url();
        } catch (PlaywrightException e) {
            return "(unknown — " + e.getMessage() + ")";
        }
    }

    public void navigate(String url) {
        try {
            page.navigate(
                    url,
                    new Page.NavigateOptions().setTimeout(90_000).setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
        } catch (PlaywrightException e) {
            System.err.println("navigate(" + url + ") failed: " + e.getMessage());
        }
        settleAfterAction();
    }

    public byte[] viewportScreenshotPng() {
        try {
            return page.screenshot(new Page.ScreenshotOptions().setFullPage(false));
        } catch (PlaywrightException e) {
            sleepQuietly(500);
            try {
                return page.screenshot(new Page.ScreenshotOptions().setFullPage(false));
            } catch (PlaywrightException retry) {
                System.err.println("screenshot failed twice: " + retry.getMessage());
                return new byte[0];
            }
        }
    }

    /**
     * Tags visible interactables with {@value #ATTR} and returns the JSON description. Retries once if the JS
     * evaluation fails because the document was being replaced (very common right after a click that navigates).
     */
    public JSONArray listInteractables() {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                page.waitForLoadState(LoadState.DOMCONTENTLOADED);
            } catch (PlaywrightException ignored) {
            }
            try {
                String json = (String) page.evaluate(SCRAPE_AND_TAG_JS);
                return new JSONArray(json);
            } catch (PlaywrightException e) {
                System.err.println("listInteractables attempt " + (attempt + 1) + " failed: " + e.getMessage());
                sleepQuietly(750);
            }
        }
        return new JSONArray();
    }

    public String formatElementsForPrompt(JSONArray elements) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < elements.length(); i++) {
            JSONObject el = elements.getJSONObject(i);
            sb.append('[').append(el.getInt("id")).append("] ").append(el.optString("tag"));
            String htmlType = el.optString("htmlType");
            if (!htmlType.isEmpty()) sb.append(" type=").append(htmlType);
            String role = el.optString("role");
            if (!role.isEmpty()) sb.append(" role=").append(role);
            String name = el.optString("name");
            if (!name.isEmpty()) sb.append(" name=").append(name);
            String placeholder = el.optString("placeholder");
            if (!placeholder.isEmpty()) sb.append(" placeholder=\"").append(trim(placeholder, 60)).append('"');
            String value = el.optString("value");
            if (!value.isEmpty()) sb.append(" value=\"").append(trim(value, 60)).append('"');
            String text = el.optString("text");
            sb.append(" — ").append(trim(text.replace('\n', ' '), 200));
            if (el.has("options") && !el.isNull("options")) {
                JSONArray opts = el.optJSONArray("options");
                if (opts != null && !opts.isEmpty()) {
                    sb.append(" options=[");
                    for (int j = 0; j < Math.min(opts.length(), 10); j++) {
                        JSONObject o = opts.getJSONObject(j);
                        if (j > 0) sb.append(", ");
                        sb.append('"').append(trim(o.optString("label"), 30)).append('"');
                    }
                    if (opts.length() > 10) sb.append(", …");
                    sb.append(']');
                }
            }
            sb.append('\n');
        }
        if (sb.isEmpty()) {
            sb.append("(no indexed interactables found — page may still be loading or use a non-standard UI)\n");
        }
        return sb.toString();
    }

    // -----------------------------------------------------------------
    // Actions — each returns a JSON status the agent loop can log/feed back
    // -----------------------------------------------------------------

    public JSONObject clickByElementId(int elementId) {
        safeguardBeforeAction();
        Locator locator = locatorFor(elementId);
        if (locator == null) return fail("no element with id=" + elementId);
        try {
            scrollIntoView(locator);
            locator.click(new Locator.ClickOptions().setTimeout(DEFAULT_ACTION_TIMEOUT_MS));
            settleAfterAction();
            return ok("playwright_click");
        } catch (RuntimeException primary) {
            // Fallback 1: bounding-box coordinate click.
            try {
                BoundingBox bb = locator.boundingBox();
                if (bb != null) {
                    page.mouse().click(bb.x + bb.width / 2.0, bb.y + bb.height / 2.0);
                    settleAfterAction();
                    return ok("coordinate_click").put("primary_error", primary.getMessage());
                }
            } catch (RuntimeException ignored) {
            }
            // Fallback 2: JS click.
            try {
                String json = (String) page.evaluate(JS_CLICK, elementId);
                JSONObject res = new JSONObject(json);
                if (res.optBoolean("ok", false)) {
                    settleAfterAction();
                    return ok("js_click").put("primary_error", primary.getMessage());
                }
                return fail("all click strategies failed: " + primary.getMessage() + "; js: " + res.optString("err"));
            } catch (RuntimeException jsEx) {
                return fail("all click strategies failed: " + primary.getMessage() + "; js: " + jsEx.getMessage());
            }
        }
    }

    public JSONObject typeByElementId(int elementId, String text) {
        if (text == null) text = "";
        safeguardBeforeAction();
        Locator locator = locatorFor(elementId);
        if (locator == null) return fail("no element with id=" + elementId);
        try {
            scrollIntoView(locator);

            // Idempotency: skip if already correct.
            String currentValue = readInputValue(locator);
            if (currentValue != null && currentValue.equals(text)) {
                return ok("already_correct").put("value", currentValue);
            }

            // Strategy 1: focus + clear + fill + verify.
            try {
                locator.click(new Locator.ClickOptions().setTimeout(DEFAULT_ACTION_TIMEOUT_MS));
            } catch (RuntimeException ignored) {
            }
            try {
                locator.fill("", new Locator.FillOptions().setTimeout(DEFAULT_ACTION_TIMEOUT_MS));
            } catch (RuntimeException ignored) {
            }
            try {
                locator.fill(text, new Locator.FillOptions().setTimeout(DEFAULT_ACTION_TIMEOUT_MS));
                if (verifyTyped(locator, text)) {
                    settleAfterAction();
                    return ok("fill");
                }
            } catch (RuntimeException ignored) {
            }

            // Strategy 2: per-character pressSequentially with delay.
            try {
                locator.pressSequentially(
                        text,
                        new Locator.PressSequentiallyOptions()
                                .setDelay(TYPE_CHAR_DELAY_MS)
                                .setTimeout(DEFAULT_ACTION_TIMEOUT_MS));
                if (verifyTyped(locator, text)) {
                    settleAfterAction();
                    return ok("press_sequentially");
                }
            } catch (RuntimeException ignored) {
            }

            // Strategy 3: keyboard.type while focused.
            try {
                locator.focus();
                page.keyboard().type(text, new com.microsoft.playwright.Keyboard.TypeOptions().setDelay(TYPE_CHAR_DELAY_MS));
                if (verifyTyped(locator, text)) {
                    settleAfterAction();
                    return ok("keyboard_type");
                }
            } catch (RuntimeException ignored) {
            }

            // Strategy 4: JS native value setter + dispatch events (React-friendly).
            Map<String, Object> args = new HashMap<>();
            args.put("id", elementId);
            args.put("text", text);
            String json = (String) page.evaluate(JS_FORCE_VALUE, args);
            JSONObject res = new JSONObject(json);
            if (res.optBoolean("ok", false) && verifyTyped(locator, text)) {
                settleAfterAction();
                return ok("js_force_value");
            }

            String observed = readInputValue(locator);
            return fail("all type strategies failed; expected=\"" + trim(text, 80) + "\" observed=\""
                    + trim(observed == null ? "" : observed, 80) + "\"");
        } catch (RuntimeException ex) {
            return fail("type failed: " + ex.getMessage());
        }
    }

    public JSONObject selectOptionByElementId(int elementId, String value, String label) {
        safeguardBeforeAction();
        Locator locator = locatorFor(elementId);
        if (locator == null) return fail("no element with id=" + elementId);
        try {
            scrollIntoView(locator);
            String tag = (String) locator.evaluate("el => el.tagName && el.tagName.toLowerCase()");
            if ("select".equals(tag)) {
                com.microsoft.playwright.options.SelectOption opt = new com.microsoft.playwright.options.SelectOption();
                if (value != null && !value.isBlank()) opt.setValue(value);
                else if (label != null && !label.isBlank()) opt.setLabel(label);
                else return fail("select_option requires value or label");
                locator.selectOption(opt);
                settleAfterAction();
                return ok("native_select").put("value", value).put("label", label);
            }
            // Custom dropdown: open then pick by text.
            locator.click(new Locator.ClickOptions().setTimeout(DEFAULT_ACTION_TIMEOUT_MS));
            page.waitForTimeout(200);
            String wanted = label != null && !label.isBlank() ? label : value;
            if (wanted == null || wanted.isBlank()) return fail("select_option on custom dropdown requires label or value");
            Locator option = page.locator(
                    "[role='option']:has-text(\"" + escapeQuotes(wanted) + "\"), "
                            + "li:has-text(\"" + escapeQuotes(wanted) + "\")");
            if (option.count() > 0) {
                option.first().click(new Locator.ClickOptions().setTimeout(DEFAULT_ACTION_TIMEOUT_MS));
                settleAfterAction();
                return ok("custom_dropdown_click").put("matched", wanted);
            }
            // Keyboard fallback.
            page.keyboard().press("ArrowDown");
            page.keyboard().press("Enter");
            settleAfterAction();
            return ok("keyboard_select").put("matched_attempt", wanted);
        } catch (RuntimeException ex) {
            return fail("select_option failed: " + ex.getMessage());
        }
    }

    public JSONObject keypress(String key) {
        if (key == null || key.isBlank()) return fail("keypress requires key");
        safeguardBeforeAction();
        try {
            page.keyboard().press(key);
            settleAfterAction();
            return ok("keypress").put("key", key);
        } catch (RuntimeException ex) {
            return fail("keypress failed: " + ex.getMessage());
        }
    }

    public JSONObject scroll(String direction, int amount) {
        int delta = amount > 0 ? amount : 600;
        int dy = switch (direction == null ? "" : direction.toLowerCase()) {
            case "up" -> -delta;
            case "down" -> delta;
            default -> delta;
        };
        safeguardBeforeAction();
        try {
            page.mouse().wheel(0, dy);
            settleAfterAction();
            return ok("scroll").put("direction", direction).put("dy", dy);
        } catch (RuntimeException ex) {
            return fail("scroll failed: " + ex.getMessage());
        }
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    private Locator locatorFor(int elementId) {
        try {
            Locator locator = page.locator("[" + ATTR + "='" + elementId + "']");
            return locator.count() > 0 ? locator.first() : null;
        } catch (PlaywrightException firstAttempt) {
            try {
                page.waitForLoadState(LoadState.DOMCONTENTLOADED, new Page.WaitForLoadStateOptions().setTimeout(5_000));
            } catch (PlaywrightException ignored) {
            }
            try {
                Locator locator = page.locator("[" + ATTR + "='" + elementId + "']");
                return locator.count() > 0 ? locator.first() : null;
            } catch (PlaywrightException secondAttempt) {
                System.err.println("locatorFor(" + elementId + ") failed twice: " + secondAttempt.getMessage());
                return null;
            }
        }
    }

    private void scrollIntoView(Locator locator) {
        try {
            locator.scrollIntoViewIfNeeded(new Locator.ScrollIntoViewIfNeededOptions().setTimeout(2_000));
        } catch (RuntimeException ignored) {
        }
    }

    private void safeguardBeforeAction() {
        try {
            page.waitForLoadState(LoadState.DOMCONTENTLOADED, new Page.WaitForLoadStateOptions().setTimeout(3_000));
        } catch (PlaywrightException ignored) {
        }
    }

    private String readInputValue(Locator locator) {
        try {
            String tag = (String) locator.evaluate("el => el.tagName && el.tagName.toLowerCase()");
            if ("input".equals(tag) || "textarea".equals(tag) || "select".equals(tag)) {
                return locator.inputValue();
            }
            return (String) locator.evaluate("el => el.isContentEditable ? el.textContent : el.innerText");
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private boolean verifyTyped(Locator locator, String expected) {
        String actual = readInputValue(locator);
        if (actual == null) return false;
        return actual.equals(expected) || actual.contains(expected);
    }

    private void settleAfterAction() {
        try {
            page.waitForLoadState(LoadState.DOMCONTENTLOADED, new Page.WaitForLoadStateOptions().setTimeout(3_000));
        } catch (RuntimeException ignored) {
        }
        try {
            page.waitForLoadState(LoadState.LOAD, new Page.WaitForLoadStateOptions().setTimeout(3_000));
        } catch (RuntimeException ignored) {
        }
        sleepQuietly(POST_ACTION_SETTLE_MS);
        try {
            page.waitForLoadState(LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions().setTimeout(1_500));
        } catch (RuntimeException ignored) {
        }
    }

    private static void sleepQuietly(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static JSONObject ok(String strategy) {
        return new JSONObject().put("ok", true).put("strategy", strategy);
    }

    private static JSONObject fail(String message) {
        return new JSONObject().put("ok", false).put("err", message);
    }

    private static String trim(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static String escapeQuotes(String s) {
        return s.replace("\"", "\\\"");
    }

    @SuppressWarnings("unused")
    public ElementHandle querySelector(String selector) {
        return page.querySelector(selector);
    }

    @Override
    public void close() {
        if (context != null) {
            try { context.close(); } catch (RuntimeException ignored) {}
        }
        if (browser != null) {
            try { browser.close(); } catch (RuntimeException ignored) {}
        }
        if (playwright != null) {
            try { playwright.close(); } catch (RuntimeException ignored) {}
        }
    }
}
