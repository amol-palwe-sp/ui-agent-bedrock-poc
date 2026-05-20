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
import com.microsoft.playwright.options.ScreenshotType;
import com.microsoft.playwright.options.WaitUntilState;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Playwright-driven browser with Skyvern-inspired robustness:
 *
 * <ul>
 *   <li>Tag every visible interactable (including same-origin iframe children) with a stable
 *       {@code data-skyvern-id} so the LLM-supplied id maps deterministically to a Playwright
 *       {@link Locator}.</li>
 *   <li>Pre-action guards check {@code disabled} / {@code aria-disabled} / {@code readonly} /
 *       {@code aria-readonly} dynamically before every click or type, providing clear failure
 *       feedback to the model instead of a cryptic Playwright timeout.</li>
 *   <li>Click falls back through Playwright → blocking-element detection → coordinate →
 *       JS, mirroring Skyvern's {@code chain_click} strategy.</li>
 *   <li>Type falls back through fill → pressSequentially → keyboard.type → JS native-setter
 *       (React-friendly).</li>
 *   <li>HOVER, CHECK, RELOAD_PAGE action methods added for production-grade coverage.</li>
 *   <li>{@code settleAfterAction()} waits for load-state, then waits for DOM stability via a
 *       MutationObserver (300 ms silence) and checks that loading spinners / skeletons have
 *       disappeared before returning.</li>
 *   <li>Configurable per-action timeouts (click, type, navigate) passed from PocConfig.</li>
 * </ul>
 */
public final class BrowserSession implements AutoCloseable {

    private static final String ATTR = "data-skyvern-id";

    private static final int TYPE_CHAR_DELAY_MS        = 15;
    private static final int POST_ACTION_SETTLE_MS     = 200;
    // JS_WAIT_DOM_STABLE has its own internal check loop; this constant documents the intent.
    @SuppressWarnings("unused")
    private static final int DOM_STABLE_WAIT_MS        = 4_000;

    // Per-action timeouts — overridden via constructor from PocConfig.
    private final int clickTimeoutMs;
    private final int typeTimeoutMs;
    private final int navigateTimeoutMs;
    private final int interActionDelayMs;

    // -------------------------------------------------------------------------
    // JavaScript constants
    // -------------------------------------------------------------------------

    /**
     * Tags visible interactables in the main frame AND same-origin iframes, returning a flat JSON
     * array. Cross-origin iframes are silently skipped.
     */
    private static final String SCRAPE_AND_TAG_JS =
            """
            () => {
              const ATTR = 'data-skyvern-id';
              let globalIdx = 0;
              const out = [];

              function isVisible(el) {
                const r = el.getBoundingClientRect();
                if (r.width < 2 || r.height < 2) return false;
                const st = window.getComputedStyle(el);
                if (st.visibility === 'hidden' || st.display === 'none'
                    || parseFloat(st.opacity) === 0) return false;
                if (el.hasAttribute('disabled')
                    || el.getAttribute('aria-hidden') === 'true') return false;
                const cx = r.left + r.width / 2;
                const cy = r.top + r.height / 2;
                if (cx < 0 || cy < 0 || cx > window.innerWidth
                    || cy > window.innerHeight) return false;
                return true;
              }

              function scrapeDoc(doc, frameLabel) {
                // clear old tags in this document
                doc.querySelectorAll('[' + ATTR + ']')
                   .forEach(el => el.removeAttribute(ATTR));

                const sel = [
                  'a[href]', 'button', 'input:not([type="hidden"])',
                  'select', 'textarea',
                  '[role="button"]', '[role="link"]', '[role="menuitem"]',
                  '[role="checkbox"]', '[role="radio"]', '[role="tab"]',
                  '[role="option"]', '[role="combobox"]', '[role="textbox"]',
                  '[contenteditable=""]', '[contenteditable="true"]'
                ].join(',');

                const visible = Array.from(doc.querySelectorAll(sel)).filter(isVisible);
                visible.forEach(el => {
                  const idx = globalIdx++;
                  el.setAttribute(ATTR, String(idx));
                  const tag  = el.tagName.toLowerCase();
                  const role = el.getAttribute('role') || '';
                  const type = (el.getAttribute('type') || '').toLowerCase();
                  const ariaLabel   = el.getAttribute('aria-label') || '';
                  const placeholder = el.getAttribute('placeholder') || '';
                  const name        = el.getAttribute('name') || '';
                  const href        = el.getAttribute('href') || '';
                  const value       = ('value' in el) ? (el.value || '') : '';
                  const text        = (el.innerText || el.textContent || '')
                                       .replace(/\\s+/g, ' ').trim();
                  const label       = (ariaLabel || text || placeholder
                                      || el.getAttribute('title') || name || '')
                                       .slice(0, 200);
                  let optionsArr;
                  if (tag === 'select') {
                    optionsArr = Array.from(el.options || []).slice(0, 50)
                      .map(o => ({ value: o.value,
                                   label: (o.label || o.text || '').trim() }));
                  }
                  out.push({
                    id: idx, tag, role, htmlType: type, name, placeholder,
                    ariaLabel, frame: frameLabel,
                    href:  href  ? href.slice(0, 200)  : '',
                    value: typeof value === 'string' ? value.slice(0, 200) : '',
                    text:  label,
                    options: optionsArr
                  });
                });
              }

              // Main frame
              scrapeDoc(document, 'main');

              // Same-origin iframes
              document.querySelectorAll('iframe').forEach((iframe, fi) => {
                try {
                  const doc = iframe.contentDocument;
                  if (doc && doc.body) scrapeDoc(doc, 'iframe-' + fi);
                } catch (e) { /* cross-origin — skip */ }
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
              try { el.click(); } catch (e) {
                return JSON.stringify({ ok: false, err: String(e) });
              }
              return JSON.stringify({ ok: true });
            }
            """;

