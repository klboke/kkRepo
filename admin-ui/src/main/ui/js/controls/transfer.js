// Focus stays on the list when the domain module regenerates its option rows.
export function bindTransferKeyboard(list, select, move) {
  let active = 0;
  function focusOption(index) {
    const options = list.querySelectorAll('[role="option"]');
    active = Math.max(0, Math.min(index, options.length - 1));
    const option = options[active];
    if (!option) {
      list.removeAttribute("aria-activedescendant");
      return;
    }
    forgetOption();
    option.classList.add("is-current-option");
    option.id = list.id + "-active";
    list.setAttribute("aria-activedescendant", option.id);
    option.scrollIntoView({ block: "nearest" });
  }
  function forgetOption() {
    const option = list.querySelector(".is-current-option");
    if (option) {
      option.classList.remove("is-current-option");
      option.removeAttribute("id");
    }
    list.removeAttribute("aria-activedescendant");
  }
  list.addEventListener("pointerdown", event => {
    const row = event.target.closest('[role="option"]');
    if (row) active = Array.from(list.children).indexOf(row);
  });
  new MutationObserver(() => {
    if (document.activeElement === list) focusOption(active);
  }).observe(list, { childList: true });
  list.addEventListener("focus", () => focusOption(active));
  list.addEventListener("blur", forgetOption);
  list.addEventListener("keydown", event => {
    const options = list.querySelectorAll('[role="option"]');
    if (!options.length) return;
    switch (event.key) {
      case "ArrowDown": active++; break;
      case "ArrowUp": active--; break;
      case "Home": active = 0; break;
      case "End": active = options.length - 1; break;
      case " ":
        select(options[Math.min(active, options.length - 1)]);
        break;
      case "Enter":
        move(options[Math.min(active, options.length - 1)]);
        break;
      default: return;
    }
    event.preventDefault();
    forgetOption();
    focusOption(active);
  });
}
