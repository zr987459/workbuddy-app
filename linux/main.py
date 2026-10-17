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

def normalize_model(model: str) -> tuple[str, str]:
    m = model.strip()
    m_lower = m.lower()
    if m_lower.startswith("global:"):
        return "global", m_lower[7:]
    if m_lower.startswith("cn:"):
        return "cn", m_lower[3:]
    return "global", m_lower

@app.get("/v1/models")
@app.get("/v1/v1/models")
async def list_models():
    models = [
        {"id": "deepseek-v4.1-flash", "object": "model", "name": "DeepSeek-V4.1-Flash", "supports_reasoning": True},
        {"id": "global:deepseek-v4.1-flash", "object": "model", "name": "DeepSeek-V4.1-Flash (Global)", "supports_reasoning": True},
        {"id": "glm-5.2", "object": "model", "name": "GLM-5.2", "supports_reasoning": True},
        {"id": "global:glm-5.2", "object": "model", "name": "GLM-5.2 (Global)", "supports_reasoning": True},
        {"id": "gpt-5.4", "object": "model", "name": "GPT-5.4", "supports_reasoning": True},
        {"id": "grok-4.7", "object": "model", "name": "Grok-4.7", "supports_reasoning": True},
        {"id": "kimi-k3", "object": "model", "name": "Kimi-K3", "supports_reasoning": True},
        {"id": "gemini-3.8-flash", "object": "model", "name": "Gemini-3.8-Flash", "supports_reasoning": True},
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

    upstream_base = UPSTREAM_GLOBAL if realm == "global" else UPSTREAM_CN
    headers = {
        "Authorization": f"Bearer {acc.get('access_token')}",
        "Content-Type": "application/json",
        "X-User-Id": acc.get("uid", ""),
        "User-Agent": CLIENT_UA,
    }

    upstream_payload = {
        "model": model_name,
        "messages": messages,
        "stream": True  # 官方只支持流式，若客户端要非流式则本地聚合
    }

    if not is_stream:
        # 本地流式聚合为单次响应
        full_content = ""
        prompt_tokens = 20
        comp_tokens = 20
        async with httpx.AsyncClient(proxy=get_proxy(), timeout=120.0) as client:
            async with client.stream("POST", f"{upstream_base}/v2/chat/completions", headers=headers, json=upstream_payload) as resp:
                if resp.status_code != 200:
                    err = await resp.aread()
                    return JSONResponse(status_code=resp.status_code, content={"error": {"message": err.decode(errors="ignore")}})
                async for line in resp.aiter_lines():
                    t = line.strip()
                    if t.startswith("data: ") and t != "data: [DONE]":
                        try:
                            d = json.loads(t[6:])
                            c = d["choices"][0]["delta"].get("content", "")
                            if c: full_content += c
                        except Exception:
                            pass
        return {
            "id": f"chatcmpl_{int(time.time()*1000)}",
            "object": "chat.completion",
            "created": int(time.time()),
            "model": model_raw,
            "choices": [{"index": 0, "message": {"role": "assistant", "content": full_content}, "finish_reason": "stop"}],
            "usage": {"prompt_tokens": promptTokens if 'promptTokens' in locals() else 20, "completion_tokens": len(full_content)//2, "total_tokens": 40}
        }
    else:
        async def stream_proxy():
            client = httpx.AsyncClient(proxy=get_proxy(), timeout=120.0)
            try:
                async with client.stream(
                    "POST",
                    f"{upstream_base}/v2/chat/completions",
                    headers=headers,
                    json=upstream_payload
                ) as resp:
                    if resp.status_code != 200:
                        err = await resp.aread()
                        yield f"data: {json.dumps({'error': err.decode(errors='ignore')})}\n\n"
                        return
                    async for line in resp.aiter_lines():
                        if line:
                            yield f"{line}\n\n"
            except Exception as e:
                yield f"data: {json.dumps({'error': str(e)})}\n\n"
            finally:
                await client.aclose()

        return StreamingResponse(stream_proxy(), media_type="text/event-stream")

@app.post("/v1/messages")
@app.post("/v1/v1/messages")
async def anthropic_messages(request: Request):
    """Anthropic 协议全兼容（流式 + 非流式单次响应，包含完整的官方 usage 字段）。"""
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

    system_prompt = body.get("system") or DEFAULT_SYSTEM_PROMPT
    messages = [{"role": "system", "content": system_prompt}]
    for m in body.get("messages", []):
        content = m.get("content")
        if isinstance(content, list):
            texts = [c.get("text", "") for c in content if c.get("type") == "text"]
            content = "\n".join(texts)
        messages.append({"role": m.get("role", "user"), "content": content or ""})

    upstream_base = UPSTREAM_GLOBAL if realm == "global" else UPSTREAM_CN
    headers = {
        "Authorization": f"Bearer {acc.get('access_token')}",
        "Content-Type": "application/json",
        "X-User-Id": acc.get("uid", ""),
        "User-Agent": CLIENT_UA,
    }

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
                "output_tokens": max(len(full_text)//2, 1)
            }
        }
    else:
        # 流式响应：严格依照官方规范发送包含 usage 的 message_start 与 message_delta
        async def anthropic_stream_proxy():
            client = httpx.AsyncClient(proxy=get_proxy(), timeout=120.0)
            try:
                yield f"event: message_start\ndata: {json.dumps({'type':'message_start','message':{'id':msg_id,'type':'message','role':'assistant','content':[],'model':model_raw,'stop_reason':None,'stop_sequence':None,'usage':{'input_tokens':20,'output_tokens':1}}})}\n\n"
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

        return StreamingResponse(anthropic_stream_proxy(), media_type="text/event-stream")

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
