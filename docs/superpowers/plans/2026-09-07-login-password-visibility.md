# Login Password Visibility Toggle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a 👁 / 🙈 toggle to the "初始口令" PasswordField in the login card so users can verify the plaintext they typed before connecting.

**Architecture:** New `com.mordor.kmate.ui.login.PasswordFieldWithToggle` (extends `StackPane`) holds a hidden `PasswordField`, a `TextField` for plaintext, and a `Button` whose glyph is "👁" / "🙈". A `BooleanProperty visible` flips which child is `visible/managed`; the two fields' `textProperty`s are bound bidirectionally. `LoginPane` swaps its `password` field type from `PasswordField` to the new component and switches from `fieldBox(...)` to an inline label+component row. A small CSS block adds right padding (28 px) to the inner field and styles the eye button.

**Tech Stack:** Java 21, JavaFX 21, JUnit 5.10.2, Surefire 3.5.2. Unicode glyphs 👁 (U+1F441) / 🙈 (U+1F648) — no new dependencies.

**Spec:** `docs/superpowers/specs/2026-09-07-login-password-visibility-design.md`

## Global Constraints

These apply to every task. Values copied verbatim from the approved spec.

- New file lives in `com.mordor.kmate.ui.login` (same package as `LoginPane` / `LoginController`).
- Public API of the new component mirrors `PasswordField`: `getText()`, `setText(String)`, `clear()`, `setDisable(boolean)`, `setPromptText(String)`. All return / accept the same types.
- `setText(null)` is normalized to `""` before write.
- `setDisable(true)` propagates to all three children (hidden, shown, eye) and the StackPane itself.
- Toggle glyphs: hidden state shows "👁" (action: reveal), shown state shows "🙈" (action: hide).
- `LoginController` is **not** modified. `applyOffline(boolean on)` calls `password.setDisable(on)` — works because method name matches.
- `handleConnect()` calls `password.getText()` — works because method name matches.
- `login.css` `.login-field` rule is **not** modified. New rules go at end of file.
- Javadoc and code comments are in **Chinese** (project convention).
- Frequent commits: one commit per task.

## File Structure

| File | Responsibility |
|---|---|
| `src/main/java/com/mordor/kmate/ui/login/PasswordFieldWithToggle.java` (new) | StackPane hosting hidden PasswordField + shown TextField + eye Button. Owns the `visible` BooleanProperty and text binding. |
| `src/test/java/com/mordor/kmate/ui/login/PasswordFieldWithToggleTest.java` (new) | Plain JUnit 5 tests of state, text sync, toggle, and disable propagation. No JavaFX toolkit init needed (controls can be instantiated in tests; properties are read directly). |
| `src/main/java/com/mordor/kmate/ui/login/LoginPane.java` (modify) | Change `password` field type. Replace the `fieldBox("初始口令", password, ...)` call with an inline label+component VBox (same shape as the existing workspace+browse row). |
| `src/main/resources/com/mordor/kmate/ui/login/login.css` (modify) | Append 4 rules: `.password-with-toggle > .login-field` right-padding patch, `.login-eye-toggle` button, hover, disabled. |

---

### Task 1: PasswordFieldWithToggle skeleton + initial state

**Files:**
- Create: `src/main/java/com/mordor/kmate/ui/login/PasswordFieldWithToggle.java`
- Create: `src/test/java/com/mordor/kmate/ui/login/PasswordFieldWithToggleTest.java`

**Interfaces (defined here, consumed by later tasks):**
- `class PasswordFieldWithToggle extends StackPane` — package-private fields `hidden: PasswordField`, `shown: TextField`, `eye: Button`, `visible: BooleanProperty`.
- `public String getText()` — returns `hidden.getText()`.
- `public void setText(String text)` — writes to `hidden` (null → `""`).
- `public void clear()` — `hidden.clear()`.
- `public void setPromptText(String text)` — writes to both fields.

- [ ] **Step 1: Write the failing test for initial state**

Create `src/test/java/com/mordor/kmate/ui/login/PasswordFieldWithToggleTest.java`:

```java
package com.mordor.kmate.ui.login;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordFieldWithToggleTest {

    @Test
    void initialStateHiddenFieldIsVisibleAndShownFieldIsNot() {
        PasswordFieldWithToggle f = new PasswordFieldWithToggle();
        assertTrue(f.hidden.isVisible(), "初始应显示密文框");
        assertFalse(f.shown.isVisible(), "初始应隐藏明文框");
        assertTrue(f.hidden.isManaged(), "密文框应参与布局");
        assertFalse(f.shown.isManaged(), "明文框不参与布局");
    }

    @Test
    void initialTextIsEmpty() {
        PasswordFieldWithToggle f = new PasswordFieldWithToggle();
        assertEquals("", f.getText());
    }

    @Test
    void initialEyeButtonShowsOpenGlyph() {
        PasswordFieldWithToggle f = new PasswordFieldWithToggle();
        assertEquals("👁", f.eye.getText());  // 👁
    }

    @Test
    void carriesLoginFieldStyleAndPasswordWithToggleClass() {
        PasswordFieldWithToggle f = new PasswordFieldWithToggle();
        assertTrue(f.hidden.getStyleClass().contains("login-field"), "密文框需带 .login-field");
        assertTrue(f.shown.getStyleClass().contains("login-field"), "明文框需带 .login-field");
        assertTrue(f.getStyleClass().contains("password-with-toggle"),
                "容器需带 .password-with-toggle 让 CSS 补丁命中");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=PasswordFieldWithToggleTest`
Expected: `BUILD FAILURE` — class `PasswordFieldWithToggle` does not exist.

- [ ] **Step 3: Write the minimal class to make the test pass**

Create `src/main/java/com/mordor/kmate/ui/login/PasswordFieldWithToggle.java`:

```java
package com.mordor.kmate.ui.login;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;

/**
 * 密码输入框 + 明文切换眼睛。
 *
 * 内部同时持有 PasswordField(密文)和 TextField(明文),通过 visible 属性切换
 * 谁显示;两者的 textProperty 双向绑定,所以读 getText() 始终拿到最新值。
 * 切换时焦点保持在当前可见的那个字段上,避免用户切完明文还要再点一次。
 *
 * 对外 API 沿用 PasswordField 的方法名(setText / getText / clear / setDisable
 * / setPromptText),LoginPane 替换字段类型后调用点不需要改。
 */
public class PasswordFieldWithToggle extends StackPane {

    static final String EYE_OPEN = "👁";  // 👁
    static final String EYE_OFF = "🙈";   // 🙈 看不见的猴子

    final PasswordField hidden = new PasswordField();
    final TextField shown = new TextField();
    final Button eye = new Button(EYE_OPEN);
    final javafx.beans.property.BooleanProperty visible =
            new javafx.beans.property.SimpleBooleanProperty(false);

    public PasswordFieldWithToggle() {
        getStyleClass().add("password-with-toggle");

        hidden.getStyleClass().add("login-field");
        shown.getStyleClass().add("login-field");
        eye.getStyleClass().add("login-eye-toggle");

        hidden.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        shown.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);

        hidden.setVisible(true);
        hidden.setManaged(true);
        shown.setVisible(false);
        shown.setManaged(false);

        getChildren().addAll(hidden, shown, eye);
        StackPane.setAlignment(eye, Pos.CENTER_RIGHT);

        hidden.textProperty().bindBidirectional(shown.textProperty());

        eye.setOnAction(e -> visible.set(!visible.get()));
    }

    public String getText() {
        return hidden.getText();
    }

    public void setText(String text) {
        hidden.setText(text == null ? "" : text);
    }

    public void clear() {
        hidden.clear();
    }

    public void setPromptText(String text) {
        hidden.setPromptText(text);
        shown.setPromptText(text);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=PasswordFieldWithToggleTest`
