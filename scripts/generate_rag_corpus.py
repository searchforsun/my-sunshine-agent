#!/usr/bin/env python3
"""生成 corpus-50 语料（全新 docId，无历史 *-policy-v1 兼容）。

输出：
  {out_dir}/*.md
  {out_dir}/manifest.json
  {out_dir}/eval_suite.json   # regression / adversarial / smoke 评测集 SSOT
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from sunshine_lib import ROOT

DEFAULT_OUT = ROOT / "docs/knowledge"

# (doc_id, display_name, domain, unique_fact, positive_query, adversarial_query)
# unique_fact 写入正文第 3 章；query 不含历史 leave-policy 等 ID
TOPICS: list[tuple[str, str, str, str, str, str]] = [
    ("c50-hr-leave", "员工假期与休假管理制度", "hr",
     "满 3 年工龄员工每年额外享有名为「青松假」的 12 天带薪特别假，须提前 10 个工作日在假勤系统申请。",
     "青松假有多少天、怎么申请", "青松假能休几天啊要提前多久报"),
    ("c50-hr-expense", "费用报销与票据核销制度", "finance",
     "市内网约车单日报销上限为 168 元；超限部分须附「紧急外勤说明」并由中心负责人电子签。",
     "市内网约车报销上限多少", "打车一天最多能报多少钱"),
    ("c50-hr-attendance", "工时考勤与加班补偿规定", "hr",
     "连续迟到折算：当月迟到满 4 次记为 0.5 个旷工日，记入「霜降考勤」异常台账。",
     "迟到几次记为半日旷工", "这个月迟到四次算不算旷工"),
    ("c50-hr-onboard", "新人入职与试用期指引", "hr",
     "入职第 1 个工作日须完成「晨星清单」：工卡、邮箱、VPN、安全培训测验四项全部打勾方可开通业务系统。",
     "晨星清单包含哪些入职事项", "新人第一天要办完哪几样才能开系统"),
    ("c50-fin-approval", "财务签批与授权矩阵", "finance",
     "非标采购金额 ≥ 8800 元须走「银杏签批」链路：业务负责人 → 财务 BP → CFO。",
     "银杏签批适用什么金额", "八千八以上采购谁批"),
    ("c50-fin-invoice", "发票合规与进项管理手册", "finance",
     "电子发票查重窗口为开票日起 180 天；重复提交触发「重影票」拦截并通知财务共享中心。",
     "电子发票查重窗口多久", "发票重复报销会被拦吗"),
    ("c50-fin-budget", "部门费用预算控制办法", "finance",
     "季度滚动预测偏差超过 7% 须提交「秋分预警」说明，否则冻结非刚性招待科目。",
     "秋分预警在什么情况下触发", "预算偏差百分之七要不要写说明"),
    ("c50-hr-remote", "混合办公与远程协作规范", "hr",
     "远程办公每周上限 2.5 天，申请单备注须填写「云栖日」编码，核心站会禁止关闭摄像头。",
     "云栖日远程上限几天", "一周在家办公最多几天"),
    ("c50-sec-data", "数据分级与保密操作规程", "security",
     "机密级文件外发须使用「锁钥通道」并双人复核；禁止微信/个人网盘传输。",
     "机密文件外发走什么通道", "机密资料能不能发微信"),
    ("c50-fin-travel", "差旅预订与补助标准", "finance",
     "一线城市差旅住宿上限 620 元/晚；餐补固定 95 元/天，出差满 3 天另发通讯补助 25 元/天。",
     "一线城市住宿和餐补标准", "北上广住宿一晚能报多少"),
    ("c50-hr-scenario", "跨制度场景处理速查", "hr",
     "出差途中突发疾病：先按差旅中止，再按病假补单，系统标记场景码「旅病交叉-09」。",
     "出差生病怎么同时处理差旅和病假", "在外面病了差旅单怎么改"),
    ("c50-hr-perf", "绩效目标与复盘管理办法", "hr",
     "绩效周期为自然半年；强制分布仅作校准参考，禁止以「末位淘汰」名义单方解除。",
     "绩效多久评一次、能否末位淘汰", "半年绩效有没有强制淘汰"),
    ("c50-hr-pay", "薪酬福利与调薪窗口制度", "hr",
     "常规调薪窗口为每年 4 月与 10 月；窗口外调薪须 CEO 签批「破窗单」。",
     "调薪窗口是哪两个月", "不在四月十月调薪要谁批"),
    ("c50-hr-recruit", "招聘面试与录用规范", "hr",
     "社招技术岗至少 3 轮：专业面、协作面、价值观面；价值观面不通过不得发 offer。",
     "社招技术岗面试几轮", "价值观面挂了还能发 offer 吗"),
    ("c50-hr-train", "培训学分与外训申请制度", "hr",
     "正式员工年度必修学分 24 分；外训单次超过 3000 元须培训委员会预审。",
     "年度必修学分多少", "外训三千以上要谁审"),
    ("c50-hr-conduct", "职业道德与利益冲突准则", "hr",
     "员工及其直系亲属与供应商存在利益关系须 5 个工作日内申报「晴空表」。",
     "利益冲突要填什么表", "亲戚是供应商要不要申报"),
    ("c50-hr-discipline", "奖惩与听证处理办法", "hr",
     "拟记大过及以上处分须启动听证，员工可于 3 个工作日内提交书面申辩。",
     "大过处分有没有听证", "要记大过几天内可以申辩"),
    ("c50-adm-asset", "固定资产领用与报废规定", "admin",
     "单台价值 ≥ 2000 元设备须贴「资产橙标」并录入资产系统，离职未归还不得办结。",
     "多少钱以上要贴资产橙标", "电脑不还能不能办离职"),
    ("c50-adm-procure", "采购比价与供应商准入", "admin",
     "常规采购须至少 3 家比价；战略供应商须通过「澄江准入」安全与合规评估。",
     "采购要比几家价", "战略供应商准入叫什么"),
    ("c50-adm-meeting", "会议议程与纪要规范", "admin",
     "超过 8 人会议须提前 24 小时发布议程；纪要 2 个工作日内归档并指定决议负责人。",
     "大会议程要提前多久发", "纪要几天内要归档"),
    ("c50-adm-visitor", "访客预约与门禁管理", "admin",
     "访客须提前预约并获取当日「岚通行证」；无陪同不得进入研发区。",
     "访客通行证叫什么", "客人能不能自己进研发区"),
    ("c50-it-account", "账号开通与权限回收规范", "it",
     "调岗 3 个工作日内须完成权限复核；离职日 18:00 前关闭全部生产账号。",
     "调岗后多久复核权限", "离职当天账号几点关"),
    ("c50-it-change", "生产变更与发布窗口", "it",
     "常规变更窗口为周二/周四 21:00–23:00；无回滚预案的变更禁止进入「星轨发布」。",
     "变更窗口是周几几点", "没有回滚能不能发版"),
    ("c50-it-incident", "故障分级与应急响应", "it",
     "P0 故障须 15 分钟内拉起战时群，4 小时内给出用户影响说明。",
     "P0 故障响应时限", "重大故障多久要说明影响"),
    ("c50-sec-retain", "日志留存与数据销毁", "security",
     "安全审计日志至少留存 210 天；销毁须双人在场并签署「灰烬单」。",
     "安全日志留存多少天", "销毁数据要不要双人"),
    ("c50-sec-privacy", "个人信息保护操作指南", "security",
     "敏感个人信息存储须字段级加密；对外共享须最小必要并记录「澄明台账」。",
     "敏感个人信息怎么存", "对外共享个人信息要记什么台账"),
    ("c50-sec-vendor", "外包与第三方安全管理", "security",
     "外包人员接入生产网络须签署「外岸承诺」并使用专用堡垒机账号。",
     "外包接生产要签什么", "外包能不能直接用普通账号"),
    ("c50-sec-llm", "生成式AI使用与泄密防控", "security",
     "禁止将机密/客户原始数据粘贴至外部大模型；对外生成内容须人工审核后发布。",
     "机密数据能否贴到外部大模型", "AI 写的对外文案要不要人工审"),
    ("c50-it-api", "API 版本与限流治理", "it",
     "破坏性变更须保留旧主版本至少 90 天；默认租户 QPS 配额 120。",
     "API 旧版本保留多久", "默认租户 QPS 多少"),
    ("c50-it-alert", "告警分级与值班轮转", "it",
     "P1 告警值班须 10 分钟内确认；未确认自动升级至值班经理。",
     "P1 告警多久要确认", "告警没人接会怎样"),
    ("c50-it-capacity", "容量规划与成本回收", "it",
     "闲置计算资源连续 14 天 CPU < 5% 触发「枯枝回收」工单。",
     "什么情况触发枯枝回收", "资源闲置两周会怎样"),
    ("c50-it-qa", "发布准入与质量门禁", "it",
     "生产发布前冒烟用例通过率须 100%，且近 30 天回滚演练记录有效。",
     "发布前冒烟要求", "没有回滚演练能不能发生产"),
    ("c50-legal-contract", "合同审查与用印流程", "legal",
     "非模板合同须法务审核；电子章「云玺」仅授权合同管理员使用。",
     "非模板合同谁审", "云玺电子章谁能用"),
    ("c50-legal-ip", "知识产权与开源合规", "legal",
     "引入 GPL 类许可证须开源委员会批准；职务发明知识产权归属公司。",
     "引入 GPL 要谁批", "职务发明归谁"),
    ("c50-legal-report", "合规举报与调查保护", "legal",
     "提供匿名举报渠道「听潮热线」；禁止对善意举报人打击报复。",
     "匿名举报渠道叫什么", "举报人受不受保护"),
    ("c50-adm-crisis", "舆情与对外口径管理", "admin",
     "媒体问询统一由品牌对外官回复；员工个人账号禁止擅自代表公司表态。",
     "媒体问询谁回复", "员工能不能自己发公司声明"),
    ("c50-adm-esg", "ESG 与供应链责任纲要", "admin",
     "范围三排放按年披露；一级供应商须签署「青链承诺」。",
     "供应商要签什么 ESG 承诺", "范围三排放披露吗"),
    ("c50-adm-safety", "消防应急与疏散演练", "admin",
     "全员疏散演练每年至少 1 次；集合点为东门「梧桐广场」。",
     "疏散集合点在哪", "消防演练一年几次"),
    ("c50-hr-health", "职业健康与 EAP 服务", "hr",
     "EAP 心理援助热线「暖泉」7×12 小时；年度体检须在入职满 6 个月内完成。",
     "EAP 热线叫什么", "体检最晚什么时候做"),
    ("c50-adm-archive", "电子归档与借阅规则", "admin",
     "合同类档案须在签署后 15 个工作日内归档；借阅机密档须二级负责人批准。",
     "合同多久内要归档", "借机密档案谁批"),
    ("c50-pmo-project", "立项评审与阶段门禁", "pmo",
     "立项须通过「澄波门」评审；未过门禁止占用研发人力超过 2 人周。",
     "立项评审叫什么门", "没过评审能不能加人"),
    ("c50-pmo-rd", "需求变更与冻结窗口", "pmo",
     "迭代冻结窗口前 48 小时停止非紧急需求变更；紧急变更走「破冰通道」。",
     "需求冻结前多久停变更", "紧急需求走什么通道"),
    ("c50-biz-sla", "客户SLA与升级路径", "biz",
     "企业版 P1 工单响应时限 30 分钟；超时自动升级至客户成功总监。",
     "企业版 P1 响应多久", "SLA 超时升给谁"),
    ("c50-biz-sales", "销售折扣与返点审批", "biz",
     "折扣低于目录价 85 折须销售 VP 批准；返点方案须合规预审。",
     "几折以下要销售 VP 批", "返点要不要合规审"),
    ("c50-biz-partner", "渠道伙伴分级与分润", "biz",
     "伙伴分金/银/铜三级；金级分润上限 18%，冲突客户以先报备为准。",
     "金级伙伴分润上限", "客户冲突怎么定"),
    ("c50-biz-market", "市场活动预算与核销", "biz",
     "单场活动预算超 5 万元须品牌委员会立项；物料须过「观象审核」。",
     "活动预算五万以上谁立项", "物料审核叫什么"),
    ("c50-legal-disclose", "对外披露与禁发清单", "legal",
     "未公开财报数据列入禁发清单；对外演讲稿须提前 3 个工作日送审。",
     "未公开财报能不能外讲", "演讲稿提前几天送审"),
    ("c50-it-bc", "业务连续性与灾备切换", "it",
     "核心交易系统 RTO ≤ 2 小时、RPO ≤ 15 分钟；每年至少一次切换演练。",
     "核心系统 RTO RPO 目标", "灾备演练一年几次"),
    ("c50-adm-space", "工位申请与会议室预约", "admin",
     "固定工位申请须部门编制内；会议室「听雨」可预约最长 2 小时，超时自动释放。",
     "听雨会议室最长订多久", "工位申请有什么条件"),
    ("c50-hr-intern", "实习与兼职用工管理", "hr",
     "实习最长连续周期 6 个月；转正评估须导师与用人经理双签「青苗表」。",
     "实习最长几个月", "转正评估要签什么表"),
]

NEGATIVES = [
    ("n01", "今天 A 股大盘怎么走", "negative"),
    ("n02", "附近哪家火锅评分最高", "negative"),
    ("n03", "帮我写一段 Python 爬虫教程", "negative"),
    ("n04", "比特币现在什么价格", "negative"),
    ("n05", "世界杯冠军是谁", "negative"),
]


def build_document(doc_id: str, display_name: str, domain: str, fact: str, index: int, target: int = 0) -> str:
    """生成单篇制度正文。禁止凑字填充与截断；长度随内容自然伸缩。"""
    del target  # 保留签名兼容，不再对齐字数
    token = f"C50-{index + 1:03d}-{doc_id.split('-')[-1].upper()}"
    # 按事实句拆出可检索的配套条款（不重复粘贴同一段话）
    return "\n".join(
        [
            f"# {display_name}",
            "",
            f"> 文档编号：`{doc_id}` · 领域：{domain} · 语料：corpus-50 · 锚点令牌：`{token}`",
            "",
            "## 1. 目的与适用范围",
            "",
            f"本制度规范 **{display_name}** 的申请、审批、执行与归档，适用于公司全体正式员工、"
            f"试用期员工，以及经授权接入相关系统的外包驻场人员。与上位法或集团制度冲突时，以上位规定为准。",
            "",
            "## 2. 基本原则",
            "",
            "- **事前审批**：业务发生前完成必要审批；紧急情形可口头报备并在下一工作日补单。",
            "- **最小必要**：仅采集与处理完成事项所需的数据与权限。",
            "- **权责一致**：经办、审批、复核角色分离，禁止同一人闭环全链路。",
            "- **证据留痕**：关键动作须在系统留痕；电子或纸质至少保留一类可追溯凭证。",
            "",
            "## 3. 核心规定（检索锚点）",
            "",
            f"1. {fact}",
            f"2. 本制度检索锚点令牌为 `{token}`，用于评测与抽检，不得删除或改写。",
            "3. 与其他制度交叉时，以本领域专章及场景说明为准；争议由归口部门会同相关方裁定。",
            "",
            "## 4. 组织职责",
            "",
            "| 角色 | 职责 |",
            "|------|------|",
            f"| 经办人 | 发起申请、补充材料、跟踪闭环（{domain}） |",
            "| 直线主管 | 初审合理性与资源安排 |",
            "| 归口部门 | 制度解释、抽检、例外备案与修订 |",
            "| 系统管理员 | 权限配置、审计日志与主数据一致性 |",
            "",
            "## 5. 标准流程",
            "",
            "1. **发起**：在对应系统填写申请，挂接必要附件。",
            "2. **审批**：按金额/风险等级路由至有权审批人；普通事项原则上 3 个工作日内完成。",
            "3. **执行**：按批准结果办理；变更须重新审批或走变更单。",
            "4. **复核与归档**：归口或共享中心抽检，完成后归档可检索。",
            "",
            "## 6. 申请与材料",
            "",
            f"申请须写明事由、时间范围、涉及对象及与「{display_name}」相关的关键指标。"
            "材料不全的，审批人可一次退回补正；补正期限不超过 5 个工作日。",
            "",
            "## 7. 时效与例外",
            "",
            "- 普通审批：3 个工作日；涉及多级签批的，每级另计 2 个工作日。",
            "- 例外备案：须说明理由、期限与替代控制措施，由归口部门编号存档。",
            f"- 核心锚点条款（§3.1）不得以例外方式架空：{fact}",
            "",
            "## 8. 系统与审计",
            "",
            "主数据须与人事/财务/权限源一致；操作须可审计。禁止以线下台账长期替代正式系统记录。"
            "抽检发现违规的，按 §10 处理。",
            "",
            "## 9. 场景说明",
            "",
            f"- **常规**：材料齐全、符合 §3 锚点条件，走标准审批。",
            f"- **交叉**：同时触及其他制度时，先满足本制度锚点，再并联相关制度流程。",
            f"- **紧急**：可口头报备后补单，补单须标注紧急原因与报备人。",
            "",
            "## 10. 禁止事项与违规处理",
            "",
            "禁止伪造材料、拆单规避、账号共享、违规外发敏感数据、删除或篡改锚点条款。"
            "视情节给予警告、记过直至解除劳动合同；涉嫌违法的，移送有权机关。",
            "",
            "## 11. 附则",
            "",
            "本制度由归口部门解释，自发布之日起施行。修订须保留版本号与变更说明。",
            "",
        ]
    )


def main() -> int:
    parser = argparse.ArgumentParser(description="生成 corpus-50 语料与评测集")
    parser.add_argument("--out-dir", default=str(DEFAULT_OUT))
    parser.add_argument("--count", type=int, default=50)
    parser.add_argument(
        "--chars",
        type=int,
        default=0,
        help="已废弃：不再按目标字数填充/截断；保留参数仅兼容旧调用",
    )
    parser.add_argument("--clean", action="store_true")
    args = parser.parse_args()

    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    if args.clean:
        for p in out_dir.glob("*.md"):
            p.unlink()
        for name in ("manifest.json", "eval_suite.json"):
            f = out_dir / name
            if f.exists():
                f.unlink()

    topics = TOPICS[: args.count]
    if len(topics) < args.count:
        raise SystemExit(f"TOPICS 仅 {len(TOPICS)} 篇，不足 {args.count}")

    manifest = []
    for i, (doc_id, display_name, domain, fact, _pq, _aq) in enumerate(topics):
        text = build_document(doc_id, display_name, domain, fact, i)
        path = out_dir / f"{display_name}.md"
        path.write_text(text, encoding="utf-8")
        manifest.append(
            {
                "docId": doc_id,
                "displayName": display_name,
                "domain": domain,
                "path": path.name,
                "chars": len(text),
                "anchor": fact,
            }
        )
        print(f"[{i + 1:02d}/{len(topics)}] {doc_id} chars={len(text)}")

    (out_dir / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    eval_suite = build_eval_suite(topics)
    (out_dir / "eval_suite.json").write_text(
        json.dumps(eval_suite, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    lengths = [m["chars"] for m in manifest]
    print(
        f"[DONE] docs={len(manifest)} avg={sum(lengths)//len(lengths)} "
        f"min={min(lengths)} max={max(lengths)} "
        f"eval_items_regression={len(eval_suite['suites']['sunshine-regression']['items'])}"
    )
    # 质量门：禁止截断标记；禁止「扩展条款·续」凑字块
    bad = []
    for m in manifest:
        text = (out_dir / m["path"]).read_text(encoding="utf-8")
        if "正文长度对齐截断" in text or "扩展条款（" in text:
            bad.append(m["docId"])
    if bad:
        print(f"[FAIL] padding/truncation markers remain: {bad}")
        return 1
    return 0


def build_eval_suite(topics: list[tuple[str, str, str, str, str, str]]) -> dict:
    regression = []
    adversarial = []
    for i, (doc_id, _name, domain, _fact, pos_q, adv_q) in enumerate(topics):
        regression.append(
            {
                "itemKey": f"q{i + 1:03d}",
                "query": pos_q,
                "itemType": "positive",
                "relevantDocIds": [doc_id],
                "relevantKeywords": [],
                "category": domain,
                "expectEmpty": False,
            }
        )
        adversarial.append(
            {
                "itemKey": f"q_adv_{i + 1:03d}",
                "query": adv_q,
                "itemType": "positive",
                "relevantDocIds": [doc_id],
                "relevantKeywords": [],
                "category": "adversarial",
                "expectEmpty": False,
            }
        )
    for item_key, query, category in NEGATIVES:
        neg = {
            "itemKey": item_key,
            "query": query,
            "itemType": "negative",
            "relevantDocIds": [],
            "relevantKeywords": [],
            "category": category,
            "expectEmpty": True,
        }
        regression.append(neg)
        adversarial.append({**neg, "itemKey": f"q_adv_{item_key}"})

    smoke_pos = [q for i, q in enumerate(regression) if q["itemType"] == "positive" and i % 5 == 0]
    smoke_neg = [q for q in regression if q["itemType"] == "negative"]
    smoke = []
    for i, q in enumerate(smoke_pos + smoke_neg):
        smoke.append({**q, "itemKey": f"s{i + 1:03d}"})

    gates = {
        "recallAt3Min": 0.90,
        "recallAt5Min": 0.94,
        "mrrMin": 0.85,
        "emptyRatePositiveMax": 0.02,
        "emptyRateNegativeMin": 0.0,
        "latencyP95MsMax": 20000,
    }
    return {
        "version": "corpus-50",
        "suites": {
            "sunshine-regression": {
                "displayName": "corpus50 标准回归",
                "description": "基于 corpus-50 全新语料的检索回归集",
                "gates": gates,
                "items": regression,
            },
            "sunshine-adversarial": {
                "displayName": "corpus50 难例对抗",
                "description": "口语化改写难例（对齐 corpus-50）",
                "gates": {**gates, "recallAt5Min": 0.88, "mrrMin": 0.80},
                "items": adversarial,
            },
            "sunshine-smoke": {
                "displayName": "corpus50 冒烟门禁",
                "description": "发布/切换配置用冒烟子集",
                "gates": gates,
                "items": smoke,
            },
        },
    }


if __name__ == "__main__":
    raise SystemExit(main())
