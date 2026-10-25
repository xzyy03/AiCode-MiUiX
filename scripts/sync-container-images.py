#!/usr/bin/env python3
"""CI 定时执行：抓取各发行版上游的 rootfs 版本，生成 data/container-images.json。

背景：App 内置的 container-images.json 是发版快照，版本号随上游发补丁只会越来越旧，
而基础镜像过时在界面上没有任何信号（Alpine 的补丁常含 OpenSSL 安全修复）。本脚本把
「版本发现」搬进 CI：每天抓一次上游，产出 data/container-images.json 供 App 运行时拉取；
App 拉不到时回退内置 assets，所以本脚本失败只会让列表停在上一版，不会出现空列表。

数据源（均为结构化接口，不解析 HTML 目录索引）：
  - 分支 / EOL / 最新版：endoflife.date API
  - 各发行版 base URL 与 sources 表：读内置 assets，避免镜像地址两处维护
  - Alpine 校验和：<base>/alpine/<branch>/releases/<arch>/alpine-minirootfs-<ver>-<arch>.tar.gz.sha256
  - Ubuntu 版本与校验和：<base>/ubuntu-base/releases/<cycle>/release/SHA256SUMS（纯文本，一次拿到全部版本）
  - Debian 版本：<base>/job/tianon/job/debuerreotype/job/<abi>/.../<suite>/rootfs.debian_version

失败处理：任一分支抓取失败即以非零退出（仍写文件便于排查），工作流据此跳过发布——
宁可让列表停在上一版，也不发布一个悄悄少了分支的残缺目录。临时抖动第二天自动恢复。

`--self-test` 用内置样本离线校验解析器，供 ci.yml 在每次 PR 上跑，避免上游改版式后才发现。
"""

import argparse
import json
import os
import re
import sys
import urllib.request
from datetime import date

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
ASSETS_PATH = os.path.join(REPO_ROOT, "app", "src", "main", "assets", "container-images.json")
TARGET_PATH = os.path.join(REPO_ROOT, "data", "container-images.json")

UA = "aicode-container-images-sync"
TIMEOUT = 30

EOL_API = {
    "alpine": "https://endoflife.date/api/alpine-linux.json",
    "ubuntu": "https://endoflife.date/api/ubuntu.json",
    "debian": "https://endoflife.date/api/debian.json",
}

# 各发行版在列表里保留最新的几个分支。刻意不按 EOL 过滤：分支凭空消失比带着
# 「即将/已停止支持」角标更让人困惑，信号交给 App 按 eol 字段呈现。
KEEP_CYCLES = {"alpine": 4, "ubuntu": 4, "debian": 2}

# 标准架构键（与 ContainerImageCatalog.CURRENT_ABI 一致）→ 该发行版实际的目录/文件名后缀
ABI_NAMES = {
    "alpine": {"arm64": "aarch64", "x86_64": "x86_64"},
    "ubuntu": {"arm64": "arm64", "x86_64": "amd64"},
    "debian": {"arm64": "arm64v8", "x86_64": "amd64"},
}

DISTRO_NAMES = {"alpine": "Alpine Linux", "ubuntu": "Ubuntu", "debian": "Debian"}

UBUNTU_FILE_RE = re.compile(r"^ubuntu-base-(\d+\.\d+(?:\.\d+)?)-base-(arm64|amd64)\.tar\.gz$")
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
VERSION_RE = re.compile(r"^\d+(\.\d+)*$")


# --------------------------------------------------------------------------- HTTP


def http_get_text(url: str) -> str:
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
        return resp.read().decode("utf-8", "replace")


def http_content_length(url: str) -> int:
    """HEAD 拿体积；拿不到返回 0（体积只用于展示，缺失不算失败）。"""
    req = urllib.request.Request(url, headers={"User-Agent": UA}, method="HEAD")
    with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
        raw = resp.headers.get("Content-Length")
    return int(raw) if raw and raw.isdigit() else 0


