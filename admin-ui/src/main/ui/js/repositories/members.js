import { bindTransferKeyboard } from "../controls/transfer.js";
import { escapeHtml, lowerOrEmpty, lucideIcon } from "../controls/display.js";
import { editingRepositoryName, repositoryFormMode } from "./form.js";
import { listState } from "./list.js";
import { currentRecipe } from "./recipes.js";

const memberTransfer = {
  selected: [],
  highlight: { available: new Set(), selected: new Set() },
  filter: "",
  dragName: null,
};

function memberCandidates() {
  const recipe = currentRecipe();
  const format = recipe ? recipe.format : null;
  if (!format) return [];
  const allowNestedGroups = format === "pub" || format === "composer"
    || format === "terraform" || format === "swift" || format === "ansiblegalaxy"
    || format === "conda";
  return listState.repositories.filter((repo) => {
    if (repo.format !== format) return false;
    if (repositoryFormMode === "edit" && repo.name === editingRepositoryName) return false;
    if (repo.type === "GROUP" && !allowNestedGroups) return false;
    return true;
  });
}

function refreshRepositoryMemberOptions() {
  const list = document.getElementById("member-transfer");
  if (!list) return;
  const candidates = memberCandidates();
  const validNames = new Set(candidates.map((r) => r.name));
  memberTransfer.selected = memberTransfer.selected.filter((name) => validNames.has(name));
  memberTransfer.highlight.available = new Set(
    [...memberTransfer.highlight.available].filter((n) => validNames.has(n) && !memberTransfer.selected.includes(n)));
  memberTransfer.highlight.selected = new Set(
    [...memberTransfer.highlight.selected].filter((n) => memberTransfer.selected.includes(n)));
  renderMemberTransfer(candidates);
}

function renderMemberTransfer(candidates) {
  const byName = new Map(candidates.map((r) => [r.name, r]));
  const selectedSet = new Set(memberTransfer.selected);
  const filter = memberTransfer.filter.trim().toLowerCase();
  const available = candidates.filter((r) =>
    !selectedSet.has(r.name) && (filter === "" || r.name.toLowerCase().includes(filter)));

  const availableEl = document.getElementById("member-available-list");
  const selectedEl = document.getElementById("member-selected-list");

  availableEl.innerHTML = available.length
    ? available.map((r) => memberRowHtml(r, "available", null)).join("")
    : `<li class="member-empty">${filter ? "No matches" : "No eligible members"}</li>`;

  if (memberTransfer.selected.length === 0) {
    selectedEl.innerHTML = '<li class="member-empty">Drag in repositories to define priority order</li>';
  } else {
    selectedEl.innerHTML = memberTransfer.selected
      .map((name, idx) => {
        const repo = byName.get(name) || { name, type: "?" };
        return memberRowHtml(repo, "selected", idx + 1);
      })
      .join("");
  }

  document.getElementById("member-available-count").textContent = String(available.length);
  document.getElementById("member-selected-count").textContent = String(memberTransfer.selected.length);

  const single = memberTransfer.highlight.selected.size === 1;
  const index = memberTransfer.selected.indexOf([...memberTransfer.highlight.selected][0]);
  document.getElementById("member-up").disabled = !single || index <= 0;
  document.getElementById("member-down").disabled = !single || index < 0 || index >= memberTransfer.selected.length - 1;
  document.getElementById("member-add").disabled = memberTransfer.highlight.available.size === 0;
  document.getElementById("member-add-all").disabled = available.length === 0;
  document.getElementById("member-remove").disabled = memberTransfer.highlight.selected.size === 0;
  document.getElementById("member-remove-all").disabled = memberTransfer.selected.length === 0;
}

function memberRowHtml(repo, side, order) {
  const highlightSet = memberTransfer.highlight[side];
  const isSelected = highlightSet.has(repo.name);
  const draggable = side === "selected";
  const handle = draggable ? lucideIcon("grip-vertical", "member-handle") : "";
  const orderBadge = order != null ? `<span class="member-order">${order}</span>` : "";
  return `<li role="option" aria-selected="${isSelected}" class="member-row${isSelected ? " is-selected" : ""}" data-name="${escapeHtml(repo.name)}" data-side="${side}"${draggable ? ' draggable="true"' : ""}>${handle}${orderBadge}<span class="member-name">${escapeHtml(repo.name)}</span><span class="member-type">${escapeHtml(lowerOrEmpty(repo.type))}</span></li>`;
}

function toggleMemberHighlight(side, name, additive) {
  const set = memberTransfer.highlight[side];
  if (additive) {
    if (set.has(name)) set.delete(name); else set.add(name);
  } else {
    set.clear();
    set.add(name);
  }
  memberTransfer.highlight[side === "available" ? "selected" : "available"].clear();
}

function addSelectedMembers(names) {
  if (!names.length) return;
  const existing = new Set(memberTransfer.selected);
  for (const name of names) {
    if (!existing.has(name)) {
      memberTransfer.selected.push(name);
      existing.add(name);
    }
  }
  memberTransfer.highlight.available.clear();
  memberTransfer.highlight.selected = new Set(names);
  refreshRepositoryMemberOptions();
}

