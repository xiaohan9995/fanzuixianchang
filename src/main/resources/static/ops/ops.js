'use strict';
/* 运营装订台
 *
 * 三件事决定了这里的写法：
 *   1. 所有写入都带版本号。过期写入必须被拒，不能悄悄覆盖别人刚存的内容。
 *   2. 任何失败都要说清「发生了什么 + 我该做什么」，不用「操作失败」搪塞。
 *   3. 令牌失效先回到登录态，不要白屏。
 *
 * 数据只从服务端取，前端不猜。所有数字都来自接口。
 */

const $ = (id) => document.getElementById(id);

const state = {
  token: '',
  busy: false,
  epoch: 0,
  view: 'desk',
  data: {
    overview: null,
    feedback: [],
    proposals: [],
    workAudit: [],
    cases: null,
    metrics: null,
  },
  draft: { json: '', dirty: false, version: 0 },
  open: { feedback: null, proposal: null },
  page: { feedback: 1, proposal: 1 },
  perPage: 20,
};

const FEEDBACK_STATUS = { open: '待处理', processing: '处理中', resolved: '已解决' };
const PROPOSAL_STATUS = { pending: '待审核', approved: '已通过', rejected: '已驳回' };
const CATEGORY = {
  recognition: '识别纠错', missing_food: '食物缺失', portion: '份量问题',
  suggestion: '建议反馈', other: '其他反馈',
};
const ACTION = {
  save: '保存草稿', publish: '发布', rollback: '回滚',
  create: '新增', archive: '归档正文', open: '重新打开',
  processing: '开始处理', resolved: '已解决', approved: '审核通过', rejected: '驳回',
};

/* ── 小工具 ─────────────────────────────────────────── */

function el(tag, cls, text) {
  const node = document.createElement(tag);
  if (cls) node.className = cls;
  if (text != null) node.textContent = text;
  return node;
}

function localDate(iso) {
  const d = iso ? new Date(iso) : new Date();
  if (Number.isNaN(d.getTime())) return iso || '—';
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

function clockText(iso) {
  const d = iso ? new Date(iso) : new Date();
  if (Number.isNaN(d.getTime())) return '';
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
}

/* 相对时间。只给「多久之前」，因为运营关心的就是它放了多久。 */
function agoText(iso) {
  if (!iso) return '时间未知';
  const then = new Date(iso).getTime();
  if (Number.isNaN(then)) return '时间未知';
  const mins = Math.max(0, Math.round((Date.now() - then) / 60000));
  if (mins < 1) return '刚刚';
  if (mins < 60) return `${mins} 分钟前`;
  const hours = Math.round(mins / 60);
  if (hours < 24) return `${hours} 小时前`;
  const days = Math.round(hours / 24);
  if (days < 30) return `${days} 天前`;
  return localDate(iso);
}

/* 放太久的散件要自己冒出来。4 小时是这里的界线：
   同一天里还来得及处理，超过就属于「堆着了」。 */
function isStale(iso) {
  if (!iso) return false;
  const then = new Date(iso).getTime();
  if (Number.isNaN(then)) return false;
  return Date.now() - then > 4 * 3600 * 1000;
}

/* ── 网络 ───────────────────────────────────────────── */

function flash(text, isError) {
  const node = $('flash');
  node.textContent = text || '';
  node.dataset.error = isError ? 'true' : 'false';
}

async function api(path, method = 'GET', body) {
  const epoch = state.epoch;
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 20000);
  try {
    const headers = { 'Content-Type': 'application/json' };
    if (state.token) headers.Authorization = `Bearer ${state.token}`;
    const response = await fetch(`/api/ops/${path}`, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: controller.signal,
    });
    let data = null;
    try { data = await response.json(); } catch (e) { data = null; }
    if (epoch !== state.epoch) throw new Error('管理连接已更新，请重新操作。');

    if (!response.ok) {
      if (response.status === 401) {
        /* 401 有两种来路：压根没给令牌，或者给了但不对。
           说成「失效」会让第一次打开的人以为自己漏了什么。 */
        const had = !!state.token;
        state.token = '';
        state.epoch += 1;
        showGate();
        throw new Error(had ? '令牌不对，或者已经换过了。请重新输入。' : '这个工作区要令牌才能进。');
      }
      const message = data && (data.message || data.error);
      if (response.status === 409) {
        throw new Error(`${message || '内容已被改动'} —— 先刷新看一眼最新版，再决定要不要重做。`);
      }
      throw new Error(message || `服务返回 ${response.status}，这次操作没有生效。`);
    }
    return data;
  } catch (e) {
    if (e.name === 'AbortError') throw new Error('请求超时，结果未确认。先刷新核对，再决定是否重试。');
    throw e;
  } finally {
    clearTimeout(timer);
  }
}

