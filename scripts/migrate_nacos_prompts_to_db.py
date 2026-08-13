#!/usr/bin/env python3
"""从 docs/nacos/sunshine-orchestrator.yaml 导入提示词到 sunshine_prompt。

用法:
  python scripts/migrate_nacos_prompts_to_db.py --dry-run
  python scripts/migrate_nacos_prompts_to_db.py --sql-out docker/mysql/init/17-sunshine-prompt-manager.sql
  python scripts/migrate_nacos_prompts_to_db.py --apply
  python scripts/migrate_nacos_prompts_to_db.py --apply --host ecs4c16g

已存在的 id（含路由规则种子）使用 INSERT IGNORE，不会覆盖。
"""
from __future__ import annotations

import argparse
import json
import sys
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_YAML = ROOT / "docs" / "nacos" / "sunshine-orchestrator.yaml"
DEFAULT_SQL_OUT = ROOT / "docker" / "mysql" / "init" / "17-sunshine-prompt-manager.sql"

PURPOSE_DESCRIPTIONS: dict[str, str] = {
    "system-prompt": "全局系统人设：定义企业助手身份、能力边界与回答风格，作为各模式 Prompt 拼装的最底层。",
    "scope-prompt": "范围约束：限制助手只处理企业制度/业务相关问题，拒绝越权或无关请求。",
    "mode-overlay.direct": "Direct 模式叠加层：直答路径的补充行为约束（可为空，保留扩展位）。",
    "mode-overlay.react": "ReAct 模式叠加层：约束自主推理时如何选工具、写思考与最终作答。",
    "mode-overlay.react-restart": "ReAct 继续生成叠加层：中断后续跑时接着已有进度，勿从头规划。",
    "mode-overlay.subagent": "子 Agent 叠加层：spawn/workflow 子任务内的角色与工具使用约束。",
    "mode-overlay.workflow": "Workflow 模式叠加层：静态/计划工作流节点执行时的补充行为约束。",
    "intent.classifier": "意图分类：将用户问题映射为执行模式（react / workflow / plan-workflow）及可选参数。",
    "planner.prompt": "动态规划器：根据用户问题生成 Plan JSON（节点与边），供 plan-workflow 校验与执行。",
    "answer.template": "Answer 节点终态作答模板：综合上游节点输出，面向用户生成 Markdown 结论。",
    "answer.overlay": "Answer 覆盖层：在 answer 模板之上追加的补充约束（可为空）。",
    "rewrite.intent": "意图补全改写：结合近期对话补全过短输入并还原指代，供意图路由使用。",
    "rewrite.planner": "规划前改写：把用户问法整理成适合 Planner 理解的清晰表述。",
    "rewrite.timeline": "改写步骤时间线文案：控制「查询改写」步骤在时间线上的 before/active/after 展示。",
    "hitl.agent-prompt": "人机确认（HITL）：写操作需用户确认时，向模型说明确认流程与等待态行为。",
    "sandbox.cancel-result": "沙箱工具取消回执：用户取消 exec/grep/glob 后回给主 Agent 的说明（含剩余次数）。",
    "sandbox.budget-exhausted": "沙箱取消预算耗尽：同族工具再调用次数用尽时，提示模型改方案或直接作答。",
    "react.subagent.cancel-result": "子任务取消回执：用户取消 spawn_subagent 后，提示主 Agent 自行接手原任务。",
    "plan-workflow.replan-feedback": "Plan 校验失败反馈：把校验错误注入 Planner，要求修正后重输出一行 Plan JSON。",
    "plan-workflow.user-modification": "用户改计划：把用户对 DAG 的修改意见注入 Planner，触发重新规划。",
    "plan-workflow.upstream-failure-line": "上游失败说明行：answer 解析上游占位时，失败节点注入的降级说明文案。",
    "timeline.intent": "意图步骤时间线：识别意图步骤的 label 与 before/active/after（含各模式 after 文案）。",
    "timeline.hitl": "HITL 步骤时间线：等待用户确认写操作时的展示文案。",
    "timeline.agent": "Agent 节点时间线：workflow/plan 中 agent 节点的展示与摘要模板。",
    "timeline.plan-approval": "Plan 确认步骤时间线：等待用户确认执行计划时的展示文案。",
    "timeline.rag-after": "RAG 完成后文案：检索步骤结束后写入 after 的摘要模板。",
    "timeline.sandbox": "沙箱步骤时间线：沙箱相关工具/工作区步骤的展示文案。",
    "timeline.steps.think": "时间线「思考/推理」步骤的 before/active/after 展示文案。",
    "timeline.steps.tool": "时间线「调用工具」步骤的 before/active/after 展示文案。",
    "timeline.steps.generate": "时间线「生成答复」步骤的 before/active/after 展示文案。",
    "timeline.steps.plan": "时间线「规划」步骤的 before/active/after 展示文案。",
    "timeline.steps.rag": "时间线「知识检索」步骤的 before/active/after 展示文案。",
    "timeline.steps.node": "时间线「工作流节点」通用步骤的 before/active/after 展示文案。",
    "timeline.steps.skill": "时间线「Skill 绑定」步骤的 before/active/after 展示文案。",
    "timeline.steps.tasks": "时间线「任务看板」步骤的 before/active/after 展示文案。",
    "timeline.steps.subagent": "时间线「子任务」步骤的 before/active/after 展示文案。",
    "routing-rule.react-policy-qa": "命中制度/办法/规定类咨询时绑定技能 policy-qa（轨 A：快速/专业模式共用）。",
    "routing-rule.react-travel-standard": "命中差旅/住宿/补贴标准类问法时绑定技能 travel-budget（轨 A：快速/专业模式共用）。",
    "routing-rule.react-expense-progress": "命中报销/付款进度与单据状态问法时绑定技能 expense-assist（轨 A：快速/专业模式共用）。",
    "routing-rule.react-compliance-risk": "命中风险点/合规风险审查类问法时绑定技能 compliance-review（轨 A：快速/专业模式共用）。",
    "routing-rule.rule-finance-smart-compliance": "命中合规审查类问法时走 finance-smart 静态工作流（轨 B：仅工作流模式）。",
    "routing-rule.rule-knowledge-budget-travel": "命中预算与出差相关问法时走 knowledge-qa 知识问答工作流（轨 B：仅工作流模式）。",
    "routing-rule.rule-finance-list-pending": "命中待审批列表查询类问法时走 finance-list 工作流（轨 B：仅工作流模式）。",
}