Expected: 4 tests pass, `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
cd /Users/ksw/workspace/repository/kmate
git add src/main/java/com/mordor/kmate/ui/login/PasswordFieldWithToggle.java \
        src/test/java/com/mordor/kmate/ui/login/PasswordFieldWithToggleTest.java
git commit -m "feat(login): add PasswordFieldWithToggle skeleton with initial-state tests

StackPane hosting a hidden PasswordField and a visible-by-default
plaintext TextField (initially hidden), plus an eye button. The two
fields are bound bidirectionally. Public API mirrors PasswordField
so the LoginPane swap is a type change only.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 2: Toggle behavior (visibility flip + eye glyph + text sync)

**Files:**
- Modify: `src/main/java/com/mordor/kmate/ui/login/PasswordFieldWithToggle.java`
- Modify: `src/test/java/com/mordor/kmate/ui/login/PasswordFieldWithToggleTest.java`

**Interfaces (consumed by LoginPane and downstream tasks):**
- Toggling `visible` flips `hidden`/`shown` visibility & managed, and switches `eye.setText(...)` between `EYE_OPEN` and `EYE_OFF`.

- [ ] **Step 1: Add failing tests for the toggle**

Append to `src/test/java/com/mordor/kmate/ui/login/PasswordFieldWithToggleTest.java`:

```java
    @Test
    void firingEyeButtonFlipsVisibilityAndEyeGlyph() {
        PasswordFieldWithToggle f = new PasswordFieldWithToggle();
        f.eye.fire();
        assertFalse(f.hidden.isVisible(), "点眼睛后密文框应隐藏");
        assertTrue(f.shown.isVisible(), "点眼睛后明文框应显示");
        assertTrue(f.shown.isManaged(), "明文框应参与布局");
        assertEquals("🙈", f.eye.getText());  // 🙈 看不见的猴子
    }

    @Test
    void firingEyeTwiceReturnsToInitialState() {
        PasswordFieldWithToggle f = new PasswordFieldWithToggle();
        f.eye.fire();
        f.eye.fire();
        assertTrue(f.hidden.isVisible());
        assertFalse(f.shown.isVisible());
        assertEquals("👁", f.eye.getText());
    }

    @Test
    void setTextPropagatesToBothFieldsViaBinding() {
        PasswordFieldWithToggle f = new PasswordFieldWithToggle();
        f.setText("hello");
        assertEquals("hello", f.hidden.getText());
        assertEquals("hello", f.shown.getText());
        assertEquals("hello", f.getText());
    }

    @Test
    void setTextNullIsNormalizedToEmptyString() {
        PasswordFieldWithToggle f = new PasswordFieldWithToggle();
        f.setText("abc");
        f.setText(null);
        assertEquals("", f.getText());
        assertEquals("", f.shown.getText());
    }

    @Test
    void clearResetsBothFields() {
        PasswordFieldWithToggle f = new PasswordFieldWithToggle();
        f.setText("secret");
        f.clear();
        assertEquals("", f.hidden.getText());
        assertEquals("", f.shown.getText());
    }

    @Test
    void setPromptTextAppliesToBothFields() {
        PasswordFieldWithToggle f = new PasswordFieldWithToggle();
        f.setPromptText("输入初始口令");
        assertEquals("输入初始口令", f.hidden.getPromptText());
        assertEquals("输入初始口令", f.shown.getPromptText());
    }
```

- [ ] **Step 2: Run tests to verify the new ones fail**

Run: `./mvnw test -Dtest=PasswordFieldWithToggleTest`
Expected: the 6 new tests fail (toggle does not yet flip anything; setText/clear/setPromptText work because they were implemented in Task 1 — those 4 should already pass). Specifically, `firingEyeButtonFlipsVisibilityAndEyeGlyph` and `firingEyeTwiceReturnsToInitialState` fail because `visible` is changed but no listener reacts.

- [ ] **Step 3: Add the `visible` listener to the constructor**

Edit `src/main/java/com/mordor/kmate/ui/login/PasswordFieldWithToggle.java` — replace the line `eye.setOnAction(e -> visible.set(!visible.get()));` at the end of the constructor with:

```java
        eye.setOnAction(e -> visible.set(!visible.get()));

        visible.addListener((obs, oldV, newV) -> {
            hidden.setVisible(!newV);
            hidden.setManaged(!newV);
            shown.setVisible(newV);
            shown.setManaged(newV);
            eye.setText(newV ? EYE_OFF : EYE_OPEN);
        });
