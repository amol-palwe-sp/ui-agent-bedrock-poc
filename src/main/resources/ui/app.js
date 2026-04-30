(function () {
  'use strict';

  // ── State ──────────────────────────────────────────────────────────────────
  let uploadedFile    = null;
  let generatedSteps  = [];   // tokenized steps (contain {Token} placeholders)
  let placeholders    = [];   // PlaceholderObject[]
  let generatedUrl    = '';
  const HALT_CLAUSE   = 'this completes all steps — do not perform any further actions';
  let eventSource     = null;
  let isGenerating    = false;
  let isRunning       = false;

  // ── Element refs ───────────────────────────────────────────────────────────
  const dropZone               = document.getElementById('dropZone');
  const fileInput              = document.getElementById('fileInput');
  const fileInfo               = document.getElementById('fileInfo');
  const fileName               = document.getElementById('fileName');
  const fileSize               = document.getElementById('fileSize');
  const overrideUrl            = document.getElementById('overrideUrl');
  const maxFramesInput         = document.getElementById('maxFrames');
  const saveDebugFrames        = document.getElementById('saveDebugFrames');
  const btnGenerate            = document.getElementById('btnGenerate');
  const btnGenerateText        = document.getElementById('btnGenerateText');
  const btnGenerateSpinner     = document.getElementById('btnGenerateSpinner');
  const sectionScript          = document.getElementById('sectionScript');
  const placeholderSection     = document.getElementById('placeholderSection');
  const placeholderGrid        = document.getElementById('placeholderGrid');
  const stepsDetails           = document.getElementById('stepsDetails');
  const stepsSummary           = document.getElementById('stepsSummary');
  const stepsList              = document.getElementById('stepsList');
  const btnAddStep             = document.getElementById('btnAddStep');
  const commandBox             = document.getElementById('commandBox');
  const btnCopy                = document.getElementById('btnCopy');
  const validationBadge        = document.getElementById('validationBadge');
  const tokenInfo              = document.getElementById('tokenInfo');
  const btnRun                 = document.getElementById('btnRun');
  const sectionLog             = document.getElementById('sectionLog');
  const logPanel               = document.getElementById('logPanel');
  const btnStop                = document.getElementById('btnStop');
  const statusDot              = document.getElementById('statusDot');
  const statusText             = document.getElementById('statusText');
  const toastContainer         = document.getElementById('toastContainer');
  const emptyPlaceholderWarning = document.getElementById('emptyPlaceholderWarning');
  const warningText            = document.getElementById('warningText');
  const btnContinueAnyway      = document.getElementById('btnContinueAnyway');
  const btnGoBack              = document.getElementById('btnGoBack');

  // ── Init ───────────────────────────────────────────────────────────────────
  document.addEventListener('DOMContentLoaded', function () {
    initDropZone();
    initSSE();
    setStatus('ready');

    btnGenerate.addEventListener('click', handleGenerate);
    btnRun.addEventListener('click', handleRun);
    btnStop.addEventListener('click', handleStop);
    btnCopy.addEventListener('click', handleCopy);
    btnAddStep.addEventListener('click', function () { addStep(''); });
    btnContinueAnyway.addEventListener('click', function () {
      hideWarningBanner();
      doRun();
    });
    btnGoBack.addEventListener('click', hideWarningBanner);
  });

  // ── Drop Zone ──────────────────────────────────────────────────────────────
  function initDropZone() {
    dropZone.addEventListener('dragover', function (e) {
      e.preventDefault();
      dropZone.classList.add('drag-over');
    });
    dropZone.addEventListener('dragleave', function () {
      dropZone.classList.remove('drag-over');
    });
    dropZone.addEventListener('drop', function (e) {
      e.preventDefault();
      dropZone.classList.remove('drag-over');
      const file = e.dataTransfer.files[0];
      if (file) validateAndSetFile(file);
    });
    dropZone.addEventListener('click', function () {
      fileInput.click();
    });
    fileInput.addEventListener('change', function () {
      if (fileInput.files[0]) validateAndSetFile(fileInput.files[0]);
    });
  }

  function validateAndSetFile(file) {
    if (!file.name.toLowerCase().endsWith('.mp4')) {
      showError('Only MP4 files are supported.');
      return;
    }
    uploadedFile = file;
    fileName.textContent = file.name;
    fileSize.textContent = formatFileSize(file.size);
    fileInfo.classList.remove('hidden');
    btnGenerate.disabled = false;
  }

  // ── Generate ───────────────────────────────────────────────────────────────
  function handleGenerate() {
    if (isGenerating || !uploadedFile) return;
    isGenerating = true;
    setStatus('generating');
    btnGenerate.disabled = true;
    btnGenerateText.textContent = 'Generating...';
    btnGenerateSpinner.classList.remove('hidden');
    clearLog();

    const form = new FormData();
    form.append('video', uploadedFile);
    if (overrideUrl.value.trim()) form.append('url', overrideUrl.value.trim());
    form.append('maxFrames', maxFramesInput.value);
    if (saveDebugFrames.checked) form.append('debugFrames', 'true');

    fetch('/api/generate', { method: 'POST', body: form })
      .then(function (r) { return r.json(); })
      .then(function (data) {
        if (data.error) {
          showError(data.error);
          setStatus('error');
          return;
        }
        generatedUrl = data.url || '';

        // Tokenize steps and extract placeholders
        const rawSteps = (data.steps || []).filter(function (s) {
          return !s.toLowerCase().includes('do not perform any further actions');
        });
        const extracted = extractPlaceholders(rawSteps);
        generatedSteps = extracted.tokenizedSteps;
        placeholders   = extracted.placeholders;

        renderPlaceholderSection();
        renderStepsList();
        substituteAndAssemble();

        showValidationBadge(data.isValid, data.issues || []);
        showTokenInfo(data.inputTokens, data.outputTokens, data.costUsd);
        unlockSection(sectionScript);
        setStatus('ready');
      })
      .catch(function (err) {
        showError(err.message);
        setStatus('error');
      })
      .finally(function () {
        isGenerating = false;
        btnGenerate.disabled = false;
        btnGenerateText.textContent = 'Generate Script';
        btnGenerateSpinner.classList.add('hidden');
      });
  }

  // ── Placeholder Extraction ─────────────────────────────────────────────────

  // Known label → canonical token mapping (checked in order, case-insensitive)
  const LABEL_MAP = [
    [/email or phone/i,   'Email'],
    [/primary email/i,    'PrimaryEmail'],
    [/email/i,            'Email'],
    [/password/i,         'Password'],
    [/first name/i,       'FirstName'],
    [/last name/i,        'LastName'],
    [/username/i,         'Username'],
    [/phone/i,            'Phone'],
    [/name/i,             'Name'],
  ];

  function labelToToken(label) {
    const trimmed = label.trim();
    for (const [pattern, token] of LABEL_MAP) {
      if (pattern.test(trimmed)) return token;
    }
    // Generic: camelCase from label, strip trailing "field"
    return trimmed
      .replace(/\bfield\b/gi, '')
      .trim()
      .split(/\s+/)
      .map(function (w, i) {
        return i === 0
          ? w.charAt(0).toUpperCase() + w.slice(1)
          : w.charAt(0).toUpperCase() + w.slice(1);
      })
      .join('');
  }

  function extractPlaceholders(rawSteps) {
    const ENTER_RE = /enter "([^"]+)" in (?:the )?(.+?) field/i;
    const tokenCounts = {};   // token → count (for deduplication)
    const tokenizedSteps = [];
    const placeholderList = [];
    const seenValues = {};    // value+label → token (avoid duplicates)

    rawSteps.forEach(function (step) {
      const m = ENTER_RE.exec(step);
      if (!m) {
        tokenizedSteps.push(step);
        return;
      }

      const value = m[1];
      const label = m[2];
      const cacheKey = label.toLowerCase() + '::' + value;

      if (seenValues[cacheKey]) {
        // Reuse same token for identical value+label
        const tokenizedStep = step.replace(
          '"' + value + '"',
          seenValues[cacheKey]
        );
        tokenizedSteps.push(tokenizedStep);
        return;
      }

      let baseToken = labelToToken(label);
      tokenCounts[baseToken] = (tokenCounts[baseToken] || 0) + 1;
      const count = tokenCounts[baseToken];
      const token = '{' + baseToken + (count > 1 ? count : '') + '}';

      const isPassword = /password/i.test(baseToken);
      const isEmail    = /email/i.test(baseToken);

      const ph = {
        token:        token,
        label:        label.trim(),
        defaultValue: value,
        currentValue: value,
        inputType:    isPassword ? 'password' : (isEmail ? 'email' : 'text'),
        isPassword:   isPassword,
      };

      placeholderList.push(ph);
      seenValues[cacheKey] = token;

      const tokenizedStep = step.replace('"' + value + '"', token);
      tokenizedSteps.push(tokenizedStep);
    });

    return { tokenizedSteps: tokenizedSteps, placeholders: placeholderList };
  }

  // ── Placeholder Rendering ──────────────────────────────────────────────────
  function renderPlaceholderSection() {
    placeholderGrid.innerHTML = '';

    if (placeholders.length === 0) {
      placeholderSection.classList.add('hidden');
      return;
    }

    placeholders.forEach(function (ph) {
      // Label cell
      const labelEl = document.createElement('div');
      labelEl.className = 'placeholder-label';
      labelEl.textContent = ph.token;
      labelEl.title = ph.label;

      // Input cell (wrapped for password toggle)
      const inputWrapper = document.createElement('div');
      if (ph.isPassword) inputWrapper.className = 'password-wrapper';

      const input = document.createElement('input');
      input.type        = ph.inputType;
      input.value       = ph.currentValue;
      input.className   = 'placeholder-input' + (ph.currentValue.trim() === '' ? ' empty' : '');
      input.placeholder = ph.label;
      input.dataset.token = ph.token;

      input.addEventListener('input', function () {
        updatePlaceholder(ph.token, input.value);
        input.className = 'placeholder-input' + (input.value.trim() === '' ? ' empty' : '');
        updateWarnIcon(ph.token, warnIcon);
        substituteAndAssemble();
      });

      inputWrapper.appendChild(input);

      if (ph.isPassword) {
        const toggle = document.createElement('button');
        toggle.type = 'button';
        toggle.className = 'password-toggle';
        toggle.textContent = '👁';
        toggle.title = 'Show / hide';
        toggle.addEventListener('click', function () {
          input.type = input.type === 'password' ? 'text' : 'password';
          toggle.textContent = input.type === 'password' ? '👁' : '🙈';
        });
        inputWrapper.appendChild(toggle);
      }

      // Warning icon cell
      const warnIcon = document.createElement('span');
      warnIcon.className = 'placeholder-warn-icon' + (ph.currentValue.trim() === '' ? ' visible' : '');
      warnIcon.textContent = '⚠';
      warnIcon.title = 'Value is empty';

      placeholderGrid.appendChild(labelEl);
      placeholderGrid.appendChild(inputWrapper);
      placeholderGrid.appendChild(warnIcon);
    });

    placeholderSection.classList.remove('hidden');
  }

  function updatePlaceholder(token, value) {
    const ph = placeholders.find(function (p) { return p.token === token; });
    if (ph) ph.currentValue = value;
  }

  function updateWarnIcon(token, iconEl) {
    const ph = placeholders.find(function (p) { return p.token === token; });
    if (!ph) return;
    if (ph.currentValue.trim() === '') {
      iconEl.classList.add('visible');
    } else {
      iconEl.classList.remove('visible');
    }
  }

  // ── Substitution + Assemble ────────────────────────────────────────────────
  function substituteAndAssemble() {
    const substituted = generatedSteps.map(function (step) {
      let s = step;
      placeholders.forEach(function (ph) {
        s = s.split(ph.token).join('"' + ph.currentValue + '"');
      });
      return s;
    });

    const active = substituted.filter(function (s) { return s.trim().length > 0; });
    if (active.length === 0) {
      commandBox.textContent = '';
      btnRun.disabled = true;
      updateStepsSummary(0);
      return;
    }

    const allSteps   = active.concat([HALT_CLAUSE]);
    const goalString = allSteps.join(', then ');
    const goalLine   = "./gradlew run --args='--url=" + (generatedUrl || 'https://example.com')
                     + " --goal=" + goalString + "'";
    commandBox.textContent = goalLine;
    btnRun.disabled = isRunning;
    updateStepsSummary(active.length);
    revalidateClient();
  }

  function updateStepsSummary(count) {
    stepsSummary.textContent = 'Script Steps (' + count + ' step' + (count !== 1 ? 's' : '') + ')';
  }

  // ── Steps Editor ───────────────────────────────────────────────────────────
  function renderStepsList() {
    stepsList.innerHTML = '';
    generatedSteps.forEach(function (step, i) {
      const row = document.createElement('div');
      row.className = 'step-row';

      const num = document.createElement('span');
      num.className = 'step-number';
      num.textContent = i + 1;

      const ta = document.createElement('textarea');
      ta.className = 'step-input';
      ta.rows = 2;
      ta.value = step;
      ta.addEventListener('input', function () {
        generatedSteps[i] = ta.value;
        substituteAndAssemble();
      });

      const rm = document.createElement('button');
      rm.className = 'btn-remove';
      rm.title = 'Remove step';
      rm.textContent = '✕';
      rm.addEventListener('click', function () { removeStep(i); });

      row.appendChild(num);
      row.appendChild(ta);
      row.appendChild(rm);
      stepsList.appendChild(row);
    });
  }

  function addStep(text) {
    generatedSteps.push(text);
    renderStepsList();
    const textareas = stepsList.querySelectorAll('.step-input');
    if (textareas.length > 0) textareas[textareas.length - 1].focus();
    substituteAndAssemble();
  }

  function removeStep(index) {
    generatedSteps.splice(index, 1);
    renderStepsList();
    substituteAndAssemble();
  }

  function revalidateClient() {
    const cmd = commandBox.textContent || '';
    const issues = [];
    if (!cmd.startsWith("./gradlew run --args='"))          issues.push("Missing ./gradlew run --args='");
    if (!cmd.includes('--url='))                             issues.push('Missing --url=');
    if (!cmd.includes('--goal='))                            issues.push('Missing --goal=');
    if (!cmd.includes('do not perform any further actions')) issues.push('Missing halt clause');
    const isValid = issues.length === 0;
    showValidationBadge(isValid, issues);
  }

  // ── Run ────────────────────────────────────────────────────────────────────
  function handleRun() {
    if (isRunning) return;
    validatePlaceholders();
  }

  function validatePlaceholders() {
    const empty = placeholders.filter(function (p) {
      return p.currentValue.trim() === '';
    });

    if (empty.length > 0) {
      const tokens = empty.map(function (p) { return p.token; }).join(', ');
      showWarningBanner(tokens + (empty.length === 1 ? ' is' : ' are') + ' empty. Script will run with empty values.');
    } else {
      doRun();
    }
  }

  function showWarningBanner(msg) {
    warningText.textContent = msg;
    emptyPlaceholderWarning.classList.remove('hidden');
    emptyPlaceholderWarning.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
  }

  function hideWarningBanner() {
    emptyPlaceholderWarning.classList.add('hidden');
  }

  function doRun() {
    const goalLine = commandBox.textContent.trim();
    if (!goalLine) return;

    isRunning = true;
    setStatus('running');
    btnRun.disabled = true;
    btnStop.classList.remove('hidden');
    unlockSection(sectionLog);
    sectionLog.scrollIntoView({ behavior: 'smooth' });
    clearLog();

    fetch('/api/run', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ goalLine: goalLine })
    })
      .then(function (r) { return r.json(); })
      .then(function (data) {
        if (data.error) {
          showError(data.error);
          resetRunState();
        }
      })
      .catch(function (err) {
        showError(err.message);
        resetRunState();
      });
  }

  function handleStop() {
    fetch('/api/stop', { method: 'POST' })
      .then(function () { resetRunState(); })
      .catch(function () { resetRunState(); });
  }

  function resetRunState() {
    isRunning = false;
    btnRun.disabled = false;
    btnStop.classList.add('hidden');
    setStatus('ready');
  }

  // ── SSE ────────────────────────────────────────────────────────────────────
  function initSSE() {
    connectSSE();
  }

  function connectSSE() {
    eventSource = new EventSource('/api/stream');

    eventSource.onmessage = function (e) {
      let data;
      try { data = JSON.parse(e.data); } catch (_) { return; }

      switch (data.type) {
        case 'log':      appendLog(data.text, data.level);   break;
        case 'status':   setStatus(data.value);              break;
        case 'progress': updateProgress(data);               break;
        case 'done':     handleDone(data.exitCode);          break;
        case 'error':    showError(data.message);            break;
      }
    };

    eventSource.onerror = function () {
      if (eventSource.readyState === EventSource.CLOSED) {
        setTimeout(connectSSE, 2000);
      }
    };
  }

  function appendLog(text, level) {
    const now  = new Date();
    const time = now.toTimeString().substring(0, 8);

    const placeholder = logPanel.querySelector('.log-placeholder');
    if (placeholder) placeholder.remove();

    const line = document.createElement('div');
    line.className = 'log-line log-' + (level || 'info');
    line.textContent = '[' + time + '] ' + (text || '');
    logPanel.appendChild(line);
    logPanel.scrollTop = logPanel.scrollHeight;
  }

  function updateProgress(data) {
    const pct = data.total > 0 ? Math.round((data.current / data.total) * 100) : 0;
    appendLog(
      (data.label || 'Progress') + ' ' + data.current + '/' + data.total + ' (' + pct + '%)',
      'info'
    );
  }

  function handleDone(exitCode) {
    resetRunState();
    if (exitCode === 0) {
      appendLog('Completed successfully', 'success');
    } else {
      appendLog('Finished with errors', 'error');
    }
  }

  // ── Status ─────────────────────────────────────────────────────────────────
  const STATUS_LABELS = {
    ready:      'Ready',
    generating: 'Generating...',
    running:    'Running...',
    error:      'Error'
  };

  function setStatus(value) {
    statusDot.className    = 'status-dot status-' + value;
    statusText.textContent = STATUS_LABELS[value] || value;
  }

  // ── Copy ───────────────────────────────────────────────────────────────────
  function handleCopy() {
    const text = commandBox.textContent;
    if (!text) return;
    navigator.clipboard.writeText(text).then(function () {
      btnCopy.textContent = '✅ Copied!';
      setTimeout(function () { btnCopy.textContent = '📋 Copy'; }, 2000);
    });
  }

  // ── Helpers ────────────────────────────────────────────────────────────────
  function unlockSection(el) {
    el.classList.remove('locked');
    el.scrollIntoView({ behavior: 'smooth' });
  }

  function showValidationBadge(isValid, issues) {
    validationBadge.className = 'badge ' + (isValid ? 'badge-success' : 'badge-error');
    validationBadge.textContent = isValid ? '✅ Valid' : '❌ ' + issues.length + ' issue(s)';
    validationBadge.title = issues.join('\n');
    validationBadge.classList.remove('hidden');
  }

  function showTokenInfo(inputTok, outputTok, cost) {
    const total = (inputTok || 0) + (outputTok || 0);
    tokenInfo.textContent = 'Tokens: ' + total.toLocaleString()
                          + ' | Cost: $' + (cost || 0).toFixed(4);
    tokenInfo.classList.remove('hidden');
  }

  function showError(msg) {
    const toast = document.createElement('div');
    toast.className = 'toast';
    toast.textContent = msg;
    toastContainer.appendChild(toast);
    setTimeout(function () { toast.remove(); }, 5000);

    if (!sectionLog.classList.contains('locked')) {
      appendLog('ERROR: ' + msg, 'error');
    }
  }

  function clearLog() {
    logPanel.innerHTML = '<div class="log-placeholder">Execution log will appear here...</div>';
  }

  function formatFileSize(bytes) {
    if (bytes > 1024 * 1024) return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
    return Math.round(bytes / 1024) + ' KB';
  }

})();