DISPLAY_NAMES: dict[str, str] = {
    "system-prompt": "系统提示词",
    "mode-overlay.direct": "模式覆盖 · Direct",
    "mode-overlay.react": "模式覆盖 · ReAct",
    "mode-overlay.react-restart": "模式覆盖 · ReAct 继续生成",
    "mode-overlay.subagent": "模式覆盖 · Subagent",
    "mode-overlay.workflow": "模式覆盖 · Workflow",
    "intent.classifier": "意图分类提示词",
    "planner.prompt": "Planner 提示词",
    "answer.template": "Answer 模板",
    "answer.overlay": "Answer 覆盖层",
    "scope-prompt": "Scope 提示词",
    "hitl.agent-prompt": "HITL Agent 提示词",
    "rewrite.intent": "改写 · Intent",
    "rewrite.planner": "改写 · Planner",
    "rewrite.timeline": "改写 · Timeline 文案",
    "timeline.intent": "时间线 · Intent",
    "timeline.hitl": "时间线 · HITL",
    "timeline.sandbox": "时间线 · Sandbox",
    "timeline.plan-approval": "时间线 · Plan 确认",
    "timeline.agent": "时间线 · Agent 节点",
    "timeline.rag-after": "时间线 · RAG after",
}


@dataclass(frozen=True)
class PromptSeed:
    id: str
    kind: str
    display_name: str
    content_text: str | None
    content_json: str | None
    priority: int = 0
    enabled: bool = True
    description: str | None = None


def _require_yaml():
    try:
        import yaml  # noqa: F401
    except ImportError:
        import subprocess

        subprocess.run([sys.executable, "-m", "pip", "install", "pyyaml", "-q"], check=True)
        import yaml  # noqa: F401
    return __import__("yaml")


