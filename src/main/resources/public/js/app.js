/**
 * TaskQueue AI Dashboard - Dynamic Logic System
 * Coordinates all reactive components, charts, visual metrics, thread lanes, and settings.
 */

const $ = (sel) => document.querySelector(sel);
const $$ = (sel) => document.querySelectorAll(sel);

let uiConfig = null;

// Immediately load and apply saved theme on execution to prevent flicker
(function() {
  const savedTheme = localStorage.getItem('tq-theme') || 'obsidian';
  if (savedTheme === 'obsidian') {
    document.documentElement.classList.add('theme-obsidian');
  }
})();
let labels = {};
let messages = {};
let refreshTimer = null;
let allFetchedTasks = [];
let currentSearchText = '';
let currentStatusFilter = 'all';

// Real-time chart data & states
const activityHistory = Array(20).fill(0);
const sysLogLines = [];
const lastLoggedTaskStates = {};
const activityFeedItems = [];

const modal = $('#settings-modal');
const apiKeyInput = $('#api-key-input');
const settingsMessage = $('#settings-message');

function tpl(template, vars) {
  if (!template) return '';
  return template.replace(/\{(\w+)\}/g, (_, key) => (vars[key] != null ? String(vars[key]) : ''));
}

function escapeHtml(s) {
  if (s == null) return '';
  const d = document.createElement('div');
  d.textContent = String(s);
  return d.innerHTML;
}

function showAlert(el, text, ok) {
  if (!el) return;
  el.hidden = false;
  el.textContent = text;
  el.className = 'alert ' + (ok ? 'alert--ok' : 'alert--err');
  setTimeout(() => { el.hidden = true; }, 5000);
}

function showSettingsMsg(text, ok) {
  if (!settingsMessage) return;
  settingsMessage.hidden = false;
  settingsMessage.textContent = text;
  settingsMessage.className = 'settings-message ' + (ok ? 'ok' : 'err');
}

async function api(path, options = {}) {
  const res = await fetch(path, {
    headers: { 'Content-Type': 'application/json', ...options.headers },
    ...options,
  });
  const data = await res.json().catch(() => ({}));
  return { ok: res.ok, status: res.status, data };
}

function formatTypeLabel(type) {
  const min = type.durationMinSeconds;
  const max = type.durationMaxSeconds;
  const dur = min === max ? `${min}s` : `${min}–${max}s`;
  return `${type.label} (${dur})`;
}

function formatStatValue(def, stats) {
  switch (def.format) {
    case 'threads':
      return `${stats.activeThreads} / ${stats.threadPoolSize}`;
    case 'queue':
      return `${stats.queueSize} / ${stats.maxQueueCapacity}`;
    case 'failedCancelled':
      return `${stats.failedTasks} / ${stats.cancelledTasks}`;
    case 'ai':
      return `${stats.aiDecisionsMade} (${stats.aiUrgentOverrides} overrides)`;
    case 'number':
      return String(stats[def.field] ?? '');
    default:
      return stats[def.field] != null ? String(stats[def.field]) : '';
  }
}

// -------------------------------------------------------------
// UI LAYOUT & PAGE NAVIGATION
// -------------------------------------------------------------

function setPage(pageName, el) {
  // Hide all pages
  $$('.page').forEach(p => p.classList.remove('active'));
  
  // Show target page
  const targetPage = $(`#page-${pageName}`);
  if (targetPage) targetPage.classList.add('active');

  // Deactivate all sidebar items and topbar tabs
  $$('.nav-item').forEach(i => i.classList.remove('active'));
  $$('.topbar-tab').forEach(t => t.classList.remove('active'));

  // Activate matching sidebar item
  const sidebarItem = Array.from($$('.nav-section .nav-item')).find(item => {
    const attr = item.getAttribute('onclick');
    return attr && attr.includes(`'${pageName}'`);
  });
  if (sidebarItem) {
    sidebarItem.classList.add('active');
  } else if (el && el.classList.contains('nav-item')) {
    el.classList.add('active');
  }

  // Activate matching topbar tab
  const topbarTab = $(`#tab-${pageName}`);
  if (topbarTab) {
    topbarTab.classList.add('active');
  }
}

// -------------------------------------------------------------
// DRAWER (NEW TASK PANEL) CONTROLS
// -------------------------------------------------------------

function openPanel() {
  const panel = $('#slide-panel');
  const overlay = $('#panel-overlay');
  if (panel) panel.classList.add('open');
  if (overlay) overlay.classList.add('open');
}

function closePanel() {
  const panel = $('#slide-panel');
  const overlay = $('#panel-overlay');
  if (panel) panel.classList.remove('open');
  if (overlay) overlay.classList.remove('open');
}

// -------------------------------------------------------------
// TASK SUBMISSION & API KEY CONTROLS
// -------------------------------------------------------------

function readTaskForm() {
  return {
    name: $('#task-name').value.trim(),
    type: $('#task-type').value,
    payload: $('#task-payload').value.trim(),
    priority: parseInt($('#task-priority').value, 10),
    deadlineSeconds: parseInt($('#task-deadline').value, 10),
  };
}

function validateTaskForm(fields) {
  if (!fields.name || !fields.type || !fields.payload) {
    return messages.submitRequiresFields || "Please fill in all required fields.";
  }
  const p = uiConfig?.priority;
  if (p && (fields.priority < p.min || fields.priority > p.max)) {
    return tpl(messages.priorityRange || "Priority must be between {min} and {max}.", { min: p.min, max: p.max });
  }
  const d = uiConfig?.deadline;
  if (d && fields.deadlineSeconds < d.minSeconds) {
    return tpl(messages.deadlineMin || "Deadline must be at least {minSeconds}s.", { minSeconds: d.minSeconds });
  }
  return null;
}