/* 所有写操作走这里：统一 busy、统一报错、统一刷新。 */
async function run(action, successText) {
  if (state.busy) return;
  state.busy = true;
  document.body.setAttribute('aria-busy', 'true');
  flash('');
  try {
    await action();
    if (successText) flash(successText, false);
  } catch (e) {
    flash(e.message, true);
  } finally {
    state.busy = false;
    document.body.removeAttribute('aria-busy');
  }
}

/* ── 视图切换 ───────────────────────────────────────── */

const VIEWS = ['desk', 'notes', 'food', 'cases', 'seal'];

function showView(name, opts) {
  if (!VIEWS.includes(name)) name = 'desk';
  state.view = name;
  VIEWS.forEach((v) => {
    const tab = $(`tab-${v}`);
    const panel = $(`panel-${v}`);
    const on = v === name;
    if (tab) tab.setAttribute('aria-selected', on ? 'true' : 'false');
    if (panel) panel.hidden = !on;
  });
  if (!opts || !opts.silent) {
    try { history.replaceState(null, '', `#${name}`); } catch (e) { /* file:// 下忽略 */ }
  }
  const panel = $(`panel-${name}`);
  if (panel && opts && opts.focus) panel.focus({ preventScroll: true });
}

function initTabs() {
  $('tablist').addEventListener('click', (event) => {
    const btn = event.target.closest('button[data-view]');
    if (!btn) return;
    showView(btn.dataset.view);
  });
  /* 方向键在页签之间移动——这是 web 上页签该有的行为，不是加分项。 */
  $('tablist').addEventListener('keydown', (event) => {
    const keys = { ArrowRight: 1, ArrowDown: 1, ArrowLeft: -1, ArrowUp: -1 };
    if (!(event.key in keys)) return;
    const tabs = [...$('tablist').querySelectorAll('button[data-view]')];
    const i = tabs.indexOf(document.activeElement);
    if (i < 0) return;
    event.preventDefault();
    const next = tabs[(i + keys[event.key] + tabs.length) % tabs.length];
    next.focus();
    showView(next.dataset.view);
  });
  const fromHash = (location.hash || '').replace('#', '');
  if (VIEWS.includes(fromHash)) showView(fromHash, { silent: true });
}

/* ── 骑缝章 ─────────────────────────────────────────── */

/* 印章状态只有三种：合、断、还不知道。
   判定只做一件事：草稿和线上的内容是不是同一份。 */
function seamState(draft, published) {
  if (draft == null && published == null) return 'unknown';
  if (published == null) return 'broken';
  if (draft == null) return 'broken';
  return JSON.stringify(draft) === JSON.stringify(published) ? 'whole' : 'broken';
}

function caseCount(bundle) {
  if (!bundle || !Array.isArray(bundle.cases)) return 0;
  return bundle.cases.length;
}

function lastPublishedAt() {
  const audit = (state.data.cases && state.data.cases.audit) || [];
  const pubs = audit.filter((a) => a.action === 'publish');
  return pubs.length ? pubs[pubs.length - 1].at : null;
}

function paintSeam(seamEl, noteEl, draftEl, liveEl, bundleDraft, bundleLive, prefix) {
  const s = seamState(bundleDraft, bundleLive);
  seamEl.dataset.state = s;

  if (prefix === 'desk') {
    draftEl.textContent = bundleDraft ? `${caseCount(bundleDraft)} 案` : '空';
    liveEl.textContent = bundleLive ? `${caseCount(bundleLive)} 案` : '未发布';
  } else {
    draftEl.textContent = bundleDraft ? `${caseCount(bundleDraft)} 案` : '空';
    const at = lastPublishedAt();
    liveEl.textContent = bundleLive ? `${caseCount(bundleLive)} 案${at ? ' · ' + localDate(at) : ''}` : '未发布';
  }

  noteEl.dataset.state = s;
  noteEl.textContent = {
    whole: '骑缝章完整 · 草稿和线上是同一版',
    broken: '骑缝章已断开 · 草稿和线上不是同一版',
    unknown: '还没有内容 · 先写一版草稿',
  }[s];

  $('tag-draft').hidden = s !== 'broken';
  return s;
}