def sql_quote(value: str | None) -> str:
    if value is None:
        return "NULL"
    escaped = (
        value.replace("\\", "\\\\")
        .replace("'", "''")
        .replace("\x00", "")
    )
    return f"'{escaped}'"


def as_json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


def display_name_for(prompt_id: str) -> str:
    if prompt_id in DISPLAY_NAMES:
        return DISPLAY_NAMES[prompt_id]
    if prompt_id.startswith("timeline.steps."):
        key = prompt_id.removeprefix("timeline.steps.")
        return f"时间线 · Steps · {key}"
    return prompt_id


def collect_seeds(agent: dict[str, Any]) -> list[PromptSeed]:
    seeds: list[PromptSeed] = []
    prompt = agent.get("prompt") or {}
    if not isinstance(prompt, dict):
        prompt = {}

    system = agent.get("system-prompt")
    if isinstance(system, str):
        seeds.append(PromptSeed(
            id="system-prompt",
            kind="system",
            display_name=display_name_for("system-prompt"),
            content_text=system,
            content_json=None,
            description=PURPOSE_DESCRIPTIONS.get("system-prompt", "全局系统人设"),
        ))

    overlays = prompt.get("mode-overlays") or {}
    if isinstance(overlays, dict):
        for key, text in overlays.items():
            if not isinstance(text, str):
                continue
            pid = f"mode-overlay.{key}"
            seeds.append(PromptSeed(
                id=pid,
                kind="mode-overlay",
                display_name=display_name_for(pid),
                content_text=text,
                content_json=None,
                description=PURPOSE_DESCRIPTIONS.get(f"mode-overlay.{key}", f"模式叠加 · {key}"),
            ))

    intent = agent.get("intent") or {}
    if isinstance(intent, dict) and isinstance(intent.get("classifier-prompt"), str):
        seeds.append(PromptSeed(
            id="intent.classifier",
            kind="intent",
            display_name=display_name_for("intent.classifier"),
            content_text=intent["classifier-prompt"],
            content_json=None,
            description=PURPOSE_DESCRIPTIONS.get("intent.classifier", "意图分类"),
        ))

    planner = agent.get("planner") or {}
    if isinstance(planner, dict) and isinstance(planner.get("prompt"), str):
        seeds.append(PromptSeed(
            id="planner.prompt",
            kind="planner",
            display_name=display_name_for("planner.prompt"),
            content_text=planner["prompt"],
            content_json=None,
            description=PURPOSE_DESCRIPTIONS.get("planner.prompt", "动态规划器"),
        ))

    for src_key, pid in (
        ("answer-template", "answer.template"),
        ("answer-overlay", "answer.overlay"),
        ("scope-prompt", "scope-prompt"),
    ):
        raw = prompt.get(src_key)
        if raw is None:
            continue
        text = raw if isinstance(raw, str) else str(raw)
        kind = "scope" if pid == "scope-prompt" else "answer"
        seeds.append(PromptSeed(
            id=pid,
            kind=kind,
            display_name=display_name_for(pid),
            content_text=text,
            content_json=None,
            description=PURPOSE_DESCRIPTIONS.get(pid, f"提示词 {pid}"),
        ))

    timeline = agent.get("timeline") or {}
    if isinstance(timeline, dict):
        for key, value in timeline.items():
            if key == "steps" and isinstance(value, dict):
                # 仅细项 timeline.steps.*；intent 走 timeline.intent，不写冗余整包
                for step_key, step_val in value.items():
                    if step_key == "intent":
                        continue
                    pid = f"timeline.steps.{step_key}"
                    seeds.append(PromptSeed(
                        id=pid,
                        kind="timeline",
                        display_name=display_name_for(pid),
                        content_text=None if isinstance(step_val, (dict, list)) else (
                            step_val if isinstance(step_val, str) else str(step_val)
                        ),
                        content_json=as_json(step_val) if isinstance(step_val, (dict, list)) else None,
                        description=PURPOSE_DESCRIPTIONS.get(f"timeline.steps.{step_key}", f"时间线步骤 · {step_key}"),
                    ))
                continue
            pid = f"timeline.{key}"
            if isinstance(value, (dict, list)):
                seeds.append(PromptSeed(
                    id=pid,
                    kind="timeline",
                    display_name=display_name_for(pid),
                    content_text=None,
                    content_json=as_json(value),
                    description=PURPOSE_DESCRIPTIONS.get(f"timeline.{key}", f"时间线 · {key}"),
                ))
            elif isinstance(value, str):
                seeds.append(PromptSeed(
                    id=pid,
                    kind="timeline",
                    display_name=display_name_for(pid),
                    content_text=value,
                    content_json=None,
                    description=PURPOSE_DESCRIPTIONS.get(f"timeline.{key}", f"时间线 · {key}"),
                ))

    rewrite = agent.get("rewrite") or {}
    if isinstance(rewrite, dict):
        for key, value in rewrite.items():
            pid = f"rewrite.{key}"
            if key in ("intent", "planner") and isinstance(value, dict):
                text = value.get("system-prompt")
                if not isinstance(text, str):
                    continue
                seeds.append(PromptSeed(
                    id=pid,
                    kind="rewrite",
                    display_name=display_name_for(pid),
                    content_text=text,
                    content_json=None,
                    description=PURPOSE_DESCRIPTIONS.get(f"rewrite.{key}", f"改写 · {key}"),
                ))
            elif isinstance(value, (dict, list)):
                seeds.append(PromptSeed(
                    id=pid,
                    kind="rewrite",
                    display_name=display_name_for(pid),
                    content_text=None,
                    content_json=as_json(value),
                    description=PURPOSE_DESCRIPTIONS.get(f"rewrite.{key}", f"改写 · {key}"),
                ))
            elif isinstance(value, str):
                seeds.append(PromptSeed(
                    id=pid,
                    kind="rewrite",
                    display_name=display_name_for(pid),
                    content_text=value,
                    content_json=None,
                    description=PURPOSE_DESCRIPTIONS.get(f"rewrite.{key}", f"改写 · {key}"),
                ))

    hitl = agent.get("hitl") or {}
    if isinstance(hitl, dict) and isinstance(hitl.get("agent-prompt"), str):
        seeds.append(PromptSeed(
            id="hitl.agent-prompt",
            kind="hitl",
            display_name=display_name_for("hitl.agent-prompt"),
            content_text=hitl["agent-prompt"],
            content_json=None,
            description=PURPOSE_DESCRIPTIONS.get("hitl.agent-prompt", "HITL 确认"),
        ))

    # 稳定顺序，便于 diff SQL
    seeds.sort(key=lambda s: (s.kind, s.id))
    return seeds


