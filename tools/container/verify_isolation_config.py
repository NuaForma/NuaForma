"""Verify the ordinary OCI boundary without custom profile dependencies or bypasses."""
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
    if any(option.startswith(("seccomp=", "apparmor=")) for option in options):
        raise ValueError("Qualification requires runtime default profiles without overrides")
    if not MASKED.issubset(host.get("MaskedPaths", [])) or not READONLY.issubset(host.get("ReadonlyPaths", [])):
        raise ValueError("Protected OCI paths must remain masked/read-only")
    if apparmor or config.get("AppArmorProfile") not in (None, "", "docker-default"):
        raise ValueError("A custom AppArmor profile is not part of the default runtime")
