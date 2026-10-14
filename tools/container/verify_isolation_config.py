"""Verify the outer OCI boundary without accepting path-mask or privilege bypasses."""
MASKED = {"/proc/acpi", "/proc/kcore", "/proc/keys", "/proc/timer_list", "/sys/firmware"}
READONLY = {"/proc/bus", "/proc/fs", "/proc/irq", "/proc/sys", "/proc/sysrq-trigger"}


def verify(config, apparmor=None):
    host = config["HostConfig"]
    if config["Config"]["User"] != "1000:1000" or not host.get("ReadonlyRootfs") or not host.get("Init"):
        raise ValueError("Non-root, read-only runtime with child reaping required")
    if host.get("Privileged") or host.get("CapAdd") or host.get("CapDrop") != ["ALL"]:
        raise ValueError("Host capability grants are forbidden")
    if host.get("PidMode") == "host" or host.get("NetworkMode") == "host":
        raise ValueError("Host PID/network namespace is forbidden")
    options = host.get("SecurityOpt", [])
    if not any(option in ("no-new-privileges", "no-new-privileges:true") for option in options):
        raise ValueError("No-new-privileges required")
    if any("unconfined" in option for option in options):
        raise ValueError("Unconfined policy/path settings are forbidden")
    if not MASKED.issubset(host.get("MaskedPaths", [])) or not READONLY.issubset(host.get("ReadonlyPaths", [])):
        raise ValueError("Protected OCI paths must remain masked/read-only")
    if apparmor and config.get("AppArmorProfile") != apparmor:
        raise ValueError("Selected named AppArmor profile is not active")