async function submitTaskForm(event) {
  event.preventDefault();
  const alertEl = $('#form-alert');
  const fields = readTaskForm();
  const err = validateTaskForm(fields);
  if (err) {
    showAlert(alertEl, err, false);
    return;
  }

  const { ok, data } = await api('/api/tasks', {
    method: 'POST',
    body: JSON.stringify(fields),
  });
  
  if (ok) {
    showAlert(alertEl, tpl(messages.submitSuccess || "Task submitted successfully with priority {priority}!", { priority: data.priority, status: data.status }), true);
    
    // Reset form fields
    $('#task-name').value = '';
    $('#task-payload').value = '';
    if (uiConfig?.priority) {
      $('#task-priority').value = uiConfig.priority.default;
      const prioVal = $('#val-task-priority');
      if (prioVal) prioVal.textContent = uiConfig.priority.default;
    }
    if (uiConfig?.deadline) {
      $('#task-deadline').value = uiConfig.deadline.defaultSeconds;
      const deadVal = $('#val-task-deadline');
      if (deadVal) deadVal.textContent = uiConfig.deadline.defaultSeconds + 's';
    }
    
    setTimeout(() => {
      closePanel();
      refresh();
    }, 1000);
  } else {
    showAlert(alertEl, data.error || "Failed to submit task.", false);
  }
}

// Settings API Key actions
async function saveKey() {
  const key = apiKeyInput.value.trim();
  if (!key) {
    showSettingsMsg(messages.apiKeyRequired || "API key cannot be empty.", false);
    return;
  }
  const { ok, data } = await api('/api/config/api-key', {
    method: 'POST',
    body: JSON.stringify({ api_key: key }),
  });
  if (ok) {
    apiKeyInput.value = '';
    showSettingsMsg(data.message, true);
    await loadApiKeyStatus();
    await loadUiConfig();
  } else {
    showSettingsMsg(data.error || data.message, false);
  }
}

async function validateKey() {
  const key = apiKeyInput.value.trim();
  const body = key ? { api_key: key } : {};
  const { ok, data } = await api('/api/config/api-key/validate', {
    method: 'POST',
    body: JSON.stringify(body),
  });
  if (ok && data.valid) {
    showSettingsMsg(data.message, true);
  } else {
    showSettingsMsg(data.message || data.error, false);
  }
}

async function clearKey() {
  const { ok, data } = await api('/api/config/api-key', { method: 'DELETE' });
  showSettingsMsg(data.message || data.error, ok);
  await loadApiKeyStatus();
  await loadUiConfig();
}

// -------------------------------------------------------------
// TASK TABLE FILTERING & SEARCH CONTROLS
// -------------------------------------------------------------

function handleQuickSearch(value) {
  currentSearchText = value.trim().toLowerCase();
  renderTasksFiltered();
}

function setFilterStatus(status, el) {
  currentStatusFilter = status.toLowerCase();
  
  const tabs = $$('#filter-tabs-container .filter-tab');
  tabs.forEach(tab => tab.classList.remove('active'));
  if (el) el.classList.add('active');
  
  renderTasksFiltered();
}

function renderTasksFiltered() {
  let filtered = allFetchedTasks;
  
  // Apply Status Filter
  if (currentStatusFilter !== 'all') {
    filtered = filtered.filter(t => t.status.toLowerCase() === currentStatusFilter);
  }
  
  // Apply Search Query
  if (currentSearchText) {
    filtered = filtered.filter(t => 
      t.name.toLowerCase().includes(currentSearchText) ||
      t.payload.toLowerCase().includes(currentSearchText) ||
      t.type.toLowerCase().includes(currentSearchText) ||
      t.status.toLowerCase().includes(currentSearchText) ||
      t.taskId.toLowerCase().includes(currentSearchText)
    );
  }
  
  drawTasksTable(filtered);
}

// -------------------------------------------------------------
// DYNAMIC COMPONENT DRAWING FUNCTIONS
// -------------------------------------------------------------

function drawDonutChart(tasksByType, totalTasks) {
  const svg = $('#donut-svg');
  if (!svg) return;
  
  // Clear dynamic segments
  svg.querySelectorAll('.dynamic-segment').forEach(el => el.remove());
  
  const totalEl = $('#donut-total');
  if (totalEl) totalEl.textContent = totalTasks;
  
  const isObsidian = document.documentElement.classList.contains('theme-obsidian');
  const colors = {
    'PAYMENT': '#f59e0b',
    'EMAIL': '#10b981',
    'REPORT': '#8b5cf6',
    'NOTIFICATION': '#06b6d4',
    'DATA_SYNC': isObsidian ? '#7c3aed' : '#3b82f6'
  };
  
  const legend = $('#donut-legend');
  if (legend) legend.innerHTML = '';
  
  if (totalTasks === 0) {
    if (legend) {
      legend.innerHTML = '<div style="color:var(--text3);font-size:11px;text-align:center;padding-top:10px">No tasks in repository</div>';
    }
    return;
  }
  
  const radius = 35;
  const circumference = 2 * Math.PI * radius; // ~219.91
  let accumulatedPercent = 0;
  
  Object.keys(tasksByType).forEach((type) => {
    const count = tasksByType[type];
    const pct = count / totalTasks;
    const color = colors[type] || '#64748b';
    
    // Draw Segment
    const circle = document.createElementNS('http://www.w3.org/2000/svg', 'circle');
    circle.setAttribute('class', 'dynamic-segment');
    circle.setAttribute('cx', '45');
    circle.setAttribute('cy', '45');
    circle.setAttribute('r', String(radius));
    circle.setAttribute('fill', 'none');
    circle.setAttribute('stroke', color);
    circle.setAttribute('stroke-width', '10');
    
    const strokeDash = pct * circumference;
    circle.setAttribute('stroke-dasharray', `${strokeDash} ${circumference}`);
    
    const dashOffset = circumference - (accumulatedPercent * circumference);
    circle.setAttribute('stroke-dashoffset', String(dashOffset));
    circle.setAttribute('transform', 'rotate(-90 45 45)');
    
    svg.appendChild(circle);
    accumulatedPercent += pct;
    
    // Legend
    if (legend) {
      const item = document.createElement('div');
      item.className = 'legend-item';
      item.style.display = 'flex';
      item.style.alignItems = 'center';
      item.style.gap = '6px';
      item.style.fontSize = '11px';
      item.style.color = 'var(--text2)';
      item.innerHTML = `
        <span style="display:inline-block;width:8px;height:8px;border-radius:50%;background:${color}"></span>
        <span style="font-weight:600;text-transform:uppercase">${type}</span>
        <span style="color:var(--text3)">(${count})</span>
      `;
      legend.appendChild(item);
    }
  });
}