function removeSelectedMembers(names) {
  if (!names.length) return;
  const removed = new Set(names);
  memberTransfer.selected = memberTransfer.selected.filter((n) => !removed.has(n));
  memberTransfer.highlight.selected.clear();
  memberTransfer.highlight.available = new Set(names);
  refreshRepositoryMemberOptions();
}

function reorderSelected(dragName, dropName, position) {
  if (!dragName || dragName === dropName) return;
  const list = memberTransfer.selected.filter((n) => n !== dragName);
  let idx = dropName ? list.indexOf(dropName) : list.length;
  if (idx < 0) idx = list.length;
  if (position === "below") idx += 1;
  list.splice(idx, 0, dragName);
  memberTransfer.selected = list;
  refreshRepositoryMemberOptions();
}

function bindMemberTransferEvents() {
  const root = document.getElementById("member-transfer");
  if (!root || root.dataset.bound === "1") return;
  root.dataset.bound = "1";

  const onListClick = (side) => (event) => {
    const row = event.target.closest(".member-row");
    if (!row || row.dataset.side !== side) return;
    toggleMemberHighlight(side, row.dataset.name, event.ctrlKey || event.metaKey || event.shiftKey);
    refreshRepositoryMemberOptions();
  };
  const onListDouble = (side) => (event) => {
    const row = event.target.closest(".member-row");
    if (!row || row.dataset.side !== side) return;
    if (side === "available") addSelectedMembers([row.dataset.name]);
    else removeSelectedMembers([row.dataset.name]);
  };

  const availableList = document.getElementById("member-available-list");
  const selectedList = document.getElementById("member-selected-list");
  for (const [list, side] of [[availableList, "available"], [selectedList, "selected"]]) {
    bindTransferKeyboard(list, row => {
      toggleMemberHighlight(side, row.dataset.name, true);
      refreshRepositoryMemberOptions();
    }, row => side === "available" ? addSelectedMembers([row.dataset.name]) : removeSelectedMembers([row.dataset.name]));
  }
  for (const [button, step] of [["member-up", -1], ["member-down", 1]]) {
    document.getElementById(button).addEventListener("click", () => {
      const [name] = memberTransfer.highlight.selected;
      const target = memberTransfer.selected[memberTransfer.selected.indexOf(name) + step];
      if (target) reorderSelected(name, target, step < 0 ? "above" : "below");
    });
  }
  availableList.addEventListener("click", onListClick("available"));
  availableList.addEventListener("dblclick", onListDouble("available"));
  selectedList.addEventListener("click", onListClick("selected"));
  selectedList.addEventListener("dblclick", onListDouble("selected"));

  document.getElementById("member-add").addEventListener("click", () => {
    addSelectedMembers([...memberTransfer.highlight.available]);
  });
  document.getElementById("member-add-all").addEventListener("click", () => {
    const names = Array.from(availableList.querySelectorAll(".member-row")).map((el) => el.dataset.name);
    addSelectedMembers(names);
  });
  document.getElementById("member-remove").addEventListener("click", () => {
    removeSelectedMembers([...memberTransfer.highlight.selected]);
  });
  document.getElementById("member-remove-all").addEventListener("click", () => {
    removeSelectedMembers([...memberTransfer.selected]);
  });

  document.getElementById("member-filter").addEventListener("input", (event) => {
    memberTransfer.filter = event.target.value || "";
    refreshRepositoryMemberOptions();
  });

  selectedList.addEventListener("dragstart", (event) => {
    const row = event.target.closest(".member-row");
    if (!row || row.dataset.side !== "selected") return;
    memberTransfer.dragName = row.dataset.name;
    row.classList.add("dragging");
    event.dataTransfer.effectAllowed = "move";
    try { event.dataTransfer.setData("text/plain", row.dataset.name); } catch (_) {}
  });
  selectedList.addEventListener("dragend", (event) => {
    const row = event.target.closest(".member-row");
    if (row) row.classList.remove("dragging");
    selectedList.querySelectorAll(".drop-target-above, .drop-target-below")
      .forEach((el) => el.classList.remove("drop-target-above", "drop-target-below"));
    memberTransfer.dragName = null;
  });
  selectedList.addEventListener("dragover", (event) => {
    if (!memberTransfer.dragName) return;
    event.preventDefault();
    event.dataTransfer.dropEffect = "move";
    const row = event.target.closest(".member-row");
    selectedList.querySelectorAll(".drop-target-above, .drop-target-below")
      .forEach((el) => el.classList.remove("drop-target-above", "drop-target-below"));
    if (!row) return;
    const rect = row.getBoundingClientRect();
    const below = event.clientY > rect.top + rect.height / 2;
    row.classList.add(below ? "drop-target-below" : "drop-target-above");
  });
  selectedList.addEventListener("drop", (event) => {
    if (!memberTransfer.dragName) return;
    event.preventDefault();
    const row = event.target.closest(".member-row");
    if (row) {
      const rect = row.getBoundingClientRect();
      const below = event.clientY > rect.top + rect.height / 2;
      reorderSelected(memberTransfer.dragName, row.dataset.name, below ? "below" : "above");
    } else {
      reorderSelected(memberTransfer.dragName, null, "below");
    }
  });
}

export {
  bindMemberTransferEvents,
  memberTransfer,
  refreshRepositoryMemberOptions,
};
