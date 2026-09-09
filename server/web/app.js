'use strict';

const $ = (id) => document.getElementById(id);
const el = {
  form: $('form'), url: $('url'), clearBtn: $('clearBtn'), pasteBtn: $('pasteBtn'),
  findBtn: $('findBtn'), spinner: document.querySelector('#findBtn .spinner'),
  alert: $('alert'), hint: $('hint'),
  result: $('result'), thumb: $('thumb'), thumbFallback: $('thumbFallback'),
  duration: $('duration'), title: $('title'), uploader: $('uploader'), source: $('source'),
  options: $('options'), moreBtn: $('moreBtn'), allFormats: $('allFormats'),
  playlist: $('playlist'), plTitle: $('plTitle'), plMeta: $('plMeta'), plEntries: $('plEntries'),
  jobs: $('jobs'),
  historyBtn: $('historyBtn'), historyPanel: $('historyPanel'), historyList: $('historyList'),
  closeHistory: $('closeHistory'), clearHistory: $('clearHistory'),
  envInfo: $('envInfo'),
};

let current = null;          // last probed media
let ffmpegAvailable = true;
const polls = new Map();     // jobId -> interval handle

/* ------------------------------------------------------------------ utils */

function fmtDuration(s) {
  if (!s && s !== 0) return '';
  s = Math.round(s);
  const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), sec = s % 60;
  const pad = (n) => String(n).padStart(2, '0');
  return h ? `${h}:${pad(m)}:${pad(sec)}` : `${m}:${pad(sec)}`;
}

function fmtEta(s) {
  if (!s && s !== 0) return '';
  return s >= 60 ? `${Math.round(s / 60)}m left` : `${Math.round(s)}s left`;
}

function showAlert(msg) {
  el.alert.textContent = msg;
  el.alert.hidden = !msg;
}

async function api(path, options) {
  const res = await fetch(path, options);
  let data = {};
  try { data = await res.json(); } catch (_) { /* non-JSON body */ }
  if (!res.ok) throw new Error(data.error || `Request failed (${res.status})`);
  return data;
}

function busy(on) {
  el.findBtn.disabled = on;
  el.spinner.hidden = !on;
  document.querySelector('#findBtn .btn-label').textContent = on ? 'Reading link' : 'Find media';
}

/* ------------------------------------------------------------------ probe */

async function probe(rawUrl) {
  const url = (rawUrl || el.url.value).trim();
  if (!url) { el.url.focus(); return; }

  showAlert('');
  el.result.hidden = true;
  el.playlist.hidden = true;
  busy(true);

  try {
    const info = await api('/api/probe', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ url }),
    });
    ffmpegAvailable = info.ffmpeg !== false;
    if (info.type === 'playlist') renderPlaylist(info, url);
    else renderMedia(info, url);
  } catch (err) {
    showAlert(err.message);
  } finally {
    busy(false);
  }
}

function renderMedia(info, url) {
  current = Object.assign({}, info, { requestUrl: url });

  el.title.textContent = info.title;
  el.uploader.textContent = info.uploader || '';
  el.uploader.hidden = !info.uploader;
  el.source.textContent = info.source || '';
  el.source.hidden = !info.source;

  el.duration.textContent = fmtDuration(info.duration);
  el.duration.hidden = !info.duration;

  if (info.thumbnail) {
    el.thumb.src = '/api/thumb?u=' + encodeURIComponent(info.thumbnail);
    el.thumb.hidden = false;
    el.thumbFallback.hidden = true;
    el.thumb.onerror = () => { el.thumb.hidden = true; el.thumbFallback.hidden = false; };
  } else {
    el.thumb.hidden = true;
    el.thumbFallback.hidden = false;
  }

  el.options.innerHTML = '';
  (info.options || []).forEach((opt) => {
    const blocked = opt.needs_ffmpeg && !ffmpegAvailable;
    el.options.appendChild(optionRow({
      title: opt.title,
      subtitle: blocked ? 'Needs ffmpeg — install it to enable' : opt.subtitle,
      recommended: opt.recommended,
      disabled: blocked,
      onClick: () => startJob(url, opt.selector, opt.kind, `${info.title} · ${opt.title}`),
    }));
  });

  const extra = (info.formats || []).filter((f) => f.format_id);
  el.allFormats.innerHTML = '';
  el.allFormats.hidden = true;
  el.moreBtn.hidden = extra.length < 2;
  el.moreBtn.textContent = 'Show all formats';
  extra.forEach((f) => {
    const parts = [f.ext && f.ext.toUpperCase(), f.size_human, f.kind.replace('-', ' '), f.note]
      .filter(Boolean).join(' · ');
    el.allFormats.appendChild(optionRow({
      title: f.label,
      subtitle: parts,
      onClick: () => startJob(url, f.format_id, f.kind === 'audio' ? 'audio' : 'video',
                              `${info.title} · ${f.label}`),
    }));
  });

  el.result.hidden = false;
  el.hint.hidden = true;
  remember({ title: info.title, url, uploader: info.uploader });
}

