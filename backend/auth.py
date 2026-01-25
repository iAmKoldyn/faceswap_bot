from typing import Optional

import jwt
from fastapi import Header, HTTPException, Request

from .config import AUTH_API_KEY, JWT_ALG, JWT_REQUIRED, JWT_SECRET


def verify_token(authorization: Optional[str]) -> str:
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="Missing bearer token")
    if not JWT_SECRET:
        raise HTTPException(status_code=500, detail="JWT_SECRET not configured")
    token = authorization.removeprefix("Bearer ").strip()
    try:
        payload = jwt.decode(token, JWT_SECRET, algorithms=[JWT_ALG], options={"verify_aud": False})
    except jwt.PyJWTError as exc:
        raise HTTPException(status_code=401, detail="Invalid token") from exc
    user_id = str(payload.get("sub") or payload.get("user_id") or "")
    if not user_id:
        raise HTTPException(status_code=401, detail="Token missing subject")
    return user_id


def get_current_user(request: Request, authorization: Optional[str] = Header(default=None)) -> str:
    if hasattr(request.state, "user_id"):
        return request.state.user_id
    if not JWT_REQUIRED and not authorization:
        request.state.user_id = "anonymous"
        return request.state.user_id
    user_id = verify_token(authorization)
    request.state.user_id = user_id
    return user_id


def require_api_key(x_api_key: Optional[str]) -> None:
    if not AUTH_API_KEY:
        raise HTTPException(status_code=500, detail="AUTH_API_KEY not configured")
    if not x_api_key or x_api_key != AUTH_API_KEY:
        raise HTTPException(status_code=401, detail="Invalid API key")


def create_token(user_id: str) -> str:
    if not JWT_SECRET:
        raise HTTPException(status_code=500, detail="JWT_SECRET not configured")
    payload = {"sub": user_id}
    return jwt.encode(payload, JWT_SECRET, algorithm=JWT_ALG)