    /**
     * Force a value + dispatch input/change events. Used as the final fallback for
     * legacy/uncontrolled inputs (e.g. React-controlled) when all Playwright strategies fail.
     */
    private static final String JS_FORCE_VALUE =
            """
            (args) => {
              const el = document.querySelector('[data-skyvern-id="' + args.id + '"]');
              if (!el) return JSON.stringify({ ok: false, err: 'no element' });
              el.focus();
              if ('value' in el) {
                const setter = Object.getOwnPropertyDescriptor(
                    el.__proto__ || {}, 'value');
                if (setter && setter.set) {
                  setter.set.call(el, args.text);
                } else {
                  el.value = args.text;
                }
              } else if (el.isContentEditable) {
                el.textContent = args.text;
              }
              el.dispatchEvent(new Event('input',  { bubbles: true }));
              el.dispatchEvent(new Event('change', { bubbles: true }));
              return JSON.stringify({ ok: true });
            }
            """;

    /**
     * Reads element state dynamically (after potential prior-step mutations).
     * Returns: { ok, disabled, readonly }
     */
    private static final String JS_ELEMENT_STATE =
            """
            (id) => {
              const el = document.querySelector('[data-skyvern-id="' + id + '"]');
              if (!el) return JSON.stringify({ ok: false });
              const s = window.getComputedStyle(el);
              const disabled =
                el.disabled === true
                || el.getAttribute('aria-disabled') === 'true'
                || s.pointerEvents === 'none'
                || s.cursor === 'not-allowed';
              const readonly =
                el.readOnly === true
                || el.getAttribute('aria-readonly') === 'true';
              return JSON.stringify({ ok: true, disabled, readonly });
            }
            """;

    /**
     * Uses elementFromPoint to find what element is actually at the center of the target.
     * Returns: { ok, blocker_id } where blocker_id is null if the target itself is on top.
     */
    private static final String JS_BLOCKER_AT_POINT =
            """
            (id) => {
              const el = document.querySelector('[data-skyvern-id="' + id + '"]');
              if (!el) return JSON.stringify({ ok: false });
              const r  = el.getBoundingClientRect();
              const cx = r.left + r.width  / 2;
              const cy = r.top  + r.height / 2;
              const top = document.elementFromPoint(cx, cy);
              if (!top) return JSON.stringify({ ok: true, blocker_id: null });
              // Walk up to find a data-skyvern-id on or near the topmost element
              let cur = top;
              for (let i = 0; i < 5 && cur; i++) {
                const bid = cur.getAttribute('data-skyvern-id');
                if (bid !== null && bid !== String(id)) {
                  return JSON.stringify({ ok: true, blocker_id: bid,
                                         blocker_tag: cur.tagName.toLowerCase() });
                }
                if (cur === el) break;
                cur = cur.parentElement;
              }
              return JSON.stringify({ ok: true, blocker_id: null });
            }
            """;