# ------------------------------------------------------------------ 纯解析函数（可离线自测）


def parse_sha256_file(text: str) -> str:
    """Alpine 的 .sha256 文件：`<hash>  <filename>`；解析不出返回空串。"""
    head = text.strip().split()[0].lower() if text.strip() else ""
    return head if SHA256_RE.match(head) else ""


def parse_sha256sums(text: str) -> dict:
    """Ubuntu 的 SHA256SUMS：`<hash> *<filename>`（也可能无 `*`）。

    返回 {(version, arch): hash}，只收 arm64/amd64 的 base rootfs，忽略 .zsync 等其它条目。
    """
    out = {}
    for line in text.splitlines():
        parts = line.split(None, 1)
        if len(parts) != 2:
            continue
        digest = parts[0].strip().lower()
        name = parts[1].strip().lstrip("*")
        matched = UBUNTU_FILE_RE.match(name)
        if matched and SHA256_RE.match(digest):
            out[(matched.group(1), matched.group(2))] = digest
    return out


def version_key(version: str) -> tuple:
    """语义化比较键：`24.04.5` → (24, 4, 5)。字符串比较会把 24.04.10 排在 24.04.9 前面。"""
    return tuple(int(part) for part in version.split("."))


def select_cycles(rows: list, distro: str, today: date):
    """取该发行版最新的若干个**已发布**分支，新→旧。

    只认 cycle 能解析成数字版本号、且 releaseDate 不晚于今天的条目（过滤掉测试中的分支）；
    Ubuntu 额外要求 lts == True，避免把 25.10 这类非 LTS 中间版塞进列表。
    """
    picked = []
    for row in rows:
        cycle = row.get("cycle") or ""
        if not VERSION_RE.match(cycle):
            continue
        if distro == "ubuntu" and row.get("lts") is not True:
            continue
        released = row.get("releaseDate")
        if isinstance(released, str) and released > today.isoformat():
            continue
        picked.append(row)
    picked.sort(key=lambda r: version_key(r["cycle"]), reverse=True)
    return picked[: KEEP_CYCLES[distro]]


# ------------------------------------------------------------------------- 各发行版


def build_alpine(base: str, cycles: list, prev_by_id: dict) -> list:
    entries = []
    for row in cycles:
        cycle = row["cycle"]
        branch = "v" + cycle
        version = row["latest"]
        sha_by_abi, sizes = {}, []
        for abi, arch in ABI_NAMES["alpine"].items():
            url = f"{base}/alpine/{branch}/releases/{arch}/alpine-minirootfs-{version}-{arch}.tar.gz"
            digest = parse_sha256_file(http_get_text(url + ".sha256"))
            if not digest:
                raise ValueError(f"Alpine {version} {arch} 校验和无法解析：{url}.sha256")
            sha_by_abi[abi] = digest
            sizes.append(http_content_length(url))
        entries.append(
            _entry(
                distro="alpine",
                cycle=cycle,
                version=version,
                # Alpine 只有单一 EOL，没有「常规支持 → 延长支持」两段，故 support_ends 留空
                eol=row.get("eol") or "",
                size=max(sizes),
                path=f"alpine/{branch}/releases/{{abi}}/alpine-minirootfs-{version}-{{abi}}.tar.gz",
                sha_by_abi=sha_by_abi,
                fallback_description="极简 Linux 发行版，rootfs 仅数 MB",
                prev_by_id=prev_by_id,
            )
        )
    return entries


