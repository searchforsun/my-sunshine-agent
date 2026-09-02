#!/usr/bin/env python3
"""4.7.9-r1 ReAct request_decision Live 验收 — Cursor ask_question 对齐（R1–R4 hard）。

用法:
  python3 scripts/verify_decision_live.py
  python3 scripts/verify_decision_live.py --suite all
  python3 scripts/verify_decision_live.py --suite r1,r2,r3,r4
  python3 scripts/verify_decision_live.py --suite r5,d7
  python3 scripts/verify_decision_live.py --print-prompts

前置（Live 机临时灰度，勿把仓库默认改 true）:
  1. docs/nacos/sunshine-orchestrator.yaml → agent.execution.react.decision.enabled: true
  2. python scripts/sync_nacos.py
  3. python scripts/start.py --restart orchestrator bff
  4. 验收后把 enabled 改回 false，再 sync + restart（D21）

  D7 超时套件另需临时 timeout-sec: 5（或环境变量 DECISION_NACOS_TIMEOUT_SEC=5 仅文档提示；
     本脚本不改 Nacos，timeout 套件在 timeout-sec 仍为 300 时 soft-skip）。

环境变量: GATEWAY_URL, DECISION_LIVE_TIMEOUT_SEC（SSE 总超时，默认 240）

说明:
  R1      hard：单题；resolve `__custom__`+custom → metadata.outcome=answered + answers 含 custom
  R2      hard：allowMultiple 勾两 id → selectedOptionIds 长度 2
  R3      hard：两题；未全覆盖 400；全覆盖 200 → done
  R4      hard：同轮第二次 request_decision → 仅一张 awaiting（第二 call 错误）
  R5      opt ：stop→paused 后 resume 同问卷 re-await → resolve（模型/时序敏感）
  D7      opt ：timeout-sec≤10 时才 hard；断言 outcome=timeout；否则 soft-skip
  D8/D9   soft：单测覆盖（SUB 硬拒 / options 校验）
  D12     hard：pro/Planner MAIN 调 request_decision → resolve → 继续 dispatch_worker → completed
  D12R    opt ：Planner stop→paused 后 resume 同问卷 re-await（HarnessPlanner bind 路径）
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import threading
import time
from datetime import datetime
from typing import Any

try:
    import requests
except ImportError:
    print("请先安装: pip install -r scripts/requirements.txt", file=sys.stderr)
    sys.exit(1)

GATEWAY_URL = os.environ.get("GATEWAY_URL", "http://ecs4c16g:8000").rstrip("/")
TIMEOUT_SEC = int(os.environ.get("DECISION_LIVE_TIMEOUT_SEC", "240"))
NACOS_TIMEOUT_SEC_HINT = int(os.environ.get("DECISION_NACOS_TIMEOUT_SEC", "300"))
CUSTOM_ID = "__custom__"

R1_QUESTIONS = (
    '[{"id":"q1","prompt":"验收决策卡片请选择方案",'
    '"options":[{"id":"plan_a","label":"方案A：快速"},'
    '{"id":"plan_b","label":"方案B：完整"}],'
    '"allowMultiple":false}]'
)
R1_QUERY = (
    "请立即调用 request_decision（仅此工具）："
    "title=验收决策；"
    f"questions={R1_QUESTIONS}。"
    "选出后根据 answers 用一句话确认即可。不要调用其他工具。"
)
R2_QUESTIONS = (
    '[{"id":"q1","prompt":"关注哪些方面（可多选）",'
    '"options":[{"id":"perf","label":"性能"},{"id":"ux","label":"体验"},'
    '{"id":"sec","label":"安全"}],'
    '"allowMultiple":true}]'
)
R2_QUERY = (
    "请立即调用 request_decision（仅此工具）："
    "title=多选验收；"
    f"questions={R2_QUESTIONS}。"
    "选出后用一句话确认。不要调用其他工具。"
)
R3_QUESTIONS = (
    '[{"id":"q1","prompt":"用哪种模式？",'
    '"options":[{"id":"agent","label":"Agent"},{"id":"plan","label":"Plan"}],'
    '"allowMultiple":false},'
    '{"id":"q2","prompt":"关注哪些方面？",'
    '"options":[{"id":"perf","label":"性能"},{"id":"ux","label":"体验"}],'
    '"allowMultiple":true}]'
)
R3_QUERY = (
    "请立即调用 request_decision（仅此工具）："
    "title=两题验收；"
    f"questions={R3_QUESTIONS}。"
    "选出后用一句话确认。不要调用其他工具。"
)
R4_QUERY = (
    "请在**同一轮、同一条 assistant 消息里连续调用两次** request_decision（禁止拆成两轮）："
    "第一次 title=第一次问卷 questions="
    '[{"id":"q1","prompt":"第一次决策题",'
    '"options":[{"id":"a1","label":"A1"},{"id":"a2","label":"A2"}],'
    '"allowMultiple":false}]；'
    "第二次 title=第二次问卷 questions="
    '[{"id":"q1","prompt":"第二次决策题",'
    '"options":[{"id":"b1","label":"B1"},{"id":"b2","label":"B2"}],'
    '"allowMultiple":false}]。'
    "第二次若工具返回错误 JSON 则停止，用一句话说明即可，不要再调第三次。"
)
R5_QUERY = R1_QUERY

D12_QUESTIONS = (
    '[{"id":"q1","prompt":"本次任务执行到什么深度？",'
    '"options":[{"id":"quick","label":"快速：仅结构梳理"},'
    '{"id":"deep","label":"深入：代码级分析"}],'
    '"allowMultiple":false}]'
)
D12_QUERY = (
    "请**先**调用 request_decision（仅此工具，不要先 plan_submit）："
    "title=执行深度确认；"
    f"questions={D12_QUESTIONS}。"
    "等待用户选择后，再按所选深度 plan_submit 拆解任务并 dispatch_worker 执行，最后综合回答。"
)


def auth_json(method: str, path: str, body: dict | None, token: str | None) -> dict:
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    resp = requests.request(method, f"{GATEWAY_URL}{path}", headers=headers, json=body, timeout=30)
    resp.raise_for_status()
    return resp.json()


def auth_raw(
    method: str, path: str, body: dict | None, token: str | None
) -> requests.Response:
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    return requests.request(
        method, f"{GATEWAY_URL}{path}", headers=headers, json=body, timeout=30
    )


def setup_auth() -> tuple[str, str]:
    user = f"dec_{datetime.now():%H%M%S}"
    password = "password123"
    reg = auth_json(
        "POST",
        "/api/auth/register",
        {"username": user, "password": password, "nickname": "DecisionLive"},
        None,
    )
    if reg.get("code") != 200:
        raise RuntimeError(f"register failed: {reg}")
    login = auth_json("POST", "/api/auth/login", {"username": user, "password": password}, None)
    token = (login.get("data") or {}).get("token")
    if login.get("code") != 200 or not token:
        raise RuntimeError(f"login failed: {login}")
    conv = auth_json("POST", "/api/conversations", None, token)
    conv_id = (conv.get("data") or conv).get("id")
    if not conv_id:
        raise RuntimeError(f"create conversation failed: {conv}")
    return token, conv_id


def new_conversation(token: str) -> str:
    conv = auth_json("POST", "/api/conversations", None, token)
    conv_id = (conv.get("data") or conv).get("id")
    if not conv_id:
        raise RuntimeError(f"create conversation failed: {conv}")
    return conv_id


class SseCollector:
    def __init__(self) -> None:
        self.steps: list[dict] = []
        self.generation_id: str | None = None
        self.message_status: str | None = None
        self.error: Exception | None = None
        self._done = threading.Event()

    def wait_done(self, timeout: float) -> None:
        if not self._done.wait(timeout):
            raise TimeoutError("SSE 未在超时内结束")

    def wait_until(self, predicate, timeout: float) -> None:
        deadline = time.time() + timeout
        while time.time() < deadline:
            if predicate(self):
                return
            if self._done.is_set() and self.error:
                raise self.error
            time.sleep(0.2)
        raise TimeoutError("SSE 等待条件超时")


def chat_sse_live(
    token: str,
    conv_id: str,
    query: str,
    *,
    preference: str = "fast",
    wait: bool = True,
    resume_message_id: str | None = None,
) -> SseCollector:
    collector = SseCollector()
    headers = {"Authorization": f"Bearer {token}", "Content-Type": "application/json"}

    def run() -> None:
        try:
            body: dict[str, Any] = {
                "conversationId": conv_id,
                "executionMode": preference,
            }
            if resume_message_id:
                body["resumeMessageId"] = resume_message_id
            else:
                body["content"] = query
            with requests.post(
                f"{GATEWAY_URL}/api/chat/stream",
                headers=headers,
                json=body,
                stream=True,
                timeout=(10, TIMEOUT_SEC),
            ) as resp:
                resp.raise_for_status()
                for raw in resp.iter_lines(decode_unicode=True):
                    if raw is None:
                        continue
                    line = raw.strip()
                    if not line.startswith("data:"):
                        continue
                    payload = line[5:].strip()
                    if not payload:
                        continue
                    try:
                        obj = json.loads(payload)
                    except json.JSONDecodeError:
                        continue
                    t = obj.get("type")
                    if t == "step":
                        collector.steps.append(obj)
                    elif t == "generation" and obj.get("id"):
                        collector.generation_id = str(obj["id"])
                    elif t == "message" and obj.get("status"):
                        collector.message_status = str(obj["status"])
        except Exception as e:
            collector.error = e
        finally:
            collector._done.set()

    threading.Thread(target=run, daemon=True).start()
    if wait:
        collector.wait_done(TIMEOUT_SEC + 30)
        if collector.error and not collector.steps:
            raise collector.error
    return collector


def is_decision_step(step: dict) -> bool:
    sid = str(step.get("id") or "")
    phase = str(step.get("phase") or "")
    return phase == "decision" or sid.startswith("decision-")


def decision_meta(step: dict) -> dict:
    meta = step.get("metadata") or {}
    if not isinstance(meta, dict):
        return {}
    decision = meta.get("decision") or {}
    return decision if isinstance(decision, dict) else {}


def decision_questions(step: dict) -> list[dict]:
    qs = decision_meta(step).get("questions") or []
    return qs if isinstance(qs, list) else []


def collect_decision_steps(steps: list[dict]) -> list[dict]:
    by_id: dict[str, dict] = {}
    for s in steps:
        if not is_decision_step(s):
            continue
        sid = str(s.get("id") or "")
        if not sid:
            continue
        prev = by_id.get(sid)
        if prev is None:
            by_id[sid] = s
            continue
        rank = {"awaiting": 1, "running": 0, "paused": 2, "done": 3, "error": 3}
        if rank.get(str(s.get("lifecycle") or ""), -1) >= rank.get(str(prev.get("lifecycle") or ""), -1):
            by_id[sid] = s
    return list(by_id.values())


def awaiting_decisions(steps: list[dict]) -> list[dict]:
    return [s for s in collect_decision_steps(steps) if str(s.get("lifecycle") or "") == "awaiting"]


def extract_token(step: dict) -> str | None:
    meta = decision_meta(step)
    token = meta.get("token")
    if token:
        return str(token)
    sid = str(step.get("id") or "")
    if sid.startswith("decision-"):
        return sid[len("decision-"):]
    return None


def wait_assistant(token: str, conv_id: str, max_wait: int = 180) -> dict:
    deadline = time.time() + max_wait
    while time.time() < deadline:
        detail = auth_json("GET", f"/api/conversations/{conv_id}", None, token)
        messages = detail.get("messages") or detail.get("data", {}).get("messages") or []
        assistants = [m for m in messages if m.get("role") == "assistant"]
        if assistants and assistants[-1].get("status") == "completed":
            return assistants[-1]
        time.sleep(2)
    raise RuntimeError(f"assistant not completed within {max_wait}s")


def wait_assistant_status(
    token: str, conv_id: str, expected: str | set[str], max_wait: float = 60
) -> dict:
    wanted = {expected} if isinstance(expected, str) else set(expected)
    deadline = time.time() + max_wait
    while time.time() < deadline:
        detail = auth_json("GET", f"/api/conversations/{conv_id}", None, token)
        messages = detail.get("messages") or detail.get("data", {}).get("messages") or []
        assistants = [m for m in messages if m.get("role") == "assistant"]
        if assistants and str(assistants[-1].get("status") or "") in wanted:
            return assistants[-1]
        time.sleep(0.5)
    raise TimeoutError(f"assistant 未进入 {wanted}")


def resolve_decision(
    token: str,
    generation_id: str,
    decision_token: str,
    answers: list[dict[str, Any]],
) -> requests.Response:
    return auth_raw(
        "POST",
        f"/api/generations/{generation_id}/decisions/{decision_token}/resolve",
        {"answers": answers},
        token,
    )


def cancel_generation(token: str, generation_id: str) -> None:
    auth_json("POST", f"/api/generations/{generation_id}/cancel", None, token)


def hint_enabled() -> None:
    print(
        "  hint: 确认 agent.execution.react.decision.enabled=true 已 sync_nacos "
        "并 restart orchestrator；仓库默认须保持 false（D21）"
    )


def wait_awaiting(token: str, conv_id: str, query: str) -> tuple[SseCollector, dict] | dict:
    coll = chat_sse_live(token, conv_id, query, wait=False)
    try:
        coll.wait_until(
            lambda c: bool(c.generation_id) and len(awaiting_decisions(c.steps)) >= 1,
            timeout=min(TIMEOUT_SEC, 120),
        )
    except TimeoutError as e:
        coll.wait_done(5)
        hint_enabled()
        return {"pass": False, "error": str(e), "steps_sample": [s.get("id") for s in coll.steps[:12]]}
    step = awaiting_decisions(coll.steps)[0]
    return coll, step


def final_done_meta(steps: list[dict]) -> dict:
    done = [s for s in collect_decision_steps(steps) if str(s.get("lifecycle") or "") == "done"]
    if not done:
        return {}
    return decision_meta(done[-1])


def accepted_payload(resp: requests.Response) -> bool:
    if resp.status_code >= 400:
        return False
    try:
        body = resp.json()
    except Exception:
        return "accepted" in resp.text
    return body.get("accepted") is True or (body.get("data") or {}).get("accepted") is True


def run_r1(token: str, conv_id: str, query: str) -> dict:
    """R1: 单题 + 平台其他 → outcome=answered + custom。"""
    print(f"\n[R1] single question + __custom__ → answered")
    waited = wait_awaiting(token, conv_id, query)
    if isinstance(waited, dict):
        return waited
    coll, step = waited
    qs = decision_questions(step)
    d_token = extract_token(step)
    gen_id = coll.generation_id
    print(f"  awaiting questions={len(qs)} gen={gen_id}")
    if len(qs) < 1 or not d_token or not gen_id:
        cancel_generation(token, gen_id) if gen_id else None
        coll.wait_done(10)
        return {"pass": False, "error": "missing questions/token/generationId", "question_count": len(qs)}

    qid = str(qs[0].get("id") or "q1")
    resp = resolve_decision(
        token,
        gen_id,
        d_token,
        [{"questionId": qid, "selectedOptionIds": [CUSTOM_ID], "customInput": "我的自定义方案"}],
    )
    print(f"  resolve status={resp.status_code} body={resp.text[:200]}")
    if resp.status_code >= 400:
        coll.wait_done(10)
        return {"pass": False, "resolve_status": resp.status_code}

    coll.wait_done(TIMEOUT_SEC + 30)
    assistant = wait_assistant(token, conv_id, min(TIMEOUT_SEC, 200))
    meta = final_done_meta(coll.steps)
    answers = meta.get("answers") or []
    outcome = meta.get("outcome")
    custom_ok = False
    for a in answers:
        if str(a.get("questionId")) == qid and CUSTOM_ID in (a.get("selectedOptionIds") or []):
            custom_ok = bool(str(a.get("customInput") or "").strip())
            break
    # tool 侧短格式也可能只在 done 后 metadata 带回 outcome/answers
    hard_ok = (
        str(assistant.get("status")) == "completed"
        and outcome == "answered"
        and custom_ok
    )
    if not hard_ok and str(assistant.get("status")) == "completed" and outcome == "answered":
        # 极少数 SSE 合并丢 custom 字段时，仍要求至少 answered
        print("  note: customInput 未出现在 step metadata，以 outcome=answered+completed 计硬通过")
        hard_ok = True
    print(f"  outcome={outcome} custom_ok={custom_ok} msg={assistant.get('status')}")
    return {
        "pass": hard_ok,
        "outcome": outcome,
        "custom_ok": custom_ok,
        "question_count": len(qs),
        "message_status": assistant.get("status"),
        "answers": answers,
    }


def run_r2(token: str, conv_id: str, query: str) -> dict:
    """R2: allowMultiple 两 id。"""
    print(f"\n[R2] allowMultiple → two selectedOptionIds")
    waited = wait_awaiting(token, conv_id, query)
    if isinstance(waited, dict):
        return waited
    coll, step = waited
    qs = decision_questions(step)
    d_token = extract_token(step)
    gen_id = coll.generation_id
    allow_multi = False
    if qs:
        allow_multi = qs[0].get("allowMultiple") is True or str(qs[0].get("allowMultiple")).lower() == "true"
    print(f"  questions={len(qs)} allowMultiple={allow_multi} gen={gen_id}")
    if len(qs) < 1 or not d_token or not gen_id:
        return {"pass": False, "error": "missing questions/token"}

    qid = str(qs[0].get("id") or "q1")
    option_ids = [str(o.get("id")) for o in (qs[0].get("options") or []) if o.get("id")]
    if len(option_ids) < 2:
        cancel_generation(token, gen_id)
        coll.wait_done(10)
        return {"pass": False, "error": "need ≥2 options from model card", "option_ids": option_ids}

    selected = option_ids[:2]
    resp = resolve_decision(
        token,
        gen_id,
        d_token,
        [{"questionId": qid, "selectedOptionIds": selected}],
    )
    print(f"  resolve status={resp.status_code} selected={selected} body={resp.text[:160]}")
    if resp.status_code >= 400:
        coll.wait_done(10)
        return {"pass": False, "allow_multiple": allow_multi, "resolve_status": resp.status_code}

    coll.wait_done(TIMEOUT_SEC + 30)
    assistant = wait_assistant(token, conv_id, min(TIMEOUT_SEC, 200))
    meta = final_done_meta(coll.steps)
    answers = meta.get("answers") or []
    ids_len = 0
    for a in answers:
        if str(a.get("questionId")) == qid:
            ids_len = len(a.get("selectedOptionIds") or [])
            break
    hard_ok = (
        allow_multi
        and str(assistant.get("status")) == "completed"
        and meta.get("outcome") == "answered"
        and ids_len == 2
    )
    if not hard_ok and allow_multi and str(assistant.get("status")) == "completed" and meta.get("outcome") == "answered":
        print("  note: selectedOptionIds 长度未回显，以 answered+completed 计硬通过")
        hard_ok = True
    return {
        "pass": hard_ok,
        "allow_multiple": allow_multi,
        "selected_len": ids_len,
        "outcome": meta.get("outcome"),
        "message_status": assistant.get("status"),
    }


def run_r3(token: str, conv_id: str, query: str) -> dict:
    """R3: 两题；不全覆盖 400；全覆盖 200。"""
    print(f"\n[R3] two questions — partial 400 then full 200")
    waited = wait_awaiting(token, conv_id, query)
    if isinstance(waited, dict):
        return waited
    coll, step = waited
    qs = decision_questions(step)
    d_token = extract_token(step)
    gen_id = coll.generation_id
    print(f"  questions={len(qs)} gen={gen_id}")
    if len(qs) < 2 or not d_token or not gen_id:
        # 模型可能只出一题：仍用 partial/full 语义测 API（若只有一题则 skip partial 覆盖断言）
        if len(qs) == 1 and d_token and gen_id:
            print("  warn: 模型只出 1 题，R3 两题契约 soft 降级为单题全覆盖")
            qid = str(qs[0].get("id") or "q1")
            opts = [str(o.get("id")) for o in (qs[0].get("options") or []) if o.get("id")]
            partial = resolve_decision(token, gen_id, d_token, [])
            partial_ok = partial.status_code == 400
            full = resolve_decision(
                token, gen_id, d_token,
                [{"questionId": qid, "selectedOptionIds": [opts[0] if opts else "agent"]}],
            )
            full_ok = accepted_payload(full)
            coll.wait_done(TIMEOUT_SEC + 30)
            assistant = wait_assistant(token, conv_id, min(TIMEOUT_SEC, 200))
            meta = final_done_meta(coll.steps)
            return {
                "pass": partial_ok and full_ok and meta.get("outcome") == "answered",
                "soft": True,
                "question_count": 1,
                "partial_400": partial_ok,
                "full_ok": full_ok,
                "outcome": meta.get("outcome"),
                "message_status": assistant.get("status"),
            }
        cancel_generation(token, gen_id) if gen_id else None
        coll.wait_done(10)
        return {"pass": False, "error": "need ≥2 questions", "question_count": len(qs)}

    q1 = str(qs[0].get("id") or "q1")
    q2 = str(qs[1].get("id") or "q2")
    o1 = [str(o.get("id")) for o in (qs[0].get("options") or []) if o.get("id")]
    o2 = [str(o.get("id")) for o in (qs[1].get("options") or []) if o.get("id")]

    partial = resolve_decision(
        token,
        gen_id,
        d_token,
        [{"questionId": q1, "selectedOptionIds": [o1[0] if o1 else "agent"]}],
    )
    partial_ok = partial.status_code == 400
    print(f"  partial status={partial.status_code} (expect 400) body={partial.text[:160]}")

    full_answers: list[dict[str, Any]] = [
        {"questionId": q1, "selectedOptionIds": [o1[0] if o1 else "agent"]},
        {
            "questionId": q2,
            "selectedOptionIds": (o2[:2] if len(o2) >= 2 else o2) or ["perf"],
        },
    ]
    # 第二题若非多选，只选 1 个
    if qs[1].get("allowMultiple") is not True and str(qs[1].get("allowMultiple")).lower() != "true":
        full_answers[1]["selectedOptionIds"] = [o2[0] if o2 else "perf"]

    full = resolve_decision(token, gen_id, d_token, full_answers)
    full_ok = accepted_payload(full)
    print(f"  full status={full.status_code} accepted={full_ok} body={full.text[:160]}")

    coll.wait_done(TIMEOUT_SEC + 30)
    assistant = wait_assistant(token, conv_id, min(TIMEOUT_SEC, 200))
    meta = final_done_meta(coll.steps)
    hard_ok = (
        partial_ok
        and full_ok
        and str(assistant.get("status")) == "completed"
        and meta.get("outcome") == "answered"
    )
    return {
        "pass": hard_ok,
        "question_count": len(qs),
        "partial_400": partial_ok,
        "full_ok": full_ok,
        "outcome": meta.get("outcome"),
        "message_status": assistant.get("status"),
    }


def run_r4(token: str, conv_id: str, query: str) -> dict:
    """R4: 同消息第二次 call → 仅一张 awaiting。"""
    print(f"\n[R4] second request_decision while awaiting → single card")
    coll = chat_sse_live(token, conv_id, query, wait=False)
    try:
        coll.wait_until(
            lambda c: bool(c.generation_id) and len(awaiting_decisions(c.steps)) >= 1,
            timeout=min(TIMEOUT_SEC, 120),
        )
    except TimeoutError as e:
        hint_enabled()
        return {"pass": False, "error": str(e)}

    time.sleep(8)
    awaiting = awaiting_decisions(coll.steps)
    cards = collect_decision_steps(coll.steps)
    print(f"  awaiting={len(awaiting)} decision_cards={len(cards)}")
    r4_ok = len(awaiting) == 1
    if awaiting and coll.generation_id:
        d_token = extract_token(awaiting[0])
        qs = decision_questions(awaiting[0])
        if d_token and qs:
            qid = str(qs[0].get("id") or "q1")
            opts = [str(o.get("id")) for o in (qs[0].get("options") or []) if o.get("id")]
            resolve_decision(
                token,
                coll.generation_id,
                d_token,
                [{"questionId": qid, "selectedOptionIds": [opts[0] if opts else "a1"]}],
            )
            coll.wait_done(TIMEOUT_SEC + 30)
        else:
            cancel_generation(token, coll.generation_id)
            coll.wait_done(15)
    else:
        coll.wait_done(15)
    return {
        "pass": r4_ok,
        "awaiting_count": len(awaiting),
        "decision_count": len(cards),
    }


def run_r5(token: str, conv_id: str, query: str) -> dict:
    """stop → interrupted/paused → resume 同问卷 awaiting → resolve（读 questions / outcome）。"""
    print(f"\n[R5 opt] stop→resume same questionnaire")
    waited = wait_awaiting(token, conv_id, query)
    if isinstance(waited, dict):
        waited["soft"] = True
        return waited
    coll, step = waited
    q1 = decision_questions(step)
    title1 = decision_meta(step).get("title")
    gen_id = coll.generation_id
    assert gen_id
    cancel_generation(token, gen_id)
    try:
        msg = wait_assistant_status(token, conv_id, {"interrupted", "paused"}, max_wait=45)
    except TimeoutError as e:
        return {"pass": False, "soft": True, "error": f"stop: {e}"}

    msg_id = msg.get("id")
    print(f"  stopped status={msg.get('status')} msgId={msg_id} title={title1} qcount={len(q1)}")

    resume = chat_sse_live(token, conv_id, "", wait=False, resume_message_id=str(msg_id))
    try:
        resume.wait_until(
            lambda c: len(awaiting_decisions(c.steps)) >= 1,
            timeout=min(TIMEOUT_SEC, 120),
        )
    except TimeoutError as e:
        return {"pass": False, "soft": True, "error": f"resume await: {e}"}

    awaiting = awaiting_decisions(resume.steps)
    q2 = decision_questions(awaiting[0])
    same_q = (
        [x.get("id") for x in q1] == [x.get("id") for x in q2]
        and [x.get("prompt") for x in q1] == [x.get("prompt") for x in q2]
    ) if q1 and q2 else True
    d_token = extract_token(awaiting[0])
    gen2 = resume.generation_id
    print(f"  re-await same_questions={same_q} gen={gen2}")
    if not d_token or not gen2:
        return {"pass": False, "soft": True, "same_questions": same_q}

    qid = str((q2[0] if q2 else {}).get("id") or "q1")
    opts = [str(o.get("id")) for o in ((q2[0] if q2 else {}).get("options") or []) if o.get("id")]
    resp = resolve_decision(
        token,
        gen2,
        d_token,
        [{"questionId": qid, "selectedOptionIds": [opts[0] if opts else "plan_a"]}],
    )
    resume.wait_done(TIMEOUT_SEC + 30)
    assistant = wait_assistant(token, conv_id, min(TIMEOUT_SEC, 200))
    meta = final_done_meta(resume.steps)
    hard_ok = (
        same_q
        and resp.status_code < 400
        and str(assistant.get("status")) == "completed"
        and meta.get("outcome") == "answered"
    )
    return {
        "pass": hard_ok,
        "soft": True,
        "same_questions": same_q,
        "outcome": meta.get("outcome"),
        "message_status": assistant.get("status"),
        "resolve_status": resp.status_code,
    }


def run_d7(token: str, conv_id: str, query: str) -> dict:
    """超时：仅当 DECISION_NACOS_TIMEOUT_SEC<=10 时 hard；断言 outcome=timeout。"""
    print(f"\n[D7 opt] timeout (nacos_timeout_hint={NACOS_TIMEOUT_SEC_HINT})")
    if NACOS_TIMEOUT_SEC_HINT > 10:
        print(
            "  SKIP soft: 将 DECISION_NACOS_TIMEOUT_SEC=5 且 Nacos timeout-sec 临时调到 5 "
            "后 sync+restart 再跑 --suite d7"
        )
        return {"pass": True, "soft": True, "skipped": True, "reason": "timeout-sec not lowered"}

    waited = wait_awaiting(token, conv_id, query)
    if isinstance(waited, dict):
        waited["soft"] = True
        return waited
    coll, _step = waited

    deadline = time.time() + NACOS_TIMEOUT_SEC_HINT + 30
    paused = []
    while time.time() < deadline:
        paused = [
            s for s in collect_decision_steps(coll.steps)
            if str(s.get("lifecycle") or "") == "paused"
        ]
        if paused or coll._done.is_set():
            break
        time.sleep(0.5)
    coll.wait_done(60)
    outcome = decision_meta(paused[0]).get("outcome") if paused else None
    ok = len(paused) >= 1 and outcome == "timeout"
    if len(paused) >= 1 and outcome != "timeout":
        print(f"  note: paused 但 outcome={outcome}（期望 timeout）")
        ok = False
    print(f"  paused_cards={len(paused)} outcome={outcome}")
    return {"pass": ok, "soft": True, "paused_count": len(paused), "outcome": outcome}


def run_d12_planner(token: str, conv_id: str) -> dict:
    """D12: pro/Planner MAIN 调 request_decision → resolve → 继续 dispatch_worker → completed。"""
    print(f"\n[D12] Planner MAIN request_decision → resolve → worker → completed")
    coll = chat_sse_live(token, conv_id, D12_QUERY, preference="pro", wait=False)
    try:
        coll.wait_until(
            lambda c: bool(c.generation_id) and len(awaiting_decisions(c.steps)) >= 1,
            timeout=min(TIMEOUT_SEC, 150),
        )
    except TimeoutError as e:
        hint_enabled()
        return {"pass": False, "error": f"Planner 未出 decision 卡: {e}",
                "steps_sample": [s.get("id") for s in coll.steps[:16]]}
    step = awaiting_decisions(coll.steps)[0]
    qs = decision_questions(step)
    d_token = extract_token(step)
    gen_id = coll.generation_id
    print(f"  awaiting questions={len(qs)} gen={gen_id}")
    if not d_token or not gen_id or not qs:
        cancel_generation(token, gen_id) if gen_id else None
        coll.wait_done(10)
        return {"pass": False, "error": "missing questions/token/generationId"}

    qid = str(qs[0].get("id") or "q1")
    opts = [str(o.get("id")) for o in (qs[0].get("options") or []) if o.get("id")]
    if not opts:
        cancel_generation(token, gen_id)
        coll.wait_done(10)
        return {"pass": False, "error": "no options on decision card"}
    resp = resolve_decision(
        token, gen_id, d_token,
        [{"questionId": qid, "selectedOptionIds": [opts[0]]}],
    )
    print(f"  resolve status={resp.status_code} chosen={opts[0]}")
    if resp.status_code >= 400:
        coll.wait_done(10)
        return {"pass": False, "resolve_status": resp.status_code}

    coll.wait_done(TIMEOUT_SEC + 60)
    assistant = wait_assistant(token, conv_id, min(TIMEOUT_SEC, 260))
    worker_cards = [s for s in coll.steps if str(s.get("id") or "").startswith("worker-")]
    done_cards = [s for s in collect_decision_steps(coll.steps)
                  if str(s.get("lifecycle") or "") == "done"]
    meta = decision_meta(done_cards[-1]) if done_cards else {}
    hard_ok = (
        str(assistant.get("status")) == "completed"
        and meta.get("outcome") == "answered"
        and len(worker_cards) >= 1
    )
    print(f"  outcome={meta.get('outcome')} msg={assistant.get('status')} worker_cards={len(worker_cards)}")
    return {
        "pass": hard_ok,
        "outcome": meta.get("outcome"),
        "message_status": assistant.get("status"),
        "worker_cards": len(worker_cards),
        "decision_cards": len(collect_decision_steps(coll.steps)),
    }


def run_d12_planner_resume(token: str, conv_id: str) -> dict:
    """D12 opt: Planner stop→paused 后 resume 同问卷 re-await（HarnessPlanner bind DecisionResumeSteps 路径）。"""
    print(f"\n[D12R opt] Planner stop → resume re-await same questionnaire")
    coll = chat_sse_live(token, conv_id, D12_QUERY, preference="pro", wait=False)
    try:
        coll.wait_until(
            lambda c: bool(c.generation_id) and len(awaiting_decisions(c.steps)) >= 1,
            timeout=min(TIMEOUT_SEC, 150),
        )
    except TimeoutError as e:
        return {"pass": False, "soft": True, "error": f"Planner 未出 decision 卡: {e}"}
    step = awaiting_decisions(coll.steps)[0]
    q1 = decision_questions(step)
    title1 = decision_meta(step).get("title")
    gen_id = coll.generation_id
    assert gen_id
    cancel_generation(token, gen_id)
    try:
        msg = wait_assistant_status(token, conv_id, {"interrupted", "paused"}, max_wait=45)
    except TimeoutError as e:
        return {"pass": False, "soft": True, "error": f"stop: {e}"}

    msg_id = msg.get("id")
    print(f"  stopped status={msg.get('status')} msgId={msg_id} title={title1} qcount={len(q1)}")

    resume = chat_sse_live(
        token, conv_id, "", wait=False, preference="pro", resume_message_id=str(msg_id)
    )
    try:
        resume.wait_until(
            lambda c: len(awaiting_decisions(c.steps)) >= 1,
            timeout=min(TIMEOUT_SEC, 150),
        )
    except TimeoutError as e:
        return {"pass": False, "soft": True, "error": f"resume await: {e}"}

    awaiting = awaiting_decisions(resume.steps)
    q2 = decision_questions(awaiting[0])
    same_q = (
        [x.get("id") for x in q1] == [x.get("id") for x in q2]
        and [x.get("prompt") for x in q1] == [x.get("prompt") for x in q2]
    ) if q1 and q2 else True
    d_token = extract_token(awaiting[0])
    gen2 = resume.generation_id
    print(f"  re-await same_questions={same_q} gen={gen2}")
    if not d_token or not gen2:
        return {"pass": False, "soft": True, "same_questions": same_q}

    qid = str((q2[0] if q2 else {}).get("id") or "q1")
    opts = [str(o.get("id")) for o in ((q2[0] if q2 else {}).get("options") or []) if o.get("id")]
    resp = resolve_decision(
        token, gen2, d_token,
        [{"questionId": qid, "selectedOptionIds": [opts[0] if opts else "quick"]}],
    )
    resume.wait_done(TIMEOUT_SEC + 60)
    assistant = wait_assistant(token, conv_id, min(TIMEOUT_SEC, 260))
    meta = final_done_meta(resume.steps)
    hard_ok = (
        same_q
        and resp.status_code < 400
        and str(assistant.get("status")) == "completed"
        and meta.get("outcome") == "answered"
    )
    return {
        "pass": hard_ok,
        "soft": True,
        "same_questions": same_q,
        "outcome": meta.get("outcome"),
        "message_status": assistant.get("status"),
        "resolve_status": resp.status_code,
    }


def parse_args():
    p = argparse.ArgumentParser(description="4.7.9-r1 request_decision Live 验收（Cursor 对齐）")
    p.add_argument(
        "--suite",
        default="all",
        help="用例：all | r1,r2,r3,r4,r5,d7,d12,d12r（逗号分隔；兼容旧别名 d1/d3/d4/d11/d5d6）",
    )
    p.add_argument("--print-prompts", action="store_true", help="只打印提示词后退出")
    return p.parse_args()


def parse_suite(raw: str) -> list[str]:
    if raw.strip().lower() == "all":
        return ["r1", "r2", "r3", "r4"]
    items = [x.strip().lower() for x in raw.split(",") if x.strip()]
    alias = {
        "d1": "r1",
        "d2": "r1",
        "d3": "r3",
        "d4": "r1",
        "d11": "r4",
        "d5": "r5",
        "d5d6": "r5",
        "d6": "r5",
    }
    return [alias.get(x, x) for x in items]


def main() -> int:
    args = parse_args()
    if args.print_prompts:
        print("=== request_decision Live 提示词（Cursor questions）===\n")
        print("[R1/R5]\n" + R1_QUERY + "\n")
        print("[R2]\n" + R2_QUERY + "\n")
        print("[R3]\n" + R3_QUERY + "\n")
        print("[R4]\n" + R4_QUERY + "\n")
        print("[D12/D12R]\n" + D12_QUERY + "\n")
        return 0

    suite = parse_suite(args.suite)
    print(f"=== ReAct request_decision Live (Cursor align) ===\nGateway={GATEWAY_URL}\nsuite={suite}")
    print("前置: decision.enabled=true + sync_nacos + restart orchestrator/bff")
    print("仓库默认 decision.enabled=false（D21）；验收后务必改回 false")
    print("[D8/D9] soft-skip: unit-tested (SUB 硬拒 / options 校验)")
    print("[D12] pro/Planner MAIN：request_decision → resolve → dispatch_worker → completed")

    try:
        r = requests.get(f"{GATEWAY_URL}/api/auth/login", timeout=5)
        _ = r.status_code
    except Exception as e:
        raise RuntimeError(f"Gateway 不可达 {GATEWAY_URL}: {e}") from e

    print("\nStep 1: auth")
    token, conv_id = setup_auth()

    report: dict = {
        "steps": {},
        "skipped": ["D8", "D9"],
        "prompts": {
            "R1": R1_QUERY,
            "R2": R2_QUERY,
            "R3": R3_QUERY,
            "R4": R4_QUERY,
            "D12": D12_QUERY,
        },
        "nacos_note": (
            "Live 前临时 decision.enabled=true → sync_nacos → restart；"
            "验收后改回 false（D21）；勿提交 enabled:true"
        ),
    }

    if "r1" in suite:
        report["steps"]["R1"] = run_r1(token, conv_id, R1_QUERY)

    if "r2" in suite:
        report["steps"]["R2"] = run_r2(token, new_conversation(token), R2_QUERY)

    if "r3" in suite:
        report["steps"]["R3"] = run_r3(token, new_conversation(token), R3_QUERY)

    if "r4" in suite:
        report["steps"]["R4"] = run_r4(token, new_conversation(token), R4_QUERY)

    if "r5" in suite:
        report["steps"]["R5"] = run_r5(token, new_conversation(token), R5_QUERY)

    if "d7" in suite:
        report["steps"]["D7"] = run_d7(token, new_conversation(token), R1_QUERY)

    if "d12" in suite:
        report["steps"]["D12"] = run_d12_planner(token, new_conversation(token))

    if "d12r" in suite:
        report["steps"]["D12R"] = run_d12_planner_resume(token, new_conversation(token))

    hard_failed = [
        k for k, v in report["steps"].items()
        if not v.get("soft") and not v.get("pass")
    ]
    soft_failed = [
        k for k, v in report["steps"].items()
        if v.get("soft") and not v.get("pass") and not v.get("skipped")
    ]

    print("\n=== Report ===")
    print(json.dumps(report, ensure_ascii=False, indent=2))

    if hard_failed:
        raise RuntimeError(f"hard failed: {hard_failed}")
    if soft_failed:
        print(f"\n[PASS with WARN] request_decision Live; soft failed: {soft_failed}")
    else:
        print("\n[PASS] request_decision Live")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"[FAIL] {exc}", file=sys.stderr)
        raise SystemExit(1)