/* ── 装订台 ─────────────────────────────────────────── */

function openFeedback() {
  return state.data.feedback.filter((f) => f.status === 'open' || f.status === 'processing');
}

function paintDesk() {
  const open = openFeedback();
  const aged = open.filter((f) => isStale(f.createdAt));
  const oldest = open.slice().sort((a, b) => String(a.createdAt).localeCompare(String(b.createdAt)))[0];

  const lead = $('lead');
  lead.replaceChildren();
  if (!open.length) {
    lead.append('桌上是空的。');
    const quiet = el('span', 'lead-quiet', '没有待处理的散件。');
    lead.append(quiet);
  } else {
    lead.append(el('b', null, String(open.length)));
    lead.append(' 份散件没装订');
    if (aged.length) {
      lead.append(' · 其中 ');
      lead.append(el('em', null, `${aged.length} 份`));
      const worst = agoText(oldest && oldest.createdAt).replace('前', '');
      lead.append(` 已放 ${worst === '时间未知' ? '很久' : worst}`);
    }
  }

  /* 桌上只摆最急的三份：给全部会把首屏变成一张表，那不是「一眼看出有没有事」。 */
  const list = $('desk-notes');
  list.replaceChildren();
  const top = open
    .slice()
    .sort((a, b) => String(a.createdAt).localeCompare(String(b.createdAt)))
    .slice(0, 5);
  top.forEach((item) => list.append(noteRow(item, true)));

  /* 桌上摆不完的要说清去哪了。不说明，人会对不上账，然后不再信这个数字。 */
  const more = $('desk-more');
  if (open.length > top.length) {
    more.hidden = false;
    more.textContent = `另有 ${open.length - top.length} 份在「散件待办」里，按收到时间排着`;
  } else {
    more.hidden = true;
  }

  $('desk-empty').hidden = open.length > 0;

  const cases = state.data.cases;
  $('desk-case-name').textContent = cases ? `v${cases.version}` : '—';
  paintSeam(
    $('desk-seam'), $('seam-note'), $('seam-draft'), $('seam-live'),
    cases && cases.draft, cases && cases.published, 'desk'
  );

  const overview = state.data.overview;
  const facts = $('desk-facts');
  facts.replaceChildren();
  if (overview) {
    facts.append(factRow('模型', overview.modelConfigured ? '已配置' : '未配置', overview.modelConfigured ? 'good' : 'bad'));
    facts.append(factRow('存储', storageWord(overview.storage), null));
    facts.append(factRow('营养目录', `v${overview.catalogVersion}`, null));
    facts.append(factRow('提案待审', `${state.data.proposals.filter((p) => p.status === 'pending').length} 份`, null));
  }

  const sealWord = $('seal-word');
  if (overview) {
    const ok = overview.modelConfigured !== false;
    sealWord.textContent = ok ? '完好' : '待配';
    sealWord.dataset.tone = ok ? 'good' : 'bad';
  }

  paintCounts();
}

function storageWord(value) {
  return {
    'cloudbase-postgresql': 'CloudBase PG',
    'wechat-cloudrun-mysql': 'MySQL',
    'single-instance-file': '单机文件',
  }[value] || value || '—';
}

function factRow(label, value, tone) {
  const row = el('div');
  row.append(el('dt', null, label));
  const dd = el('dd', null, value);
  if (tone) dd.dataset.tone = tone;
  row.append(dd);
  return row;
}

/* 袋上的待办数：与列表同一口径，不另算一份。 */
function paintCounts() {
  const f = openFeedback().length;
  const p = state.data.proposals.filter((x) => x.status === 'pending').length;
  const cases = state.data.cases;
  const d = cases && seamState(cases.draft, cases.published) === 'broken' ? 1 : 0;

  document.querySelectorAll('[data-count="feedback"]').forEach((n) => {
    n.textContent = String(f);
    n.hidden = f === 0;
  });
  document.querySelectorAll('[data-count="proposals"]').forEach((n) => {
    n.textContent = String(p);
    n.hidden = p === 0;
  });
  document.querySelectorAll('[data-count="draft"]').forEach((n) => {
    n.textContent = '未发';
    n.hidden = d === 0;
  });
}

/* ── 散件条目 ───────────────────────────────────────── */

