// Shared by all pages of the Diffsert site: theme toggle (auto -> light -> dark), copy buttons on code blocks and
// the change explorer.
(function () {
  // ---------- theme toggle: auto -> light -> dark ----------
  var root = document.documentElement;
  var btn = document.getElementById("theme-btn");
  var modes = ["auto", "light", "dark"];
  function apply(mode) {
    if (mode === "auto") root.removeAttribute("data-theme"); else root.setAttribute("data-theme", mode);
    btn.textContent = mode;
    btn.setAttribute("aria-label", "Color theme: " + (mode === "auto" ? "system" : mode));
  }
  var saved = "auto";
  try { saved = localStorage.getItem("diffsert-theme") || "auto"; } catch (e) {}
  apply(modes.indexOf(saved) >= 0 ? saved : "auto");
  btn.addEventListener("click", function () {
    var next = modes[(modes.indexOf(btn.textContent) + 1) % modes.length];
    apply(next);
    try { localStorage.setItem("diffsert-theme", next); } catch (e) {}
  });

  // ---------- copy buttons ----------
  document.querySelectorAll(".code .copy").forEach(function (b) {
    b.addEventListener("click", function () {
      var pre = b.closest(".code").querySelector("pre");
      var text = pre.innerText;
      function done(label) { b.textContent = label; setTimeout(function () { b.textContent = "Copy"; }, 1600); }
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(text).then(function () { done("Copied"); }, function () { selectText(pre); done("Selected"); });
      } else { selectText(pre); done("Selected"); }
    });
  });
  function selectText(el) {
    var range = document.createRange();
    range.selectNodeContents(el);
    var sel = window.getSelection();
    sel.removeAllRanges();
    sel.addRange(range);
  }

  // ---------- change explorer: <figure class="explorer" data-scenarios="new metadata ..."> ----------
  // One stored customer and the next run's version of it, written with ignored jobRunId and _updatedAt, preserved
  // _createdAt and version field version. Outcomes and events were taken from MongoDB 7.0.
  // A row is [field, stored, new, after the write, comparison, event]; null is a missing field.
  // Comparison: "c" counts as a change, "s" differs but isn't compared, "m" moved, "k" kept by merge mode,
  // "n" equal as numbers. Event: "u" in updatedFields, "r" in removedFields, "i" in the inserted document.
  var D1 = "2026-10-01", D2 = "2026-10-08";
  var ROLES = { jobRunId: "ignored", _updatedAt: "ignored", _createdAt: "preserved", version: "version" };
  function base(changes) {
    var rows = [
      ["_id", '"c1"', '"c1"', '"c1"', "", ""],
      ["name", '"Acme"', '"Acme"', '"Acme"', "", ""],
      ["city", '"Berlin"', '"Berlin"', '"Berlin"', "", ""],
      ["tier", '"gold"', '"gold"', '"gold"', "", ""],
      ["jobRunId", '"run-1"', '"run-2"', '"run-2"', "s", "u"],
      ["_createdAt", D1, D2, D1, "s", ""],
      ["_updatedAt", D1, D2, D2, "s", "u"],
      ["version", "3", null, "4", "s", "u"]
    ];
    rows = rows.map(function (r) { return changes[r[0]] === undefined ? r : [r[0]].concat(changes[r[0]]); });
    (changes.$extra || []).forEach(function (r) { rows.push(r); });
    return rows;
  }
  // The stored document as it is: every compared field is equal.
  function untouched(rows) {
    return rows.map(function (r) {
      return [r[0], r[1], r[2], r[1], r[4] === "c" ? "c" : r[1] === r[2] ? "" : r[4] || "s", ""];
    });
  }
  var SCENARIOS = {
    "new": {
      tab: "New document",
      rows: base({
        _id: [null, '"c1"', '"c1"', "", "i"], name: [null, '"Acme"', '"Acme"', "", "i"],
        city: [null, '"Berlin"', '"Berlin"', "", "i"], tier: [null, '"gold"', '"gold"', "", "i"],
        jobRunId: [null, '"run-2"', '"run-2"', "", "i"], _createdAt: [null, D2, D2, "", "i"],
        _updatedAt: [null, D2, D2, "", "i"], version: [null, null, "0", "", "i"]
      }),
      outcome: "INSERTED", event: "insert · fullDocument: the new document",
      why: "There is no document with this _id yet, so the upsert inserts the new one. Preserved fields take the new value, and the version field starts at its initial value, 0."
    },
    metadata: {
      tab: "Only run metadata",
      rows: untouched(base({})),
      outcome: "UNCHANGED", event: "no write · no event",
      why: "Only ignored fields differ, and preserved and version fields aren't compared. The pipeline keeps the stored document as it is: matched, not modified, no event. The old jobRunId stays."
    },
    city: {
      tab: "city changed",
      rows: base({ city: ['"Berlin"', '"Hamburg"', '"Hamburg"', "c", "u"] }),
      outcome: "UPDATED", event: "update · updatedFields: city, jobRunId, _updatedAt, version",
      why: "city really changed, so the result is written: the ignored fields go along with it, _createdAt keeps its stored value and the version goes up by one. MongoDB logs only these fields."
    },
    removed: {
      tab: "Field removed",
      rows: base({ tier: ['"gold"', null, null, "c", "r"] }),
      outcome: "UPDATED", event: "update · updatedFields: jobRunId, _updatedAt, version · removedFields: tier",
      why: "In replace mode, the default, the new document is the full new state, so a field it leaves out is removed. Merge mode would keep it."
    },
    order: {
      tab: "Field order",
      rows: base({
        name: ['"Acme"', '"Acme"', '"Acme"', "m", "u", "now 3rd"],
        city: ['"Berlin"', '"Berlin"', '"Berlin"', "m", "", "now 2nd"],
        tier: ['"gold"', '"gold"', '"gold"', "", "u"],
        _createdAt: [D1, D2, D1, "s", "u"]
      }),
      outcome: "UPDATED", event: "update · updatedFields: name, tier, jobRunId, _createdAt, _updatedAt, version",
      why: "Same values, but city now comes before name. Field order counts, so the document is rewritten, and MongoDB lists every field after the moved one, even _createdAt with its unchanged value. Keep the field order stable."
    },
    created: {
      tab: "_createdAt missing",
      rows: untouched(base({ _createdAt: [D1, null, D1, "s", ""] })),
      outcome: "UNCHANGED", event: "no write · no event",
      why: "A preserved field that the new document leaves out keeps its stored value, so that alone is no change, wherever the stored document has the field."
    },
    nulls: {
      tab: "null vs missing",
      rows: base({ $extra: [["phone", "null", null, null, "c", "r"]] }),
      outcome: "UPDATED", event: "update · updatedFields: jobRunId, _updatedAt, version · removedFields: phone",
      why: "null is a value. A field that is null in the stored document and missing from the new one is a change, and the field is removed. Map null the same way on every run."
    },
    numbers: {
      tab: "int vs long",
      rows: untouched(base({ $extra: [["orders", "7 (int)", "7 (long)", "7 (int)", "n", ""]] })),
      outcome: "UNCHANGED", event: "no write · no event",
      why: "With ignored, preserved or version fields, the comparison treats equal numbers as equal, so nothing is written and the stored int stays. Without these options, the server's own diff writes the long."
    },
    merge: {
      tab: "Merge mode", merge: true,
      rows: base({
        city: ['"Berlin"', '"Hamburg"', '"Hamburg"', "c", "u"],
        tier: ['"gold"', null, '"gold"', "k", "", "kept"]
      }),
      outcome: "UPDATED", event: "update · updatedFields: city, jobRunId, _updatedAt, version",
      why: "In merge mode, the new document is merged into the stored one: tier, which the new document doesn't have, is kept. Use it when another job writes other fields of the same documents."
    }
  };

  function el(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text !== undefined) e.textContent = text;
    return e;
  }
  function value(v, cls) {
    // A missing field reads as a dash, unless its absence is the change
    if (v === null && cls === "change") return el("span", "ex-missing change", "missing");
    var e = el("span", (v === null ? "ex-missing" : "ex-v") + (cls ? " " + cls : ""), v === null ? "—" : v);
    if (v === null) e.appendChild(el("span", "sr", " missing"));
    return e;
  }

  document.querySelectorAll(".explorer").forEach(function (fig, n) {
    var keys = (fig.getAttribute("data-scenarios") || "").split(/\s+/).filter(function (k) { return SCENARIOS[k]; });
    if (!keys.length) return;
    var config = el("p", "ex-config");
    var tablist = el("div", "ex-tabs");
    tablist.setAttribute("role", "tablist");
    tablist.setAttribute("aria-label", "Scenarios");
    var panel = el("div", "ex-panel");
    panel.id = "ex" + n + "-panel";
    panel.setAttribute("role", "tabpanel");
    var tabs = keys.map(function (k) {
      var t = el("button", "ex-tab", SCENARIOS[k].tab);
      t.type = "button";
      t.id = "ex" + n + "-" + k;
      t.setAttribute("role", "tab");
      t.setAttribute("aria-controls", panel.id);
      t.addEventListener("click", function () { show(k); });
      tablist.appendChild(t);
      return t;
    });
    tablist.addEventListener("keydown", function (e) {
      var d = e.key === "ArrowRight" ? 1 : e.key === "ArrowLeft" ? -1 : 0;
      var i = tabs.indexOf(document.activeElement);
      if (!d || i < 0) return;
      var next = (i + d + tabs.length) % tabs.length;
      show(keys[next]);
      tabs[next].focus();
    });
    var legend = el("ul", "ex-legend");
    [["change", "counts as a change"], ["skip", "differs, not compared"], ["ev", "in the change event"],
      ["removed", "removed"]].forEach(function (l) {
      var li = el("li");
      li.appendChild(el("i", "ex-key " + l[0]));
      li.appendChild(document.createTextNode(l[1]));
      legend.appendChild(li);
    });
    fig.appendChild(config);
    fig.appendChild(tablist);
    fig.appendChild(panel);
    fig.appendChild(legend);

    function show(k) {
      var sc = SCENARIOS[k];
      tabs.forEach(function (t, i) {
        var on = keys[i] === k;
        t.setAttribute("aria-selected", on ? "true" : "false");
        t.tabIndex = on ? 0 : -1;
      });
      panel.setAttribute("aria-labelledby", "ex" + n + "-" + k);
      config.innerHTML = "";
      config.appendChild(el("b", null, sc.merge ? "merge mode" : "replace mode"));
      config.appendChild(document.createTextNode(" · ignored: jobRunId, _updatedAt · preserved: _createdAt · version field: version"));

      var table = el("table", "ex-table");
      var head = el("tr");
      ["Field", "Stored", "New", "After the write"].forEach(function (h) {
        var th = el("th", null, h);
        th.scope = "col";
        head.appendChild(th);
      });
      table.appendChild(el("thead")).appendChild(head);
      var body = el("tbody");
      var unchanged = sc.outcome === "UNCHANGED";
      sc.rows.forEach(function (r) {
        var tr = el("tr");
        var f = el("td", "ex-f");
        f.appendChild(el("span", "ex-name", r[0]));
        if (ROLES[r[0]]) f.appendChild(el("span", "ex-role", ROLES[r[0]]));
        tr.appendChild(f);
        var stored = el("td");
        stored.appendChild(value(r[1]));
        tr.appendChild(stored);
        var neu = el("td");
        var cmp = { c: "change", s: "skip", m: "change", k: "", n: "skip" }[r[4]] || "";
        neu.appendChild(value(r[2], r[1] === r[2] && r[4] !== "m" ? "" : cmp));
        if (r[4] === "m") neu.appendChild(el("span", "ex-note", r[6]));
        if (r[4] === "n") neu.appendChild(el("span", "ex-note", "equal as numbers"));
        tr.appendChild(neu);
        var after = el("td", unchanged ? "ex-after kept" : "ex-after");
        if (r[5] === "r") {
          after.appendChild(el("span", "ex-v removed", r[1]));
          after.appendChild(el("span", "ex-note", "removed"));
        } else {
          var v = value(r[3]);
          if (r[5]) v.classList.add("ev");
          after.appendChild(v);
          if (r[4] === "k") after.appendChild(el("span", "ex-note", r[6]));
        }
        tr.appendChild(after);
        body.appendChild(tr);
      });
      table.appendChild(body);

      panel.innerHTML = "";
      var scroll = el("div", "ex-scroll");
      scroll.appendChild(table);
      panel.appendChild(scroll);
      var verdict = el("div", "ex-verdict");
      var line = el("p", "ex-result");
      line.appendChild(el("span", "ex-outcome " + sc.outcome.toLowerCase(), sc.outcome));
      line.appendChild(el("span", "ex-event", sc.event));
      verdict.appendChild(line);
      verdict.appendChild(el("p", "ex-why", sc.why));
      panel.appendChild(verdict);
    }
    show(keys[Math.min(keys.length - 1, 2)]);
  });
})();