function updateActivityChart(currentQueueSize) {
  activityHistory.shift();
  activityHistory.push(currentQueueSize);
  
  const pathEl = $('#chart-path');
  const pathBgEl = $('#chart-path-bg');
  if (!pathEl || !pathBgEl) return;
  
  const width = 600;
  const height = 130;
  const padding = 10;
  
  const maxVal = Math.max(...activityHistory, 5);
  const step = width / (activityHistory.length - 1);
  
  let d = '';
  activityHistory.forEach((val, idx) => {
    const x = idx * step;
    const y = height - padding - ((val / maxVal) * (height - 2 * padding));
    
    if (idx === 0) {
      d += `M ${x} ${y}`;
    } else {
      d += ` L ${x} ${y}`;
    }
  });
  
  pathEl.setAttribute('d', d);
  pathBgEl.setAttribute('d', `${d} L ${width} ${height} L 0 ${height} Z`);
}

function drawThreadsGrid(workers) {
  const grid = $('#threads-grid');
  if (!grid) return;
  
  if (!workers || workers.length === 0) {
    grid.innerHTML = '<p class="empty" style="color:var(--text3)">No active worker threads.</p>';
    return;
  }
  
  grid.innerHTML = workers.map(w => {
    const isBusy = w.isWorking;
    const cardClass = isBusy ? 'thread-card busy' : 'thread-card idle';
    const badgeClass = isBusy ? 'thread-status-badge busy' : 'thread-status-badge idle';
    const statusText = isBusy ? 'BUSY' : 'IDLE';
    const taskName = isBusy ? w.taskName : 'No active task';
    const priority = isBusy ? `Priority: ${w.taskPriority}` : '';
    
    let elapsedText = '';
    let fillWidth = '0%';
    if (isBusy && w.startedAt) {
      const elapsedSec = Math.round((Date.now() - w.startedAt) / 1000);
      elapsedText = `${elapsedSec}s elapsed`;
      const pct = Math.min(100, Math.round((elapsedSec / 4) * 100));
      fillWidth = `${pct}%`;
    }
    
    return `
      <div class="${cardClass}">
        <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:6px">
          <span class="thread-id">${escapeHtml(w.name)}</span>
          <span class="${badgeClass}">${statusText}</span>
        </div>
        <div class="thread-task">${escapeHtml(taskName)}</div>
        <div class="thread-progress-bar">
          <div class="thread-progress-fill" style="width:${fillWidth}"></div>
        </div>
        <div class="thread-meta">
          <span>${priority}</span>
          <span>${elapsedText}</span>
        </div>
      </div>
    `;
  }).join('');
}

function drawThreadPoolLanes(workers) {
  const lanes = $('#thread-pool-lanes');
  if (!lanes) return;
  
  if (!workers || workers.length === 0) {
    lanes.innerHTML = '<p class="empty">No threads active.</p>';
    return;
  }
  
  lanes.innerHTML = workers.map(w => {
    const isBusy = w.isWorking;
    const statusClass = isBusy ? 'thread-lane-status busy' : 'thread-lane-status idle';
    const statusText = isBusy ? 'BUSY' : 'IDLE';
    let threadTypeClass = '';
    if (w.name.includes('-1') || w.name.includes('-4')) {
      threadTypeClass = 'thread-compute';
    } else if (w.name.includes('-2')) {
      threadTypeClass = 'thread-io-wait';
    } else if (w.name.includes('-3')) {
      threadTypeClass = 'thread-analytics';
    }

    const barClass = isBusy ? `thread-lane-bar active ${threadTypeClass}` : 'thread-lane-bar idle-bar';
    const taskText = isBusy ? `${w.taskName} (${w.taskType})` : 'Idle';
    
    let elapsedPct = '';
    if (isBusy && w.startedAt) {
      const elapsedSec = Math.round((Date.now() - w.startedAt) / 1000);
      const pct = Math.min(100, Math.round((elapsedSec / 4) * 100));
      elapsedPct = `<span class="thread-pct">${pct}%</span>`;
    }
    
    return `
      <div class="thread-lane">
        <div class="thread-lane-header">
          <span class="thread-lane-id">${escapeHtml(w.name)}</span>
          <span class="${statusClass}">${statusText}</span>
        </div>
        <div class="${barClass}">
          <span>${escapeHtml(taskText)}</span>
          ${elapsedPct}
        </div>
      </div>
    `;
  }).join('');
}