def build_ubuntu(base: str, cycles: list, prev_by_id: dict) -> list:
    entries = []
    for row in cycles:
        cycle = row["cycle"]
        sums = parse_sha256sums(
            http_get_text(f"{base}/ubuntu-base/releases/{cycle}/release/SHA256SUMS")
        )
        per_abi, sizes = {}, []
        for abi, arch in ABI_NAMES["ubuntu"].items():
            versions = [v for (v, a) in sums if a == arch]
            if not versions:
                raise ValueError(f"Ubuntu {cycle} 的 SHA256SUMS 里没有 {arch} 条目")
            best = max(versions, key=version_key)
            per_abi[abi] = best
            url = f"{base}/ubuntu-base/releases/{cycle}/release/ubuntu-base-{best}-base-{arch}.tar.gz"
            sizes.append(http_content_length(url))
        if len(set(per_abi.values())) != 1:
            # 镜像正在滚动发布时会短暂出现各架构补丁号不一致，此时不发布，等明天
            raise ValueError(f"Ubuntu {cycle} 各架构最新补丁不一致：{per_abi}")
        version = next(iter(per_abi.values()))
        codename = (row.get("codename") or "").split()[0] if row.get("codename") else ""
        suffix = f" LTS ({codename})" if codename else " LTS"
        entries.append(
            _entry(
                distro="ubuntu",
                cycle=cycle,
                version=version,
                # Ubuntu 的字段口径与其他两家不同：常规（标准）支持结束在 `eol`，
                # 之后的 ESM 结束在 `extendedSupport`。故这里把 eol 映射到 ESM 结束，
                # support_ends 映射到标准支持结束——App 侧两级判定才能统一。
                eol=row.get("extendedSupport") or row.get("eol") or "",
                support_ends=row.get("eol") or "",
                support_extension="ESM",
                size=max(sizes),
                path=f"ubuntu-base/releases/{cycle}/release/ubuntu-base-{version}-base-{{abi}}.tar.gz",
                sha_by_abi={abi: sums[(v, ABI_NAMES["ubuntu"][abi])] for abi, v in per_abi.items()},
                fallback_description=f"Ubuntu {cycle}{suffix} base rootfs",
                prev_by_id=prev_by_id,
            )
        )
    return entries


def build_debian(base: str, cycles: list, prev_by_id: dict) -> list:
    entries = []
    for row in cycles:
        cycle = row["cycle"]
        suite = (row.get("codename") or "").lower()
        if not suite:
            raise ValueError(f"Debian {cycle} 缺少 codename，无法拼镜像路径")
        patches, sizes = {}, []
        for abi, abi_dir in ABI_NAMES["debian"].items():
            artifact = f"{base}/job/tianon/job/debuerreotype/job/{abi_dir}/lastSuccessfulBuild/artifact/{suite}"
            patch = http_get_text(artifact + "/rootfs.debian_version").strip()
            if not VERSION_RE.match(patch):
                raise ValueError(f"Debian {suite} {abi_dir} 的 rootfs.debian_version 异常：{patch!r}")
            patches[abi] = patch
            sizes.append(http_content_length(artifact + "/rootfs.tar.xz"))
        if len(set(patches.values())) != 1:
            raise ValueError(f"Debian {suite} 各架构补丁不一致：{patches}")
        version = next(iter(patches.values()))
        artifact_path = f"job/tianon/job/debuerreotype/job/{{abi}}/lastSuccessfulBuild/artifact/{suite}"
        entries.append(
            _entry(
                distro="debian",
                cycle=cycle,
                version=version,
                # Debian 的 `support` 是常规支持结束（之后移交 LTS 团队），`eol` 是含 LTS 的完全结束
                eol=row.get("eol") or "",
                support_ends=row.get("support") or "",
                support_extension="LTS",
                size=max(sizes),
                path=f"{artifact_path}/rootfs.tar.xz",
                sha_by_abi={},  # Jenkins artifact 不提供校验和，App 侧跳过校验
                fallback_description=f"Debian {cycle} 稳定版，Docker 官方镜像同源 rootfs（debuerreotype 构建）",
                extra={
                    "versionSuffix": f"({suite})",
                    "versionPath": f"{artifact_path}/rootfs.debian_version",
                },
                prev_by_id=prev_by_id,
            )
        )
    return entries


