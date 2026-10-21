// Shared by all pages of the Diffsert site: theme toggle (auto -> light -> dark) and copy buttons on code blocks.
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
})();