function noteRow(item, compact) {
  const li = el('li', 'note');
  li.dataset.done = item.status === 'resolved' ? 'true' : 'false';

  li.append(el('div', 'note-no', item.id));

  const body = el('div', 'note-body');
  const line = el('p', 'note-line');
  line.append(el('span', 'note-cat', CATEGORY[item.category] || item.category || '其他'));
  line.append(item.status === 'processing' ? `处理中 · ${item.message || ''}` : (item.message || ''));
  body.append(line);

  const meta = el('p', 'note-meta');
  meta.dataset.stale = isStale(item.createdAt) ? 'true' : 'false';
  const bits = [agoText(item.createdAt)];
  if (item.resolution) bits.push(`处理说明：${item.resolution}`);
  meta.textContent = bits.join(' · ');
  body.append(meta);

  const expanded = state.open.feedback === item.id;
  if (expanded) body.append(feedbackEditor(item));
  li.append(body);

  const act = el('div', 'note-act');
  const btn = el('button', 'btn-act', expanded ? '收起' : '处置');
  btn.type = 'button';
  btn.setAttribute('aria-expanded', expanded ? 'true' : 'false');
  btn.addEventListener('click', () => {
    state.open.feedback = expanded ? null : item.id;
    paintFeedback();
    paintDesk();
    if (!expanded) {
      const box = document.querySelector(`[data-editor="${item.id}"] textarea`);
      if (box) box.focus();
    }
  });
  act.append(btn);
  li.append(act);
  return li;
}

function feedbackEditor(item) {
  const box = el('div', 'edit-panel');
  box.dataset.editor = item.id;

  const row = el('div', 'edit-row');

  const statusWrap = el('label', 'field-inline');
  statusWrap.append(el('span', null, '状态'));
  const status = el('select');
  Object.entries(FEEDBACK_STATUS).forEach(([value, label]) => {
    const opt = el('option', null, label);
    opt.value = value;
    if (item.status === value) opt.selected = true;
    status.append(opt);
  });
  status.id = `st-${item.id}`;
  statusWrap.append(status);
  row.append(statusWrap);

  const noteWrap = el('label', 'field-inline grow');
  noteWrap.append(el('span', null, '处理说明（会记进处理记录）'));
  const note = el('textarea');
  note.rows = 2;
  note.maxLength = 500;
  note.value = item.resolution || '';
  note.placeholder = '例：口径问题，建议按实际容器的刻度估';
  noteWrap.append(note);
  row.append(noteWrap);
  box.append(row);

  const actions = el('div', 'edit-actions');

  const save = el('button', 'btn-primary', '存下处置');
  save.type = 'button';
  save.addEventListener('click', () => run(async () => {
    const next = status.value;
    const text = note.value.trim();
    if (next === 'processing' || next === 'resolved') {
      if (!text) throw new Error('要留一句处理说明：将来只有这句话能说明当时为什么这么判。');
    }
    await api(`feedback/${encodeURIComponent(item.id)}/status`, 'POST', {
      version: item.version, status: next, resolution: text,
    });
    state.open.feedback = null;
    await reload();
    flash('处置已记下。', false);
  }));
  actions.append(save);

  const archive = el('button', 'btn-ghost', '归档并移除正文');
  archive.type = 'button';
  archive.addEventListener('click', () => run(async () => {
    if (!confirm('归档会移除用户写的正文，只留记录。确认？')) return;
    await api(`feedback/${encodeURIComponent(item.id)}/archive`, 'POST', { version: item.version });
    state.open.feedback = null;
    await reload();
    flash('已归档。', false);
  }));
  actions.append(archive);
  box.append(actions);
  return box;
}

/* ── 反馈列表 ───────────────────────────────────────── */

function filteredFeedback() {
  const q = ($('q-feedback').value || '').trim().toLowerCase();
  const status = $('f-feedback').value;
  return state.data.feedback.filter((f) => {
    if (status !== 'all' && f.status !== status) return false;
    if (!q) return true;
    return [f.id, f.message, f.resolution, CATEGORY[f.category]]
      .filter(Boolean).join(' ').toLowerCase().includes(q);
  });
}

function paintFeedback() {
  const items = filteredFeedback();
  const list = $('feedback-list');
  list.replaceChildren();

  const pages = Math.max(1, Math.ceil(items.length / state.perPage));
  if (state.page.feedback > pages) state.page.feedback = pages;
  const start = (state.page.feedback - 1) * state.perPage;
  items.slice(start, start + state.perPage).forEach((item) => list.append(noteRow(item, false)));

  $('feedback-empty').hidden = items.length > 0;
  $('feedback-count').textContent = items.length
    ? `共 ${items.length} 条${items.length > state.perPage ? ` · 第 ${state.page.feedback}/${pages} 页` : ''}`
    : '';

  paintPager($('feedback-pager'), state.page.feedback, pages, (p) => {
    state.page.feedback = p;
    paintFeedback();
  });
}