def _entry(
    distro: str,
    cycle: str,
    version: str,
    eol: str,
    size: int,
    path: str,
    sha_by_abi: dict,
    fallback_description: str,
    prev_by_id: dict,
    support_ends: str = "",
    support_extension: str = "",
    extra: dict | None = None,
) -> dict:
    """按固定键序拼条目（稳定顺序 → JSON diff 干净）。文案优先沿用内置 assets 里人工写的版本。

    支持周期分两级：[support_ends] 是**常规支持**结束日（过了就只剩延长支持），[eol] 是**完全结束**日。
    两者相同（或常规支持缺失，如 Alpine）时 [support_ends] 留空——没有中间态就不该多发一个字段。
    """
    entry_id = f"{distro}-{cycle}"
    prev = prev_by_id.get(entry_id) or {}
    out = {
        "id": entry_id,
        "name": prev.get("name") or DISTRO_NAMES[distro],
        "version": version,
    }
    out.update(extra or {})
    out.update(
        {
            "eol": eol,
            "description": prev.get("description") or fallback_description,
            "sizeBytes": size,
            "distro": distro,
            "path": path,
        }
    )
    if support_ends and support_ends != eol:
        out["supportEnds"] = support_ends
        if support_extension:
            out["supportExtension"] = support_extension
    if sha_by_abi:
        out["sha256ByAbi"] = sha_by_abi
    out["abiNames"] = ABI_NAMES[distro]
    return out


# ------------------------------------------------------------------------------ 主流程


def generate(today: date) -> dict:
    with open(ASSETS_PATH, encoding="utf-8") as f:
        assets = json.load(f)
    sources = assets.get("sources") or {}
    base = (sources.get("official") or {}).get("distros") or {}
    missing = [d for d in DISTRO_NAMES if not base.get(d)]
    if missing:
        raise ValueError(f"内置 assets 的 official 源缺少发行版 base URL：{missing}")

    prev_by_id = {img.get("id"): img for img in assets.get("images", [])}

    images = []
    for distro, builder in (
        ("alpine", build_alpine),
        ("ubuntu", build_ubuntu),
        ("debian", build_debian),
    ):
        rows = json.loads(http_get_text(EOL_API[distro]))
        cycles = select_cycles(rows, distro, today)
        if not cycles:
            raise ValueError(f"{distro} 未选出任何分支，endoflife.date 返回异常？")
        built = builder(base[distro], cycles, prev_by_id)
        # 列表内按**分支号**升序（不是补丁号），与内置 assets 的既有展示顺序一致
        built.sort(key=lambda e: version_key(e["id"].split("-", 1)[1]))
        images.extend(built)
        print(f"{distro}: {', '.join(e['id'] for e in built)}")

    return {"sources": sources, "images": images}


