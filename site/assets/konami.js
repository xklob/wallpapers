/* Include once with konami.css. A [data-konami-toggle] button is the touch entry point. */
(() => {
  const root = document.documentElement;
  const code = ["arrowup", "arrowup", "arrowdown", "arrowdown", "arrowleft", "arrowright", "arrowleft", "arrowright", "b", "a"];
  const entered = [];
  const editors = 'input, textarea, select, [contenteditable]:not([contenteditable="false"]), [role="textbox"], [role="slider"], [role="menu"], [role="tablist"], [role="radiogroup"], [role="spinbutton"]';
  function setRainbow(enabled) {
    root.classList.toggle("psychedelic", enabled);
    for (const button of document.querySelectorAll("[data-konami-toggle]")) button.setAttribute("aria-pressed", String(enabled));
    for (const message of document.querySelectorAll("[data-konami-status]")) message.textContent = enabled ? "Rainbow effect on. Press Escape to exit." : "Rainbow effect off.";
  }
  function toggle() {
    entered.length = 0;
    setRainbow(!root.classList.contains("psychedelic"));
  }
  document.addEventListener("click", event => {
    if (event.target.closest?.("[data-konami-toggle]")) toggle();
  });
  document.addEventListener("keydown", event => {
    if (event.repeat) return;
    if (event.defaultPrevented || event.isComposing || event.altKey || event.ctrlKey || event.metaKey || event.shiftKey || document.querySelector('dialog[open], [aria-haspopup][aria-expanded="true"]')) {
      entered.length = 0;
      return;
    }
    if (event.key === "Escape") {
      entered.length = 0;
      if (root.classList.contains("psychedelic")) setRainbow(false);
      return;
    }
    if (event.target.closest?.(editors)) {
      entered.length = 0;
      return;
    }
    entered.push(event.key.toLowerCase());
    while (entered.length && !entered.every((key, index) => key === code[index])) entered.shift();
    if (entered.length >= 2 && event.key.startsWith("Arrow")) event.preventDefault();
    if (entered.length === code.length) toggle();
  });
  window.addEventListener("blur", () => { entered.length = 0; });
})();
