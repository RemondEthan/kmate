# Replace kmate Icons with ky.png — Design Spec

- Date: 2026-09-04
- Branch: `feat/new_icon`
- Owner: remond
- Status: Approved (pending spec review)

## 1. Background

`kmate` 当前使用一组以蓝青色 "K" 字 logo 为核心的图标：

- `src/main/resources/icons/Kmate.png` / `Kmate-alert.png`（512×512）
- `src/main/resources/icons/Kmate.icns`（macOS）
- `src/main/resources/icons/Kmate.ico`（Windows 16/32/48；`src/main/jpackage/Kmate.ico` 是它的副本）
- `src/main/resources/icons/tray.png` / `tray-alert.png`（32×32）
- `src/main/resources/icons/tray.ico` / `tray-alert.ico`（Windows 16/32/48）

Java 侧引用图标的位置（文件名不变）：

- `Mate4K.java:37` 启动时设 stage 图标 → `/icons/Kmate.png`
- `Mate4K.java:52` 启动时设 Dock/任务栏 → `/icons/Kmate.png`
- `UnreadAlert.java:47-48,76-78` 未读时切换 stage + taskbar 图标为 `Kmate-alert.png`，并在 macOS Dock 上加 `•` 徽章
- `TrayManager.java:42,46,56` 系统托盘正常态 `tray.png`、未读态 `tray-alert.png`

`scripts/make-wix-ico.py` 只覆盖 `Kmate.ico` 的生成；`Kmate.icns`、`tray.ico`、`tray-alert.ico` 都是手工提交产物，没有任何脚本可重生成。

本次替换源图：仓库根 `ky.png`（378×326，RGBA，透明背景，一个戴红色发箍的短发女孩头像）。

## 2. Goal

把 kmate 所有图标（应用图标 + 托盘图标 + jpackage 安装包图标）一次性换成基于 `ky.png` 的版本，并提供**跨平台**的可重生成脚本。其他平台开发者 clone 后直接 `./mvnw clean package` 即可，不需要在本地跑脚本。

非目标：

- 不改 Java 代码（`AppIcons`、`TrayManager`、`UnreadAlert`、`Mate4K` 引用的路径和文件名都不变）。
- 不改 emoji 资源（`emoji/*.png` 是 Twemoji 表情包，不是 kmate 品牌资产）。
- 不改 `bg-chat.png` 聊天背景。
- 不优化 16×16 / 32×32 下的人物面部细节（首次替换保留 LANCZOS 缩放效果，后续若觉得小尺寸糊再单独优化）。

## 3. Decisions

| 决策点 | 选择 | 理由 |
|---|---|---|
| 替换范围 | 全部 9 个产物（含 jpackage ICO + ICNS） | 用户明确选了"全部图标（含 jpackage 安装包图标）" |
| 非方形源图处理 | 等比缩放 + 居中放置到透明正方形画布 | 用户选择；不裁切、不变形 |
| alert 角标 | 保留右上角红色实心圆点 | 用户选择；与现有 `Kmate-alert.png` / `tray-alert.png` 风格一致 |
| 生成脚本架构 | 单脚本 `scripts/regen-icons.py` 一站式 | 用户选择；入口统一，删除旧的 `make-wix-ico.py` 避免两套入口 |
| ICNS 生成 | Pillow 原生 `Image.save(format="ICNS")` | 用户选择；跨平台一致，无 `iconutil` 依赖 |
| 生成产物是否提交 | 提交 | 用户选择；其他平台 clone 后直接打包 |

## 4. File changes

### 4.1 New

- `scripts/regen-icons.py` — 一站式图标生成脚本（≈150 行）。依赖 `Pillow >= 9.1.0`。跨平台（macOS / Linux / Windows）行为一致。

### 4.2 Replaced（commit 后内容变化）

