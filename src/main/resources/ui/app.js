(function () {
  'use strict';

  // ── State ──────────────────────────────────────────────────────────────────
  let uploadedFile    = null;
  let generatedSteps  = [];
  let generatedUrl    = '';
  const HALT_CLAUSE   = 'this completes all steps — do not perform any further actions';
  let eventSource     = null;
  let isGenerating    = false;
  let isRunning       = false;

  // ── Element refs ───────────────────────────────────────────────────────────
  const dropZone            = document.getElementById('dropZone');
  const fileInput           = document.getElementById('fileInput');
  const fileInfo            = document.getElementById('fileInfo');
  const fileName            = document.getElementById('fileName');
  const fileSize            = document.getElementById('fileSize');
  const overrideUrl         = document.getElementById('overrideUrl');
  const maxFramesInput      = document.getElementById('maxFrames');
  const saveDebugFrames     = document.getElementById('saveDebugFrames');
  const btnGenerate         = document.getElementById('btnGenerate');
  const btnGenerateText     = document.getElementById('btnGenerateText');
  const btnGenerateSpinner  = document.getElementById('btnGenerateSpinner');
  const sectionScript       = document.getElementById('sectionScript');
  const stepsList           = document.getElementById('stepsList');
  const btnAddStep          = document.getElementById('btnAddStep');
  const commandBox          = document.getElementById('commandBox');
  const btnCopy             = document.getElementById('btnCopy');
  const validationBadge     = document.getElementById('validationBadge');
  const tokenInfo           = document.getElementById('tokenInfo');
  const btnRun              = document.getElementById('btnRun');
  const sectionLog          = document.getElementById('sectionLog');
  const logPanel            = document.getElementById('logPanel');
  const btnStop             = document.getElementById('btnStop');
  const statusDot           = document.getElementById('statusDot');
  const statusText          = document.getElementById('statusText');
  const toastContainer      = document.getElementById('toastContainer');

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
        populateStepsEditor(data.steps || []);
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

  // ── Steps Editor ───────────────────────────────────────────────────────────
  function populateStepsEditor(steps) {
    // strip trailing halt clause if Claude included it
    const filtered = steps.filter(function (s) {
      return !s.toLowerCase().includes('do not perform any further actions');
    });
    generatedSteps = filtered.slice();
    renderStepsList();
  }

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
        reassembleCommand();
        revalidateClient();
      });

      const rm = document.createElement('button');
      rm.className = 'btn-remove';
      rm.title = 'Remove step';
      rm.textContent = '✕';
      rm.addEventListener('click', function () {
        removeStep(i);
      });

      row.appendChild(num);
      row.appendChild(ta);
      row.appendChild(rm);
      stepsList.appendChild(row);
    });
    reassembleCommand();
  }

  function addStep(text) {
    generatedSteps.push(text);
    renderStepsList();
    const textareas = stepsList.querySelectorAll('.step-input');
    if (textareas.length > 0) textareas[textareas.length - 1].focus();
    reassembleCommand();
  }

  function removeStep(index) {
    generatedSteps.splice(index, 1);
    renderStepsList();
    reassembleCommand();
  }

  function reassembleCommand() {
    const active = generatedSteps.filter(function (s) { return s.trim().length > 0; });
    if (active.length === 0) {
      commandBox.textContent = '';
      btnRun.disabled = true;
      return;
    }
    const allSteps   = active.concat([HALT_CLAUSE]);
    const goalString = allSteps.join(', then ');
    const goalLine   = "./gradlew run --args='--url=" + (generatedUrl || 'https://example.com')
                     + " --goal=" + goalString + "'";
    commandBox.textContent = goalLine;
    btnRun.disabled = isRunning;
  }

  function revalidateClient() {
    const cmd = commandBox.textContent || '';
    const issues = [];
    if (!cmd.startsWith("./gradlew run --args='"))   issues.push("Missing ./gradlew run --args='");
    if (!cmd.includes('--url='))                      issues.push('Missing --url=');
    if (!cmd.includes('--goal='))                     issues.push('Missing --goal=');
    if (!cmd.includes('do not perform any further actions')) issues.push('Missing halt clause');
    if (!/\d+\./.test(cmd) === false)                 issues.push('Numbered steps detected');
    const isValid = issues.length === 0;
    showValidationBadge(isValid, issues);
  }

  // ── Run ────────────────────────────────────────────────────────────────────
  function handleRun() {
    if (isRunning) return;
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

    // Remove placeholder if present
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
    statusDot.className  = 'status-dot status-' + value;
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
    // Toast
    const toast = document.createElement('div');
    toast.className = 'toast';
    toast.textContent = msg;
    toastContainer.appendChild(toast);
    setTimeout(function () { toast.remove(); }, 5000);

    // Also log if log section is visible
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