function drawQueueList(pendingTasks) {
  const container = $('#queue-list-container');
  if (!container) return;
  
  const badge = $('#queue-depth-badge');
  if (badge) badge.textContent = `${pendingTasks.length} depth`;
  
  if (pendingTasks.length === 0) {
    container.innerHTML = '<p class="empty" style="color:var(--text3);padding:20px;text-align:center;">No pending tasks in queue.</p>';
    return;
  }
  
  pendingTasks.sort((a, b) => b.priority - a.priority);
  
  container.innerHTML = pendingTasks.map((t, idx) => {
    return `
      <div class="queue-item">
        <span class="queue-item-badge" style="color:var(--amber2)">#${idx + 1}</span>
        <div class="queue-item-info">
          <div class="queue-item-name">${escapeHtml(t.name)}</div>
          <div class="queue-item-sub">Type: ${escapeHtml(t.type)} | Priority: ${t.priority}</div>
        </div>
        <span class="queue-item-id">${t.taskId.substring(0, 8)}</span>
      </div>
    `;
  }).join('');
}

function canCancel(status) {
  return ['PENDING', 'RUNNING'].includes(status.toUpperCase());
}

async function cancelTask(id) {
  if (!confirm(messages.cancelConfirm || "Are you sure you want to cancel this active task?")) return;
  await api(`/api/tasks/${id}`, { method: 'DELETE' });
  refresh();
}

async function reprioritizeTask(id) {
  const { ok, data } = await api('/api/tasks/prioritize', {
    method: 'POST',
    body: JSON.stringify({ taskId: id })
  });
  if (ok) {
    alert(`AI Dynamic Prioritization successfully executed! New Priority: ${data.newPriority}`);
    refresh();
  } else {
    alert(`Priority optimization failed: ${data.error || "API Connection error."}`);
  }
}

function drawTasksTable(tasks) {
  const tbody = $('#tasks-table-body');
  if (!tbody) return;
  
  if (tasks.length === 0) {
    tbody.innerHTML = '<tr><td colspan="8" class="empty" style="text-align:center;padding:24px;color:var(--text3);">No matching tasks found.</td></tr>';
    return;
  }
  
  tbody.innerHTML = tasks.map(t => {
    const isCancellable = canCancel(t.status);
    const cancelBtn = isCancellable
      ? `<button class="action-btn" title="Cancel Task" onclick="cancelTask('${t.taskId}')">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
         </button>`
      : '';
      
    const reprioBtn = t.status === 'PENDING'
      ? `<button class="action-btn" title="Run AI Smart Prioritizer" onclick="reprioritizeTask('${t.taskId}')" style="color:var(--purple2)">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 2l3.09 6.26L22 9.27l-5 4.87 1.18 6.88L12 17.77l-6.18 3.25L7 14.14 2 9.27l6.91-1.01L12 2z"/></svg>
         </button>`
      : '';
      
    const statusText = t.status;
    const statusClass = t.status.toLowerCase();
    
    const typeClass = t.type.toLowerCase();
    const typeLabel = t.type;
    
    const priorityPct = Math.round((t.priority / 10) * 100);
    let prioClass = 'prio-low';
    if (t.priority >= 8) prioClass = 'prio-high';
    else if (t.priority >= 5) prioClass = 'prio-med';
    
    const createdStr = t.createdAt ? new Date(t.createdAt).toLocaleTimeString() : '-';
    const completedStr = t.completedAt ? new Date(t.completedAt).toLocaleTimeString() : '-';
    
    const retries = t.maxRetries ?? uiConfig?.maxRetries ?? 3;
    
    return `
      <tr>
        <td class="task-id" title="${t.taskId}">${t.taskId.substring(0, 8)}</td>
        <td>
          <div class="task-name">${escapeHtml(t.name)}</div>
          <div style="font-size:11px;color:var(--text3);max-width:200px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;" title="${escapeHtml(t.payload)}">
            ${escapeHtml(t.payload)}
          </div>
        </td>
        <td><span class="type-badge ${typeClass}">${typeLabel}</span></td>
        <td>
          <div class="priority-bar">
            <div class="priority-track"><div class="priority-fill ${prioClass}" style="width:${priorityPct}%"></div></div>
            <span class="priority-num">${t.priority}</span>
          </div>
        </td>
        <td>
          <span class="status-badge ${statusClass}">
            <span class="status-dot ${statusClass}"></span>
            ${statusText}
          </span>
          ${t.failureReason ? `<div style="font-size:10px;color:var(--red2);margin-top:2px;max-width:180px;line-height:1.2">${escapeHtml(t.failureReason)}</div>` : ''}
        </td>
        <td><span class="mono">${t.retryCount} / ${retries}</span></td>
        <td>
          <div class="created-time">In: ${createdStr}</div>
          <div class="created-time" style="color:var(--text3)">Out: ${completedStr}</div>
        </td>
        <td>
          <div class="action-btns">
            ${reprioBtn}
            ${cancelBtn}
          </div>
        </td>
      </tr>
    `;
  }).join('');
}