function paintPager(host, current, pages, go) {
  host.replaceChildren();
  if (pages <= 1) return;
  for (let p = 1; p <= pages; p += 1) {
    const btn = el('button', null, String(p));
    btn.type = 'button';
    if (p === current) btn.setAttribute('aria-current', 'true');
    btn.addEventListener('click', () => go(p));
    host.append(btn);
  }
}

/* ── 提案 ───────────────────────────────────────────── */

function filteredProposals() {
  const q = ($('q-proposal').value || '').trim().toLowerCase();
  const status = $('f-proposal').value;
  return state.data.proposals.filter((p) => {
    if (status !== 'all' && p.status !== status) return false;
    if (!q) return true;
    return [p.id, p.name, p.aliases, p.sourceUrl, p.reviewNote]
      .filter(Boolean).join(' ').toLowerCase().includes(q);
  });
}

function paintProposals() {
  const items = filteredProposals();
  const list = $('proposal-list');
  list.replaceChildren();

  const pages = Math.max(1, Math.ceil(items.length / state.perPage));
  if (state.page.proposal > pages) state.page.proposal = pages;
  const start = (state.page.proposal - 1) * state.perPage;
  items.slice(start, start + state.perPage).forEach((item) => list.append(proposalRow(item)));

  $('proposal-empty').hidden = items.length > 0;
  $('proposal-count').textContent = items.length ? `共 ${items.length} 份` : '';
  paintPager($('proposal-pager'), state.page.proposal, pages, (p) => {
    state.page.proposal = p;
    paintProposals();
  });
}

function proposalRow(item) {
  const li = el('li', 'note');
  li.dataset.done = item.status === 'approved' ? 'true' : 'false';

  li.append(el('div', 'note-no', item.id));

  const body = el('div', 'note-body');
  const line = el('p', 'note-line');
  line.append(el('span', 'note-cat', PROPOSAL_STATUS[item.status] || item.status));
  line.append(item.name || '（未命名）');
  if (item.aliases) {
    const bits = el('span', 'note-cat note-cat-plain');
    bits.textContent = item.aliases;
    line.append(bits);
  }
  body.append(line);

  const meta = el('p', 'note-meta');
  const parts = [agoText(item.createdAt)];
  if (item.sourceUrl) parts.push(item.sourceUrl);
  if (item.reviewNote) parts.push(`审核说明：${item.reviewNote}`);
  meta.textContent = parts.join(' · ');
  body.append(meta);

  const expanded = state.open.proposal === item.id;
  if (expanded) body.append(proposalEditor(item));
  li.append(body);

  const act = el('div', 'note-act');
  const btn = el('button', 'btn-act', expanded ? '收起' : '审核');
  btn.type = 'button';
  btn.setAttribute('aria-expanded', expanded ? 'true' : 'false');
  btn.addEventListener('click', () => {
    state.open.proposal = expanded ? null : item.id;
    paintProposals();
  });
  act.append(btn);
  li.append(act);
  return li;
}

function proposalEditor(item) {
  const box = el('div', 'edit-panel');

  if (item.sourceNote) {
    box.append(el('p', 'edit-quote', `提交说明：${item.sourceNote}`));
  }

  const row = el('div', 'edit-row');

  const statusWrap = el('label', 'field-inline');
  statusWrap.append(el('span', null, '结论'));
  const status = el('select');
  Object.entries(PROPOSAL_STATUS).forEach(([value, label]) => {
    const opt = el('option', null, label);
    opt.value = value;
    if (item.status === value) opt.selected = true;
    status.append(opt);
  });
  statusWrap.append(status);
  row.append(statusWrap);

  const noteWrap = el('label', 'field-inline grow');
  noteWrap.append(el('span', null, '审核说明'));
  const text = el('textarea');
  text.rows = 2;
  text.maxLength = 500;
  text.value = item.reviewNote || '';
  text.placeholder = '例：来源可信，但份量口径需再核一遍';
  noteWrap.append(text);
  row.append(noteWrap);
  box.append(row);

  const actions = el('div', 'edit-actions');

  const save = el('button', 'btn-primary', '存下结论');
  save.type = 'button';
  save.addEventListener('click', () => run(async () => {
    await api(`proposals/${encodeURIComponent(item.id)}/review`, 'POST', {
      version: item.version, status: status.value, reviewNote: text.value.trim(),
    });
    state.open.proposal = null;
    await reload();
    flash('审核结论已记下。通过不等于入目录，仍需随版本发布。', false);
  }));
  actions.append(save);

  const archive = el('button', 'btn-ghost', '归档并移除正文');
  archive.type = 'button';
  archive.addEventListener('click', () => run(async () => {
    if (!confirm('归档会移除提案正文，只留记录。确认？')) return;
    await api(`proposals/${encodeURIComponent(item.id)}/archive`, 'POST', { version: item.version });
    state.open.proposal = null;
    await reload();
    flash('已归档。', false);
  }));
  actions.append(archive);
  box.append(actions);
  return box;
}