- `src/main/resources/icons/Kmate.png` — 512×512，ky.png 等比居中
- `src/main/resources/icons/Kmate-alert.png` — 512×512 + 右上红点
- `src/main/resources/icons/Kmate.ico` — Windows 16/32/48/256 多尺寸
- `src/main/resources/icons/Kmate.icns` — macOS，主尺寸 512
- `src/main/resources/icons/tray.png` — 32×32，ky.png 等比居中
- `src/main/resources/icons/tray-alert.png` — 32×32 + 右上红点
- `src/main/resources/icons/tray.ico` — Windows 16/32/48
- `src/main/resources/icons/tray-alert.ico` — Windows 16/32/48
- `src/main/jpackage/Kmate.ico` — 与 `icons/Kmate.ico` 同字节（脚本最后一步同步复制）

### 4.3 Deleted

- `scripts/make-wix-ico.py` — 功能完全被 `regen-icons.py` 取代；`pom.xml` 中无引用，安全可删。

### 4.4 Unchanged

- `ky.png`（仓库根） — 源图，作为脚本默认输入；保留提交。
- 所有 Java 源文件 — 引用路径与文件名都不变。
- `pom.xml` — 不需要改动（jpackage 三个 profile 继续引用同样的路径）。

## 5. Script design (`scripts/regen-icons.py`)

### 5.1 Pipeline

```
load_source()                       # 读 ky.png -> RGBA Image
    │
    ▼
composite_centered(src, size)       # 等比缩放 + 居中到 size×size 透明画布
    │
    ├─► Kmate.png         (size=512, no badge)
    ├─► Kmate-alert.png   (size=512, with badge)
    ├─► tray.png          (size=32,  no badge)
    └─► tray-alert.png    (size=32,  with badge)

# 对每个 ICO 输出，从已生成的 PNG 源出发，按目标尺寸列表 resize + save
make_ico(source_png_path, out_path, sizes)
    ├─► icons/Kmate.ico        (sizes=[16, 32, 48])
    ├─► icons/tray.ico         (sizes=[16, 32, 48])
    └─► icons/tray-alert.ico   (sizes=[16, 32, 48])

make_icns(source_png_path, out_path)
    └─► icons/Kmate.icns

sync_jpackage_ico()
    └─► shutil.copy("icons/Kmate.ico", "jpackage/Kmate.ico")
```

### 5.2 Constants

```python
SOURCE = "ky.png"                                    # 默认源图
ICON_DIR = "src/main/resources/icons"
JPACKAGE_ICON = "src/main/jpackage/Kmate.ico"

# Kmate.png / Kmate-alert.png / Kmate.ico 主尺寸
KMATE_PNG_SIZE = 512
KMATE_ICO_SIZES = (16, 32, 48)

# tray.png / tray-alert.png / tray.ico 主尺寸
TRAY_PNG_SIZE = 32
TRAY_ICO_SIZES = (16, 32, 48)

# alert 角标
ALERT_BADGE_COLOR = (231, 0, 11, 255)    # 与原 alert 图风格一致的红色
ALERT_BADGE_RATIO = 0.22                  # 红点直径 / 画布边长
ALERT_BADGE_INSET_RATIO = 0.06            # 红点中心到画布右上角的水平/垂直距离占比

RESAMPLE = Image.LANCZOS
```

### 5.3 Algorithm: composite_centered(src, size, with_badge)

1. `scale = size / max(src.width, src.height)` — 等比缩放，使源图最长边等于画布边长
2. `resized = src.resize((round(src.width * scale), round(src.height * scale)), LANCZOS)`
3. `canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))` — 透明背景
4. `canvas.paste(resized, ((size - resized.width) // 2, (size - resized.height) // 2), resized)` — 居中
5. 若 `with_badge`：
   - `r = round(size * ALERT_BADGE_RATIO / 2)`
   - `cx = size - round(size * ALERT_BADGE_INSET_RATIO) - r`
   - `cy = round(size * ALERT_BADGE_INSET_RATIO) + r`
   - 用 `ImageDraw.ellipse` 在 `(cx - r, cy - r, cx + r, cy + r)` 画填充椭圆，颜色 `ALERT_BADGE_COLOR`
6. return `canvas`

### 5.4 ICO generation: make_ico(source_png_path, out_path, sizes)

```python
img = Image.open(source_png_path)
img.save(out_path, format="ICO", sizes=[(s, s) for s in sizes])
```

