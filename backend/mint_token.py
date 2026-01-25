import argparse
import sys

from backend.auth import create_token
from backend.config import JWT_SECRET


def main() -> int:
    parser = argparse.ArgumentParser(description="Mint a JWT for FaceFusion API.")
    parser.add_argument("--user-id", required=True, help="User id to embed in the token.")
    args = parser.parse_args()

    if not JWT_SECRET:
        print("JWT_SECRET is not configured. Set it in .env or environment.", file=sys.stderr)
        return 1

    token = create_token(args.user_id)
    print(token)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