/* ── 案卷 ───────────────────────────────────────────── */

function paintCases() {
  const cases = state.data.cases;
  if (!cases) return;

  paintSeam(
    $('case-seam'), $('case-seam-note'), $('case-draft'), $('case-live'),
    cases.draft, cases.published, 'case'
  );

  $('case-version').textContent = `当前 v${cases.version}`;

  if (!state.draft.dirty) {
    $('case-json').value = JSON.stringify(cases.draft || { schemaVersion: 1, cases: [] }, null, 2);
    state.draft.json = $('case-json').value;
    state.draft.version = cases.version;
  }

  paintHistory(cases.history || []);
  paintTable($('case-audit'), ['动作', '版本', '时间'],
    (cases.audit || []).slice().reverse().map((a) => [
      ACTION[a.action] || a.action,
      `v${a.version}`,
      localDate(a.at) + ' ' + clockText(a.at),
    ]), '还没有操作记录。');
}

function paintHistory(history) {
  const rows = history.slice().reverse().map((h) => [
    `v${h.version}`,
    `${caseCount(h.bundle)} 案`,
    localDate(h.at) + ' ' + clockText(h.at),
  ]);
  paintTable($('case-history'), ['版本', '内容', '时间'], rows, '还没有历史版本。');
}

function paintTable(host, headers, rows, emptyText) {
  host.replaceChildren();
  if (!rows.length) {
    host.append(el('p', 'empty', emptyText));
    return;
  }
  const table = el('table');
  const thead = el('thead');
  const hr = el('tr');
  headers.forEach((h) => hr.append(el('th', null, h)));
  thead.append(hr);
  table.append(thead);
  const tbody = el('tbody');
  rows.forEach((cells) => {
    const tr = el('tr');
    cells.forEach((c) => tr.append(el('td', null, c)));
    tbody.append(tr);
  });
  table.append(tbody);
  host.append(table);
}

/* 本地先校一遍，让用户在按下保存之前就知道哪里不对。
   服务端仍然会再校一次——两边都拦，任何一边失效都不会写进坏内容。 */
function checkDraft() {
  const box = $('case-check');
  box.replaceChildren();
  const text = $('case-json').value;
  let parsed;
  try {
    parsed = JSON.parse(text);
  } catch (e) {
    box.dataset.tone = 'bad';
    box.textContent = `JSON 还没写完：${e.message}`;
    return null;
  }

  const problems = [];
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
    problems.push('顶层要是一个对象');
  } else {
    if (parsed.schemaVersion !== 1) problems.push(`schemaVersion 需为 1，当前是 ${parsed.schemaVersion}`);
    if (!Array.isArray(parsed.cases) || !parsed.cases.length) problems.push('cases 至少要有一案');
    else {
      const ids = new Set();
      const codes = new Set();
      parsed.cases.forEach((c, i) => {
        const need = ['id', 'code', 'title', 'hook', 'meta', 'panels', 'evidence',
          'redHerring', 'verdict', 'lesson', 'quote', 'sourceIds', 'action', 'next'];
        need.forEach((k) => {
          if (c == null || c[k] === undefined || c[k] === null) problems.push(`cases[${i}].${k} 缺少`);
        });
        if (c && typeof c === 'object') {
          if (c.id && ids.has(c.id)) problems.push(`cases[${i}].id 重复：${c.id}`);
          if (c.id) ids.add(c.id);
          if (c.code && codes.has(c.code)) problems.push(`cases[${i}].code 重复：${c.code}`);
          if (c.code) codes.add(c.code);
          if (Array.isArray(c.panels)) {
            c.panels.forEach((p, j) => {
              if (p && p.kind && !['text', 'image'].includes(p.kind)) {
                problems.push(`cases[${i}].panels[${j}].kind 只认 text 或 image`);
              }
            });
          }
        }
      });
    }
  }

  box.dataset.tone = problems.length ? 'bad' : 'good';
  if (!problems.length) {
    box.textContent = `结构和字段都对得上（${caseCount(parsed)} 案）。服务端还会再校一遍，措辞红线也在那边查。`;
    return parsed;
  }
  box.textContent = `还有 ${problems.length} 处要改：`;
  const ul = el('ul');
  problems.slice(0, 8).forEach((p) => ul.append(el('li', null, p)));
  if (problems.length > 8) ul.append(el('li', null, `……另外 ${problems.length - 8} 处`));
  box.append(ul);
  return null;
}