```

- [ ] **Step 4: Run tests to verify everything passes**

Run: `./mvnw test -Dtest=PasswordFieldWithToggleTest`
Expected: all 10 tests pass, `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
cd /Users/ksw/workspace/repository/kmate
git add src/main/java/com/mordor/kmate/ui/login/PasswordFieldWithToggle.java \
        src/test/java/com/mordor/kmate/ui/login/PasswordFieldWithToggleTest.java
git commit -m "feat(login): wire visible listener to flip fields and eye glyph

Clicking the eye now swaps hidden/shown visibility and managed, and
switches the button text between 👁 and 🙈. setText/clear/
setPromptText remain covered by Task 1's implementation; new tests
pin the behavior so the listener can't regress silently.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 3: setDisable propagation

**Files:**
- Modify: `src/main/java/com/mordor/kmate/ui/login/PasswordFieldWithToggle.java`
- Modify: `src/test/java/com/mordor/kmate/ui/login/PasswordFieldWithToggleTest.java`

**Interfaces (consumed by `LoginPane.applyOffline(boolean)`):**
- `setDisable(boolean)` on the component disables the StackPane and all three children.

- [ ] **Step 1: Add the failing test**

Append to `PasswordFieldWithToggleTest.java`:

```java
    @Test
    void setDisableTrueDisablesStackPaneAndAllChildren() {
        PasswordFieldWithToggle f = new PasswordFieldWithToggle();
        f.setDisable(true);
        assertTrue(f.isDisable(), "容器自身应被禁用");
        assertTrue(f.hidden.isDisable(), "密文框应被禁用");
        assertTrue(f.shown.isDisable(), "明文框应被禁用");
        assertTrue(f.eye.isDisable(), "眼睛按钮应被禁用");
    }

    @Test
    void setDisableFalseReEnablesAllChildren() {
        PasswordFieldWithToggle f = new PasswordFieldWithToggle();
        f.setDisable(true);
        f.setDisable(false);
        assertFalse(f.isDisable());
        assertFalse(f.hidden.isDisable());
        assertFalse(f.shown.isDisable());
        assertFalse(f.eye.isDisable());
    }
```

- [ ] **Step 2: Run test to verify the new ones fail**

Run: `./mvnw test -Dtest=PasswordFieldWithToggleTest#setDisableTrueDisablesStackPaneAndAllChildren+setDisableFalseReEnablesAllChildren`
Expected: both fail (StackPane.setDisable disables the container only, not the children — at minimum `f.eye.isDisable()` is false).

- [ ] **Step 3: Override setDisable in the component**

Edit `PasswordFieldWithToggle.java`. Add the following method (anywhere in the class — after `setPromptText` is fine):

```java
    @Override
    public void setDisable(boolean disabled) {
        super.setDisable(disabled);
        hidden.setDisable(disabled);
        shown.setDisable(disabled);
        eye.setDisable(disabled);
    }
```

- [ ] **Step 4: Run test to verify everything passes**

