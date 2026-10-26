"""Check the locally produced JaCoCo inventory, not a coverage percentage gate."""

from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
REPORT = Path("build/reports/jacoco/coverageReport/coverageReport.xml")
MODULES = (
    "nuaforma-contracts", "nuaforma-core", "nuaforma-template", "nuaforma-docx",
    "nuaforma-docx-pdf", "nuaforma-pdf", "nuaforma-server", "nuaforma-playground",
)


def validate(root):
    report = ET.parse(root / REPORT).getroot()
    classes = {node.attrib["name"] for node in report.findall("./package/class")}
    for module in MODULES:
        class_root = root / module / "build/classes"
        production = set()
        for language in ("kotlin", "java"):
            main = class_root / language / "main"
            production.update(path.relative_to(main).as_posix()[:-6] for path in main.rglob("*.class"))
        if not production or not classes.intersection(production):
            raise ValueError(f"Missing production coverage inventory for {module}")
        has_tests = any(path for language in ("kotlin", "java")
                        for path in (class_root / language / "test").rglob("*.class"))
        if has_tests and not (root / module / "build/jacoco/test.exec").is_file():
            raise ValueError(f"Missing test execution data for {module}")
    all_production = {
        path.relative_to(root / module / f"build/classes/{language}/main").as_posix()[:-6]
        for module in MODULES for language in ("java", "kotlin")
        for path in (root / module / f"build/classes/{language}/main").rglob("*.class")
    }
    if classes - all_production:
        raise ValueError("Coverage contains non-production classes")
    counters = {node.attrib["type"]: node.attrib for node in report.findall("counter")}
    if int(counters.get("LINE", {}).get("covered", "0")) <= 0:
        raise ValueError("No covered production lines were recorded")
    return counters


if __name__ == "__main__":
    counters = validate(ROOT)
    lines = counters["LINE"]
    print(f"Production module inventory verified: {lines['covered']} covered / "
          f"{int(lines['covered']) + int(lines['missed'])} executable JVM lines")
