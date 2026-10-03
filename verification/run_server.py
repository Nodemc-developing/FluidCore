"""Isolated Paper/Folia verification. Never points at a production server directory.
Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
from pathlib import Path
import queue
import shutil
import subprocess
import threading
import time
import urllib.request
import urllib.error
import zipfile
import atexit

ROOT = Path(__file__).resolve().parents[1]
PINNED_CE = "46ebe45f31f3e3f0965179a85cb1f308d8729f53281d6c64f4ef2af5c23d99f6"
USER_AGENT = "FluidCore/0.1.0-SNAPSHOT (local verification by ydxc20091)"


def digest(path):
    hasher = hashlib.sha256()
    with open(path, "rb") as file:
        for block in iter(lambda: file.read(1024 * 1024), b""):
            hasher.update(block)
    return hasher.hexdigest()


def get_json(url):
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    def read():
        with urllib.request.urlopen(request, timeout=40) as response:
            return json.load(response)
    return retry_network(read)


def retry_network(operation):
    """Retry transient network failures twice; unavailable official versions remain explicit."""
    for attempt in range(3):
        try:
            return operation()
        except urllib.error.HTTPError as error:
            if error.code < 500 and error.code != 429:
                raise
            if attempt == 2:
                raise
        except (urllib.error.URLError, TimeoutError, ConnectionError):
            if attempt == 2:
                raise
        time.sleep(2 * (attempt + 1))


def official_jar(project, version, cache):
    builds = get_json(f"https://fill.papermc.io/v3/projects/{project}/versions/{version}/builds")
    builds = builds if isinstance(builds, list) else builds.get("builds", [])
    build = max(builds, key=lambda row: row.get("id", row.get("build", 0)))
    download = build["downloads"]["server:default"]
    url = download["url"]
    expected = download["checksums"]["sha256"]
    target = cache / f"{project}-{version}-{build.get('id', build.get('build'))}.jar"
    target.parent.mkdir(parents=True, exist_ok=True)
    if not target.exists() or digest(target) != expected:
        temporary = target.with_suffix(".part")
        request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
        def download_file():
            with urllib.request.urlopen(request, timeout=90) as response, temporary.open("wb") as output:
                shutil.copyfileobj(response, output)
                length = response.headers.get("Content-Length")
                if length is not None and output.tell() != int(length):
                    raise ConnectionError("Truncated official server download")
            if digest(temporary) != expected:
                raise ConnectionError("Official server download failed its SHA256 check")
        retry_network(download_file)
        if digest(temporary) != expected:
            raise RuntimeError("Official server checksum mismatch")
        temporary.replace(target)
    return target, {"build": build.get("id", build.get("build")), "channel": build.get("channel"), "url": url, "sha256": expected}


def prepare_bundler_payload(server):
    """Fetch the official bundler's own declared payload with the same SHA256 contract."""
    with zipfile.ZipFile(server / "server.jar") as archive:
        try:
            context = archive.read("META-INF/download-context").decode("utf-8").strip()
        except KeyError:
            return
    expected, url, filename = context.split("\t")
    if Path(filename).name != filename or len(expected) != 64 or not url.startswith("https://"):
        raise RuntimeError("Unexpected official bundler download context")
    cache = server / "cache"
    cache.mkdir(exist_ok=True)
    target = cache / filename
    if target.exists() and digest(target) == expected:
        return
    temporary = target.with_suffix(".part")
    def download():
        request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
        with urllib.request.urlopen(request, timeout=90) as response, temporary.open("wb") as output:
            shutil.copyfileobj(response, output)
            length = response.headers.get("Content-Length")
            if length is not None and output.tell() != int(length):
                raise ConnectionError("Truncated official Mojang payload download")
        if digest(temporary) != expected:
            raise ConnectionError("Official Mojang download failed its SHA256 check")
    retry_network(download)
    if digest(temporary) != expected:
        raise RuntimeError("Official Mojang bundler payload checksum mismatch")
    temporary.replace(target)


