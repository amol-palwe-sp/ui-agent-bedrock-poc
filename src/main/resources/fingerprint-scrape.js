() => {
  const VIEWPORT_ONLY = __VIEWPORT_ONLY__;
  const ATTR = 'data-ui-agent-id';
  const out = [];
  const usedIds = new Map();

  function djb2Hash(str) {
    let hash = 5381;
    for (let i = 0; i < str.length; i++) {
      hash = ((hash << 5) + hash) + str.charCodeAt(i);
    }
    return Math.abs(hash) >>> 0;
  }

  function toHex(n) {
    return n.toString(16).padStart(8, '0').slice(-8);
  }

  function sanitize(s, max) {
    return (s || '').replace(/\s+/g, ' ').trim().slice(0, max || 200);
  }

  function nearestFormContext(el) {
    let cur = el;
    for (let i = 0; i < 8 && cur; i++) {
      if (cur.tagName && cur.tagName.toLowerCase() === 'form') {
        return cur.id || cur.getAttribute('name') || '';
      }
      cur = cur.parentElement;
    }
    return '';
  }

  function sectionLabel(el) {
    const labelled = el.getAttribute('aria-labelledby');
    if (labelled) {
      const ref = document.getElementById(labelled);
      if (ref) return sanitize(ref.innerText, 40);
    }
    let cur = el.parentElement;
    for (let i = 0; i < 5 && cur; i++) {
      const aria = cur.getAttribute('aria-label');
      if (aria) return sanitize(aria, 40);
      if (cur.id) return cur.id;
      cur = cur.parentElement;
    }
    return '';
  }

  function getSemanticPath(el) {
    const parts = [];
    let cur = el;
    while (cur && cur !== document.body && parts.length < 8) {
      const tag = (cur.tagName || '').toLowerCase();
      const id = cur.id;
      const role = cur.getAttribute('role');
      const name = cur.getAttribute('name');
      const testid = cur.getAttribute('data-testid');
      const semanticTags = ['form', 'nav', 'header', 'main', 'section', 'aside'];
      if (id) {
        parts.unshift(tag + '#' + id);
      } else if (testid) {
        parts.unshift(tag + '[data-testid=' + testid + ']');
      } else if (role) {
        parts.unshift(tag + '[role=' + role + ']');
      } else if (name) {
        parts.unshift(tag + '[name=' + name + ']');
      } else if (semanticTags.includes(tag)) {
        parts.unshift(tag);
      }
      cur = cur.parentElement;
    }
    return parts.join('>');
  }

  /**
   * Viewport-independent structural identity (Skyvern-inspired). Combines stable signals — tag,
   * role, type, name, aria-label, placeholder, href, normalized text, and the semantic ancestor
   * path — while deliberately excluding our injected data-ui-agent-id and volatile geometry. Used
   * at replay to remap an element by content even when its fingerprint hash / id churned.
   */
  function computeStructuralHash(el) {
    const tag = (el.tagName || '').toLowerCase();
    const parts = [
      tag,
      el.getAttribute('role') || '',
      (el.getAttribute('type') || '').toLowerCase(),
      el.getAttribute('name') || '',
      el.getAttribute('aria-label') || '',
      el.getAttribute('placeholder') || '',
      (el.getAttribute('href') || '').slice(0, 200),
      sanitize(el.innerText || el.textContent || '', 80),
      getSemanticPath(el)
    ];
    return toHex(djb2Hash(parts.join('|')));
  }

  function computeFingerprint(el, doc) {
    const tag = el.tagName.toLowerCase();
    const type = (el.getAttribute('type') || '').toLowerCase();
    const formCtx = nearestFormContext(el);
    const section = sectionLabel(el);

    if (el.id) {
      return { fp: 'id:' + el.id, level: 1 };
    }
    const testid = el.getAttribute('data-testid');
    if (testid) {
      return { fp: 'testid:' + testid, level: 2 };
    }
    const aria = el.getAttribute('aria-label');
    if (aria) {
      return { fp: 'aria:' + tag + ':' + type + ':' + sanitize(aria, 80), level: 3 };
    }
    const name = el.getAttribute('name');
    if (name) {
      return { fp: 'name:' + tag + ':' + name + ':' + formCtx, level: 4 };
    }
    const placeholder = el.getAttribute('placeholder');
    if (placeholder) {
      return { fp: 'placeholder:' + tag + ':' + sanitize(placeholder, 80), level: 5 };
    }
    const text = sanitize((el.innerText || el.textContent || ''), 50);
    if (text) {
      return { fp: 'text:' + tag + ':' + text + ':' + section, level: 6 };
    }
    return { fp: 'path:' + getSemanticPath(el), level: 7 };
  }

  function assignStableId(fingerprint) {
    const hash = toHex(djb2Hash(fingerprint));
    const count = usedIds.get(hash) || 0;
    usedIds.set(hash, count + 1);
    return count === 0 ? hash : hash + '_' + count;
  }

  function escAttr(s) {
    return (s || '').replace(/'/g, "\\'");
  }

  /** Standard CSS selectors for document.querySelector (REQ-SIV-4). */
  function buildFallbacks(el) {
    const fallbacks = [];
    const tag = el.tagName.toLowerCase();
    const id = el.id;
    const name = el.getAttribute('name');
    const type = el.getAttribute('type');
    const aria = el.getAttribute('aria-label');

    function add(sel) {
      if (sel && fallbacks.indexOf(sel) < 0) fallbacks.push(sel);
    }

    // Test-automation attributes are the most stable selectors when present.
    const testAttrs = ['data-testid', 'data-test', 'data-qa', 'data-automation-id', 'data-cy'];
    for (const attr of testAttrs) {
      const v = el.getAttribute(attr);
      if (v) add(tag + "[" + attr + "='" + escAttr(v) + "']");
    }
    if (id) {
      add(tag + '#' + CSS.escape(id));
    }
    if (id && (name || type)) {
      let sel = tag + '#' + CSS.escape(id);
      if (name) sel += "[name='" + escAttr(name) + "']";
      if (type) sel += "[type='" + escAttr(type) + "']";
      add(sel);
    }
    if (aria) {
      add("[aria-label='" + escAttr(aria) + "']");
    }
    if (name && type) {
      add(tag + "[name='" + escAttr(name) + "'][type='" + escAttr(type) + "']");
    }
    if (name) {
      add(tag + "[name='" + escAttr(name) + "']");
    }
    // Role as a last-resort querySelector-safe hint (may be broad; ordered last).
    const role = el.getAttribute('role');
    if (role) {
      add(tag + "[role='" + escAttr(role) + "']");
    }
    return fallbacks.slice(0, 6);
  }

  function isVisible(el) {
    const r = el.getBoundingClientRect();
    if (r.width < 2 || r.height < 2) return false;
    const st = window.getComputedStyle(el);
    if (st.visibility === 'hidden' || st.display === 'none' || parseFloat(st.opacity) === 0) return false;
    if (el.hasAttribute('disabled') || el.getAttribute('aria-hidden') === 'true') return false;
    if (VIEWPORT_ONLY) {
      const cx = r.left + r.width / 2;
      const cy = r.top + r.height / 2;
      if (cx < 0 || cy < 0 || cx > window.innerWidth || cy > window.innerHeight) return false;
    }
    return true;
  }

  function scrapeDoc(doc, frameLabel) {
    doc.querySelectorAll('[' + ATTR + ']').forEach(el => el.removeAttribute(ATTR));

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
      const { fp, level } = computeFingerprint(el, doc);
      const stableId = assignStableId(fp);
      const ordinal = stableId.includes('_') ? parseInt(stableId.split('_').pop(), 10) || 0 : 0;
      el.setAttribute(ATTR, stableId);

      const tag = el.tagName.toLowerCase();
      const role = el.getAttribute('role') || '';
      const htmlType = (el.getAttribute('type') || '').toLowerCase();
      const ariaLabel = el.getAttribute('aria-label') || '';
      const placeholder = el.getAttribute('placeholder') || '';
      const name = el.getAttribute('name') || '';
      const href = el.getAttribute('href') || '';
      const value = ('value' in el) ? (el.value || '') : '';
      const text = sanitize((el.innerText || el.textContent || ''), 200);
      const label = sanitize(ariaLabel || text || placeholder || el.getAttribute('title') || name || '', 200);

      let optionsArr;
      if (tag === 'select') {
        optionsArr = Array.from(el.options || []).slice(0, 50)
          .map(o => ({ value: o.value, label: (o.label || o.text || '').trim() }));
      }

      out.push({
        id: stableId,
        fingerprintString: fp,
        fingerprintLevel: level,
        structuralHash: computeStructuralHash(el),
        stableIdOrdinal: ordinal,
        fallbackSelectors: buildFallbacks(el),
        elementLabel: label,
        tag, role, htmlType, name, placeholder,
        ariaLabel, frame: frameLabel,
        href: href ? href.slice(0, 200) : '',
        value: typeof value === 'string' ? value.slice(0, 200) : '',
        text: label,
        options: optionsArr
      });
    });
  }

  scrapeDoc(document, 'main');
  document.querySelectorAll('iframe').forEach((iframe, fi) => {
    try {
      const doc = iframe.contentDocument;
      if (doc && doc.body) scrapeDoc(doc, 'iframe-' + fi);
    } catch (e) { /* cross-origin */ }
  });

  return JSON.stringify(out);
}
