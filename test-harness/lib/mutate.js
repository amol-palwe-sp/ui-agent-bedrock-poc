'use strict';

// Structural mutations for the Tier C replay-resilience demo.
// The SAME logical page is served in structurally different forms so we can
// record a script on v1 and replay it on v2..v7, measuring survival per
// fingerprint level (id -> aria -> text -> structural).
//
// Mutations operate on marker comments/attributes present in the base HTML:
//   - reorder:   swaps the two children of <div data-reorder-group>
//   - classes:   rewrites class names inside data-variant-scope
//   - ids:       rewrites element id="..." values
//   - wrap:      wraps each labelled field in an extra <div>
//   - i18n:      swaps English labels/placeholders for German
//   - theme:     adds a dark-theme class to <body>

const I18N = {
  'First name': 'Vorname',
  'Last name': 'Nachname',
  'Email': 'E-Mail',
  'Password': 'Passwort',
  'Department': 'Abteilung',
  'Create user': 'Benutzer erstellen',
  'Sign in': 'Anmelden',
  'Next': 'Weiter',
};

function reorder(html) {
  // Swap the two immediate blocks marked with data-reorder-item inside the group.
  return html.replace(
    /(<div data-reorder-group>)([\s\S]*?)(<\/div><!--\/reorder-->)/,
    (_m, open, inner, close) => {
      const items = inner.split('<!--item-->').filter((s) => s.trim().length);
      items.reverse();
      return open + items.join('<!--item-->') + close;
    }
  );
}

function classes(html) {
  return html.replace(/class="([^"]*)"/g, (_m, cls) => {
    const renamed = cls
      .split(/\s+/)
      .filter(Boolean)
      .map((c) => (c.startsWith('mut-') ? c : `x-${c}`))
      .join(' ');
    return `class="${renamed}"`;
  });
}

function ids(html) {
  return html.replace(/id="([^"]+)"/g, (_m, id) =>
    id === 'app' ? `id="${id}"` : `id="r_${id}"`
  );
}

function wrap(html) {
  return html.replace(
    /(<label[\s\S]*?<\/label>\s*<(?:input|select|textarea)[^>]*>)/g,
    '<div class="mut-wrapper">$1</div>'
  );
}

function i18n(html) {
  let out = html;
  for (const [en, de] of Object.entries(I18N)) {
    out = out.split(en).join(de);
  }
  return out;
}

function theme(html) {
  return html.replace('<body', '<body class="theme-dark"');
}

const PIPELINE = {
  1: [],
  2: [reorder],
  3: [classes],
  4: [ids],
  5: [wrap],
  6: [i18n],
  7: [theme],
};

function applyVariant(html, variant) {
  const steps = PIPELINE[variant] || [];
  return steps.reduce((acc, fn) => fn(acc), html);
}

module.exports = { applyVariant };
