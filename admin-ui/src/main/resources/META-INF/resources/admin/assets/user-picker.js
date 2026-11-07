(function (root) {
  "use strict";

  // Each dialog gets a fresh snapshot. No user catalog is retained across openings.
  function create({ source, user, status }) {
    let users = [], ready = false, locked = false, generation = 0;
    const sourceBox = combobox(source, () => [...new Set(users.map((entry) => entry.source))]
      .sort((a, b) => a === "Local" ? -1 : b === "Local" ? 1 : a.localeCompare(b))
      .map((value) => ({ value, label: value })), () => {
        userBox.set("");
        userBox.close();
        sync();
      });
    const userBox = combobox(user, () => users.filter((entry) => entry.source === sourceBox.value())
      .map((entry) => ({ value: entry.userId, label: entry.email ? `${entry.userId} · ${entry.email}` : entry.userId })), () => {});

    function sync() {
      source.disabled = !ready || locked;
      user.disabled = !ready || locked || !sourceBox.value();
    }

    async function open(owner) {
      const current = ++generation;
      users = [];
      ready = false;
      locked = Boolean(owner);
      sourceBox.set(owner?.source || "");
      userBox.set(owner?.userId || "");
      sourceBox.close();
      userBox.close();
      status.textContent = "";
      sync();
      // Rotation must also work when the owner is no longer in the user catalog.
      if (owner) { ready = true; return; }
      status.textContent = "Loading users…";
      try {
        const response = await fetch("/internal/security/users", { cache: "no-store" });
        if (!response.ok) throw new Error(await responseErrorMessage(response));
        const entries = await response.json();
        if (current !== generation) return;
        users = entries;
        ready = true;
        const sources = [...new Set(users.map((entry) => entry.source))];
        sourceBox.set(sources.includes("Local") ? "Local" : sources[0] || "");
        status.textContent = users.length ? "" : "No existing users available.";
      } catch (error) {
        if (current !== generation) return;
        status.textContent = `Failed to load users: ${error.message}`;
      } finally {
        if (current === generation) sync();
      }
    }

    function validate() {
      if (!ready) return false;
      const valid = locked || users.some((entry) => entry.source === sourceBox.value()
        && entry.userId === userBox.value());
      if (!valid) {
        status.textContent = "Select an existing user from the list.";
        (sourceBox.value() ? user : source).focus();
      }
      return valid;
    }

    function close() {
      generation++;
      ready = false;
      users = [];
      sourceBox.set("");
      userBox.set("");
      sourceBox.close();
      userBox.close();
      status.textContent = "";
    }
    return { open, close, validate, sync };
  }

  function combobox(input, candidates, changed) {
    const wrapper = document.createElement("div");
    wrapper.className = "user-picker";
    input.replaceWith(wrapper);
    wrapper.append(input);
    const list = document.createElement("div");
    list.className = "user-picker-options";
    list.id = `${input.id}-options`;
    list.setAttribute("role", "listbox");
    list.setAttribute("aria-label", input.getAttribute("aria-label") || input.id);
    list.hidden = true;
    wrapper.append(list);
    input.setAttribute("role", "combobox");
    input.setAttribute("aria-autocomplete", "list");
    input.setAttribute("aria-controls", list.id);
    input.setAttribute("aria-expanded", "false");
    input.autocomplete = "off";
    input.spellcheck = false;
    input.placeholder = "Search and select…";
    let selected = "", options = [], active = -1;

    function close() {
      list.hidden = true;
      input.setAttribute("aria-expanded", "false");
      input.removeAttribute("aria-activedescendant");
      active = -1;
    }
    function set(value) {
      selected = value;
      input.value = value;
      input.setCustomValidity(value ? "" : "Select an option from the list.");
    }
    function render() {
      if (input.disabled || input.readOnly) return;
      const query = selected ? "" : input.value.trim().toLocaleLowerCase();
      options = candidates().filter((option) => option.label.toLocaleLowerCase().includes(query)).slice(0, 50);
      list.replaceChildren();
      active = -1;
      input.removeAttribute("aria-activedescendant");
      for (const [index, option] of options.entries()) {
        const row = document.createElement("div");
        row.id = `${list.id}-${index}`;
        row.className = "user-picker-option";
        row.setAttribute("role", "option");
        row.setAttribute("aria-selected", String(option.value === selected));
        row.textContent = option.label;
        row.addEventListener("mousedown", (event) => event.preventDefault());
        row.addEventListener("click", () => choose(index));
        list.append(row);
      }
      if (!options.length) {
        const empty = document.createElement("div");
        empty.className = "form-note user-picker-empty";
        empty.textContent = "No matches. Refine your search.";
        list.append(empty);
      }
      list.hidden = false;
      input.setAttribute("aria-expanded", "true");
    }
    function choose(index) {
      if (!options[index] || input.disabled) return;
      set(options[index].value);
      close();
      changed();
    }
    input.addEventListener("focus", render);
    input.addEventListener("click", render);
    input.addEventListener("input", () => {
      selected = "";
      input.setCustomValidity("Select an option from the list.");
      changed();
      render();
    });
    input.addEventListener("blur", close);
    input.addEventListener("keydown", (event) => {
      if (event.key === "Escape" && !list.hidden) {
        event.preventDefault();
        event.stopPropagation();
        close();
      } else if (event.key === "ArrowDown" || event.key === "ArrowUp") {
        event.preventDefault();
        if (list.hidden) render();
        if (!options.length) return;
        active = (active + (event.key === "ArrowDown" ? 1 : -1) + options.length) % options.length;
        Array.from(list.children).forEach((row, index) => row.classList.toggle("is-active", index === active));
        input.setAttribute("aria-activedescendant", list.children[active].id);
        list.children[active].scrollIntoView({ block: "nearest" });
      } else if (event.key === "Enter" && !list.hidden) {
        event.preventDefault();
        if (active >= 0) choose(active);
        else if (options.length === 1) choose(0);
      } else if (event.key === "Tab") close();
    });
    return { set, close, value: () => selected };
  }

  root.KkUserPicker = { create };
  if (typeof module !== "undefined" && module.exports) module.exports = { create };
})(typeof window === "undefined" ? globalThis : window);