function updateTasksStatsRow(tasks) {
  const row = $('#tasks-stats-row');
  if (!row) return;
  
  const pending = tasks.filter(t => t.status === 'PENDING').length;
  const running = tasks.filter(t => t.status === 'RUNNING').length;
  const completed = tasks.filter(t => t.status === 'COMPLETED').length;
  const failed = tasks.filter(t => t.status === 'FAILED').length;
  
  row.innerHTML = `
    <div class="tasks-stat">
      <div class="tasks-stat-icon" style="background:var(--blue-dim);color:var(--blue2)">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><path d="M12 6v6l4 2"/></svg>
      </div>
      <div>
        <div class="tasks-stat-label">Pending</div>
        <div class="tasks-stat-value" style="color:var(--blue2)">${pending}</div>
      </div>
    </div>
    <div class="tasks-stat">
      <div class="tasks-stat-icon" style="background:var(--teal-dim);color:var(--teal2)">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="22 12 18 12 15 21 9 3 6 12 2 12"/></svg>
      </div>
      <div>
        <div class="tasks-stat-label">Running</div>
        <div class="tasks-stat-value" style="color:var(--teal2)">${running}</div>
      </div>
    </div>
    <div class="tasks-stat">
      <div class="tasks-stat-icon" style="background:var(--green-dim);color:var(--green2)">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M22 11.08V12a10 10 0 11-5.93-9.14"/><polyline points="22 4 12 14.01 9 11.01"/></svg>
      </div>
      <div>
        <div class="tasks-stat-label">Completed</div>
        <div class="tasks-stat-value" style="color:var(--green2)">${completed}</div>
      </div>
    </div>
    <div class="tasks-stat">
      <div class="tasks-stat-icon" style="background:var(--red-dim);color:var(--red2)">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><line x1="15" y1="9" x2="9" y2="15"/><line x1="9" y1="9" x2="15" y2="15"/></svg>
      </div>
      <div>
        <div class="tasks-stat-label">Failed</div>
        <div class="tasks-stat-value" style="color:var(--red2)">${failed}</div>
      </div>
    </div>
  `;
}

function updateSystemLogs(tasks) {
  const logEl = $('#sys-log');
  if (!logEl) return;
  
  let newLogsAdded = false;
  
  tasks.forEach(t => {
    const prevState = lastLoggedTaskStates[t.taskId];
    if (prevState !== t.status) {
      lastLoggedTaskStates[t.taskId] = t.status;
      
      const timeStr = new Date().toLocaleTimeString();
      let msg = '';
      let msgClass = '';
      
      if (prevState === undefined) {
        msg = `[Task Service] Enqueued task '${t.name}' (Type: ${t.type}, Base Priority: ${t.priority})`;
        msgClass = 'info';
        newLogsAdded = true;
      } else if (t.status === 'RUNNING') {
        msg = `[ThreadPool] Worker thread active on task '${t.name}'. Allocated.`;
        msgClass = '';
        newLogsAdded = true;
      } else if (t.status === 'COMPLETED') {
        msg = `[Task Executor] Successfully completed task: '${t.name}'`;
        msgClass = 'success';
        newLogsAdded = true;
      } else if (t.status === 'FAILED') {
        msg = `[Task Executor] Task '${t.name}' failed: ${t.failureReason || 'Simulated connection delay.'}`;
        msgClass = 'err';
        newLogsAdded = true;
      } else if (t.status === 'CANCELLED') {
        msg = `[Task Service] Task '${t.name}' successfully cancelled and removed from active pool.`;
        msgClass = 'warn';
        newLogsAdded = true;
      }
      
      if (newLogsAdded) {
        sysLogLines.push({ time: timeStr, text: msg, cls: msgClass });
      }
    }
  });
  
  if (sysLogLines.length > 50) {
    sysLogLines.splice(0, sysLogLines.length - 50);
  }
  
  if (sysLogLines.length === 0) {
    logEl.innerHTML = '<div class="log-line"><span class="log-msg info">[System] Queue service up and listening. Ready for task enqueuing.</span></div>';
    return;
  }
  
  logEl.innerHTML = sysLogLines.map(l => `
    <div class="log-line">
      <span class="log-time">[${l.time}]</span>
      <span class="log-msg ${l.cls}">${escapeHtml(l.text)}</span>
    </div>
  `).join('');
  
  logEl.scrollTop = logEl.scrollHeight;
}

function updateActivityFeed(tasks) {
  const feedEl = $('#activity-feed-list');
  if (!feedEl) return;
  
  tasks.forEach(t => {
    const key = `${t.taskId}-${t.status}`;
    if (!updateActivityFeed.loggedKeys) updateActivityFeed.loggedKeys = new Set();
    
    if (!updateActivityFeed.loggedKeys.has(key)) {
      updateActivityFeed.loggedKeys.add(key);
      
      let title = '';
      let meta = '';
      let iconColor = '';
      let iconSvg = '';
      const timeStr = new Date().toLocaleTimeString();
      
      if (t.status === 'COMPLETED') {
        title = `Task Completed`;
        meta = `${t.name} (Type: ${t.type})`;
        iconColor = 'success';
        iconSvg = `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3"><polyline points="20 6 9 17 4 12"/></svg>`;
      } else if (t.status === 'FAILED') {
        title = `Task Failed`;
        meta = `${t.name} (Retry: ${t.retryCount})`;
        iconColor = 'error';
        iconSvg = `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>`;
      } else if (t.status === 'PENDING' && !updateActivityFeed.loggedKeys.has(`${t.taskId}-RUNNING`)) {
        title = `Task Enqueued`;
        meta = `${t.name} (Priority: ${t.priority})`;
        iconColor = 'info';
        iconSvg = `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>`;
      }
      
      if (title) {
        activityFeedItems.unshift({ title, meta, iconColor, iconSvg, time: timeStr });
      }
    }
  });
  
  if (activityFeedItems.length > 20) {
    activityFeedItems.pop();
  }
  
  if (activityFeedItems.length === 0) {
    feedEl.innerHTML = '<p class="empty" style="color:var(--text3);text-align:center;padding:24px;font-size:12px;">Waiting for system workloads...</p>';
    return;
  }
  
  feedEl.innerHTML = activityFeedItems.map(item => `
    <div class="activity-item">
      <div class="activity-dot ${item.iconColor}">
        ${item.iconSvg}
      </div>
      <div class="activity-text">
        <div class="activity-title">${escapeHtml(item.title)}</div>
        <div style="color:var(--text2);margin-bottom:2px">${escapeHtml(item.meta)}</div>
        <div class="activity-meta">${item.time}</div>
      </div>
    </div>
  `).join('');
}

