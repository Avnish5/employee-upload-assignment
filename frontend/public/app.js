"use strict";

const POLL_MS = 1000;
const PAGE_SIZE = 50;

const $ = (id) => document.getElementById(id);

// ---------- helpers ----------

async function getJson(url) {
    const res = await fetch(url);
    const body = await res.json().catch(() => ({}));
    if (!res.ok) throw new Error(body.message || `Request failed (${res.status})`);
    return body;
}

/** Creates an element; children may be strings (inserted as text, never HTML) or nodes. */
function el(tag, attrs = {}, ...children) {
    const node = document.createElement(tag);
    for (const [k, v] of Object.entries(attrs)) {
        if (k === "class") node.className = v;
        else if (k.startsWith("on")) node.addEventListener(k.slice(2), v);
        else node.setAttribute(k, v);
    }
    for (const c of children) node.append(c ?? "");
    return node;
}

const fmt = (n) => Number(n || 0).toLocaleString();

// ---------- upload + status ----------

let pollTimer = null;

$("upload-form").addEventListener("submit", (e) => {
    e.preventDefault();
    const file = $("file").files[0];
    if (!file) return;
    startUpload(file);
});

/** Uses XHR (not fetch) so the browser-to-server transfer itself shows progress. */
function startUpload(file) {
    clearTimeout(pollTimer);
    $("upload-error").hidden = true;
    $("upload-btn").disabled = true;
    renderStatus({ filename: file.name, status: "UPLOADING", totalRows: 0, processedRows: 0 });

    const form = new FormData();
    form.append("file", file);
    const xhr = new XMLHttpRequest();
    xhr.open("POST", "/api/uploads");
    xhr.upload.onprogress = (e) => {
        if (e.lengthComputable) {
            const pct = Math.round((e.loaded / e.total) * 100);
            setProgress(pct);
            $("status-text").textContent = `Sending file… ${pct}%`;
        }
    };
    xhr.onload = () => {
        $("upload-btn").disabled = false;
        let body = {};
        try { body = JSON.parse(xhr.responseText); } catch { /* non-JSON error page */ }
        if (xhr.status === 202) {
            $("file").value = "";
            renderStatus(body);
            poll(body.id);
            loadHistory();
        } else {
            $("status").hidden = true;
            showUploadError(body.message || `Upload failed (HTTP ${xhr.status}).`);
        }
    };
    xhr.onerror = () => {
        $("upload-btn").disabled = false;
        $("status").hidden = true;
        showUploadError("Network error: could not reach the server.");
    };
    xhr.send(form);
}

function showUploadError(message) {
    $("upload-error").textContent = message;
    $("upload-error").hidden = false;
}

async function poll(id) {
    try {
        const upload = await getJson(`/api/uploads/${id}`);
        renderStatus(upload);
        if (upload.status === "COMPLETED" || upload.status === "FAILED") {
            loadHistory();
            loadEmployees();
            loadDepartments();
            return;
        }
    } catch (err) {
        $("status-text").textContent = `Could not refresh status (${err.message}); retrying…`;
    }
    pollTimer = setTimeout(() => poll(id), POLL_MS);
}

function setProgress(pct) {
    $("progress-fill").style.width = `${pct}%`;
    $("progress").setAttribute("aria-valuenow", String(pct));
}

function renderStatus(u) {
    $("status").hidden = false;
    $("status-file").textContent = u.filename;
    $("status-badge").textContent = u.status;
    $("status-badge").className = `badge ${u.status}`;

    if (u.status !== "UPLOADING") {
        const pct = u.totalRows ? Math.round((u.processedRows / u.totalRows) * 100) : 0;
        setProgress(u.status === "COMPLETED" ? 100 : pct);
        const text = {
            QUEUED: `Queued: waiting for earlier uploads to finish. ${fmt(u.totalRows)} rows to process.`,
            PROCESSING: `Processing… ${fmt(u.processedRows)} of ${fmt(u.totalRows)} rows (${pct}%)`,
            COMPLETED: `Finished: ${fmt(u.processedRows)} rows processed.`,
            FAILED: "Processing failed.",
        }[u.status];
        $("status-text").textContent = text;
    }

    $("c-inserted").textContent = fmt(u.insertedRows);
    $("c-skipped").textContent = fmt(u.skippedRows);
    $("c-rejected").textContent = fmt(u.rejectedRows);

    const msg = $("status-message");
    msg.hidden = !u.errorMessage;
    msg.textContent = u.errorMessage || "";

    const link = $("report-link");
    const done = u.status === "COMPLETED" || u.status === "FAILED";
    link.hidden = !(done && u.rejectedRows > 0);
    if (!link.hidden) {
        link.href = `/api/uploads/${u.id}/errors.csv`;
        link.textContent = `Download error report (${fmt(u.rejectedRows)} rows)`;
    }
}