    /**
     * Waits up to the caller's timeout for:
     * 1. Common loading indicators to disappear (spinners, skeletons, aria-busy).
     * 2. DOM mutations to settle for 300 ms (MutationObserver silence threshold).
     * Must be called with page.evaluate(...) where the page supports Promises.
     */
    private static final String JS_WAIT_DOM_STABLE =
            """
            () => new Promise(resolve => {
              const SPINNER_SELECTORS = [
                '[aria-busy="true"]',
                '[role="progressbar"]',
                '[role="status"][aria-busy="true"]',
                '[class*="spinner"]',
                '[class*="loading"]',
                '[class*="loader"]',
                '[class*="skeleton"]',
                '[class*="shimmer"]',
                '.loading-overlay',
                '.page-loading'
              ];
              const STABLE_MS  = 300;   // silence window needed
              const MAX_WAIT_MS = 2000; // absolute ceiling — always resolve within this
              const startedAt  = Date.now();
              let lastChange   = Date.now();
              const obs = new MutationObserver(muts => {
                const sig = muts.some(m =>
                  m.type === 'childList' ||
                  (m.type === 'attributes' && (() => {
                    const r = m.target.getBoundingClientRect &&
                              m.target.getBoundingClientRect();
                    return r && r.width > 0 && r.height > 0;
                  })())
                );
                if (sig) lastChange = Date.now();
              });
              obs.observe(document.body, {
                childList: true, subtree: true, attributes: true
              });
              const hasSpinner = () => SPINNER_SELECTORS.some(s => {
                try {
                  const el = document.querySelector(s);
                  if (!el) return false;
                  const r = el.getBoundingClientRect();
                  const st = window.getComputedStyle(el);
                  return r.width > 0 && r.height > 0
                    && st.display !== 'none'
                    && st.visibility !== 'hidden';
                } catch (e) { return false; }
              });
              const done = () => { obs.disconnect(); resolve(true); };
              const check = () => {
                if (Date.now() - startedAt >= MAX_WAIT_MS) { done(); return; }
                if (!hasSpinner() && Date.now() - lastChange >= STABLE_MS) {
                  done();
                } else {
                  setTimeout(check, 100);
                }
              };
              setTimeout(check, 50);
            })
            """;

    // -------------------------------------------------------------------------
    // Instance state
    // -------------------------------------------------------------------------

    private final Playwright playwright;
    private final Browser browser;
    private final BrowserContext context;

    /**
     * Active page. Mutable: OAuth/SSO/popup flows open fresh tabs; we follow focus so the agent
     * always interacts with what the user sees.
     */
    private Page page;

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    public BrowserSession(
            boolean headless,
            int slowMoMs,
            int viewportWidth,
            int viewportHeight,
            boolean startMaximized,
            int fullscreenViewportWidth,
            int fullscreenViewportHeight,
            int clickTimeoutMs,
            int typeTimeoutMs,
            int navigateTimeoutMs,
            int interActionDelayMs) {

        this.clickTimeoutMs      = clickTimeoutMs      > 0 ? clickTimeoutMs      : 8_000;
        this.typeTimeoutMs       = typeTimeoutMs       > 0 ? typeTimeoutMs       : 5_000;
        this.navigateTimeoutMs   = navigateTimeoutMs   > 0 ? navigateTimeoutMs   : 90_000;
        this.interActionDelayMs  = interActionDelayMs;

        this.playwright = Playwright.create();
        BrowserType.LaunchOptions launchOpts = new BrowserType.LaunchOptions().setHeadless(headless);
        if (slowMoMs > 0) launchOpts.setSlowMo(slowMoMs);
        if (startMaximized && !headless) launchOpts.setArgs(List.of("--start-maximized"));

        this.browser = playwright.chromium().launch(launchOpts);

        Browser.NewContextOptions ctxOpts = new Browser.NewContextOptions();
        if (startMaximized && !headless) {
            ctxOpts.setViewportSize(null);                              // window-driven viewport
        } else if (startMaximized) {
            ctxOpts.setViewportSize(fullscreenViewportWidth, fullscreenViewportHeight);
        } else {
            ctxOpts.setViewportSize(viewportWidth, viewportHeight);
        }
        this.context = browser.newContext(ctxOpts);
        this.page = context.newPage();
        this.page.setDefaultTimeout(this.clickTimeoutMs);

        // NOTE: We intentionally do NOT use context.onPage here.
        // context.onPage fires for every background/utility page the browser opens (OAuth
        // helpers, credential brokers, 1×1 redirect pages, etc.). Auto-switching to those
        // kills our active page reference when they close. We use page.onPopup instead,
        // which only fires for real user-visible popup windows opened from the active page.
        wireUpNewPage(page);
    }

