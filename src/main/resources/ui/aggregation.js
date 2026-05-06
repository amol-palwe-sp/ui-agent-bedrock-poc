(function () {
  'use strict';

  // ── State ──────────────────────────────────────────────────────────────────
  let uploadedFile       = null;
  let generatedSteps     = [];   // step strings with {Token} placeholders
  let placeholders       = [];   // PlaceholderObject[]
  let paginationPattern  = null; // { type, description, selectorHint }
  let generatedUrl       = '';   // URL extracted from video by Claude
  let eventSource        = null;
  let isGenerating       = false;
  let isRunning          = false;

  // ── Element refs ───────────────────────────────────────────────────────────
  const dropZone               = document.getElementById('dropZone');
  const fileInput              = document.getElementById('fileInput');
  const fileInfo               = document.getElementById('fileInfo');
  const fileName               = document.getElementById('fileName');
  const fileSize               = document.getElementById('fileSize');
  const overrideUrl            = document.getElementById('overrideUrl');
  const maxFramesInput         = document.getElementById('maxFrames');
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
  const goalBox                = document.getElementById('goalBox');
  const validationBadge        = document.getElementById('validationBadge');
  const tokenInfo              = document.getElementById('tokenInfo');
  const paginationInfo         = document.getElementById('paginationInfo');
  const paginationTypeBadge    = document.getElementById('paginationTypeBadge');
  const paginationDescription  = document.getElementById('paginationDescription');
  const paginationSelectorHint = document.getElementById('paginationSelectorHint');
  const emptyPlaceholderWarning = document.getElementById('emptyPlaceholderWarning');
  const warningText            = document.getElementById('warningText');
  const btnContinueAnyway      = document.getElementById('btnContinueAnyway');
  const btnGoBack              = document.getElementById('btnGoBack');
  const btnRun                 = document.getElementById('btnRun');
  const sectionLog             = document.getElementById('sectionLog');
  const logPanel               = document.getElementById('logPanel');
  const btnStop                = document.getElementById('btnStop');
  const statusDot              = document.getElementById('statusDot');
  const statusText             = document.getElementById('statusText');
  const toastContainer         = document.getElementById('toastContainer');
  const sectionResults         = document.getElementById('sectionResults');
  const statsBar               = document.getElementById('statsBar');
  const statsTotalRows         = document.getElementById('statsTotalRows');
  const statsPagesScraped      = document.getElementById('statsPagesScraped');
  const statsColumns           = document.getElementById('statsColumns');
  const downloadRow            = document.getElementById('downloadRow');
  const btnDownloadCsv         = document.getElementById('btnDownloadCsv');
  const downloadFileInfo       = document.getElementById('downloadFileInfo');
  const previewTableWrapper    = document.getElementById('previewTableWrapper');
  const previewTableHead       = document.getElementById('previewTableHead');
  const previewTableBody       = document.getElementById('previewTableBody');
  const previewNote            = document.getElementById('previewNote');
  const usageSummary           = document.getElementById('usageSummary');
  const resultsPlaceholder     = document.getElementById('resultsPlaceholder');
  const resultsTokenInfo       = document.getElementById('resultsTokenInfo');

  // ── Init ───────────────────────────────────────────────────────────────────
  document.addEventListener('DOMContentLoaded', function () {
    initDropZone();
    initSSE();
    setStatus('ready');

    btnGenerate.addEventListener('click', handleGenerate);
    btnRun.addEventListener('click', handleRun);
    btnStop.addEventListener('click', handleStop);
    btnAddStep.addEventListener('click', function () { addStep(''); });
    btnContinueAnyway.addEventListener('click', function () {
      hideWarningBanner();
      doRun();
    });
    btnGoBack.addEventListener('click', hideWarningBanner);
    btnDownloadCsv.addEventListener('click', handleDownload);
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
    dropZone.addEventListener('click', function () { fileInput.click(); });
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
    generatedUrl = '';
    setStatus('generating');
    btnGenerate.disabled = true;
    btnGenerateText.textContent = 'Generating...';
    btnGenerateSpinner.classList.remove('hidden');
    clearLog();

    const form = new FormData();
    form.append('video', uploadedFile);
    if (overrideUrl.value.trim()) form.append('url', overrideUrl.value.trim());
    form.append('maxFrames', maxFramesInput.value);

    fetch('/api/aggregation/generate', { method: 'POST', body: form })
      .then(function (r) { return r.json(); })
      .then(function (data) {
        if (data.error) {
          showError(data.error);
          setStatus('error');
          return;
        }

        // Store URL from Claude; fall back to override field if Claude returned blank
        generatedUrl = (data.url && data.url.trim())
            ? data.url.trim()
            : overrideUrl.value.trim();

        // Store pagination pattern for later use in Run
        paginationPattern = data.paginationPattern || null;

        // The steps come from Claude already with {Token} placeholders
        const rawSteps = (data.steps || []);
        const extracted = extractPlaceholders(rawSteps);
        generatedSteps = extracted.tokenizedSteps;
        placeholders   = extracted.placeholders;

        renderPlaceholderSection();
        renderStepsList();
        renderPaginationInfo(paginationPattern);
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
        btnGenerateText.textContent = '▶ Generate Script';
        btnGenerateSpinner.classList.add('hidden');
      });
  }

  // ── Placeholder Extraction ─────────────────────────────────────────────────
  // Claude already outputs {Token} placeholders in the steps.
  // We scan each step for {Token} patterns and build the placeholder list.

  function extractPlaceholders(rawSteps) {
    const TOKEN_RE = /\{([^}]+)\}/g;
    const seenTokens = {};
    const placeholderList = [];
    const tokenizedSteps = rawSteps.slice(); // steps are already tokenized by Claude

    rawSteps.forEach(function (step) {
      let m;
      TOKEN_RE.lastIndex = 0;
      while ((m = TOKEN_RE.exec(step)) !== null) {
        const tokenName = m[1];
        const token     = '{' + tokenName + '}';
        if (seenTokens[token]) continue;
        seenTokens[token] = true;

        const isPassword = /password/i.test(tokenName);
        const isEmail    = /email/i.test(tokenName);

        placeholderList.push({
          token:        token,
          label:        tokenName,
          currentValue: '',
          inputType:    isPassword ? 'password' : (isEmail ? 'email' : 'text'),
          isPassword:   isPassword,
        });
      }
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
      const labelEl = document.createElement('div');
      labelEl.className = 'placeholder-label';
      labelEl.textContent = ph.token;

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

      const warnIcon = document.createElement('span');
      warnIcon.className = 'placeholder-warn-icon visible';
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
  // Unlike app.js, we produce a plain goal string (not a CLI command).
  function substituteAndAssemble() {
    const substituted = generatedSteps.map(function (step) {
      let s = step;
      placeholders.forEach(function (ph) {
        // Wrap substituted value in quotes to match expected format
        s = s.split(ph.token).join('"' + ph.currentValue + '"');
      });
      return s;
    });

    const active = substituted.filter(function (s) { return s.trim().length > 0; });
    if (active.length === 0) {
      goalBox.textContent = '';
      btnRun.disabled = true;
      updateStepsSummary(0);
      return;
    }

    const goalString = active.join(', then ');
    goalBox.textContent = goalString;
    btnRun.disabled = isRunning;
    updateStepsSummary(active.length);
  }

  function updateStepsSummary(count) {
    stepsSummary.textContent = 'Script Steps (' + count + ' step' + (count !== 1 ? 's' : '') + ')';
  }

  // ── Pagination Info Display ────────────────────────────────────────────────
  function renderPaginationInfo(pp) {
    if (!pp) {
      paginationInfo.classList.add('hidden');
      return;
    }

    // Type badge
    const type = (pp.type || 'unknown').toLowerCase().replace(/_/g, '-');
    paginationTypeBadge.textContent = pp.type || 'unknown';
    paginationTypeBadge.className   = 'pagination-type-badge type-' + type;

    paginationDescription.textContent  = pp.description  || '—';
    paginationSelectorHint.textContent = pp.selectorHint || '—';

    paginationInfo.classList.remove('hidden');
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
      showWarningBanner(tokens + (empty.length === 1 ? ' is' : ' are')
              + ' empty. Aggregation will run with empty values.');
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
    const goalString = goalBox.textContent.trim();
    if (!goalString) return;

    // Override URL field takes precedence; fall back to Claude-extracted URL
    const url = overrideUrl.value.trim() || generatedUrl;

    if (!url) {
      showError('No target URL available. Please enter the URL in the Override URL field.');
      resetRunState();
      return;
    }

    isRunning = true;
    setStatus('running');
    btnRun.disabled = true;
    btnStop.classList.remove('hidden');
    downloadRow.classList.add('hidden');
    downloadFileInfo.textContent = '';
    unlockSection(sectionLog);
    sectionLog.scrollIntoView({ behavior: 'smooth' });
    clearLog();

    fetch('/api/aggregation/run', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        goalLine:          goalString,
        url:               url,
        paginationPattern: paginationPattern,
      }),
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

  function handleDownload() {
    btnDownloadCsv.disabled = true;
    btnDownloadCsv.classList.add('downloading');
    btnDownloadCsv.innerHTML = '<span class="download-icon">⏳</span> Downloading...';

    fetch('/api/aggregation/download')
      .then(function (r) {
        if (!r.ok) return r.json().then(function (e) { throw new Error(e.error || r.statusText); });
        return r.blob();
      })
      .then(function (blob) {
        const url  = URL.createObjectURL(blob);
        const a    = document.createElement('a');
        a.href     = url;
        const info = downloadFileInfo.textContent || '';
        a.download = info.split('·')[0].trim() || 'accounts.csv';
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        URL.revokeObjectURL(url);
        btnDownloadCsv.innerHTML = '<span class="download-icon">⬇</span> Download CSV';
      })
      .catch(function (err) {
        showError('Download failed: ' + err.message);
        btnDownloadCsv.innerHTML = '<span class="download-icon">⬇</span> Download CSV';
      })
      .finally(function () {
        btnDownloadCsv.disabled = false;
        btnDownloadCsv.classList.remove('downloading');
      });
  }

  function handleStop() {
    fetch('/api/aggregation/stop', { method: 'POST' })
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
    eventSource = new EventSource('/api/aggregation/stream');

    eventSource.onmessage = function (e) {
      let data;
      try { data = JSON.parse(e.data); } catch (_) { return; }

      switch (data.type) {
        case 'log':               appendLog(data.text, data.level);   break;
        case 'status':            setStatus(data.value);              break;
        case 'progress':          updateProgress(data);               break;
        case 'done':              handleDone(data.exitCode);          break;
        case 'error':             showError(data.message);            break;
        case 'aggregation_done':  handleAggregationDone(data);        break;
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
      appendLog('Aggregation completed successfully', 'success');
    } else {
      appendLog('Aggregation finished with errors', 'error');
    }
  }

  function handleAggregationDone(data) {
    // Fetch and render the full preview data
    fetch('/api/aggregation/preview')
      .then(function (r) {
        if (!r.ok) throw new Error('Preview not available (status ' + r.status + ')');
        return r.json();
      })
      .then(function (previewData) {
        renderResults(previewData);
        unlockSection(sectionResults);
        sectionResults.scrollIntoView({ behavior: 'smooth' });
      })
      .catch(function (err) {
        showError('Could not load preview: ' + err.message);
      });
  }

  // ── Results Rendering ──────────────────────────────────────────────────────
  function renderResults(data) {
    const stats = data.stats || {};

    // Hide placeholder
    resultsPlaceholder.classList.add('hidden');

    // Stats bar
    statsTotalRows.textContent   = (stats.totalRows   || 0).toLocaleString();
    statsPagesScraped.textContent = (stats.pagesScraped || 0).toLocaleString();
    const cols = stats.columns || [];
    statsColumns.textContent = cols.length > 0
            ? 'Columns: ' + cols.join(', ')
            : '';
    statsBar.classList.remove('hidden');

    // Download button
    if (data.csvPath) {
      const parts    = data.csvPath.replace(/\\/g, '/').split('/');
      const filename = parts[parts.length - 1];
      const rowCount = (stats.totalRows || 0).toLocaleString();
      downloadFileInfo.textContent = filename + '  ·  ' + rowCount + ' rows';
      downloadRow.classList.remove('hidden');
    }

    // Token usage
    if (stats.inputTokens !== undefined) {
      const total = (stats.inputTokens || 0) + (stats.outputTokens || 0);
      resultsTokenInfo.textContent = 'Tokens: ' + total.toLocaleString()
              + ' | Cost: $' + (stats.costUsd || 0).toFixed(4);
      resultsTokenInfo.classList.remove('hidden');
    }

    // Preview table
    const rows    = data.previewRows || [];
    const headers = cols.length > 0 ? cols
            : (rows.length > 0 ? Object.keys(rows[0]) : []);

    if (rows.length > 0 && headers.length > 0) {
      renderPreviewTable(headers, rows);
      previewTableWrapper.classList.remove('hidden');

      if (rows.length < (stats.totalRows || 0)) {
        previewNote.textContent = 'Showing first ' + rows.length
                + ' of ' + (stats.totalRows || rows.length).toLocaleString() + ' accounts.';
        previewNote.classList.remove('hidden');
      }
    }

    // Usage summary
    if (stats.inputTokens !== undefined) {
      usageSummary.innerHTML = '<span>Input tokens: <strong>'
              + (stats.inputTokens || 0).toLocaleString() + '</strong></span>'
              + '<span>Output tokens: <strong>'
              + (stats.outputTokens || 0).toLocaleString() + '</strong></span>'
              + '<span>Cost: <strong>$' + (stats.costUsd || 0).toFixed(6) + '</strong></span>';
      usageSummary.classList.remove('hidden');
    }
  }

  function renderPreviewTable(headers, rows) {
    // Header row
    const headerRow = document.createElement('tr');
    headers.forEach(function (h) {
      const th = document.createElement('th');
      th.textContent = h;
      headerRow.appendChild(th);
    });
    previewTableHead.innerHTML = '';
    previewTableHead.appendChild(headerRow);

    // Data rows
    previewTableBody.innerHTML = '';
    rows.forEach(function (row) {
      const tr = document.createElement('tr');
      headers.forEach(function (h) {
        const td = document.createElement('td');
        td.textContent = row[h] || '';
        td.title = row[h] || '';
        tr.appendChild(td);
      });
      previewTableBody.appendChild(tr);
    });
  }

  // ── Status ─────────────────────────────────────────────────────────────────
  const STATUS_LABELS = {
    ready:       'Ready',
    generating:  'Generating...',
    aggregating: 'Aggregating...',
    running:     'Running...',
    error:       'Error',
  };

  function setStatus(value) {
    statusDot.className    = 'status-dot status-' + value;
    statusText.textContent = STATUS_LABELS[value] || value;
  }

  // ── Validation Badge ───────────────────────────────────────────────────────
  function showValidationBadge(isValid, issues) {
    validationBadge.className   = 'badge ' + (isValid ? 'badge-success' : 'badge-error');
    validationBadge.textContent = isValid ? '✅ Valid' : '❌ ' + issues.length + ' issue(s)';
    validationBadge.title       = issues.join('\n');
    validationBadge.classList.remove('hidden');
  }

  function showTokenInfo(inputTok, outputTok, cost) {
    const total = (inputTok || 0) + (outputTok || 0);
    tokenInfo.textContent = 'Tokens: ' + total.toLocaleString()
                          + ' | Cost: $' + (cost || 0).toFixed(4);
    tokenInfo.classList.remove('hidden');
  }

  // ── Helpers ────────────────────────────────────────────────────────────────
  function unlockSection(el) {
    el.classList.remove('locked');
    el.scrollIntoView({ behavior: 'smooth' });
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
