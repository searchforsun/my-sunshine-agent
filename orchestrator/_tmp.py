import json, urllib.request
query = "\u5148\u68c0\u7d22\u5dee\u65c5\u62a5\u9500\u76f8\u5173\u5236\u5ea6\uff0c\u518d\u67e5\u8be2\u5f85\u5ba1\u6279\u62a5\u9500\u5355\uff0c\u5e76\u5bf9\u6bcf\u6761\u505a\u5408\u89c4\u5206\u6790\u540e\u7ed9\u51fa\u7ed3\u8bba"
system = (
    "You are Workflow Planner. Output ONE JSON object only, no markdown, no prose.\n"
    "Format: {\"planId\":null,\"reason\":\"...\",\"nodes\":[...],\"edges\":[...]}\n"
    "Node types: rag|tool|llm|agent|answer. Tools: search_knowledge, list_finance_messages, get_finance_message_detail.\n"
    "Skills: compliance-check, policy-review. Must include answer node and edges start->...->answer."
)
body = json.dumps({"model":"deepseek-v4-flash","messages":[{"role":"system","content":system},{"role":"user","content":query}],"max_tokens":1024,"temperature":0}).encode()
req = urllib.request.Request("http://localhost:8300/v1/chat/completions", data=body, headers={"Content-Type":"application/json","Authorization":"Bearer sunshine-gateway"})
resp = urllib.request.urlopen(req, timeout=90)
data = json.loads(resp.read())
content = data["choices"][0]["message"]["content"]
print("RAW_LEN", len(content))
print(content[:2500])
print("STARTS_BRACE", content.strip()[:1] == "{")
