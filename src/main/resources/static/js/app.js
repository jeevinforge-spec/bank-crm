/* Banking CRM front end - plain JavaScript, no framework. Talks to the Spring REST API under /api. */
(() => {
    'use strict';

    // ------------------------------------------------------------------ helpers

    const $ = (sel, root = document) => root.querySelector(sel);
    const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];

    /** Escape anything that came from the server/CSV before putting it into innerHTML. */
    const esc = (v) => String(v ?? '').replace(/[&<>"']/g, (c) =>
        ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

    const fmtInt = (n) => Number(n ?? 0).toLocaleString();
    const fmtMoney = (n) => n == null ? '–' : Number(n).toLocaleString(undefined, { style: 'currency', currency: 'USD', maximumFractionDigits: 0 });
    const fmtMoney2 = (n) => n == null ? '–' : Number(n).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 });
    const fmtTime = (iso) => iso ? new Date(iso).toLocaleString() : '–';
    const fmtMs = (ms) => ms == null ? '–' : ms < 1000 ? `${ms} ms` : `${(ms / 1000).toFixed(1)} s`;
    const debounce = (fn, ms = 300) => { let t; return (...a) => { clearTimeout(t); t = setTimeout(() => fn(...a), ms); }; };

    const storage = {
        get(k, d) { try { return localStorage.getItem(k) ?? d; } catch { return d; } },
        set(k, v) { try { localStorage.setItem(k, v); } catch { /* private mode */ } },
    };

    function toast(message, isError = false) {
        const el = $('#toast');
        el.textContent = message;
        el.classList.toggle('error', isError);
        el.classList.add('show');
        clearTimeout(toast.timer);
        toast.timer = setTimeout(() => el.classList.remove('show'), 3500);
    }

    class ApiError extends Error {
        constructor(status, body) {
            super(body?.message || `Request failed (${status})`);
            this.status = status;
            this.errors = body?.errors || [];
        }
    }

    /** fetch wrapper: adds the operator header, parses JSON, throws ApiError on non-2xx. */
    async function api(path, { method = 'GET', body, form } = {}) {
        const headers = { 'X-User': $('#operator').value.trim() || 'system' };
        let payload;
        if (form) payload = form;
        else if (body !== undefined) { headers['Content-Type'] = 'application/json'; payload = JSON.stringify(body); }
        const res = await fetch(`api/${path}`, { method, headers, body: payload });
        const text = await res.text();
        const data = text ? JSON.parse(text) : null;
        if (!res.ok) throw new ApiError(res.status, data);
        return data;
    }

    const qs = (params) => new URLSearchParams(Object.entries(params).filter(([, v]) => v !== '' && v != null)).toString();

    const statusBadge = (s) => {
        const cls = { COMPLETED: 'ok', SUCCESS: 'ok', VERIFIED: 'ok', ACTIVE: 'ok', LOW: 'ok',
            RUNNING: 'info', QUEUED: 'info', PENDING: 'warn', MEDIUM: 'warn', PARTIAL: 'warn', DORMANT: 'warn',
            COMPLETED_WITH_ERRORS: 'warn', EXPIRED: 'warn', INACTIVE: 'warn',
            FAILED: 'bad', FAILURE: 'bad', REJECTED: 'bad', HIGH: 'bad', CLOSED: 'bad' }[s] || '';
        return `<span class="badge ${cls}">${esc(String(s ?? '').replaceAll('_', ' '))}</span>`;
    };

    function bars(container, entries, { format = fmtInt, labelFmt = (l) => l.replaceAll('_', ' ') } = {}) {
        if (!entries.length) { container.innerHTML = '<div class="empty">No data yet</div>'; return; }
        const max = Math.max(...entries.map(([, v]) => v), 1);
        container.innerHTML = entries.map(([label, value]) => `
            <div class="bar-row">
                <span>${esc(labelFmt(label))}</span>
                <div class="bar-track"><div class="bar-fill" style="width:${(value / max * 100).toFixed(1)}%"></div></div>
                <span class="num">${esc(format(value))}</span>
            </div>`).join('');
    }

    function pager(container, page, onGo) {
        const from = page.totalElements === 0 ? 0 : page.page * page.size + 1;
        const to = Math.min((page.page + 1) * page.size, page.totalElements);
        container.innerHTML = `
            <span>${fmtInt(from)}–${fmtInt(to)} of ${fmtInt(page.totalElements)}</span>
            <div>
                <button class="btn ghost small" data-go="${page.page - 1}" ${page.page <= 0 ? 'disabled' : ''}>‹ Prev</button>
                <button class="btn ghost small" data-go="${page.page + 1}" ${page.page + 1 >= page.totalPages ? 'disabled' : ''}>Next ›</button>
            </div>`;
        $$('[data-go]', container).forEach((b) => b.addEventListener('click', () => onGo(Number(b.dataset.go))));
    }

    // ------------------------------------------------------------------ routing

    const views = {};
    function route() {
        const name = (location.hash || '#dashboard').slice(1);
        const target = views[name] ? name : 'dashboard';
        $$('.view').forEach((v) => v.classList.toggle('active', v.id === `view-${target}`));
        $$('.nav-link').forEach((a) => a.classList.toggle('active', a.dataset.view === target));
        views[target].show();
    }

    // ------------------------------------------------------------------ metadata

    let meta = { accountTypes: [], kycStatuses: [], riskCategories: [], customerStatuses: [], auditActions: [] };
    let bulkConfig = { defaultThreads: 8, maxThreads: 16, defaultChunkSize: 500, maxChunkSize: 5000 };

    const fillSelect = (sel, values, keepFirst = true) => {
        const first = keepFirst ? sel.querySelector('option') : null;
        sel.innerHTML = '';
        if (first) sel.appendChild(first);
        values.forEach((v) => sel.insertAdjacentHTML('beforeend', `<option value="${esc(v)}">${esc(v.replaceAll('_', ' '))}</option>`));
    };

    // ------------------------------------------------------------------ dashboard

    views.dashboard = {
        async show() {
            try {
                const d = await api('dashboard');
                $('#kpi-customers').textContent = fmtInt(d.totalCustomers);
                $('#kpi-balance').textContent = fmtMoney(d.totalBalance);
                $('#kpi-audit').textContent = fmtInt(d.auditEvents);
                const verified = d.byKycStatus.VERIFIED || 0;
                $('#kpi-kyc').textContent = d.totalCustomers ? `${(verified / d.totalCustomers * 100).toFixed(1)}%` : '–';

                bars($('#dash-types'), d.byAccountType.map((t) => [t.accountType, t.count]));
                bars($('#dash-kyc'), Object.entries(d.byKycStatus).sort((a, b) => b[1] - a[1]));
                const riskOrder = ['LOW', 'MEDIUM', 'HIGH'];
                bars($('#dash-risk'), Object.entries(d.byRiskCategory).sort((a, b) => riskOrder.indexOf(a[0]) - riskOrder.indexOf(b[0])));
                renderJobsTable($('#dash-jobs'), d.recentJobs, (job) => { location.hash = '#bulk'; setTimeout(() => bulk.watch(job.jobId), 50); });
            } catch (e) { toast(e.message, true); }
        },
    };
    $('#dash-refresh').addEventListener('click', () => views.dashboard.show());

    function renderJobsTable(table, jobs, onClick) {
        if (!jobs.length) { table.innerHTML = '<tbody><tr><td class="empty">No bulk loads yet.</td></tr></tbody>'; return; }
        table.innerHTML = `
            <thead><tr><th>Started</th><th>Source</th><th>File</th><th>Status</th><th class="num">Inserted</th>
            <th class="num">Rejected</th><th class="num">Threads</th><th class="num">Duration</th><th class="num">Rows/s</th><th>By</th></tr></thead>
            <tbody>${jobs.map((j, i) => `
                <tr data-i="${i}">
                    <td>${esc(fmtTime(j.startedAt || j.createdAt))}</td>
                    <td>${esc(j.source.replace('_', ' '))}</td>
                    <td>${esc(j.fileName)}</td>
                    <td>${statusBadge(j.status)}</td>
                    <td class="num ok">${fmtInt(j.successCount)}</td>
                    <td class="num ${j.failureCount ? 'bad' : ''}">${fmtInt(j.failureCount)}</td>
                    <td class="num">${j.threadCount}</td>
                    <td class="num">${esc(fmtMs(j.elapsedMs))}</td>
                    <td class="num">${fmtInt(Math.round(j.recordsPerSecond))}</td>
                    <td>${esc(j.requestedBy)}</td>
                </tr>`).join('')}</tbody>`;
        $$('tbody tr[data-i]', table).forEach((tr) => tr.addEventListener('click', () => onClick(jobs[Number(tr.dataset.i)])));
    }

    // ------------------------------------------------------------------ customers

    const cust = {
        state: { page: 0, size: 25, sort: 'id', dir: 'desc' },
        columns: [
            ['customerNumber', 'Customer #'], ['lastName', 'Name'], ['email', 'Email'], ['city', 'City'],
            ['country', 'Country'], ['accountType', 'Account'], ['accountBalance', 'Balance', 'num'],
            ['creditScore', 'Score', 'num'], ['kycStatus', 'KYC'], ['riskCategory', 'Risk'], ['customerStatus', 'Status'],
        ],
        rows: [],

        async load() {
            const params = {
                q: $('#cust-q').value.trim(), accountType: $('#cust-type').value, kycStatus: $('#cust-kyc').value,
                riskCategory: $('#cust-risk').value, page: this.state.page, size: this.state.size,
                sort: this.state.sort, dir: this.state.dir,
            };
            try {
                const page = await api(`customers?${qs(params)}`);
                this.rows = page.content;
                this.render(page);
            } catch (e) { toast(e.message, true); }
        },

        render(page) {
            const arrow = (f) => this.state.sort === f ? (this.state.dir === 'asc' ? ' ▲' : ' ▼') : '';
            const table = $('#cust-table');
            table.innerHTML = `
                <thead><tr>${this.columns.map(([f, l, c]) => `<th class="sortable ${c || ''}" data-sort="${f}">${l}${arrow(f)}</th>`).join('')}</tr></thead>
                <tbody>${this.rows.length ? this.rows.map((c, i) => `
                    <tr data-i="${i}">
                        <td class="mono">${esc(c.customerNumber)}</td>
                        <td>${esc(c.firstName)} ${esc(c.lastName)}</td>
                        <td>${esc(c.email)}</td>
                        <td>${esc(c.city)}</td>
                        <td>${esc(c.country)}</td>
                        <td>${esc(c.accountType.replaceAll('_', ' '))}</td>
                        <td class="num">${fmtMoney2(c.accountBalance)}</td>
                        <td class="num">${esc(c.creditScore ?? '–')}</td>
                        <td>${statusBadge(c.kycStatus)}</td>
                        <td>${statusBadge(c.riskCategory)}</td>
                        <td>${statusBadge(c.customerStatus)}</td>
                    </tr>`).join('') : `<tr><td colspan="${this.columns.length}" class="empty">No customers match. Try Bulk load to add 10,000.</td></tr>`}
                </tbody>`;
            $$('th[data-sort]', table).forEach((th) => th.addEventListener('click', () => {
                const f = th.dataset.sort;
                this.state.dir = this.state.sort === f && this.state.dir === 'asc' ? 'desc' : 'asc';
                this.state.sort = f;
                this.state.page = 0;
                this.load();
            }));
            $$('tbody tr[data-i]', table).forEach((tr) => tr.addEventListener('click', () => custModal.open(this.rows[Number(tr.dataset.i)])));
            pager($('#cust-pager'), page, (p) => { this.state.page = p; this.load(); });
        },
    };
    views.customers = { show: () => cust.load() };

    const reloadCustomers = debounce(() => { cust.state.page = 0; cust.load(); });
    $('#cust-q').addEventListener('input', reloadCustomers);
    ['#cust-type', '#cust-kyc', '#cust-risk'].forEach((s) => $(s).addEventListener('change', reloadCustomers));
    $('#cust-new').addEventListener('click', () => custModal.open(null));
    $('#cust-purge').addEventListener('click', async () => {
        if (!confirm('Delete ALL customers? This is recorded in the audit trail and cannot be undone.')) return;
        try {
            const r = await api('customers?confirm=true', { method: 'DELETE' });
            toast(`Deleted ${fmtInt(r.deleted)} customers`);
            cust.load();
        } catch (e) { toast(e.message, true); }
    });

    const custModal = {
        dialog: $('#cust-modal'),
        form: $('#cust-form'),
        current: null,

        open(customer) {
            this.current = customer;
            this.form.reset();
            $('#cust-errors').classList.add('hidden');
            $('#cust-modal-title').textContent = customer ? `${customer.firstName} ${customer.lastName}` : 'New customer';
            $('#cust-delete').classList.toggle('hidden', !customer);
            this.form.customerNumber.readOnly = !!customer;
            if (customer) {
                Object.entries(customer).forEach(([k, v]) => { const el = this.form.elements.namedItem(k); if (el) el.value = v ?? ''; });
                $('#cust-meta').textContent = `Created ${fmtTime(customer.createdAt)} by ${customer.createdBy} · Updated ${fmtTime(customer.updatedAt)} by ${customer.updatedBy}`
                    + (customer.bulkJobId ? ` · Loaded by bulk job ${customer.bulkJobId.slice(0, 8)}` : '') + ` · v${customer.version}`;
            } else {
                this.form.kycStatus.value = 'PENDING';
                this.form.riskCategory.value = 'LOW';
                this.form.customerStatus.value = 'ACTIVE';
                this.form.accountType.value = 'SAVINGS';
                $('#cust-meta').textContent = '';
            }
            this.dialog.showModal();
        },

        payload() {
            const f = this.form.elements;
            const val = (n) => f.namedItem(n).value.trim() || null;
            const num = (n) => f.namedItem(n).value === '' ? null : Number(f.namedItem(n).value);
            return {
                customerNumber: val('customerNumber'), firstName: val('firstName'), lastName: val('lastName'),
                email: val('email'), phone: val('phone'), dateOfBirth: val('dateOfBirth'), addressLine: val('addressLine'),
                city: val('city'), state: val('state'), postalCode: val('postalCode'), country: val('country'),
                accountType: val('accountType'), accountBalance: num('accountBalance'), annualIncome: num('annualIncome'),
                creditScore: num('creditScore'), kycStatus: val('kycStatus'), riskCategory: val('riskCategory'),
                customerStatus: val('customerStatus'), branchCode: val('branchCode'),
                version: this.current ? this.current.version : null,
            };
        },

        showError(e) {
            const box = $('#cust-errors');
            box.innerHTML = esc(e.message) + (e.errors?.length ? `<ul>${e.errors.map((x) => `<li>${esc(x)}</li>`).join('')}</ul>` : '');
            box.classList.remove('hidden');
            box.scrollIntoView({ block: 'nearest' });
        },
    };

    custModal.form.addEventListener('submit', async (ev) => {
        ev.preventDefault();
        const btn = $('#cust-save');
        btn.disabled = true;
        try {
            const c = custModal.current;
            const saved = c
                ? await api(`customers/${c.id}`, { method: 'PUT', body: custModal.payload() })
                : await api('customers', { method: 'POST', body: custModal.payload() });
            custModal.dialog.close();
            toast(`${c ? 'Updated' : 'Created'} ${saved.customerNumber}`);
            cust.load();
        } catch (e) { custModal.showError(e); } finally { btn.disabled = false; }
    });
    $('#cust-delete').addEventListener('click', async () => {
        const c = custModal.current;
        if (!c || !confirm(`Delete customer ${c.customerNumber}?`)) return;
        try {
            await api(`customers/${c.id}`, { method: 'DELETE' });
            custModal.dialog.close();
            toast(`Deleted ${c.customerNumber}`);
            cust.load();
        } catch (e) { custModal.showError(e); }
    });

    $$('[data-close]').forEach((b) => b.addEventListener('click', () => b.closest('dialog').close()));

    // ------------------------------------------------------------------ bulk load

    const bulk = {
        pollTimer: null,
        watchingId: null,

        async show() {
            this.loadHistory();
            if (!this.watchingId) {
                // resume watching a job that's still running (e.g. after navigating away)
                try {
                    const jobs = await api('bulk/jobs?limit=5');
                    const running = jobs.find((j) => !isFinished(j.status));
                    if (running) this.watch(running.jobId);
                } catch { /* ignore */ }
            }
        },

        async loadHistory() {
            try {
                const jobs = await api('bulk/jobs?limit=20');
                renderJobsTable($('#jobs-table'), jobs, (job) => this.watch(job.jobId));
            } catch (e) { toast(e.message, true); }
        },

        async start(path, options) {
            try {
                const job = await api(path, options);
                toast(`Job ${job.jobId.slice(0, 8)} accepted – loading in background`);
                this.watch(job.jobId);
            } catch (e) { toast(e.message, true); }
        },

        watch(jobId) {
            this.watchingId = jobId;
            clearTimeout(this.pollTimer);
            $('#job-errors-wrap').classList.add('hidden');
            $('#job-panel').classList.remove('hidden');
            $('#job-panel').scrollIntoView({ behavior: 'smooth', block: 'nearest' });
            this.poll();
        },

        async poll() {
            const id = this.watchingId;
            try {
                const job = await api(`bulk/jobs/${id}`);
                if (id !== this.watchingId) return;
                this.renderJob(job);
                if (isFinished(job.status)) {
                    this.loadHistory();
                    if (job.failureCount > 0) this.loadErrors(job);
                } else {
                    this.pollTimer = setTimeout(() => this.poll(), 400);
                }
            } catch (e) { toast(e.message, true); }
        },

        renderJob(j) {
            $('#job-title').textContent = `${j.source === 'GENERATED' ? 'Generated load' : 'CSV upload'} · ${j.fileName}`;
            $('#job-id').textContent = `job ${j.jobId} · by ${j.requestedBy}`;
            $('#job-status').outerHTML = statusBadge(j.status).replace('<span', '<span id="job-status"');
            const pct = j.totalRecords ? Math.min(100, j.processedRecords / j.totalRecords * 100) : (isFinished(j.status) ? 100 : 0);
            const bar = $('#job-bar');
            bar.style.width = `${pct}%`;
            bar.classList.toggle('done', j.status === 'COMPLETED' || j.status === 'COMPLETED_WITH_ERRORS');
            bar.classList.toggle('failed', j.status === 'FAILED');
            $('#job-processed').textContent = `${fmtInt(j.processedRecords)} / ${fmtInt(j.totalRecords)}`;
            $('#job-success').textContent = fmtInt(j.successCount);
            $('#job-failed').textContent = fmtInt(j.failureCount);
            $('#job-rate').textContent = `${fmtInt(Math.round(j.recordsPerSecond))}/s`;
            $('#job-elapsed').textContent = fmtMs(j.elapsedMs);
            $('#job-config').textContent = `${j.threadCount} × ${fmtInt(j.chunkSize)}`;
            bars($('#job-threads'), Object.entries(j.threadStats || {}), { labelFmt: (l) => l });
            const err = $('#job-error-msg');
            err.textContent = j.errorMessage || '';
            err.classList.toggle('hidden', !j.errorMessage);
        },

        async loadErrors(job) {
            try {
                const errors = await api(`bulk/jobs/${job.jobId}/errors?limit=500`);
                $('#job-errors-note').textContent = errors.length < job.failureCount
                    ? `(showing ${fmtInt(errors.length)} of ${fmtInt(job.failureCount)})` : `(${fmtInt(errors.length)})`;
                $('#job-errors').innerHTML = `
                    <thead><tr><th class="num">Line</th><th>Customer #</th><th>Reason</th></tr></thead>
                    <tbody>${errors.map((e) => `
                        <tr title="${esc(e.rawData)}">
                            <td class="num">${e.rowNumber}</td>
                            <td class="mono">${esc(e.customerNumber ?? '–')}</td>
                            <td class="wrap">${esc(e.errorMessage)}</td>
                        </tr>`).join('')}</tbody>`;
                $('#job-errors-wrap').classList.remove('hidden');
            } catch (e) { toast(e.message, true); }
        },
    };
    views.bulk = { show: () => bulk.show() };
    const isFinished = (s) => ['COMPLETED', 'COMPLETED_WITH_ERRORS', 'FAILED'].includes(s);

    $('#gen-form').addEventListener('submit', (ev) => {
        ev.preventDefault();
        const f = ev.target;
        bulk.start(`bulk/generate?${qs({ rows: f.rows.value, invalidPercent: f.invalidPercent.value, threads: f.threads.value, chunkSize: f.chunkSize.value })}`, { method: 'POST' });
    });

    $('#upload-form').addEventListener('submit', (ev) => {
        ev.preventDefault();
        const f = ev.target;
        if (!f.file.files.length) { toast('Choose a CSV file first', true); return; }
        const data = new FormData();
        data.append('file', f.file.files[0]);
        bulk.start(`bulk/upload?${qs({ threads: f.threads.value, chunkSize: f.chunkSize.value })}`, { method: 'POST', form: data });
    });

    const fileInput = $('#upload-form input[type=file]');
    const drop = $('#file-drop');
    fileInput.addEventListener('change', () => {
        const file = fileInput.files[0];
        $('#file-label').textContent = file ? `${file.name} (${(file.size / 1024).toFixed(0)} KB)` : 'Choose or drop a .csv file';
    });
    ['dragenter', 'dragover'].forEach((e) => drop.addEventListener(e, () => drop.classList.add('drag')));
    ['dragleave', 'drop'].forEach((e) => drop.addEventListener(e, () => drop.classList.remove('drag')));
    $('#jobs-refresh').addEventListener('click', () => bulk.loadHistory());

    // ------------------------------------------------------------------ audit

    const audit = {
        state: { page: 0, size: 50 },
        rows: [],

        async load() {
            const params = { action: $('#audit-action').value, user: $('#audit-user').value.trim(),
                jobId: $('#audit-job').value.trim(), page: this.state.page, size: this.state.size };
            try {
                const page = await api(`audit?${qs(params)}`);
                this.rows = page.content;
                this.render(page);
            } catch (e) { toast(e.message, true); }
        },

        render(page) {
            const table = $('#audit-table');
            table.innerHTML = `
                <thead><tr><th>Time</th><th>Action</th><th>Entity</th><th>Performed by</th><th>Outcome</th><th>Details</th></tr></thead>
                <tbody>${this.rows.length ? this.rows.map((a, i) => `
                    <tr data-i="${i}">
                        <td>${esc(fmtTime(a.eventTime))}</td>
                        <td><span class="badge info">${esc(a.action.replaceAll('_', ' '))}</span></td>
                        <td>${esc(a.entityType)}${a.entityId ? ` <span class="muted mono">#${esc(a.entityId.slice(0, 12))}</span>` : ''}</td>
                        <td>${esc(a.performedBy)}</td>
                        <td>${statusBadge(a.outcome)}</td>
                        <td class="wrap">${esc(a.details)}</td>
                    </tr>`).join('') : '<tr><td colspan="6" class="empty">No audit events match.</td></tr>'}
                </tbody>`;
            $$('tbody tr[data-i]', table).forEach((tr) => tr.addEventListener('click', () => this.detail(this.rows[Number(tr.dataset.i)])));
            pager($('#audit-pager'), page, (p) => { this.state.page = p; this.load(); });
        },

        detail(a) {
            let changes = '';
            if (a.changes) {
                let pretty = a.changes;
                try { pretty = JSON.stringify(JSON.parse(a.changes), null, 2); } catch { /* keep raw */ }
                changes = `<h4>${a.action === 'UPDATE' ? 'Field changes' : 'Data'}</h4><pre class="json">${esc(pretty)}</pre>`;
            }
            $('#audit-detail').innerHTML = `
                <dl class="kv">
                    <dt>Event id</dt><dd class="mono">${a.id}</dd>
                    <dt>Time</dt><dd>${esc(fmtTime(a.eventTime))}</dd>
                    <dt>Action</dt><dd>${esc(a.action)}</dd>
                    <dt>Outcome</dt><dd>${statusBadge(a.outcome)}</dd>
                    <dt>Entity</dt><dd>${esc(a.entityType)} ${esc(a.entityId ?? '')}</dd>
                    <dt>Performed by</dt><dd>${esc(a.performedBy)}</dd>
                    <dt>Client IP</dt><dd class="mono">${esc(a.clientIp ?? '–')}</dd>
                    <dt>Job</dt><dd class="mono">${esc(a.jobId ?? '–')}</dd>
                    <dt>Details</dt><dd>${esc(a.details)}</dd>
                </dl>${changes}`;
            $('#audit-modal').showModal();
        },
    };
    views.audit = { show: () => audit.load() };
    const reloadAudit = debounce(() => { audit.state.page = 0; audit.load(); });
    $('#audit-action').addEventListener('change', reloadAudit);
    $('#audit-user').addEventListener('input', reloadAudit);
    $('#audit-job').addEventListener('input', reloadAudit);
    $('#audit-refresh').addEventListener('click', () => audit.load());

    // ------------------------------------------------------------------ boot

    async function init() {
        const op = $('#operator');
        op.value = storage.get('crm.operator', 'demo.user');
        op.addEventListener('change', () => storage.set('crm.operator', op.value.trim()));

        try {
            [meta, bulkConfig] = await Promise.all([api('meta'), api('bulk/config')]);
        } catch (e) { toast(`Could not reach the server: ${e.message}`, true); }

        fillSelect($('#cust-type'), meta.accountTypes);
        fillSelect($('#cust-kyc'), meta.kycStatuses);
        fillSelect($('#cust-risk'), meta.riskCategories);
        fillSelect($('#audit-action'), meta.auditActions);
        fillSelect(custModal.form.accountType, meta.accountTypes, false);
        fillSelect(custModal.form.kycStatus, meta.kycStatuses, false);
        fillSelect(custModal.form.riskCategory, meta.riskCategories, false);
        fillSelect(custModal.form.customerStatus, meta.customerStatuses, false);

        $$('input[name=threads]').forEach((i) => { i.value = bulkConfig.defaultThreads; i.max = bulkConfig.maxThreads; });
        $$('input[name=chunkSize]').forEach((i) => { i.value = bulkConfig.defaultChunkSize; i.max = bulkConfig.maxChunkSize; });
        $('#gen-form').rows.max = bulkConfig.maxGeneratedRows;

        window.addEventListener('hashchange', route);
        route();
    }

    init();
})();