/* ── 服务 ───────────────────────────────────────────── */

function paintSeal() {
  const overview = state.data.overview;
  const metrics = state.data.metrics;
  const facts = $('seal-facts');
  facts.replaceChildren();
  if (overview) {
    facts.append(factRow('模型', overview.modelConfigured ? '已配置' : '未配置', overview.modelConfigured ? 'good' : 'bad'));
    facts.append(factRow('存储', storageWord(overview.storage), null));
    facts.append(factRow('营养目录', `v${overview.catalogVersion}`, null));
    facts.append(factRow('营养数值可改', '否 · 随版本发布', null));
  }

  const host = $('metrics');
  if (!metrics) {
    host.replaceChildren(el('p', 'empty', '还没有读到指标。'));
    $('metrics-since').textContent = '';
    return;
  }
  const rows = (metrics.routes || []).map((r) => [
    r.path,
    String(r.requests),
    String(r.successes),
    String(r.failures),
    `${Number(r.averageMs).toFixed(1)} ms`,
    `${Number(r.maxMs).toFixed(0)} ms`,
  ]);
  paintTable(host, ['接口', '请求', '成功', '失败', '平均', '最慢'], rows, '这个实例还没有请求。');
  $('metrics-since').textContent =
    `统计起点 ${localDate(metrics.since)} ${clockText(metrics.since)} · 仅当前实例，重启清零。`;
}

/* ── 加载 ───────────────────────────────────────────── */

async function reload() {
  const epoch = state.epoch;
  const [overview, feedback, proposals, workAudit, cases, metrics] = await Promise.all([
    api('overview'),
    api('feedback'),
    api('proposals'),
    api('work-audit'),
    api('cases'),
    api('metrics'),
  ]);
  if (epoch !== state.epoch) throw new Error('管理连接已更新，请重新操作。');

  state.data.overview = overview;
  state.data.feedback = feedback.items || feedback.feedback || [];
  state.data.proposals = proposals.items || proposals.proposals || [];
  state.data.workAudit = workAudit.items || workAudit.audit || [];
  state.data.cases = cases;
  state.data.metrics = metrics;

  $('tag-model').hidden = !overview.modelConfigured;
  $('tag-model').textContent = overview.modelConfigured ? '模型已配置' : '模型未配置';

  const tableRows = state.data.workAudit.slice().reverse().map((a) => [
    ACTION[a.action] || a.action,
    a.type === 'proposal' ? '资料提案' : '反馈',
    a.id,
    `v${a.version}`,
    localDate(a.at) + ' ' + clockText(a.at),
  ]);
  paintTable($('work-audit'), ['动作', '类型', '编号', '版本', '时间'], tableRows, '还没有处理记录。');

  paintDesk();
  paintFeedback();
  paintProposals();
  paintCases();
  paintSeal();

  const lamp = $('lamp');
  lamp.dataset.state = overview.modelConfigured ? 'on' : 'warn';
  $('access-mode').textContent = overview.modelConfigured ? '已连接 · 模型正常' : '已连接 · 模型未配置';
}

/* ── 登录 ───────────────────────────────────────────── */

function showGate() {
  $('gate').hidden = false;
  $('workspace').hidden = true;
  $('btn-refresh').hidden = true;
  $('btn-logout').hidden = true;
  $('open-banner').hidden = true;
  $('lamp').dataset.state = 'off';
  $('access-mode').textContent = '未连接';
  $('token').focus();
}

function showDesk() {
  $('gate').hidden = true;
  $('workspace').hidden = false;
  $('btn-refresh').hidden = false;
  $('btn-logout').hidden = false;
}