def self_test() -> int:
    """用内置样本离线校验解析器与分支选择（不打网络）。"""
    failures = []

    def check(name, actual, expected):
        if actual != expected:
            failures.append(f"{name}: 期望 {expected!r}，实际 {actual!r}")

    digest = "f25a96d2846a4bc439093107c1b48a8b0c93dcb411e2cb9cfded6f790b2bc001"
    check(
        "parse_sha256_file",
        parse_sha256_file(f"{digest}  alpine-minirootfs-3.21.8-aarch64.tar.gz\n"),
        digest,
    )
    check("parse_sha256_file 空输入", parse_sha256_file(""), "")
    check("parse_sha256_file HTML 错误页", parse_sha256_file("<html>404</html>"), "")

    sums = parse_sha256sums(
        "\n".join(
            [
                f"{'a' * 64} *ubuntu-base-24.04.4-base-arm64.tar.gz",
                f"{'b' * 64} *ubuntu-base-24.04.5-base-arm64.tar.gz",
                f"{'c' * 64}  ubuntu-base-24.04.5-base-amd64.tar.gz",
                f"{'d' * 64} *ubuntu-base-24.04.5-base-armhf.tar.gz",
                f"{'e' * 64} *ubuntu-base-24.04.5-base-arm64.tar.gz.zsync",
                "不是一行合法的校验和",
            ]
        )
    )
    check("parse_sha256sums 只收 arm64/amd64", sorted(sums), [("24.04.4", "arm64"), ("24.04.5", "amd64"), ("24.04.5", "arm64")])
    check("parse_sha256sums 后出现的同名条目覆盖前者", sums[("24.04.5", "arm64")], "b" * 64)
    check("version_key 补丁号按数字比", version_key("24.04.10") > version_key("24.04.9"), True)

    today = date(2026, 10, 12)
    rows = [
        {"cycle": "3.24", "eol": "2028-06-01", "releaseDate": "2026-06-09", "latest": "3.24.2"},
        {"cycle": "3.21", "eol": "2026-11-01", "releaseDate": "2024-12-05", "latest": "3.21.8"},
        {"cycle": "3.20", "eol": "2026-04-01", "releaseDate": "2024-05-22", "latest": "3.20.10"},
        {"cycle": "3.25", "eol": "2029-01-01", "releaseDate": "2027-01-01", "latest": "3.25.0"},
        {"cycle": "edge", "eol": "", "releaseDate": "2020-01-01", "latest": "x"},
    ]
    alpine_picked = [r["cycle"] for r in select_cycles(rows, "alpine", today)]
    check("select_cycles 过滤未发布与非数字分支", alpine_picked, ["3.24", "3.21", "3.20"])

    ubuntu_rows = [
        {"cycle": "26.04", "lts": True, "releaseDate": "2026-04-23", "latest": "26.04.1"},
        {"cycle": "25.10", "lts": False, "releaseDate": "2025-10-09", "latest": "25.10"},
        {"cycle": "24.04", "lts": True, "releaseDate": "2024-04-25", "latest": "24.04.5"},
    ]
    ubuntu_picked = [r["cycle"] for r in select_cycles(ubuntu_rows, "ubuntu", today)]
    check("select_cycles Ubuntu 只收 LTS", ubuntu_picked, ["26.04", "24.04"])

    def mk(support_ends, extension, eol):
        return _entry(
            distro="debian", cycle="12", version="12.15", eol=eol, size=1, path="p",
            sha_by_abi={}, fallback_description="d", prev_by_id={},
            support_ends=support_ends, support_extension=extension,
        )

    check("常规支持与完全结束同日_不下发中间态", "supportEnds" in mk("2028-06-30", "LTS", "2028-06-30"), False)
    check("常规支持缺失_不下发中间态", "supportEnds" in mk("", "LTS", "2028-06-30"), False)
    check("常规支持更早_下发 supportEnds", mk("2026-07-11", "LTS", "2028-06-30").get("supportEnds"), "2026-07-11")
    check("延长支持计划名一并下发", mk("2026-07-11", "LTS", "2028-06-30").get("supportExtension"), "LTS")

    if failures:
        print("自测失败：")
        for f in failures:
            print("  - " + f)
        return 1
    print("自测通过")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--self-test", action="store_true", help="离线校验解析器后退出")
    parser.add_argument("--out", default=TARGET_PATH, help="输出路径")
    args = parser.parse_args()

    if args.self_test:
        return self_test()

    try:
        payload = generate(date.today())
    except Exception as e:  # noqa: BLE001 —— CI 里需要把原因原样打出来
        print(f"抓取上游镜像版本失败：{e}")
        return 1

    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    # newline="\n"：Windows 上 Python 默认把 \n 写成 \r\n，会让本地生成结果与仓库里的 LF 版本不一致，
    # 而 CI 的“无变化则跳过提交”判定依赖逐字节比对
    with open(args.out, "w", encoding="utf-8", newline="\n") as f:
        json.dump(payload, f, ensure_ascii=False, indent=2)
        f.write("\n")
    print(f"已写出 {args.out}（{len(payload['images'])} 个条目）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
