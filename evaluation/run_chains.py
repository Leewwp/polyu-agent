#!/usr/bin/env python3
"""任务 B 跑链器：每链一个一次性用户 × 一个会话，逐轮打 /agent/v1/chat。

隔离语义：一次性用户按链分配（链间长期记忆零共享）；每轮记录 SSE 元数据、
答案全文与耗时。凭据不落盘——一次性用户的密码哈希复用本地种子账号档
（创建 SQL 由 --users-sql 落盘供审查），登录密码经 EXP_B_PASSWORD 环境变量传入，
仅存进程环境。

用法：
  EXP_B_USERS_SQL=<users.sql> EXP_B_PASSWORD=<pass> \
  python3 run_chains.py --arm on --chains chains.jsonl \
      --out turns-on.jsonl --base http://localhost:9090/api/ragent
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import pathlib
import sys

import collect_arms as ca  # 复用登录/SSE/代理绕行

USER_TMPL = "bchain-{chain}-{arm}"


def make_users_sql(all_chains, out_sql):
    """一次性用户 INSERT SQL（密码哈希复用种子账号行，密码与该账号一致）。

    id 确定性生成（与执行顺序无关），重复执行用 ON CONFLICT 语义由调用方先 DELETE。
    """
    rows = []
    for i, c in enumerate(all_chains):
        for arm in ("on", "off2", "pilot-on", "pilot-off", "shrink"):
            uid = f"99{len(rows):018d}"
            rows.append((uid, USER_TMPL.format(chain=c["chain_id"], arm=arm)))
    with open(out_sql, "w", encoding="utf-8") as f:
        f.write("-- 任务 B 一次性隔离用户（worktree 实验用，本地库，收尾清理脚本随报告交付）\n")
        for uid, name in rows:
            f.write(f"INSERT INTO t_user (id, username, password, role) "
                    f"SELECT '{uid}', '{name}', password, 'user' FROM t_user "
                    f"WHERE username='test-alice@example.com' "
                    f"AND NOT EXISTS (SELECT 1 FROM t_user WHERE username='{name}');\n")
    return {name: uid for uid, name in rows}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--arm", required=True, help="on|off|pilot-on|pilot-off（进用户名与输出）")
    ap.add_argument("--chains", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--base", default="http://localhost:9090/api/ragent")
    ap.add_argument("--users-sql", default="b-users.sql")
    ap.add_argument("--only", default="", help="只跑指定 chain_id（试点用）")
    args = ap.parse_args()

    chains = [json.loads(l) for l in open(args.chains, encoding="utf-8") if l.strip()]
    all_chains = [json.loads(l) for l in open(args.chains, encoding="utf-8") if l.strip()]
    if args.only:
        chains = [c for c in chains if c["chain_id"] == args.only]
    users = make_users_sql(all_chains, args.users_sql)

    out = pathlib.Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    done = set()
    last_conv = {}  # 断点续跑：链内会话延续（否则新进程会开新会话丢上下文）
    if out.exists():
        for l in open(out, encoding="utf-8"):
            try:
                r = json.loads(l)
                done.add((r["chain_id"], r["no"]))
                if r.get("conversation_id"):
                    last_conv[r["chain_id"]] = r["conversation_id"]
            except Exception:
                pass

    stamp = dt.datetime.now().astimezone().strftime("%Y%m%d-%H%M%S")
    b_pwd = os.environ.get("EXP_B_PASSWORD")
    if not b_pwd:
        print("[chains] 缺 EXP_B_PASSWORD（一次性用户密码，与建号种子账号一致）", file=sys.stderr)
        return 1
    with out.open("a", encoding="utf-8") as f:
        for c in chains:
            cname = USER_TMPL.format(chain=c["chain_id"], arm=args.arm)
            uid = users[cname]
            with ca._req(f"{args.base}/auth/login", "POST",
                         {"username": cname, "password": b_pwd}) as resp:
                token = (json.loads(resp.read().decode()).get("data") or {}).get("token")
            if not token:
                print(f"[chains] 登录失败 {cname}", file=sys.stderr)
                return 1

            conversation_id = last_conv.get(c["chain_id"])
            for t in c["turns"]:
                if (c["chain_id"], t["no"]) in done:
                    continue
                ts_sent = dt.datetime.now().astimezone().isoformat()
                chat = ca.chat_once(args.base, token, t["question"], conversation_id)
                conversation_id = chat.get("conversation_id") or conversation_id
                answer = ""
                if conversation_id:
                    try:
                        m = ca.last_assistant(args.base, token, conversation_id)
                        answer = (m or {}).get("content") or ""
                    except Exception as e:  # noqa: BLE001
                        answer = "".join(chat.get("stream_answer") or [])
                        print(f"[chains] WARN messages {c['chain_id']}#{t['no']}: {e}",
                              file=sys.stderr)
                row = {
                    "arm": args.arm, "chain_id": c["chain_id"], "no": t["no"],
                    "question": t["question"], "answer": answer,
                    "conversation_id": conversation_id,
                    "run_id": f"memeval-{args.arm}-{stamp}",
                    "ts_sent": ts_sent,
                    "ts_done": dt.datetime.now().astimezone().isoformat(),
                    "finish_status": chat.get("finish_status"),
                    "message_status": None,
                    "n_tool_events": chat.get("n_tool_events"),
                }
                f.write(json.dumps(row, ensure_ascii=False) + "\n")
                f.flush()
                print(f"[chains] {c['chain_id']}#{t['no']}/{c['n_turns']} "
                      f"ans={len(answer)}ch", flush=True)
    print(f"[chains] OK arm={args.arm} -> {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