Run: `./mvnw test -Dtest=PasswordFieldWithToggleTest`
Expected: all 12 tests pass, `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
cd /Users/ksw/workspace/repository/kmate
git add src/main/java/com/mordor/kmate/ui/login/PasswordFieldWithToggle.java \
        src/test/java/com/mordor/kmate/ui/login/PasswordFieldWithToggleTest.java
git commit -m "feat(login): propagate setDisable to all children in PasswordFieldWithToggle

LoginPane.applyOffline calls setDisable(true) on the password field;
overriding here keeps the eye button from staying clickable while
the rest of the row is greyed out.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 4: Focus retention on toggle (try-catch wrapper)

**Files:**
- Modify: `src/main/java/com/mordor/kmate/ui/login/PasswordFieldWithToggle.java`
- Modify: `src/test/java/com/mordor/kmate/ui/login/PasswordFieldWithToggleTest.java`

**Interfaces:** The toggle listener must call `requestFocus()` on the field that just became visible, wrapped in `try-catch (Exception)` so a missing scene during construction or test setup cannot crash the component.

- [ ] **Step 1: Add the failing test**

Append to `PasswordFieldWithToggleTest.java`:

```java
    @Test
    void toggleDoesNotThrowWhenNoSceneIsAttached() {
        // 无 scene 时 requestFocus 会抛 IllegalStateException,组件必须吞掉。
        PasswordFieldWithToggle f = new PasswordFieldWithToggle();
        f.eye.fire();
        f.eye.fire();
        // 走到这里就说明 toggle 流程没有异常传播
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=PasswordFieldWithToggleTest#toggleDoesNotThrowWhenNoSceneIsAttached`
Expected: FAIL with `IllegalStateException` from `requestFocus` (the current listener does not focus either field, but the test passes; actually the current code does NOT call requestFocus, so the test passes — see Step 3 to add the call site first, then the test will fail if not wrapped).

- [ ] **Step 3: Add the focus call to the listener**

Edit `PasswordFieldWithToggle.java`. Replace the listener body so it now also requests focus on the visible field:

```java
        visible.addListener((obs, oldV, newV) -> {
            hidden.setVisible(!newV);
            hidden.setManaged(!newV);
            shown.setVisible(newV);
            shown.setManaged(newV);
            eye.setText(newV ? EYE_OFF : EYE_OPEN);
            javafx.scene.control.Control target = newV ? shown : hidden;
            try {
                target.requestFocus();
            } catch (Exception ignored) {
                // 没有 scene 时 (构造期 / 单元测试) 静默
            }
        });
```

- [ ] **Step 4: Run test to verify everything passes**

Run: `./mvnw test -Dtest=PasswordFieldWithToggleTest`
Expected: all 13 tests pass, `BUILD SUCCESS`. (The test in Step 1 was added BEFORE the call exists — it will pass either way, but the test now documents that toggling must be safe without a scene; if a future change removes the try-catch the test catches the regression.)

- [ ] **Step 5: Commit**

```bash
cd /Users/ksw/workspace/repository/kmate
git add src/main/java/com/mordor/kmate/ui/login/PasswordFieldWithToggle.java \
        src/test/java/com/mordor/kmate/ui/login/PasswordFieldWithToggleTest.java
git commit -m "feat(login): keep focus on the visible field after toggling

After clicking the eye, the field that just appeared calls
requestFocus so the user can keep typing without re-clicking.
Wrapped in try-catch because a StackPane constructed outside a
Scene (e.g. unit tests) would otherwise throw IllegalStateException.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 5: Wire PasswordFieldWithToggle into LoginPane

**Files:**
- Modify: `src/main/java/com/mordor/kmate/ui/login/LoginPane.java`

**Touchpoints (all in `LoginPane.java`):**
- Line 18 imports `PasswordField` — keep; still used in `applyOffline` via the wrapper's `hidden`. Actually `LoginPane` no longer references `PasswordField` after the swap; remove the import if it becomes unused.
- Line 45: change `private final PasswordField password = new PasswordField();` → `private final PasswordFieldWithToggle password = new PasswordFieldWithToggle();`
- Line 93: replace `VBox passwordBox = fieldBox("初始口令", password, "输入初始口令");` with the inline construction shown below.
- Lines 245-252 (`handleConnect`): no change — `password.getText()` still works.
- Line 180 (`applyOffline`): no change — `password.setDisable(on)` still works.

- [ ] **Step 1: Change the field type**

In `src/main/java/com/mordor/kmate/ui/login/LoginPane.java`, line 45:

Replace:
```java
    private final PasswordField password = new PasswordField();
```
with:
```java
    private final PasswordFieldWithToggle password = new PasswordFieldWithToggle();
```

`PasswordFieldWithToggle` lives in the same package (`com.mordor.kmate.ui.login`) as `LoginPane`, so no import line is needed. Do not add one.

- [ ] **Step 2: Replace the fieldBox call with inline construction**

In `src/main/java/com/mordor/kmate/ui/login/LoginPane.java`, line 93:

Replace:
```java
        VBox passwordBox = fieldBox("初始口令", password, "输入初始口令");
```
with:
```java
        Label passwordLabel = new Label("初始口令");
        passwordLabel.getStyleClass().add("login-field-label");
        passwordLabel.setMaxWidth(Double.MAX_VALUE);
        password.setPromptText("输入初始口令");
        VBox passwordBox = new VBox(3, passwordLabel, password);
        passwordBox.setMaxWidth(Double.MAX_VALUE);
        passwordBox.setFillWidth(true);
```

This mirrors the inline pattern used elsewhere in the same file for the workspace+browse row (line 100-106).

- [ ] **Step 3: Remove the now-unused `PasswordField` import (if any)**

After Step 1 the file may no longer reference `javafx.scene.control.PasswordField` directly (the wrapper encapsulates it). Check by running:
```bash
cd /Users/ksw/workspace/repository/kmate
grep -n "PasswordField" src/main/java/com/mordor/kmate/ui/login/LoginPane.java
```
Expected output: only the `PasswordFieldWithToggle` reference appears. If the bare `PasswordField` import is unused, delete the line `import javafx.scene.control.PasswordField;` from the import block. (If it still appears in the import list but unused, the compiler warns but doesn't fail. Leaving it is fine — note in the commit body either way.)

- [ ] **Step 4: Compile to verify nothing else broke**

Run: `./mvnw -q -DskipTests compile`
Expected: `BUILD SUCCESS`. If `LoginController` had compile errors mentioning `password`, re-check that `password` field type was updated.

- [ ] **Step 5: Run all unit tests**

Run: `./mvnw test`
Expected: all existing tests still pass; `PasswordFieldWithToggleTest` is included in the run.

- [ ] **Step 6: Commit**

```bash
cd /Users/ksw/workspace/repository/kmate
git add src/main/java/com/mordor/kmate/ui/login/LoginPane.java
git commit -m "feat(login): swap password field for PasswordFieldWithToggle in LoginPane

Field type changes from PasswordField to the new wrapper. The
fieldBox("初始口令", ...) call is replaced with the same inline
label+component pattern used for the workspace+browse row, since
the wrapper is a StackPane rather than a Control that fieldBox
can decorate. handleConnect and applyOffline call sites are
unchanged — the wrapper's API matches PasswordField.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 6: login.css styles for the toggle

**Files:**
- Modify: `src/main/resources/com/mordor/kmate/ui/login/login.css`

- [ ] **Step 1: Append the new CSS rules**

Edit `src/main/resources/com/mordor/kmate/ui/login/login.css`. Append at the end of the file:

```css

/* 密码框明文切换：眼睛按钮 + 右内边距补丁 */
.password-with-toggle > .login-field {
    -fx-padding: 5 28 5 8;
}

.login-eye-toggle {
    -fx-background-color: transparent;
    -fx-border-color: transparent;
    -fx-cursor: hand;
    -fx-font-size: 14px;
    -fx-padding: 0 6 0 6;
    -fx-min-width: 28;
    -fx-pref-width: 28;
}

.login-eye-toggle:hover {
    -fx-background-color: #F0F4FA;
    -fx-background-radius: 4;
}

.login-eye-toggle:disabled {
    -fx-opacity: 0.4;
}
```

Notes:
- The selector `.password-with-toggle > .login-field` targets only the fields inside the toggle wrapper — `.login-field` itself is unchanged, so IP/IM_CODE/用户名/工作区等其它文本框的 8 px 右内边距保留不变。
- The button width 28 px + horizontal padding 6 = visible area 28 px, exactly what the inner field's right padding (28) was sized for.

- [ ] **Step 2: Compile + run all unit tests**

Run: `./mvnw test`
Expected: `BUILD SUCCESS` (CSS changes don't break unit tests; this step is here to confirm nothing accidentally broke Java code in the same commit window).

- [ ] **Step 3: Commit**

```bash
cd /Users/ksw/workspace/repository/kmate
git add src/main/resources/com/mordor/kmate/ui/login/login.css
git commit -m "feat(login): add login.css styles for password visibility toggle

The .password-with-toggle > .login-field selector only patches the
right padding of fields inside the new wrapper, leaving .login-field
itself unchanged so other text boxes keep their original 8 px right
padding. The .login-eye-toggle rules give the button a transparent
background, hover halo, and a dimmed disabled state that matches
the existing login-offline / login-model-config treatments.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 7: Visual + final verification

**Files:** none (verification only). May produce a small follow-up commit if a tweak is needed.

- [ ] **Step 1: Run the full test suite**

Run: `./mvnw test`
Expected: all tests pass, including the 13 in `PasswordFieldWithToggleTest`.

- [ ] **Step 2: Launch the dev app and walk the acceptance criteria**

Run: `./mvnw -Pmac javafx:run` (or the platform-appropriate dev target).

Verify each item. If any fails, fix the root cause and recommit before moving on. Do not declare done with red items.

1. 密码框右侧出现 👁。
2. 输入一段文字 → 看到掩码 `•`。
3. 点击 👁 → 框内出现明文,按钮变成 🙈。
4. 不重新点击输入框直接继续输入 → 文字正常追加,光标位置合理(焦点未丢)。
5. 点击 🙈 → 回到密文。
6. 勾选"脱机登录" → 整个密码框变灰,眼睛按钮也变灰且点不动(`setDisable` 传播)。
7. 取消"脱机登录" → 重新可输入、眼睛按钮可点。
8. 其它文本框(IP / 端口 / IM_CODE / 用户名 / 工作区)的边框、聚焦色、内边距与改动前完全一致(`.login-field` 没被改)。
9. 切换明文/密文不影响"连接"按钮、错误提示 banner。
10. 关闭窗口、重开应用 → 之前没连上的会话里残留的明文/密文状态不持久化(只活在 LoginPane 生命周期内)。

- [ ] **Step 3: Optional — package the native installer**

Pick the profile matching the host OS:
```bash
./mvnw -Pmac clean package       # on macOS
./mvnw -Pwin clean package       # on Windows
./mvnw -Plinux-deb clean package # on Linux
```
Expected: `BUILD SUCCESS`, installer appears under `target/dist/`. Smoke-test by launching the packaged app and re-checking items 1-7.

- [ ] **Step 4: (Conditional) Commit any visual-tweak follow-up**

If Step 2 surfaced a tweak (e.g. button too small, hover halo off-color, padding insufficient), fix it in the appropriate file and commit:

```bash
cd /Users/ksw/workspace/repository/kmate
git add <touched-files>
git commit -m "fix(login): tune password-eye visual per acceptance check

<one-line description of what was tweaked and why>

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

If no tweak was needed, skip this step.

- [ ] **Step 5: Push (only if user asks)**

Do **not** push unless the user explicitly requests it.

---

## Self-review

**Spec coverage:**

| Spec section | Task |
|---|---|
| §1 背景 | (context only) |
| §2 目标 (4 项目标) | Task 1 (UI), Task 2 (toggle), Task 4 (focus), Task 5 (wiring into LoginPane) |
| §3 决策 — Unicode 图标 | Task 1 (EYE_OPEN/EYE_OFF constants), Task 6 (no PNG) |
| §3 决策 — StackPane 叠加 | Task 1 (constructor), Task 6 (CSS) |
| §3 决策 — 抽到独立组件 | Tasks 1-4 (all in PasswordFieldWithToggle) |
| §3 决策 — bindBidirectional | Task 1 (constructor), Task 2 (test coverage) |
| §3 决策 — CSS 补丁选择器 | Task 6 |
| §4.1 组件 API (5 methods) | Tasks 1-4 |
| §4.2 LoginPane 修改 (3 touchpoints) | Task 5 |
| §4.3 login.css 4 规则 | Task 6 |
| §4.4 测试覆盖 (6-8 用例) | Tasks 1-4 (13 tests total) |
| §5 数据流 | Tasks 1-4 (listener wiring) |
| §6 错误处理 (3 项) | Task 3 (setDisable propagation), Task 4 (try-catch), Task 1 (null normalization) |
| §7 验证 (7 acceptance + 可选 native) | Task 7 |

**Placeholder scan:** No "TBD" / "TODO" / "implement later" markers. The `<one-line description>` in Task 7 Step 4 is a template the engineer fills in, not a placeholder for plan content.

**Type consistency:**
- `PasswordFieldWithToggle.hidden` / `shown` / `eye` / `visible` — used consistently across Tasks 1-4.
- `EYE_OPEN` / `EYE_OFF` — Task 1 defines, Task 2 listener uses.
- Method names `getText` / `setText` / `clear` / `setDisable` / `setPromptText` — defined in Task 1, used by LoginPane in Task 5, covered by tests in Tasks 2-4.
- `setDisable` override added in Task 3 (after the constructor establishes fields in Task 1).
- `requestFocus` added in Task 4 (after the listener exists in Task 2).