Pillow 直接把同一 PNG 按 sizes 列表重采样并打包进 ICO。源 PNG 始终是 512×512 或 32×32，所以 ICO 内每个尺寸都是从清晰源缩放下来的。

### 5.5 ICNS generation: make_icns(source_png_path, out_path)

```python
img = Image.open(source_png_path)
img.save(out_path, format="ICNS")
```

Pillow ≥ 9.1 内置 ICNS 编码，自动内嵌合适尺寸（512 主尺寸匹配 macOS ic09/ic10 槽位）。

### 5.6 CLI

```
python scripts/regen-icons.py [--source PATH] [--check]
```

- `--source PATH`：覆盖默认源图（默认 `ky.png`）
- `--check`：只生成到临时目录，对比已提交的产物；若任一不一致，exit 1 并打印差异列表；一致则打印 `OK: all icons up to date`，exit 0。CI 后续可接入。

### 5.7 Error handling

- 源文件缺失：`print(f"ERROR: {source} not found in {cwd}", file=sys.stderr); sys.exit(1)`
- Pillow 版本 < 9.1：启动时检测，提示 `Pillow >= 9.1 required for ICNS support`，exit 1
- 中途任意步骤失败：print stderr + exit 1，不留半成品。ICO/ICNS 写到 `tempfile.TemporaryDirectory()` 内的临时文件，最后 `shutil.move` 到目标路径。

## 6. Verification

### 6.1 After running the script

```bash
python scripts/regen-icons.py
echo "exit=$?"                 # 期望 0

git diff --stat                # 期望看到 9 个目标文件变更 + 1 个文件删除
file icons/Kmate.png           # 期望: PNG image data, 512 x 512
file icons/Kmate.ico           # 期望: MS Windows icon resource
file icons/Kmate.icns          # 期望: Mac OS X icon image
python -c "from PIL import Image; print(Image.open('icons/Kmate.png').size)"
                                # 期望: (512, 512)
python -c "from PIL import Image; print(Image.open('icons/tray.png').size)"
                                # 期望: (32, 32)
```

### 6.2 Build

```bash
./mvnw -Pmac clean package     # macOS 开发者；或用 -Pwin / -Plinux-deb
ls target/dist/                # 期望: Kmate-*.dmg / .msi / .deb
```

### 6.3 Runtime smoke test

启动应用，目视检查：

- Dock / 任务栏图标：是 ky 头像（不是 K logo）
- 窗口标题栏图标：是 ky 头像
- 系统托盘图标：是 ky 头像
- 让对端发一条消息，期望：
  - 标题栏 / Dock / 任务栏图标出现红色圆点角标
  - macOS Dock 出现 `•` 文字徽章（已存在逻辑，不变）
  - 系统托盘图标出现红色圆点角标

### 6.4 Unit tests

不新增。`AppIcons` / `TrayManager` / `UnreadAlert` 都是资源加载工具类，Java 代码本身不变；图标是构建期产物，运行时只是文件读取。

## 7. Risks & rollback

| Risk | Mitigation |
|---|---|
| Pillow 9.1 之前的 ICNS 编码格式与 macOS 不兼容 | 脚本启动时显式校验 `PIL.__version__ >= "9.1"`，不通过直接报错 |
| 小尺寸 ICO（16×16）下人物识别度低 | 接受 LANCZOS 自动缩放结果作为首发版本；若反馈糊，再单独优化（手工画小尺寸版） |
| 替换过程中遗漏某个引用点 | Java 侧引用路径不变，只换文件内容；IDE 全局搜索 `icons/`、`Kmate`、`tray` 关键字确认无遗漏 |
| 用户后悔 | `git revert` 单 commit 回退；旧的 9 个图标产物都在 git 历史里 |

## 8. Out of scope

- 自定义小尺寸（16/16、16/32）的优化版图标
- 在 Dock 角标上显示具体未读数字（当前是固定 `•`，与本次无关）
- Windows 通知中心 toast 图标（项目未集成 toast API）
- 应用启动闪屏（splash）图标
