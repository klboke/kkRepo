import { escapeHtml, lowerOrEmpty } from "../controls/display.js";
import { markInputValidity } from "../controls/fields.js";
import { FORMAT_DISPLAY_NAMES, formatIconName } from "../controls/formats.js";
import { showToast } from "../controls/toast.js";
import { refreshRepositoryRecipeControls } from "./form.js";
import { listRepositoryRecipes } from "./api.js";

let repositoryRecipes = [];

let activeRepositoryRecipeName = null;

async function loadRepositoryRecipes() {
  try {
    repositoryRecipes = await listRepositoryRecipes();
  } catch (error) {
    repositoryRecipes = [];
    showToast(`Failed to load recipes: ${error.message}`, "error");
  }
  refreshRepositoryRecipeOptions();
}

// ---- Repository form -----------------------------------------------------
function currentRecipe() {
  const name = document.getElementById("repository-recipe").value;
  return repositoryRecipes.find((r) => r.name === name) || null;
}

function repositoryFormatLabel(format) {
  const normalized = lowerOrEmpty(format);
  return FORMAT_DISPLAY_NAMES[normalized] || normalized || "Unknown";
}

function repositoryTypeLabel(type) {
  const normalized = lowerOrEmpty(type);
  return normalized ? normalized.charAt(0).toUpperCase() + normalized.slice(1) : "Unknown";
}

function repositoryRecipeContent(recipe) {
  const format = lowerOrEmpty(recipe?.format);
  const type = repositoryTypeLabel(recipe?.type);
  return `
    <span class="format-logo format-logo-${formatIconName(format)}" aria-hidden="true"></span>
    <span class="recipe-option-copy">
      <strong>${escapeHtml(repositoryFormatLabel(format))}</strong>
    </span>
    <span class="recipe-type">${escapeHtml(type)}</span>
  `;
}

function repositoryRecipeMatches(recipe, filter) {
  if (!filter) return true;
  return `${recipe?.name || ""} ${repositoryFormatLabel(recipe?.format)} ${repositoryTypeLabel(recipe?.type)}`
    .toLowerCase()
    .includes(filter);
}

function visibleRepositoryRecipes() {
  const filter = document.getElementById("repository-recipe-search").value.trim().toLowerCase();
  return repositoryRecipes.filter((recipe) => repositoryRecipeMatches(recipe, filter));
}

function setActiveRepositoryRecipe(name, scroll = false) {
  activeRepositoryRecipeName = name || null;
  const search = document.getElementById("repository-recipe-search");
  let activeOption = null;
  document.querySelectorAll("#repository-recipe-options .recipe-combobox-option").forEach((option) => {
    const active = option.dataset.recipe === activeRepositoryRecipeName;
    option.classList.toggle("is-active", active);
    if (active) activeOption = option;
  });
  if (activeOption) {
    search.setAttribute("aria-activedescendant", activeOption.id);
    if (scroll) activeOption.scrollIntoView({ block: "nearest" });
  } else {
    search.removeAttribute("aria-activedescendant");
  }
}

function renderRepositoryRecipeOptions() {
  const select = document.getElementById("repository-recipe");
  const options = document.getElementById("repository-recipe-options");
  const recipes = visibleRepositoryRecipes();
  if (!recipes.some((recipe) => recipe.name === activeRepositoryRecipeName)) {
    activeRepositoryRecipeName = recipes.find((recipe) => recipe.name === select.value)?.name
      || recipes[0]?.name
      || null;
  }
  options.innerHTML = recipes.length
    ? recipes.map((recipe, index) => `
        <div
            class="recipe-combobox-option${recipe.name === activeRepositoryRecipeName ? " is-active" : ""}"
            id="repository-recipe-option-${index}"
            role="option"
            aria-selected="${String(recipe.name === select.value)}"
            data-recipe="${escapeHtml(recipe.name)}">
          ${repositoryRecipeContent(recipe)}
        </div>
      `).join("")
    : '<div class="recipe-combobox-empty">No matching recipes</div>';
  setActiveRepositoryRecipe(activeRepositoryRecipeName);
}

function syncRepositoryRecipeCombobox() {
  const select = document.getElementById("repository-recipe");
  const trigger = document.getElementById("repository-recipe-trigger");
  const value = document.getElementById("repository-recipe-value");
  const recipe = currentRecipe();
  trigger.disabled = select.disabled || repositoryRecipes.length === 0;
  trigger.setAttribute("aria-disabled", String(trigger.disabled));
  value.innerHTML = recipe
    ? `<span class="recipe-selection">${repositoryRecipeContent(recipe)}</span>`
    : escapeHtml(repositoryRecipes.length === 0 ? "No recipes available" : "Select a recipe");
  if (!document.getElementById("repository-recipe-popover").hidden) {
    renderRepositoryRecipeOptions();
  }
}

