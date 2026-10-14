"use strict";

const root = document.documentElement;
const themeButton = document.querySelector("#theme-toggle");
const systemTheme = window.matchMedia("(prefers-color-scheme: dark)");
let selectedTheme = null;
try { selectedTheme = localStorage.getItem("nuaforma-theme"); } catch { /* Storage is optional. */ }
if (selectedTheme === "light" || selectedTheme === "dark") root.dataset.theme = selectedTheme;
function updateThemeLabel() {
  const dark = root.dataset.theme ? root.dataset.theme === "dark" : systemTheme.matches;
  document.querySelector("#theme-icon").textContent = dark ? "☀" : "☾";
  document.querySelector("#theme-label").textContent = dark ? "Light" : "Dark";
  themeButton.setAttribute("aria-label", `Switch to ${dark ? "light" : "dark"} mode`);
}
updateThemeLabel();
systemTheme.addEventListener("change", updateThemeLabel);
themeButton.addEventListener("click", () => {
  const dark = root.dataset.theme ? root.dataset.theme === "dark" : systemTheme.matches;
  root.dataset.theme = dark ? "light" : "dark";
  try { localStorage.setItem("nuaforma-theme", root.dataset.theme); } catch { /* Storage is optional. */ }
  updateThemeLabel();
});

document.querySelectorAll("[role=tab]").forEach(tab => {
  function activate() {
    document.querySelectorAll("[role=tab]").forEach(other => {
      const active = other === tab;
      other.setAttribute("aria-selected", String(active));
      other.tabIndex = active ? 0 : -1;
      document.getElementById(other.getAttribute("aria-controls")).hidden = !active;
    });
  }
  tab.addEventListener("click", activate);
  tab.addEventListener("keydown", event => {
    const tabs = [...document.querySelectorAll("[role=tab]")];
    let index = tabs.indexOf(tab);
    if (event.key === "ArrowRight") index = (index + 1) % tabs.length;
    else if (event.key === "ArrowLeft") index = (index + tabs.length - 1) % tabs.length;
    else if (event.key === "Home") index = 0;
    else if (event.key === "End") index = tabs.length - 1;
    else return;
    event.preventDefault();
    tabs[index].click();
    tabs[index].focus();
  });
});

document.addEventListener("click", event => {
  const link = event.target.closest("a[data-download-status]");
  if (!link) return;
  const status = document.getElementById(link.dataset.downloadStatus);
  if (status) status.textContent = `Download requested: ${new URL(link.href).pathname.split("/").pop()}. Check your browser's downloads. If no file appears, use the seccomp.json link or the ‘Download not starting?’ instructions below.`;
});

document.addEventListener("click", async event => {
  const button = event.target.closest("[data-copy]");
  if (!button) return;
  const text = document.getElementById(button.dataset.copy)?.textContent;
  if (!text) return;
  const previous = button.textContent;
  try {
    await navigator.clipboard.writeText(text);
    button.textContent = "Copied";
    document.querySelector("#copy-status").textContent = "Copied to clipboard";
  } catch {
    const range = document.createRange();
    range.selectNodeContents(document.getElementById(button.dataset.copy));
    const selection = window.getSelection();
    selection.removeAllRanges();
    selection.addRange(range);
    document.querySelector("#copy-status").textContent = "Clipboard unavailable. Selected the text for manual copying.";
    button.textContent = "Text selected";
  }
  window.setTimeout(() => { button.textContent = previous; }, 1800);
});

async function loadExamples() {
  const response = await fetch("downloads/scenarios.json");
  if (!response.ok) throw new Error("Example index unavailable");
  const scenarios = await response.json();
  const list = document.querySelector("#scenario-list");
  let activeRequest = 0;
  async function select(scenario, button) {
    const requestNumber = ++activeRequest;
    list.querySelectorAll("button").forEach(item => item.setAttribute("aria-pressed", String(item === button)));
    document.querySelector("#scenario-title").textContent = scenario.title;
    document.querySelector("#scenario-description").textContent = scenario.description;
    document.querySelector("#scenario-expected").textContent = `Expected: ${scenario.expected}`;
    document.querySelector("#scenario-filename").textContent = scenario.file;
    const download = document.querySelector("#scenario-download");
    download.href = "downloads/" + scenario.file;
    document.querySelector("#scenario-json").textContent = "Loading request…";
    try {
      const result = await fetch(download.href);
      if (!result.ok) throw new Error("Request file unavailable");
      const data = await result.json();
      if (requestNumber === activeRequest) document.querySelector("#scenario-json").textContent = JSON.stringify(data, null, 2);
    } catch {
      if (requestNumber === activeRequest) document.querySelector("#scenario-json").textContent = "Could not load the example. Download the complete testset instead.";
    }
  }
  scenarios.forEach((scenario, index) => {
    if (!/^[a-zA-Z0-9_.-]+\.json$/.test(scenario.file)) throw new Error("Invalid local example name");
    const button = document.createElement("button");
    button.type = "button";
    button.className = "scenario-button";
    button.setAttribute("aria-pressed", "false");
    const title = document.createElement("span");
    title.textContent = scenario.title;
    const subtitle = document.createElement("small");
    subtitle.textContent = scenario.profile;
    button.append(title, subtitle);
    button.addEventListener("click", () => select(scenario, button));
    list.append(button);
    if (index === 0) select(scenario, button);
  });
}
loadExamples().catch(() => {
  document.querySelector("#scenario-title").textContent = "Download the example kit";
  document.querySelector("#scenario-description").textContent = "The inline examples could not be loaded. All JSON requests and instructions are included in the downloadable kit.";
});

async function latestRelease() {
  const versionPattern = /^\d+\.\d+\.\d+(?:-[A-Za-z0-9][A-Za-z0-9.-]*)?$/;
  try {
    const response = await fetch("https://api.github.com/repos/NuaForma/NuaForma/releases?per_page=20", { signal: AbortSignal.timeout(4000) });
    if (!response.ok) return;
    const releases = await response.json();
    const release = releases.filter(item => !item.draft && versionPattern.test(item.tag_name?.replace(/^v/, "") ?? "")).sort((a, b) => Date.parse(b.published_at) - Date.parse(a.published_at))[0];
    if (!release) return;
    const version = release.tag_name.replace(/^v/, "");
    document.querySelector("#release-version").textContent = version;
    document.querySelector("#release-link").href = "https://github.com/NuaForma/NuaForma/releases/tag/" + encodeURIComponent(release.tag_name);
    document.querySelector("#release-source").textContent = `Container release from GitHub. Maven library versions are listed separately.${release.prerelease ? " Evaluation alpha; APIs are not yet stable." : ""}`;
  } catch { /* The known release stays visible when metadata is unavailable. */ }
}
latestRelease();