def render_sql(seeds: list[PromptSeed], *, bump_catalog: bool = True) -> str:
    ts = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    lines = [
        "-- sunshine-prompt-manager 提示词种子（由 scripts/migrate_nacos_prompts_to_db.py 生成）",
        f"-- generated_at={ts}",
        "-- 路由规则已在 17-sunshine-prompt-manager.sql；此处 INSERT IGNORE 跳过已存在 id",
        "USE sunshine_prompt;",
        "",
    ]
    for seed in seeds:
        desc = sql_quote(seed.description)
        lines.append(
            "INSERT IGNORE INTO prompt_definition "
            "(id, kind, display_name, description, enabled, priority, active_version, catalog_version) VALUES "
            f"({sql_quote(seed.id)}, {sql_quote(seed.kind)}, {sql_quote(seed.display_name)}, "
            f"{desc}, {1 if seed.enabled else 0}, {seed.priority}, 1, 1);"
        )
        lines.append(
            "INSERT IGNORE INTO prompt_version "
            "(prompt_id, version, status, content_text, content_json, change_note, maintainer) VALUES "
            f"({sql_quote(seed.id)}, 1, 'published', {sql_quote(seed.content_text)}, "
            f"{sql_quote(seed.content_json)}, 'nacos migrate', 'migrate_nacos_prompts_to_db');"
        )
        lines.append("")
    if bump_catalog:
        lines.append(
            "UPDATE prompt_catalog_meta SET catalog_version = catalog_version + 1, "
            "updated_at = CURRENT_TIMESTAMP WHERE id = 1;"
        )
        lines.append("")
    return "\n".join(lines)