function openRepositoryRecipeCombobox() {
  const trigger = document.getElementById("repository-recipe-trigger");
  const popover = document.getElementById("repository-recipe-popover");
  if (trigger.disabled || !popover.hidden) return;
  document.getElementById("repository-recipe-search").value = "";
  activeRepositoryRecipeName = document.getElementById("repository-recipe").value
    || repositoryRecipes[0]?.name
    || null;
  popover.hidden = false;
  trigger.setAttribute("aria-expanded", "true");
  renderRepositoryRecipeOptions();
  setTimeout(() => document.getElementById("repository-recipe-search").focus(), 0);
}

function closeRepositoryRecipeCombobox(focusTrigger = false) {
  const popover = document.getElementById("repository-recipe-popover");
  const trigger = document.getElementById("repository-recipe-trigger");
  popover.hidden = true;
  trigger.setAttribute("aria-expanded", "false");
  activeRepositoryRecipeName = null;
  if (focusTrigger) trigger.focus();
}

function toggleRepositoryRecipeCombobox() {
  const popover = document.getElementById("repository-recipe-popover");
  if (popover.hidden) openRepositoryRecipeCombobox();
  else closeRepositoryRecipeCombobox(true);
}

function moveActiveRepositoryRecipe(direction) {
  const options = Array.from(
    document.querySelectorAll("#repository-recipe-options .recipe-combobox-option"));
  if (options.length === 0) return;
  let index = options.findIndex((option) => option.dataset.recipe === activeRepositoryRecipeName);
  if (index < 0) index = direction > 0 ? -1 : 0;
  index = (index + direction + options.length) % options.length;
  setActiveRepositoryRecipe(options[index].dataset.recipe, true);
}

function selectRepositoryRecipe(name) {
  if (!repositoryRecipes.some((recipe) => recipe.name === name)) return;
  const select = document.getElementById("repository-recipe");
  select.value = name;
  markInputValidity(select, false);
  select.dispatchEvent(new Event("change", { bubbles: true }));
  closeRepositoryRecipeCombobox(true);
}

function bindRepositoryRecipeCombobox() {
  const root = document.getElementById("repository-recipe-combobox");
  const trigger = document.getElementById("repository-recipe-trigger");
  const search = document.getElementById("repository-recipe-search");
  const options = document.getElementById("repository-recipe-options");

  trigger.addEventListener("click", toggleRepositoryRecipeCombobox);
  trigger.addEventListener("keydown", (event) => {
    if (event.key === "ArrowDown" || event.key === "ArrowUp") {
      event.preventDefault();
      openRepositoryRecipeCombobox();
      return;
    }
    if (event.key === "Escape" && !document.getElementById("repository-recipe-popover").hidden) {
      event.preventDefault();
      event.stopPropagation();
      closeRepositoryRecipeCombobox(true);
    }
  });

  search.addEventListener("input", () => {
    activeRepositoryRecipeName = null;
    renderRepositoryRecipeOptions();
  });
  search.addEventListener("keydown", (event) => {
    if (event.key === "ArrowDown" || event.key === "ArrowUp") {
      event.preventDefault();
      moveActiveRepositoryRecipe(event.key === "ArrowDown" ? 1 : -1);
      return;
    }
    if (event.key === "Enter") {
      event.preventDefault();
      if (activeRepositoryRecipeName) selectRepositoryRecipe(activeRepositoryRecipeName);
      return;
    }
    if (event.key === "Escape") {
      event.preventDefault();
      event.stopPropagation();
      closeRepositoryRecipeCombobox(true);
    }
  });

  options.addEventListener("mousemove", (event) => {
    const option = event.target.closest(".recipe-combobox-option");
    if (option) setActiveRepositoryRecipe(option.dataset.recipe);
  });
  options.addEventListener("click", (event) => {
    const option = event.target.closest(".recipe-combobox-option");
    if (option) selectRepositoryRecipe(option.dataset.recipe);
  });

  document.addEventListener("click", (event) => {
    if (!root.contains(event.target)) closeRepositoryRecipeCombobox();
  });
}

function refreshRepositoryRecipeOptions() {
  const select = document.getElementById("repository-recipe");
  const previous = select.value;
  select.innerHTML = repositoryRecipes.map((r) =>
    `<option value="${escapeHtml(r.name)}">${escapeHtml(r.name)}</option>`).join("");
  if (previous && repositoryRecipes.some((r) => r.name === previous)) {
    select.value = previous;
  }
  refreshRepositoryRecipeControls();
}

export {
  bindRepositoryRecipeCombobox,
  closeRepositoryRecipeCombobox,
  currentRecipe,
  loadRepositoryRecipes,
  repositoryFormatLabel,
  repositoryRecipes,
  repositoryTypeLabel,
  syncRepositoryRecipeCombobox,
};