async function connect(value) {
  if (state.busy) return;
  state.token = String(value || '').trim();
  state.epoch += 1;
  flash('正在读取工作区…');
  try {
    await reload();
    showDesk();
    /* 没有令牌却连上了，说明服务端关掉了校验——这件事必须显眼。 */
    $('open-banner').hidden = !!state.token;
    flash(state.token ? '已连接。' : '', false);
  } catch (e) {
    showGate();
    flash(e.message, true);
  }
}

/* ── 绑定 ───────────────────────────────────────────── */

function init() {
  const now = new Date();
  $('today').textContent = `${now.getMonth() + 1} 月 ${now.getDate()} 日`;
  $('clock').textContent = clockText();

  initTabs();

  $('gate').addEventListener('submit', (event) => {
    event.preventDefault();
    connect($('token').value);
  });

  $('btn-refresh').addEventListener('click', () => run(async () => {
    await reload();
    flash('已刷新。', false);
  }));

  $('btn-logout').addEventListener('click', () => {
    if (state.draft.dirty && !confirm('还有没保存的草稿改动，退出会丢掉这些输入。确认退出？')) return;
    state.token = '';
    state.epoch += 1;
    state.draft.dirty = false;
    showGate();
    flash('已退出。', false);
  });

  $('q-feedback').addEventListener('input', () => { state.page.feedback = 1; paintFeedback(); });
  $('f-feedback').addEventListener('change', () => { state.page.feedback = 1; paintFeedback(); });
  $('q-proposal').addEventListener('input', () => { state.page.proposal = 1; paintProposals(); });
  $('f-proposal').addEventListener('change', () => { state.page.proposal = 1; paintProposals(); });

  $('form-proposal').addEventListener('submit', (event) => {
    event.preventDefault();
    run(async () => {
      await api('proposals', 'POST', {
        name: $('p-name').value.trim(),
        aliases: $('p-aliases').value.trim(),
        sourceUrl: $('p-url').value.trim(),
        sourceNote: $('p-note').value.trim(),
      });
      event.target.reset();
      await reload();
      flash('提案已提交待审。', false);
    });
  });

  $('btn-goto-case').addEventListener('click', () => showView('cases'));

  /* 编辑器：改动只标脏，不自动存。草稿是用户的，不是我们的。 */
  $('case-json').addEventListener('input', () => {
    state.draft.dirty = true;
    state.draft.json = $('case-json').value;
    $('case-version').textContent = `未保存的改动 · 基于 v${state.draft.version}`;
    checkDraft();
  });

  $('btn-save-case').addEventListener('click', () => run(async () => {
    const parsed = checkDraft();
    if (!parsed) throw new Error('内容还有问题，先在下面那行里改完再存。');
    state.data.cases = await api('cases', 'PUT', { version: state.draft.version, bundle: parsed });
    state.draft.dirty = false;
    state.draft.version = state.data.cases.version;
    paintCases();
    paintDesk();
    paintCounts();
    flash('草稿已存。还没上线——要按「发布草稿」才生效。', false);
  }));

  $('btn-discard-case').addEventListener('click', () => {
    if (!state.draft.dirty) return;
    if (!confirm('放弃这次改动？会回到上次保存的草稿。')) return;
    state.draft.dirty = false;
    paintCases();
    checkDraft();
    flash('已回到上次保存的草稿。', false);
  });

  $('btn-publish-case').addEventListener('click', () => run(async () => {
    if (state.draft.dirty) throw new Error('还有没保存的改动。先存草稿，再发布。');
    const cases = state.data.cases;
    if (seamState(cases.draft, cases.published) === 'whole') {
      throw new Error('草稿和线上已经是同一版，没有要发布的内容。');
    }
    if (!confirm('发布后小程序会立刻读到这一版。确认发布？')) return;
    state.data.cases = await api('cases/publish', 'POST', { version: cases.version, targetVersion: 0 });
    paintCases();
    paintDesk();
    paintCounts();
    flash('已发布。小程序下次拉内容就是这个版本。', false);
  }));

  window.addEventListener('beforeunload', (event) => {
    if (!state.draft.dirty) return;
    event.preventDefault();
    event.returnValue = '';
  });

  /* 首屏先试一次无令牌连接：如果服务端关了校验，会直接进工作区。 */
  connect('');
}

document.addEventListener('DOMContentLoaded', init);
