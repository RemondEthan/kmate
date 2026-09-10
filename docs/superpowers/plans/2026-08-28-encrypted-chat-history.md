# Encrypted Chat History Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Client keeps at most 100 messages in memory/UI and persists the rest to a per-IM_CODE AES-GCM text archive keyed by `MD5(password + "|archive|" + im_code)`.

**Architecture:** `HistoryCodec` serializes `Message` to quoted JSON. `ChatHistory` owns one file, a dedicated archive `CryptoService`, and a single background thread. `ChatController` caps the observable list at 100 and asks `ChatHistory` to append / load older. `MessageListView` handles removals, prepend, scroll-up, and pin-to-bottom only when following latest.

**Tech Stack:** Java 21, JUnit 5, existing `CryptoService` / `Protocol.quote`, no new JSON library.

**Spec:** `docs/superpowers/specs/2026-08-28-encrypted-chat-history-design.md`

---

### Task 1: HistoryCodec + archive key factory

**Files:**
- Create: `src/test/java/com/glodon/mordor/kmate/service/HistoryCodecTest.java`
- Create: `src/main/java/com/glodon/mordor/kmate/service/HistoryCodec.java`
- Modify: `src/main/java/com/glodon/mordor/kmate/service/Protocol.java` (package-visible `stringField`)
- Modify: `src/main/java/com/glodon/mordor/kmate/service/CryptoService.java`
- Modify: `src/test/java/com/glodon/mordor/kmate/service/CryptoServiceTest.java`

- [ ] Codec round-trip test, then `HistoryCodec.encode/decode`
- [ ] `CryptoService.forArchive` test: different session padding cannot decrypt archive; archive instance can
- [ ] `mvn -q -Dtest=HistoryCodecTest,CryptoServiceTest test`

### Task 2: ChatHistory file store

**Files:**
- Create: `src/test/java/com/glodon/mordor/kmate/service/ChatHistoryTest.java`
- Create: `src/main/java/com/glodon/mordor/kmate/service/ChatHistory.java`

- [ ] Tests: create+append+loadNewest, timestamps, wrong password leaves file untouched, skip bad lines, loadOlderThan, trim to N, distinct im_code paths
- [ ] Implement open/append/load/trim on injected `Path` + `CryptoService`
- [ ] `mvn -q -Dtest=ChatHistoryTest test`

### Task 3: Wire controller and list

**Files:**
- Modify: `src/main/java/com/glodon/mordor/kmate/service/ImClient.java` (`imCode()`, `password()`)
- Modify: `src/main/java/com/glodon/mordor/kmate/ui/chat/ChatController.java`
- Modify: `src/main/java/com/glodon/mordor/kmate/ui/chat/MessageListView.java`

- [ ] Expose imCode/password from `ImClient`
- [ ] Controller: async load newest 100, merge live arrivals, cap 100, appendAsync, requestOlder, followLatest
- [ ] List: handle removed/inserted, pin bottom only when following, scroll-up loads older and anchors viewport
- [ ] `mvn -q test`

---

User asked to implement after design approval. Execute inline in this session (no extra commit unless asked).
