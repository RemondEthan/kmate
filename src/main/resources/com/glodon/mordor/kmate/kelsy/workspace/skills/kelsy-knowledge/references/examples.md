# 归档与回忆例子

用 `load_skill_through_path` 加载本文件。不要把本页写进 MEMORY.md。

## 归档

用户：「今天下午和张三把评审定在周五了。」

应收成：

```
- 2026-09-02 与张三敲定评审方案，周五评审
```

不要收成：`- 定了评审` 或 `- 和他约了周五`。

用户第三次提到「评审方案」细节：写入 `knowledge/projects/评审方案.md`，文首含日期和张三；`KNOWLEDGE.md` 加一行 `- knowledge/projects/评审方案.md — 与张三的评审，2026-09 敲定`；`MEMORY.md` 只留 `- 评审方案：见 knowledge/projects/评审方案.md`。

## 回忆半年前

用户（2026-09-02）：「半年前和张三定的方案是什么？」

窗口：2026-02-01～2026-04-30。

1. `memory_get` `memory/2026-03-15.md` 等窗口内文件（先 search 或按日期试读存在的文件）
2. `memory_search`：`张三`、`方案`
3. 若目录有 `knowledge/projects/评审方案.md`，`read_file` 该路径
4. 答：引用读到的句子 + `来源：memory/2026-03-15.md`

search 与 get 都空：答「知识库在 2026-02 至 2026-04 没有与张三/方案相关的归档。」不要补一段像真的。

## `/note` 回显

用户 `/note 2026-03-15 与张三敲定评审方案` 被改写成强制归档指令后：

`memory_save` 完成，回复例如：「已写入 `MEMORY.md` 与 `memory/2026-03-15.md`。」
