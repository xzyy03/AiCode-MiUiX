#!/usr/bin/env python3
"""合并 VSCode 默认主题（Dark+/Light+）为 tm4e 可直接加载的单文件 JSON。

VSCode 主题是 JSONC（含注释与尾逗号）且用 "include" 继承基础主题，
tm4e 的 IThemeSource 不解析 include、也不容忍注释。这里在构建期离线合并：
基础主题(dark_vs/light_vs)的 colors + tokenColors 打底，父主题(dark_plus/light_plus)
的 tokenColors 追加在后（同 scope 后者覆盖），semanticTokenColors 合并覆盖，
产出纯 JSON 放到 app/src/main/assets/textmate/{dark_plus,light_plus}.json。

发版/依赖升级时如需刷新配色，重跑本脚本即可。
"""
import json
import re
import urllib.request
from pathlib import Path

BASE = "https://raw.githubusercontent.com/microsoft/vscode/1.85.0/extensions/theme-defaults/themes/"
OUT_DIR = Path(__file__).resolve().parent.parent / "app/src/main/assets/textmate"

PAIRS = [
    ("dark_vs.json", "dark_plus.json", "dark_plus.json", "dark"),
    ("light_vs.json", "light_plus.json", "light_plus.json", "light"),
]


def strip_jsonc(text: str) -> str:
    # 逐字符扫描，仅剥离字符串外的 // 行注释与 /* */ 块注释，避免误伤
    # 形如 "vscode://schemas/..." 这类字符串内的 //。最后去掉尾逗号。
    out = []
    i, n = 0, len(text)
    in_str = False
    while i < n:
        c = text[i]
        if in_str:
            out.append(c)
            if c == "\\" and i + 1 < n:
                out.append(text[i + 1])
                i += 2
                continue
            if c == '"':
                in_str = False
            i += 1
            continue
        if c == '"':
            in_str = True
            out.append(c)
            i += 1
            continue
        if c == "/" and i + 1 < n and text[i + 1] == "/":
            i += 2
            while i < n and text[i] not in "\n\r":
                i += 1
            continue
        if c == "/" and i + 1 < n and text[i + 1] == "*":
            i += 2
            while i + 1 < n and not (text[i] == "*" and text[i + 1] == "/"):
                i += 1
            i += 2
            continue
        out.append(c)
        i += 1
    cleaned = "".join(out)
    # 去掉对象/数组里的尾逗号
    cleaned = re.sub(r",(\s*[}\]])", r"\1", cleaned)
    return cleaned


def load(name: str) -> dict:
    raw = urllib.request.urlopen(BASE + name, timeout=30).read().decode("utf-8")
    return json.loads(strip_jsonc(raw), strict=False)


def merge(base_name: str, plus_name: str, out_name: str, kind: str) -> None:
    base = load(base_name)
    plus = load(plus_name)

    merged = {
        "name": plus.get("name", base.get("name")),
        "type": kind,
        "colors": base.get("colors", {}),
        "tokenColors": base.get("tokenColors", []) + plus.get("tokenColors", []),
        "semanticHighlighting": True,
        "semanticTokenColors": {
            **base.get("semanticTokenColors", {}),
            **plus.get("semanticTokenColors", {}),
        },
    }

    # sora 的 TextMateColorScheme 读旧键 editorIndentGuide.background(/activeBackground) 映射
    # 缩进参考线颜色；VSCode 1.85 改用带 1 后缀的新键，补上旧键以便取色。
    colors = merged["colors"]
    if "editorIndentGuide.background1" in colors:
        colors.setdefault("editorIndentGuide.background", colors["editorIndentGuide.background1"])
    if "editorIndentGuide.activeBackground1" in colors:
        colors.setdefault("editorIndentGuide.activeBackground", colors["editorIndentGuide.activeBackground1"])

    out = OUT_DIR / out_name
    out.write_text(json.dumps(merged, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"{out_name}: {len(merged['tokenColors'])} token rules, {len(merged['colors'])} colors")


if __name__ == "__main__":
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    for args in PAIRS:
        merge(*args)