function optionRow({ title, subtitle, recommended, disabled, onClick }) {
  const b = document.createElement('button');
  b.type = 'button';
  b.className = 'opt' + (recommended ? ' recommended' : '');
  b.disabled = !!disabled;
  if (disabled) b.style.opacity = '.5';

  const left = document.createElement('div');
  const t = document.createElement('div');
  t.className = 'opt-title';
  t.textContent = title;
  if (recommended) {
    const pill = document.createElement('span');
    pill.className = 'pill';
    pill.textContent = 'Best';
    t.appendChild(pill);
  }
  left.appendChild(t);
  if (subtitle) {
    const s = document.createElement('div');
    s.className = 'opt-sub';
    s.textContent = subtitle;
    left.appendChild(s);
  }

  const go = document.createElement('span');
  go.className = 'go';
  go.innerHTML = '<svg viewBox="0 0 24 24"><path d="M12 4v12m0 0 4.5-4.5M12 16l-4.5-4.5M5 20h14"/></svg>';

  b.append(left, go);
  if (!disabled) b.addEventListener('click', onClick);
  return b;
}

function renderPlaylist(info, url) {
  el.plTitle.textContent = info.title;
  el.plMeta.textContent = [info.uploader, `${info.count} items`].filter(Boolean).join(' · ');
  el.plEntries.innerHTML = '';
  info.entries.forEach((entry) => {
    if (!entry.url) return;
    const b = document.createElement('button');
    b.type = 'button';
    b.className = 'entry';
    const left = document.createElement('div');
    const t = document.createElement('div');
    t.className = 'entry-title';
    t.textContent = entry.title;
    left.appendChild(t);
    if (entry.duration) {
      const s = document.createElement('div');
      s.className = 'entry-sub';
      s.textContent = fmtDuration(entry.duration);
      left.appendChild(s);
    }
    const go = document.createElement('span');
    go.className = 'go';
    go.innerHTML = '<svg viewBox="0 0 24 24"><path d="m9 6 6 6-6 6"/></svg>';
    b.append(left, go);
    b.addEventListener('click', () => { el.url.value = entry.url; probe(entry.url); window.scrollTo({ top: 0, behavior: 'smooth' }); });
    el.plEntries.appendChild(b);
  });
  el.playlist.hidden = false;
  el.hint.hidden = true;
  remember({ title: info.title, url, uploader: info.uploader });
}

/* ------------------------------------------------------------------- jobs */

async function startJob(url, selector, kind, label) {
  const card = jobCard(label);
  el.jobs.prepend(card.node);
  card.node.scrollIntoView({ behavior: 'smooth', block: 'nearest' });

  try {
    const job = await api('/api/jobs', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ url, selector, kind }),
    });
    card.attach(job.id, () => startJob(url, selector, kind, label));
    poll(job.id, card);
  } catch (err) {
    card.fail(err.message, () => startJob(url, selector, kind, label));
  }
}

