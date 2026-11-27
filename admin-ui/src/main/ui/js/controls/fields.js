import { showToast } from "./toast.js";

function commaList(value) {
  return String(value || "")
    .split(",")
    .map((item) => item.trim())
    .filter(Boolean);
}

function setCommaList(id, values) {
  document.getElementById(id).value = Array.isArray(values) ? values.join(", ") : "";
}

function parseJsonObject(id) {
  const input = document.getElementById(id);
  const text = input.value.trim();
  input.classList.remove("is-invalid");
  input.setAttribute("aria-invalid", "false");
  if (!text) return {};
  try {
    const parsed = JSON.parse(text);
    if (!parsed || Array.isArray(parsed) || typeof parsed !== "object") {
      throw new Error("JSON must be an object");
    }
    return parsed;
  } catch (error) {
    input.classList.add("is-invalid");
    input.setAttribute("aria-invalid", "true");
    input.closest("details")?.setAttribute("open", "");
    showToast(`Invalid JSON: ${error.message}`, "error");
    throw error;
  }
}

function markInputValidity(input, invalid) {
  input.classList.toggle("is-invalid", invalid);
  input.setAttribute("aria-invalid", String(invalid));
  if (input.id === "repository-recipe") {
    const trigger = document.getElementById("repository-recipe-trigger");
    trigger?.classList.toggle("is-invalid", invalid);
    trigger?.setAttribute("aria-invalid", String(invalid));
  }
}

function fieldIsRequired(field) {
  return field.required ? Boolean(field.required(field)) : true;
}

function fieldValue(input) {
  if (!input) return "";
  if (input.tagName === "SELECT") return input.value;
  return String(input.value || "").trim();
}

function updateRequiredMarker(field) {
  const input = document.getElementById(field.id);
  if (!input) return;
  const marker = input.closest("label, .form-field")?.querySelector(".required-mark");
  if (!marker) return;
  marker.hidden = !fieldIsRequired(field);
}

function updateRequiredMarkers(fields) {
  fields.forEach(updateRequiredMarker);
}

function setFieldRequired(input, required) {
  if (!input) return;
  if (required) {
    input.setAttribute("required", "");
    input.setAttribute("aria-required", "true");
  } else {
    input.removeAttribute("required");
    input.removeAttribute("aria-required");
  }
}

function validateRequiredFields(fields, options = {}) {
  const missing = [];
  let firstMissingInput = null;
  fields.forEach((field) => {
    const input = document.getElementById(field.id);
    if (!input) return;
    const required = fieldIsRequired(field);
    setFieldRequired(input, required);
    updateRequiredMarker(field);
    const invalid = required && !input.disabled && !fieldValue(input);
    markInputValidity(input, invalid);
    if (invalid) {
      missing.push(field.label);
      firstMissingInput = firstMissingInput
        || (input.id === "repository-recipe"
          ? document.getElementById("repository-recipe-trigger")
          : input);
    }
  });
  if (missing.length > 0) {
    showToast(`${options.prefix || "Required fields missing"}: ${missing.join(", ")}`, "error");
    firstMissingInput?.focus();
    return false;
  }
  return true;
}

function clearRequiredFieldError(event) {
  if (fieldValue(event.target)) {
    markInputValidity(event.target, false);
  }
}

function bindRequiredFieldErrors(fields) {
  fields.forEach((field) => {
    const input = document.getElementById(field.id);
    if (input && !input.dataset.requiredErrorBound) {
      const field = input.closest("label, .form-field");
      if (field) {
        const error = document.createElement("small");
        error.id = input.id + "-required-error";
        error.className = "field-error";
        error.textContent = "Required";
        field.append(error);
        const target = input.id === "repository-recipe"
          ? document.getElementById("repository-recipe-trigger") : input;
        target.setAttribute("aria-errormessage", error.id);
        input.dataset.requiredErrorBound = "true";
      }
    }
    input?.addEventListener("input", clearRequiredFieldError);
    input?.addEventListener("change", clearRequiredFieldError);
  });
}

function clearRequiredFieldErrors(fields) {
  fields.forEach((field) => {
    const input = document.getElementById(field.id);
    if (input) markInputValidity(input, false);
  });
}

function filterValue(id) {
  const input = document.getElementById(id);
  return input ? input.value.trim().toLowerCase() : "";
}

function textInputValue(id) {
  const input = document.getElementById(id);
  if (!input) return null;
  const value = input.value.trim();
  return value || null;
}

function numberInputValue(id) {
  const value = textInputValue(id);
  return value == null ? null : Number(value);
}

function setInputValue(id, value, fallback = "") {
  document.getElementById(id).value = value ?? fallback;
}

function setSelectValue(id, value, fallback) {
  const select = document.getElementById(id);
  select.value = value || fallback;
}

function setCheckboxValue(id, value, fallback = false) {
  document.getElementById(id).checked = value == null ? fallback : Boolean(value);
}

export {
  bindRequiredFieldErrors,
  clearRequiredFieldErrors,
  commaList,
  filterValue,
  markInputValidity,
  numberInputValue,
  parseJsonObject,
  setCheckboxValue,
  setFieldRequired,
  setInputValue,
  setSelectValue,
  textInputValue,
  updateRequiredMarker,
  updateRequiredMarkers,
  validateRequiredFields,
};