function updateNodeHealth(stats) {
  const availPctEl = $('#queue-avail-pct');
  const availBarEl = $('#queue-avail-bar');
  const utilPctEl = $('#thread-util-pct');
  const utilBarEl = $('#thread-util-bar');
  
  if (stats.maxQueueCapacity > 0) {
    const avail = stats.maxQueueCapacity - stats.queueSize;
    const availPct = Math.round((avail / stats.maxQueueCapacity) * 100);
    if (availPctEl) availPctEl.textContent = `${availPct}%`;
    if (availBarEl) availBarEl.style.width = `${availPct}%`;
  }
  
  if (stats.threadPoolSize > 0) {
    const utilPct = Math.round((stats.activeThreads / stats.threadPoolSize) * 100);
    if (utilPctEl) utilPctEl.textContent = `${utilPct}%`;
    if (utilBarEl) utilBarEl.style.width = `${utilPct}%`;
  }
}

function updateCpuPill(stats) {
  const cpuPill = $('#cpu-pill');
  if (!cpuPill) return;
  
  let baseCpu = 0;
  if (stats.threadPoolSize > 0) {
    baseCpu = (stats.activeThreads / stats.threadPoolSize) * 75;
  }
  const fluctuation = Math.floor(Math.random() * 8) + 1;
  const finalCpu = Math.min(100, Math.round(baseCpu + fluctuation));
  cpuPill.textContent = `CPU ${finalCpu}%`;
}

function renderAiLogs(logs) {
  const el = $('#ai-logs');
  if (!el) return;
  
  if (!logs || logs.length === 0) {
    el.innerHTML = `<p class="empty" style="color:var(--text3);text-align:center;padding:24px;grid-column:1/-1;">No AI decision logs found.</p>`;
    return;
  }

  el.innerHTML = [...logs].reverse().map((l) => {
    const recommendation = l.recommendation || 'NORMAL';
    const recClass = 'rec-' + recommendation.toLowerCase();
    
    let waitLine = '';
    const status = l.taskStatus ? l.taskStatus.toUpperCase() : 'PENDING';
    
    if (status === 'PENDING') {
      const pos = l.queuePosition ?? 1;
      const wait = l.estimatedWaitTime ?? 0;
      waitLine = tpl(messages.aiLogWait || "Est. Wait: {wait}s | Position: #{position}", { wait: wait, position: pos });
    } else if (status === 'RUNNING') {
      waitLine = `<span style="color:var(--teal2)">⚡ Currently executing in thread pool</span>`;
    } else if (status === 'COMPLETED') {
      waitLine = `<span style="color:var(--green2)">✓ Successfully completed</span>`;
    } else if (status === 'FAILED') {
      waitLine = `<span style="color:var(--red2)">✗ Execution failed</span>`;
    } else if (status === 'CANCELLED') {
      waitLine = `<span style="color:var(--text3)">⚙ Task cancelled by user</span>`;
    } else if (status === 'PREVIEW') {
      waitLine = `<span style="color:var(--purple2)">🔍 Hypothetical task preview</span>`;
    } else {
      waitLine = l.estimatedWaitTime != null && l.estimatedWaitTime >= 0
        ? tpl(messages.aiLogWait || "Est. Wait: {wait}s | Position: {position}", { wait: l.estimatedWaitTime, position: l.queuePosition ?? '' })
        : '';
    }

    return `
      <div class="ai-log-card">
        <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:8px">
          <span class="rec-badge ${recClass}">
            <span class="rec-dot"></span>
            ${escapeHtml(recommendation)}
          </span>
          <span style="color:var(--text3);font-size:10px">${new Date(l.timestamp).toLocaleTimeString()}</span>
        </div>
        <div style="font-weight:600;font-size:13px;margin-bottom:4px">${escapeHtml(l.taskName || 'Task')}</div>
        <div style="font-family:var(--mono);font-size:11px;margin-bottom:6px">
          Priority: ${l.originalPriority} &rarr; <b style="color:var(--purple2)">${l.suggestedPriority}</b>
        </div>
        <div style="color:var(--text2);line-height:1.4">${escapeHtml(l.reasoning)}</div>
        ${l.warnings?.length ? `<div style="color:var(--amber2);margin-top:6px;font-size:11px">⚠ ${l.warnings.map(escapeHtml).join(', ')}</div>` : ''}
        ${waitLine ? `<div style="font-size:10px;margin-top:6px">${waitLine}</div>` : ''}
      </div>
    `;
  }).join('');
}

async function exportLogs() {
  const { ok, data } = await api('/api/tasks/ai-insights');
  if (!ok) {
    alert("Failed to export logs.");
    return;
  }
  const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = `ai-decision-logs-${Date.now()}.json`;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
}

// -------------------------------------------------------------
// AI ANALYZER PREVIEW CONTROLS
// -------------------------------------------------------------