    /**
     * Wires up dialog auto-dismiss, popup following, and close-recovery on the given page.
     * Called recursively so that dialog dismissal and popup tracking work in popup windows too.
     */
    private void wireUpNewPage(Page newPage) {
        try {
            // Auto-dismiss native dialogs (alert/confirm/prompt) — they block JS otherwise.
            newPage.onDialog(d -> { try { d.dismiss(); } catch (PlaywrightException ignored) {} });

            // Follow real popup windows (not background pages).
            newPage.onPopup(popup -> {
                wireUpNewPage(popup);   // wire dialog + popup handlers recursively
                switchToPage(popup);
            });

            // Recovery: when this page closes, fall back to the most recent surviving page.
            newPage.onClose(closedPage -> {
                if (closedPage == this.page) {
                    try {
                        List<Page> alive = context.pages();
                        // Prefer the most recently opened surviving page.
                        for (int i = alive.size() - 1; i >= 0; i--) {
                            Page candidate = alive.get(i);
                            if (!candidate.isClosed()) {
                                System.out.println("[BrowserSession] Active page closed;"
                                        + " recovering to: " + safeUrl(candidate));
                                this.page = candidate;
                                try { candidate.bringToFront(); } catch (PlaywrightException ignored) {}
                                break;
                            }
                        }
                    } catch (PlaywrightException ignored) {}
                }
            });
        } catch (PlaywrightException ignored) {}
    }

    private void switchToPage(Page newPage) {
        if (newPage == null || newPage.isClosed()) return;
        try {
            newPage.bringToFront();
            newPage.setDefaultTimeout(clickTimeoutMs);
            this.page = newPage;
        } catch (PlaywrightException ignored) {}
    }

    private String safeUrl(Page p) {
        try { return p.url(); } catch (PlaywrightException ignored) { return "(unknown)"; }
    }

    // -------------------------------------------------------------------------
    // Navigation + screenshots
    // -------------------------------------------------------------------------

    public Page page() { return page; }

    public String currentUrl() {
        try { return page.url(); }
        catch (PlaywrightException e) { return "(unknown — " + e.getMessage() + ")"; }
    }