def lock_server(server):
    """Keep one runner per disposable server directory, including restart verification."""
    lock = (server / "verification.lock").open("a+b")
    if lock.tell() == 0:
        lock.write(b"0")
        lock.flush()
    lock.seek(0)
    try:
        if os.name == "nt":
            import msvcrt
            msvcrt.locking(lock.fileno(), msvcrt.LK_NBLCK, 1)
        else:
            import fcntl
            fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
    except OSError as error:
        lock.close()
        raise RuntimeError(f"Another verification runner owns {server}") from error
    atexit.register(lock.close)
    return lock


def boot(java, folder, log, commands, timeout):
    lines = queue.Queue()
    process = subprocess.Popen([java, "-Xms512M", "-Xmx1536M", "-Dfile.encoding=UTF-8", "-jar", "server.jar", "--nogui"],
        cwd=folder, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        text=True, encoding="utf-8", errors="replace", bufsize=1,
        creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
    started = time.monotonic()
    ready = False
    sent = False
    seen = set()
    done_at = None

    def read():
        with log.open("w", encoding="utf-8") as output:
            for line in process.stdout:
                output.write(line)
                output.flush()
                lines.put(line)
        lines.put(None)

    threading.Thread(target=read, daemon=True).start()
    failure = None
    try:
        while time.monotonic() - started < timeout:
            try:
                line = lines.get(timeout=1)
            except queue.Empty:
                line = ""
            if line is None:
                failure = "Server exited before all verification markers"
                break
            if 'Done (' in line:
                ready = True
                done_at = time.monotonic()
                print(f"READY {folder.name}", flush=True)
            if ready and not sent and time.monotonic() - done_at >= 3:
                for command, marker in commands:
                    process.stdin.write(command + "\n")
                process.stdin.flush()
                sent = True
            for command, marker in commands:
                if marker in line:
                    seen.add(marker)
                    print(line.strip(), flush=True)
            if "FLUIDCORE_CE_VERIFY FAIL" in line:
                failure = "CE verification failed; inspect log"
                break
            if len(seen) == len(commands):
                break
            if process.poll() is not None:
                failure = "Server stopped unexpectedly"
                break
        else:
            failure = "Verification timeout"
    finally:
        if process.poll() is None:
            try:
                process.stdin.write("stop\n")
                process.stdin.flush()
                process.wait(timeout=45)
            except (OSError, subprocess.TimeoutExpired):
                process.kill()
                process.wait(timeout=10)
        print(f"STOPPED {folder.name} exit={process.returncode}", flush=True)
    return {"pass": failure is None and len(seen) == len(commands), "failure": failure,
            "markers": sorted(seen), "seconds": round(time.monotonic() - started, 2), "log": str(log.relative_to(ROOT))}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--project", choices=["paper", "folia"], required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--ce-jar", type=Path, required=True)
    parser.add_argument("--eula-file", type=Path, required=True, help="An existing EULA file accepted by the server owner")
    parser.add_argument("--java", required=True)
    parser.add_argument("--server-jar", type=Path)
    parser.add_argument("--ce-library-cache", type=Path)
    parser.add_argument("--bundler-cache", type=Path)
    parser.add_argument("--full", action="store_true", help="CE block/container/ticker test plus restart persistence")
    parser.add_argument("--timeout", type=int, default=300)
    parser.add_argument("--port", type=int, default=25790)
    parser.add_argument("--result-label", default="", help="Keep this verification run separate from older artifact results")
    parser.add_argument("--refresh-example-pack", action="store_true", help="Refresh packaged example files only inside the disposable server")
    args = parser.parse_args()
    if args.result_label and not re.fullmatch(r"[a-z0-9][a-z0-9-]{0,48}", args.result_label):
        parser.error("--result-label must contain lowercase letters, digits and hyphens")
    run_name = f"{args.project}-{args.version}" + (f"-{args.result_label}" if args.result_label else "")
    if digest(args.ce_jar) != PINNED_CE:
        raise RuntimeError("CE snapshot does not match the pinned baseline")
    if not any(line.strip().lower() == "eula=true" for line in args.eula_file.read_text(encoding="utf-8-sig").splitlines()):
        raise RuntimeError("The supplied EULA file has not been accepted")
    server = ROOT / ".local" / "verification" / "servers" / f"{args.project}-{args.version}"
    server.mkdir(parents=True, exist_ok=True)
    server_lock = lock_server(server)
    result_folder = ROOT / "verification" / "results"
    result_folder.mkdir(parents=True, exist_ok=True)
    if args.server_jar:
        jar = args.server_jar
        source = {"local_artifact_sha256": digest(jar)}
    else:
        jar, source = official_jar(args.project, args.version, ROOT / ".local" / "verification" / "downloads")
    shutil.copy2(jar, server / "server.jar")
    shutil.copy2(args.eula_file, server / "eula.txt")
    plugins = server / "plugins"
    plugins.mkdir(exist_ok=True)
    shutil.copy2(args.ce_jar, plugins / "CraftEngine.jar")
    for name in ["FluidCore-0.1.0-SNAPSHOT.jar", "FluidCore-Examples-0.1.0-SNAPSHOT.jar"]:
        shutil.copy2(ROOT / "dist" / name, plugins / name)
    if args.refresh_example_pack:
        pack_target = (plugins / "CraftEngine" / "resources" / "fluidcore-examples").resolve()
        with zipfile.ZipFile(plugins / "FluidCore-Examples-0.1.0-SNAPSHOT.jar") as example:
            for entry in example.infolist():
                if not entry.filename.startswith("pack/") or entry.is_dir():
                    continue
                destination = (pack_target / entry.filename.removeprefix("pack/")).resolve()
                if not destination.is_relative_to(pack_target):
                    raise RuntimeError("Example pack entry escapes the disposable pack directory")
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_bytes(example.read(entry))
    if args.ce_library_cache:
        shutil.copytree(args.ce_library_cache, plugins / "CraftEngine" / "libs", dirs_exist_ok=True)
    if args.bundler_cache:
        for name in ["cache", "libraries", "versions"]:
            candidate = args.bundler_cache / name
            if candidate.is_dir():
                shutil.copytree(candidate, server / name, dirs_exist_ok=True)
    prepare_bundler_payload(server)
    (plugins / "FluidCore").mkdir(exist_ok=True)
    (plugins / "FluidCore" / "verification.enabled").write_text("isolated runner\n", encoding="utf-8")
    properties = {
        "server-ip": "127.0.0.1", "server-port": args.port, "online-mode": "false", "level-name": "fluidcore-verification",
        "level-type": "minecraft:flat", "view-distance": 2, "simulation-distance": 2, "spawn-chunk-radius": 0,
        "generate-structures": "false", "max-players": 1, "enable-query": "false", "enable-rcon": "false",
        "allow-nether": "false", "max-tick-time": "-1", "sync-chunk-writes": "true",
        "generator-settings": '{"biome":"minecraft:plains","layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:stone","height":2}]}',
    }
    (server / "server.properties").write_text("\n".join(f"{key}={value}" for key, value in properties.items()) + "\n", encoding="utf-8")
    java_version = subprocess.run([args.java, "-version"], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, errors="replace").stdout.strip()
    result = {"project": args.project, "minecraft_target": args.version, "java": java_version, "server": source,
              "ce_sha256": PINNED_CE, "fluidcore_sha256": digest(plugins / "FluidCore-0.1.0-SNAPSHOT.jar"), "full": args.full}
    if args.result_label:
        result["result_label"] = args.result_label
    checks = [("fluidcore status", "FluidCore 0.1.0-SNAPSHOT"), ("fluidcore verify", "FLUIDCORE_VERIFY PASS")]
    if args.full:
        checks.append(("fluidcore verify-ce", "FLUIDCORE_CE_VERIFY PASS"))
        # This command also emits a dedicated marker after validating the public standard API.
        checks.append(("fluidcore fluid fluidcoreexample:heated_water", "FLUIDCORE_STANDARD_API PASS"))
    result["first_boot"] = boot(args.java, server, result_folder / f"{run_name}-boot.log", checks, args.timeout)
    if args.full and result["first_boot"]["pass"]:
        result["restart"] = boot(args.java, server, result_folder / f"{run_name}-restart.log",
                [("fluidcore verify-persisted", "FLUIDCORE_PERSISTENCE PASS")], args.timeout)
    result["pass"] = result["first_boot"]["pass"] and result.get("restart", {"pass": True})["pass"]
    output = result_folder / f"server-{run_name}.json"
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"project": args.project, "version": args.version, "pass": result["pass"], "result": str(output)}, ensure_ascii=False), flush=True)
    return 0 if result["pass"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