async function runAiPreview() {
  const resultCard = $('#ai-preview-result-card');
  const codeBlock = $('#ai-preview-code-block');
  
  const fields = {
    name: $('#preview-name').value.trim(),
    type: $('#preview-type').value,
    payload: $('#preview-payload').value.trim(),
    priority: parseInt($('#preview-priority').value, 10),
    deadlineSeconds: parseInt($('#preview-deadline').value, 10),
  };
  
  if (!fields.name || !fields.payload) {
    alert("Please fill in the task name and payload parameters.");
    return;
  }
  
  const btn = $('#btn-preview-ai');
  const originalText = btn.innerHTML;
  btn.disabled = true;
  btn.textContent = "Analyzing payload...";
  
  const { ok, data } = await api('/api/tasks/prioritize', {
    method: 'POST',
    body: JSON.stringify(fields),
  });
  
  btn.disabled = false;
  btn.innerHTML = originalText;
  
  if (ok) {
    if (resultCard) resultCard.hidden = false;
    if (codeBlock) {
      codeBlock.textContent = JSON.stringify(data.aiDecision || data, null, 2);
    }
    refresh();
  } else {
    if (resultCard) resultCard.hidden = false;
    if (codeBlock) {
      codeBlock.textContent = `Error: ${data.error || JSON.stringify(data)}`;
    }
  }
}

// -------------------------------------------------------------
// CORE SYSTEM INITIALIZATION & LOOPS
// -------------------------------------------------------------

function applyUiConfig(config) {
  uiConfig = config;
  labels = config.labels || {};
  messages = config.messages || {};

  document.title = labels.appTitle || document.title;
  const brandIcon = $('.brand-icon');
  if (brandIcon && labels.brandIcon) brandIcon.textContent = labels.brandIcon;
  
  const subtitle = $('#app-subtitle');
  if (subtitle && labels.appSubtitle) {
    subtitle.textContent = tpl(labels.appSubtitle, {
      port: config.server?.port,
      poolSize: config.threadPool?.threadPoolSize,
    });
  }

  // Populate dynamic form elements
  const priority = config.priority;
  const prioEl = $('#task-priority');
  if (prioEl && priority) {
    prioEl.min = priority.min;
    prioEl.max = priority.max;
    prioEl.value = priority.default;
    const prioVal = $('#val-task-priority');
    if (prioVal) prioVal.textContent = priority.default;
  }

  const deadline = config.deadline;
  const deadEl = $('#task-deadline');
  if (deadEl && deadline) {
    deadEl.min = deadline.minSeconds;
    deadEl.value = deadline.defaultSeconds;
    const deadVal = $('#val-task-deadline');
    if (deadVal) deadVal.textContent = deadline.defaultSeconds + 's';
  }

  // Populate Task Type dropdowns
  const typeSelect = $('#task-type');
  const previewTypeSelect = $('#preview-type');
  
  if (typeSelect) {
    typeSelect.innerHTML = '';
    (config.taskTypes || []).forEach((t) => {
      const opt = document.createElement('option');
      opt.value = t.value;
      opt.textContent = formatTypeLabel(t);
      typeSelect.appendChild(opt);
    });
  }
  
  if (previewTypeSelect) {
    previewTypeSelect.innerHTML = '';
    (config.taskTypes || []).forEach((t) => {
      const opt = document.createElement('option');
      opt.value = t.value;
      opt.textContent = formatTypeLabel(t);
      previewTypeSelect.appendChild(opt);
    });
  }

  // Build basic stats card shells
  buildStatsRow(config);

  updateApiBadgeFromConfig(config.ai);
  showBillingBanner(config.ai);

  // Settings visibility controls
  const settingsBtn = $('#btn-open-settings');
  if (settingsBtn) {
    settingsBtn.hidden = !!config.ai?.preconfigured;
  }
}

function buildStatsRow(config) {
  const row = $('#stats-row');
  if (!row) return;
  row.innerHTML = '';
  (config.statsDefinitions || []).forEach((def) => {
    const card = document.createElement('article');
    card.className = 'stat-card' + (def.cssClass ? ' ' + def.cssClass : '');
    card.innerHTML = `
      <div class="stat-label">
        <span>${escapeHtml(def.label)}</span>
      </div>
      <div class="stat-value" id="${def.id}">-</div>
      <div class="stat-sub">${escapeHtml(def.subLabel || '')}</div>
    `;
    row.appendChild(card);
  });
}

function showBillingBanner(ai) {
  let banner = document.getElementById('billing-banner');
  if (!ai?.billing_blocked) {
    if (banner) banner.hidden = true;
    return;
  }
  if (!banner) {
    banner = document.createElement('div');
    banner.id = 'billing-banner';
    banner.className = 'billing-banner';
    $('.content')?.insertBefore(banner, $('#stats-row'));
  }
  banner.hidden = false;
  banner.textContent = messages.aiBillingBlocked || ai.last_error || 'Billing limits reached on AI platform.';
}

function updateApiBadgeFromConfig(ai) {
  const badge = $('#api-key-badge');
  const badgeText = $('#api-badge-text');
  if (!badge || !badgeText) return;
  
  const configured = ai?.configured;
  badge.className = 'ai-badge ' + (configured ? 'api-badge--on' : 'api-badge--off');
  badgeText.textContent = configured
    ? tpl(messages.apiKeyActive || "AI Prioritization: {hint}", { hint: ai.partialKeyHint || ai.source || '' })
    : (messages.apiKeyNotSet || "AI Prioritization: Fallback Mode");

  const valEl = $('#key-status-value');
  if (valEl) {
    valEl.textContent = ai?.status || 'inactive';
    valEl.className = 'key-status-pill ' + (configured ? 'active' : 'inactive');
  }
  
  const srcEl = $('#key-source-value');
  if (srcEl) srcEl.textContent = ai?.source || 'none';
  
  const hintEl = $('#key-hint-value');
  if (hintEl) hintEl.textContent = ai?.partialKeyHint || '';
}

