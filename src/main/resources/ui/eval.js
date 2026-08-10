(function () {
  'use strict';

  // ── State ──────────────────────────────────────────────────────────────────
  var evalEventSource = null;
  var isEvalRunning   = false;

  // ── Element refs ───────────────────────────────────────────────────────────
  const reportsContainer   = document.getElementById('reportsContainer');
  const btnRefreshReports  = document.getElementById('btnRefreshReports');
  const benchmarkCasesList = document.getElementById('benchmarkCasesList');
  const toastContainer     = document.getElementById('toastContainer');

  // Run section
  const evalCaseSelect     = document.getElementById('evalCaseSelect');
  const skipJudgeToggle    = document.getElementById('skipJudgeToggle');
  const skipJudgeBadge     = document.getElementById('skipJudgeBadge');
  const skipJudgeWarning   = document.getElementById('skipJudgeWarning');
  const btnRunEval         = document.getElementById('btnRunEval');
  const btnRunEvalText     = document.getElementById('btnRunEvalText');
  const btnRunEvalSpinner  = document.getElementById('btnRunEvalSpinner');
  const btnStopEval        = document.getElementById('btnStopEval');
  const evalProgressWrap   = document.getElementById('evalProgressWrap');
  const evalProgressLabel  = document.getElementById('evalProgressLabel');
  const evalProgressPct    = document.getElementById('evalProgressPct');
  const evalProgressBar    = document.getElementById('evalProgressBar');
  const evalLogPanel       = document.getElementById('evalLogPanel');
  const evalStatusDot      = document.getElementById('evalStatusDot');
  const evalStatusText     = document.getElementById('evalStatusText');

  // ── Init ───────────────────────────────────────────────────────────────────
  document.addEventListener('DOMContentLoaded', function () {
    btnRefreshReports.addEventListener('click', loadReports);
    loadReports();
    loadBenchmarkCases();

    btnRunEval.addEventListener('click', handleRunEval);
    btnStopEval.addEventListener('click', handleStopEval);

    if (skipJudgeToggle) {
      skipJudgeToggle.addEventListener('change', function () {
        var on = skipJudgeToggle.checked;
        skipJudgeBadge.textContent = on ? 'ON' : 'OFF';
        skipJudgeBadge.className = 'toggle-badge ' + (on ? 'toggle-badge-on' : 'toggle-badge-off');
        if (skipJudgeWarning) skipJudgeWarning.classList.toggle('hidden', !on);
      });
    }
  });

  // ── Run Eval ───────────────────────────────────────────────────────────────
  function handleRunEval() {
    if (isEvalRunning) return;

    var caseId    = evalCaseSelect ? evalCaseSelect.value : '';
    var skipJudge = skipJudgeToggle ? skipJudgeToggle.checked : false;

    isEvalRunning = true;
    setEvalStatus('running');
    btnRunEval.disabled = true;
    btnRunEvalText.textContent = 'Running...';
    btnRunEvalSpinner.classList.remove('hidden');
    btnStopEval.classList.remove('hidden');

    // Show log panel and progress
    evalLogPanel.style.display = 'block';
    evalLogPanel.innerHTML = '<div class="log-placeholder">Starting eval...</div>';
    evalProgressWrap.classList.remove('hidden');
    setProgress(0, 1, 'Initializing...');

    // Connect SSE before firing the run request
    connectEvalSSE();

    fetch('/api/eval/run', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ caseId: caseId || null, skipJudge: skipJudge })
    })
      .then(function (r) { return r.json(); })
      .then(function (data) {
        if (data.error) {
          appendEvalLog('ERROR: ' + data.error, 'error');
          resetEvalState();
        }
      })
      .catch(function (err) {
        appendEvalLog('Failed to start: ' + err.message, 'error');
        resetEvalState();
      });
  }

  function handleStopEval() {
    fetch('/api/eval/stop', { method: 'POST' })
      .then(function () { resetEvalState(); })
      .catch(function () { resetEvalState(); });
  }

  function resetEvalState() {
    isEvalRunning = false;
    setEvalStatus('ready');
    btnRunEval.disabled = false;
    btnRunEvalText.textContent = '▶ Run Eval';
    btnRunEvalSpinner.classList.add('hidden');
    btnStopEval.classList.add('hidden');
    if (evalEventSource) {
      evalEventSource.close();
      evalEventSource = null;
    }
  }

  // ── SSE ────────────────────────────────────────────────────────────────────
  function connectEvalSSE() {
    if (evalEventSource) {
      evalEventSource.close();
      evalEventSource = null;
    }

    evalEventSource = new EventSource('/api/eval/stream');

    evalEventSource.onmessage = function (e) {
      var data;
      try { data = JSON.parse(e.data); } catch (_) { return; }

      switch (data.type) {
        case 'log':
          appendEvalLog(data.text, data.level);
          break;
        case 'progress':
          setProgress(data.current, data.total, data.label || '');
          break;
        case 'status':
          setEvalStatus(data.value);
          break;
        case 'done':
          handleEvalDone(data.exitCode);
          break;
        case 'ping':
          break;
      }
    };

    evalEventSource.onerror = function () {
      if (evalEventSource && evalEventSource.readyState === EventSource.CLOSED) {
        evalEventSource = null;
      }
    };
  }

  function handleEvalDone(exitCode) {
    resetEvalState();
    if (exitCode === 0) {
      appendEvalLog('Eval completed successfully ✅', 'success');
      showToast('Eval complete — refreshing reports...');
      setTimeout(loadReports, 1000);
    } else {
      appendEvalLog('Eval finished with errors ❌', 'error');
    }
    evalProgressWrap.classList.add('hidden');
  }

  // ── Log & progress helpers ─────────────────────────────────────────────────
  function appendEvalLog(text, level) {
    if (!evalLogPanel) return;
    var placeholder = evalLogPanel.querySelector('.log-placeholder');
    if (placeholder) placeholder.remove();

    var now  = new Date();
    var time = now.toTimeString().substring(0, 8);
    var line = document.createElement('div');
    line.className = 'log-line log-' + (level || 'info');
    line.textContent = '[' + time + '] ' + (text || '');
    evalLogPanel.appendChild(line);
    evalLogPanel.scrollTop = evalLogPanel.scrollHeight;
  }

  function setProgress(current, total, label) {
    if (!evalProgressBar) return;
    var pct = total > 0 ? Math.round((current / total) * 100) : 0;
    evalProgressBar.style.width = pct + '%';
    if (evalProgressLabel) evalProgressLabel.textContent = label || '';
    if (evalProgressPct)   evalProgressPct.textContent   = pct + '%';
  }

  function setEvalStatus(value) {
    if (!evalStatusDot) return;
    evalStatusDot.className = 'status-dot status-' + value;
    if (evalStatusText) {
      evalStatusText.textContent = value === 'running' ? 'Running...' : 'Ready';
    }
  }

  // ── Load benchmark cases dynamically from benchmarks.json ─────────────────
  function loadBenchmarkCases() {
    fetch('/api/eval/cases')
      .then(function (r) { return r.json(); })
      .then(function (data) {
        if (data.error) {
          console.warn('Could not load benchmark cases:', data.error);
          return;
        }
        var cases = data.cases || [];
        renderKnownCases(cases);
        populateCaseDropdown(cases);
      })
      .catch(function (err) {
        console.warn('Failed to fetch benchmark cases:', err.message);
      });
  }

  function renderKnownCases(cases) {
    if (!benchmarkCasesList) return;
    benchmarkCasesList.innerHTML = '';
    if (cases.length === 0) {
      benchmarkCasesList.innerHTML = '<tr><td colspan="4" style="padding:8px 10px; color:var(--text-muted);">No cases found in benchmarks.json</td></tr>';
      return;
    }
    cases.forEach(function (c) {
      var tr = document.createElement('tr');
      tr.innerHTML =
        '<td style="padding:6px 10px; border-bottom:1px solid #f0f0f0;">' + esc(c.id) + '</td>' +
        '<td style="padding:6px 10px; border-bottom:1px solid #f0f0f0;">' + esc(c.description) + '</td>' +
        '<td style="padding:6px 10px; border-bottom:1px solid #f0f0f0;">' +
          '<span style="font-size:11px; padding:2px 7px; border-radius:10px; background:' +
          (c.taskType === 'AGGREGATION' ? '#dbeafe; color:#1e40af' : '#fef3c7; color:#92400e') + ';">' +
          esc(c.taskType) + '</span></td>' +
        '<td style="padding:6px 10px; border-bottom:1px solid #f0f0f0;">' +
          '<span style="font-size:11px; padding:2px 7px; border-radius:10px; background:' +
          (c.mode === 'PLACEHOLDER' ? '#ede9fe; color:#5b21b6' : '#f0fdf4; color:#166534') + ';">' +
          esc(c.mode) + '</span></td>';
      benchmarkCasesList.appendChild(tr);
    });
  }

  function populateCaseDropdown(cases) {
    if (!evalCaseSelect) return;
    var prev = evalCaseSelect.value;
    evalCaseSelect.innerHTML = '<option value="">All cases (' + cases.length + ')</option>';
    cases.forEach(function (c) {
      var opt = document.createElement('option');
      opt.value = c.id;
      var typeShort = c.taskType === 'AGGREGATION' ? 'AGG' : 'PRO';
      var modeShort = c.mode === 'PLACEHOLDER' ? 'PH' : 'LI';
      opt.textContent = c.id + ' — ' + c.description + ' (' + typeShort + ' / ' + modeShort + ')';
      evalCaseSelect.appendChild(opt);
    });
    // Restore previous selection if it still exists
    if (prev) evalCaseSelect.value = prev;
  }

  // ── Load eval reports ──────────────────────────────────────────────────────
  function loadReports() {
    reportsContainer.innerHTML = '<div class="log-placeholder">Loading reports…</div>';

    fetch('/api/eval/reports')
      .then(function (r) { return r.json(); })
      .then(function (data) {
        if (data.error) {
          reportsContainer.innerHTML = '<div class="log-placeholder" style="color:var(--error);">Error: ' + esc(data.error) + '</div>';
          return;
        }
        var reports = data.reports || [];
        if (reports.length === 0) {
          renderNoReports(data.outputDir || './eval-reports');
        } else {
          renderReports(reports);
        }
      })
      .catch(function (err) {
        reportsContainer.innerHTML = '<div class="log-placeholder" style="color:var(--error);">Failed to load reports: ' + esc(err.message) + '</div>';
      });
  }

  function renderNoReports(outputDir) {
    reportsContainer.innerHTML =
      '<div class="eval-no-reports">' +
        '<p>No eval reports found in <code>' + esc(outputDir) + '</code></p>' +
        '<p style="font-size:13px;">Run the benchmark eval from the command line to generate reports:</p>' +
        '<div class="eval-run-box">./gradlew runEval</div>' +
        '<p style="font-size:12px; color: var(--text-muted); margin-top: 8px;">' +
          'Make sure your video files are in <code>./src/main/resources/eval/videos/</code>' +
        '</p>' +
      '</div>';
  }

  function renderReports(reports) {
    reportsContainer.innerHTML = '';
    var list = document.createElement('div');
    list.className = 'eval-reports-list';

    reports.forEach(function (report, idx) {
      var card = buildReportCard(report, idx);
      list.appendChild(card);
    });

    reportsContainer.appendChild(list);
  }

  // ── Build one report card ──────────────────────────────────────────────────
  function buildReportCard(report, idx) {
    var card = document.createElement('div');
    card.className = 'eval-report-card';

    var passRate    = typeof report.passRate === 'number' ? report.passRate : 0;
    var passRatePct = Math.round(passRate * 100);
    var prClass     = passRatePct >= 75 ? 'eval-pr-good' : passRatePct >= 50 ? 'eval-pr-mid' : 'eval-pr-bad';
    var runAt       = report.runAt || report.fileName || 'Unknown time';
    var modelId     = report.modelId || '—';
    var total       = report.totalCases || 0;
    var passed      = report.passed || 0;
    // null when the judge scored nothing, which is not the same as scoring zero.
    var avgJudge    = report.averageScores ? report.averageScores.judgeOverall : null;
    var avgLabel    = typeof avgJudge === 'number' ? 'avg judge ' + avgJudge.toFixed(2) : 'unscored';
    var bodyId      = 'eval-body-' + idx;

    // Header row
    var header = document.createElement('div');
    header.className = 'eval-report-header';
    header.setAttribute('role', 'button');
    header.setAttribute('aria-expanded', 'false');
    header.innerHTML =
      '<div class="eval-report-title">' + esc(report.fileName || runAt) + '</div>' +
      '<div class="eval-report-meta">' +
        '<span>' + passed + '/' + total + ' passed</span>' +
        '<span>' + esc(avgLabel) + '</span>' +
        '<span style="color:var(--text-muted); font-size:11px;">' + esc(modelId.length > 40 ? modelId.slice(-40) : modelId) + '</span>' +
      '</div>' +
      '<span class="eval-pass-rate ' + prClass + '">' + passRatePct + '%</span>' +
      '<span style="font-size:16px; transition:transform 0.2s;" id="chevron-' + idx + '">▶</span>';

    // Body (hidden by default)
    var body = document.createElement('div');
    body.className = 'eval-report-body hidden';
    body.id = bodyId;
    body.appendChild(buildAverageMetrics(report.averageScores || {}));
    body.appendChild(buildCasesTable(report.cases || []));

    // Token summary
    if (report.totalTokenUsage) {
      var tu = report.totalTokenUsage;
      var tokenRow = document.createElement('div');
      tokenRow.className = 'eval-token-summary';
      tokenRow.innerHTML =
        '<span>In: ' + num(tu.inputTokens) + ' tokens</span>' +
        '<span>Out: ' + num(tu.outputTokens) + ' tokens</span>' +
        '<span>Cost: $' + (tu.costUsd || 0).toFixed(4) + '</span>' +
        '<span>Run: ' + esc(runAt) + '</span>';
      body.appendChild(tokenRow);
    }

    header.addEventListener('click', function () {
      var isOpen = !body.classList.contains('hidden');
      body.classList.toggle('hidden', isOpen);
      var chev = document.getElementById('chevron-' + idx);
      if (chev) chev.style.transform = isOpen ? '' : 'rotate(90deg)';
      header.setAttribute('aria-expanded', String(!isOpen));
    });

    card.appendChild(header);
    card.appendChild(body);
    return card;
  }

  function buildAverageMetrics(avg) {
    var container = document.createElement('div');
    container.className = 'eval-metric-avg';

    var metrics = [
      { key: 'correctness',   label: 'Correctness'   },
      { key: 'order',         label: 'Order'         },
      { key: 'hallucination', label: 'Hallucination' },
      { key: 'judgeOverall',  label: 'Judge Overall' },
    ];

    metrics.forEach(function (m) {
      // Rendered as "—" rather than 0.00 when nothing was scored: a zero here would read
      // as a terrible run when the truth is that the judge never returned a verdict.
      var raw  = avg[m.key];
      var text = typeof raw === 'number' ? raw.toFixed(2) : '—';
      var item = document.createElement('div');
      item.className = 'eval-metric-item';
      item.innerHTML =
        '<span class="eval-metric-name">' + esc(m.label) + '</span>' +
        '<span class="eval-metric-value">' + text + '</span>';
      container.appendChild(item);
    });

    return container;
  }

  function buildCasesTable(cases) {
    var wrapper = document.createElement('div');
    wrapper.style.overflowX = 'auto';

    var table = document.createElement('table');
    table.className = 'eval-cases-table';

    var thead = document.createElement('thead');
    thead.innerHTML =
      '<tr>' +
        '<th>ID</th>' +
        '<th>Description</th>' +
        '<th>Type</th>' +
        '<th>Mode</th>' +
        '<th>Judge Overall</th>' +
        '<th>Correctness</th>' +
        '<th>Result</th>' +
        '<th>Issues</th>' +
      '</tr>';

    var tbody = document.createElement('tbody');
    cases.forEach(function (c) {
      var judge    = c.judgeScores || {};
      var passed   = c.passed;
      // Scores are null when the judge never ran, so the bar is drawn empty and labelled
      // rather than shown at 0%, which would look like a scored failure.
      var overall  = typeof judge.overall     === 'number' ? judge.overall     : null;
      var correct  = typeof judge.correctness === 'number' ? judge.correctness : null;

      var fillClass = overall == null ? 'eval-score-fill-bad'
        : overall >= 0.7 ? 'eval-score-fill-good'
        : overall >= 0.5 ? 'eval-score-fill-mid' : 'eval-score-fill-bad';
      var scorePct  = Math.round((overall || 0) * 100) + '%';

      var issueCount = (c.issues || []).length + (judge.issues || []).length;

      var tr = document.createElement('tr');
      tr.innerHTML =
        '<td>' + esc(c.caseId || '') + '</td>' +
        '<td>' + esc((c.description || '').length > 30 ? c.description.slice(0, 30) + '…' : (c.description || '')) + '</td>' +
        '<td><span style="font-size:11px;">' + esc(c.taskType || '') + '</span></td>' +
        '<td><span style="font-size:11px;">' + esc(c.mode || '') + '</span></td>' +
        '<td>' +
          '<div class="eval-score-bar">' +
            '<div class="eval-score-track"><div class="eval-score-fill ' + fillClass + '" style="width:' + scorePct + '"></div></div>' +
            '<span style="font-size:12px; font-weight:600; min-width:38px;">' +
              (overall == null ? '—' : overall.toFixed(2)) + '</span>' +
          '</div>' +
        '</td>' +
        '<td style="font-size:12px; font-weight:600;">' + (correct == null ? '—' : correct.toFixed(2)) + '</td>' +
        '<td class="' + (passed ? 'eval-result-pass' : 'eval-result-fail') + '">' + (passed ? 'PASS' : 'FAIL') + '</td>' +
        '<td style="font-size:12px; color: var(--text-muted);">' + (issueCount > 0 ? issueCount + ' issue(s)' : '—') + '</td>';

      // Expand row for details
      tr.setAttribute('title', buildTooltip(c));
      tbody.appendChild(tr);

      // Expandable detail row
      var detailRow = buildDetailRow(c, table);
      tbody.appendChild(detailRow);

      tr.style.cursor = 'pointer';
      tr.addEventListener('click', function () {
        detailRow.classList.toggle('hidden');
      });
    });

    table.appendChild(thead);
    table.appendChild(tbody);
    wrapper.appendChild(table);
    return wrapper;
  }

  function buildDetailRow(c, table) {
    var judge    = c.judgeScores  || {};
    var verdict  = c.verdict      || {};
    var hsteps   = c.hallucinatedSteps || [];
    var msteps   = c.missingSteps      || [];
    var issues   = c.issues            || [];
    var jIssues  = judge.issues        || [];

    var tr = document.createElement('tr');
    tr.className = 'hidden';

    var td = document.createElement('td');
    td.colSpan = 8;
    td.style.background = '#fafbfc';
    td.style.padding = '12px 16px';

    var html = '';

    // Why the case landed where it did, stated before any numbers. A reviewer opening a
    // failed row wants the reason, and for an unscored case there are no numbers to read.
    if (verdict.failureReason) {
      html += '<div style="margin-bottom:10px; font-size:12px; color:var(--error);"><strong>' +
        esc(verdict.failureReason) + '</strong></div>';
    }
    if (verdict.judgeFailed) {
      html += '<div style="margin-bottom:10px; font-size:12px; color:var(--text-muted);">' +
        '⚠ The judge did not return a verdict, so this case was never scored. That is an ' +
        'infrastructure failure, not a statement about the generated plan.</div>';
    }

    // The judge grading itself. Surfaced prominently because it undermines the scores
    // directly below it — the only signal this eval has left.
    if (jIssues.length > 0) {
      html += '<div style="margin-bottom:10px;"><strong style="font-size:12px; color:#856404;">' +
        '⚠ Judge self-check (' + jIssues.length + '):</strong><ul style="margin:4px 0 0 18px; padding:0;">' +
        jIssues.map(function (s) {
          return '<li style="font-size:12px; color:#856404;">' + esc(s) + '</li>';
        }).join('') + '</ul></div>';
    }

    html += '<div style="display:grid; grid-template-columns: repeat(auto-fill, minmax(130px, 1fr)); gap: 8px; margin-bottom: 12px;">';
    [
      ['Correctness',   judge.correctness,   '0.40'],
      ['Order',         judge.order,         '0.30'],
      ['Hallucination', judge.hallucination, '0.30'],
      ['Overall',       judge.overall,       ''],
    ].forEach(function (item) {
      var val = typeof item[1] === 'number' ? item[1].toFixed(2) : '—';
      html += '<div><span style="font-size:10px;color:var(--text-muted);text-transform:uppercase;">' +
        esc(item[0]) + (item[2] ? ' · w' + item[2] : '') +
        '</span><br><strong>' + val + '</strong></div>';
    });
    html += '</div>';

    if (judge.reasoning) {
      html += '<div style="font-size:12px; color:var(--text-muted); font-style:italic; margin-bottom:8px;">"' + esc(judge.reasoning) + '"</div>';
    }

    // Quoted by the judge from its own comparison, so these are the evidence behind the
    // scores above rather than a separate word-overlap opinion.
    if (hsteps.length > 0) {
      html += '<div style="margin-bottom:6px;"><strong style="font-size:12px;">🔴 Invented, per the judge (' + hsteps.length + '):</strong> ';
      html += hsteps.map(function (s) { return '<span style="font-size:12px; color:#721c24;">' + esc(s) + '</span>'; }).join(' · ');
      html += '</div>';
    }

    if (msteps.length > 0) {
      html += '<div style="margin-bottom:6px;"><strong style="font-size:12px;">🟡 Missing, per the judge (' + msteps.length + '):</strong> ';
      html += msteps.map(function (s) { return '<span style="font-size:12px; color:#856404;">' + esc(s) + '</span>'; }).join(' · ');
      html += '</div>';
    }

    if (issues.length > 0) {
      html += '<div><strong style="font-size:12px;">Issues:</strong> ' +
        issues.map(function (s) { return '<span style="font-size:12px; color:var(--text-muted);">' + esc(s) + '</span>'; }).join(', ') + '</div>';
    }

    td.innerHTML = html;
    tr.appendChild(td);
    return tr;
  }

  function buildTooltip(c) {
    var lines = [];
    var judge = c.judgeScores || {};
    var reason = (c.verdict || {}).failureReason;
    if (reason) lines.push(reason);
    if (typeof judge.correctness === 'number') lines.push('Correctness: ' + judge.correctness.toFixed(2));
    if (typeof judge.overall     === 'number') lines.push('Overall: '     + judge.overall.toFixed(2));
    if ((c.hallucinatedSteps || []).length > 0) lines.push('Invented: ' + c.hallucinatedSteps.join(', '));
    if ((c.missingSteps      || []).length > 0) lines.push('Missing: '  + c.missingSteps.join(', '));
    return lines.join('\n');
  }

  // ── Utilities ──────────────────────────────────────────────────────────────
  function esc(str) {
    if (str == null) return '';
    return String(str)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }

  function num(n) {
    return (n || 0).toLocaleString();
  }

})();