function poll(jobId, card) {
  const tick = async () => {
    try {
      const job = await api(`/api/jobs/${jobId}`);
      card.update(job);
      if (job.status === 'ready') {
        stopPoll(jobId);
        card.ready(jobId);
        save(jobId);                       // hand the file to the browser
      } else if (job.status === 'error' || job.status === 'cancelled') {
        stopPoll(jobId);
        if (job.status === 'error') card.fail(job.error || 'Download failed', card.retry);
        else card.cancelled();
      }
    } catch (err) {
      stopPoll(jobId);
      card.fail(err.message, card.retry);
    }
  };
  polls.set(jobId, setInterval(tick, 700));
  tick();
}

function stopPoll(jobId) {
  clearInterval(polls.get(jobId));
  polls.delete(jobId);
}

function save(jobId) {
  const a = document.createElement('a');
  a.href = `/api/jobs/${jobId}/file`;
  a.download = '';
  document.body.appendChild(a);
  a.click();
  a.remove();
}

function jobCard(label) {
  const node = document.createElement('div');
  node.className = 'job';
  node.innerHTML = `
    <div class="job-head">
      <div class="job-name"></div>
      <div class="job-pct">Starting…</div>
    </div>
    <div class="bar indeterminate"><span></span></div>
    <div class="job-foot"><div class="status">Preparing</div><div class="job-actions"></div></div>`;
  node.querySelector('.job-name').textContent = label;

  const bar = node.querySelector('.bar');
  const fill = node.querySelector('.bar span');
  const pct = node.querySelector('.job-pct');
  const status = node.querySelector('.status');
  const actions = node.querySelector('.job-actions');

  const card = {
    node,
    retry: null,
    attach(jobId, retry) {
      card.retry = retry;
      actions.innerHTML = '';
      const cancel = document.createElement('button');
      cancel.className = 'mini';
      cancel.textContent = 'Cancel';
      cancel.onclick = () => {
        fetch(`/api/jobs/${jobId}/cancel`, { method: 'POST' }).catch(() => {});
        cancel.disabled = true;
      };
      actions.appendChild(cancel);
    },
    update(job) {
      if (job.status === 'processing') {
        bar.classList.add('indeterminate');
        pct.textContent = '';
        status.textContent = 'Merging audio and video…';
        return;
      }
      if (job.total) {
        bar.classList.remove('indeterminate');
        fill.style.width = `${Math.round(job.progress * 100)}%`;
        pct.textContent = `${Math.round(job.progress * 100)}%`;
      }
      const bits = [
        job.total_human ? `${job.downloaded_human || '0 B'} of ${job.total_human}` : job.downloaded_human,
        job.speed_human,
        fmtEta(job.eta),
      ].filter(Boolean);
      status.textContent = bits.join(' · ') || 'Downloading…';
    },
    ready(jobId) {
      bar.classList.remove('indeterminate');
      bar.classList.add('done');
      fill.style.width = '100%';
      pct.textContent = 'Done';
      status.textContent = 'Saved to your Downloads';
      actions.innerHTML = '';
      const again = document.createElement('button');
      again.className = 'mini save';
      again.textContent = 'Save again';
      again.onclick = () => save(jobId);
      actions.appendChild(again);
    },
    fail(message, retry) {
      bar.classList.remove('indeterminate');
      bar.classList.add('failed');
      pct.textContent = 'Failed';
      status.className = 'status err';
      status.textContent = message;
      actions.innerHTML = '';
      if (retry) {
        const r = document.createElement('button');
        r.className = 'mini retry';
        r.textContent = 'Try again';
        r.onclick = () => { node.remove(); retry(); };
        actions.appendChild(r);
      }
    },
    cancelled() {
      bar.classList.remove('indeterminate');
      pct.textContent = 'Cancelled';
      status.textContent = 'Stopped';
      actions.innerHTML = '';
      setTimeout(() => node.remove(), 1800);
    },
  };
  return card;
}

/* ---------------------------------------------------------------- history */

const HISTORY_KEY = 'media-saver:history';

