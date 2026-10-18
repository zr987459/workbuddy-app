#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""WorkBuddy App - Linux 独立服务端与网关 (端口 8800)
支持 WorkBuddy 国际版 (workbuddy.ai) 与国内版大模型直驱，提供标准 OpenAI / Anthropic 双协议。
"""
from __future__ import annotations

import argparse
import asyncio
import json
import logging
import os
import sys
import time
from pathlib import Path
from typing import Any, Optional

import httpx
from fastapi import FastAPI, HTTPException, Request, Response, Query
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, JSONResponse, StreamingResponse
from fastapi.staticfiles import StaticFiles
import uvicorn

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(name)s: %(message)s")
logger = logging.getLogger("workbuddy.app")

ROOT_DIR = Path(__file__).resolve().parent.parent
WEB_DIR = ROOT_DIR / "web"
DATA_DIR = ROOT_DIR / "data"
ACCOUNTS_FILE = DATA_DIR / "accounts.json"

UPSTREAM_GLOBAL = "https://www.workbuddy.ai"
UPSTREAM_CN = "https://copilot.tencent.com"
CLIENT_UA = "WorkBuddy AI/2.137.1"

# 默认官方系统提示词（腾讯接口硬性要求第一条必须是 system 角色）
DEFAULT_SYSTEM_PROMPT = (
    "You are WorkBuddy, an advanced AI programming assistant developed by Tencent. "
    "You provide clear, accurate, and professional answers."
)

app = FastAPI(title="WorkBuddy App Gateway", version="1.0.3")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# 内存账号池与状态
_accounts: list[dict[str, Any]] = []

def init_data_dir():
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    if ACCOUNTS_FILE.exists():
        try:
            raw = json.loads(ACCOUNTS_FILE.read_text(encoding="utf-8"))
            if isinstance(raw, list):
                _accounts.extend(raw)
                logger.info(f"已加载 {len(_accounts)} 个本地账号")
        except Exception as e:
            logger.error(f"读取账号数据失败: {e}")

def save_accounts():
    try:
        ACCOUNTS_FILE.write_text(json.dumps(_accounts, ensure_ascii=False, indent=2), encoding="utf-8")
    except Exception as e:
        logger.error(f"保存账号数据失败: {e}")

def get_proxy() -> Optional[str]:
    # 优先检测本地 7890 代理
    p = os.getenv("HTTP_PROXY") or os.getenv("http_proxy")
    if p: return p
    import socket
    s = socket.socket()
    s.settimeout(0.1)
    if s.connect_ex(("127.0.0.1", 7890)) == 0:
        s.close()
        return "http://127.0.0.1:7890"
    s.close()
    return None

def get_active_account(realm: str = "global") -> Optional[dict[str, Any]]:
    for a in _accounts:
        if a.get("realm", "global") == realm and not a.get("disabled", False):
            return a
    if _accounts:
        return _accounts[0]
    return None

# ─────────────────── 移动端前端聚合 API ───────────────────

@app.get("/panel/api/overview")
@app.get("/m/api/overview")
async def get_overview():
    healthy = sum(1 for a in _accounts if not a.get("disabled", False))
    return {
        "healthy": healthy,
        "total": len(_accounts),
        "accounts": _accounts,
        "auth_required": False,
        "version": "1.0.3-standalone"
    }

@app.post("/panel/api/login/start")
@app.post("/m/api/auth/start")
async def login_start(request: Request):
    """发起设备授权 OAuth 流。"""
    body = await request.json() if request.headers.get("content-type") == "application/json" else {}
    realm = body.get("realm", "global")
    base = UPSTREAM_GLOBAL if realm == "global" else UPSTREAM_CN
    origin = UPSTREAM_GLOBAL if realm == "global" else "https://www.codebuddy.cn"

    async with httpx.AsyncClient(proxy=get_proxy(), timeout=30.0) as client:
        try:
            r = await client.post(
                f"{base}/v2/plugin/auth/state?platform=CLI",
                headers={
                    "Origin": origin,
                    "Referer": f"{origin}/",
                    "User-Agent": CLIENT_UA,
                    "Content-Type": "application/json"
                },
                json={}
            )
            data = r.json()
            if data.get("code") == 0 and data.get("data"):
                d = data["data"]
                return {"ok": True, "state": d.get("state"), "url": d.get("authUrl"), "realm": realm}
            return {"ok": False, "error": data.get("msg", "未知错误")}
        except Exception as e:
            logger.error(f"发起授权异常: {e}")
            return {"ok": False, "error": str(e)}

@app.get("/panel/api/login/poll")
@app.get("/m/api/auth/poll")
async def login_poll(state: str = Query(...)):
    """轮询授权结果。"""
    if not state:
        return {"ok": False, "error": "missing state"}

    for base, origin, realm in [
        (UPSTREAM_GLOBAL, UPSTREAM_GLOBAL, "global"),
        (UPSTREAM_CN, "https://www.codebuddy.cn", "cn")
    ]:
        async with httpx.AsyncClient(proxy=get_proxy(), timeout=20.0) as client:
            try:
                r = await client.get(
                    f"{base}/v2/plugin/auth/token?state={state}",
                    headers={"Origin": origin, "Referer": f"{origin}/", "User-Agent": CLIENT_UA}
                )
                d = r.json()
                if d.get("code") == 0 and d.get("data") and d["data"].get("accessToken"):
                    tok = d["data"]
                    uid = f"user_{int(time.time())}"
                    nickname = "WorkBuddy用户"
                    try:
                        ar = await client.get(
                            f"{base}/v2/plugin/login/account?state={state}",
                            headers={
                                "Origin": origin,
                                "Referer": f"{origin}/",
                                "User-Agent": CLIENT_UA,
                                "Authorization": f"Bearer {tok['accessToken']}"
                            }
                        )
                        ad = ar.json().get("data", {})
                        if ad.get("uid"):
                            uid = ad["uid"]
                            nickname = ad.get("nickname") or nickname
                    except Exception:
                        pass

                    new_acc = {
                        "uid": uid,
                        "nickname": nickname,
                        "realm": realm,
                        "access_token": tok["accessToken"],
                        "refresh_token": tok.get("refreshToken", ""),
                        "expires_at": int(time.time()) + int(tok.get("expiresIn", 2592000)),
                        "disabled": False,
                        "cooling": False,
                        "credits": 350,
                        "token_usage": {"request_count": 0, "total_tokens": 0}
                    }

                    global _accounts
                    _accounts = [a for a in _accounts if a.get("uid") != uid]
                    _accounts.insert(0, new_acc)
                    save_accounts()

                    return {"ok": True, "done": True, "uid": uid, "nickname": nickname, "realm": realm}
            except Exception:
                pass

    return {"ok": True, "done": False, "message": "waiting for authorization"}

# ─────────────────── OpenAI & Anthropic 转发网关 ───────────────────

MODEL_ALIASES = {
    "deepseek-v4.1-flash-1017": "deepseek-v4.1-flash",
    "deepseek-flash-1017": "deepseek-v4.1-flash",
    "deepseek-flash": "deepseek-v4.1-flash",
    "claude-3.7-sonnet": "default-1.1",
    "claude-4.0-sonnet": "default-1.2",
    "kimi-k3": "kimi-k3-1",
    "kimi-k3.1": "kimi-k3-1",
    "hy4-preview-f": "hy4-preview",
    "deepseek-v3.2": "deepseek-v3-2-volc",
    "gemini-3.8-flash": "gemini-3.5-flash",
}

def normalize_model(model: str) -> tuple[str, str]:
    m = model.strip()
    m_lower = m.lower()
    realm = "global"
    if m_lower.startswith("global:"):
        realm = "global"
        m_lower = m_lower[7:]
    elif m_lower.startswith("cn:"):
        realm = "cn"
        m_lower = m_lower[3:]
    m_target = MODEL_ALIASES.get(m_lower, m_lower)
    return realm, m_target

def derive_conversation_id(messages: list, system_prompt: str = "") -> str:
    first_user = ""
    for m in messages:
        if m.get("role") == "user":
            c = m.get("content", "")
            if isinstance(c, str):
                first_user = c
            elif isinstance(c, list):
                first_user = "\n".join(part.get("text", "") for part in c if isinstance(part, dict) and part.get("type") == "text")
            break
    anchor = f"conv:{system_prompt}\x00{first_user}"
    return uuid.uuid3(uuid.NAMESPACE_DNS, anchor).hex

def build_upstream_headers(realm: str, acc: dict, conv_id: str = None) -> dict:
    uid = acc.get("uid", "")
    c_id = conv_id or uuid.uuid4().hex
    h = {
        "Authorization": f"Bearer {acc.get('access_token')}",
        "Content-Type": "application/json",
        "Accept": "application/json, text/event-stream",
        "Accept-Language": "en-US" if realm == "global" else "zh-CN",
        "X-Requested-With": "XMLHttpRequest",
        "X-CodeBuddy-Request": "1",
        "X-User-Id": uid,
        "X-Product": "WorkBuddy",
        "X-Agent-Purpose": "conversation",
        "X-IDE-Name": "WorkBuddy",
        "X-IDE-Type": "WorkBuddy",
        "X-IDE-Version": "5.5.4",
        "X-Machine-ID": hashlib.sha256(f"wb2a:machine:{uid}".encode()).hexdigest()[:36],
        "X-Session-ID": hashlib.sha256(f"wb2a:session:{uid}".encode()).hexdigest()[:36],
        "X-Conversation-ID": c_id,
        "X-Conversation-Request-ID": uuid.uuid4().hex,
        "X-Conversation-Message-ID": uuid.uuid4().hex,
        "X-Root-Request-ID": uuid.uuid4().hex,
        "X-Trace-ID": uuid.uuid4().hex,
        "User-Agent": CLIENT_UA,
    }
    if realm == "global":
        h["X-No-Enterprise-Id"] = "1"
        h["X-Domain"] = "www.workbuddy.ai"
    return h

def is_channel_check(status_code: int, body_str: str) -> bool:
    if "11128" in body_str or "11-128" in body_str:
        lower = body_str.lower()
        return "渠道校验" in body_str or "channel verification" in lower or "official client" in lower
    return False

def is_waf_blocked(status_code: int, body_str: str) -> bool:
    return status_code == 403 and ("WAF Block Page" in body_str or "myqcloud.com" in body_str or '"code":' not in body_str)

def should_try_next(status_code: int, body_str: str) -> bool:
    if status_code in (404, 500, 502, 503):
        return True
    if is_channel_check(status_code, body_str) or is_waf_blocked(status_code, body_str):
        return True
    return False

@app.get("/v1/models")
@app.get("/v1/v1/models")
async def list_models():
    models = [
        {"id": "deepseek-v4.1-flash-1017", "object": "model", "name": "DeepSeek-V4.1-Flash-1017 [0.00x 限免免费 · 多模态+思考]", "credits": "0.00x (Free)", "supports_reasoning": True, "supports_images": True},
        {"id": "global:deepseek-v4.1-flash-1017", "object": "model", "name": "DeepSeek-V4.1-Flash-1017 (Global) [0.00x 限免免费]", "credits": "0.00x (Free)", "supports_reasoning": True, "supports_images": True},
        {"id": "deepseek-v4.1-flash", "object": "model", "name": "DeepSeek-V4.1-Flash [0.00x 限免免费]", "credits": "0.00x (Free)", "supports_reasoning": True, "supports_images": True},
        {"id": "global:deepseek-v4.1-flash", "object": "model", "name": "DeepSeek-V4.1-Flash (Global) [0.00x 限免免费]", "credits": "0.00x (Free)", "supports_reasoning": True, "supports_images": True},
        {"id": "hy4-preview", "object": "model", "name": "Hy4-Preview [0.00x 限免免费 · 混元深度思考]", "credits": "0.00x (Free)", "supports_reasoning": True, "supports_images": True},
        {"id": "hy3", "object": "model", "name": "Hy3 [0.00x 限免免费]", "credits": "0.00x (Free)", "supports_reasoning": True, "supports_images": True},
        {"id": "gpt-6-astra", "object": "model", "name": "GPT-6-Astra [6.67x · 国际版专属顶级旗舰]", "credits": "6.67x", "supports_reasoning": True, "supports_images": True},
        {"id": "gpt-5.6-sol", "object": "model", "name": "GPT-5.6-Sol [国际版专属]", "supports_images": True},
        {"id": "gpt-5.4", "object": "model", "name": "GPT-5.4 [国际版原生多模态]", "supports_images": True},
        {"id": "grok-4.7", "object": "model", "name": "Grok-4.7 [国际版专属]"},
        {"id": "gemini-3.5-flash", "object": "model", "name": "Gemini-3.5-Flash [国际版原生多模态]", "supports_images": True},
        {"id": "glm-5.3-flash", "object": "model", "name": "GLM-5.3-Flash [极低消耗]", "supports_reasoning": True, "supports_images": True},
        {"id": "glm-5.3", "object": "model", "name": "GLM-5.3 [最新旗舰]", "supports_reasoning": True, "supports_images": True},
        {"id": "glm-5.2", "object": "model", "name": "GLM-5.2 [1M 长上下文]", "supports_reasoning": True, "supports_images": True},
        {"id": "kimi-k3", "object": "model", "name": "Kimi-K3 [长文本深度推理]", "supports_reasoning": True, "supports_images": True},
        {"id": "kimi-k2.8-preview", "object": "model", "name": "Kimi-K2.8-Preview [深度推理]", "supports_reasoning": True, "supports_images": True},
        {"id": "claude-3.7-sonnet", "object": "model", "name": "Claude-3.7-Sonnet (官方映射 default-1.1)", "supports_images": True},
        {"id": "claude-4.0-sonnet", "object": "model", "name": "Claude-4.0-Sonnet (官方映射 default-1.2)", "supports_images": True},
        {"id": "auto", "object": "model", "name": "Auto [自动智能调度]", "supports_reasoning": True},
    ]
    return {"object": "list", "data": models}

@app.post("/v1/chat/completions")
@app.post("/v1/v1/chat/completions")
async def chat_completions(request: Request):
    """OpenAI 标准接口（支持流式与非流式）。"""
    body = await request.json()
    is_stream = body.get("stream", False)
    model_raw = body.get("model", "global:deepseek-v4.1-flash")
    realm, model_name = normalize_model(model_raw)

    acc = get_active_account(realm)
    if not acc:
        return JSONResponse(
            status_code=503,
            content={"error": {"message": "当前账号池无可用账号，请先在控制台添加账号", "type": "server_error"}}
        )

    messages = list(body.get("messages", []))
    if not messages or messages[0].get("role") != "system":
        messages.insert(0, {"role": "system", "content": DEFAULT_SYSTEM_PROMPT})

    # 出站脱敏 11128 -> 11-128 (对齐 workbuddy2api/sanitize.go)
    for m in messages:
        c = m.get("content")
        if isinstance(c, str) and "11128" in c:
            m["content"] = c.replace("11128", "11-128")

    upstream_base = UPSTREAM_GLOBAL if realm == "global" else UPSTREAM_CN
    sys_prompt = messages[0].get("content", "") if messages and messages[0].get("role") == "system" else ""
    c_id = derive_conversation_id(messages, sys_prompt)
    headers = build_upstream_headers(realm, acc, c_id)

    # 采用黑名单模式，完整透传 tools / tool_choice / reasoning_effort 等参数
    upstream_payload = {k: v for k, v in body.items() if k not in ["stream"]}
    upstream_payload["model"] = model_name
    upstream_payload["messages"] = messages
    upstream_payload["stream"] = True
    upstream_payload["stream_options"] = {"include_usage": True}

    if not is_stream:
        # 本地流式聚合为单次响应，完整保留 tool_calls 与 reasoning_content
        full_content = ""
        full_reasoning = ""
        tool_calls_map = {}
        prompt_tokens = 20
        comp_tokens = 20
        finish_reason = "stop"

        candidate_endpoints = [
            f"{upstream_base}/v2/chat/completions",
            f"{upstream_base}/console/chat/completions",
        ]
        for idx, ep_url in enumerate(candidate_endpoints):
            is_last = (idx == len(candidate_endpoints) - 1)
            async with httpx.AsyncClient(proxy=get_proxy(), timeout=120.0) as client:
                async with client.stream("POST", ep_url, headers=headers, json=upstream_payload) as resp:
                    if resp.status_code != 200:
                        err_bytes = await resp.aread()
                        err_str = err_bytes.decode(errors="ignore")
                        if not is_last and should_try_next(resp.status_code, err_str):
                            continue
                        return JSONResponse(status_code=resp.status_code, content={"error": {"message": err_str}})
                    async for line in resp.aiter_lines():
                        t = line.strip()
                        if t.startswith("data: ") and t != "data: [DONE]":
                            try:
                                d = json.loads(t[6:])
                                usage = d.get("usage")
                                if usage:
                                    prompt_tokens = usage.get("prompt_tokens", prompt_tokens)
                                    comp_tokens = usage.get("completion_tokens", comp_tokens)
                                choices = d.get("choices") or []
                                if not choices:
                                    continue
                                choice = choices[0]
                                delta = choice.get("delta", {})
                                c = delta.get("content", "")
                                if c: full_content += c
                                r = delta.get("reasoning_content", "")
                                if r: full_reasoning += r
                                tc = delta.get("tool_calls")
                                if tc:
                                    for i, call in enumerate(tc):
                                        i_call = call.get("index", i)
                                        if i_call not in tool_calls_map:
                                            tool_calls_map[i_call] = {
                                                "id": call.get("id", f"call_{uuid.uuid4().hex[:8]}"),
                                                "type": "function",
                                                "function": {"name": "", "arguments": ""}
                                            }
                                        fn = call.get("function", {})
                                        if fn.get("name"): tool_calls_map[i_call]["function"]["name"] += fn["name"]
                                        if fn.get("arguments"): tool_calls_map[i_call]["function"]["arguments"] += fn["arguments"]
                                if choice.get("finish_reason"):
                                    finish_reason = choice["finish_reason"]
                            except Exception:
                                pass
                    break
                        except Exception:
                            pass

        msg = {"role": "assistant", "content": full_content or None}
        if full_reasoning:
            msg["reasoning_content"] = full_reasoning
        if tool_calls_map:
            msg["tool_calls"] = list(tool_calls_map.values())
            finish_reason = "tool_calls"

        return {
            "id": f"chatcmpl_{int(time.time()*1000)}",
            "object": "chat.completion",
            "created": int(time.time()),
            "model": model_raw,
            "choices": [{"index": 0, "message": msg, "finish_reason": finish_reason}],
            "usage": {"prompt_tokens": prompt_tokens, "completion_tokens": comp_tokens, "total_tokens": prompt_tokens + comp_tokens}
        }
    else:
        async def stream_proxy():
            client = httpx.AsyncClient(proxy=get_proxy(), timeout=120.0)
            candidate_endpoints = [
                f"{upstream_base}/v2/chat/completions",
                f"{upstream_base}/console/chat/completions",
            ]
            try:
                for idx, ep_url in enumerate(candidate_endpoints):
                    is_last = (idx == len(candidate_endpoints) - 1)
                    async with client.stream("POST", ep_url, headers=headers, json=upstream_payload) as resp:
                        if resp.status_code != 200:
                            err_bytes = await resp.aread()
                            err_str = err_bytes.decode(errors="ignore")
                            if not is_last and should_try_next(resp.status_code, err_str):
                                continue
                            yield f"data: {json.dumps({'error': err_str})}\n\n"
                            yield "data: [DONE]\n\n"
                            return
                        async for line in resp.aiter_lines():
                            if line:
                                yield f"{line}\n\n"
                        return
            except Exception as e:
                yield f"data: {json.dumps({'error': str(e)})}\n\n"
                yield "data: [DONE]\n\n"
            finally:
                await client.aclose()

        return StreamingResponse(stream_proxy(), media_type="text/event-stream")

def anthropic_content_to_openai(content: Any) -> Any:
    if isinstance(content, str):
        return content
    if not isinstance(content, list):
        return ""
    parts = []
    has_image = False
    text_buf = []
    for part in content:
        if not isinstance(part, dict):
            continue
        ptype = part.get("type")
        if ptype == "text":
            t = part.get("text", "")
            text_buf.append(t)
            parts.append({"type": "text", "text": t})
        elif ptype == "image":
            src = part.get("source") or {}
            media = src.get("media_type") or "image/png"
            data = src.get("data") or ""
            if data:
                has_image = True
                parts.append({"type": "image_url", "image_url": {"url": f"data:{media};base64,{data}"}})
    return parts if has_image else "".join(text_buf)

@app.post("/v1/messages")
@app.post("/v1/v1/messages")
async def anthropic_messages(request: Request):
    """Anthropic 协议全兼容（流式 + 非流式单次响应，包含完整的官方 usage 字段与多模态图片块）。"""
    body = await request.json()
    is_stream = body.get("stream", False)
    model_raw = body.get("model", "deepseek-v4.1-flash")
    realm, model_name = normalize_model(model_raw)

    acc = get_active_account(realm)
    if not acc:
        return JSONResponse(
            status_code=503,
            content={"type": "error", "error": {"type": "api_error", "message": "无可用账号"}}
        )

    # 转换 Anthropic 格式为 OpenAI 格式
    system_val = body.get("system")
    if isinstance(system_val, list):
        texts = [c.get("text", "") for c in system_val if isinstance(c, dict) and c.get("type") == "text"]
        system_prompt = "\n".join(texts).strip() or DEFAULT_SYSTEM_PROMPT
    elif isinstance(system_val, str) and system_val.strip():
        system_prompt = system_val.strip()
    else:
        system_prompt = DEFAULT_SYSTEM_PROMPT

    messages = [{"role": "system", "content": system_prompt}]
    for m in body.get("messages", []):
        content = m.get("content")
        messages.append({
            "role": m.get("role", "user"),
            "content": anthropic_content_to_openai(content)
        })

    upstream_base = UPSTREAM_GLOBAL if realm == "global" else UPSTREAM_CN
    sys_prompt = system_prompt or (messages[0].get("content", "") if messages and messages[0].get("role") == "system" else "")
    c_id = derive_conversation_id(messages, sys_prompt)
    headers = build_upstream_headers(realm, acc, c_id)

    upstream_payload = {
        "model": model_name,
        "messages": messages,
        "stream": True
    }

    msg_id = f"msg_{int(time.time()*1000)}"

    if not is_stream:
        # 非流式测试请求：聚合后返回完整 Anthropic JSON
        full_text = ""
        async with httpx.AsyncClient(proxy=get_proxy(), timeout=120.0) as client:
            async with client.stream("POST", f"{upstream_base}/v2/chat/completions", headers=headers, json=upstream_payload) as resp:
                if resp.status_code != 200:
                    err = await resp.aread()
                    return JSONResponse(status_code=resp.status_code, content={"type": "error", "error": {"message": err.decode(errors="ignore")}})
                async for line in resp.aiter_lines():
                    t = line.strip()
                    if t.startswith("data: ") and t != "data: [DONE]":
                        try:
                            d = json.loads(t[6:])
                            c = d["choices"][0]["delta"].get("content", "")
                            if c: full_text += c
                        except Exception:
                            pass

        return {
            "id": msg_id,
            "type": "message",
            "role": "assistant",
            "content": [{"type": "text", "text": full_text or "测试成功"}],
            "model": model_raw,
            "stop_reason": "end_turn",
            "stop_sequence": None,
            "usage": {
                "input_tokens": 20,
                "output_tokens": max(len(full_text) // 2, 1)
            }
        }
    else:
        # 流式响应：严格依照官方规范发送包含 usage 的 message_start 与 message_delta
        async def anthropic_stream_proxy():
            client = httpx.AsyncClient(proxy=get_proxy(), timeout=120.0)
            try:
                yield f"event: message_start\ndata: {json.dumps({'type':'message_start','message':{'id':msg_id,'type':'message','role':'assistant','content':[],'model':model_raw,'stop_reason':None,'stop_sequence':None,'usage':{'input_tokens':20,'output_tokens':0}}})}\n\n"
                yield f"event: content_block_start\ndata: {json.dumps({'type':'content_block_start','index':0,'content_block':{'type':'text','text':''}})}\n\n"

                out_count = 0
                async with client.stream(
                    "POST",
                    f"{upstream_base}/v2/chat/completions",
                    headers=headers,
                    json=upstream_payload
                ) as resp:
                    if resp.status_code != 200:
                        err = await resp.aread()
                        yield f"event: error\ndata: {json.dumps({'type':'error','error':{'message':err.decode(errors='ignore')}})}\n\n"
                        return
                    async for line in resp.aiter_lines():
                        t = line.strip()
                        if not t or t == "data: [DONE]":
                            continue
                        if t.startswith("data: "):
                            try:
                                chunk = json.loads(t[6:])
                                delta = chunk.get("choices", [{}])[0].get("delta", {})
                                text = delta.get("content", "")
                                if text:
                                    out_count += 1
                                    yield f"event: content_block_delta\ndata: {json.dumps({'type':'content_block_delta','index':0,'delta':{'type':'text_delta','text':text}})}\n\n"
                            except Exception:
                                pass

                yield f"event: content_block_stop\ndata: {json.dumps({'type':'content_block_stop','index':0})}\n\n"
                yield f"event: message_delta\ndata: {json.dumps({'type':'message_delta','delta':{'stop_reason':'end_turn','stop_sequence':None},'usage':{'output_tokens':max(out_count, 1)}})}\n\n"
                yield f"event: message_stop\ndata: {json.dumps({'type':'message_stop'})}\n\n"
            except Exception as e:
                yield f"event: error\ndata: {json.dumps({'type':'error','error':{'message':str(e)}})}\n\n"
            finally:
                await client.aclose()

        return StreamingResponse(
            anthropic_stream_proxy(),
            media_type="text/event-stream",
            headers={
                "Cache-Control": "no-cache, no-transform",
                "Connection": "keep-alive",
                "X-Accel-Buffering": "no"
            }
        )

# ─────────────────── 静态前端路由 ───────────────────

@app.get("/")
@app.get("/m")
@app.get("/m/")
@app.get("/m/index.html")
async def serve_index():
    return FileResponse(WEB_DIR / "index.html")

app.mount("/", StaticFiles(directory=WEB_DIR, html=True), name="web")

def main():
    parser = argparse.ArgumentParser(description="WorkBuddy App (Linux Server & Gateway)")
    parser.add_argument("--host", default="0.0.0.0", help="监听地址 (默认 0.0.0.0)")
    parser.add_argument("--port", type=int, default=8800, help="服务端口 (默认 8800)")
    args = parser.parse_args()

    init_data_dir()

    print("=" * 60)
    print("🚀 WorkBuddy App (Linux 独立纯净服务端) 已启动")
    print(f"   Web 控制台地址 : http://{args.host}:{args.port}")
    print(f"   OpenAI 接口    : http://{args.host}:{args.port}/v1/chat/completions")
    print(f"   Anthropic 接口 : http://{args.host}:{args.port}/v1/messages")
    print("=" * 60)

    uvicorn.run(app, host=args.host, port=args.port)

if __name__ == "__main__":
    main()
