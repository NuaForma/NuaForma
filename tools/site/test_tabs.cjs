"use strict";
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const vm = require("node:vm");

function fixture() {
  const panels = new Map();
  const element = () => ({textContent: "", addEventListener() {}, setAttribute() {}});
  function group(names) {
    const tabs = names.map((name, index) => {
      const attributes = {"aria-controls": name, "aria-selected": String(index === 0)};
      const handlers = {};
      panels.set(name, {hidden: index !== 0});
      return {
        tabIndex: index === 0 ? 0 : -1,
        matches: selector => selector === "[role=tab]",
        getAttribute: key => attributes[key],
        setAttribute: (key, value) => { attributes[key] = value; },
        addEventListener: (name, action) => { handlers[name] = action; },
        click() { handlers.click(); },
        focus() { this.focused = true; },
        key(key) { let prevented = false; handlers.keydown({key, preventDefault() { prevented = true; }}); return prevented; },
      };
    });
    return {children: tabs};
  }
  const methods = group(["compose", "docker", "helm"]);
  const architectures = group(["amd64", "arm64"]);
  const clients = group(["bruno", "postman", "curl"]);
  const document = {
    documentElement: {dataset: {}},
    querySelector: element,
    querySelectorAll: selector => selector === "[role=tablist]" ? [methods, architectures, clients] : [],
    getElementById: name => panels.get(name),
    addEventListener() {},
  };
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, "../../site/app.js"), "utf8"), {
    document, window: {matchMedia: () => ({matches: false, addEventListener() {}})},
    localStorage: {getItem: () => null}, AbortSignal, fetch: async () => ({ok: false}),
  });
  return {methods: methods.children, architectures: architectures.children, clients: clients.children, panels};
}

test("Compose defaults to AMD64 and architecture changes do not close the parent panel", () => {
  const {methods, architectures, panels} = fixture();
  assert.equal(panels.get("compose").hidden, false);
  assert.equal(panels.get("amd64").hidden, false);
  architectures[1].click();
  assert.equal(panels.get("compose").hidden, false);
  assert.equal(methods[0].getAttribute("aria-selected"), "true");
  assert.equal(panels.get("arm64").hidden, false);
  assert.equal(panels.get("amd64").hidden, true);
  methods[1].click();
  assert.equal(panels.get("compose").hidden, true);
  assert.equal(panels.get("docker").hidden, false);
  assert.equal(architectures[1].getAttribute("aria-selected"), "true");
  methods[0].click();
  assert.equal(panels.get("compose").hidden, false);
  assert.equal(panels.get("arm64").hidden, false);
});

test("keyboard navigation and focus remain within each tablist", () => {
  const {methods, architectures, panels} = fixture();
  assert.equal(architectures[0].key("ArrowLeft"), true);
  assert.equal(architectures[1].focused, true);
  assert.equal(architectures[1].tabIndex, 0);
  assert.equal(methods[0].tabIndex, 0);
  architectures[1].key("ArrowRight");
  assert.equal(architectures[0].focused, true);
  architectures[0].key("End");
  assert.equal(panels.get("arm64").hidden, false);
  architectures[1].key("Home");
  assert.equal(panels.get("amd64").hidden, false);
  methods[0].key("End");
  assert.equal(panels.get("helm").hidden, false);
  assert.equal(panels.get("amd64").hidden, false);
  assert.equal(methods[2].key("Escape"), false);
});

test("HTTP clients default to Bruno and cannot change deployment or architecture", () => {
  const {methods, architectures, clients, panels} = fixture();
  assert.equal(panels.get("bruno").hidden, false);
  architectures[1].click();
  clients[1].click();
  assert.equal(panels.get("postman").hidden, false);
  assert.equal(panels.get("bruno").hidden, true);
  assert.equal(panels.get("compose").hidden, false);
  assert.equal(panels.get("arm64").hidden, false);
  clients[1].key("End");
  assert.equal(panels.get("curl").hidden, false);
  assert.equal(clients[2].focused, true);
  methods[2].click();
  assert.equal(panels.get("curl").hidden, false);
  clients[2].key("Home");
  assert.equal(panels.get("bruno").hidden, false);
  assert.equal(panels.get("helm").hidden, false);
  assert.equal(architectures[1].getAttribute("aria-selected"), "true");
});