// ---------- history ----------

async function loadHistory() {
    try {
        const uploads = await getJson("/api/uploads?limit=20");
        $("history-body").replaceChildren(...uploads.map((u) => el("tr",
            { class: "clickable", title: "Show status", onclick: () => { clearTimeout(pollTimer); renderStatus(u); poll(u.id); } },
            el("td", {}, String(u.id)),
            el("td", {}, u.filename),
            el("td", {}, u.source === "PRELOAD" ? "Preload" : "Upload"),
            el("td", {}, el("span", { class: `badge ${u.status}` }, u.status)),
            el("td", {}, fmt(u.totalRows)),
            el("td", {}, fmt(u.insertedRows)),
            el("td", {}, fmt(u.skippedRows)),
            el("td", {}, fmt(u.rejectedRows)),
            el("td", {}, u.rejectedRows > 0 && (u.status === "COMPLETED" || u.status === "FAILED")
                ? el("a", { href: `/api/uploads/${u.id}/errors.csv`, onclick: (e) => e.stopPropagation() }, "CSV")
                : "—"),
        )));
    } catch (err) {
        $("history-body").replaceChildren(el("tr", {}, el("td", { colspan: "9", class: "error" }, err.message)));
    }
}

// ---------- employees ----------

let page = 0;

async function loadEmployees() {
    const params = new URLSearchParams({ page, size: PAGE_SIZE });
    const q = $("q").value.trim();
    const dept = $("department").value;
    if (q) params.set("q", q);
    if (dept) params.set("department", dept);
    try {
        const data = await getJson(`/api/employees?${params}`);
        const pages = Math.max(1, Math.ceil(data.total / PAGE_SIZE));
        $("emp-total").textContent = `(${fmt(data.total)})`;
        $("page-info").textContent = `Page ${page + 1} of ${pages}`;
        $("prev").disabled = page === 0;
        $("next").disabled = page + 1 >= pages;
        $("emp-body").replaceChildren(...(data.items.length ? data.items.map((e) => el("tr", {},
            el("td", {}, `${e.firstName} ${e.lastName}`),
            el("td", {}, e.dob),
            el("td", {}, e.gender),
            el("td", {}, e.mobile),
            el("td", {}, e.email || "—"),
            el("td", {}, e.department),
            el("td", {}, e.designation),
            el("td", {}, e.dateOfJoining),
        )) : [el("tr", {}, el("td", { colspan: "8", class: "muted" }, "No employees match."))]));
    } catch (err) {
        $("emp-body").replaceChildren(el("tr", {}, el("td", { colspan: "8", class: "error" }, err.message)));
    }
}

async function loadDepartments() {
    try {
        const current = $("department").value;
        const depts = await getJson("/api/employees/departments");
        $("department").replaceChildren(el("option", { value: "" }, "All departments"),
            ...depts.map((d) => el("option", { value: d }, d)));
        $("department").value = depts.includes(current) ? current : "";
    } catch { /* filter stays as-is */ }
}

let searchTimer = null;
$("q").addEventListener("input", () => {
    clearTimeout(searchTimer);
    searchTimer = setTimeout(() => { page = 0; loadEmployees(); }, 300);
});
$("department").addEventListener("change", () => { page = 0; loadEmployees(); });
$("filter-form").addEventListener("submit", (e) => e.preventDefault());
$("prev").addEventListener("click", () => { page = Math.max(0, page - 1); loadEmployees(); });
$("next").addEventListener("click", () => { page += 1; loadEmployees(); });

// ---------- init ----------

loadHistory();
loadEmployees();
loadDepartments();
