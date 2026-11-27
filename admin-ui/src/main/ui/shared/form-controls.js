function passwordControl(input) {
  const field = document.createElement("div");
  field.className = "credential-field";
  input.replaceWith(field);
  const button = document.createElement("button");
  button.type = "button";
  button.className = "credential-toggle";
  button.setAttribute("aria-controls", input.id);
  function mask(hidden) {
    input.type = hidden ? "password" : "text";
    button.setAttribute("aria-label", hidden ? "Show password" : "Hide password");
    button.title = hidden ? "Show password" : "Hide password";
    button.textContent = hidden ? "Show" : "Hide";
  }
  mask(true);
  field.append(input, button);
  button.addEventListener("click", event => {
    event.preventDefault();
    if (!input.disabled) mask(input.type !== "password");
  });
  field.addEventListener("focusout", event => {
    if (!field.contains(event.relatedTarget) && input.type !== "password") mask(true);
  });
}

function updateFileSelection(input) {
  let selection = input.nextElementSibling;
  if (!selection?.classList.contains("file-selection")) {
    selection = document.createElement("span");
    selection.className = "file-selection";
    const size = document.createElement("span");
    size.setAttribute("role", "status");
    const clear = document.createElement("button");
    clear.type = "button";
    clear.className = "row-action";
    clear.textContent = "Clear";
    clear.addEventListener("click", event => {
      event.preventDefault();
      input.value = "";
      input.dispatchEvent(new Event("change", { bubbles: true }));
      input.focus();
    });
    selection.append(size, clear);
    input.after(selection);
  }
  const files = Array.from(input.files);
  selection.hidden = !files.length;
  const bytes = files.reduce((sum, file) => sum + file.size, 0);
  const large = bytes >= 1000000;
  selection.firstElementChild.textContent = new Intl.NumberFormat(document.documentElement.lang, {
    style: "unit", unit: large ? "megabyte" : "kilobyte", maximumFractionDigits: 1,
  }).format(bytes / (large ? 1000000 : 1000));
}

export function bindFormControls() {
  document.querySelectorAll('.view input[type="password"][autocomplete="new-password"]').forEach(passwordControl);
  document.addEventListener("change", event => {
    if (event.target.matches('.view input[type="file"]')) updateFileSelection(event.target);
  });
  document.addEventListener("reset", event => {
    for (const selection of event.target.querySelectorAll(".file-selection")) selection.hidden = true;
  });
}