async function loadUiConfig() {
  const { ok, data } = await api('/api/config/ui');
  if (!ok) {
    document.body.innerHTML = `<p style="color:#f87171;padding:2rem;font-weight:600">Failed to boot application configuration from REST backend.</p>`;
    return false;
  }
  applyUiConfig(data);
  return true;
}

async function loadApiKeyStatus() {
  const { ok, data } = await api('/api/config/ai');
  if (ok) {
    updateApiBadgeFromConfig({
      configured: data.configured,
      source: data.source,
      partialKeyHint: data.partial_key_hint,
      status: data.status,
    });
    showBillingBanner({
      billing_blocked: data.billing_blocked,
      last_error: data.last_error,
    });
  }
}

async function refresh() {
  try {
    const [statsRes, tasksRes, logsRes] = await Promise.all([
      api('/api/queue/stats'),
      api('/api/tasks'),
      api('/api/tasks/ai-insights'),
    ]);

    if (statsRes.ok) {
      const s = statsRes.data;
      (uiConfig?.statsDefinitions || []).forEach((def) => {
        const el = document.getElementById(def.id);
        if (el) el.textContent = formatStatValue(def, s);
      });
      
      drawDonutChart(s.tasksByType || {}, s.totalTasks || 0);
      updateActivityChart(s.queueSize || 0);
      drawThreadsGrid(s.workers || []);
      drawThreadPoolLanes(s.workers || []);
      updateNodeHealth(s);
      updateCpuPill(s);
      
      const countEl = $('#ai-decisions-made-value');
      if (countEl && s.aiDecisionsMade != null) countEl.textContent = s.aiDecisionsMade;
      const overrideEl = $('#ai-overrides-badge');
      if (overrideEl && s.aiUrgentOverrides != null) overrideEl.textContent = `${s.aiUrgentOverrides} URGENT OVERRIDES`;
    }

    if (tasksRes.ok) {
      allFetchedTasks = tasksRes.data || [];
      renderTasksFiltered();
      
      const pendingTasks = allFetchedTasks.filter(t => t.status === 'PENDING');
      drawQueueList(pendingTasks);
      
      updateSystemLogs(allFetchedTasks);
      updateActivityFeed(allFetchedTasks);
      updateTasksStatsRow(allFetchedTasks);
    }

    if (logsRes.ok) {
      renderAiLogs(logsRes.data || []);
    }
  } catch (err) {
    console.error('System synchronization tick encountered an error', err);
  }
}

function startRefreshLoop() {
  if (refreshTimer) clearInterval(refreshTimer);
  const ms = uiConfig?.threadPool?.refreshIntervalMs || 1500;
  refreshTimer = setInterval(refresh, ms);
}

// Attach settings modal events programmatically
function bindStaticEvents() {
  const settingsBtn = $('#btn-open-settings');
  if (settingsBtn) {
    settingsBtn.addEventListener('click', () => {
      loadApiKeyStatus();
      const preconfigured = uiConfig?.ai?.preconfigured;
      const keySection = document.querySelector('#settings-modal .form-group');
      const keyActions = document.querySelector('.modal-actions');
      if (preconfigured) {
        if (keySection) keySection.style.display = 'none';
        if (keyActions) keyActions.style.display = 'none';
        showSettingsMsg(messages.apiKeyPreconfigured || "API key preconfigured in backend local.properties.", true);
      } else {
        if (keySection) keySection.style.display = '';
        if (keyActions) keyActions.style.display = '';
        if (settingsMessage) settingsMessage.hidden = true;
      }
      if (modal) modal.showModal();
    });
  }
  initThemeSwitcher();
}

async function init() {
  bindStaticEvents();
  const loaded = await loadUiConfig();
  if (!loaded) return;
  await refresh();
  startRefreshLoop();
}

window.onload = init;

// Initialize theme switcher UI and event handling
function initThemeSwitcher() {
  const themeBtn = $('#theme-btn');
  const dropdown = $('#theme-dropdown');
  const options = $$('.theme-option');

  if (!themeBtn || !dropdown) return;

  const currentTheme = localStorage.getItem('tq-theme') || 'obsidian';
  options.forEach(opt => {
    if (opt.getAttribute('data-theme') === currentTheme) {
      opt.classList.add('active');
    } else {
      opt.classList.remove('active');
    }
  });

  themeBtn.addEventListener('click', (e) => {
    e.stopPropagation();
    dropdown.classList.toggle('open');
  });

  document.addEventListener('click', (e) => {
    if (!dropdown.contains(e.target) && e.target !== themeBtn && !themeBtn.contains(e.target)) {
      dropdown.classList.remove('open');
    }
  });

  options.forEach(opt => {
    opt.addEventListener('click', () => {
      const selectedTheme = opt.getAttribute('data-theme');
      const activeTheme = localStorage.getItem('tq-theme') || 'obsidian';

      if (selectedTheme === activeTheme) {
        dropdown.classList.remove('open');
        return;
      }

      document.body.classList.add('theme-transitioning');

      options.forEach(o => o.classList.remove('active'));
      opt.classList.add('active');

      if (selectedTheme === 'obsidian') {
        document.documentElement.classList.add('theme-obsidian');
      } else {
        document.documentElement.classList.remove('theme-obsidian');
      }

      localStorage.setItem('tq-theme', selectedTheme);
      dropdown.classList.remove('open');

      refresh();

      setTimeout(() => {
        document.body.classList.remove('theme-transitioning');
      }, 400);
    });
  });
}