def load_agent(yaml_path: Path) -> dict[str, Any]:
    yaml = _require_yaml()
    data = yaml.safe_load(yaml_path.read_text(encoding="utf-8"))
    if not isinstance(data, dict) or not isinstance(data.get("agent"), dict):
        raise RuntimeError(f"invalid yaml: missing agent root in {yaml_path}")
    return data["agent"]


def apply_sql(sql: str, *, host: str, port: int, user: str, password: str) -> None:
    sys.path.insert(0, str(ROOT / "scripts"))
    from sunshine_lib import run_mysql

    run_mysql(sql, host=host, port=port, user=user, password=password)


def ensure_schema(*, host: str, port: int, user: str, password: str) -> None:
    """创建库并执行 17 建表/路由种子（幂等：表已存在则跳过 DDL 错误由调用方处理）。"""
    init_db = "CREATE DATABASE IF NOT EXISTS sunshine_prompt DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
    apply_sql(init_db, host=host, port=port, user=user, password=password)
    schema_sql = (ROOT / "docker" / "mysql" / "init" / "17-sunshine-prompt-manager.sql").read_text(
        encoding="utf-8"
    )
    # 表已存在时 CREATE TABLE 会失败；用 mysql 探测后按需执行
    probe = (
        "SELECT COUNT(*) AS c FROM information_schema.tables "
        "WHERE table_schema='sunshine_prompt' AND table_name='prompt_definition';"
    )
    import shutil
    import subprocess

    mysql = shutil.which("mysql")
    if not mysql:
        raise RuntimeError("mysql client not found in PATH")
    proc = subprocess.run(
        [mysql, "-h", host, "-P", str(port), "-u", user, f"-p{password}", "-N", "-e", probe],
        text=True,
        capture_output=True,
    )
    if proc.returncode != 0:
        raise RuntimeError(f"MySQL probe failed: {proc.stderr or proc.stdout}")
    count = (proc.stdout or "").strip()
    if count == "0":
        apply_sql(schema_sql, host=host, port=port, user=user, password=password)
        print(">> applied 17-sunshine-prompt-manager.sql")
    else:
        print(">> sunshine_prompt schema already present, skip DDL")


def main() -> int:
    parser = argparse.ArgumentParser(description="Migrate Nacos orchestrator prompts into sunshine_prompt")
    parser.add_argument("--yaml", type=Path, default=DEFAULT_YAML)
    parser.add_argument("--dry-run", action="store_true", help="Print SQL to stdout")
    parser.add_argument("--sql-out", type=Path, nargs="?", const=DEFAULT_SQL_OUT,
                        help=f"Write SQL seed file (default: {DEFAULT_SQL_OUT})")
    parser.add_argument("--apply", action="store_true", help="Apply SQL via mysql client")
    parser.add_argument("--ensure-schema", action="store_true",
                        help="Create DB + run 17 DDL/routing seeds if missing (with --apply)")
    parser.add_argument("--host", default="ecs4c16g")
    parser.add_argument("--port", type=int, default=3306)
    parser.add_argument("--user", default="root")
    parser.add_argument("--password", default="root123")
    args = parser.parse_args()

    if not args.dry_run and args.sql_out is None and not args.apply:
        parser.error("specify --dry-run and/or --sql-out and/or --apply")

    agent = load_agent(args.yaml)
    seeds = collect_seeds(agent)
    if not seeds:
        print("ERROR: no prompt seeds extracted", file=sys.stderr)
        return 1
    sql = render_sql(seeds)
    print(f">> extracted {len(seeds)} prompt seeds from {args.yaml}")

    if args.dry_run:
        print(sql)

    if args.sql_out is not None:
        out: Path = args.sql_out
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(sql, encoding="utf-8")
        print(f">> wrote {out}")

    if args.apply:
        if args.ensure_schema:
            ensure_schema(host=args.host, port=args.port, user=args.user, password=args.password)
        apply_sql(sql, host=args.host, port=args.port, user=args.user, password=args.password)
        print(f">> applied {len(seeds)} seeds to {args.host}:{args.port}/sunshine_prompt")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