    public void navigate(String url) {
        try {
            page.navigate(url, new Page.NavigateOptions()
                    .setTimeout(navigateTimeoutMs)
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
        } catch (PlaywrightException e) {
            System.err.println("navigate(" + url + ") failed: " + e.getMessage());
        }
        settleAfterAction();
    }

    public JSONObject reloadPage() {
        try {
            page.reload(new Page.ReloadOptions()
                    .setTimeout(navigateTimeoutMs)
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            settleAfterAction();
            return ok("reload");
        } catch (PlaywrightException e) {
            System.err.println("reload() failed: " + e.getMessage());
            return fail("reload failed: " + firstLine(e.getMessage()));
        }
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
     * Viewport screenshot as JPEG at the given quality (0–100). Typically 3–6× smaller than PNG,
     * reducing the base64 payload sent to Bedrock on every vision call.
     */
    public byte[] viewportScreenshotJpeg(int quality) {
        Page.ScreenshotOptions opts = new Page.ScreenshotOptions()
                .setFullPage(false)
                .setType(ScreenshotType.JPEG)
                .setQuality(Math.max(0, Math.min(100, quality)));
        try {
            return page.screenshot(opts);
        } catch (PlaywrightException e) {
            sleepQuietly(500);
            try {
                return page.screenshot(opts);
            } catch (PlaywrightException retry) {
                System.err.println("screenshot (jpeg) failed twice: " + retry.getMessage());
                return new byte[0];
            }
        }
    }

    // -------------------------------------------------------------------------
    // DOM scraping
    // -------------------------------------------------------------------------

    /**
     * Tags visible interactables (main frame + same-origin iframes) and returns JSON descriptions.
     * Retries once on context-destroyed errors (common right after a navigation).
     */
    public JSONArray listInteractables() {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                page.waitForLoadState(LoadState.DOMCONTENTLOADED,
                        new Page.WaitForLoadStateOptions().setTimeout(2_000));
            } catch (PlaywrightException ignored) {}
            try {
                String json = (String) page.evaluate(SCRAPE_AND_TAG_JS);
                return new JSONArray(json);
            } catch (PlaywrightException e) {
                // Normal after a navigation; the retry below handles it.
                System.out.println("  [INFO] listInteractables retry (attempt " + (attempt + 1)
                        + "): " + firstLine(e.getMessage()));
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
            String frame = el.optString("frame");
            if (!frame.isEmpty() && !frame.equals("main")) sb.append(" frame=").append(frame);
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
            sb.append("(no indexed interactables found — page may still be loading"
                    + " or use a non-standard UI)\n");
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Actions
    // -------------------------------------------------------------------------

    public JSONObject clickByElementId(int elementId) {
        applyInterActionDelay();
        safeguardBeforeAction();
        Locator locator = locatorFor(elementId);
        if (locator == null) return fail("no element with id=" + elementId);

        // Pre-action guard: skip immediately if element is disabled.
        JSONObject state = elementState(elementId);
        if (state.optBoolean("disabled", false)) {
            return fail("element id=" + elementId + " is disabled — cannot click");
        }

        try {
            scrollIntoView(locator);
            locator.click(new Locator.ClickOptions().setTimeout(clickTimeoutMs));
            settleAfterAction();
            return ok("playwright_click");
        } catch (RuntimeException primary) {

            // Fallback 1: check for a blocking element and click it first.
            try {
                JSONObject blocker = blockerAtPoint(elementId);
                if (blocker.optBoolean("ok", false) && !blocker.isNull("blocker_id")) {
                    String blockerId = blocker.optString("blocker_id");
                    String blockerTag = blocker.optString("blocker_tag", "?");
                    System.out.println("  [Click] blocker detected: id=" + blockerId
                            + " tag=" + blockerTag + " — clicking blocker first");
                    Locator blockerLocator = page.locator("[" + ATTR + "='" + blockerId + "']");
                    if (blockerLocator.count() > 0) {
                        blockerLocator.first().click(new Locator.ClickOptions()
                                .setTimeout(clickTimeoutMs));
                        sleepQuietly(300);
                        // Retry original after blocker dismissed.
                        locator.click(new Locator.ClickOptions().setTimeout(clickTimeoutMs));
                        settleAfterAction();
                        return ok("blocker_then_click")
                                .put("blocker_tag", blockerTag)
                                .put("primary_error", firstLine(primary.getMessage()));
                    }
                }
            } catch (RuntimeException ignored) {}

            // Fallback 2: bounding-box coordinate click.
            try {
                BoundingBox bb = locator.boundingBox();
                if (bb != null) {
                    page.mouse().click(bb.x + bb.width / 2.0, bb.y + bb.height / 2.0);
                    settleAfterAction();
                    return ok("coordinate_click")
                            .put("primary_error", firstLine(primary.getMessage()));
                }
            } catch (RuntimeException ignored) {}

            // Fallback 3: JS click.
            try {
                String json = (String) page.evaluate(JS_CLICK, elementId);
                JSONObject res = new JSONObject(json);
                if (res.optBoolean("ok", false)) {
                    settleAfterAction();
                    return ok("js_click")
                            .put("primary_error", firstLine(primary.getMessage()));
                }
                return fail("all click strategies failed: " + firstLine(primary.getMessage())
                        + "; js: " + res.optString("err"));
            } catch (RuntimeException jsEx) {
                return fail("all click strategies failed: " + firstLine(primary.getMessage())
                        + "; js: " + firstLine(jsEx.getMessage()));
            }
        }
    }

    public JSONObject typeByElementId(int elementId, String text) {
        if (text == null) text = "";
        applyInterActionDelay();
        safeguardBeforeAction();
        Locator locator = locatorFor(elementId);
        if (locator == null) return fail("no element with id=" + elementId);

        // Pre-action guard: disabled or readonly check.
        JSONObject state = elementState(elementId);
        if (state.optBoolean("disabled", false)) {
            return fail("element id=" + elementId + " is disabled — cannot type");
        }
        if (state.optBoolean("readonly", false)) {
            return fail("element id=" + elementId + " is readonly — cannot type");
        }

        try {
            scrollIntoView(locator);

            // Idempotency: skip if the field already has the desired value.
            String currentValue = readInputValue(locator);
            if (currentValue != null && currentValue.equals(text)) {
                return ok("already_correct").put("value", currentValue);
            }

            // Strategy 1: focus + clear + fill + verify.
            try { locator.click(new Locator.ClickOptions().setTimeout(typeTimeoutMs)); }
            catch (RuntimeException ignored) {}
            try { locator.fill("", new Locator.FillOptions().setTimeout(typeTimeoutMs)); }
            catch (RuntimeException ignored) {}
            try {
                locator.fill(text, new Locator.FillOptions().setTimeout(typeTimeoutMs));
                if (verifyTyped(locator, text)) {
                    settleAfterAction();
                    return ok("fill");
                }
            } catch (RuntimeException ignored) {}

            // Strategy 2: per-character pressSequentially with delay.
            try {
                locator.pressSequentially(text, new Locator.PressSequentiallyOptions()
                        .setDelay(TYPE_CHAR_DELAY_MS)
                        .setTimeout(typeTimeoutMs));
                if (verifyTyped(locator, text)) {
                    settleAfterAction();
                    return ok("press_sequentially");
                }
            } catch (RuntimeException ignored) {}

            // Strategy 3: keyboard.type while focused.
            try {
                locator.focus();
                page.keyboard().type(text, new com.microsoft.playwright.Keyboard.TypeOptions()
                        .setDelay(TYPE_CHAR_DELAY_MS));
                if (verifyTyped(locator, text)) {
                    settleAfterAction();
                    return ok("keyboard_type");
                }
            } catch (RuntimeException ignored) {}

            // Strategy 4: JS native value setter + React input/change events.
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
            return fail("all type strategies failed; expected=\"" + trim(text, 80)
                    + "\" observed=\"" + trim(observed == null ? "" : observed, 80) + "\"");
        } catch (RuntimeException ex) {
            return fail("type failed: " + ex.getMessage());
        }
    }

    public JSONObject hoverByElementId(int elementId) {
        applyInterActionDelay();
        safeguardBeforeAction();
        Locator locator = locatorFor(elementId);
        if (locator == null) return fail("no element with id=" + elementId);
        try {
            scrollIntoView(locator);
            locator.hover(new Locator.HoverOptions().setTimeout(clickTimeoutMs));
            sleepQuietly(300); // let reveal animation settle before the agent observes
            return ok("hover");
        } catch (RuntimeException ex) {
            return fail("hover failed: " + ex.getMessage());
        }
    }

    public JSONObject checkboxByElementId(int elementId, boolean targetChecked) {
        applyInterActionDelay();
        safeguardBeforeAction();
        Locator locator = locatorFor(elementId);
        if (locator == null) return fail("no element with id=" + elementId);

        JSONObject state = elementState(elementId);
        if (state.optBoolean("disabled", false)) {
            return fail("element id=" + elementId + " is disabled — cannot toggle checkbox");
        }
        try {
            scrollIntoView(locator);
            boolean current = locator.isChecked();
            if (current == targetChecked) {
                return ok("already_correct").put("checked", current);
            }
            if (targetChecked) {
                locator.check(new Locator.CheckOptions().setTimeout(clickTimeoutMs));
            } else {
                locator.uncheck(new Locator.UncheckOptions().setTimeout(clickTimeoutMs));
            }
            settleAfterAction();
            return ok("checkbox_" + (targetChecked ? "checked" : "unchecked"))
                    .put("checked", targetChecked);
        } catch (RuntimeException ex) {
            return fail("checkbox failed: " + ex.getMessage());
        }
    }

    public JSONObject selectOptionByElementId(int elementId, String value, String label) {
        applyInterActionDelay();
        safeguardBeforeAction();
        Locator locator = locatorFor(elementId);
        if (locator == null) return fail("no element with id=" + elementId);
        try {
            scrollIntoView(locator);
            String tag = (String) locator.evaluate("el => el.tagName && el.tagName.toLowerCase()");
            if ("select".equals(tag)) {
                com.microsoft.playwright.options.SelectOption opt =
                        new com.microsoft.playwright.options.SelectOption();
                if (value != null && !value.isBlank()) opt.setValue(value);
                else if (label != null && !label.isBlank()) opt.setLabel(label);
                else return fail("select_option requires value or label");
                locator.selectOption(opt);
                settleAfterAction();
                return ok("native_select").put("value", value).put("label", label);
            }
            // Custom dropdown: open then pick by text.
            locator.click(new Locator.ClickOptions().setTimeout(clickTimeoutMs));
            page.waitForTimeout(200);
            String wanted = label != null && !label.isBlank() ? label : value;
            if (wanted == null || wanted.isBlank()) {
                return fail("select_option on custom dropdown requires label or value");
            }
            Locator option = page.locator(
                    "[role='option']:has-text(\"" + escapeQuotes(wanted) + "\"), "
                    + "li:has-text(\"" + escapeQuotes(wanted) + "\")");
            if (option.count() > 0) {
                option.first().click(new Locator.ClickOptions().setTimeout(clickTimeoutMs));
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
        applyInterActionDelay();
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
        applyInterActionDelay();
        safeguardBeforeAction();
        try {
            page.mouse().wheel(0, dy);
            settleAfterAction();
            return ok("scroll").put("direction", direction).put("dy", dy);
        } catch (RuntimeException ex) {
            return fail("scroll failed: " + ex.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Locator locatorFor(int elementId) {
        try {
            Locator locator = page.locator("[" + ATTR + "='" + elementId + "']");
            return locator.count() > 0 ? locator.first() : null;
        } catch (PlaywrightException firstAttempt) {
            try {
                page.waitForLoadState(LoadState.DOMCONTENTLOADED,
                        new Page.WaitForLoadStateOptions().setTimeout(5_000));
            } catch (PlaywrightException ignored) {}
            try {
                Locator locator = page.locator("[" + ATTR + "='" + elementId + "']");
                return locator.count() > 0 ? locator.first() : null;
            } catch (PlaywrightException secondAttempt) {
                System.err.println("locatorFor(" + elementId + ") failed twice: "
                        + secondAttempt.getMessage());
                return null;
            }
        }
    }

    private void scrollIntoView(Locator locator) {
        try {
            locator.scrollIntoViewIfNeeded(
                    new Locator.ScrollIntoViewIfNeededOptions().setTimeout(2_000));
        } catch (RuntimeException ignored) {}
    }

    /** Reads element disabled/readonly state via JS (dynamic — reflects post-mutation values). */
    private JSONObject elementState(int elementId) {
        try {
            String json = (String) page.evaluate(JS_ELEMENT_STATE, elementId);
            return new JSONObject(json);
        } catch (RuntimeException e) {
            return new JSONObject().put("ok", false);
        }
    }

    /**
     * Uses {@code elementFromPoint} to find whether another element is covering the target.
     * Returns JSON with {@code blocker_id} (the skyvern-id of the covering element, or null).
     */
    private JSONObject blockerAtPoint(int elementId) {
        try {
            String json = (String) page.evaluate(JS_BLOCKER_AT_POINT, elementId);
            return new JSONObject(json);
        } catch (RuntimeException e) {
            return new JSONObject().put("ok", false);
        }
    }

    private void safeguardBeforeAction() {
        try {
            page.waitForLoadState(LoadState.DOMCONTENTLOADED,
                    new Page.WaitForLoadStateOptions().setTimeout(3_000));
        } catch (PlaywrightException ignored) {}
    }

    /**
     * Waits for page stability after any action:
     * 1. DOMCONTENTLOADED + LOAD load states.
     * 2. Brief sleep so React / Vue re-renders fire.
     * 3. NETWORKIDLE (short timeout — some SPAs never go idle, so we don't block on it).
     * 4. DOM-stable JS: MutationObserver 300 ms silence + loading spinner check.
     */
    /**
     * Waits for the page to stabilise after an action. Designed to be fast on steady pages
     * and fail-safe on continuously-mutating pages (e.g. Google sign-in):
     *
     * <ol>
     *   <li>DOMCONTENTLOADED  — 2 s timeout (HTML parsed)</li>
     *   <li>LOAD              — 2 s timeout (blocking resources fetched)</li>
     *   <li>short sleep       — lets React / Vue flush their queued renders</li>
     *   <li>NETWORKIDLE       — 1 s timeout (SPAs that never go idle just skip this)</li>
     *   <li>DOM-stable JS     — 2 s hard ceiling inside the JS itself; resolves early
     *                           when spinners are gone and mutations stop for 300 ms</li>
     * </ol>
     *
     * Worst-case total: 2 + 2 + 0.15 + 1 + 2 = ~7.15 s.
     */
    private void settleAfterAction() {
        try {
            page.waitForLoadState(LoadState.DOMCONTENTLOADED,
                    new Page.WaitForLoadStateOptions().setTimeout(2_000));
        } catch (RuntimeException ignored) {}
        try {
            page.waitForLoadState(LoadState.LOAD,
                    new Page.WaitForLoadStateOptions().setTimeout(2_000));
        } catch (RuntimeException ignored) {}
        sleepQuietly(POST_ACTION_SETTLE_MS);
        try {
            page.waitForLoadState(LoadState.NETWORKIDLE,
                    new Page.WaitForLoadStateOptions().setTimeout(1_000));
        } catch (RuntimeException ignored) {}
        // DOM stability: JS resolves after ≤2 s regardless (see MAX_WAIT_MS in JS_WAIT_DOM_STABLE).
        try {
            page.evaluate(JS_WAIT_DOM_STABLE);
        } catch (RuntimeException ignored) {
            // Best-effort — if the page navigated mid-evaluate, just continue.
        }
    }

    private String readInputValue(Locator locator) {
        try {
            String tag = (String) locator.evaluate("el => el.tagName && el.tagName.toLowerCase()");
            if ("input".equals(tag) || "textarea".equals(tag) || "select".equals(tag)) {
                return locator.inputValue();
            }
            return (String) locator.evaluate(
                    "el => el.isContentEditable ? el.textContent : el.innerText");
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private boolean verifyTyped(Locator locator, String expected) {
        String actual = readInputValue(locator);
        if (actual == null) return false;
        return actual.equals(expected) || actual.contains(expected);
    }

    /**
     * Inserts a random inter-action delay when {@code interActionDelayMs > 0}. This introduces
     * human-like pacing and reduces the risk of hitting rate-limits on fast-responding sites.
     */
    private void applyInterActionDelay() {
        if (interActionDelayMs <= 0) return;
        // jitter: uniform random in [0, interActionDelayMs]
        int jitter = (int) (Math.random() * interActionDelayMs);
        if (jitter > 0) sleepQuietly(jitter);
    }

    private static void sleepQuietly(int ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static String firstLine(String s) {
        if (s == null) return "(no message)";
        int nl = s.indexOf('\n');
        String head = nl >= 0 ? s.substring(0, nl) : s;
        return head.length() > 200 ? head.substring(0, 200) + "…" : head;
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
        if (context  != null) { try { context.close();    } catch (RuntimeException ignored) {} }
        if (browser  != null) { try { browser.close();    } catch (RuntimeException ignored) {} }
        if (playwright != null){ try { playwright.close(); } catch (RuntimeException ignored) {} }
    }
}