function readHistory() {
  try { return JSON.parse(localStorage.getItem(HISTORY_KEY) || '[]'); } catch (_) { return []; }
}

function remember(item) {
  const list = readHistory().filter((x) => x.url !== item.url);
  list.unshift(Object.assign({ at: Date.now() }, item));
  try { localStorage.setItem(HISTORY_KEY, JSON.stringify(list.slice(0, 25))); } catch (_) {}
}

function renderHistory() {
  const list = readHistory();
  el.historyList.innerHTML = '';
  if (!list.length) {
    const p = document.createElement('p');
    p.className = 'muted';
    p.textContent = 'Nothing yet. Links you look up show up here.';
    el.historyList.appendChild(p);
    return;
  }
  list.forEach((item) => {
    const b = document.createElement('button');
    b.type = 'button';
    b.className = 'entry';
    const left = document.createElement('div');
    const t = document.createElement('div');
    t.className = 'entry-title';
    t.textContent = item.title || item.url;
    const s = document.createElement('div');
    s.className = 'entry-sub';
    s.textContent = item.uploader || new URL(item.url).hostname;
    left.append(t, s);
    const go = document.createElement('span');
    go.className = 'go';
    go.innerHTML = '<svg viewBox="0 0 24 24"><path d="m9 6 6 6-6 6"/></svg>';
    b.append(left, go);
    b.addEventListener('click', () => {
      el.historyPanel.hidden = true;
      el.url.value = item.url;
      probe(item.url);
    });
    el.historyList.appendChild(b);
  });
}

/* ----------------------------------------------------------------- wiring */

el.form.addEventListener('submit', (e) => { e.preventDefault(); el.url.blur(); probe(); });

el.url.addEventListener('input', () => { el.clearBtn.hidden = !el.url.value; });
el.clearBtn.addEventListener('click', () => {
  el.url.value = '';
  el.clearBtn.hidden = true;
  el.result.hidden = true;
  el.playlist.hidden = true;
  el.hint.hidden = false;
  showAlert('');
  el.url.focus();
});

el.pasteBtn.addEventListener('click', async () => {
  try {
    const text = (await navigator.clipboard.readText()).trim();
    if (!text) return showAlert('Clipboard is empty.');
    el.url.value = text;
    el.clearBtn.hidden = false;
    probe(text);
  } catch (_) {
    showAlert('Your browser blocked clipboard access — paste into the box instead.');
    el.url.focus();
  }
});

el.moreBtn.addEventListener('click', () => {
  const showing = !el.allFormats.hidden;
  el.allFormats.hidden = showing;
  el.moreBtn.textContent = showing ? 'Show all formats' : 'Hide formats';
});

el.historyBtn.addEventListener('click', () => { renderHistory(); el.historyPanel.hidden = false; });
el.closeHistory.addEventListener('click', () => { el.historyPanel.hidden = true; });
el.historyPanel.addEventListener('click', (e) => {
  if (e.target === el.historyPanel) el.historyPanel.hidden = true;
});
el.clearHistory.addEventListener('click', () => {
  try { localStorage.removeItem(HISTORY_KEY); } catch (_) {}
  renderHistory();
});

/* Shared-in or deep-linked URL: /?url=… (also handles the Android share sheet) */
(function bootstrap() {
  const params = new URLSearchParams(location.search);
  const shared = params.get('url') || params.get('text') || '';
  const match = shared.match(/https?:\/\/\S+/);
  if (match) {
    el.url.value = match[0];
    el.clearBtn.hidden = false;
    history.replaceState({}, '', location.pathname);
    probe(match[0]);
  }

  api('/api/health').then((h) => {
    ffmpegAvailable = !!h.ffmpeg;
    el.envInfo.textContent = h.ffmpeg
      ? `yt-dlp ${h.ytdlp}`
      : `yt-dlp ${h.ytdlp} · ffmpeg missing, HD merging disabled`;
  }).catch(() => {});

  if ('serviceWorker' in navigator) {
    navigator.serviceWorker.register('/sw.js').catch(() => {});
  }
})();
